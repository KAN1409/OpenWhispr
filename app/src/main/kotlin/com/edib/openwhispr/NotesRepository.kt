package com.edib.openwhispr

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

interface NoteStorage {
    fun insert(note: Note): Boolean
    fun update(note: Note): Boolean
    fun get(id: String): Note?
    fun getAll(): List<Note>
    fun search(query: String): List<Note>
    fun delete(id: String): Boolean
}

class SQLiteNoteStorage(context: Context) : NoteStorage {
    private val dbHelper = DatabaseHelper(context.applicationContext)

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, "openwhispr_notes.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE notes (
                    id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    modified_at INTEGER NOT NULL,
                    audio_path TEXT NOT NULL,
                    audio_duration_ms INTEGER NOT NULL,
                    original_transcript TEXT,
                    edited_transcript TEXT,
                    transcription_state TEXT NOT NULL,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    error_message TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_notes_created_at ON notes(created_at DESC)")
            db.execSQL("CREATE INDEX idx_notes_pinned ON notes(is_pinned DESC, created_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future migrations
        }
    }

    override fun insert(note: Note): Boolean {
        return try {
            val db = dbHelper.writableDatabase
            val cv = noteToContentValues(note)
            db.insertWithOnConflict("notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L
        } catch (e: Exception) {
            Log.e("SQLiteNoteStorage", "Insert failed", e)
            false
        }
    }

    override fun update(note: Note): Boolean {
        return try {
            val db = dbHelper.writableDatabase
            val cv = noteToContentValues(note)
            db.update("notes", cv, "id = ?", arrayOf(note.id)) > 0
        } catch (e: Exception) {
            Log.e("SQLiteNoteStorage", "Update failed", e)
            false
        }
    }

    override fun get(id: String): Note? {
        return try {
            val db = dbHelper.readableDatabase
            db.query(
                "notes",
                null,
                "id = ?",
                arrayOf(id),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursorToNote(cursor) else null
            }
        } catch (e: Exception) {
            Log.e("SQLiteNoteStorage", "Get failed", e)
            null
        }
    }

    override fun getAll(): List<Note> {
        val list = mutableListOf<Note>()
        try {
            val db = dbHelper.readableDatabase
            db.query(
                "notes",
                null,
                null,
                null,
                null,
                null,
                "is_pinned DESC, created_at DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursorToNote(cursor))
                }
            }
        } catch (e: Exception) {
            Log.e("SQLiteNoteStorage", "GetAll failed", e)
        }
        return list
    }

    override fun search(query: String): List<Note> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return getAll()
        // Searches locally across title, originalTranscript, and editedTranscript.
        // Performs full Unicode case-insensitive matching supporting Arabic, English, and mixed scripts.
        return getAll().filter { note ->
            note.title.contains(trimmed, ignoreCase = true) ||
                note.originalTranscript?.contains(trimmed, ignoreCase = true) == true ||
                note.editedTranscript?.contains(trimmed, ignoreCase = true) == true
        }
    }

    override fun delete(id: String): Boolean {
        return try {
            val db = dbHelper.writableDatabase
            db.delete("notes", "id = ?", arrayOf(id)) > 0
        } catch (e: Exception) {
            Log.e("SQLiteNoteStorage", "Delete failed", e)
            false
        }
    }

    private fun noteToContentValues(note: Note): ContentValues {
        return ContentValues().apply {
            put("id", note.id)
            put("created_at", note.createdAt)
            put("modified_at", note.modifiedAt)
            put("audio_path", note.audioPath)
            put("audio_duration_ms", note.audioDurationMs)
            put("original_transcript", note.originalTranscript)
            put("edited_transcript", note.editedTranscript)
            put("transcription_state", note.transcriptionState.name)
            put("is_pinned", if (note.isPinned) 1 else 0)
            put("error_message", note.errorMessage)
        }
    }

    private fun cursorToNote(c: Cursor): Note {
        val id = c.getString(c.getColumnIndexOrThrow("id"))
        val createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
        val modifiedAt = c.getLong(c.getColumnIndexOrThrow("modified_at"))
        val audioPath = c.getString(c.getColumnIndexOrThrow("audio_path"))
        val audioDurationMs = c.getLong(c.getColumnIndexOrThrow("audio_duration_ms"))
        val originalTranscript = c.getString(c.getColumnIndexOrThrow("original_transcript"))
        val editedTranscript = c.getString(c.getColumnIndexOrThrow("edited_transcript"))
        val stateStr = c.getString(c.getColumnIndexOrThrow("transcription_state"))
        val state = try {
            Note.State.valueOf(stateStr)
        } catch (_: Exception) {
            Note.State.PENDING
        }
        val isPinned = c.getInt(c.getColumnIndexOrThrow("is_pinned")) == 1
        val errorMessage = c.getString(c.getColumnIndexOrThrow("error_message"))

        return Note(
            id = id,
            createdAt = createdAt,
            modifiedAt = modifiedAt,
            audioPath = audioPath,
            audioDurationMs = audioDurationMs,
            originalTranscript = originalTranscript,
            editedTranscript = editedTranscript,
            transcriptionState = state,
            isPinned = isPinned,
            errorMessage = errorMessage
        )
    }
}

class InMemoryNoteStorage : NoteStorage {
    private val notes = mutableMapOf<String, Note>()

    override fun insert(note: Note): Boolean {
        notes[note.id] = note
        return true
    }

    override fun update(note: Note): Boolean {
        if (!notes.containsKey(note.id)) return false
        notes[note.id] = note
        return true
    }

    override fun get(id: String): Note? = notes[id]

    override fun getAll(): List<Note> =
        notes.values.sortedWith(compareByDescending<Note> { it.isPinned }.thenByDescending { it.createdAt })

    override fun search(query: String): List<Note> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return getAll()
        return getAll().filter { note ->
            note.title.contains(trimmed, ignoreCase = true) ||
                note.originalTranscript?.contains(trimmed, ignoreCase = true) == true ||
                note.editedTranscript?.contains(trimmed, ignoreCase = true) == true
        }
    }

    override fun delete(id: String): Boolean = notes.remove(id) != null
}

/**
 * Single source of truth for persistent voice notes.
 * Guarantees that audio is stored on disk before any network operations.
 */
class NotesRepository(
    private val context: Context?,
    private val storage: NoteStorage
) {
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private val mainHandler: Handler? by lazy {
        try {
            val looper = Looper.getMainLooper()
            if (looper != null) Handler(looper) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun notifyListeners() {
        val copy = synchronized(listeners) { listeners.toList() }
        val h = mainHandler
        if (h != null) {
            h.post { copy.forEach { it.invoke() } }
        } else {
            copy.forEach { it.invoke() }
        }
    }

    val notesDir: File
        get() {
            val base = context?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "openwhispr_test")
            return File(base, "notes").apply { mkdirs() }
        }

    /**
     * INVARIANT 1: Durably stores raw PCM bytes as a .wav file on disk FIRST,
     * and records the Note in database before returning.
     */
    fun createAndSaveNoteFromPcm(pcm: ByteArray, sampleRate: Int = 16000): Note {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val durationMs = if (pcm.isNotEmpty()) {
            (pcm.size.toLong() * 1000L) / (sampleRate.toLong() * 2L)
        } else {
            0L
        }

        val wavBytes = WavWriter.encode(pcm, sampleRate = sampleRate)
        val audioFile = File(notesDir, "$id.wav")

        writeDurably(audioFile, wavBytes)

        val note = Note(
            id = id,
            createdAt = now,
            modifiedAt = now,
            audioPath = audioFile.absolutePath,
            audioDurationMs = durationMs,
            originalTranscript = null,
            editedTranscript = null,
            transcriptionState = Note.State.PENDING,
            isPinned = false
        )

        val inserted = storage.insert(note)
        if (!inserted) {
            // The audio has already been fsync'd.  Keep it for startup
            // reconciliation rather than turning a metadata failure into data loss.
            throw IllegalStateException("Failed to insert note into database; audio retained at ${audioFile.absolutePath}")
        }

        notifyListeners()
        return note
    }

    /**
     * Imports an already-canonical PCM16 mono 16-kHz WAV without loading the
     * whole file into memory. The source remains untouched.
     */
    fun createAndSaveNoteFromCanonicalWavFile(sourceWav: File, durationMs: Long): Note {
        require(sourceWav.isFile && sourceWav.length() > 44L) { "Imported WAV is empty" }
        require(isCompleteWav(sourceWav)) { "Imported WAV is incomplete" }

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val audioFile = File(notesDir, "$id.wav")

        writeDurably(audioFile, sourceWav)

        val note = Note(
            id = id,
            createdAt = now,
            modifiedAt = now,
            audioPath = audioFile.absolutePath,
            audioDurationMs = durationMs,
            originalTranscript = null,
            editedTranscript = null,
            transcriptionState = Note.State.PENDING,
            isPinned = false
        )

        val inserted = storage.insert(note)
        if (!inserted) {
            throw IllegalStateException(
                "Failed to insert imported note into database; audio retained at ${audioFile.absolutePath}"
            )
        }

        notifyListeners()
        return note
    }

    /**
     * Keeps the exact imported source beside the canonical ASR WAV. It is not
     * decoded or rewritten and is intentionally outside the playable .wav
     * reconciliation path.
     */
    fun preserveImportedSource(noteId: String, source: File): File {
        require(source.isFile && source.length() > 0L) { "Imported source is empty" }
        val destination = File(notesDir, "$noteId.source")
        writeDurably(destination, source)
        return destination
    }

    /**
     * Durably stores complete WAV bytes directly.
     */
    fun createAndSaveNoteFromWav(wavBytes: ByteArray, durationMs: Long): Note {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val audioFile = File(notesDir, "$id.wav")

        writeDurably(audioFile, wavBytes)

        val note = Note(
            id = id,
            createdAt = now,
            modifiedAt = now,
            audioPath = audioFile.absolutePath,
            audioDurationMs = durationMs,
            originalTranscript = null,
            editedTranscript = null,
            transcriptionState = Note.State.PENDING,
            isPinned = false
        )

        val inserted = storage.insert(note)
        if (!inserted) {
            throw IllegalStateException("Failed to insert note into database; audio retained at ${audioFile.absolutePath}")
        }

        notifyListeners()
        return note
    }

    private fun writeDurably(destination: File, source: File) {
        val directory = destination.parentFile
            ?: throw IllegalStateException("Recording has no parent directory")
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IllegalStateException("Unable to create notes directory")
        }
        val staging = File(directory, "${destination.name}.part")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(staging).use { out ->
                    input.copyTo(out, 64 * 1024)
                    out.flush()
                    out.fd.sync()
                }
            }
            if (!staging.renameTo(destination)) {
                throw IllegalStateException("Unable to commit imported recording")
            }
        } catch (e: Exception) {
            staging.delete()
            throw e
        }
    }

    private fun writeDurably(destination: File, bytes: ByteArray) {
        val directory = destination.parentFile
            ?: throw IllegalStateException("Recording has no parent directory")
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IllegalStateException("Unable to create notes directory")
        }
        val staging = File(directory, "${destination.name}.part")
        try {
            FileOutputStream(staging).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!staging.renameTo(destination)) {
                throw IllegalStateException("Unable to commit recording file")
            }
        } catch (e: Exception) {
            // An incomplete staging file is never treated as a playable recording.
            staging.delete()
            throw e
        }
    }

    /**
     * Repairs crash windows without deleting completed recordings. Valid orphan WAVs are
     * imported as PENDING notes; interrupted deletes are either rolled back or completed.
     */
    @Synchronized
    fun reconcileAudioIntegrity() {
        try {
            val allNotes = storage.getAll()
            val notesByPath = allNotes.associateBy { File(it.audioPath).absolutePath }
            val files = notesDir.listFiles() ?: return

            // Recover/finish a delete that was interrupted between file rename and DB delete.
            for (file in files.filter { it.name.endsWith(".deleting") }) {
                val original = File(file.parentFile, file.name.removeSuffix(".deleting"))
                if (notesByPath.containsKey(original.absolutePath)) file.renameTo(original) else file.delete()
            }

            for (file in files) {
                if (!file.isFile || !file.name.endsWith(".wav") || notesByPath.containsKey(file.absolutePath)) continue
                if (!isCompleteWav(file)) continue
                val id = file.name.removeSuffix(".wav")
                if (runCatching { UUID.fromString(id) }.isFailure) continue
                val now = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                val durationMs = ((file.length() - 44L).coerceAtLeast(0L) * 1000L) / (16000L * 2L)
                storage.insert(
                    Note(
                        id = id,
                        createdAt = now,
                        modifiedAt = now,
                        audioPath = file.absolutePath,
                        audioDurationMs = durationMs,
                        transcriptionState = Note.State.PENDING
                    )
                )
            }

            // Surface broken references instead of leaving an endless PENDING state.
            storage.getAll().forEach { note ->
                if (!File(note.audioPath).isFile) {
                    storage.update(
                        note.copy(
                            transcriptionState = Note.State.FAILED,
                            errorMessage = "Recording audio file missing",
                            modifiedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            notifyListeners()
        } catch (e: Exception) {
            Log.w("NotesRepository", "Audio reconciliation error", e)
        }
    }

    @Deprecated("Use reconcileAudioIntegrity; completed orphan audio must not be deleted")
    fun cleanupOrphanAudioFiles() = reconcileAudioIntegrity()

    private fun isCompleteWav(file: File): Boolean {
        if (file.length() < 44L) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(44)
                if (input.read(header) != 44) return false
                fun ascii(offset: Int, size: Int) = String(header, offset, size, Charsets.US_ASCII)
                fun littleEndianInt(offset: Int): Long =
                    (header[offset].toLong() and 0xff) or
                        ((header[offset + 1].toLong() and 0xff) shl 8) or
                        ((header[offset + 2].toLong() and 0xff) shl 16) or
                        ((header[offset + 3].toLong() and 0xff) shl 24)
                ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE" && ascii(36, 4) == "data" &&
                    littleEndianInt(40) == file.length() - 44L
            }
        } catch (_: Exception) {
            false
        }
    }

    fun getAllNotes(): List<Note> = storage.getAll()

    fun getNote(id: String): Note? = storage.get(id)

    fun searchNotes(query: String): List<Note> = storage.search(query)

    fun markTranscriptionSuccess(id: String, rawTranscript: String): Boolean {
        val note = storage.get(id) ?: return false
        val updated = note.copy(
            // The first ASR result is evidence. Retries must never rewrite it.
            originalTranscript = note.originalTranscript ?: rawTranscript,
            transcriptionState = Note.State.COMPLETE,
            modifiedAt = System.currentTimeMillis(),
            errorMessage = null
        )
        val ok = storage.update(updated)
        if (ok) notifyListeners()
        return ok
    }

    fun markTranscriptionFailed(id: String, error: String): Boolean {
        val note = storage.get(id) ?: return false
        val updated = note.copy(
            transcriptionState = Note.State.FAILED,
            errorMessage = error,
            modifiedAt = System.currentTimeMillis()
        )
        val ok = storage.update(updated)
        if (ok) notifyListeners()
        return ok
    }

    fun markTranscriptionPending(id: String): Boolean {
        val note = storage.get(id) ?: return false
        val updated = note.copy(
            transcriptionState = Note.State.PENDING,
            errorMessage = null,
            modifiedAt = System.currentTimeMillis()
        )
        val ok = storage.update(updated)
        if (ok) notifyListeners()
        return ok
    }

    fun updateEditedTranscript(id: String, edited: String): Boolean {
        val note = storage.get(id) ?: return false
        val updated = note.copy(
            editedTranscript = edited.trim(),
            modifiedAt = System.currentTimeMillis()
        )
        val ok = storage.update(updated)
        if (ok) notifyListeners()
        return ok
    }

    fun revertToOriginalTranscript(id: String): Boolean {
        val note = storage.get(id) ?: return false
        val updated = note.copy(
            editedTranscript = null,
            modifiedAt = System.currentTimeMillis()
        )
        val ok = storage.update(updated)
        if (ok) notifyListeners()
        return ok
    }

    fun togglePinned(id: String): Boolean {
        val note = storage.get(id) ?: return false
        val updated = note.copy(
            isPinned = !note.isPinned,
            modifiedAt = System.currentTimeMillis()
        )
        val ok = storage.update(updated)
        if (ok) notifyListeners()
        return ok
    }

    fun deleteNote(id: String): Boolean {
        val note = storage.get(id) ?: return false
        val audio = File(note.audioPath)
        val tombstone = File(audio.parentFile, "${audio.name}.deleting")
        if (audio.exists() && !audio.renameTo(tombstone)) return false
        val ok = storage.delete(id)
        if (ok) {
            tombstone.delete()
            File(notesDir, "$id.source").delete()
            context?.let { appContext ->
                runCatching { LocalModelBenchmark.deleteResults(appContext, id) }
                    .onFailure { Log.w("NotesRepository", "Unable to delete benchmark sidecar for $id", it) }
            }
            notifyListeners()
        } else if (tombstone.exists()) {
            tombstone.renameTo(audio)
        }
        return ok
    }

    companion object {
        @Volatile
        private var INSTANCE: NotesRepository? = null

        fun getInstance(context: Context): NotesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotesRepository(
                    context.applicationContext,
                    SQLiteNoteStorage(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }

        fun forTesting(storage: NoteStorage = InMemoryNoteStorage()): NotesRepository {
            return NotesRepository(null, storage)
        }
    }
}
