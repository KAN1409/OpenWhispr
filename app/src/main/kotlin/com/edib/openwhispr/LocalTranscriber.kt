package com.edib.openwhispr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Local on-device transcription via sherpa-onnx.
 *
 * Native recognizer memory is intentionally releasable without destroying the
 * Kotlin wrapper. This lets benchmark runs evict a resident overlay model and
 * have it load lazily again on the next real dictation, avoiding two large
 * sherpa models being resident at the same time.
 */
class LocalTranscriber private constructor(
    private val context: Context,
    private val modelName: String,
    initialRecognizer: OfflineRecognizer
) : AutoCloseable {

    @Volatile
    private var recognizer: OfflineRecognizer? = initialRecognizer

    @Volatile
    private var permanentlyClosed = false

    init {
        synchronized(liveInstances) {
            liveInstances.add(this)
        }
    }

    /** Transcribe raw PCM float samples. Blocking — call from a background thread. */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String =
        exclusive {
            check(!permanentlyClosed) { "Local transcriber is closed" }
            val active = ensureRecognizer()
            val stream = active.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                active.decode(stream)
                active.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }

    /** Permanently closes this wrapper and its current native recognizer. */
    override fun close() {
        exclusive {
            if (permanentlyClosed) return@exclusive
            permanentlyClosed = true
            releaseNativeLocked()
            synchronized(liveInstances) {
                liveInstances.remove(this)
            }
        }
    }

    /**
     * Drops only native memory. The wrapper remains usable and reloads its
     * configured model lazily the next time transcribe() is called.
     */
    private fun releaseNativeForPressureLocked() {
        if (!permanentlyClosed) releaseNativeLocked()
    }

    private fun ensureRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        val replacement = createRecognizer(context, modelName)
            ?: throw IllegalStateException("Unable to reload local model: $modelName")
        recognizer = replacement
        return replacement
    }

    private fun releaseNativeLocked() {
        val current = recognizer ?: return
        recognizer = null
        try {
            current.release()
        } catch (e: LinkageError) {
            Log.w(TAG, "Recognizer release linkage error", e)
        } catch (e: Exception) {
            Log.w(TAG, "Recognizer release failed", e)
        }
    }

    companion object {
        private const val TAG = "LocalTranscriber"

        /**
         * Serializes model load, inference, and native release across the whole
         * process. Reentrant because benchmark orchestration may hold the lock
         * while calling transcribe(), which also enters it.
         */
        private val executionLock = ReentrantLock(true)

        private val liveInstances: MutableSet<LocalTranscriber> =
            Collections.newSetFromMap(WeakHashMap())

        fun <T> exclusive(block: () -> T): T = executionLock.withLock(block)

        /**
         * Releases native memory for every live wrapper without invalidating
         * those wrappers. Existing overlay references will reload lazily.
         */
        fun releaseIdleNativeMemory() {
            executionLock.withLock {
                val snapshot = synchronized(liveInstances) { liveInstances.toList() }
                snapshot.forEach { it.releaseNativeForPressureLocked() }
            }
        }

        /** Find available model dirs under the app's files/models/ dir. */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        }

        /** Create a LocalTranscriber for the given model directory name. Returns null on failure. */
        fun create(ctx: Context, modelName: String): LocalTranscriber? {
            val appContext = ctx.applicationContext
            val recognizer = createRecognizer(appContext, modelName) ?: return null
            Log.i(TAG, "Loaded model: $modelName")
            return LocalTranscriber(appContext, modelName, recognizer)
        }

        private fun createRecognizer(ctx: Context, modelName: String): OfflineRecognizer? {
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
                OfflineRecognizer(assetManager = null, config = config)
            } catch (e: LinkageError) {
                Log.e(TAG, "Native model runtime unavailable: ${e.message}", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: ${e.message}", e)
                null
            }
        }

        /** Auto-detect model type from files present in the directory. */
        private fun detectModelConfig(dir: File): OfflineRecognizerConfig? {
            val p = dir.absolutePath
            val tokens = "$p/tokens.txt"
            if (!File(tokens).exists()) return null

            // Moonshine (has preprocess.onnx)
            if (File("$p/preprocess.onnx").exists()) {
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

        /** Find first file matching prefix (prefer int8 quantized). */
        private fun findFile(dir: String, prefix: String): String? {
            val d = File(dir)
            d.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.name.contains("int8") }
                ?.let { return it.absolutePath }
            return d.listFiles()?.firstOrNull {
                it.name.startsWith(prefix) && (it.name.endsWith(".onnx") || it.name.endsWith(".ort"))
            }?.absolutePath
        }
    }
}
