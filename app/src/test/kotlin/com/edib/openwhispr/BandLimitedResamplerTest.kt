package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs

class BandLimitedResamplerTest {
    @Test
    fun downsampling48kTo16kKeepsExpectedLengthAndSpeechBand() {
        val rateIn = 48_000
        val rateOut = 16_000
        val durationSec = 1
        val input = FloatArray(rateIn * durationSec) { i ->
            sin(2.0 * PI * 1000.0 * i.toDouble() / rateIn.toDouble()).toFloat()
        }

        val output = BandLimitedResampler(rateIn, rateOut).resample(input, flush = true)

        assertEquals(rateOut, output.size)
        val rms = sqrt(output.map { it.toDouble() * it.toDouble() }.average())
        assertTrue("1-kHz speech-band tone should survive resampling", rms > 0.65)
        assertTrue("Resampler output must stay bounded", output.all { it.isFinite() && kotlin.math.abs(it) <= 1.05f })
    }

    @Test
    fun streamingOutputMatchesOneShotOutput() {
        val rateIn = 44_100
        val rateOut = 16_000
        val input = FloatArray(rateIn * 2) { i ->
            (
                0.55 * sin(2.0 * PI * 440.0 * i.toDouble() / rateIn.toDouble()) +
                    0.25 * sin(2.0 * PI * 3200.0 * i.toDouble() / rateIn.toDouble())
                ).toFloat()
        }

        val oneShot =
            BandLimitedResampler(rateIn, rateOut).resample(input, flush = true)

        val streaming = BandLimitedResampler(rateIn, rateOut)
        val parts = mutableListOf<FloatArray>()
        var offset = 0
        val chunkSize = 7_777
        while (offset < input.size) {
            val end = minOf(input.size, offset + chunkSize)
            parts += streaming.resample(
                input.copyOfRange(offset, end),
                flush = false
            )
            offset = end
        }
        parts += streaming.resample(FloatArray(0), flush = true)

        val streamed = FloatArray(parts.sumOf { it.size })
        var outOffset = 0
        parts.forEach { part ->
            part.copyInto(streamed, outOffset)
            outOffset += part.size
        }

        assertEquals(oneShot.size, streamed.size)
        val maxDelta = oneShot.indices.maxOf { i ->
            abs(oneShot[i] - streamed[i]).toDouble()
        }
        assertTrue("Streaming and one-shot resampling must match", maxDelta < 1e-5)
    }

    @Test
    fun downsamplingSuppressesAboveNyquistEnergy() {
        val rateIn = 48_000
        val rateOut = 16_000
        val input = FloatArray(rateIn) { i ->
            sin(2.0 * PI * 12_000.0 * i.toDouble() / rateIn.toDouble()).toFloat()
        }

        val output = BandLimitedResampler(rateIn, rateOut).resample(input, flush = true)
        val rms = sqrt(output.map { it.toDouble() * it.toDouble() }.average())

        assertTrue("12-kHz energy should be strongly attenuated before 16-kHz output", rms < 0.12)
    }
}
