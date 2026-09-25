package com.edib.openwhispr

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Lightweight native audio player for voice notes.
 * Works completely offline with no external dependencies.
 */
class NoteAudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var currentPath: String? = null
    private var currentSpeed: Float = 1.0f
    private val handler: Handler? by lazy {
        try {
            val looper = Looper.getMainLooper()
            if (looper != null) Handler(looper) else null
        } catch (_: Exception) {
            null
        }
    }
    private var progressCallback: ((currentMs: Int, totalMs: Int) -> Unit)? = null
    private var completionCallback: (() -> Unit)? = null

    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null && player.isPlaying) {
                try {
                    val current = player.currentPosition
                    val total = player.duration
                    progressCallback?.invoke(current, total)
                    handler?.postDelayed(this, 100)
                } catch (_: Exception) {}
            }
        }
    }

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    fun play(
        audioPath: String,
        speed: Float = currentSpeed,
        onProgress: (currentMs: Int, totalMs: Int) -> Unit,
        onCompletion: () -> Unit,
        onError: (String) -> Unit
    ) {
        val file = File(audioPath)
        if (!file.exists() || file.length() == 0L) {
            onError("Recording file unavailable")
            return
        }

        stop()
        progressCallback = onProgress
        completionCallback = onCompletion
        currentPath = audioPath
        currentSpeed = speed

        try {
            val player = MediaPlayer().apply {
                setDataSource(audioPath)
                setOnPreparedListener { mp ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentSpeed != 1.0f) {
                        try {
                            mp.playbackParams = PlaybackParams().apply { this.speed = currentSpeed }
                        } catch (_: Exception) {}
                    }
                    mp.start()
                    progressCallback?.invoke(0, mp.duration)
                    handler?.post(progressUpdater)
                }
                setOnCompletionListener {
                    handler?.removeCallbacks(progressUpdater)
                    progressCallback?.invoke(it.duration, it.duration)
                    completionCallback?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("NoteAudioPlayer", "Playback error: what=$what extra=$extra")
                    handler?.removeCallbacks(progressUpdater)
                    onError("Playback error")
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e("NoteAudioPlayer", "Failed to initialize player", e)
            onError(e.message ?: "Failed to play audio")
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    handler?.removeCallbacks(progressUpdater)
                }
            }
        } catch (_: Exception) {}
    }

    fun resume() {
        try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                    handler?.post(progressUpdater)
                }
            }
        } catch (_: Exception) {}
    }

    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer?.seekTo(positionMs)
        } catch (_: Exception) {}
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mediaPlayer?.let {
                    val wasPlaying = it.isPlaying
                    it.playbackParams = PlaybackParams().apply { this.speed = speed }
                    if (!wasPlaying) {
                        it.pause()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("NoteAudioPlayer", "Could not set playback speed", e)
        }
    }

    fun stop() {
        handler?.removeCallbacks(progressUpdater)
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        currentPath = null
    }

    fun release() {
        stop()
        progressCallback = null
        completionCallback = null
    }
}
