package com.edib.openwhispr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Band-limited rational sample-rate converter.
 *
 * Algorithm adapted from sherpa-onnx / Kaldi LinearResample (Apache-2.0):
 * raised-cosine-windowed sinc, cutoff 0.99 * Nyquist of the lower rate,
 * filter width 6. This avoids the aliasing that a naive linear interpolator
 * introduces when importing 44.1/48-kHz voice notes into a 16-kHz ASR path.
 */
class BandLimitedResampler(
    private val inputRate: Int,
    private val outputRate: Int,
    private val filterCutoff: Float =
        (0.99f * 0.5f * min(inputRate, outputRate).toFloat()),
    private val numZeros: Int = 6
) {
    private val inputSamplesInUnit: Int
    private val outputSamplesInUnit: Int
    private val firstIndex: IntArray
    private val weights: Array<FloatArray>

    private var inputSampleOffset = 0L
    private var outputSampleOffset = 0L
    private var remainder = FloatArray(0)

    init {
        require(inputRate > 0 && outputRate > 0)
        require(filterCutoff > 0f)
        require(filterCutoff * 2f <= inputRate)
        require(filterCutoff * 2f <= outputRate)
        require(numZeros > 0)

        val base = gcd(inputRate, outputRate)
        inputSamplesInUnit = inputRate / base
        outputSamplesInUnit = outputRate / base

        firstIndex = IntArray(outputSamplesInUnit)
        weights = Array(outputSamplesInUnit) { FloatArray(0) }
        setIndexesAndWeights()
    }

    fun resample(input: FloatArray, flush: Boolean): FloatArray {
        val totalInput = inputSampleOffset + input.size
        val totalOutput = getNumOutputSamples(totalInput, flush)
        require(totalOutput >= outputSampleOffset)

        val output = FloatArray((totalOutput - outputSampleOffset).toInt())

        var outIndex = 0
        var sampleOut = outputSampleOffset
        while (sampleOut < totalOutput) {
            val unitIndex = sampleOut / outputSamplesInUnit
            val wrapped = (sampleOut - unitIndex * outputSamplesInUnit).toInt()
            val firstSampleIn =
                firstIndex[wrapped].toLong() + unitIndex * inputSamplesInUnit
            val ws = weights[wrapped]
            val firstInputIndex = (firstSampleIn - inputSampleOffset).toInt()

            var value = 0f
            if (firstInputIndex >= 0 && firstInputIndex + ws.size <= input.size) {
                var i = 0
                while (i < ws.size) {
                    value += input[firstInputIndex + i] * ws[i]
                    i++
                }
            } else {
                var i = 0
                while (i < ws.size) {
                    val inputIndex = firstInputIndex + i
                    value += when {
                        inputIndex < 0 && remainder.size + inputIndex >= 0 ->
                            ws[i] * remainder[remainder.size + inputIndex]

                        inputIndex >= 0 && inputIndex < input.size ->
                            ws[i] * input[inputIndex]

                        else -> 0f
                    }
                    i++
                }
            }

            output[outIndex++] = value
            sampleOut++
        }

        if (flush) {
            reset()
        } else {
            setRemainder(input)
            inputSampleOffset = totalInput
            outputSampleOffset = totalOutput
        }

        return output
    }

    private fun setIndexesAndWeights() {
        val windowWidth = numZeros / (2.0 * filterCutoff)

        for (i in 0 until outputSamplesInUnit) {
            val outputT = i / outputRate.toDouble()
            val minT = outputT - windowWidth
            val maxT = outputT + windowWidth
            val minInputIndex = ceil(minT * inputRate).toInt()
            val maxInputIndex = floor(maxT * inputRate).toInt()
            val count = maxInputIndex - minInputIndex + 1

            firstIndex[i] = minInputIndex
            weights[i] = FloatArray(count) { j ->
                val inputIndex = minInputIndex + j
                val inputT = inputIndex / inputRate.toDouble()
                val deltaT = (inputT - outputT).toFloat()
                filterFunc(deltaT) / inputRate.toFloat()
            }
        }
    }

    private fun filterFunc(t: Float): Float {
        val width = numZeros / (2.0f * filterCutoff)
        val window = if (abs(t) < width) {
            0.5f * (
                1f + cos(
                    2.0 * PI * filterCutoff.toDouble() / numZeros.toDouble() *
                        t.toDouble()
                ).toFloat()
            )
        } else {
            0f
        }

        val filter = if (t != 0f) {
            (
                sin(2.0 * PI * filterCutoff.toDouble() * t.toDouble()) /
                    (PI * t.toDouble())
                ).toFloat()
        } else {
            2f * filterCutoff
        }

        return filter * window
    }

    private fun getNumOutputSamples(inputNumSamples: Long, flush: Boolean): Long {
        val tickFrequency = lcm(inputRate, outputRate)
        val ticksPerInputPeriod = tickFrequency / inputRate
        var intervalTicks = inputNumSamples * ticksPerInputPeriod

        if (!flush) {
            val windowWidth = numZeros / (2.0 * filterCutoff)
            val windowWidthTicks = floor(windowWidth * tickFrequency).toInt()
            intervalTicks -= windowWidthTicks
        }

        if (intervalTicks <= 0L) return 0L

        val ticksPerOutputPeriod = tickFrequency / outputRate
        var lastOutputSample = intervalTicks / ticksPerOutputPeriod
        if (lastOutputSample * ticksPerOutputPeriod == intervalTicks) {
            lastOutputSample--
        }

        return lastOutputSample + 1L
    }

    private fun setRemainder(input: FloatArray) {
        val old = remainder
        val maxNeeded = ceil(inputRate * numZeros / filterCutoff).toInt()
        val next = FloatArray(maxNeeded)

        var index = -maxNeeded
        while (index < 0) {
            val inputIndex = index + input.size
            val dest = index + maxNeeded
            when {
                inputIndex >= 0 ->
                    next[dest] = input[inputIndex]

                inputIndex + old.size >= 0 ->
                    next[dest] = old[inputIndex + old.size]
            }
            index++
        }

        remainder = next
    }

    private fun reset() {
        inputSampleOffset = 0L
        outputSampleOffset = 0L
        remainder = FloatArray(0)
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = kotlin.math.abs(a)
        var y = kotlin.math.abs(b)
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    private fun lcm(a: Int, b: Int): Int {
        val divisor = gcd(a, b)
        return (a / divisor) * b
    }
}
