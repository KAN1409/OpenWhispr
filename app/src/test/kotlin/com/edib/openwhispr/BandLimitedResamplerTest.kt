package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

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
