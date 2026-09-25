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
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun requestIfEnabled(context: Context, noteId: String) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("openwhispr", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ENABLED, false)) return
        if (BenchmarkStore.read(appContext, noteId) != null) return

        cancelled.remove(noteId)
        BenchmarkStore.write(appContext, newQueuedSnapshot(noteId))
        notifyChanged(noteId)
    }

    fun runIfRequested(context: Context, noteId: String) {
        val appContext = context.applicationContext
        val snapshot = BenchmarkStore.read(appContext, noteId) ?: return
        if (snapshot.state == BenchmarkRunState.COMPLETE) return
        schedule(appContext, noteId, 0L)
    }

    fun resumeRequested(context: Context, notes: List<Note>) {
        val appContext = context.applicationContext
        notes.forEach { note ->
            val snapshot = BenchmarkStore.read(appContext, note.id) ?: return@forEach
            if (note.transcriptionState == Note.State.PENDING) return@forEach
            if (snapshot.state == BenchmarkRunState.COMPLETE) return@forEach
            schedule(appContext, note.id, 0L)
        }
    }

    fun rerun(context: Context, noteId: String) {
        val appContext = context.applicationContext
        if (scheduled.contains(noteId)) return
        cancelled.remove(noteId)
        BenchmarkStore.write(appContext, newQueuedSnapshot(noteId))
        notifyChanged(noteId)
        schedule(appContext, noteId, 0L)
    }

    fun getSnapshot(context: Context, noteId: String): BenchmarkSnapshot? =
        BenchmarkStore.read(context.applicationContext, noteId)

    fun isRunning(noteId: String? = null): Boolean =
        if (noteId == null) scheduled.isNotEmpty() else scheduled.contains(noteId)

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    /** Cancel any in-flight benchmark and remove its sidecar evidence. */
    fun deleteResults(context: Context, noteId: String) {
        cancelled.add(noteId)
        BenchmarkStore.delete(context.applicationContext, noteId)
        notifyChanged(noteId)
    }

    private fun newQueuedSnapshot(noteId: String) = BenchmarkSnapshot(
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

    private fun schedule(context: Context, noteId: String, delayMs: Long) {
        if (cancelled.contains(noteId)) return
        if (!scheduled.add(noteId)) return
        executor.schedule({ runBenchmark(context, noteId) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun runBenchmark(context: Context, noteId: String) {
        var retryNeeded = false

        try {
            if (cancelled.contains(noteId)) return

            val repo = NotesRepository.getInstance(context)
            val note = repo.getNote(noteId)
            if (note == null) {
                BenchmarkStore.delete(context, noteId)
                return
            }

            // Primary transcription always wins scheduling priority.
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

            val samples = decodeAppWav(audioFile.readBytes())

            /*
             * Hold the same process-wide sherpa lock for the entire benchmark.
             * This prevents the accessibility overlay or primary Voice Note path
             * from reloading a resident model between benchmark models. Existing
             * wrappers remain valid: their native recognizers are evicted here and
             * lazily reload on their next real transcription.
             */
            LocalTranscriber.exclusive {
                LocalTranscriber.releaseIdleNativeMemory()

                if (cancelled.contains(noteId)) return@exclusive

                var snapshot = BenchmarkStore.read(context, noteId) ?: return@exclusive
                snapshot = snapshot.copy(
                    state = BenchmarkRunState.RUNNING,
                    startedAt = snapshot.startedAt ?: System.currentTimeMillis(),
                    finishedAt = null,
                    results = snapshot.results.map { result ->
                        // RUNNING left on disk means the previous process ended while
                        // this model was active. Do not auto-repeat it and crash-loop.
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
                writeIfActive(context, snapshot) ?: return@exclusive

                for (model in MODEL_CATALOG) {
                    if (cancelled.contains(noteId)) return@exclusive
                    if (repo.getNote(noteId) == null) return@exclusive

                    snapshot = BenchmarkStore.read(context, noteId) ?: return@exclusive
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
                        writeIfActive(context, snapshot) ?: return@exclusive
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
                    writeIfActive(context, snapshot) ?: return@exclusive

                    val t0 = System.currentTimeMillis()
                    val result = try {
                        val transcriber = LocalTranscriber.create(context, model.archive)
                            ?: throw IllegalStateException("Unable to load ${model.name}")
                        val text = try {
                            transcriber.transcribe(samples, 16000)
                        } finally {
                            transcriber.close()
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

                    if (cancelled.contains(noteId)) return@exclusive
                    snapshot = replaceModelResult(snapshot, result)
                    writeIfActive(context, snapshot) ?: return@exclusive
                }

                if (cancelled.contains(noteId)) return@exclusive
                snapshot = BenchmarkStore.read(context, noteId) ?: return@exclusive
                writeIfActive(
                    context,
                    snapshot.copy(
                        state = BenchmarkRunState.COMPLETE,
                        finishedAt = System.currentTimeMillis()
                    )
                )

                // Leave resident wrappers alive but native memory cold after testing.
                LocalTranscriber.releaseIdleNativeMemory()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Benchmark failed for note $noteId", e)
            if (!cancelled.contains(noteId)) {
                failWholeRun(context, noteId, e.message ?: "Benchmark failed")
            }
        } finally {
            scheduled.remove(noteId)

            if (retryNeeded && !cancelled.contains(noteId)) {
                schedule(context, noteId, RETRY_DELAY_MS)
            } else if (cancelled.contains(noteId)) {
                BenchmarkStore.delete(context, noteId)
                cancelled.remove(noteId)
            }

            notifyChanged(noteId)
        }
    }

    private fun writeIfActive(
        context: Context,
        snapshot: BenchmarkSnapshot
    ): BenchmarkSnapshot? {
        if (cancelled.contains(snapshot.noteId)) return null
        BenchmarkStore.write(context, snapshot)
        notifyChanged(snapshot.noteId)
        return snapshot
    }

    private fun markQueued(context: Context, noteId: String) {
        if (cancelled.contains(noteId)) return
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
        if (cancelled.contains(noteId)) return
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
        require(dataSize >= 0) { "Invalid WAV data size" }

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
        require(json.optInt("version", -1) == VERSION) {
            "Unsupported benchmark file version"
        }

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
