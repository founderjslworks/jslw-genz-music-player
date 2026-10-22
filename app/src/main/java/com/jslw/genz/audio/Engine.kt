package com.jslw.genz.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.PowerManager
import com.jslw.genz.model.Song

/** One decoder/output chain. The service may run two at once (crossfade / gapless). */
interface Engine {
    val sessionId: Int
    var onCompletion: (() -> Unit)?
    var onError: ((String) -> Unit)?

    /** Synchronous prepare of a local file. @return false if the format can't be decoded. */
    fun prepare(ctx: Context, song: Song): Boolean
    fun start()
    fun pause()
    val isPlaying: Boolean
    fun position(): Long
    fun duration(): Long
    fun seekTo(ms: Long)
    fun setVolume(v: Float)
    /** Android 6+ only for the system decoder. @return true if applied. */
    fun setSpeedPitch(speed: Float, pitch: Float): Boolean
    fun attachAux(effectId: Int, level: Float)
    /** True gapless hand-off (MediaPlayer → MediaPlayer). */
    fun setNext(next: Engine?): Boolean
    fun release()

    companion object {
        fun create(ctx: Context, song: Song, sessionId: Int): Engine =
            if (song.needsPcmEngine) PcmEngine(ctx, sessionId) else MediaPlayerEngine(ctx, sessionId)

        fun musicAttributes(): AudioAttributes? = if (Build.VERSION.SDK_INT >= 21)
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        else null
    }
}

/** Android's built-in decoder: MP3, AAC, FLAC, WAV, OGG, Opus, MIDI, ALAC (most devices), AMR… */
class MediaPlayerEngine(ctx: Context, override val sessionId: Int) : Engine {
    val player = MediaPlayer()
    override var onCompletion: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    private var prepared = false
    private var speed = 1f
    private var pitch = 1f

    init {
        if (Build.VERSION.SDK_INT >= 21) {
            player.setAudioAttributes(Engine.musicAttributes()!!)
        } else {
            @Suppress("DEPRECATION")
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
        }
        try { player.audioSessionId = sessionId } catch (_: Exception) {}
        player.setWakeMode(ctx.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        player.setOnCompletionListener { onCompletion?.invoke() }
        player.setOnErrorListener { _, what, extra ->
            onError?.invoke("Playback error ($what, $extra)")
            true
        }
    }

    override fun prepare(ctx: Context, song: Song): Boolean = try {
        player.setDataSource(ctx, song.uri)
        player.prepare()
        prepared = true
        true
    } catch (e: Exception) {
        false
    }

    override fun start() {
        if (!prepared) return
        player.start()
        applyParams()
    }

    override fun pause() { if (prepared && player.isPlaying) player.pause() }
    override val isPlaying: Boolean get() = prepared && try { player.isPlaying } catch (e: Exception) { false }
    override fun position(): Long = if (prepared) try { player.currentPosition.toLong() } catch (e: Exception) { 0 } else 0
    override fun duration(): Long = if (prepared) try { player.duration.toLong().coerceAtLeast(0) } catch (e: Exception) { 0 } else 0
    override fun seekTo(ms: Long) { if (prepared) player.seekTo(ms.toInt()) }
    override fun setVolume(v: Float) { try { player.setVolume(v, v) } catch (_: Exception) {} }

    override fun setSpeedPitch(speed: Float, pitch: Float): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        this.speed = speed; this.pitch = pitch
        if (isPlaying) applyParams()
        return true
    }

    // Setting PlaybackParams on a paused player would start it, so we only apply while playing.
    private fun applyParams() {
        if (Build.VERSION.SDK_INT >= 23 && (speed != 1f || pitch != 1f || player.playbackParams.speed != 1f)) {
            try { player.playbackParams = PlaybackParams().setSpeed(speed).setPitch(pitch) } catch (_: Exception) {}
        }
    }

    override fun attachAux(effectId: Int, level: Float) {
        try {
            player.attachAuxEffect(effectId)
            player.setAuxEffectSendLevel(level)
        } catch (_: Exception) {}
    }

    override fun setNext(next: Engine?): Boolean = try {
        player.setNextMediaPlayer((next as? MediaPlayerEngine)?.player)
        next == null || next is MediaPlayerEngine
    } catch (e: Exception) { false }

    override fun release() {
        prepared = false
        try { player.setNextMediaPlayer(null) } catch (_: Exception) {}
        try { player.reset() } catch (_: Exception) {}
        player.release()
    }
}
