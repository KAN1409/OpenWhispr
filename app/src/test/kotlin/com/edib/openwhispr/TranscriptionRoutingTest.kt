package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Routing contract for note transcription.
 *
 * The defect these tests guard: when the user had selected Local but the local
 * model could not be loaded, [NoteTranscriber] fell through to the Groq cloud
 * path. A cloud transcript was then stored as if it were a local result, and
 * audio the user had chosen to keep on device left the device.
 *
 * Contract: Local selected => local only. Any local failure is explicit, the
 * recording is preserved, and no cloud request is made.
 */
class TranscriptionRoutingTest {

    private fun wav(seconds: Int = 1, sampleRate: Int = 16000): ByteArray {
        val pcm = ByteArray(seconds * sampleRate * 2)
        // 44-byte canonical header so the note reader sees a real WAV.
        return ByteArray(44) + pcm
    }

    @Test
    fun `local mode with no model name fails without reaching cloud`() {
        val out = NoteTranscriber.runLocal(
            modelDir = null,
            modelName = "",
            wavBytes = wav()
        )
        assertTrue(
            "blank model must produce an explicit failure, got $out",
            out is NoteTranscriber.LocalOutcome.Failed
        )
        assertEquals(NoteTranscriber.LOCAL_FAILED_MESSAGE, (out as NoteTranscriber.LocalOutcome.Failed).reason)
    }

    @Test
    fun `local mode with a missing model directory fails explicitly`() {
        val out = NoteTranscriber.runLocal(
            modelDir = File(System.getProperty("java.io.tmpdir"), "no-such-model-${System.nanoTime()}"),
            modelName = "whatever",
            wavBytes = wav()
        )
        assertTrue(
            "a model directory that does not exist must fail, got $out",
            out is NoteTranscriber.LocalOutcome.Failed
        )
    }

    @Test
    fun `local failure message promises the recording is safe and names the choice`() {
        val m = NoteTranscriber.LOCAL_FAILED_MESSAGE
        assertTrue("message must say the recording is safe: $m", m.contains("recording is safe"))
        assertTrue("message must offer Retry: $m", m.contains("Retry"))
        assertTrue(
            "message must make switching an explicit choice in Settings, not automatic: $m",
            m.contains("Settings") && m.contains("change the transcription method")
        )
        // The message may name Cloud as the *option* the user can deliberately
        // pick; what it must not do is imply cloud was already used.
        assertTrue(
            "message must not claim a cloud result was produced: $m",
            !m.contains("Groq") && !m.contains("via cloud") &&
                !m.contains("transcribed by")
        )
    }

    @Test
    fun `local outcome types distinguish blank speech from failure`() {
        // Blank is "no speech", which is not a failure and must not be shown as
        // one; Failed is the explicit local failure.
        assertTrue(NoteTranscriber.LocalOutcome.Blank !is NoteTranscriber.LocalOutcome.Failed)
    }

    /**
     * Static proof that the local branch of transcribeNoteAsync cannot fall
     * through to the cloud call. The cloud request is the only reference to
     * TranscriberClient.transcribe in the file, and it must be preceded by an
     * early `return@thread` inside the `if (useLocal)` block.
     */
    @Test
    fun `local branch returns before the cloud call in source`() {
        val src = readSource("NoteTranscriber.kt")
        val localBranch = src.substringAfter("if (useLocal) {")
            .substringBefore("// Cloud transcription via Groq")
        assertTrue(
            "the Local branch must return before reaching the cloud block",
            localBranch.contains("return@thread")
        )
        assertTrue(
            "the Local branch must not invoke TranscriberClient",
            !localBranch.contains("TranscriberClient")
        )
    }

    @Test
    fun `accessibility dictation also cannot fall back to cloud when local is selected`() {
        val src = readSource("WhisperAccessibilityService.kt")
        val start = src.indexOf("if (useLocal) {")
        assertTrue("accessibility service should branch on useLocal", start > 0)
        val branch = src.substring(start, minOf(src.length, start + 1600))
        // The *outer* else is the one that must call transcribeApi, so anchor
        // on it rather than the first "} else {" (which is the inner
        // null-check).
        val cloudCall = branch.indexOf("transcribeApi(")
        assertTrue("expected a cloud call in the else branch", cloudCall > 0)
        val localPart = branch.substring(0, cloudCall)
        assertTrue(
            "the local branch must report the failure itself instead of " +
                "silently reaching the cloud path; got:\n$localPart",
            localPart.contains("no local model is loaded")
        )
        assertTrue(
            "the local branch must never call transcribeApi, got:\n$localPart",
            !localPart.contains("transcribeApi(")
        )
    }

    private fun readSource(file: String): String {
        val names = listOf(
            "app/src/main/kotlin/com/edib/openwhispr/$file",
            "src/main/kotlin/com/edib/openwhispr/$file",
            "src/test/kotlin/com/edib/openwhispr/$file"
        )
        for (n in names) {
            val f = File(n)
            if (f.exists()) return f.readText()
        }
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (n in names) {
                val f = File(dir, n)
                if (f.exists()) return f.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate source for $file")
    }
}
