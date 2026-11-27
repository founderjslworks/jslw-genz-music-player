package com.jslw.genz.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import com.jslw.genz.data.Prefs
import com.jslworks.soundengine.core.JslwAudioBuffer
import com.jslworks.soundengine.dsp.AudioPlusChain
import com.jslworks.soundengine.dsp.BiquadType
import com.jslworks.soundengine.dsp.EqBand
import com.jslworks.soundengine.dsp.ParametricEqualizer
import com.jslworks.soundengine.dsp.SoundPreset
import com.jslworks.soundengine.dsp.SoundPresets
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * JSL Audio+ – GEN-Z's advanced audio, powered by the JSL Audio Engine (proprietary, JSL WORKS PVT LTD).
 *
 * One processing stage, not a stack: an Audio+ song plays on its OWN audio session, so the phone-side
 * 1.8 effects (equalizer, bass boost, 3D, loudness, reverb) never process it a second time. The user's
 * 10-band EQ curve runs inside the engine instead, followed by the preset (EQ tilt → virtual bass →
 * Melody / width → room → limiter). The level is matched to the original song, so Audio+ is never quieter.
 *
 * Design rule (fixes the old "turning the engine on stops the song" problem): Audio+ never adds or removes
 * effects on a track that is already playing. On/off moves the song to a fresh track
 * (MusicService.audioPlusToggled); every other change is a live 50 ms crossfade in the decoder thread.
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

    /** Current output, e.g. "Partho's OnePlus Bullets Wireless Z2 ANC", "Phone speaker". */
    @Volatile var outputName = "Phone speaker"; private set
    @Volatile var bluetooth = false; private set

    // user's graphic EQ (from the 1.8 equalizer screen), applied inside the engine
    @Volatile private var eqFreqs = IntArray(0)
    @Volatile private var eqDb = FloatArray(0)
    @Volatile private var eqOn = false

    /** Route changed or settings changed: re-detect the output, recall its preset, rebuild. */
    fun changed(ctx: Context) {
        detectOutput(ctx)
        recallForDevice()
        generation++
    }

    fun setCompare(on: Boolean) {
        if (compare == on) return
        compare = on
        generation++
    }

    /** Called whenever the 1.8 equalizer changes. */
    fun updateEq(fx: AudioFx?) {
        if (fx == null) return
        eqFreqs = fx.bandFreqs.copyOf()
        eqDb = fx.currentLevels().map { it / 100f }.toFloatArray()
        eqOn = Prefs.eqEnabled
        generation++
    }

    fun mood(): SoundPreset = try { SoundPresets.mood(Prefs.apMood) } catch (_: Exception) { SoundPresets.mood("happy") }

    // ---------------------------------------------------------------- per-device memory
    /** A preset was picked: remember it for the current output (headphones, speaker, car…). */
    fun rememberForDevice() {
        val o = devices()
        o.put(outputName, JSONObject().put("mood", Prefs.apMood).put("room", Prefs.apRoom).put("intensity", Prefs.apIntensity))
        Prefs.apDevices = o.toString()
    }

    private fun recallForDevice() {
        val e = devices().optJSONObject(outputName) ?: return
        Prefs.apMood = e.optString("mood", Prefs.apMood)
        Prefs.apRoom = e.optString("room", Prefs.apRoom)
        Prefs.apIntensity = e.optInt("intensity", Prefs.apIntensity)
    }

    private fun devices(): JSONObject = try { JSONObject(Prefs.apDevices) } catch (_: Exception) { JSONObject() }

    private fun detectOutput(ctx: Context) {
        var name = "Phone speaker"; var bt = false; var spk = true
        if (Build.VERSION.SDK_INT >= 23) {
            try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val pick = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            if (pick != null) {
                spk = false
                bt = pick.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                name = pick.productName?.toString()?.takeIf { it.isNotBlank() } ?: when {
                    bt -> "Bluetooth headphones"
                    pick.type == AudioDeviceInfo.TYPE_USB_HEADSET || pick.type == AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
                    else -> "Wired headphones"
                }
                if (!bt && pick.type != AudioDeviceInfo.TYPE_USB_HEADSET && pick.type != AudioDeviceInfo.TYPE_USB_DEVICE) name = "Wired headphones"
            }
            } catch (_: Throwable) {}
        } else {
            spk = try { SoundEngine.detect(ctx) == SoundEngine.Output.SPEAKER } catch (_: Throwable) { false }
        }
        outputName = name; bluetooth = bt; speaker = spk
    }

    /** Phone-maker sound effects installed on this phone (Dolby Atmos, Dirac, DTS…), if any. */
    fun phoneEffects(): String? = try {
        val known = listOf("dolby" to "Dolby Atmos", "dirac" to "Dirac", "dts" to "DTS", "soundalive" to "SoundAlive",
            "realsound" to "Real Sound", "mi sound" to "Mi Sound", "audiowizard" to "AudioWizard", "maxxaudio" to "MaxxAudio")
        val names = AudioEffect.queryEffects()?.map { (it.name + " " + it.implementor).lowercase() }.orEmpty()
        known.firstOrNull { (k, _) -> names.any { n -> n.replace(" ", "").contains(k.replace(" ", "")) } }?.second
    } catch (_: Throwable) { null }

    // ---------------------------------------------------------------- chain
    /** Builds the processing graph for the current settings (allocates – decoder thread only). */
    fun build(rate: Int, withUserEq: Boolean = true): Graph {
        val m = mood()
        val k = Prefs.apIntensity.coerceIn(0, 150) / 100.0
        val roomId: String?
        val roomWet: Double
        when (val r = Prefs.apRoom) {
            "mood" -> { roomId = m.roomId; roomWet = m.roomWet }
            "off" -> { roomId = null; roomWet = 0.0 }
            else -> {
                roomId = SoundPresets.ROOMS.firstOrNull { it.id == r }?.id
                roomWet = roomId?.let { m.roomWet.takeIf { w -> w > 0 } ?: 0.15 } ?: 0.0
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
            // phone speakers can't move air below ~150 Hz: keep the harmonics, drop the energy they can't play
            c.bass.setCutoff(150.0)
            c.bass.removeSubBass = p.bassBoost > 0
        }

        // the user's 10-band curve, inside the engine (graphic-EQ bands: one octave wide, shelves at the ends)
        var user: ParametricEqualizer? = null
        val f = eqFreqs; val g = eqDb
        if (withUserEq && eqOn && f.isNotEmpty() && f.size == g.size && g.any { it != 0f }) {
            val eq = ParametricEqualizer(2, rate)
            val n = minOf(f.size, ParametricEqualizer.MAX_BANDS)
            for (i in 0 until n) {
                val type = when (i) { 0 -> BiquadType.LowShelf; n - 1 -> BiquadType.HighShelf; else -> BiquadType.Peaking }
                eq.setBand(i, EqBand(type, f[i].toDouble(), g[i].toDouble(), if (type == BiquadType.Peaking) 1.41 else 0.707))
            }
            // half the largest boost as headroom; level matching restores loudness, the limiter catches peaks
            eq.setPreampDb(-0.5 * (g.maxOrNull() ?: 0f).coerceAtLeast(0f))
            user = eq
        }
        return Graph(c, user)
    }

    class Graph(val chain: AudioPlusChain, private val userEq: ParametricEqualizer?) {
        fun process(b: JslwAudioBuffer) { userEq?.process(b); chain.process(b) }
    }
}

/**
 * Runs Audio+ on interleaved stereo float PCM inside a decoder thread. Settings changes crossfade over
 * 50 ms; level matching keeps Audio+ as loud as the original (never more than +9 dB of make-up).
 * When Audio+ is off it costs nothing.
 */
/** @param withUserEq false when the song still plays on GEN-Z's main session (the phone EQ already applies). */
class AudioPlusProcessor(private val rate: Int, private val withUserEq: Boolean = true) {
    private val fadeLen = (rate / 20).coerceAtLeast(1)
    private var gen = -1
    private var cur: AudioPlus.Graph? = null
    private var prev: AudioPlus.Graph? = null
    private var fadeLeft = 0
    private var a: JslwAudioBuffer? = null
    private var b: JslwAudioBuffer? = null

    // level matching: slow running powers of the original and the processed sound
    private var pIn = 0.0
    private var pOut = 0.0
    private var makeup = 1f
    private val avg = 0.02 // per block ≈ 1–2 s memory

    fun process(data: FloatArray, frames: Int, channels: Int) {
        if (channels != 2 || frames <= 0) return
        val g = AudioPlus.generation
        if (g != gen) {
            gen = g
            prev = cur
            cur = if (AudioPlus.active && !AudioPlus.compare) {
                try { AudioPlus.build(rate, withUserEq) } catch (_: Throwable) { null }
            } else null
            fadeLeft = if (prev != null || cur != null) fadeLen else 0
        }
        val c = cur
        if (c == null && fadeLeft == 0) return

        var x = a
        if (x == null || x.frameCount != frames) { x = JslwAudioBuffer(2, frames, rate); a = x; b = JslwAudioBuffer(2, frames, rate) }
        val y = b!!
        val xl = x.channel(0); val xr = x.channel(1)
        var sIn = 0.0
        for (i in 0 until frames) {
            val l = data[2 * i]; val r = data[2 * i + 1]
            xl[i] = l; xr[i] = r
            sIn += (l * l + r * r).toDouble()
        }
        val fading = fadeLeft > 0
        if (fading) { xl.copyInto(y.channel(0)); xr.copyInto(y.channel(1)) }

        if (c != null) {
            // make-up gain (moves slowly, ~5 % per block, so it never pumps)
            val target = makeup
            if (target != 1f) for (i in 0 until frames) { xl[i] *= target; xr[i] *= target }
            c.process(x)
            var sOut = 0.0
            for (i in 0 until frames) sOut += (xl[i] * xl[i] + xr[i] * xr[i]).toDouble()
            val blockIn = sIn / frames; val blockOut = sOut / frames
            if (blockIn > 1e-7) {   // ignore silence
                pIn += (blockIn - pIn) * avg
                pOut += (blockOut - pOut) * avg
                if (pOut > 1e-9) {
                    val want = (makeup * sqrt(pIn / pOut)).toFloat().coerceIn(0.5f, 2.8f)
                    makeup += (want - makeup) * 0.05f
                }
            }
        }
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
