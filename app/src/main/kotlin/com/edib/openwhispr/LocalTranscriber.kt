package com.edib.openwhispr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * Local on-device transcription via sherpa-onnx.
 * Models are loaded from the app's external files dir.
 */
class LocalTranscriber private constructor(
    private val recognizer: OfflineRecognizer,
    val modelName: String,
) {
    /**
     * Transcribe raw PCM float samples. Blocking — call from background thread.
     *
     * Whisper only ever sees 30 s of audio at a time, so anything longer has to
     * be split. Audio is cut on the quietest point near each 30 s boundary
     * instead of on a hard fixed offset, which keeps words from being sliced in
     * half, and each chunk is decoded as its own stream (sherpa resets the
     * decoder per stream, so chunking cannot introduce translation).
     */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        if (samples.isEmpty()) return ""
        if (samples.size <= CHUNK_SAMPLES) return decodeChunk(samples, sampleRate)

        val pieces = ArrayList<String>()
        var pos = 0
        while (pos < samples.size) {
            val end = minOf(pos + CHUNK_SAMPLES, samples.size)
            var cut = end
            if (end < samples.size) {
                // Pull the boundary back to the quietest frame in the search
                // window so the split lands in a pause, not mid-word.
                val from = maxOf(pos, end - SEEK_SAMPLES)
                cut = quietestFrom(samples, from, end)
            }
            val chunk = samples.copyOfRange(pos, cut)
            if (chunk.isNotEmpty()) {
                val text = decodeChunk(chunk, sampleRate)
                if (text.isNotBlank()) pieces.add(text.trim())
            }
            if (cut <= pos) cut = end // never loop forever on silence
            pos = cut
        }
        return pieces.joinToString(" ")
    }

    private fun decodeChunk(samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** Index (exclusive) of the lowest-RMS 20 ms frame in [from, to). */
    private fun quietestFrom(samples: FloatArray, from: Int, to: Int): Int {
        var best = from
        var bestEnergy = Float.MAX_VALUE
        var i = from
        while (i + WINDOW_SAMPLES <= to) {
            var sum = 0f
            for (k in 0 until WINDOW_SAMPLES) {
                val v = samples[i + k]
                sum += v * v
            }
            if (sum < bestEnergy) {
                bestEnergy = sum
                best = i
            }
            i += WINDOW_SAMPLES
        }
        return best
    }

    companion object {
        private const val TAG = "LocalTranscriber"

        /** Whisper's receptive field: 30 s at 16 kHz. */
        private const val CHUNK_SAMPLES = 30 * 16000

        /** How far back from a hard boundary we look for a quieter split point. */
        private const val SEEK_SAMPLES = 3 * 16000

        /** RMS analysis window for split-point selection (20 ms). */
        private const val WINDOW_SAMPLES = 320

        /** Find available model dirs under the app's files/models/ dir */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        }

        /**
         * Shared handle to the one loaded model.
         *
         * A Whisper Large v3 session costs well over a gigabyte of native
         * memory. Loading one per note means transcribing a queue of notes
         * allocates one copy per concurrent thread, which drives the app into
         * an OOM kill. A single cached instance is reused instead, and callers
         * serialise their work through [withShared].
         */
        private val lock = Any()

        @Volatile
        private var cachedModel: String? = null

        @Volatile
        private var cachedRecognizer: LocalTranscriber? = null

        /**
         * Run [block] with a transcriber for [modelName], loading it at most
         * once per process. Only one caller at a time holds the model.
         */
        fun <T> withShared(ctx: Context, modelName: String, block: (LocalTranscriber) -> T): T? {
            if (modelName.isBlank()) return null
            synchronized(lock) {
                val existing = cachedRecognizer
                if (existing != null && cachedModel == modelName) {
                    return block(existing)
                }
                // A different model was loaded before: drop the old session so
                // its native memory is released before loading the new one.
                cachedRecognizer = null
                cachedModel = null
                System.gc()

                val loaded = create(ctx, modelName) ?: return null
                cachedRecognizer = loaded
                cachedModel = modelName
                return block(loaded)
            }
        }

        /** Release the cached model (used when the user switches models). */
        fun releaseCached() {
            synchronized(lock) {
                cachedRecognizer = null
                cachedModel = null
            }
        }

        /** Create a LocalTranscriber for the given model directory name. Returns null on failure. */
        fun create(ctx: Context, modelName: String): LocalTranscriber? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                Log.e(TAG, "Model dir not found: $modelDir")
                return null
            }

            val config = detectModelConfig(modelDir) ?: run {
                Log.e(TAG, "Could not detect model type in $modelDir")
                return null
            }

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: $modelName")
                LocalTranscriber(recognizer, modelName)
            } catch (e: LinkageError) {
                // Missing/incompatible sherpa native libraries surface as an
                // Error rather than an Exception. Never let that terminate the
                // app; callers can safely fall back to cloud transcription.
                Log.e(TAG, "Native model runtime unavailable: ${e.message}", e)
                null
            } catch (e: OutOfMemoryError) {
                // A model that will not fit in memory must not take the process
                // down; the caller falls back to cloud transcription.
                Log.e(TAG, "Not enough memory to load $modelName", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: ${e.message}")
                null
            }
        }

        /**
         * Auto-detect model type from files present in the directory.
         *
         * Internal (not private) so unit tests can build the real config from a
         * synthetic model directory and assert the language/task settings that
         * the decoder will actually see.
         */
        internal fun detectModelConfig(dir: File): OfflineRecognizerConfig? {
            val p = dir.absolutePath
            // sherpa-onnx model archives are not consistent about the tokens
            // filename: some ship "tokens.txt", others ship "<model>-tokens.txt"
            // (e.g. "large-v3-tokens.txt"). Accept both, otherwise every
            // prefixed model fails to load and silently falls back to the cloud.
            val tokens = findTokens(dir) ?: return null

            // Moonshine (has preprocess.onnx)
            if (findFile(p, "preprocess") != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            preprocessor = "$p/preprocess.onnx",
                            encoder = findFile(p, "encode") ?: return null,
                            uncachedDecoder = findFile(p, "uncached_decode") ?: return null,
                            cachedDecoder = findFile(p, "cached_decode") ?: return null,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            // Whisper (has encoder + decoder, no joiner)
            val whisperEncoder = findFile(p, "encoder")
            val whisperDecoder = findFile(p, "decoder")
            if (whisperEncoder != null && whisperDecoder != null && findFile(p, "joiner") == null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = whisperEncoder,
                            decoder = whisperDecoder,
                            // sherpa-onnx defaults `language` to "en" and `task`
                            // to "transcribe" when they are not supplied. That
                            // default forces the English language token into the
                            // decoder prompt, so Arabic (and any Arabic/English
                            // code-switched) audio is decoded under the wrong
                            // language and comes back translated or in
                            // Modern-Standard Arabic instead of Egyptian.
                            //
                            // `task` must stay "transcribe" (the backend rejects
                            // anything else) so speech is never translated.
                            // `language` is left empty on purpose: for a
                            // multilingual Whisper an empty value makes sherpa
                            // run its own DetectLanguage pass and inject the
                            // right token, which keeps Arabic/English mixing
                            // intact. Hard-coding "ar" would break the English
                            // half of a code-switched sentence, and hard-coding
                            // "en" is what breaks Arabic.
                            language = "",
                            task = "transcribe",
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        modelType = "whisper",
                    )
                )
            }

            // NeMo transducer / Parakeet TDT (has encoder + decoder + joiner)
            val encoder = findFile(p, "encoder")
            val decoder = findFile(p, "decoder")
            val joiner = findFile(p, "joiner")
            if (encoder != null && decoder != null && joiner != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = encoder,
                            decoder = decoder,
                            joiner = joiner,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        modelType = "nemo_transducer",
                    )
                )
            }

            // NeMo CTC (single model.onnx / model.int8.onnx)
            val ctcModel = findFile(p, "model")
            if (ctcModel != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = ctcModel),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            return null
        }

        /**
         * Locate the tokens file for a model directory.
         *
         * Prefers a model-specific "<something>-tokens.txt" over a bare
         * "tokens.txt" when both are present, so a directory holding several
         * models still resolves to the right vocabulary.
         */
        internal fun findTokens(dir: File): String? {
            val files = dir.listFiles() ?: return null
            val named = files.filter { it.isFile && it.name.endsWith("tokens.txt") }
            // "<name>-tokens.txt" is more specific than "tokens.txt".
            return (named.firstOrNull { it.name != "tokens.txt" } ?: named.firstOrNull())
                ?.absolutePath
        }

        /**
         * Find the model file for a role ("encoder", "decoder", "joiner", ...).
         *
         * sherpa archives prefix every file with the model name, e.g.
         * "large-v3-encoder.int8.onnx", so a plain startsWith(role) misses
         * them. Match on the role appearing after the last '-' instead, and
         * still prefer an int8-quantised file when one exists.
         */
        private fun findFile(dir: String, role: String): String? {
            val d = File(dir)
            val candidates = d.listFiles()?.filter { f ->
                f.isFile && (f.name.endsWith(".onnx") || f.name.endsWith(".ort")) &&
                    roleMatches(f.name, role)
            } ?: return null
            return (candidates.firstOrNull { it.name.contains("int8") }
                ?: candidates.firstOrNull())?.absolutePath
        }

        /** True when [role] is the trailing component of an onnx filename. */
        private fun roleMatches(fileName: String, role: String): Boolean {
            val stem = fileName.removeSuffix(".onnx").removeSuffix(".ort")
            // strip any quantisation suffix, then take the last '-'-separated part
            val base = stem.substringBeforeLast(".")
            val parts = base.split('-')
            val tail = parts.last()
            return tail == role || tail.startsWith(role) && tail.length > role.length
        }
    }
}
