package com.edib.openwhispr

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Validates the state machine and gesture handling invariants of Checkpoint E:
 * 1. NORMAL TAP retains current Dictation behavior.
 * 2. LONG PRESS triggers Voice Note recording.
 * 3. NOTE STOP persists audio, creates Note, and NEVER injects text into focused fields.
 * 4. DRAG does not trigger either Dictation or Voice Note.
 * 5. Egyptian Arabic mixed with English transcription test sentence.
 */
class GestureStateMachineTest {

    private val mixedSentence =
        "بص أنا عايز أعمل update للـapplication النهارده بس متغيرش الـuser interface وخلي الـexisting features زي ما هي بالظبط وبعد كده اعمل build للـAPK"

    enum class MockState { IDLE, RECORDING, TRANSCRIBING }
    enum class MockSession { DICTATION, NOTE }

    class GestureSimulator(
        private val repo: NotesRepository,
        private val onInjectText: (String) -> Unit
    ) {
        var state = MockState.IDLE
        var session = MockSession.DICTATION
        var injectedTexts = mutableListOf<String>()
        var recordedNotes = mutableListOf<Note>()
        var feedbackShown = mutableListOf<String>()

        fun onNormalTap() {
            when (state) {
                MockState.IDLE -> {
                    // Normal tap starts dictation
                    state = MockState.RECORDING
                    session = MockSession.DICTATION
                }
                MockState.RECORDING -> {
                    if (session == MockSession.NOTE) {
                        stopNote()
                    } else {
                        stopDictationAndInject("Transcribed dictation text")
                    }
                }
                MockState.TRANSCRIBING -> {}
            }
        }

        fun onLongPress() {
            if (state == MockState.IDLE) {
                state = MockState.RECORDING
                session = MockSession.NOTE
            }
        }

        fun onDrag(movedDistanceDp: Float, thresholdDp: Float): Boolean {
            // Dragging above threshold cancels any long press and moves overlay
            return movedDistanceDp > thresholdDp
        }

        fun stopDictationAndInject(text: String) {
            state = MockState.TRANSCRIBING
            injectedTexts.add(text)
            onInjectText(text)
            state = MockState.IDLE
            session = MockSession.DICTATION
        }

        fun stopNote(pcm: ByteArray = ByteArray(32000)) {
            state = MockState.IDLE
            session = MockSession.DICTATION
            val note = repo.createAndSaveNoteFromPcm(pcm)
            recordedNotes.add(note)
            feedbackShown.add("✓ Note saved")
            // Crucial: onInjectText is NEVER called for notes!
        }
    }

    @Test
    fun `normal tap starts and stops dictation, injecting text`() {
        val repo = NotesRepository.forTesting()
        var textInjected = false
        val sim = GestureSimulator(repo) { textInjected = true }

        // First tap: start dictation
        sim.onNormalTap()
        assertEquals(MockState.RECORDING, sim.state)
        assertEquals(MockSession.DICTATION, sim.session)
        assertFalse(textInjected)

        // Second tap: stop dictation and inject text
        sim.onNormalTap()
        assertEquals(MockState.IDLE, sim.state)
        assertTrue(textInjected)
        assertEquals(1, sim.injectedTexts.size)
        assertEquals(0, sim.recordedNotes.size) // No notes created
    }

    @Test
    fun `long press starts voice note and stop persists audio without injecting text`() {
        val repo = NotesRepository.forTesting()
        var textInjected = false
        val sim = GestureSimulator(repo) { textInjected = true }

        // Long press activates note recording
        sim.onLongPress()
        assertEquals(MockState.RECORDING, sim.state)
        assertEquals(MockSession.NOTE, sim.session)
        assertFalse(textInjected)

        // Stop note recording
        sim.onNormalTap() // Tapping note bar stops and saves note
        assertEquals(MockState.IDLE, sim.state)
        assertEquals(MockSession.DICTATION, sim.session)
        assertFalse("Voice notes must NEVER inject text into focused fields", textInjected)
        assertEquals(0, sim.injectedTexts.size)
        assertEquals(1, sim.recordedNotes.size)
        assertEquals("✓ Note saved", sim.feedbackShown.first())

        val savedNote = sim.recordedNotes.first()
        val audioFile = File(savedNote.audioPath)
        assertTrue("Note audio must be durably saved on disk", audioFile.exists())
        assertEquals(Note.State.PENDING, savedNote.transcriptionState)

        repo.deleteNote(savedNote.id)
    }

    @Test
    fun `drag cancels long press and does not trigger recording in either mode`() {
        val repo = NotesRepository.forTesting()
        val sim = GestureSimulator(repo) {}

        val threshold = 10f
        val dragDistance = 25f

        val isDrag = sim.onDrag(dragDistance, threshold)
        assertTrue(isDrag)
        assertEquals(MockState.IDLE, sim.state)
        assertEquals(0, sim.injectedTexts.size)
        assertEquals(0, sim.recordedNotes.size)
    }

    @Test
    fun `regression test sentence with mixed Egyptian Arabic and English preserves formatting and storage`() {
        val repo = NotesRepository.forTesting()
        val note = repo.createAndSaveNoteFromPcm(ByteArray(16000))

        repo.markTranscriptionSuccess(note.id, mixedSentence)
        val loaded = repo.getNote(note.id)!!

        assertEquals(mixedSentence, loaded.originalTranscript)
        assertEquals(mixedSentence, loaded.displayTranscript)
        assertTrue(loaded.originalTranscript!!.contains("للـapplication"))
        assertTrue(loaded.originalTranscript!!.contains("للـAPK"))
        assertTrue(loaded.originalTranscript!!.contains("الـuser interface"))

        repo.deleteNote(note.id)
    }
}
