package com.edib.openwhispr

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NotesRepositoryTest {

    private val mixedArabicEnglishText =
        "بص أنا عايز أعمل update للـapplication النهارده بس متغيرش الـuser interface وخلي الـexisting features زي ما هي بالظبط وبعد كده اعمل build للـAPK"

    @Test
    fun `save note from PCM durably saves audio file and creates pending note`() {
        val repo = NotesRepository.forTesting()
        val dummyPcm = ByteArray(32000) // 1 second of 16kHz 16-bit mono

        val note = repo.createAndSaveNoteFromPcm(dummyPcm, sampleRate = 16000)

        assertNotNull(note.id)
        assertEquals(Note.State.PENDING, note.transcriptionState)
        assertEquals(1000L, note.audioDurationMs)
        assertNull(note.originalTranscript)
        assertNull(note.editedTranscript)

        val audioFile = File(note.audioPath)
        assertTrue("Audio file should exist on disk", audioFile.exists())
        assertTrue("Audio file should be non-empty WAV", audioFile.length() > 44)

        // Clean up
        repo.deleteNote(note.id)
    }

    @Test
    fun `audio is retained when transcription fails and retry is possible`() {
        val repo = NotesRepository.forTesting()
        val dummyPcm = ByteArray(16000)

        val note = repo.createAndSaveNoteFromPcm(dummyPcm)
        val audioFile = File(note.audioPath)
        assertTrue(audioFile.exists())

        // Simulate network failure
        repo.markTranscriptionFailed(note.id, "Groq API HTTP 504 Gateway Timeout")

        val failedNote = repo.getNote(note.id)
        assertNotNull(failedNote)
        assertEquals(Note.State.FAILED, failedNote!!.transcriptionState)
        assertEquals("Groq API HTTP 504 Gateway Timeout", failedNote.errorMessage)
        assertTrue("Audio MUST be retained on failure", audioFile.exists())

        // Simulate retry transition
        repo.markTranscriptionPending(note.id)
        val retryingNote = repo.getNote(note.id)
        assertEquals(Note.State.PENDING, retryingNote!!.transcriptionState)

        // Retry succeeds
        repo.markTranscriptionSuccess(note.id, mixedArabicEnglishText)
        val completedNote = repo.getNote(note.id)
        assertEquals(Note.State.COMPLETE, completedNote!!.transcriptionState)
        assertEquals(mixedArabicEnglishText, completedNote.originalTranscript)
        assertTrue(audioFile.exists())

        // Clean up
        repo.deleteNote(note.id)
    }

    @Test
    fun `editing transcript preserves original raw transcript without destruction`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(100))

        repo.markTranscriptionSuccess(note.id, mixedArabicEnglishText)
        val completed = repo.getNote(note.id)!!
        assertEquals(mixedArabicEnglishText, completed.displayTranscript)
        assertEquals(mixedArabicEnglishText, completed.originalTranscript)
        assertFalse(completed.hasEditedTranscript)

        // User edits the transcript
        val userEdited = "تعديل المستخدم: $mixedArabicEnglishText (مكتمل)"
        repo.updateEditedTranscript(note.id, userEdited)

        val edited = repo.getNote(note.id)!!
        assertTrue(edited.hasEditedTranscript)
        assertEquals(userEdited, edited.displayTranscript)
        assertEquals(mixedArabicEnglishText, edited.originalTranscript) // Original untouched!

        // User reverts back to original
        repo.revertToOriginalTranscript(note.id)
        val reverted = repo.getNote(note.id)!!
        assertFalse(reverted.hasEditedTranscript)
        assertEquals(mixedArabicEnglishText, reverted.displayTranscript)
        assertEquals(mixedArabicEnglishText, reverted.originalTranscript)

        repo.deleteNote(note.id)
    }

    @Test
    fun `pinning toggles and orders pinned notes first`() {
        val repo = NotesRepository.forTesting()
        val note1 = repo.createAndSaveNoteFromPcm(ByteArray(100))
        Thread.sleep(10)
        val note2 = repo.createAndSaveNoteFromPcm(ByteArray(100))

        // By default note2 is newer, so note2 is first
        var all = repo.getAllNotes()
        assertEquals(note2.id, all[0].id)
        assertEquals(note1.id, all[1].id)

        // Pin note1
        repo.togglePinned(note1.id)
        all = repo.getAllNotes()
        assertEquals(note1.id, all[0].id)
        assertTrue(all[0].isPinned)
        assertEquals(note2.id, all[1].id)
        assertFalse(all[1].isPinned)

        // Unpin note1
        repo.togglePinned(note1.id)
        all = repo.getAllNotes()
        assertEquals(note2.id, all[0].id)

        repo.deleteNote(note1.id)
        repo.deleteNote(note2.id)
    }

    @Test
    fun `delete removes note and cleans up audio file`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(1000))
        val audioFile = File(note.audioPath)
        assertTrue(audioFile.exists())

        repo.deleteNote(note.id)

        assertNull(repo.getNote(note.id))
        assertFalse("Audio file must be deleted when note is deleted", audioFile.exists())
    }

    @Test
    fun `search handles Arabic, English, and mixed queries`() {
        val repo = NotesRepository.forTesting()
        val note1 = repo.createAndSaveNoteFromPcm(ByteArray(100))
        repo.markTranscriptionSuccess(note1.id, mixedArabicEnglishText)

        val note2 = repo.createAndSaveNoteFromPcm(ByteArray(100))
        repo.markTranscriptionSuccess(note2.id, "Buy groceries: apples, milk, bread")

        // Search Arabic token
        val arabicResult = repo.searchNotes("عايز")
        assertEquals(1, arabicResult.size)
        assertEquals(note1.id, arabicResult[0].id)

        // Search English token inside mixed text (case-insensitive)
        val englishInMixed = repo.searchNotes("application")
        assertEquals(1, englishInMixed.size)
        assertEquals(note1.id, englishInMixed[0].id)

        val upperCaseQuery = repo.searchNotes("APPLICATION")
        assertEquals(1, upperCaseQuery.size)
        assertEquals(note1.id, upperCaseQuery[0].id)

        // Search English in pure English note
        val englishResult = repo.searchNotes("milk")
        assertEquals(1, englishResult.size)
        assertEquals(note2.id, englishResult[0].id)

        // Search with no matches
        val noMatch = repo.searchNotes("completely non existent text")
        assertEquals(0, noMatch.size)

        // Search technical term in regression sentence
        val apkMatch = repo.searchNotes("APK")
        assertEquals(1, apkMatch.size)

        val buildMatch = repo.searchNotes("build")
        assertEquals(1, buildMatch.size)

        repo.deleteNote(note1.id)
        repo.deleteNote(note2.id)
    }

    @Test
    fun `duration formatting correctly renders seconds and minutes`() {
        assertEquals("0:00", Note.formatDuration(0L))
        assertEquals("0:02", Note.formatDuration(2500L))
        assertEquals("0:41", Note.formatDuration(41000L))
        assertEquals("2:43", Note.formatDuration(163000L))
        assertEquals("10:00", Note.formatDuration(600000L))
    }

    @Test
    fun `elapsed time formatting correctly renders double digit seconds and minutes`() {
        assertEquals("00:00", Note.formatElapsed(0L))
        assertEquals("00:41", Note.formatElapsed(41000L))
        assertEquals("02:43", Note.formatElapsed(163000L))
    }

    @Test
    fun `footer time displays Just now for recent notes`() {
        val justNow = System.currentTimeMillis() - 10_000L
        assertEquals("Just now", Note.formatFooterTime(justNow))

        val oneHourAgo = System.currentTimeMillis() - 3600_000L
        assertNotEquals("Just now", Note.formatFooterTime(oneHourAgo))
        assertEquals(Note.formatTime(oneHourAgo), Note.formatFooterTime(oneHourAgo))
    }

    @Test
    fun `note title has deterministic format`() {
        val now = 1711364220000L // arbitrary timestamp
        val timeStr = Note.formatTime(now)
        val note = Note(
            id = "test-1",
            createdAt = now,
            modifiedAt = now,
            audioPath = "/fake/path.wav",
            audioDurationMs = 5000L
        )
        assertEquals("Voice note · $timeStr", note.title)
    }

    @Test
    fun `Arabic and English mixed transcript remains bit-exact and pristine`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(100))
        repo.markTranscriptionSuccess(note.id, mixedArabicEnglishText)

        val retrieved = repo.getNote(note.id)!!
        assertEquals(mixedArabicEnglishText, retrieved.originalTranscript)
        assertEquals(mixedArabicEnglishText, retrieved.displayTranscript)
        // Verify no characters were altered, reversed, or stripped
        assertTrue(retrieved.originalTranscript!!.startsWith("بص"))
        assertTrue(retrieved.originalTranscript!!.contains("للـapplication"))
        assertTrue(retrieved.originalTranscript!!.endsWith("للـAPK"))

        repo.deleteNote(note.id)
    }
}
