package com.edib.openwhispr

import org.junit.Assert.*
import org.junit.Test

class TranscriberClientTest {

    @Test fun `verbose response exposes whisper confidence`() {
        val result = TranscriberClient.parseScoredResponse(
            """{"text":"English words","segments":[{"avg_logprob":-0.31},{"avg_logprob":-0.21}]}"""
        )
        assertEquals("English words", result.text)
        assertEquals(-0.26, result.averageLogProbability!!, 0.0001)
    }

    @Test fun `higher whisper confidence wins language comparison`() {
        val english = TranscriberClient.ScoredResult("English", null, -0.40)
        val arabic = TranscriberClient.ScoredResult("العربية", null, -0.62)
        assertEquals(english, TranscriberClient.chooseHigherConfidence(english, arabic))
    }

    @Test fun `language comparison falls back unless both decodes are scored`() {
        val english = TranscriberClient.ScoredResult("English", null, -0.40)
        val failedArabic = TranscriberClient.ScoredResult(null, "network", null)
        assertNull(TranscriberClient.chooseHigherConfidence(english, failedArabic))
    }

    @Test fun `parses success response`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `parses error response`() {
        val r = TranscriberClient.parseResponse("""{"error":{"message":"Invalid key","type":"auth"}}""")
        assertNull(r.text)
        assertEquals("Invalid key", r.error)
    }

    @Test fun `handles unknown format`() {
        val r = TranscriberClient.parseResponse("""{"foo":"bar"}""")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `handles malformed json`() {
        val r = TranscriberClient.parseResponse("not json")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `detects only genuine mixed-script transcripts`() {
        assertTrue(TranscriberClient.isArabicLatinMix("نجرب العربي and English"))
        assertFalse(TranscriberClient.isArabicLatinMix("العربي فقط"))
        assertFalse(TranscriberClient.isArabicLatinMix("English only"))
    }

    @Test fun `filters short hallucinated chunks`() {
        assertFalse(TranscriberClient.isMeaningfulChunk("Thank you."))
        assertFalse(TranscriberClient.isMeaningfulChunk("Yes."))
        assertFalse(TranscriberClient.isMeaningfulChunk("."))
        assertTrue(TranscriberClient.isMeaningfulChunk("The English text doesn't show."))
    }
}
