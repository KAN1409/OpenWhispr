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

    fun resumePendingNotes(context: Context) {
        val repo = NotesRepository.getInstance(context)
        repo.reconcileAudioIntegrity()
        val notes = repo.getAllNotes()
        notes
            .filter { it.transcriptionState == Note.State.PENDING }
            .forEach { transcribeNoteAsync(context, it.id) }
        LocalModelBenchmark.resumeRequested(context, notes)
    }

    fun transcribeNoteAsync(context: Context, noteId: String) {
        if (!inFlight.add(noteId)) return
        val repo = NotesRepository.getInstance(context)
        val note = repo.getNote(noteId) ?: run {
            finishPrimary(context, noteId)
            return
        }

        // Central hook so every Voice Note transcription path (in-app, overlay,
        // imports/retries) can opt into benchmark mode without changing callers.
        LocalModelBenchmark.requestIfEnabled(context.applicationContext, noteId)
        repo.markTranscriptionPending(noteId)

        val audioFile = File(note.audioPath)
        if (!audioFile.exists() || audioFile.length() <= 44L) {
            repo.markTranscriptionFailed(noteId, "Recording audio file missing or empty")
            finishPrimary(context, noteId)
            return
        }

        val prefs = context.getSharedPreferences("openwhispr", Context.MODE_PRIVATE)
        val useLocal = prefs.getBoolean("use_local", true)

        thread(name = "note-transcription-$noteId") {
            try {
                val wavBytes = audioFile.readBytes()

                if (useLocal) {
                    val modelName = prefs.getString("model_name", "") ?: ""
                    if (modelName.isNotBlank()) {
                        val pcm = if (wavBytes.size > 44) {
                            wavBytes.copyOfRange(44, wavBytes.size)
                        } else {
                            ByteArray(0)
                        }
                        val samples = FloatArray(pcm.size / 2)
                        for (i in samples.indices) {
                            val lo = pcm[i * 2].toInt() and 0xFF
                            val hi = pcm[i * 2 + 1].toInt()
                            samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                        }

                        val residentText = WhisperAccessibilityService.instance
                            ?.transcribeWithResidentLocalModel(modelName, samples, 16000)

                        val rawText = residentText ?: LocalTranscriber.exclusive {
                            val local = LocalTranscriber.create(context, modelName)
                            if (local == null) {
                                null
                            } else {
                                try {
                                    local.transcribe(samples, 16000)
                                } finally {
                                    local.close()
                                }
                            }
                        }

                        if (rawText != null) {
                            if (rawText.isNotBlank()) {
                                repo.markTranscriptionSuccess(noteId, rawText)
                            } else {
                                repo.markTranscriptionFailed(noteId, "No speech detected")
                            }
                            finishPrimary(context, noteId)
                            return@thread
                        }
                    }
                    // If the selected local model cannot be loaded, preserve the
                    // existing behavior and fall back to Groq when an API key exists.
                }

                val apiKey = prefs.getString("api_key", "") ?: ""
                if (apiKey.isBlank()) {
                    repo.markTranscriptionFailed(
                        noteId,
                        "No Groq API key configured. Tap Settings to set API key or download a local model."
                    )
                    finishPrimary(context, noteId)
                    return@thread
                }

                TranscriberClient.transcribe(wavBytes, apiKey) { result ->
                    try {
                        if (result.text != null && result.text.isNotBlank()) {
                            repo.markTranscriptionSuccess(noteId, result.text)
                        } else {
                            repo.markTranscriptionFailed(
                                noteId,
                                result.error ?: "Transcription produced no text"
                            )
                        }
                    } finally {
                        finishPrimary(context, noteId)
                    }
                }
            } catch (e: LinkageError) {
                Log.e(TAG, "Native transcription failed for note $noteId", e)
                repo.markTranscriptionFailed(
                    noteId,
                    e.message ?: "Native transcription runtime error"
                )
                finishPrimary(context, noteId)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed for note $noteId", e)
                repo.markTranscriptionFailed(noteId, e.message ?: "Transcription error")
                finishPrimary(context, noteId)
            }
        }
    }

    private fun finishPrimary(context: Context, noteId: String) {
        inFlight.remove(noteId)
        LocalModelBenchmark.runIfRequested(context.applicationContext, noteId)
    }
}
