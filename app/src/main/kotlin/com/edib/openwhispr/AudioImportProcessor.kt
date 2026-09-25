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
 * Converts arbitrary selected audio into OpenWispr's canonical ASR input:
 * PCM16 mono 16-kHz WAV.
 *
 * Pipeline:
 * 1) Android MediaExtractor/MediaCodec decode (MP3/M4A/AAC/Opus/WAV etc.)
 * 2) float-domain channel downmix
 * 3) band-limited 16-kHz resampling (sherpa/Kaldi-style windowed sinc)
 * 4) very-low-cut DC/rumble removal
 * 5) bounded active-speech RMS normalization with peak protection
 *
 * No denoiser, compressor, EQ, or speech enhancement is applied because those
 * can alter phonemes and hurt ASR. The goal is clean format/level consistency,
 * not cosmetic audio processing.
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
        var decoder: MediaCodec? = null
        val id = UUID.randomUUID().toString()
        val rawPcm = File(context.cacheDir, "import-$id.pcm")
        val finalWav = File(context.cacheDir, "import-$id.wav")
        val sourceCopy = File(context.cacheDir, "import-$id.source")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(sourceCopy).use { out ->
                    input.copyTo(out, 64 * 1024)
                    out.flush()
                    out.fd.sync()
                }
            } ?: throw IllegalArgumentException("Unable to read selected audio")
            require(sourceCopy.length() > 0L) { "Selected audio is empty" }

            extractor.setDataSource(sourceCopy.absolutePath)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    .orEmpty()
                    .startsWith("audio/")
            } ?: throw IllegalArgumentException("No audio track found in this file")

            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Unknown audio format")
            val declaredRate =
                sourceFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: TARGET_RATE
            val declaredChannels =
                sourceFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1

            val isRawPcm = mime == MediaFormat.MIMETYPE_AUDIO_RAW || mime == "audio/raw"
            val mediaDecoder = if (isRawPcm) {
                null
            } else {
                MediaCodec.createDecoderByType(mime).also { created ->
                    decoder = created
                    created.configure(sourceFormat, null, null, 0)
                    created.start()
                }
            }

            var currentRate = declaredRate
            var currentChannels = declaredChannels
            var currentEncoding =
                sourceFormat.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING)
                    ?: AudioFormat.ENCODING_PCM_16BIT
            var resampler: BandLimitedResampler? = null
            var resamplerInputRate: Int? = null

            var outputSamples = 0L
            var peak = 0f
            var activeSquareSum = 0.0
            var activeCount = 0L

            // High-pass ~20 Hz after resampling. This only removes DC and
            // sub-audible handling rumble; speech fundamentals are untouched.
            val hpAlpha = exp(-2.0 * PI * 20.0 / TARGET_RATE).toFloat()
            var hpPrevX = 0f
            var hpPrevY = 0f

            BufferedOutputStream(FileOutputStream(rawPcm), 64 * 1024).use { pcmOut ->
                fun acceptCanonical(sample: Float) {
                    val x = sample.coerceIn(-1f, 1f)
                    val filtered =
                        (x - hpPrevX + hpAlpha * hpPrevY).coerceIn(-1f, 1f)
                    hpPrevX = x
                    hpPrevY = filtered

                    val magnitude = kotlin.math.abs(filtered)
                    if (magnitude > peak) peak = magnitude
                    if (magnitude >= ACTIVE_THRESHOLD) {
                        activeSquareSum += filtered.toDouble() * filtered.toDouble()
                        activeCount++
                    }

                    val pcm16 =
                        (filtered * 32767f).toInt().coerceIn(-32768, 32767)
                    pcmOut.write(pcm16 and 0xff)
                    pcmOut.write((pcm16 shr 8) and 0xff)
                    outputSamples++
                }

                fun feedDecodedMono(samples: FloatArray, sampleRate: Int) {
                    if (samples.isEmpty()) return
                    if (sampleRate == TARGET_RATE) {
                        samples.forEach(::acceptCanonical)
                        return
                    }

                    if (resampler == null) {
                        resamplerInputRate = sampleRate
                        resampler =
                            BandLimitedResampler(sampleRate, TARGET_RATE)
                    } else {
                        require(resamplerInputRate == sampleRate) {
                            "Audio sample rate changed during decode"
                        }
                    }

                    resampler!!.resample(samples, flush = false)
                        .forEach(::acceptCanonical)
                }

                if (mediaDecoder == null) {
                    // PCM WAV/AIFF-style tracks are already decoded. There is
                    // no MediaCodec for audio/raw on many devices, so feed the
                    // extractor's PCM samples directly into the same canonical
                    // downmix/resample path.
                    val rawBuffer =
                        ByteBuffer.allocateDirect(256 * 1024)
                            .order(ByteOrder.LITTLE_ENDIAN)

                    while (true) {
                        rawBuffer.clear()
                        val size = extractor.readSampleData(rawBuffer, 0)
                        if (size < 0) break

                        rawBuffer.position(0)
                        rawBuffer.limit(size)
                        val mono = decodeInterleavedPcm(
                            rawBuffer.slice().order(ByteOrder.LITTLE_ENDIAN),
                            currentChannels.coerceAtLeast(1),
                            currentEncoding
                        )
                        feedDecodedMono(mono, currentRate)
                        extractor.advance()
                    }
                } else {
                    val activeDecoder = mediaDecoder
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false

                    while (!outputDone) {
                        if (!inputDone) {
                            val inputIndex = activeDecoder.dequeueInputBuffer(10_000)
                            if (inputIndex >= 0) {
                                val input = activeDecoder.getInputBuffer(inputIndex)
                                    ?: throw IllegalStateException(
                                        "Decoder input buffer unavailable"
                                    )
                                input.clear()

                                val size = extractor.readSampleData(input, 0)
                                if (size < 0) {
                                    activeDecoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    activeDecoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        size,
                                        extractor.sampleTime,
                                        0
                                    )
                                    extractor.advance()
                                }
                            }
                        }

                        when (
                            val outputIndex =
                                activeDecoder.dequeueOutputBuffer(info, 10_000)
                        ) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val outputFormat = activeDecoder.outputFormat
                                currentRate =
                                    outputFormat.getIntegerOrNull(
                                        MediaFormat.KEY_SAMPLE_RATE
                                    ) ?: currentRate
                                currentChannels =
                                    outputFormat.getIntegerOrNull(
                                        MediaFormat.KEY_CHANNEL_COUNT
                                    ) ?: currentChannels
                                currentEncoding =
                                    outputFormat.getIntegerOrNull(
                                        MediaFormat.KEY_PCM_ENCODING
                                    ) ?: AudioFormat.ENCODING_PCM_16BIT
                            }

                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                            else -> if (outputIndex >= 0) {
                                val isCodecConfig =
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                                if (info.size > 0 && !isCodecConfig) {
                                    val output =
                                        activeDecoder.getOutputBuffer(outputIndex)
                                            ?: throw IllegalStateException(
                                                "Decoder output buffer unavailable"
                                            )
                                    val duplicate =
                                        output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                    duplicate.position(info.offset)
                                    duplicate.limit(info.offset + info.size)
                                    val slice =
                                        duplicate.slice().order(ByteOrder.LITTLE_ENDIAN)

                                    val mono = decodeInterleavedPcm(
                                        slice,
                                        currentChannels.coerceAtLeast(1),
                                        currentEncoding
                                    )
                                    feedDecodedMono(mono, currentRate)
                                }

                                outputDone =
                                    (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                activeDecoder.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                }

                // Emit the resampler tail using zero-padding exactly as the
                // underlying sherpa/Kaldi algorithm does at end-of-stream.
                resampler?.resample(FloatArray(0), flush = true)
                    ?.forEach(::acceptCanonical)
            }

            require(outputSamples > 0) {
                "Decoder produced no audio samples"
            }

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
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            rawPcm.delete()
            sourceCopy.delete()

            if (!finalWav.exists() || finalWav.length() <= 44L) {
                finalWav.delete()
            }
        }
    }

    private fun decodeInterleavedPcm(
        buffer: ByteBuffer,
        channels: Int,
        encoding: Int
    ): FloatArray {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT,
            AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 2
        }

        val frameBytes = bytesPerSample * channels
        if (frameBytes <= 0) return FloatArray(0)

        val frameCount = buffer.remaining() / frameBytes
        val mono = FloatArray(frameCount)

        var frame = 0
        while (frame < frameCount) {
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
                        val value = b0 or (b1 shl 8) or (b2 shl 16)
                        value / 8_388_608f
                    }

                    AudioFormat.ENCODING_PCM_32BIT ->
                        buffer.int / 2_147_483_648f

                    else ->
                        buffer.short / 32768f
                }
            }

            mono[frame] =
                (sum / channels.toFloat()).coerceIn(-1f, 1f)
            frame++
        }

        return mono
    }

    private fun writeNormalizedWav(
        sourcePcm: File,
        destination: File,
        sampleCount: Long,
        gain: Float
    ) {
        val dataSize = sampleCount * 2L
        require(dataSize <= Int.MAX_VALUE.toLong()) {
            "Imported audio is too long"
        }

        BufferedOutputStream(
            FileOutputStream(destination),
            64 * 1024
        ).use { out ->
            out.write(wavHeader(dataSize.toInt(), TARGET_RATE))

            BufferedInputStream(
                FileInputStream(sourcePcm),
                64 * 1024
            ).use { input ->
                while (true) {
                    val lo = input.read()
                    if (lo < 0) break
                    val hi = input.read()
                    if (hi < 0) break

                    val sample =
                        ((hi shl 8) or lo).toShort().toInt()
                    val scaled =
                        (sample * gain).toInt().coerceIn(-32768, 32767)

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
            value.forEachIndexed { i, c ->
                header[offset + i] = c.code.toByte()
            }
        }

        fun putInt(offset: Int, value: Int) {
            repeat(4) { i ->
                header[offset + i] = (value shr (8 * i)).toByte()
            }
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

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) {
            runCatching { getInteger(key) }.getOrNull()
        } else {
            null
        }
}
