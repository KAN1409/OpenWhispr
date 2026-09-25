package com.edib.openwhispr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Test-only local-model benchmark. The primary Voice Note transcription remains
 * authoritative; benchmark outputs are stored separately as evidence.
 */
object LocalModelBenchmark {
    const val PREF_ENABLED = "benchmark_local_models"

    private const val TAG = "LocalModelBenchmark"
    private const val RETRY_DELAY_MS = 1500L

    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "local-model-benchmark").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }.apply {
        removeOnCancelPolicy = true
    }

    private val scheduled = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun requestIfEnabled(context: Context, noteId: String) {
        val prefs = context.getSharedPreferences("openwhispr", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ENABLED, false)) return
        if (BenchmarkStore.read(context, noteId) != null) return

        BenchmarkStore.write(
            context,
            BenchmarkSnapshot(
                noteId = noteId,
                state = BenchmarkRunState.QUEUED,
                requestedAt = System.currentTimeMillis(),
                results = MODEL_CATALOG.map { model ->
                    BenchmarkModelResult(
                        archive = model.archive,
                        modelName = model.name
                    )
                }
            )
        )
        notifyChanged(noteId)
    }

    fun runIfRequested(context: Context, noteId: String) {
        val snapshot = BenchmarkStore.read(context, noteId) ?: return
        if (snapshot.state == BenchmarkRunState.COMPLETE) return
        schedule(context.applicationContext, noteId, 0L)
    }

    fun resumeRequested(context: Context, notes: List<Note>) {
        notes.forEach { note ->
            val snapshot = BenchmarkStore.read(context, note.id) ?: return@forEach
            if (note.transcriptionState == Note.State.PENDING) return@forEach
            if (snapshot.state == BenchmarkRunState.COMPLETE) return@forEach
            schedule(context.applicationContext, note.id, 0L)
        }
    }

    fun rerun(context: Context, noteId: String) {
        if (scheduled.contains(noteId)) return
        BenchmarkStore.write(
            context,
            BenchmarkSnapshot(
                noteId = noteId,
                state = BenchmarkRunState.QUEUED,
                requestedAt = System.currentTimeMillis(),
                results = MODEL_CATALOG.map { model ->
                    BenchmarkModelResult(
                        archive = model.archive,
                        modelName = model.name
                    )
                }
            )
        )
        notifyChanged(noteId)
        schedule(context.applicationContext, noteId, 0L)
    }

    fun getSnapshot(context: Context, noteId: String): BenchmarkSnapshot? =
        BenchmarkStore.read(context, noteId)

    fun isRunning(noteId: String? = null): Boolean =
        if (noteId == null) scheduled.isNotEmpty() else scheduled.contains(noteId)

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun deleteResults(context: Context, noteId: String) {
        BenchmarkStore.delete(context, noteId)
        scheduled.remove(noteId)
        notifyChanged(noteId)
    }

    private fun schedule(context: Context, noteId: String, delayMs: Long) {
        if (!scheduled.add(noteId)) return
        executor.schedule({ runBenchmark(context, noteId) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun runBenchmark(context: Context, noteId: String) {
        var overlayPaused = false
        var retryNeeded = false

        try {
            val repo = NotesRepository.getInstance(context)
            val note = repo.getNote(noteId)
            if (note == null) {
                BenchmarkStore.delete(context, noteId)
                return
            }

            // The primary transcript always wins scheduling priority.
            if (note.transcriptionState == Note.State.PENDING) {
                markQueued(context, noteId)
                retryNeeded = true
                return
            }

            val audioFile = File(note.audioPath)
            if (!audioFile.isFile || audioFile.length() <= 44L) {
                failWholeRun(context, noteId, "Recording audio file missing or empty")
                return
            }

            // The accessibility overlay can keep the selected local model resident.
            // Release it first so a benchmark model is never loaded on top of it.
            WhisperAccessibilityService.instance?.let { service ->
                overlayPaused = service.pauseLocalModelForBenchmark()
                if (!overlayPaused) {
                    markQueued(context, noteId)
                    retryNeeded = true
                    return
                }
            }

            var snapshot = BenchmarkStore.read(context, noteId) ?: return
            snapshot = snapshot.copy(
                state = BenchmarkRunState.RUNNING,
                startedAt = snapshot.startedAt ?: System.currentTimeMillis(),
                finishedAt = null,
                results = snapshot.results.map { result ->
                    // A RUNNING result left on disk means the previous process ended
                    // inside that model. Do not auto-repeat it and risk a crash loop.
                    if (result.state == BenchmarkModelState.RUNNING) {
                        result.copy(
                            state = BenchmarkModelState.FAILED,
                            error = "Previous app process ended while this model was running"
                        )
                    } else {
                        result
                    }
                }
            )
            BenchmarkStore.write(context, snapshot)
            notifyChanged(noteId)

            val samples = decodeAppWav(audioFile.readBytes())

            for (model in MODEL_CATALOG) {
                snapshot = BenchmarkStore.read(context, noteId) ?: return
                val previous = snapshot.results.firstOrNull { it.archive == model.archive }
                if (previous?.state == BenchmarkModelState.COMPLETE) continue
                if (previous?.error?.startsWith("Previous app process ended") == true) continue

                if (!ModelDownloader.isInstalled(context, model)) {
                    snapshot = replaceModelResult(
                        snapshot,
                        BenchmarkModelResult(
                            archive = model.archive,
                            modelName = model.name,
                            state = BenchmarkModelState.NOT_INSTALLED
                        )
                    )
                    BenchmarkStore.write(context, snapshot)
                    notifyChanged(noteId)
                    continue
                }

                snapshot = replaceModelResult(
                    snapshot,
                    BenchmarkModelResult(
                        archive = model.archive,
                        modelName = model.name,
                        state = BenchmarkModelState.RUNNING
                    )
                )
                BenchmarkStore.write(context, snapshot)
                notifyChanged(noteId)

                val t0 = System.currentTimeMillis()
                val result = try {
                    val text = LocalTranscriber.exclusive {
                        val transcriber = LocalTranscriber.create(context, model.archive)
                            ?: throw IllegalStateException("Unable to load ${model.name}")
                        try {
                            transcriber.transcribe(samples, 16000)
                        } finally {
                            transcriber.close()
                        }
                    }
                    BenchmarkModelResult(
                        archive = model.archive,
                        modelName = model.name,
                        state = BenchmarkModelState.COMPLETE,
                        transcript = text,
                        elapsedMs = System.currentTimeMillis() - t0
                    )
                } catch (e: LinkageError) {
                    Log.e(TAG, "${model.name} native runtime error", e)
                    BenchmarkModelResult(
                        archive = model.archive,
                        modelName = model.name,
                        state = BenchmarkModelState.FAILED,
                        error = e.message ?: "Native runtime unavailable",
                        elapsedMs = System.currentTimeMillis() - t0
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "${model.name} benchmark failed", e)
                    BenchmarkModelResult(
                        archive = model.archive,
                        modelName = model.name,
                        state = BenchmarkModelState.FAILED,
                        error = e.message ?: "Benchmark failed",
                        elapsedMs = System.currentTimeMillis() - t0
                    )
                }

                snapshot = replaceModelResult(snapshot, result)
                BenchmarkStore.write(context, snapshot)
                notifyChanged(noteId)
            }

            snapshot = BenchmarkStore.read(context, noteId) ?: return
            BenchmarkStore.write(
                context,
                snapshot.copy(
                    state = BenchmarkRunState.COMPLETE,
                    finishedAt = System.currentTimeMillis()
                )
            )
            notifyChanged(noteId)
        } catch (e: Exception) {
            Log.e(TAG, "Benchmark failed for note $noteId", e)
            failWholeRun(context, noteId, e.message ?: "Benchmark failed")
        } finally {
            if (overlayPaused) {
                WhisperAccessibilityService.instance?.restoreLocalModelAfterBenchmark()
            }
            scheduled.remove(noteId)
            if (retryNeeded) {
                schedule(context, noteId, RETRY_DELAY_MS)
            }
            notifyChanged(noteId)
        }
    }

    private fun markQueued(context: Context, noteId: String) {
        val snapshot = BenchmarkStore.read(context, noteId) ?: return
        if (snapshot.state != BenchmarkRunState.COMPLETE) {
            BenchmarkStore.write(context, snapshot.copy(state = BenchmarkRunState.QUEUED))
            notifyChanged(noteId)
        }
    }

    private fun replaceModelResult(
        snapshot: BenchmarkSnapshot,
        replacement: BenchmarkModelResult
    ): BenchmarkSnapshot = snapshot.copy(
        results = snapshot.results.map { result ->
            if (result.archive == replacement.archive) replacement else result
        }
    )

    private fun failWholeRun(context: Context, noteId: String, error: String) {
        val current = BenchmarkStore.read(context, noteId) ?: return
        BenchmarkStore.write(
            context,
            current.copy(
                state = BenchmarkRunState.FAILED,
                finishedAt = System.currentTimeMillis(),
                results = current.results.map { result ->
                    if (
                        result.state == BenchmarkModelState.WAITING ||
                        result.state == BenchmarkModelState.RUNNING
                    ) {
                        result.copy(state = BenchmarkModelState.FAILED, error = error)
                    } else {
                        result
                    }
                }
            )
        )
        notifyChanged(noteId)
    }

    /** Voice Notes created by OpenWispr use canonical 44-byte PCM16 mono 16-kHz WAVs. */
    private fun decodeAppWav(wav: ByteArray): FloatArray {
        require(wav.size > 44) { "WAV is empty" }

        fun u16(offset: Int): Int =
            (wav[offset].toInt() and 0xff) or ((wav[offset + 1].toInt() and 0xff) shl 8)

        fun u32(offset: Int): Int =
            (wav[offset].toInt() and 0xff) or
                ((wav[offset + 1].toInt() and 0xff) shl 8) or
                ((wav[offset + 2].toInt() and 0xff) shl 16) or
                ((wav[offset + 3].toInt() and 0xff) shl 24)

        require(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(wav, 8, 4, Charsets.US_ASCII) == "WAVE")
        require(u16(22) == 1) { "Only mono WAV is supported" }
        require(u32(24) == 16000) { "Expected 16-kHz WAV" }
        require(u16(34) == 16) { "Expected PCM16 WAV" }
        require(String(wav, 36, 4, Charsets.US_ASCII) == "data")

        val dataSize = u32(40).coerceAtMost(wav.size - 44)
        val samples = FloatArray(dataSize / 2)
        for (i in samples.indices) {
            val lo = wav[44 + i * 2].toInt() and 0xff
            val hi = wav[44 + i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
        }
        return samples
    }

    private fun notifyChanged(noteId: String) {
        mainHandler.post {
            listeners.forEach { listener -> listener(noteId) }
        }
    }
}

enum class BenchmarkRunState { QUEUED, RUNNING, COMPLETE, FAILED }
enum class BenchmarkModelState { WAITING, RUNNING, COMPLETE, FAILED, NOT_INSTALLED }

data class BenchmarkModelResult(
    val archive: String,
    val modelName: String,
    val state: BenchmarkModelState = BenchmarkModelState.WAITING,
    val transcript: String = "",
    val error: String? = null,
    val elapsedMs: Long = 0L
)

data class BenchmarkSnapshot(
    val noteId: String,
    val state: BenchmarkRunState,
    val requestedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val results: List<BenchmarkModelResult>
)

private object BenchmarkStore {
    private const val VERSION = 1

    @Synchronized
    fun read(context: Context, noteId: String): BenchmarkSnapshot? {
        val file = resultFile(context, noteId)
        if (!file.isFile) return null
        return try {
            decode(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.e("BenchmarkStore", "Unable to read ${file.name}", e)
            null
        }
    }

    @Synchronized
    fun write(context: Context, snapshot: BenchmarkSnapshot) {
        val target = resultFile(context, snapshot.noteId)
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, "${target.name}.part")
        val bytes = encode(snapshot).toString().toByteArray(Charsets.UTF_8)

        try {
            FileOutputStream(staging).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    staging.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    staging.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            staging.delete()
        }
    }

    @Synchronized
    fun delete(context: Context, noteId: String) {
        val target = resultFile(context, noteId)
        target.delete()
        File(target.parentFile, "${target.name}.part").delete()
    }

    private fun resultFile(context: Context, noteId: String): File =
        File(File(context.filesDir, "notes"), "$noteId.benchmark.json")

    private fun encode(snapshot: BenchmarkSnapshot): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("noteId", snapshot.noteId)
        put("state", snapshot.state.name)
        put("requestedAt", snapshot.requestedAt)
        put("startedAt", snapshot.startedAt ?: JSONObject.NULL)
        put("finishedAt", snapshot.finishedAt ?: JSONObject.NULL)
        put("results", JSONArray().apply {
            snapshot.results.forEach { result ->
                put(JSONObject().apply {
                    put("archive", result.archive)
                    put("modelName", result.modelName)
                    put("state", result.state.name)
                    put("transcript", result.transcript)
                    put("error", result.error ?: JSONObject.NULL)
                    put("elapsedMs", result.elapsedMs)
                })
            }
        })
    }

    private fun decode(json: JSONObject): BenchmarkSnapshot {
        require(json.optInt("version", -1) == VERSION) { "Unsupported benchmark file version" }
        val resultsJson = json.getJSONArray("results")
        val results = ArrayList<BenchmarkModelResult>(resultsJson.length())
        for (i in 0 until resultsJson.length()) {
            val item = resultsJson.getJSONObject(i)
            results += BenchmarkModelResult(
                archive = item.getString("archive"),
                modelName = item.getString("modelName"),
                state = BenchmarkModelState.valueOf(item.getString("state")),
                transcript = item.optString("transcript", ""),
                error = if (item.isNull("error")) null else item.getString("error"),
                elapsedMs = item.optLong("elapsedMs", 0L)
            )
        }
        return BenchmarkSnapshot(
            noteId = json.getString("noteId"),
            state = BenchmarkRunState.valueOf(json.getString("state")),
            requestedAt = json.getLong("requestedAt"),
            startedAt = if (json.isNull("startedAt")) null else json.getLong("startedAt"),
            finishedAt = if (json.isNull("finishedAt")) null else json.getLong("finishedAt"),
            results = results
        )
    }
}
