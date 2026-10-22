package com.jslw.genz.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import com.jslw.genz.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/**
 * Sound effects on the player's audio session:
 *  - 10-band graphic EQ (Android 9+, DynamicsProcessing) or the device's own EQ bands (usually 5) on older Android
 *  - bass boost, 3D surround (virtualizer), loudness enhancer, reverb (aux effect)
 */
class AudioFx(private val sessionId: Int) {
    private var dp: Any? = null              // DynamicsProcessing (typed Any so the class loads on KitKat)
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null

    /** Centre frequencies (Hz) of the bands shown in the UI. */
    var bandFreqs: IntArray = IntArray(0); private set
    /** Gain range in millibels. */
    var minLevel = -1200; private set
    var maxLevel = 1200; private set
    val tenBand: Boolean get() = dp != null
    val reverbEffectId: Int get() = reverb?.id ?: 0
    var available = false; private set

    init {
        if (Build.VERSION.SDK_INT >= 28) createDp()
        if (dp == null) try {
            val e = Equalizer(0, sessionId)
            eq = e
            bandFreqs = IntArray(e.numberOfBands.toInt()) { e.getCenterFreq(it.toShort()) / 1000 }
            val r = e.bandLevelRange
            minLevel = r[0].toInt(); maxLevel = r[1].toInt()
        } catch (_: Throwable) {}
        try { bass = BassBoost(0, sessionId) } catch (_: Throwable) {}
        try { virt = Virtualizer(0, sessionId) } catch (_: Throwable) {}
        try { loud = LoudnessEnhancer(sessionId) } catch (_: Throwable) {}
        try { reverb = PresetReverb(0, 0) } catch (_: Throwable) {}
        available = dp != null || eq != null
        applyAll()
    }

    @androidx.annotation.RequiresApi(28)
    private fun createDp() {
        try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, 10, false, 0, false, 0, true
            ).build()
            dp = DynamicsProcessing(0, sessionId, cfg)
            bandFreqs = STD_FREQS.copyOf()
            minLevel = -1200; maxLevel = 1200
        } catch (_: Throwable) {
            dp = null
        }
    }

    fun currentLevels(): IntArray {
        val saved = Prefs.eqBands.split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (saved.size == bandFreqs.size) saved.toIntArray() else IntArray(bandFreqs.size)
    }

    fun setLevels(levels: IntArray, presetName: String) {
        Prefs.eqBands = levels.joinToString(",")
        Prefs.eqPreset = presetName
        applyEq()
    }

    fun applyAll() {
        applyEq()
        val on = Prefs.eqEnabled
        try {
            bass?.let {
                if (it.strengthSupported) it.setStrength(Prefs.bassStrength.toShort())
                it.enabled = on && Prefs.bassStrength > 0
            }
        } catch (_: Throwable) {}
        try {
            virt?.let {
                if (it.strengthSupported) it.setStrength(Prefs.virtStrength.toShort())
                it.enabled = on && Prefs.virtStrength > 0
            }
        } catch (_: Throwable) {}
        try {
            loud?.let {
                it.setTargetGain(Prefs.loudness * 3 / 2)
                it.enabled = on && Prefs.loudness > 0
            }
        } catch (_: Throwable) {}
        try {
            reverb?.let {
                it.preset = Prefs.reverb.toShort()
                it.enabled = on && Prefs.reverb > 0
            }
        } catch (_: Throwable) {}
    }

    private fun applyEq() {
        val on = Prefs.eqEnabled
        val levels = currentLevels()
        if (Build.VERSION.SDK_INT >= 28 && dp != null) {
            try {
                val d = dp as DynamicsProcessing
                for (i in levels.indices) {
                    val edge = if (i == levels.lastIndex) 20000f else (bandFreqs[i] * 1.4142f)
                    d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, edge, levels[i] / 100f))
                }
                // Clean sound: pull the input down by the largest boost so the EQ never clips,
                // and keep a transparent brick-wall limiter at -0.3 dBFS as a safety net.
                d.setInputGainAllChannelsTo(-maxBoostDb(levels))
                d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -0.3f, 0f))
                d.enabled = on
            } catch (_: Throwable) {}
        }
        eq?.let { e ->
            try {
                for (i in levels.indices) e.setBandLevel(i.toShort(), levels[i].coerceIn(minLevel, maxLevel).toShort())
                e.enabled = on
            } catch (_: Throwable) {}
        }
    }

    private fun maxBoostDb(levels: IntArray): Float = (levels.maxOrNull() ?: 0).coerceAtLeast(0) / 100f

    /**
     * Linear volume factor that gives the legacy (pre-Android 9) equalizer the same anti-clipping
     * headroom: boosting 6 dB lowers the player volume by 6 dB. 1.0 when off or on the 10-band engine.
     */
    fun headroom(): Float {
        if (!Prefs.eqEnabled || dp != null || eq == null) return 1f
        val db = maxBoostDb(currentLevels()) + (if (Prefs.bassStrength > 0) Prefs.bassStrength / 250f else 0f)
        return Math.pow(10.0, -db / 20.0).toFloat()
    }

    /** All presets: built-in (GEN-Z), device presets and the user's own. Values in mB per UI band. */
    fun presets(): LinkedHashMap<String, IntArray> {
        val out = LinkedHashMap<String, IntArray>()
        for ((name, db) in BUILTIN) out[name] = mapToBands(db)
        eq?.let { e ->
            try {
                for (p in 0 until e.numberOfPresets) {
                    val n = e.getPresetName(p.toShort())
                    if (n.isNullOrBlank() || out.containsKey(n)) continue
                    // read a device preset by applying it to a throw-away Equalizer
                    val tmp = Equalizer(0, sessionId)
                    tmp.usePreset(p.toShort())
                    out[n] = IntArray(bandFreqs.size) { tmp.getBandLevel(it.toShort()).toInt() }
                    tmp.release()
                }
            } catch (_: Throwable) {}
        }
        customPresets().forEach { (k, v) -> out[k] = v }
        return out
    }

    fun customPresets(): Map<String, IntArray> {
        val out = LinkedHashMap<String, IntArray>()
        try {
            val o = JSONObject(Prefs.eqCustomPresets)
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                val db = FloatArray(a.length()) { a.getDouble(it).toFloat() }
                out[k] = mapToBands(db, if (db.size == 10) STD_FREQS else null)
            }
        } catch (_: Exception) {}
        return out
    }

    /** Custom presets are stored in dB at the standard 10 frequencies so they move between phones. */
    fun saveCustom(name: String, levels: IntArray) {
        val o = try { JSONObject(Prefs.eqCustomPresets) } catch (e: Exception) { JSONObject() }
        val db = FloatArray(10) { i -> interp(bandFreqs, levels.map { it / 100f }.toFloatArray(), STD_FREQS[i].toFloat()) }
        o.put(name, JSONArray(db.map { it.toDouble() }))
        Prefs.eqCustomPresets = o.toString()
    }

    fun deleteCustom(name: String) {
        val o = try { JSONObject(Prefs.eqCustomPresets) } catch (e: Exception) { return }
        o.remove(name)
        Prefs.eqCustomPresets = o.toString()
    }

    private fun mapToBands(db: FloatArray, freqs: IntArray? = STD_FREQS): IntArray =
        IntArray(bandFreqs.size) { i ->
            val v = if (freqs == null) db.getOrElse(i) { 0f } else interp(freqs, db, bandFreqs[i].toFloat())
            (v * 100).toInt().coerceIn(minLevel, maxLevel)
        }

    /** Log-frequency linear interpolation. */
    private fun interp(freqs: IntArray, vals: FloatArray, f: Float): Float {
        if (freqs.isEmpty()) return 0f
        if (f <= freqs.first()) return vals.first()
        if (f >= freqs.last()) return vals.last()
        for (i in 0 until freqs.size - 1) {
            if (f <= freqs[i + 1]) {
                val a = ln(freqs[i].toDouble()); val b = ln(freqs[i + 1].toDouble())
                val t = ((ln(f.toDouble()) - a) / (b - a)).toFloat()
                return vals[i] + (vals[i + 1] - vals[i]) * t
            }
        }
        return 0f
    }

    fun release() {
        try { if (Build.VERSION.SDK_INT >= 28) (dp as? DynamicsProcessing)?.release() } catch (_: Throwable) {}
        listOf(eq, bass, virt, loud, reverb).forEach { try { it?.release() } catch (_: Throwable) {} }
    }

    companion object {
        val STD_FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        val REVERBS = listOf("None", "Small room", "Medium room", "Large room", "Medium hall", "Large hall", "Plate")

        val BUILTIN: List<Pair<String, FloatArray>> = listOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Bass Boost" to floatArrayOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f),
            "Rock" to floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f),
            "Pop" to floatArrayOf(-1f, 0f, 2f, 4f, 5f, 4f, 2f, 0f, -1f, -1f),
            "Jazz" to floatArrayOf(4f, 3f, 1f, 2f, -1f, -1f, 0f, 1f, 3f, 4f),
            "Classical" to floatArrayOf(5f, 4f, 3f, 2f, -1f, -1f, 0f, 2f, 3f, 4f),
            "Vocal" to floatArrayOf(-2f, -2f, -1f, 1f, 4f, 5f, 4f, 2f, 0f, -1f),
            "Electronic" to floatArrayOf(6f, 5f, 1f, 0f, -2f, 2f, 1f, 2f, 5f, 6f),
            "Hip-Hop" to floatArrayOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, -1f, 2f, 3f),
            "Acoustic" to floatArrayOf(4f, 4f, 3f, 1f, 2f, 2f, 3f, 3f, 3f, 2f),
            "Treble" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 7f, 8f),
            "Loudness" to floatArrayOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -2f, 4f, 2f),
            "Late Night" to floatArrayOf(-3f, -2f, 0f, 1f, 2f, 2f, 1f, 0f, -2f, -3f),
        )
    }
}
