package com.edib.openwhispr

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a persistent voice note.
 *
 * Invariants:
 * 1. Audio is saved durably before transcription is attempted.
 * 2. Original transcription is immutable once set; edits are stored separately in [editedTranscript].
 * 3. Transcription states: PENDING -> COMPLETE or FAILED.
 */
data class Note(
    val id: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val audioPath: String,
    val audioDurationMs: Long,
    val originalTranscript: String? = null,
    val editedTranscript: String? = null,
    val transcriptionState: State = State.PENDING,
    val isPinned: Boolean = false,
    val errorMessage: String? = null
) {
    enum class State {
        PENDING,
        COMPLETE,
        FAILED
    }

    /**
     * The transcript to display to the user.
     * Prefers user-edited transcript if available, falling back to original ASR transcript.
     */
    val displayTranscript: String?
        get() = editedTranscript ?: originalTranscript

    /** True if user has edited the transcript away from original */
    val hasEditedTranscript: Boolean
        get() = editedTranscript != null && editedTranscript != originalTranscript

    /** Deterministic title in V1: "Voice note · <time>" */
    val title: String
        get() = "Voice note · " + formatTime(createdAt)

    companion object {
        fun formatTime(timestampMs: Long): String {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            return sdf.format(Date(timestampMs))
        }

        fun formatDateHeader(timestampMs: Long): String {
            val now = System.currentTimeMillis()
            val dayMs = 24 * 60 * 60 * 1000L
            val sdfDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val todayStr = sdfDay.format(Date(now))
            val noteDayStr = sdfDay.format(Date(timestampMs))

            if (todayStr == noteDayStr) return "Today"

            val yesterdayCal = Date(now - dayMs)
            if (sdfDay.format(yesterdayCal) == noteDayStr) return "Yesterday"

            val sdfFull = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
            return sdfFull.format(Date(timestampMs))
        }

        fun formatDuration(ms: Long): String {
            val totalSecs = (ms / 1000).coerceAtLeast(0)
            val mins = totalSecs / 60
            val secs = totalSecs % 60
            return String.format(Locale.US, "%d:%02d", mins, secs)
        }

        fun formatElapsed(ms: Long): String {
            val totalSecs = (ms / 1000).coerceAtLeast(0)
            val mins = totalSecs / 60
            val secs = totalSecs % 60
            return String.format(Locale.US, "%02d:%02d", mins, secs)
        }

        fun formatFooterTime(timestampMs: Long): String {
            val diff = System.currentTimeMillis() - timestampMs
            return if (diff in 0..120_000L) {
                "Just now"
            } else {
                formatTime(timestampMs)
            }
        }
    }
}
