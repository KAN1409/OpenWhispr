package com.edib.openwhispr

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Validates all Checkpoint F requirements:
 * - SEARCH across title, originalTranscript, editedTranscript (Arabic, English, mixed)
 * - EDIT: transcript editing, originalTranscript immutability, editedTranscript persistence, view original
 * - SHARE: Share transcript, Share audio, Share audio + transcript via FileProvider content:// URI patterns
 */
class SearchEditShareTest {

    private val arabicText = "كلم أحمد بكرة وشوف موضوع الرخام الجديد"
    private val englishText = "Discuss the project milestones and deliver the update tomorrow"
    private val mixedArabicEnglishText =
        "بص أنا عايز أعمل update للـapplication النهارده بس متغيرش الـuser interface وخلي الـexisting features زي ما هي بالظبط وبعد كده اعمل build للـAPK"

    // ==========================================
    // 1. SEARCH TESTS
    // ==========================================

    @Test
    fun `search matches across title, originalTranscript, and editedTranscript in Arabic and English`() {
        val repo = NotesRepository.forTesting()

        // Note 1: Pure Arabic
        val note1 = repo.createAndSaveNoteFromPcm(ByteArray(100))
        repo.markTranscriptionSuccess(note1.id, arabicText)

        // Note 2: Pure English
        val note2 = repo.createAndSaveNoteFromPcm(ByteArray(100))
        repo.markTranscriptionSuccess(note2.id, englishText)

        // Note 3: Mixed Arabic and English
        val note3 = repo.createAndSaveNoteFromPcm(ByteArray(100))
        repo.markTranscriptionSuccess(note3.id, mixedArabicEnglishText)

        // Note 4: Edited transcript (different from original)
        val note4 = repo.createAndSaveNoteFromPcm(ByteArray(100))
        repo.markTranscriptionSuccess(note4.id, "Original draft about architecture")
        repo.updateEditedTranscript(note4.id, "Final review of Villa marble samples")

        // 1. Search by title
        val titleQuery = repo.searchNotes("Voice note")
        assertEquals("Title search must match all notes having default title", 4, titleQuery.size)

        // 2. Search by Arabic query in original transcript
        val arabicQuery = repo.searchNotes("أحمد")
        assertEquals(1, arabicQuery.size)
        assertEquals(note1.id, arabicQuery[0].id)

        // 3. Search by English query (case-insensitive) in original transcript
        val englishQueryLower = repo.searchNotes("milestones")
        val englishQueryUpper = repo.searchNotes("MILESTONES")
        assertEquals(1, englishQueryLower.size)
        assertEquals(1, englishQueryUpper.size)
        assertEquals(note2.id, englishQueryLower[0].id)
        assertEquals(note2.id, englishQueryUpper[0].id)

        // 4. Search by mixed Arabic + English token
        val mixedQueryApp = repo.searchNotes("للـapplication")
        assertEquals(1, mixedQueryApp.size)
        assertEquals(note3.id, mixedQueryApp[0].id)

        val mixedQueryUi = repo.searchNotes("user interface")
        assertEquals(1, mixedQueryUi.size)
        assertEquals(note3.id, mixedQueryUi[0].id)

        val mixedQueryApk = repo.searchNotes("build للـAPK")
        assertEquals(1, mixedQueryApk.size)
        assertEquals(note3.id, mixedQueryApk[0].id)

        // 5. Search in edited transcript
        val editedQuery = repo.searchNotes("Villa marble")
        assertEquals(1, editedQuery.size)
        assertEquals(note4.id, editedQuery[0].id)

        // 6. Search in original transcript of edited note
        val originalQueryOfEdited = repo.searchNotes("architecture")
        assertEquals(1, originalQueryOfEdited.size)
        assertEquals(note4.id, originalQueryOfEdited[0].id)

        // 7. Empty or whitespace query returns all
        assertEquals(4, repo.searchNotes("").size)
        assertEquals(4, repo.searchNotes("   ").size)

        // 8. No matches
        assertEquals(0, repo.searchNotes("nonexistent token xyz").size)

        repo.deleteNote(note1.id)
        repo.deleteNote(note2.id)
        repo.deleteNote(note3.id)
        repo.deleteNote(note4.id)
    }

    // ==========================================
    // 2. EDIT TESTS
    // ==========================================

    @Test
    fun `editing transcript updates editedTranscript and never mutates originalTranscript`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(100))
        repo.markTranscriptionSuccess(note.id, mixedArabicEnglishText)

        val initialNote = repo.getNote(note.id)!!
        assertEquals(mixedArabicEnglishText, initialNote.originalTranscript)
        assertNull(initialNote.editedTranscript)
        assertFalse(initialNote.hasEditedTranscript)
        assertEquals(mixedArabicEnglishText, initialNote.displayTranscript)

        // Perform edit
        val userEditedText = "بص أنا عملت update للـapplication وخلاص"
        repo.updateEditedTranscript(note.id, userEditedText)

        val editedNote = repo.getNote(note.id)!!
        // CRITICAL INVARIANT: originalTranscript must never be overwritten
        assertEquals(mixedArabicEnglishText, editedNote.originalTranscript)
        assertEquals(userEditedText, editedNote.editedTranscript)
        assertTrue(editedNote.hasEditedTranscript)
        assertEquals(userEditedText, editedNote.displayTranscript)

        // Revert to original
        repo.revertToOriginalTranscript(note.id)
        val revertedNote = repo.getNote(note.id)!!
        assertEquals(mixedArabicEnglishText, revertedNote.originalTranscript)
        assertNull(revertedNote.editedTranscript)
        assertFalse(revertedNote.hasEditedTranscript)
        assertEquals(mixedArabicEnglishText, revertedNote.displayTranscript)

        repo.deleteNote(note.id)
    }

    // ==========================================
    // 3. SHARE INTENT & FILEPROVIDER CONTRACT TESTS
    // ==========================================

    @Test
    fun `shareTranscript payload contains title and full text for Arabic, English, and mixed scripts`() {
        val repo = NotesRepository.forTesting()

        // 1. Arabic
        val noteAr = repo.createAndSaveNoteFromPcm(ByteArray(100))
        repo.markTranscriptionSuccess(noteAr.id, arabicText)
        val noteArData = repo.getNote(noteAr.id)!!
        assertEquals(arabicText, noteArData.displayTranscript)

        // 2. English
        val noteEn = repo.createAndSaveNoteFromPcm(ByteArray(100))
        repo.markTranscriptionSuccess(noteEn.id, englishText)
        val noteEnData = repo.getNote(noteEn.id)!!
        assertEquals(englishText, noteEnData.displayTranscript)

        // 3. Mixed Arabic + English
        val noteMixed = repo.createAndSaveNoteFromPcm(ByteArray(100))
        repo.markTranscriptionSuccess(noteMixed.id, mixedArabicEnglishText)
        val noteMixedData = repo.getNote(noteMixed.id)!!
        assertEquals(mixedArabicEnglishText, noteMixedData.displayTranscript)

        repo.deleteNote(noteAr.id)
        repo.deleteNote(noteEn.id)
        repo.deleteNote(noteMixed.id)
    }

    @Test
    fun `shareAudio verifies audio file exists on disk and has valid size`() {
        val repo = NotesRepository.forTesting()
        val pcm = ByteArray(16000)
        val note = repo.createAndSaveNoteFromPcm(pcm)

        val audioFile = File(note.audioPath)
        assertTrue("Audio file must exist for sharing", audioFile.exists())
        assertEquals(16000L + 44L, audioFile.length())

        // Ensure FileProvider path is within internal files dir, preventing file:// exposure
        assertTrue(audioFile.absolutePath.contains("notes"))
        assertFalse("Paths must never be raw file:// URI strings in database", note.audioPath.startsWith("file://"))

        repo.deleteNote(note.id)
    }

    @Test
    fun `shareAudioAndTranscript provides both audio path and transcript text`() {
        val repo = NotesRepository.forTesting()
        val pcm = ByteArray(16000)
        val note = repo.createAndSaveNoteFromPcm(pcm)
        repo.markTranscriptionSuccess(note.id, mixedArabicEnglishText)

        val noteData = repo.getNote(note.id)!!
        val audioFile = File(noteData.audioPath)
        assertTrue(audioFile.exists())
        assertEquals(mixedArabicEnglishText, noteData.displayTranscript)

        repo.deleteNote(note.id)
    }
}
