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
import com.jslworks.soundengine.dsp.MoodClassifier
import com.jslworks.soundengine.dsp.MoodTag
import com.jslworks.soundengine.dsp.ParametricEqualizer
import com.jslworks.soundengine.dsp.SoundPreset
import com.jslworks.soundengine.dsp.SoundPresets
import com.jslworks.soundengine.dsp.StageProcessor
import com.jslworks.soundengine.dsp.TrackAnalyzer
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * JSL Audio+ – GEN-Z's advanced audio, powered by the JSL Audio Engine (proprietary, JSL WORKS PVT LTD).
 *
 * One processing stage, not a stack: an Audio+ song plays on its OWN audio session, so the phone-side
 * 1.8 effects never process it a second time. Order: your EQ curve → Stage / Separation / Instruments →
 * preset (tone tilt → virtual bass → Melody / width → room → limiter). The level is matched to the original.
 *
 * "Auto" listens to the first 15 s of each song (tempo, tonal balance, loudness), tags it with a mood and a
 * gentle tone correction, remembers the tag, and crossfades to it – next time the song starts tagged.
 *
 * Design rule (fixes the old "turning the engine on stops the song" problem): Audio+ never adds or removes
 * effects on a track that is already playing. On/off moves the song to a fresh track; every other change is
 * a live 50 ms crossfade in the decoder thread.
 */
object AudioPlus {
    const val AUTO = "auto"
    const val KIND_HEADPHONES = "headphones"
    const val KIND_SPEAKER = "speaker"
    const val KIND_CAR = "car"

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

    /** What the current output is (user's choice, remembered per device): headphones, speaker or car. */
    @Volatile var kind = KIND_HEADPHONES; private set

    /** Auto: the song playing now and its tag (null while listening). */
    @Volatile var nowSongId = -1L
    @Volatile var nowTag: MoodTag? = null

    // user's graphic EQ (from the equalizer screen), applied inside the engine
    @Volatile private var eqFreqs = IntArray(0)
    @Volatile private var eqDb = FloatArray(0)
    @Volatile private var eqOn = false

    fun changed(ctx: Context) {
        detectOutput(ctx)
        recallForDevice()
        generation++
    }

    /** Settings changed in the app (no route change). */
    fun bump() { generation++ }

    fun setCompare(on: Boolean) {
        if (compare == on) return
        compare = on
        generation++
    }

    /** Called whenever the equalizer changes (band drag, preset, on/off). */
    fun updateEq(fx: AudioFx?) {
        if (fx == null) return
        eqFreqs = fx.bandFreqs.copyOf()
        eqDb = fx.currentLevels().map { it / 100f }.toFloatArray()
        eqOn = Prefs.eqEnabled
        generation++
    }

    fun mood(id: String = Prefs.apMood): SoundPreset =
        try { SoundPresets.mood(if (id == AUTO) "balanced" else id) } catch (_: Exception) { SoundPresets.mood("happy") }

    // ---------------------------------------------------------------- per-device memory
    fun rememberForDevice() {
        val o = devices()
        o.put(outputName, JSONObject().put("mood", Prefs.apMood).put("room", Prefs.apRoom).put("intensity", Prefs.apIntensity)
            .put("stage", Prefs.apStage).put("sep", Prefs.apSep).put("inst", Prefs.apInst).put("kind", kind))
        Prefs.apDevices = o.toString()
    }

    fun setKind(k: String) { kind = k; rememberForDevice(); generation++ }

    private fun recallForDevice() {
        val e = devices().optJSONObject(outputName)
        kind = e?.optString("kind", "")?.takeIf { it.isNotEmpty() } ?: if (speaker) KIND_SPEAKER else KIND_HEADPHONES
        if (e == null) return
        Prefs.apMood = e.optString("mood", Prefs.apMood)
        Prefs.apRoom = e.optString("room", Prefs.apRoom)
        Prefs.apIntensity = e.optInt("intensity", Prefs.apIntensity)
        Prefs.apStage = e.optInt("stage", Prefs.apStage)
        Prefs.apSep = e.optInt("sep", Prefs.apSep)
        Prefs.apInst = e.optInt("inst", Prefs.apInst)
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
                    val usb = pick.type == AudioDeviceInfo.TYPE_USB_HEADSET || pick.type == AudioDeviceInfo.TYPE_USB_DEVICE
                    name = when {
                        bt -> pick.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth device"
                        usb -> pick.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB audio"
                        else -> "Wired headphones"
                    }
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
            "realsound" to "Real Sound", "misound" to "Mi Sound", "audiowizard" to "AudioWizard", "maxxaudio" to "MaxxAudio")
        val names = AudioEffect.queryEffects()?.map { (it.name + it.implementor).lowercase().replace(" ", "") }.orEmpty()
        known.firstOrNull { (k, _) -> names.any { n -> n.contains(k) } }?.second
    } catch (_: Throwable) { null }

    // ---------------------------------------------------------------- auto tags (remembered per song)
    private const val MAX_TAGS = 3000

    fun cachedTag(songId: Long): MoodTag? = try {
        JSONObject(Prefs.apTags).optJSONObject(songId.toString())?.let {
            MoodTag(it.getString("m"), it.optString("t", "balanced"), it.optDouble("l", 0.0), it.optDouble("h", 0.0), it.optDouble("b", 0.0))
        }
    } catch (_: Exception) { null }

    @Synchronized fun storeTag(songId: Long, t: MoodTag) {
        val o = try { JSONObject(Prefs.apTags) } catch (_: Exception) { JSONObject() }
        if (o.length() >= MAX_TAGS) o.keys().asSequence().take(MAX_TAGS / 10).toList().forEach { o.remove(it) }
        o.put(songId.toString(), JSONObject().put("m", t.moodId).put("t", t.tone).put("l", t.lowShelfAdjustDb).put("h", t.highShelfAdjustDb).put("b", t.bpm))
        Prefs.apTags = o.toString()
    }

    // ---------------------------------------------------------------- chain
    /** Builds the processing graph for the current settings (allocates – decoder thread only). */
    fun build(rate: Int, withUserEq: Boolean, tag: MoodTag?): Graph {
        val auto = Prefs.apMood == AUTO
        val m = if (auto) mood(tag?.moodId ?: "balanced") else mood()
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
        val extSpeaker = !speaker && kind == KIND_SPEAKER
        var low = m.lowShelfDb * k + (if (auto) tag?.lowShelfAdjustDb ?: 0.0 else 0.0)
        val high = m.highShelfDb * k + (if (auto) tag?.highShelfAdjustDb ?: 0.0 else 0.0)
        var bass = m.bassBoost * k
        if (extSpeaker || kind == KIND_CAR) {
            // a speaker / car already has real bass: lift it gently so it never booms or pumps
            low = low.coerceAtMost(2.0); bass *= 0.4
        }
        if (auto && tag?.tone == "bass-heavy") bass *= 0.3
        val widthScale = if (speaker) 0.5 else if (extSpeaker) 0.8 else 1.0
        val p = SoundPreset(
            m.id, m.name, m.description,
            lowShelfDb = low, highShelfDb = high, bassBoost = bass,
            width = 1.0 + (m.width - 1.0) * k * widthScale,
            melody = (m.melody * k).coerceAtMost(1.0),
            roomId = roomId, roomWet = roomWet * k,
        )
        val c = AudioPlusChain(rate)
        c.applyPreset(p)
        if (speaker) {
            c.bass.setCutoff(150.0)
            c.bass.removeSubBass = p.bassBoost > 0
        }

        val stage = StageProcessor(rate).apply {
            this.stage = Prefs.apStage / 100.0 * (if (speaker) 0.5 else 1.0)
            separation = Prefs.apSep / 100.0
            instruments = Prefs.apInst / 100.0
        }.takeIf { it.isActive }

        var user: ParametricEqualizer? = null
        val f = eqFreqs; val g = eqDb
        if (withUserEq && eqOn && f.isNotEmpty() && f.size == g.size && g.any { it != 0f }) {
            val eq = ParametricEqualizer(2, rate)
            val n = minOf(f.size, ParametricEqualizer.MAX_BANDS)
            for (i in 0 until n) {
                val type = when (i) { 0 -> BiquadType.LowShelf; n - 1 -> BiquadType.HighShelf; else -> BiquadType.Peaking }
                eq.setBand(i, EqBand(type, f[i].toDouble(), g[i].toDouble(), if (type == BiquadType.Peaking) 1.41 else 0.707))
            }
            eq.setPreampDb(-0.5 * (g.maxOrNull() ?: 0f).coerceAtLeast(0f))
            user = eq
        }
        return Graph(c, user, stage)
    }

    class Graph(val chain: AudioPlusChain, private val userEq: ParametricEqualizer?, private val stage: StageProcessor?) {
        fun process(b: JslwAudioBuffer) { userEq?.process(b); stage?.process(b); chain.process(b) }
    }
}

/**
 * Runs Audio+ on interleaved stereo float PCM inside a decoder thread: 50 ms crossfades on every change,
 * level matching (never quieter than the original, at most +4 dB, and it backs off while the limiter works so
 * bass-heavy songs never pump), and Auto tagging of the song.
 */
class AudioPlusProcessor(private val rate: Int, private val withUserEq: Boolean = true, private val songId: Long = -1L) {
    private val fadeLen = (rate / 20).coerceAtLeast(1)
    private var gen = -1
    private var cur: AudioPlus.Graph? = null
    private var prev: AudioPlus.Graph? = null
    private var fadeLeft = 0
    private var a: JslwAudioBuffer? = null
    private var b: JslwAudioBuffer? = null

    private var pIn = 0.0
    private var pOut = 0.0
    private var makeup = 1f
    private val avg = 0.02

    private var tag: MoodTag? = if (songId >= 0) AudioPlus.cachedTag(songId) else null
    private var analyzer: TrackAnalyzer? = null

    init {
        if (songId >= 0) { AudioPlus.nowSongId = songId; AudioPlus.nowTag = tag }
    }

    fun process(data: FloatArray, frames: Int, channels: Int) {
        if (channels != 2 || frames <= 0) return
        val g = AudioPlus.generation
        if (g != gen) {
            gen = g
            prev = cur
            cur = if (AudioPlus.active && !AudioPlus.compare) {
                try { AudioPlus.build(rate, withUserEq, tag) } catch (_: Throwable) { null }
            } else null
            fadeLeft = if (prev != null || cur != null) fadeLen else 0
        }
        val c = cur
        val needTag = songId >= 0 && tag == null && AudioPlus.active && Prefs.apMood == AudioPlus.AUTO
        if (c == null && fadeLeft == 0 && !needTag) return

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

        if (needTag) {
            val an = analyzer ?: TrackAnalyzer(rate, ANALYZE_SECONDS).also { analyzer = it }
            an.process(x)
            if (an.isFull) {
                val t = MoodClassifier.classify(an.features())
                tag = t
                AudioPlus.storeTag(songId, t)
                AudioPlus.nowTag = t
                analyzer = null
                AudioPlus.bump()   // rebuild with the tag on the next block (crossfaded)
            }
            if (c == null && fadeLeft == 0) return
        }

        val fading = fadeLeft > 0
        if (fading) { xl.copyInto(y.channel(0)); xr.copyInto(y.channel(1)) }

        if (c != null) {
            val mk = makeup
            if (mk != 1f) for (i in 0 until frames) { xl[i] *= mk; xr[i] *= mk }
            c.process(x)
            var sOut = 0.0
            for (i in 0 until frames) sOut += (xl[i] * xl[i] + xr[i] * xr[i]).toDouble()
            val blockIn = sIn / frames; val blockOut = sOut / frames
            if (blockIn > 1e-7) {
                pIn += (blockIn - pIn) * avg
                pOut += (blockOut - pOut) * avg
                if (c.chain.limiter.gainReductionDb < -1.5) {
                    makeup = (makeup * 0.98f).coerceAtLeast(1f)          // yield to the limiter: no pumping
                } else if (pOut > 1e-9) {
                    val want = (makeup * sqrt(pIn / pOut)).toFloat().coerceIn(0.7f, MAX_MAKEUP)
                    makeup += (want - makeup) * 0.03f
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

    companion object {
        private const val ANALYZE_SECONDS = 15.0
        private const val MAX_MAKEUP = 1.58f   // +4 dB
    }
}
