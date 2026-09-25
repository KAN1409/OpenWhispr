package com.edib.openwhispr

import android.content.Context
import android.util.Log
import java.io.File
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

    fun transcribeNoteAsync(context: Context, noteId: String) {
        val repo = NotesRepository.getInstance(context)
        val note = repo.getNote(noteId) ?: return

        repo.markTranscriptionPending(noteId)

        val audioFile = File(note.audioPath)
        if (!audioFile.exists() || audioFile.length() <= 44L) {
            repo.markTranscriptionFailed(noteId, "Recording audio file missing or empty")
            return
        }

        val prefs = context.getSharedPreferences("openwhispr", Context.MODE_PRIVATE)
        val useLocal = prefs.getBoolean("use_local", true)

        thread(name = "note-transcription-$noteId") {
            try {
                val wavBytes = audioFile.readBytes()

                if (useLocal) {
                    val modelName = prefs.getString("model_name", "") ?: ""
                    val local = if (modelName.isNotBlank()) {
                        LocalTranscriber.create(context, modelName)
                    } else {
                        val available = LocalTranscriber.availableModels(context)
                        if (available.isNotEmpty()) LocalTranscriber.create(context, available.first()) else null
                    }

                    if (local != null) {
                        // Extract PCM from WAV (skip 44-byte header)
                        val pcm = if (wavBytes.size > 44) wavBytes.copyOfRange(44, wavBytes.size) else ByteArray(0)
                        val samples = FloatArray(pcm.size / 2)
                        for (i in samples.indices) {
                            val lo = pcm[i * 2].toInt() and 0xFF
                            val hi = pcm[i * 2 + 1].toInt()
                            samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                        }

                        val rawText = local.transcribe(samples, 16000)
                        if (rawText.isNotBlank()) {
                            repo.markTranscriptionSuccess(noteId, rawText)
                        } else {
                            repo.markTranscriptionFailed(noteId, "No speech detected")
                        }
                        return@thread
                    }
                    // If local model is not loaded, fall back to cloud if key available
                }

                // Cloud transcription via Groq
                val apiKey = prefs.getString("api_key", "") ?: ""
                if (apiKey.isBlank()) {
                    repo.markTranscriptionFailed(noteId, "No Groq API key configured. Tap Settings to set API key or download a local model.")
                    return@thread
                }

                TranscriberClient.transcribe(wavBytes, apiKey) { result ->
                    if (result.text != null && result.text.isNotBlank()) {
                        repo.markTranscriptionSuccess(noteId, result.text)
                    } else {
                        val err = result.error ?: "Transcription produced no text"
                        repo.markTranscriptionFailed(noteId, err)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed for note $noteId", e)
                repo.markTranscriptionFailed(noteId, e.message ?: "Transcription error")
            }
        }
    }
}
