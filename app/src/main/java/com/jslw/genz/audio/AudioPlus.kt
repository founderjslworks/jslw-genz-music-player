package com.jslw.genz.audio

import android.content.Context
import android.os.Build
import com.jslw.genz.data.Prefs
import com.jslworks.soundengine.core.JslwAudioBuffer
import com.jslworks.soundengine.dsp.AudioPlusChain
import com.jslworks.soundengine.dsp.SoundPreset
import com.jslworks.soundengine.dsp.SoundPresets

/**
 * JSL Audio+ – GEN-Z's advanced audio, powered by the JSL Audio Engine (proprietary, JSL WORKS PVT LTD).
 *
 * GEN-Z decodes the song itself and runs it through the engine's chain (EQ tilt → virtual bass →
 * Melody / stereo width → physical room model → look-ahead limiter) before the sound leaves the phone.
 * The 1.8 equalizer keeps working on top, unchanged.
 *
 * Design rule (fixes the old "turning the engine on stops the song" problem): Audio+ never adds or removes
 * effects on a track that is already playing. Turning it on/off moves the song to a fresh track
 * (MusicService.audioPlusToggled), and every other change (mood, room, intensity, Compare) is a live,
 * 50 ms crossfade inside the decoder thread – the output never stops.
 */
object AudioPlus {
    /** Needs float PCM output (Android 5.0+). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 21

    /** Audio+ processes the music right now. */
    val active: Boolean get() = supported && Prefs.apOn && !Prefs.bitPerfect

    /** Bumped on every settings change; decoder threads rebuild their chain when it moves. */
    @Volatile var generation = 0; private set

    /** While true the original sound is heard (Compare button). */
    @Volatile var compare = false; private set

    /** Output is the phone's own speaker (protect it from deep bass, narrower width). */
    @Volatile var speaker = false; private set

    fun changed(ctx: Context) {
        speaker = try { SoundEngine.detect(ctx) == SoundEngine.Output.SPEAKER } catch (_: Throwable) { false }
        generation++
    }

    fun setCompare(on: Boolean) {
        if (compare == on) return
        compare = on
        generation++
    }

    fun mood(): SoundPreset = try { SoundPresets.mood(Prefs.apMood) } catch (_: Exception) { SoundPresets.mood("happy") }

    /** Builds the chain for the current settings. Allocates – call from the decoder thread, not the UI. */
    fun buildChain(rate: Int): AudioPlusChain {
        val m = mood()
        val k = Prefs.apIntensity.coerceIn(0, 150) / 100.0
        val roomId: String?
        val roomWet: Double
        when (val r = Prefs.apRoom) {
            "mood" -> { roomId = m.roomId; roomWet = m.roomWet }
            "off" -> { roomId = null; roomWet = 0.0 }
            else -> {
                roomId = SoundPresets.ROOMS.firstOrNull { it.id == r }?.id
                roomWet = roomId?.let { SoundPresets.room(it).wet } ?: 0.0
            }
        }
        val widthScale = if (speaker) 0.5 else 1.0
        val p = SoundPreset(
            m.id, m.name, m.description,
            lowShelfDb = m.lowShelfDb * k,
            highShelfDb = m.highShelfDb * k,
            bassBoost = m.bassBoost * k,
            width = 1.0 + (m.width - 1.0) * k * widthScale,
            melody = (m.melody * k).coerceAtMost(1.0),
            roomId = roomId,
            roomWet = roomWet * k,
        )
        val c = AudioPlusChain(rate)
        c.applyPreset(p)
        if (speaker) {
            // Phone speakers can't move air below ~150 Hz: keep the harmonics, drop the energy it can't play.
            c.bass.setCutoff(150.0)
            c.bass.removeSubBass = p.bassBoost > 0
        }
        return c
    }
}

/**
 * Runs Audio+ on interleaved stereo float PCM inside a decoder thread. Settings changes crossfade
 * over 50 ms, so the sound never cuts out. When Audio+ is off it costs nothing.
 */
class AudioPlusProcessor(private val rate: Int) {
    private val fadeLen = (rate / 20).coerceAtLeast(1)
    private var gen = -1
    private var cur: AudioPlusChain? = null
    private var prev: AudioPlusChain? = null
    private var fadeLeft = 0
    private var a: JslwAudioBuffer? = null
    private var b: JslwAudioBuffer? = null

    /** Processes `frames` frames of interleaved stereo in place. */
    fun process(data: FloatArray, frames: Int, channels: Int) {
        if (channels != 2 || frames <= 0) return
        val g = AudioPlus.generation
        if (g != gen) {
            gen = g
            prev = cur
            cur = if (AudioPlus.active && !AudioPlus.compare) {
                try { AudioPlus.buildChain(rate) } catch (_: Throwable) { null }
            } else null
            fadeLeft = if (prev != null || cur != null) fadeLen else 0
            if (prev == null && cur == null) fadeLeft = 0
        }
        val c = cur
        if (c == null && fadeLeft == 0) return

        var x = a
        if (x == null || x.frameCount != frames) { x = JslwAudioBuffer(2, frames, rate); a = x; b = JslwAudioBuffer(2, frames, rate) }
        val y = b!!
        val xl = x.channel(0); val xr = x.channel(1)
        for (i in 0 until frames) { xl[i] = data[2 * i]; xr[i] = data[2 * i + 1] }
        val fading = fadeLeft > 0
        if (fading) { xl.copyInto(y.channel(0)); xr.copyInto(y.channel(1)) }

        c?.process(x)
        if (fading) {
            prev?.process(y)
            val yl = y.channel(0); val yr = y.channel(1)
            val done = fadeLen - fadeLeft
            for (i in 0 until frames) {
                val t = ((done + i + 1).toFloat() / fadeLen).coerceAtMost(1f)
                xl[i] = yl[i] + (xl[i] - yl[i]) * t
                xr[i] = yr[i] + (xr[i] - yr[i]) * t
            }
            fadeLeft = (fadeLeft - frames).coerceAtLeast(0)
            if (fadeLeft == 0) prev = null
        }
        for (i in 0 until frames) { data[2 * i] = xl[i]; data[2 * i + 1] = xr[i] }
    }
}
