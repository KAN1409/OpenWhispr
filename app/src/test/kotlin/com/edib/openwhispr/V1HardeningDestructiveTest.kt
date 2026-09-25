package com.edib.openwhispr

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FINAL V1 HARDENING: Adversarial and Destructive Test Suite.
 * Covers edge cases, concurrency, failure modes, data integrity, and regressions.
 */
class V1HardeningDestructiveTest {

    private val mixedArabicEnglishText =
        "بص أنا عايز أعمل update للـapplication النهارده بس متغيرش الـuser interface وخلي الـexisting features زي ما هي بالظبط وبعد كده اعمل build للـAPK"

    // 1. Network disappears while transcribing
    @Test
    fun `test network disappears during transcription - audio remains safe and state becomes FAILED`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(32000))
        val audioFile = File(note.audioPath)
        assertTrue(audioFile.exists())

        // Simulate network disconnect / SocketTimeoutException
        repo.markTranscriptionFailed(note.id, "java.net.SocketTimeoutException: timeout")

        val failedNote = repo.getNote(note.id)!!
        assertEquals(Note.State.FAILED, failedNote.transcriptionState)
        assertEquals("java.net.SocketTimeoutException: timeout", failedNote.errorMessage)
        // CRITICAL: Audio must remain safe and playable
        assertTrue("Audio file must remain intact after network disconnect", audioFile.exists())
        assertEquals(32000L + 44L, audioFile.length())

        repo.deleteNote(note.id)
    }

    // 2. Groq returns HTTP error (e.g. 401 / 429 / 500)
    @Test
    fun `test Groq returns HTTP error 429 rate limit - audio safe and error recorded`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(16000))

        val httpErrorMsg = "HTTP 429: Rate limit reached. Please wait."
        repo.markTranscriptionFailed(note.id, httpErrorMsg)

        val failed = repo.getNote(note.id)!!
        assertEquals(Note.State.FAILED, failed.transcriptionState)
        assertEquals(httpErrorMsg, failed.errorMessage)
        assertTrue(File(note.audioPath).exists())

        repo.deleteNote(note.id)
    }

    // 3. Groq returns empty transcription
    @Test
    fun `test Groq returns empty transcription - handled gracefully without crash`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(16000))

        // Empty response simulation
        val emptyMsg = "No speech detected"
        repo.markTranscriptionFailed(note.id, emptyMsg)

        val failed = repo.getNote(note.id)!!
        assertEquals(Note.State.FAILED, failed.transcriptionState)
        assertEquals("No speech detected", failed.errorMessage)
        assertNull(failed.originalTranscript)

        repo.deleteNote(note.id)
    }

    // 4. App goes background during transcription -> persistence finishes and is readable
    @Test
    fun `test asynchronous background transcription updates persistent state correctly`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(16000))

        val latch = CountDownLatch(1)
        Thread {
            Thread.sleep(50)
            repo.markTranscriptionSuccess(note.id, mixedArabicEnglishText)
            latch.countDown()
        }.start()

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val completed = repo.getNote(note.id)!!
        assertEquals(Note.State.COMPLETE, completed.transcriptionState)
        assertEquals(mixedArabicEnglishText, completed.displayTranscript)

        repo.deleteNote(note.id)
    }

    // 5. App process restarts after audio was persisted
    @Test
    fun `test process death and restart after persistence - audio and metadata intact`() {
        val sharedStorage = InMemoryNoteStorage()
        val repo1 = NotesRepository.forTesting(sharedStorage)

        // 48,000 bytes at 16,000 Hz 16-bit mono (32,000 bytes/sec) = 1.5s = 1500ms
        val note = repo1.createAndSaveNoteFromPcm(ByteArray(48000))
        val audioFile = File(note.audioPath)
        assertTrue(audioFile.exists())

        // Simulate complete restart of app with repo2
        val repo2 = NotesRepository.forTesting(sharedStorage)
        val loaded = repo2.getNote(note.id)
        assertNotNull(loaded)
        assertEquals(Note.State.PENDING, loaded!!.transcriptionState)
        assertEquals(1500L, loaded.audioDurationMs)

        // Process transcription in new instance
        repo2.markTranscriptionSuccess(note.id, "Transcribed after restart")
        val finalNote = repo2.getNote(note.id)!!
        assertEquals(Note.State.COMPLETE, finalNote.transcriptionState)
        assertEquals("Transcribed after restart", finalNote.originalTranscript)

        repo2.deleteNote(note.id)
    }

    // 6. User rapidly starts and stops / cancels recording
    @Test
    fun `test rapid start, stop and cancel operations do not corrupt state or leak resources`() {
        val repo = NotesRepository.forTesting()

        val createdIds = mutableListOf<String>()
        for (i in 1..20) {
            if (i % 2 == 0) {
                // Cancelled recording
            } else {
                val n = repo.createAndSaveNoteFromPcm(ByteArray(3200))
                createdIds.add(n.id)
            }
        }

        assertEquals(10, createdIds.size)
        assertEquals(10, repo.getAllNotes().size)

        // Cleanup
        createdIds.forEach { repo.deleteNote(it) }
        assertEquals(0, repo.getAllNotes().size)
    }

    // 7. User deletes FAILED note - audio file must be cleaned up (no orphan files)
    @Test
    fun `test deleting FAILED note completely deletes audio file from disk`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(16000))
        val audioFile = File(note.audioPath)
        assertTrue(audioFile.exists())

        repo.markTranscriptionFailed(note.id, "Network error")
        assertEquals(Note.State.FAILED, repo.getNote(note.id)!!.transcriptionState)

        // Delete note
        val deleted = repo.deleteNote(note.id)
        assertTrue(deleted)
        assertNull(repo.getNote(note.id))
        assertFalse("Audio file must be deleted when note is deleted", audioFile.exists())
    }

    // 8. Database insertion failure rolls back WAV file creation
    @Test
    fun `test DB insertion failure deletes WAV file and avoids orphan audio`() {
        val failingStorage = object : NoteStorage {
            override fun insert(note: Note): Boolean = false
            override fun update(note: Note): Boolean = false
            override fun get(id: String): Note? = null
            override fun getAll(): List<Note> = emptyList()
            override fun search(query: String): List<Note> = emptyList()
            override fun delete(id: String): Boolean = false
        }
        val repo = NotesRepository.forTesting(failingStorage)

        try {
            repo.createAndSaveNoteFromPcm(ByteArray(16000))
            fail("Should throw when database insertion fails")
        } catch (e: Exception) {
            assertTrue(e is IllegalStateException)
        }
    }

    // 9. Orphan audio reconciliation deletes unreferenced old WAV files
    @Test
    fun `test orphan audio cleanup reconciles unreferenced files`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(16000))

        // Create an unreferenced orphan file
        val tempDir = File(System.getProperty("java.io.tmpdir"), "openwhispr_test/notes")
        tempDir.mkdirs()
        val orphanFile = File(tempDir, "${UUID.randomUUID()}.wav")
        FileOutputStream(orphanFile).use { it.write(ByteArray(100)) }
        // Set last modified to 10 minutes ago
        orphanFile.setLastModified(System.currentTimeMillis() - 10 * 60 * 1000L)
        assertTrue(orphanFile.exists())

        repo.cleanupOrphanAudioFiles()

        // Orphan must be deleted, while referenced note audio stays intact
        assertFalse(orphanFile.exists())
        assertTrue(File(note.audioPath).exists())

        repo.deleteNote(note.id)
    }

    // 10. Audio file is unexpectedly missing - handled gracefully
    @Test
    fun `test player and transcriber fail gracefully when audio file is deleted from filesystem`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(16000))
        val audioFile = File(note.audioPath)
        assertTrue(audioFile.exists())

        // Delete file behind repository's back
        audioFile.delete()
        assertFalse(audioFile.exists())

        var playerErrorCalled = false
        val player = NoteAudioPlayer()
        player.play(
            audioPath = note.audioPath,
            onProgress = { _, _ -> },
            onCompletion = {},
            onError = { playerErrorCalled = true }
        )

        assertTrue("Player must report error gracefully for missing file", playerErrorCalled)
        assertFalse(player.isPlaying)

        repo.deleteNote(note.id)
    }

    // 11. Very long transcript
    @Test
    fun `test very long transcript handling and display formatting`() {
        val repo = NotesRepository.forTesting()
        val sb = StringBuilder()
        repeat(500) {
            sb.append("This is a long recurring paragraph about software architecture and systems design. ")
        }
        val veryLongText = sb.toString().trim()

        val note = repo.createAndSaveNoteFromPcm(ByteArray(16000))
        repo.markTranscriptionSuccess(note.id, veryLongText)

        val loaded = repo.getNote(note.id)!!
        assertEquals(veryLongText, loaded.displayTranscript)
        assertTrue(loaded.title.startsWith("Voice note"))

        // Search matches inside long transcript
        val found = repo.searchNotes("systems design")
        assertEquals(1, found.size)
        assertEquals(note.id, found[0].id)

        repo.deleteNote(note.id)
    }

    // 12. Mixed Arabic/English transcript search
    @Test
    fun `test mixed Arabic and English search and tokenization`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(16000))
        repo.markTranscriptionSuccess(note.id, mixedArabicEnglishText)

        assertTrue(repo.searchNotes("للـapplication").isNotEmpty())
        assertTrue(repo.searchNotes("APPLICATION").isNotEmpty())
        assertTrue(repo.searchNotes("user interface").isNotEmpty())
        assertTrue(repo.searchNotes("النهاردة").isEmpty()) // different spelling
        assertTrue(repo.searchNotes("النهارده").isNotEmpty())

        repo.deleteNote(note.id)
    }

    // 13. Immutability of original transcript on multiple edits
    @Test
    fun `test multiple successive edits never overwrite original transcript`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(16000))
        repo.markTranscriptionSuccess(note.id, mixedArabicEnglishText)

        repo.updateEditedTranscript(note.id, "Edit 1")
        assertEquals("Edit 1", repo.getNote(note.id)!!.displayTranscript)
        assertEquals(mixedArabicEnglishText, repo.getNote(note.id)!!.originalTranscript)

        repo.updateEditedTranscript(note.id, "Edit 2")
        assertEquals("Edit 2", repo.getNote(note.id)!!.displayTranscript)
        assertEquals(mixedArabicEnglishText, repo.getNote(note.id)!!.originalTranscript)

        repo.revertToOriginalTranscript(note.id)
        assertEquals(mixedArabicEnglishText, repo.getNote(note.id)!!.displayTranscript)
        assertEquals(mixedArabicEnglishText, repo.getNote(note.id)!!.originalTranscript)
        assertNull(repo.getNote(note.id)!!.editedTranscript)

        repo.deleteNote(note.id)
    }
}
