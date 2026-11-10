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
 *  - JSLW Sound Engine (see [SoundEngine]): tone, dialogue, surround, room and volume leveller
 *
 * Effects exist only while GEN-Z's equalizer or Sound Engine is switched on. When both are off GEN-Z attaches
 * nothing to its audio, so the phone's own sound features (for example its Dolby or other system sound
 * enhancement) apply to GEN-Z exactly as they do to other apps.
 */
class AudioFx(private val sessionId: Int, private val ctx: android.content.Context) {
    private var dp: Any? = null              // DynamicsProcessing (typed Any so the class loads on KitKat)
    private var dpFull = false               // DP with multi-band leveller + post-EQ (the Sound Engine stages)
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null
    private var created = false

    /** Centre frequencies (Hz) of the bands shown in the UI. */
    var bandFreqs: IntArray = IntArray(0); private set
    /** Gain range in millibels. */
    var minLevel = -1200; private set
    var maxLevel = 1200; private set
    var tenBand = false; private set
    val reverbEffectId: Int get() = reverb?.id ?: 0
    var available = false; private set

    init {
        // learn what this phone offers, then step aside again unless an effect is switched on
        create()
        tenBand = dp != null
        available = dp != null || eq != null
        applyAll()
    }

    private fun create() {
        if (created) return
        created = true
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
    }

    /** Releases every effect so nothing of GEN-Z's sits on the audio path. */
    private fun destroy() {
        if (!created) return
        created = false
        try { if (Build.VERSION.SDK_INT >= 28) (dp as? DynamicsProcessing)?.release() } catch (_: Throwable) {}
        listOf(eq, bass, virt, loud, reverb).forEach { try { it?.release() } catch (_: Throwable) {} }
        dp = null; eq = null; bass = null; virt = null; loud = null; reverb = null
    }

    @androidx.annotation.RequiresApi(28)
    private fun createDp() {
        // full chain: 10-band EQ → 3-band leveller → 6-band tone → limiter; fall back to EQ + limiter
        // Android 9/10 builds of DynamicsProcessing are unreliable with the multi-band stage, so those
        // phones use EQ + limiter and the engine's legacy leveller (LoudnessEnhancer) instead.
        for (full in if (Build.VERSION.SDK_INT >= 30) listOf(true, false) else listOf(false)) {
            try {
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, 10, full, if (full) 3 else 0, full, if (full) 6 else 0, true
                ).build()
                dp = DynamicsProcessing(0, sessionId, cfg)
                dpFull = full
                bandFreqs = STD_FREQS.copyOf()
                minLevel = -1200; maxLevel = 1200
                return
            } catch (_: Throwable) {
                dp = null
            }
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

    private val engineOn get() = Prefs.seOn
    private val eqOn get() = Prefs.eqEnabled

    /**
     * Applies every setting. Returns true when the effect chain itself was just added or removed:
     * a track that is already playing may not pick that up (Android 10 and several vendor ROMs leave it
     * silent), so the caller must reopen the current song on a fresh track.
     */
    fun applyAll(): Boolean {
        val had = created
        if (!eqOn && !engineOn) { destroy(); return had }
        create()
        val v = if (engineOn) SoundEngine.values(ctx) else null
        applyEq()
        try {
            bass?.let {
                val strength = if (eqOn) Prefs.bassStrength else 0
                if (it.strengthSupported) it.setStrength(strength.toShort())
                it.enabled = strength > 0
            }
        } catch (_: Throwable) {}
        try {
            virt?.let {
                val user = if (eqOn) Prefs.virtStrength else 0
                val engine = v?.let { (it.surround * 1000).toInt() } ?: 0
                val strength = maxOf(user, engine)
                // force real virtualisation for Bluetooth and wired headphones alike (Android 8+)
                if (Build.VERSION.SDK_INT >= 26 && strength > 0) try {
                    it.forceVirtualizationMode(
                        if (v?.headphones != false) Virtualizer.VIRTUALIZATION_MODE_BINAURAL else Virtualizer.VIRTUALIZATION_MODE_TRANSAURAL
                    )
                } catch (_: Throwable) {}
                if (it.strengthSupported) it.setStrength(strength.toShort())
                it.enabled = strength > 0
            }
        } catch (_: Throwable) {}
        try {
            loud?.let {
                // Android 9+: the Sound Engine levels with a real compressor; older phones lift quiet passages instead
                val legacyLevel = if (v != null && !dpFull) (v.leveller * 800).toInt() else 0
                val gain = maxOf(if (eqOn) Prefs.loudness * 3 / 2 else 0, legacyLevel)
                it.setTargetGain(gain)
                it.enabled = gain > 0
            }
        } catch (_: Throwable) {}
        try {
            reverb?.let {
                val preset = reverbPreset(v)
                it.preset = preset.toShort()
                it.enabled = preset > 0
            }
        } catch (_: Throwable) {}
        return !had
    }

    private fun reverbPreset(v: SoundEngine.Values?): Int = when {
        eqOn && Prefs.reverb > 0 -> Prefs.reverb
        v != null && Prefs.seRoomType == "off" -> 0
        v != null && v.room > 0.02f && Prefs.seRoomType != "auto" -> SoundEngine.room(Prefs.seRoomType).preset.coerceAtLeast(0)
        v != null && v.room > 0.02f -> when (Prefs.seProfile) {
            "movie" -> 3      // large room
            "game" -> 2       // medium room
            else -> 1         // small room
        }
        else -> 0
    }

    /** How much of the music goes to the reverb (0 = none). */
    fun reverbSend(): Float {
        if (!created || reverb == null) return 0f
        if (eqOn && Prefs.reverb > 0) return 1f
        if (!engineOn) return 0f
        if (Prefs.seRoomType == "off") return 0f
        return SoundEngine.values(ctx).room.coerceIn(0f, 1f) * 0.7f
    }

    private fun applyEq() {
        if (!created) return
        val levels = if (eqOn) currentLevels() else IntArray(bandFreqs.size)
        val v = if (engineOn) SoundEngine.values(ctx) else null
        val tone = v?.let { SoundEngine.toneDb(it) }
        if (Build.VERSION.SDK_INT >= 28 && dp != null) {
            try {
                val d = dp as DynamicsProcessing
                // without the full chain, fold the engine's tone into the graphic EQ
                val pre = if (tone != null && !dpFull) IntArray(levels.size) { i ->
                    levels[i] + (interp(SoundEngine.TONE_FREQS, tone, bandFreqs[i].toFloat()) * 100).toInt()
                } else levels
                for (i in pre.indices) {
                    val edge = if (i == pre.lastIndex) 20000f else (bandFreqs[i] * 1.4142f)
                    d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, edge, pre[i] / 100f))
                }
                var toneMax = 0f
                if (dpFull) {
                    val edges = floatArrayOf(100f, 250f, 700f, 2500f, 5000f, 20000f)
                    for (i in 0 until 6) {
                        val g = tone?.get(i) ?: 0f
                        toneMax = maxOf(toneMax, g)
                        d.setPostEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, edges[i], g))
                    }
                    // volume leveller: gentle 3-band compression with make-up gain
                    val l = v?.leveller ?: 0f
                    val cut = floatArrayOf(250f, 4000f, 20000f)
                    for (i in 0 until 3) {
                        d.setMbcBandAllChannelsTo(i, DynamicsProcessing.MbcBand(
                            l > 0.01f, cut[i], 8f, 180f, 1f + 3f * l, -10f - 24f * l, 6f, -90f, 1f, 0f, 5f * l
                        ))
                    }
                } else tone?.let { toneMax = it.maxOrNull() ?: 0f }
                // Clean sound: pull the input down by the largest boost so nothing clips,
                // and keep a transparent brick-wall limiter at -0.3 dBFS as a safety net.
                d.setInputGainAllChannelsTo(-(maxBoostDb(pre) + toneMax * 0.6f))
                d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -0.3f, 0f))
                d.enabled = true
            } catch (_: Throwable) {}
        }
        eq?.let { e ->
            try {
                for (i in levels.indices) {
                    val add = tone?.let { (interp(SoundEngine.TONE_FREQS, it, bandFreqs[i].toFloat()) * 100).toInt() } ?: 0
                    e.setBandLevel(i.toShort(), (levels[i] + add).coerceIn(minLevel, maxLevel).toShort())
                }
                e.enabled = true
            } catch (_: Throwable) {}
        }
    }

    private fun maxBoostDb(levels: IntArray): Float = (levels.maxOrNull() ?: 0).coerceAtLeast(0) / 100f

    /**
     * Linear volume factor that gives the legacy (pre-Android 9) equalizer the same anti-clipping
     * headroom: boosting 6 dB lowers the player volume by 6 dB. 1.0 when off or on the 10-band engine.
     */
    fun headroom(): Float {
        if (dp != null || eq == null || (!Prefs.eqEnabled && !Prefs.seOn)) return 1f
        var db = if (Prefs.eqEnabled) maxBoostDb(currentLevels()) + (if (Prefs.bassStrength > 0) Prefs.bassStrength / 250f else 0f) else 0f
        if (Prefs.seOn) db += (SoundEngine.toneDb(SoundEngine.values(ctx)).maxOrNull() ?: 0f) * 0.6f
        return Math.pow(10.0, -db / 20.0).toFloat()
    }

    /** All presets: built-in (GEN-Z), device presets and the user's own. Values in mB per UI band. */
    fun presets(): LinkedHashMap<String, IntArray> {
        val out = LinkedHashMap<String, IntArray>()
        for ((name, db) in BUILTIN) out[name] = mapToBands(db)
        val probe = eq ?: if (dp == null && !created) try { Equalizer(0, sessionId) } catch (_: Throwable) { null } else null
        probe?.let { e ->
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
            if (e !== eq) try { e.release() } catch (_: Throwable) {}
        }
        customPresets().forEach { (k, v) -> out[k] = v }
        return out
    }

    /** Which group a preset belongs to: a built-in category, "Device" or "Mine". */
    fun category(name: String): String =
        CATEGORIES.firstOrNull { name in it.second }?.first ?: if (customPresets().containsKey(name)) "Mine" else "Device"

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

    fun release() = destroy()

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
            "Dance" to floatArrayOf(5f, 4f, 2f, 0f, 0f, -1f, 1f, 3f, 4f, 4f),
            "R&B" to floatArrayOf(3f, 6f, 5f, 1f, -2f, -1f, 2f, 3f, 3f, 3f),
            "Metal" to floatArrayOf(4f, 3f, 1f, -1f, -2f, 0f, 3f, 5f, 6f, 5f),
            "Bollywood" to floatArrayOf(4f, 4f, 2f, 0f, 1f, 3f, 3f, 2f, 3f, 3f),
            "Bhangra" to floatArrayOf(7f, 6f, 3f, 0f, -1f, 1f, 2f, 3f, 4f, 3f),
            "Indian Classical" to floatArrayOf(1f, 1f, 1f, 2f, 3f, 3f, 2f, 2f, 3f, 2f),
            "Sufi & Ghazal" to floatArrayOf(2f, 2f, 1f, 2f, 4f, 4f, 3f, 1f, 1f, 0f),
            "Devotional" to floatArrayOf(1f, 1f, 2f, 3f, 4f, 4f, 3f, 1f, 0f, -1f),
            "Lo-fi" to floatArrayOf(4f, 3f, 2f, 1f, 0f, -1f, -2f, -3f, -4f, -5f),
            "Piano" to floatArrayOf(0f, 0f, 1f, 2f, 2f, 1f, 2f, 3f, 2f, 1f),
            "Deep Bass" to floatArrayOf(9f, 8f, 5f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Podcast" to floatArrayOf(-6f, -4f, -1f, 2f, 4f, 4f, 3f, 1f, -1f, -3f),
            "Gaming" to floatArrayOf(2f, 1f, 0f, -1f, 0f, 2f, 4f, 4f, 3f, 2f),
            "TV & Movies" to floatArrayOf(3f, 2f, 0f, 1f, 3f, 4f, 3f, 1f, 1f, 0f),
            "Car" to floatArrayOf(4f, 3f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 3f),
            "Small Speakers" to floatArrayOf(-4f, -2f, 2f, 3f, 3f, 2f, 1f, 1f, 0f, -1f),
        )

        /** Built-in presets grouped for the Sound screen; device and saved presets get their own groups. */
        val CATEGORIES: List<Pair<String, List<String>>> = listOf(
            "Popular" to listOf("Flat", "Pop", "Rock", "Hip-Hop", "Electronic", "Dance", "R&B", "Metal"),
            "Indian" to listOf("Bollywood", "Bhangra", "Indian Classical", "Sufi & Ghazal", "Devotional"),
            "Chill" to listOf("Jazz", "Classical", "Acoustic", "Piano", "Lo-fi"),
            "Tone" to listOf("Bass Boost", "Deep Bass", "Treble", "Vocal", "Loudness"),
            "Situations" to listOf("Late Night", "Podcast", "Gaming", "TV & Movies", "Car", "Small Speakers"),
        )
    }
}
