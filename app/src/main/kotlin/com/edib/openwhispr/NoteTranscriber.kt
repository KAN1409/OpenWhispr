package com.edib.openwhispr

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Executes transcription for persistent Voice Notes by reusing the existing
 * transcription pipeline (Groq cloud or Sherpa-ONNX local).
 *
 * Enforces the Raw Transcript Contract:
 * Returned ASR text is preserved verbatim without post-processing cleanup,
 * translation, or modification.
 */
object NoteTranscriber {
    private const val TAG = "NoteTranscriber"
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Shown when Local mode is selected and Local transcription could not be
     * completed. The recording is never discarded and Cloud is never invoked
     * on the user's behalf; the wording tells them exactly what happened and
     * that switching is a deliberate choice.
     */
    const val LOCAL_FAILED_MESSAGE =
        "Local transcription failed. Your recording is safe. Retry, or change the " +
            "transcription method in Settings to use Cloud."

    fun resumePendingNotes(context: Context) {
        val repo = NotesRepository.getInstance(context)
        repo.reconcileAudioIntegrity()
        repo.getAllNotes()
            .filter { it.transcriptionState == Note.State.PENDING }
            .forEach { transcribeNoteAsync(context, it.id) }
    }

    fun transcribeNoteAsync(context: Context, noteId: String) {
        if (!inFlight.add(noteId)) return
        val repo = NotesRepository.getInstance(context)
        val note = repo.getNote(noteId) ?: run {
            inFlight.remove(noteId)
            return
        }

        repo.markTranscriptionPending(noteId)

        val audioFile = File(note.audioPath)
        if (!audioFile.exists() || audioFile.length() <= 44L) {
            repo.markTranscriptionFailed(noteId, "Recording audio file missing or empty")
            inFlight.remove(noteId)
            return
        }

        val prefs = context.getSharedPreferences("openwhispr", Context.MODE_PRIVATE)
        val useLocal = prefs.getBoolean("use_local", true)

        thread(name = "note-transcription-$noteId") {
            try {
                val wavBytes = audioFile.readBytes()

                if (useLocal) {
                    // LOCAL ROUTING CONTRACT
                    // When the user has selected Local, this note is transcribed
                    // locally or not at all. Any failure is surfaced explicitly
                    // and the recording is preserved. It must never fall through
                    // to a cloud provider, because a cloud transcript stored as a
                    // local result misrepresents where the text came from.
                    val modelName = prefs.getString("model_name", "") ?: ""
                    val outcome = runLocal(context, modelName, wavBytes)
                    when (outcome) {
                        is LocalOutcome.Text ->
                            repo.markTranscriptionSuccess(noteId, outcome.text)
                        is LocalOutcome.Blank ->
                            repo.markTranscriptionFailed(noteId, "No speech detected")
                        is LocalOutcome.Failed -> {
                            Log.e(TAG, "Local transcription failed for note $noteId: ${outcome.reason}")
                            repo.markTranscriptionFailed(noteId, outcome.reason)
                        }
                    }
                    inFlight.remove(noteId)
                    return@thread
                }

                // Cloud transcription via Groq — only reachable when the user has
                // explicitly chosen Cloud mode.
                val apiKey = prefs.getString("api_key", "") ?: ""
                if (apiKey.isBlank()) {
                    repo.markTranscriptionFailed(noteId, "No Groq API key configured. Tap Settings to set API key or change to Local mode.")
                    inFlight.remove(noteId)
                    return@thread
                }

                TranscriberClient.transcribe(wavBytes, apiKey) { result ->
                    try {
                        if (result.text != null && result.text.isNotBlank()) {
                            repo.markTranscriptionSuccess(noteId, result.text)
                        } else {
                            val err = result.error ?: "Transcription produced no text"
                            repo.markTranscriptionFailed(noteId, err)
                        }
                    } finally {
                        inFlight.remove(noteId)
                    }
                }
            } catch (e: OutOfMemoryError) {
                // Memory exhaustion must not take the process down, and must not
                // be answered by quietly switching the note to a cloud provider.
                Log.e(TAG, "Out of memory transcribing note $noteId", e)
                repo.markTranscriptionFailed(noteId, LOCAL_FAILED_MESSAGE)
                inFlight.remove(noteId)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed for note $noteId", e)
                repo.markTranscriptionFailed(noteId, if (useLocal) LOCAL_FAILED_MESSAGE
                else (e.message ?: "Transcription error"))
                inFlight.remove(noteId)
            }
        }
    }

    /** Result of a Local-only transcription attempt. */
    internal sealed class LocalOutcome {
        data class Text(val text: String) : LocalOutcome()
        object Blank : LocalOutcome()
        data class Failed(val reason: String) : LocalOutcome()
    }

    /**
     * Run transcription through the on-device recognizer only.
     *
     * Every failure mode (missing model, undetected model type, failed native
     * init, out of memory, inference exception) resolves to [LocalOutcome.Failed]
     * rather than an exception escaping to the caller, so that no code path can
     * accidentally continue into a cloud request.
     */
    internal fun runLocal(
        context: Context,
        modelName: String,
        wavBytes: ByteArray
    ): LocalOutcome =
        runLocal(File(context.filesDir, "models/$modelName"), modelName, wavBytes, context)

    /**
     * Core of the local attempt, with the model directory injected so the
     * routing contract is testable without a device or a native runtime.
     *
     * [context] is only used to reach the cached recognizer; when it is null
     * the attempt stops at the pre-flight checks, which is precisely the
     * "initialisation failed" path under test.
     */
    internal fun runLocal(
        modelDir: File?,
        modelName: String,
        wavBytes: ByteArray,
        context: Context? = null
    ): LocalOutcome {
        if (modelName.isBlank() || modelDir == null || !modelDir.isDirectory) {
            return LocalOutcome.Failed(LOCAL_FAILED_MESSAGE)
        }
        if (context == null) {
            // Pre-flight checks passed but no runtime is available to decode
            // with: still an explicit local failure, never a cloud fallback.
            return LocalOutcome.Failed(LOCAL_FAILED_MESSAGE)
        }
        return try {
            val rawText = LocalTranscriber.withShared(context, modelName) { local ->
                val pcm = if (wavBytes.size > 44) wavBytes.copyOfRange(44, wavBytes.size) else ByteArray(0)
                val samples = FloatArray(pcm.size / 2)
                for (i in samples.indices) {
                    val lo = pcm[i * 2].toInt() and 0xFF
                    val hi = pcm[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }
                local.transcribe(samples, 16000)
            }
            when {
                rawText == null -> LocalOutcome.Failed(LOCAL_FAILED_MESSAGE)
                rawText.isBlank() -> LocalOutcome.Blank
                else -> LocalOutcome.Text(rawText)
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory in local transcription", e)
            LocalOutcome.Failed(LOCAL_FAILED_MESSAGE)
        } catch (e: Exception) {
            Log.e(TAG, "Local transcription exception", e)
            LocalOutcome.Failed(LOCAL_FAILED_MESSAGE)
        }
    }
}
