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
import com.jslworks.soundengine.dsp.SoundFitEqualizer
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
 * JSLW Audio+ – GEN-Z's advanced audio, powered by the JSLW Audio Engine (proprietary, JSL WORKS PVT LTD).
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
    const val KIND_SOUNDBAR = "soundbar"

    /** Output is a TV / HDMI / ARC connection, most likely a soundbar or home theatre. */
    @Volatile var hdmi = false; private set

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

    val bandFreqs: IntArray get() = eqFreqs
    val intensity: Double get() = Prefs.apIntensity.coerceIn(0, 150) / 100.0

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
            .put("stage", Prefs.apStage).put("sep", Prefs.apSep).put("inst", Prefs.apInst).put("kind", kind)
            .put("modesAuto", Prefs.apModesAuto))
        Prefs.apDevices = o.toString()
    }

    fun setKind(k: String) { kind = k; rememberForDevice(); generation++ }

    private fun recallForDevice() {
        val e = devices().optJSONObject(outputName)
        kind = e?.optString("kind", "")?.takeIf { it.isNotEmpty() } ?: when {
            speaker -> KIND_SPEAKER
            hdmi || Regex("(?i)sound ?bar|home ?theat|\\bbar\\b|cinema").containsMatchIn(outputName) -> KIND_SOUNDBAR
            else -> KIND_HEADPHONES
        }
        if (e == null) return
        Prefs.apMood = e.optString("mood", Prefs.apMood)
        Prefs.apRoom = e.optString("room", Prefs.apRoom)
        Prefs.apIntensity = e.optInt("intensity", Prefs.apIntensity)
        Prefs.apStage = e.optInt("stage", Prefs.apStage)
        Prefs.apSep = e.optInt("sep", Prefs.apSep)
        Prefs.apInst = e.optInt("inst", Prefs.apInst)
        Prefs.apModesAuto = e.optBoolean("modesAuto", Prefs.apModesAuto)
    }

    private fun devices(): JSONObject = try { JSONObject(Prefs.apDevices) } catch (_: Exception) { JSONObject() }

    private fun detectOutput(ctx: Context) {
        var name = "Phone speaker"; var bt = false; var spk = true; var tv = false
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val pick = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                    ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                    ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
                    ?: outs.firstOrNull { isTvOut(it.type) }
                if (pick != null && isTvOut(pick.type)) {
                    spk = false; tv = true
                    name = pick.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL } ?: "TV / soundbar (HDMI)"
                } else if (pick != null) {
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
        outputName = name; bluetooth = bt; speaker = spk; hdmi = tv
    }

    private fun isTvOut(t: Int) = t == AudioDeviceInfo.TYPE_HDMI || t == AudioDeviceInfo.TYPE_HDMI_ARC ||
        t == AudioDeviceInfo.TYPE_LINE_DIGITAL || t == AudioDeviceInfo.TYPE_LINE_ANALOG ||
        (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_HDMI_EARC)

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
    fun build(rate: Int, withUserEq: Boolean, tag: MoodTag?, nativeLimiter: Boolean = false): Graph {
        val auto = Prefs.apMood == AUTO
        val m = if (auto) mood(tag?.moodId ?: "balanced") else mood()
        val k = Prefs.apIntensity.coerceIn(0, 150) / 100.0
        val roomId: String?
        val roomWet: Double
        when (val r = Prefs.apRoom) {
            "mood" -> { roomId = m.roomId; roomWet = m.roomWet }
            "off" -> { roomId = null; roomWet = 0.0 }
            AutoSound.ROOM_AUTO -> {
                // starts on the preset's room; the processor moves it with the music
                roomId = AutoSound.live.roomId ?: m.roomId ?: "medium_room"
                roomWet = m.roomWet.takeIf { it > 0 } ?: 0.15
            }
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
        val bar = !speaker && kind == KIND_SOUNDBAR
        if (bar) {
            // a soundbar: small drivers in one narrow box (often with a sub) – moderate bass, a wider stage
            // so the music opens up beyond the bar, and a little more voice so dialogue and singers stay clear
            low = low.coerceAtMost(3.0); bass *= 0.6
        }
        if (auto && tag?.tone == "bass-heavy") bass *= 0.3
        val widthScale = if (speaker) 0.5 else if (extSpeaker) 0.8 else if (bar) 1.3 else 1.0
        val p = SoundPreset(
            m.id, m.name, m.description,
            lowShelfDb = low, highShelfDb = high, bassBoost = bass,
            width = (1.0 + (m.width - 1.0) * k * widthScale).let { if (bar) maxOf(it, 1.0 + 0.15 * k) else it },
            melody = ((m.melody + if (bar) 0.15 else 0.0) * k).coerceAtMost(1.0),
            roomId = roomId, roomWet = roomWet * k,
        )
        val c = AudioPlusChain(rate)
        c.applyPreset(p)
        // the C++ true-peak limiter (−1 dBTP) is the final stage: the Kotlin sample-peak limiter stands aside
        if (nativeLimiter) c.limiter.ceilingDb = HiFi.KOTLIN_LIMITER_PARKED_DB
        if (speaker) {
            c.bass.setCutoff(150.0)
            c.bass.removeSubBass = p.bassBoost > 0
        }

        val stage = if (AutoSound.modesAuto) StageProcessor(rate).apply {
            // Auto: kept even at 0 so the processor can ease the modes in and out with the music
            val l = AutoSound.live
            this.stage = l.stage; separation = l.sep; instruments = l.inst
        } else StageProcessor(rate).apply {
            this.stage = Prefs.apStage / 100.0 * (if (speaker) 0.5 else 1.0)
            separation = Prefs.apSep / 100.0
            instruments = Prefs.apInst / 100.0
        }.takeIf { it.isActive }

        var user: ParametricEqualizer? = null
        val f = eqFreqs; val g = eqDb
        if (withUserEq && AutoSound.eqAuto && f.isNotEmpty()) {
            // Auto EQ: bands start where Auto last was and are eased by the processor
            val eq = ParametricEqualizer(2, rate)
            val n = minOf(f.size, ParametricEqualizer.MAX_BANDS)
            val cur = AutoSound.live.eqDb
            for (i in 0 until n) eq.setBand(i, autoBand(i, n, f[i], cur.getOrElse(i) { 0f }.toDouble()))
            user = eq
        } else if (withUserEq && eqOn && f.isNotEmpty() && f.size == g.size && g.any { it != 0f }) {
            val eq = ParametricEqualizer(2, rate)
            val n = minOf(f.size, ParametricEqualizer.MAX_BANDS)
            for (i in 0 until n) {
                val type = when (i) { 0 -> BiquadType.LowShelf; n - 1 -> BiquadType.HighShelf; else -> BiquadType.Peaking }
                eq.setBand(i, EqBand(type, f[i].toDouble(), g[i].toDouble(), if (type == BiquadType.Peaking) 1.41 else 0.707))
            }
            eq.setPreampDb(-0.5 * (g.maxOrNull() ?: 0f).coerceAtLeast(0f))
            user = eq
        }
        // SoundFit: this device's "Tune to my ears" profile, per ear
        val fit = SoundFitStore.active()?.let { pf ->
            try { SoundFitEqualizer(rate).also { pf.apply(it) } } catch (_: Throwable) { null }
        }
        return Graph(c, user, stage, fit)
    }

    fun autoBand(i: Int, n: Int, hz: Int, db: Double): EqBand {
        val type = when (i) { 0 -> BiquadType.LowShelf; n - 1 -> BiquadType.HighShelf; else -> BiquadType.Peaking }
        return EqBand(type, hz.toDouble(), db, if (type == BiquadType.Peaking) 1.41 else 0.707)
    }

    class Graph(val chain: AudioPlusChain, val userEq: ParametricEqualizer?, val stage: StageProcessor?,
                val soundFit: SoundFitEqualizer? = null) {
        // SoundFit first (it corrects the earphones + your ears), then your EQ, modes and the preset chain
        fun process(b: JslwAudioBuffer) { soundFit?.process(b); userEq?.process(b); stage?.process(b); chain.process(b) }
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

    /**
     * K-weighting (ITU-R BS.1770): high-pass 38 Hz + high shelf +4 dB above ~1.7 kHz. Level matching compares the
     * song before and after Audio+ the way the ear does – so added bass no longer turns voices and detail down.
     */
    private class KWeight(rate: Int) {
        private val hs = com.jslworks.soundengine.dsp.BiquadFilter(2).apply { configure(BiquadType.HighShelf, rate.toDouble(), 1681.974450955533, 0.7071752369554196, 3.999843853973347) }
        private val hp = com.jslworks.soundengine.dsp.BiquadFilter(2).apply { configure(BiquadType.HighPass, rate.toDouble(), 38.13547087602444, 0.5003270373238773, 0.0) }
        fun weigh(ch: Int, x: Double) = hp.processSample(ch, hs.processSample(ch, x))
    }
    private val kIn = KWeight(rate)
    private val kOut = KWeight(rate)

    private var pIn = 0.0
    private var pOut = 0.0
    private var makeup = 1f
    private val avg = 0.02

    private var tag: MoodTag? = if (songId >= 0) AudioPlus.cachedTag(songId) else null
    private var analyzer: TrackAnalyzer? = null

    init {
        if (songId >= 0) { AudioPlus.nowSongId = songId; AudioPlus.nowTag = tag }
    }

    // ---------------------------------------------------------------- JSLW Audio Engine C++ core (final stage)
    private val nativeLock = Any()
    private var core: com.jslworks.soundengine.nativecore.JslwNativeEngine? =
        if (HiFi.coreActive) try { com.jslworks.soundengine.nativecore.JslwNativeEngine.create(rate.toDouble(), 4096) } catch (_: Throwable) { null } else null
    private var coreLoud = false
    private var corePhon = -1.0
    private var coreXfeed = false

    /** Extra delay of the C++ core (true-peak look-ahead), frames: 85 at 48 kHz, 79 at 44.1 kHz. */
    val latencyFrames: Int = core?.latencyFrames ?: 0

    /** Kotlin chain, then the C++ core: equal-loudness → crossfeed → true-peak limiter. One call per decoder buffer. */
    fun process(data: FloatArray, frames: Int, channels: Int) {
        processGraph(data, frames, channels)
        if (channels != 2 || frames <= 0) return
        synchronized(nativeLock) {
            val n = core ?: return
            // Compare (A/B) hears the original: Hi-Fi stages glide off, only the transparent limiter stays
            val ab = AudioPlus.compare
            val loud = !ab && HiFi.loudnessOn
            val phon = if (loud) HiFi.listeningPhon(if (smooth.ready) smooth.lufs else -14.0) else 83.0
            if (loud != coreLoud || kotlin.math.abs(phon - corePhon) >= 1.0) { n.setLoudness(loud, phon); coreLoud = loud; corePhon = phon }
            val xf = !ab && HiFi.crossfeedOn
            if (xf != coreXfeed) { n.setCrossfeed(xf); coreXfeed = xf }
            n.render(data, 0, frames)
        }
    }

    /** After a seek: no old audio may come out of the look-ahead delay. Call from the decoder thread. */
    fun onSeek() = synchronized(nativeLock) { core?.reset() }

    /**
     * End of the song: the last [latencyFrames] are still inside the limiter's look-ahead. Pushes silence through
     * so they come out; returns how many frames of [buf] to write.
     */
    fun drainTail(buf: FloatArray): Int = synchronized(nativeLock) {
        val n = core ?: return 0
        val f = latencyFrames
        if (f <= 0 || buf.size < f * 2) return 0
        java.util.Arrays.fill(buf, 0, f * 2, 0f)
        n.render(buf, 0, f)
        f
    }

    fun close() = synchronized(nativeLock) { core?.close(); core = null }

    private fun limiterGrDb(c: AudioPlus.Graph): Double = core?.gainReductionDb ?: c.chain.limiter.gainReductionDb

    private fun processGraph(data: FloatArray, frames: Int, channels: Int) {
        if (channels != 2 || frames <= 0) return
        val g = AudioPlus.generation
        if (g != gen) {
            gen = g
            prev = cur
            cur = if (AudioPlus.active && !AudioPlus.compare) {
                try { AudioPlus.build(rate, withUserEq, tag, core != null) } catch (_: Throwable) { null }
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
            // loudness as the ear hears it (K-weighted, ITU-R BS.1770), not raw energy dominated by bass
            val kl = kIn.weigh(0, l.toDouble()); val kr = kIn.weigh(1, r.toDouble())
            sIn += kl * kl + kr * kr
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

        if (c != null && (AutoSound.anyAuto || HiFi.loudnessOn)) autoStep(c, x, frames)

        val fading = fadeLeft > 0
        if (fading) { xl.copyInto(y.channel(0)); xr.copyInto(y.channel(1)) }

        if (c != null) {
            val mk = makeup
            if (mk != 1f) for (i in 0 until frames) { xl[i] *= mk; xr[i] *= mk }
            c.process(x)
            var sOut = 0.0
            for (i in 0 until frames) {
                val kl = kOut.weigh(0, xl[i].toDouble()); val kr = kOut.weigh(1, xr[i].toDouble())
                sOut += kl * kl + kr * kr
            }
            val blockIn = sIn / frames; val blockOut = sOut / frames
            if (blockIn > 1e-7) {
                pIn += (blockIn - pIn) * avg
                pOut += (blockOut - pOut) * avg
                if (limiterGrDb(c) < -1.5) {
                    makeup = (makeup * 0.98f).coerceAtLeast(1f)          // yield to the limiter: no pumping
                } else if (pOut > 1e-9) {
                    val want = (makeup * sqrt(pIn / pOut)).toFloat().coerceIn(0.89f, MAX_MAKEUP)   // never noticeably quieter
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

    // ---------------------------------------------------------------- Auto Room / Modes / EQ, live with the music
    private var section: TrackAnalyzer? = null
    private var sections = 0
    private val smooth = AutoSound.Smoothed()
    private var target: AutoSound.Decision? = null
    private var lastRoomVote: String? = null
    private var confirmedRoom: String? = null
    private var appliedRoom: String? = null
    private var appliedWet = -1.0
    private var eqNow: FloatArray = AutoSound.live.eqDb.copyOf()
    private var eqSet: FloatArray = FloatArray(0)
    private val modesNow = doubleArrayOf(AutoSound.live.stage, AutoSound.live.sep, AutoSound.live.inst)
    private var autoGraph: AudioPlus.Graph? = null

    private fun autoStep(c: AudioPlus.Graph, x: JslwAudioBuffer, frames: Int) {
        // listen in sections: the first after 4 s, then every 6 s, all the way through the song
        val an = section ?: TrackAnalyzer(rate, if (sections == 0) 4.0 else 6.0).also { section = it }
        an.process(x)
        if (an.isFull) {
            val f = an.features()
            section = null; sections++
            if (smooth.add(f)) {
                val moodNow = if (Prefs.apMood == AudioPlus.AUTO) AudioPlus.mood(tag?.moodId ?: "balanced") else AudioPlus.mood()
                val d = AutoSound.decide(smooth, moodNow, AudioPlus.bandFreqs, AudioPlus.kind, AudioPlus.speaker)
                val first = target == null
                target = d
                // a room changes only when two sections in a row agree (or on the first decision)
                if (first || d.roomId == lastRoomVote) confirmedRoom = d.roomId
                lastRoomVote = d.roomId
                AutoSound.live.listening = false
            }
        }
        val t = target ?: return
        if (c !== autoGraph) { autoGraph = c; eqSet = FloatArray(0); appliedRoom = null }   // new graph: apply everything once
        val dt = frames.toDouble() / rate

        // room
        if (AutoSound.roomAuto) {
            val want = confirmedRoom
            val wet = t.roomWet * AudioPlus.intensity
            if (want != null && (want != appliedRoom || kotlin.math.abs(wet - appliedWet) > appliedWet * 0.2 + 0.01)) {
                try { c.chain.setRoom(SoundPresets.room(want), wet) } catch (_: Throwable) {}
                appliedRoom = want; appliedWet = wet
                AutoSound.live.roomId = want
            }
        }

        // modes, eased ~0.12 per second
        val st = c.stage
        if (AutoSound.modesAuto && st != null) {
            val goal = doubleArrayOf(t.stage, t.sep, t.inst)
            val step = 0.12 * dt
            var moved = false
            for (i in 0..2) {
                val dlt = (goal[i] - modesNow[i]).coerceIn(-step, step)
                if (dlt != 0.0) { modesNow[i] += dlt; moved = true }
            }
            if (moved || st.stage != modesNow[0]) {
                st.stage = modesNow[0]; st.separation = modesNow[1]; st.instruments = modesNow[2]
                AutoSound.live.stage = modesNow[0]; AutoSound.live.sep = modesNow[1]; AutoSound.live.inst = modesNow[2]
            }
        }

        // equalizer, eased ~0.6 dB per second
        val eq = c.userEq
        if (AutoSound.eqAuto && eq != null && t.eqDb.isNotEmpty()) {
            val n = minOf(t.eqDb.size, ParametricEqualizer.MAX_BANDS)
            if (eqNow.size != n) eqNow = FloatArray(n) { AutoSound.live.eqDb.getOrElse(it) { 0f } }
            val step = (0.6 * dt).toFloat()
            for (i in 0 until n) eqNow[i] += (t.eqDb[i] - eqNow[i]).coerceIn(-step, step)
            val changed = eqSet.size != n || (0 until n).any { kotlin.math.abs(eqNow[it] - eqSet[it]) > 0.05f }
            if (changed) {
                val fr = AudioPlus.bandFreqs
                for (i in 0 until n) eq.setBand(i, AudioPlus.autoBand(i, n, fr.getOrElse(i) { 1000 }, eqNow[i].toDouble()))
                eq.setPreampDb(-0.5 * (eqNow.maxOrNull() ?: 0f).coerceAtLeast(0f))
                eqSet = eqNow.copyOf()
                AutoSound.live.eqDb = eqNow.copyOf()
            }
        }
    }

    companion object {
        private const val ANALYZE_SECONDS = 15.0
        private const val MAX_MAKEUP = 1.58f   // +4 dB
    }
}
