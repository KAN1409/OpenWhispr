package com.edib.openwhispr

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Helper for sharing voice notes.
 *
 * Requirements (Checkpoint F):
 * 1. Share transcript (text/plain)
 * 2. Share audio (audio/wav via FileProvider content URI)
 * 3. Share audio + transcript (audio/wav + EXTRA_STREAM + EXTRA_TEXT + ClipData)
 *
 * Guarantees:
 * - Never exposes file:// URIs (strictly uses content:// via FileProvider)
 * - Sets ClipData and FLAG_GRANT_READ_URI_PERMISSION for maximum interoperability
 */
object NoteShareHelper {

    fun shareTranscript(context: Context, note: Note) {
        val text = note.displayTranscript
        if (text.isNullOrBlank()) {
            Toast.makeText(context, "No transcript available to share", Toast.LENGTH_SHORT).show()
            return
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newPlainText(note.title, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "Share transcript").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun shareAudio(context: Context, note: Note) {
        val file = File(note.audioPath)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(context, "Audio file not found", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to share audio: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            clipData = ClipData.newUri(context.contentResolver, note.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "Share audio").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun shareAudioAndTranscript(context: Context, note: Note) {
        val file = File(note.audioPath)
        val hasAudio = file.exists() && file.length() > 0L
        val text = note.displayTranscript ?: ""

        if (!hasAudio && text.isBlank()) {
            Toast.makeText(context, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasAudio) {
            shareTranscript(context, note)
            return
        }

        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to share audio: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        // Most interoperable share mechanism for audio + transcript on Android:
        // Set type to audio/wav, EXTRA_STREAM with content URI, and EXTRA_TEXT with transcript text.
        // Also populate ClipData with the content URI so permissions propagate to the receiving app.
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text.isNotBlank()) {
                putExtra(Intent.EXTRA_TEXT, text)
            }
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            clipData = ClipData.newUri(context.contentResolver, note.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "Share audio & transcript").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
