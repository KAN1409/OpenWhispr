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
                active.getResult(stream).text
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

        /**
         * Four ORT worker threads is a better ceiling for modern big.LITTLE
         * phones than the old hard-coded 2 while avoiding oversubscription.
         */
        private fun recommendedThreadCount(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        /** Find available model dirs under the app's files/models/ dir. */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        }

        /** Create a LocalTranscriber for the given model directory name. Returns null on failure. */
        fun create(ctx: Context, modelName: String): LocalTranscriber? = exclusive {
            val appContext = ctx.applicationContext
            val recognizer = createRecognizer(appContext, modelName) ?: return@exclusive null
            Log.i(TAG, "Loaded model: $modelName")
            LocalTranscriber(appContext, modelName, recognizer)
        }

        private fun createRecognizer(ctx: Context, modelName: String): OfflineRecognizer? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                Log.e(TAG, "Model dir not found: $modelDir")
                return null
            }

            val installIssue = installationIssue(modelDir, modelName)
            if (installIssue != null) {
                Log.e(TAG, "Model install invalid for $modelName: $installIssue")
                return null
            }

            val config = detectModelConfig(modelDir, modelName) ?: run {
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

        /** Returns null only when the downloaded model directory is complete enough to load. */
        fun installationIssue(ctx: Context, modelName: String): String? =
            installationIssue(File(ctx.filesDir, "models/$modelName"), modelName)

        fun isModelInstallComplete(ctx: Context, modelName: String): Boolean =
            installationIssue(ctx, modelName) == null

        private fun installationIssue(dir: File, modelName: String): String? {
            if (!dir.isDirectory) return "Model directory is missing"

            val tokens = findTokensFile(dir)
                ?: return "Token file is missing"

            val lowerName = modelName.lowercase()
            if (lowerName.contains("whisper")) {
                if (findModelFile(dir, "encoder") == null) return "Whisper encoder is missing"
                if (findModelFile(dir, "decoder") == null) return "Whisper decoder is missing"
                return null
            }

            if (lowerName.contains("moonshine")) {
                if (!File(dir, "preprocess.onnx").isFile) return "Moonshine preprocessor is missing"
                if (findModelFile(dir, "encode") == null) return "Moonshine encoder is missing"
                if (findModelFile(dir, "uncached_decode") == null) return "Moonshine uncached decoder is missing"
                if (findModelFile(dir, "cached_decode") == null) return "Moonshine cached decoder is missing"
                return null
            }

            // For Parakeet/NeMo packages, accept either transducer/TDT
            // (encoder+decoder+joiner) or a single CTC model file.
            val hasTransducer =
                findModelFile(dir, "encoder") != null &&
                findModelFile(dir, "decoder") != null &&
                findModelFile(dir, "joiner") != null
            val hasCtc = findModelFile(dir, "model") != null
            return if (hasTransducer || hasCtc) null else "Required NeMo model files are missing"
        }

        /** Auto-detect model type from files present in the directory. */
        private fun detectModelConfig(dir: File, modelName: String): OfflineRecognizerConfig? {
            val tokens = findTokensFile(dir)?.absolutePath ?: return null
            val lowerName = modelName.lowercase()

            // Moonshine v1 package naming is preprocess.onnx + encode*.onnx +
            // uncached_decode*.onnx + cached_decode*.onnx.
            if (lowerName.contains("moonshine") || File(dir, "preprocess.onnx").exists()) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            preprocessor = File(dir, "preprocess.onnx").absolutePath,
                            encoder = findModelFile(dir, "encode") ?: return null,
                            uncachedDecoder = findModelFile(dir, "uncached_decode") ?: return null,
                            cachedDecoder = findModelFile(dir, "cached_decode") ?: return null,
                        ),
                        tokens = tokens,
                        numThreads = recommendedThreadCount(),
                    )
                )
            }

            // Official sherpa Whisper archives prefix files with the model name:
            // e.g. large-v3-encoder.int8.onnx and large-v3-tokens.txt.
            if (lowerName.contains("whisper")) {
                val whisperEncoder = findModelFile(dir, "encoder") ?: return null
                val whisperDecoder = findModelFile(dir, "decoder") ?: return null
                val englishOnly = lowerName.contains(".en")
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = whisperEncoder,
                            decoder = whisperDecoder,
                            language = if (englishOnly) "en" else "",
                            task = "transcribe",
                        ),
                        tokens = tokens,
                        numThreads = recommendedThreadCount(),
                        modelType = "whisper",
                    )
                )
            }

            // NeMo transducer / Parakeet TDT.
            val encoder = findModelFile(dir, "encoder")
            val decoder = findModelFile(dir, "decoder")
            val joiner = findModelFile(dir, "joiner")
            if (encoder != null && decoder != null && joiner != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = encoder,
                            decoder = decoder,
                            joiner = joiner,
                        ),
                        tokens = tokens,
                        numThreads = recommendedThreadCount(),
                        modelType = "nemo_transducer",
                    )
                )
            }

            // NeMo CTC.
            val ctcModel = findModelFile(dir, "model")
            if (ctcModel != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = ctcModel),
                        tokens = tokens,
                        numThreads = recommendedThreadCount(),
                    )
                )
            }

            return null
        }

        private fun findTokensFile(dir: File): File? {
            val files = dir.listFiles()?.filter { it.isFile } ?: return null
            return files.firstOrNull { it.name == "tokens.txt" }
                ?: files.firstOrNull { it.name.endsWith("-tokens.txt") }
        }

        /**
         * Finds both generic sherpa names (encoder.int8.onnx) and Whisper's
         * prefixed names (large-v3-encoder.int8.onnx). Prefer int8 artifacts.
         */
        private fun findModelFile(dir: File, role: String): String? {
            val candidates = dir.listFiles()?.filter { file ->
                if (!file.isFile) return@filter false
                val n = file.name
                val supported = n.endsWith(".onnx") || n.endsWith(".ort")
                val roleMatch =
                    n.startsWith("$role.") ||
                    n.startsWith("$role-") ||
                    n.contains("-$role.") ||
                    n.contains("-$role-")
                supported && roleMatch
            } ?: return null

            return (candidates.firstOrNull { it.name.contains("int8") } ?: candidates.firstOrNull())
                ?.absolutePath
        }
    }
}
