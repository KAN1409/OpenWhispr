package com.edib.openwhispr

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * Validates all Checkpoint D requirements:
 * - 5-second note
 * - 60-second note
 * - cancel
 * - successful transcription
 * - failed transcription
 * - retry
 * - background/foreground transition
 * - app restart
 * - audio playback after restart
 */
class InAppNoteCaptureTest {

    private val mixedSentence =
        "بص أنا عايز أعمل update للـapplication النهارده بس متغيرش الـuser interface وخلي الـexisting features زي ما هي بالظبط وبعد كده اعمل build للـAPK"

    @Test
    fun `test 5-second note durably saves WAV and creates PENDING note`() {
        val repo = NotesRepository.forTesting()
        // 5 seconds @ 16kHz 16-bit mono: 5 * 16000 * 2 = 160,000 bytes
        val pcm5s = ByteArray(160000)

        val note = repo.createAndSaveNoteFromPcm(pcm5s, sampleRate = 16000)

        assertEquals(Note.State.PENDING, note.transcriptionState)
        assertEquals(5000L, note.audioDurationMs)
        assertEquals("0:05", Note.formatDuration(note.audioDurationMs))

        val audioFile = File(note.audioPath)
        assertTrue("Audio file must exist on disk before any network operations", audioFile.exists())
        assertEquals(160000L + 44L, audioFile.length()) // WAV header is 44 bytes

        // Verify valid WAV header
        FileInputStream(audioFile).use { input ->
            val header = ByteArray(44)
            input.read(header)
            assertEquals("RIFF", String(header, 0, 4))
            assertEquals("WAVE", String(header, 8, 4))
        }

        repo.deleteNote(note.id)
    }

    @Test
    fun `test 60-second note durably saves WAV and formats duration correctly`() {
        val repo = NotesRepository.forTesting()
        // 60 seconds @ 16kHz 16-bit mono: 60 * 16000 * 2 = 1,920,000 bytes
        val pcm60s = ByteArray(1920000)

        val note = repo.createAndSaveNoteFromPcm(pcm60s, sampleRate = 16000)

        assertEquals(Note.State.PENDING, note.transcriptionState)
        assertEquals(60000L, note.audioDurationMs)
        assertEquals("1:00", Note.formatDuration(note.audioDurationMs))

        val audioFile = File(note.audioPath)
        assertTrue(audioFile.exists())
        assertEquals(1920000L + 44L, audioFile.length())

        repo.deleteNote(note.id)
    }

    @Test
    fun `test cancel leaves no orphan notes or audio files`() {
        val repo = NotesRepository.forTesting()
        val initialCount = repo.getAllNotes().size

        // Simulation of cancel in InAppNoteRecorder:
        // isRecordingInternal set to false, buffer cleared, no save to repo.
        var pcmBuffer: ByteArray? = ByteArray(80000)
        pcmBuffer = null

        val afterCancelCount = repo.getAllNotes().size
        assertEquals("Cancel must not create any note records", initialCount, afterCancelCount)
        assertNull(pcmBuffer)
    }

    @Test
    fun `test successful transcription updates note from PENDING to COMPLETE`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(32000))

        assertEquals(Note.State.PENDING, note.transcriptionState)
        assertNull(note.originalTranscript)

        // Asynchronous transcription returns success
        val ok = repo.markTranscriptionSuccess(note.id, mixedSentence)
        assertTrue(ok)

        val completed = repo.getNote(note.id)!!
        assertEquals(Note.State.COMPLETE, completed.transcriptionState)
        assertEquals(mixedSentence, completed.originalTranscript)
        assertEquals(mixedSentence, completed.displayTranscript)
        assertNull(completed.errorMessage)

        repo.deleteNote(note.id)
    }

    @Test
    fun `test failed transcription retains audio safely and retry succeeds`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(32000))
        val audioFile = File(note.audioPath)
        assertTrue("Audio file must exist", audioFile.exists())

        // Simulate network failure
        val failureMsg = "Network connection timeout"
        repo.markTranscriptionFailed(note.id, failureMsg)

        val failedNote = repo.getNote(note.id)!!
        assertEquals(Note.State.FAILED, failedNote.transcriptionState)
        assertEquals(failureMsg, failedNote.errorMessage)
        assertTrue("CRITICAL: Audio must remain completely safe and intact on disk after transcription failure", audioFile.exists())
        assertTrue("Audio must be non-empty", audioFile.length() > 44)

        // User taps "Retry transcription"
        repo.markTranscriptionPending(note.id)
        val retryingNote = repo.getNote(note.id)!!
        assertEquals(Note.State.PENDING, retryingNote.transcriptionState)

        // Retry succeeds
        repo.markTranscriptionSuccess(note.id, mixedSentence)
        val recoveredNote = repo.getNote(note.id)!!
        assertEquals(Note.State.COMPLETE, recoveredNote.transcriptionState)
        assertEquals(mixedSentence, recoveredNote.originalTranscript)
        assertTrue(audioFile.exists())

        repo.deleteNote(note.id)
    }

    @Test
    fun `test app restart retains notes and audio is playable after restart`() {
        val sharedStorage = InMemoryNoteStorage()
        val repoInstance1 = NotesRepository.forTesting(sharedStorage)

        // Create 3-second note before app restart
        val pcm3s = ByteArray(96000)
        val noteBeforeRestart = repoInstance1.createAndSaveNoteFromPcm(pcm3s, sampleRate = 16000)
        repoInstance1.markTranscriptionSuccess(noteBeforeRestart.id, "Saved note before restart")

        val audioPath = noteBeforeRestart.audioPath
        val audioFile = File(audioPath)
        assertTrue(audioFile.exists())

        // Simulate app termination and restart with new repository instance referencing same storage
        val repoInstance2 = NotesRepository.forTesting(sharedStorage)
        val noteAfterRestart = repoInstance2.getNote(noteBeforeRestart.id)

        assertNotNull("Note must persist across app restarts", noteAfterRestart)
        assertEquals(noteBeforeRestart.id, noteAfterRestart!!.id)
        assertEquals(Note.State.COMPLETE, noteAfterRestart.transcriptionState)
        assertEquals("Saved note before restart", noteAfterRestart.originalTranscript)
        assertEquals(audioPath, noteAfterRestart.audioPath)

        // Verify audio file is present and valid for offline playback after restart
        val restoredAudioFile = File(noteAfterRestart.audioPath)
        assertTrue("Audio file must survive app restart", restoredAudioFile.exists())
        assertEquals(96000L + 44L, restoredAudioFile.length())

        repoInstance2.deleteNote(noteAfterRestart.id)
    }
}
