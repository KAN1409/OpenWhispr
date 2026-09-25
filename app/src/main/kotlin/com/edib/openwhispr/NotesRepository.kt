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

        FileOutputStream(audioFile).use { out ->
            out.write(wavBytes)
            out.flush()
        }

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

        val inserted = try {
            storage.insert(note)
        } catch (e: Exception) {
            audioFile.delete()
            throw e
        }
        if (!inserted) {
            audioFile.delete()
            throw IllegalStateException("Failed to insert note into database")
        }

        notifyListeners()
        return note
    }

    /**
     * Durably stores complete WAV bytes directly.
     */
    fun createAndSaveNoteFromWav(wavBytes: ByteArray, durationMs: Long): Note {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val audioFile = File(notesDir, "$id.wav")

        FileOutputStream(audioFile).use { out ->
            out.write(wavBytes)
            out.flush()
        }

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

        val inserted = try {
            storage.insert(note)
        } catch (e: Exception) {
            audioFile.delete()
            throw e
        }
        if (!inserted) {
            audioFile.delete()
            throw IllegalStateException("Failed to insert note into database")
        }

        notifyListeners()
        return note
    }

    /**
     * Reconciles files on disk with the database to remove unreferenced orphan recordings.
     */
    fun cleanupOrphanAudioFiles() {
        try {
            val allNotes = storage.getAll()
            val validPaths = allNotes.map { it.audioPath }.toSet()
            val files = notesDir.listFiles() ?: return
            val threshold = System.currentTimeMillis() - 5 * 60 * 1000L // 5-minute grace period
            for (file in files) {
                if (file.isFile && file.name.endsWith(".wav")) {
                    if (!validPaths.contains(file.absolutePath) && file.lastModified() < threshold) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("NotesRepository", "Orphan audio cleanup error", e)
        }
    }

    fun getAllNotes(): List<Note> = storage.getAll()

    fun getNote(id: String): Note? = storage.get(id)

    fun searchNotes(query: String): List<Note> = storage.search(query)

    fun markTranscriptionSuccess(id: String, rawTranscript: String): Boolean {
        val note = storage.get(id) ?: return false
        val updated = note.copy(
            originalTranscript = rawTranscript.trim(),
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
        val note = storage.get(id)
        if (note != null) {
            try {
                val f = File(note.audioPath)
                if (f.exists()) f.delete()
            } catch (e: Exception) {
                Log.e("NotesRepository", "Failed to delete audio file: ${note.audioPath}", e)
            }
        }
        val ok = storage.delete(id)
        if (ok) notifyListeners()
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
