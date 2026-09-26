package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Guards the code-switching contract for local Whisper transcription.
 *
 * The failure this prevents: sherpa-onnx defaults OfflineWhisperModelConfig's
 * `language` field to "en" whenever it is not supplied. A Whisper model built
 * without an explicit language therefore gets the English language token forced
 * into its decoder prompt, so Egyptian Arabic comes back translated or
 * normalised to MSA, and Arabic/English code-switching is flattened to one
 * language.
 *
 * These tests drive the real production code path -- LocalTranscriber's own
 * model auto-detection -- against a synthetic model directory, so they fail if
 * anyone re-introduces a hard-coded language.
 */
class WhisperCodeSwitchingConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Lay out the files detectModelConfig() needs to classify a dir as Whisper.
     *
     * `prefix` mirrors real sherpa-onnx model archives, which ship
     * "<model>-tokens.txt" / "<model>-encoder.int8.onnx" rather than bare
     * "tokens.txt" / "encoder.onnx". Large v3 uses "large-v3", base.en uses
     * "base.en". Both are the on-disk shape that used to fail to load.
     */
    private fun whisperModelDir(prefix: String? = null): java.io.File {
        val d = tmp.newFolder("whisper" + (prefix ?: ""))
        fun onnx(name: String) = java.io.File(d, name).writeText("onnx")
        fun toks(name: String) = java.io.File(d, name).writeText("# tokens\n<unk> 0\n")
        if (prefix == null) {
            toks("tokens.txt")
            onnx("encoder.int8.onnx")
            onnx("decoder.onnx")
        } else {
            toks("$prefix-tokens.txt")
            onnx("$prefix-encoder.int8.onnx")
            onnx("$prefix-decoder.int8.onnx")
        }
        return d
    }

    private fun buildProductionConfig(dir: java.io.File = whisperModelDir()): OfflineWhisperModelConfig {
        val cfg = LocalTranscriber.detectModelConfig(dir)
        assertNotNull("detectModelConfig did not recognise a Whisper model dir", cfg)
        return cfg!!.modelConfig.whisper!!
    }

    @Test
    fun `model dir with model-prefixed filenames still resolves to whisper`() {
        // Regression: the detector hard-coded "tokens.txt", so every real
        // Whisper archive (large-v3-tokens.txt, base.en-tokens.txt) failed to
        // load and the app silently fell back to cloud transcription.
        for (prefix in listOf("large-v3", "base.en", "small")) {
            val cfg = buildProductionConfig(whisperModelDir(prefix))
            assertEquals("transcribe", cfg.task)
            assertTrue(
                "$prefix encoder was not resolved: ${cfg.encoder}",
                cfg.encoder.endsWith("encoder.int8.onnx")
            )
            assertTrue(
                "$prefix decoder was not resolved: ${cfg.decoder}",
                cfg.decoder.endsWith("decoder.int8.onnx")
            )
        }
    }

    @Test
    fun `tokens file resolves for prefixed model dirs`() {
        assertTrue(
            LocalTranscriber.findTokens(whisperModelDir("large-v3"))!!
                .endsWith("large-v3-tokens.txt")
        )
        assertTrue(
            LocalTranscriber.findTokens(whisperModelDir(null))!!
                .endsWith("tokens.txt")
        )
    }

    @Test
    fun `production config sets task to transcribe so speech is never translated`() {
        val cfg = buildProductionConfig()
        assertEquals("transcribe", cfg.task)
        assertNotEquals("translate", cfg.task)
    }

    @Test
    fun `production config leaves language empty so sherpa auto-detects`() {
        val cfg = buildProductionConfig()
        assertEquals(
            "language must be empty: an empty value makes sherpa run DetectLanguage, " +
                "which is what preserves Arabic/English code-switching",
            "", cfg.language
        )
    }

    @Test
    fun `production config avoids the sherpa English default`() {
        // Prove sherpa's default really is "en" -- that is the bug guarded here.
        val dflt = OfflineWhisperModelConfig(encoder = "e", decoder = "d", task = "transcribe")
        assertEquals(
            "sherpa-onnx is expected to default language to \"en\"; if upstream changes, " +
                "re-check the code-switching rationale",
            "en", dflt.language
        )
        assertNotEquals("en", buildProductionConfig().language)
    }

    @Test
    fun `production config forces neither Arabic nor English`() {
        // Forcing "ar" would equally destroy the English half of a
        // code-switched sentence, so both directions must be avoided.
        val lang = buildProductionConfig().language
        assertTrue(
            "language must not be forced to a single language (got '$lang')",
            lang.isEmpty()
        )
    }

    @Test
    fun `transcription splits long audio at quiet points`() {
        // Whisper has a hard 30 s receptive field; without chunking, everything
        // past 30 s is silently dropped.
        val methods = LocalTranscriber::class.java.declaredMethods.map { it.name }
        assertTrue(
            "LocalTranscriber must chunk long audio (expected a quiet-point search)",
            methods.any { it.contains("quietestFrom") }
        )
    }
}
