package com.edib.openwhispr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Handles in-app audio recording for new voice notes.
 * Captures raw PCM at 16,000 Hz 16-bit Mono.
 */
class InAppNoteRecorder(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private var isRecordingInternal = false
    private var startTimeMs = 0L

    val isRecording: Boolean
        get() = isRecordingInternal

    var onTickListener: ((elapsedMs: Long, amplitude: Float) -> Unit)? = null

    fun start(onError: (String) -> Unit): Boolean {
        if (isRecordingInternal) return true

        val sampleRate = 16000
        val bufSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufSize <= 0) {
            onError("Audio recorder unavailable")
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: SecurityException) {
            onError("Audio permission denied")
            return false
        } catch (e: Exception) {
            onError("Failed to initialize recorder: ${e.message}")
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onError("Audio recorder could not be initialized")
            record.release()
            return false
        }

        pcmStream = ByteArrayOutputStream()
        audioRecord = record
        isRecordingInternal = true
        startTimeMs = System.currentTimeMillis()

        try {
            record.startRecording()
        } catch (e: Exception) {
            isRecordingInternal = false
            audioRecord = null
            pcmStream = null
            record.release()
            onError("Unable to start recording: ${e.message}")
            return false
        }

        thread(name = "in-app-audio-record") {
            val buf = ByteArray(bufSize)
            while (isRecordingInternal) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    pcmStream?.write(buf, 0, n)

                    // Calculate RMS amplitude for visualization
                    var sum = 0.0
                    val samplesCount = n / 2
                    for (i in 0 until samplesCount) {
                        val lo = buf[i * 2].toInt() and 0xFF
                        val hi = buf[i * 2 + 1].toInt()
                        val sample = ((hi shl 8) or lo).toShort()
                        sum += sample * sample
                    }
                    val rms = sqrt(sum / samplesCount)
                    val amp = (rms / 32768.0).toFloat().coerceIn(0f, 1f)

                    val elapsed = System.currentTimeMillis() - startTimeMs
                    handler.post {
                        if (isRecordingInternal) {
                            onTickListener?.invoke(elapsed, amp)
                        }
                    }
                }
            }
        }

        return true
    }

    /**
     * Stops recording and immediately preserves audio durably into NotesRepository.
     * Triggers transcription asynchronously.
     */
    fun stopAndSave(): Note? {
        if (!isRecordingInternal) return null
        isRecordingInternal = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("InAppNoteRecorder", "Error stopping AudioRecord", e)
        }
        audioRecord = null

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) return null

        val repo = NotesRepository.getInstance(context)
        val note = repo.createAndSaveNoteFromPcm(pcm, 16000)

        // Asynchronously transcribe the durable note
        NoteTranscriber.transcribeNoteAsync(context, note.id)

        return note
    }

    fun cancel() {
        isRecordingInternal = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        pcmStream = null
    }
}
