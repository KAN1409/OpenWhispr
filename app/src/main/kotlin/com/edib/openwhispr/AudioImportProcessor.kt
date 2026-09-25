package com.edib.openwhispr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Converts arbitrary user-selected audio into OpenWispr's canonical ASR input:
 * PCM16 mono 16 kHz WAV.
 *
 * The conversion is deliberately conservative:
 * - native Android decoder (no lossy re-encode through a media container)
 * - channel downmix in float
 * - linear resampling to the model rate
 * - very-low-cut DC/rumble removal
 * - bounded active-speech RMS normalization with peak protection
 *
 * The normalized WAV is only an ASR working copy. It avoids feeding different
 * sample rates/channel layouts/levels into the recognizer.
 */
object AudioImportProcessor {
    private const val TARGET_RATE = 16_000
    private const val TARGET_ACTIVE_RMS = 0.10f
    private const val ACTIVE_THRESHOLD = 0.015f
    private const val MAX_GAIN = 4.0f
    private const val MIN_GAIN = 0.5f
    private const val PEAK_LIMIT = 0.95f

    data class PreparedAudio(
        val wavFile: File,
        val durationMs: Long,
        val sourceSampleRate: Int,
        val sourceChannels: Int,
        val appliedGain: Float
    ) {
        val summary: String
            get() = "${sourceSampleRate / 1000.0} kHz / ${sourceChannels}ch → 16 kHz mono"
    }

    fun prepare(context: Context, uri: Uri): PreparedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val id = UUID.randomUUID().toString()
        val rawPcm = File(context.cacheDir, "import-$id.pcm")
        val finalWav = File(context.cacheDir, "import-$id.wav")

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                val mime = extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    .orEmpty()
                mime.startsWith("audio/")
            } ?: throw IllegalArgumentException("No audio track found in this file")

            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Unknown audio format")
            val declaredRate = sourceFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: TARGET_RATE
            val declaredChannels = sourceFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()

            var currentRate = declaredRate
            var currentChannels = declaredChannels
            var currentEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler: StreamingResampler? = null
            var outputSamples = 0L
            var peak = 0f
            var activeSquareSum = 0.0
            var activeCount = 0L

            // High-pass at ~20 Hz after resampling: removes DC/handling rumble
            // without cutting useful speech fundamentals.
            val hpAlpha = exp(-2.0 * PI * 20.0 / TARGET_RATE).toFloat()
            var hpPrevX = 0f
            var hpPrevY = 0f

            BufferedOutputStream(FileOutputStream(rawPcm), 64 * 1024).use { pcmOut ->
                fun acceptCanonical(sample: Float) {
                    val x = sample.coerceIn(-1f, 1f)
                    val filtered = (x - hpPrevX + hpAlpha * hpPrevY).coerceIn(-1f, 1f)
                    hpPrevX = x
                    hpPrevY = filtered

                    val abs = kotlin.math.abs(filtered)
                    if (abs > peak) peak = abs
                    if (abs >= ACTIVE_THRESHOLD) {
                        activeSquareSum += filtered.toDouble() * filtered.toDouble()
                        activeCount++
                    }

                    val s = (filtered * 32767f).toInt().coerceIn(-32768, 32767)
                    pcmOut.write(s and 0xff)
                    pcmOut.write((s shr 8) and 0xff)
                    outputSamples++
                }

                fun ensureResampler(rate: Int): StreamingResampler {
                    val existing = resampler
                    if (existing != null) {
                        require(existing.inputRate == rate) {
                            "Audio sample rate changed during decode"
                        }
                        return existing
                    }
                    return StreamingResampler(rate, TARGET_RATE, ::acceptCanonical).also {
                        resampler = it
                    }
                }

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = decoder.getInputBuffer(inputIndex)
                                ?: throw IllegalStateException("Decoder input buffer unavailable")
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, size, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            currentRate = outputFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE)
                                ?: currentRate
                            currentChannels = outputFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                                ?: currentChannels
                            currentEncoding = outputFormat.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING)
                                ?: AudioFormat.ENCODING_PCM_16BIT
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        else -> if (outputIndex >= 0) {
                            if (info.size > 0) {
                                val output = decoder.getOutputBuffer(outputIndex)
                                    ?: throw IllegalStateException("Decoder output buffer unavailable")
                                val duplicate = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                duplicate.position(info.offset)
                                duplicate.limit(info.offset + info.size)
                                val slice = duplicate.slice().order(ByteOrder.LITTLE_ENDIAN)

                                val rs = ensureResampler(currentRate)
                                decodeInterleavedPcm(
                                    slice,
                                    currentChannels.coerceAtLeast(1),
                                    currentEncoding
                                ) { mono ->
                                    rs.accept(mono)
                                }
                            }

                            outputDone =
                                (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            require(outputSamples > 0) { "Decoder produced no audio samples" }

            val activeRms = if (activeCount > 0) {
                sqrt(activeSquareSum / activeCount.toDouble()).toFloat()
            } else {
                0f
            }
            val rmsGain = if (activeRms > 0f) {
                (TARGET_ACTIVE_RMS / activeRms).coerceIn(MIN_GAIN, MAX_GAIN)
            } else {
                1f
            }
            val peakGain = if (peak > 0f) PEAK_LIMIT / peak else 1f
            val gain = min(rmsGain, peakGain).coerceAtLeast(MIN_GAIN)

            writeNormalizedWav(rawPcm, finalWav, outputSamples, gain)
            val durationMs = outputSamples * 1000L / TARGET_RATE

            return PreparedAudio(
                wavFile = finalWav,
                durationMs = durationMs,
                sourceSampleRate = declaredRate,
                sourceChannels = declaredChannels,
                appliedGain = gain
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            rawPcm.delete()
            if (!finalWav.exists() || finalWav.length() <= 44L) {
                finalWav.delete()
            }
        }
    }

    private fun decodeInterleavedPcm(
        buffer: ByteBuffer,
        channels: Int,
        encoding: Int,
        onMonoSample: (Float) -> Unit
    ) {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT,
            AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 2
        }
        val frameBytes = bytesPerSample * channels
        if (frameBytes <= 0) return

        while (buffer.remaining() >= frameBytes) {
            var sum = 0f
            repeat(channels) {
                sum += when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT ->
                        ((buffer.get().toInt() and 0xff) - 128) / 128f

                    AudioFormat.ENCODING_PCM_FLOAT ->
                        buffer.float.coerceIn(-1f, 1f)

                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val b0 = buffer.get().toInt() and 0xff
                        val b1 = buffer.get().toInt() and 0xff
                        val b2 = buffer.get().toInt()
                        val value = (b0 or (b1 shl 8) or (b2 shl 16))
                        value / 8_388_608f
                    }

                    AudioFormat.ENCODING_PCM_32BIT ->
                        buffer.int / 2_147_483_648f

                    else ->
                        buffer.short / 32768f
                }
            }
            onMonoSample((sum / channels.toFloat()).coerceIn(-1f, 1f))
        }
    }

    private fun writeNormalizedWav(
        sourcePcm: File,
        destination: File,
        sampleCount: Long,
        gain: Float
    ) {
        val dataSize = sampleCount * 2L
        require(dataSize <= Int.MAX_VALUE.toLong()) { "Imported audio is too long" }

        BufferedOutputStream(FileOutputStream(destination), 64 * 1024).use { out ->
            out.write(wavHeader(dataSize.toInt(), TARGET_RATE))
            BufferedInputStream(FileInputStream(sourcePcm), 64 * 1024).use { input ->
                while (true) {
                    val lo = input.read()
                    if (lo < 0) break
                    val hi = input.read()
                    if (hi < 0) break
                    val sample = (((hi shl 8) or lo).toShort().toInt())
                    val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
                    out.write(scaled and 0xff)
                    out.write((scaled shr 8) and 0xff)
                }
            }
            out.flush()
        }
    }

    private fun wavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val header = ByteArray(44)
        fun putStr(offset: Int, value: String) {
            value.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        }
        fun putInt(offset: Int, value: Int) {
            repeat(4) { i -> header[offset + i] = (value shr (8 * i)).toByte() }
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = value.toByte()
            header[offset + 1] = (value shr 8).toByte()
        }

        putStr(0, "RIFF")
        putInt(4, 36 + dataSize)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        putShort(32, 2)
        putShort(34, 16)
        putStr(36, "data")
        putInt(40, dataSize)
        return header
    }

    private class StreamingResampler(
        val inputRate: Int,
        outputRate: Int,
        private val sink: (Float) -> Unit
    ) {
        private val step = inputRate.toDouble() / outputRate.toDouble()
        private var inputIndex = 0L
        private var nextSourcePosition = 0.0
        private var previous = 0f
        private var hasPrevious = false

        fun accept(sample: Float) {
            if (!hasPrevious) {
                previous = sample
                hasPrevious = true
                if (nextSourcePosition == 0.0) {
                    sink(sample)
                    nextSourcePosition += step
                }
                return
            }

            inputIndex++
            val leftIndex = inputIndex - 1L
            while (nextSourcePosition <= inputIndex.toDouble()) {
                val fraction = (nextSourcePosition - leftIndex.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
                sink(previous + (sample - previous) * fraction)
                nextSourcePosition += step
            }
            previous = sample
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
}
