package com.screensaathi.sarvam

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Plays WAV bytes from Bulbul via MediaPlayer, writing to a temp file first
 * (MediaPlayer needs a source, not a byte array). onDone fires when playback
 * finishes or fails, so the caller can flip the pill out of the speaking state.
 */
class AudioPlayer internal constructor(
    private val clip: File,
    private val createPlayback: () -> AudioPlayback,
) {
    constructor(context: Context) : this(File(context.cacheDir, "saathi_tts.wav"), ::AndroidAudioPlayback)

    private val lock = Any()
    private var player: AudioPlayback? = null

    fun play(wavBytes: ByteArray, onStart: () -> Unit, onDone: () -> Unit,
        withCurrent: (() -> Unit) -> Unit = { it() },
    ) {
        var installed: AudioPlayback? = null
        try {
            // Acquire the session/turn lease before our lock, including on the
            // prepared boundary below. Stop never acquires the session lease.
            withCurrent {
                synchronized(lock) {
                    stopLocked()
                    val mp = createPlayback()
                    installed = mp
                    player = mp
                    clip.writeBytes(wavBytes)
                }
            }
            val mp = installed ?: return
            mp.prepare(clip.absolutePath,
                onPrepared = { startPrepared(mp, withCurrent, onStart, onDone) },
                onDone = { if (stopIfOwned(mp)) onDone() },
            )
        } catch (_: Exception) {
            Log.w(TAG, "playback preparation failed")
            val mp = installed
            if (mp == null) withCurrent(onDone)
            else if (stopIfOwned(mp)) onDone()
        }
    }

    private fun startPrepared(
        mp: AudioPlayback,
        withCurrent: (() -> Unit) -> Unit,
        onStart: () -> Unit,
        onDone: () -> Unit,
    ) {
        var started = false
        try {
            withCurrent {
                synchronized(lock) {
                    if (player === mp) {
                        // Start is inside both the freshness lease and the
                        // playback lock. Stop cannot complete in a check/start gap.
                        mp.start()
                        started = true
                        onStart()
                    }
                }
            }
        } catch (_: Exception) {
            Log.w(TAG, "playback start failed")
            if (stopIfOwned(mp)) onDone()
        } finally {
            if (!started) stopIfOwned(mp)
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopIfOwned(mp: AudioPlayback): Boolean = synchronized(lock) {
        if (player !== mp) return@synchronized false
        stopLocked()
        true
    }

    private fun stopLocked() {
        val previous = player
        player = null
        runCatching { previous?.release() }
        // The input WAV (WavRecorder) is deleted right after its STT call;
        // this one wasn't, so a spoken clip sat in cache indefinitely between
        // turns. Same rule should apply to both temp files.
        runCatching { clip.delete() }
    }

    companion object { private const val TAG = "AudioPlayer" }
}

/** Narrow async media seam; ownership and start/stop policy live in AudioPlayer. */
internal interface AudioPlayback {
    fun prepare(path: String, onPrepared: () -> Unit, onDone: () -> Unit)
    fun start()
    fun release()
}

private class AndroidAudioPlayback : AudioPlayback {
    private val player = MediaPlayer()
    override fun prepare(path: String, onPrepared: () -> Unit, onDone: () -> Unit) {
        player.setDataSource(path)
        player.setOnPreparedListener { onPrepared() }
        player.setOnCompletionListener { onDone() }
        player.setOnErrorListener { _, what, extra ->
            Log.w("AudioPlayer", "MediaPlayer error $what/$extra")
            onDone()
            true
        }
        player.prepareAsync()
    }
    override fun start() = player.start()
    override fun release() = player.release()
}
