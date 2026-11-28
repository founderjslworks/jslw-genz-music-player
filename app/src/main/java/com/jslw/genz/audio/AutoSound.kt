package com.jslw.genz.audio

import com.jslw.genz.data.Prefs
import com.jslworks.soundengine.dsp.SoundPreset
import com.jslworks.soundengine.dsp.SoundPresets
import com.jslworks.soundengine.dsp.TrackFeatures
import org.json.JSONArray
import kotlin.math.log10
import kotlin.math.ln

/**
 * The "Auto" brain of JSLW Audio+ for Room, Modes and Equalizer.
 *
 * While a song plays, Audio+ listens to it in short sections (~6 s) all the way through – tonal balance (sub, low-mid,
 * mid, presence, air), dynamics (crest factor), loudness, stereo width and tempo – and decides:
 *  - **Room**: the preset's own room, one size bigger for quiet, open passages and one size smaller for dense, loud
 *    or fast ones, with less echo when the mix is busy (so reverb never muddies a chorus);
 *  - **Modes**: Sound Stage for narrow mixes, Sound Separation when a voice is there to lift, Instrument Separation
 *    for dense, compressed mixes;
 *  - **Equalizer**: a gentle correction (±4.5 dB) that brings each song toward the average tonal balance of YOUR
 *    music – learned from everything you play – so dull songs get air, boomy songs get tamed.
 * Every change is eased in (EQ ~0.6 dB/s, modes ~0.12/s) so nothing jumps; rooms change only when two sections
 * in a row agree.
 */
object AutoSound {
    const val ROOM_AUTO = "auto"
    const val EQ_AUTO = "Auto"

    /** Region centres for the analyser's five tonal shares. */
    private val REGION_HZ = doubleArrayOf(50.0, 200.0, 1000.0, 3500.0, 12000.0)
    private val ROOM_ORDER = listOf("small_room", "medium_room", "large_room", "medium_hall", "large_hall", "concert_hall", "cathedral")
    private val SPECIAL_ROOMS = setOf("party_hall", "plate", "temple")

    val eqAuto: Boolean get() = Prefs.eqEnabled && Prefs.eqPreset == EQ_AUTO
    val roomAuto: Boolean get() = Prefs.apRoom == ROOM_AUTO
    val modesAuto: Boolean get() = Prefs.apModesAuto
    val anyAuto: Boolean get() = eqAuto || roomAuto || modesAuto

    /** What Auto is doing right now – shown live on the Sound screen. */
    class Live(
        @Volatile var roomId: String? = null,
        @Volatile var stage: Double = 0.0,
        @Volatile var sep: Double = 0.0,
        @Volatile var inst: Double = 0.0,
        @Volatile var eqDb: FloatArray = FloatArray(0),
        @Volatile var listening: Boolean = true,
    )
    @Volatile var live = Live()

    class Decision(val roomId: String?, val roomWet: Double, val stage: Double, val sep: Double, val inst: Double, val eqDb: FloatArray)

    // ---------------------------------------------------------------- your music's average tonal balance
    @Volatile private var ref: DoubleArray? = null
    private var refUpdates = 0

    private fun reference(): DoubleArray? {
        ref?.let { return it }
        val r = try {
            val a = JSONArray(Prefs.apRefShares)
            if (a.length() == 5) DoubleArray(5) { a.getDouble(it) } else null
        } catch (_: Exception) { null }
        ref = r
        return r
    }

    @Synchronized private fun learn(s: DoubleArray) {
        val r = reference()
        ref = if (r == null) s.copyOf() else DoubleArray(5) { r[it] + (s[it] - r[it]) * 0.03 }
        if (++refUpdates % 15 == 1) Prefs.apRefShares = JSONArray(ref!!.toList()).toString()
    }

    fun shares(f: TrackFeatures): DoubleArray? {
        val raw = doubleArrayOf(f.subShare, f.lowMidShare, f.midShare, f.presenceShare, f.airShare)
        val sum = raw.sum()
        if (sum <= 1e-9 || raw.any { it.isNaN() }) return null
        return DoubleArray(5) { (raw[it] / sum).coerceAtLeast(1e-4) }
    }

    /** A section was analysed: decide Room, Modes and EQ for it. [smoothed] are the song's running features. */
    fun decide(f: Smoothed, mood: SoundPreset, bandFreqs: IntArray, kind: String, onSpeaker: Boolean): Decision {
        val soundFitOn = SoundFitStore.active() != null
        val s = f.shares
        learn(s)
        val r = reference() ?: s

        // ---- room
        val sparse = f.crestDb > 14 && f.lufs < -18
        val dense = f.crestDb < 9 || f.lufs > -10
        val fast = f.bpm > 128
        val base = mood.roomId ?: "medium_room"
        val room = if (base in SPECIAL_ROOMS) base else {
            val i = ROOM_ORDER.indexOf(base).takeIf { it >= 0 } ?: 1
            var step = 0
            if (sparse) step++
            if (dense) step--
            if (fast) step--
            ROOM_ORDER[(i + step).coerceIn(0, ROOM_ORDER.size - 1)]
        }
        var wet = mood.roomWet.takeIf { it > 0 } ?: 0.15
        wet *= when { dense -> 0.7; sparse -> 1.25; else -> 1.0 }
        if (fast) wet *= 0.8
        wet *= 0.8   // Auto keeps the room a touch drier than the preset: clearer, closer sound
        if (onSpeaker) wet *= 0.8

        // ---- modes
        val w = f.width
        var stage = when { w < 0.15 -> 0.6; w < 0.35 -> 0.4; w < 0.6 -> 0.2; else -> 0.05 }
        val voice = s[3] / r[3].coerceAtLeast(1e-4)
        var sep = when { voice > 1.1 -> 0.55; voice > 0.85 -> 0.4; else -> 0.15 } + (if (dense) 0.1 else 0.0)
        val inst = when { f.crestDb < 9 -> 0.55; f.crestDb < 13 -> 0.35; else -> 0.15 }
        if (onSpeaker) stage *= 0.5
        if (kind == AudioPlus.KIND_SOUNDBAR && !onSpeaker) { stage = (stage + 0.15).coerceAtMost(0.7); sep += 0.05 }
        sep = sep.coerceAtMost(0.7)

        // ---- equalizer: half-way toward your average balance, ±4.5 dB
        val corr = DoubleArray(5) { (10 * log10(r[it] / s[it]) * 0.6).coerceIn(-4.5, 4.5) }
        val eq = FloatArray(bandFreqs.size) { i ->
            var v = interp(bandFreqs[i].toDouble(), corr)
            if ((kind == AudioPlus.KIND_SPEAKER || kind == AudioPlus.KIND_CAR || kind == AudioPlus.KIND_SOUNDBAR || onSpeaker) && bandFreqs[i] < 150) v = v.coerceAtMost(2.0)
            // SoundFit already shaped the low end for these earphones: Auto EQ doesn't add more bass on top
            if (soundFitOn && bandFreqs[i] < 250) v = v.coerceAtMost(0.5)
            // clarity first: Auto never dulls voices and detail (presence and air are only lifted, or cut ≤ 1 dB)
            if (bandFreqs[i] >= 2000) v = v.coerceAtLeast(-1.0)
            v.toFloat()
        }
        if (eq.isNotEmpty()) {
            val mean = eq.average().toFloat()
            for (i in eq.indices) eq[i] = ((eq[i] - mean) * 10).toInt() / 10f
        }
        return Decision(room, wet, stage, sep, inst, eq)
    }

    private fun interp(hz: Double, v: DoubleArray): Double {
        if (hz <= REGION_HZ[0]) return v[0]
        if (hz >= REGION_HZ[4]) return v[4]
        for (i in 0 until 4) {
            if (hz <= REGION_HZ[i + 1]) {
                val t = (ln(hz) - ln(REGION_HZ[i])) / (ln(REGION_HZ[i + 1]) - ln(REGION_HZ[i]))
                return v[i] + (v[i + 1] - v[i]) * t
            }
        }
        return v[4]
    }

    /** The song's features, eased across sections so one odd bar doesn't swing the sound. */
    class Smoothed {
        var shares = DoubleArray(5); var crestDb = 12.0; var lufs = -14.0; var width = 0.5; var bpm = 100.0
        private var n = 0
        fun add(f: TrackFeatures): Boolean {
            val s = AutoSound.shares(f) ?: return false
            val a = if (n == 0) 1.0 else 0.5
            for (i in 0 until 5) shares[i] += (s[i] - shares[i]) * a
            if (!f.crestDb.isNaN()) crestDb += (f.crestDb - crestDb) * a
            if (!f.lufs.isNaN() && f.lufs > -70) lufs += (f.lufs - lufs) * a
            if (!f.width.isNaN()) width += (f.width - width) * a
            if (f.tempoConfidence > 0.4 && f.bpm in 50.0..220.0) bpm += (f.bpm - bpm) * a
            n++
            return true
        }
        val ready get() = n > 0
    }

    /**
     * What Auto has chosen for the song playing right now, e.g.
     * "Happy · Medium Hall · Stage Light · Voice Medium · EQ bass −1.5, treble +2". Null when Audio+ is off
     * or nothing is on Auto.
     */
    fun summary(): String? {
        if (!AudioPlus.active) return null
        val parts = ArrayList<String>()
        if (Prefs.apMood == AudioPlus.AUTO) {
            val t = AudioPlus.nowTag
            parts += if (t != null) "${AudioPlus.mood(t.moodId).name} · ${t.bpm.toInt()} BPM" else "Preset: listening…"
        }
        if (roomAuto) parts += live.roomId?.let { roomName(it) } ?: "Room: listening…"
        if (modesAuto) {
            val m = listOf("Stage" to live.stage, "Voice" to live.sep, "Instruments" to live.inst)
                .filter { it.second >= 0.1 }.joinToString(" · ") { "${it.first} ${level(it.second)}" }
            parts += m.ifEmpty { "Modes off" }
        }
        if (eqAuto) parts += eqText()
        if (parts.isEmpty()) return null
        if (SoundFitStore.active() != null) parts += "SoundFit"
        return parts.joinToString("  ·  ")
    }

    private fun eqText(): String {
        val db = live.eqDb; val f = AudioPlus.bandFreqs
        if (db.isEmpty() || f.size != db.size) return "EQ: listening…"
        fun avg(lo: Int, hi: Int) = db.indices.filter { f[it] in lo until hi }.map { db[it] }.average().takeIf { !it.isNaN() } ?: 0.0
        val zones = listOf("bass" to avg(0, 250), "mids" to avg(250, 4000), "treble" to avg(4000, 100000))
            .filter { kotlin.math.abs(it.second) >= 0.3 }
        if (zones.isEmpty()) return "EQ flat"
        return "EQ " + zones.joinToString(", ") { "${it.first} ${if (it.second > 0) "+" else "−"}${"%.1f".format(kotlin.math.abs(it.second))}" }
    }

    fun roomName(id: String?): String = when (id) {
        null -> "No room"
        else -> try { SoundPresets.room(id).name } catch (_: Exception) { id }
    }

    fun level(v: Double) = when { v < 0.1 -> "Off"; v < 0.45 -> "Light"; v < 0.7 -> "Medium"; else -> "Strong" }
}
