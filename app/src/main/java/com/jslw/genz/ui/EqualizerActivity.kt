package com.jslw.genz.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.jslw.genz.R
import com.jslw.genz.audio.AudioFx
import com.jslw.genz.data.Prefs
import com.jslw.genz.playback.MusicService
import com.jslw.genz.playback.Player
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.ui.views.EqBandView
import com.jslw.genz.ui.views.EqCurveView
import com.jslw.genz.util.toast

/** Graphic equalizer (10-band on Android 9+), presets, bass boost, 3D surround, loudness and reverb. */
class EqualizerActivity : BaseActivity() {
    private lateinit var fx: AudioFx
    private lateinit var svc: MusicService
    private lateinit var bandsBox: LinearLayout
    private lateinit var curve: EqCurveView
    private lateinit var presetGroup: ChipGroup
    private val bands = ArrayList<EqBandView>()
    private var levels = IntArray(0)
    private var presets = LinkedHashMap<String, IntArray>()
    private var settingPreset = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_equalizer)
        setupScreen(findViewById(R.id.root))
        header("Equalizer")
        bandsBox = findViewById(R.id.eqBands)
        curve = findViewById(R.id.eqCurve)
        presetGroup = findViewById(R.id.eqPresets)
        Player.with(this) { s -> svc = s; fx = s.fx; build() }
    }

    private fun build() {
        val enable = findViewById<SwitchCompat>(R.id.eqEnable)
        val info = findViewById<TextView>(R.id.eqInfo)
        val note = findViewById<TextView>(R.id.eqNote)
        if (!fx.available) {
            info.text = "This device doesn't provide audio effects to apps."
            enable.isEnabled = false
        } else info.text = if (fx.tenBand) "10-band graphic equalizer" else "${fx.bandFreqs.size}-band equalizer (device)"
        note.text = buildString {
            append("Effects work on the music GEN-Z plays, including DSD and AIFF.")
            if (Prefs.bitPerfect) append("\nBit-perfect USB output is on: Android bypasses all effects in that mode.")
        }
        enable.isChecked = Prefs.eqEnabled
        enable.setOnCheckedChangeListener { _, on -> Prefs.eqEnabled = on; svc.effectsChanged(); setEnabled(on) }

        // faders
        levels = fx.currentLevels()
        bandsBox.removeAllViews(); bands.clear()
        fx.bandFreqs.forEachIndexed { i, f ->
            val b = EqBandView(this)
            b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            b.min = fx.minLevel; b.max = fx.maxLevel
            b.value = levels[i]
            b.label = if (f >= 1000) "${f / 1000}k" else "$f"
            b.onChange = { v ->
                levels[i] = v
                curve.set(levels, fx.minLevel, fx.maxLevel, false)
                fx.setLevels(levels, "Custom")
                if (!settingPreset) selectPresetChip(null)
            }
            bands += b
            bandsBox.addView(b)
        }
        curve.set(levels, fx.minLevel, fx.maxLevel, false)

        buildPresets()

        findViewById<MaterialButton>(R.id.eqSave).setOnClickListener {
            Sheets.askText(this, "Save preset", "My sound", "Save") { name ->
                fx.saveCustom(name, levels)
                Prefs.eqPreset = name
                buildPresets()
                toast("Saved \"$name\"")
            }
        }
        findViewById<MaterialButton>(R.id.eqDelete).setOnClickListener {
            val custom = fx.customPresets().keys.toList()
            if (custom.isEmpty()) { toast("No custom presets yet"); return@setOnClickListener }
            MaterialAlertDialogBuilder(this).setTitle("Delete preset").setItems(custom.toTypedArray()) { _, w ->
                fx.deleteCustom(custom[w]); buildPresets()
            }.show()
        }
        findViewById<MaterialButton>(R.id.eqReset).setOnClickListener { applyPreset("Flat", IntArray(levels.size)) }

        slider(R.id.eqBass, R.id.eqBassVal, Prefs.bassStrength) { Prefs.bassStrength = it }
        slider(R.id.eqVirt, R.id.eqVirtVal, Prefs.virtStrength) { Prefs.virtStrength = it }
        slider(R.id.eqLoud, R.id.eqLoudVal, Prefs.loudness) { Prefs.loudness = it }

        val reverb = findViewById<ChipGroup>(R.id.eqReverb)
        reverb.removeAllViews()
        AudioFx.REVERBS.forEachIndexed { i, n ->
            val c = Chip(this)
            c.text = n
            c.isCheckable = true
            c.id = View.generateViewId()
            SkinPainter.styleChip(c)
            chipIcon(c, reverbIcon(n))
            c.isChecked = Prefs.reverb == i
            c.setOnClickListener { Prefs.reverb = i; svc.effectsChanged() }
            reverb.addView(c)
        }
        setEnabled(Prefs.eqEnabled)
        SkinPainter.apply(findViewById(R.id.root))
        decorate()
        animateIn()
    }

    /** Sections spring in one after another; the faders rise from 0 dB to their levels like a VU meter. */
    private fun animateIn() {
        val content = (findViewById<View>(R.id.eqGraph).parent as android.view.ViewGroup)
        com.jslw.genz.util.Stagger.enter(content)
        val target = levels.copyOf()
        bands.forEachIndexed { i, b ->
            b.value = 0
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = 260L + i * 55L
                duration = 650
                interpolator = android.view.animation.OvershootInterpolator(2.2f)
                addUpdateListener { a -> b.value = (target[i] * (a.animatedValue as Float)).toInt() }
                start()
            }
        }
        curve.set(IntArray(target.size), fx.minLevel, fx.maxLevel, false)
        curve.postDelayed({ curve.set(target, fx.minLevel, fx.maxLevel, true) }, 300)
        // chips pop in
        for (g in listOf(presetGroup, findViewById<ChipGroup>(R.id.eqReverb))) {
            for (k in 0 until g.childCount) {
                val c = g.getChildAt(k)
                c.scaleX = 0.6f; c.scaleY = 0.6f; c.alpha = 0f
                c.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(200L + k * 35L).setDuration(380)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }
        }
    }

    private fun slider(id: Int, valId: Int, value: Int, set: (Int) -> Unit) {
        val s = findViewById<Slider>(id)
        val label = findViewById<TextView>(valId)
        s.value = value.coerceIn(0, 1000).toFloat()
        label.text = "${value.coerceIn(0, 1000) / 10}%"
        s.addOnChangeListener { _, v, fromUser ->
            label.text = "${v.toInt() / 10}%"
            if (fromUser) { set(v.toInt()); svc.effectsChanged() }
        }
    }

    // ---------------------------------------------------------------- iconography
    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** Gradient icon tiles, guide icons and button icons, all in the active theme's colours. */
    private fun decorate() {
        val sk = com.jslw.genz.skin.SkinManager.current
        fun tile(id: Int, c1: Int, c2: Int) {
            val iv = findViewById<android.widget.ImageView>(id)
            iv.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)
            ).apply { cornerRadius = dp(12f) }
            iv.setColorFilter(0xFFFFFFFF.toInt())
        }
        tile(R.id.eqTitleIcon, sk.header, sk.headerEnd)
        tile(R.id.eqBassIcon, sk.header, sk.headerEnd)
        tile(R.id.eqVirtIcon, sk.accent, sk.header)
        tile(R.id.eqLoudIcon, sk.headerEnd, sk.accent)
        tile(R.id.eqReverbIcon, sk.header, sk.accent)
        for ((id, ic) in listOf(R.id.eqGuideLow to R.drawable.ic_eq_bass, R.id.eqGuideMid to R.drawable.ic_eq_mid, R.id.eqGuideHigh to R.drawable.ic_eq_treble)) {
            val tv = findViewById<TextView>(id)
            val d = androidx.appcompat.content.res.AppCompatResources.getDrawable(this, ic)?.mutate() ?: continue
            androidx.core.graphics.drawable.DrawableCompat.setTint(androidx.core.graphics.drawable.DrawableCompat.wrap(d), sk.accent)
            val sz = dp(16f).toInt(); d.setBounds(0, 0, sz, sz)
            tv.setCompoundDrawables(d, null, null, null)
        }
        for ((id, ic) in listOf(R.id.eqSave to R.drawable.ic_eq_save, R.id.eqDelete to R.drawable.ic_delete, R.id.eqReset to R.drawable.ic_eq_flat)) {
            val b = findViewById<MaterialButton>(id)
            b.setIconResource(ic)
            b.iconSize = dp(18f).toInt()
            b.iconPadding = dp(6f).toInt()
            b.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            b.iconTint = b.textColors
            b.maxLines = 1
        }
    }

    private fun chipIcon(c: Chip, res: Int) {
        c.setChipIconResource(res)
        c.isChipIconVisible = true
        c.chipIconSize = dp(18f)
        c.chipIconTint = c.textColors
        c.iconStartPadding = dp(4f)
    }

    private fun presetIcon(name: String): Int {
        val n = name.lowercase()
        return when {
            "flat" in n || "normal" in n -> R.drawable.ic_eq_flat
            "bass" in n -> R.drawable.ic_eq_bass
            "rock" in n || "metal" in n -> R.drawable.ic_eq_rock
            "pop" in n || "dance" in n -> R.drawable.ic_star
            "jazz" in n || "classical" in n || "folk" in n -> R.drawable.ic_note
            "vocal" in n || "voice" in n || "podcast" in n -> R.drawable.ic_eq_vocal
            "electr" in n || "edm" in n -> R.drawable.ic_eq_electronic
            "hip" in n || "rap" in n -> R.drawable.ic_eq_hiphop
            "acoustic" in n -> R.drawable.ic_eq_acoustic
            "treble" in n -> R.drawable.ic_eq_treble
            "loud" in n -> R.drawable.ic_eq_loudness
            "night" in n -> R.drawable.ic_eq_night
            "heavy" in n -> R.drawable.ic_eq_rock
            name in fx.customPresets().keys -> R.drawable.ic_eq_custom
            else -> R.drawable.ic_eq_device
        }
    }

    private fun reverbIcon(name: String): Int {
        val n = name.lowercase()
        return when {
            "none" in n || "off" in n -> R.drawable.ic_eq_none
            "hall" in n -> R.drawable.ic_eq_hall
            "plate" in n -> R.drawable.ic_eq_plate
            else -> R.drawable.ic_eq_reverb
        }
    }

    private fun buildPresets() {
        presets = fx.presets()
        presetGroup.removeAllViews()
        for ((name, vals) in presets) {
            val c = Chip(this)
            c.text = name
            c.isCheckable = true
            c.id = View.generateViewId()
            SkinPainter.styleChip(c)
            chipIcon(c, presetIcon(name))
            c.isChecked = name == Prefs.eqPreset
            c.setOnClickListener { applyPreset(name, vals) }
            presetGroup.addView(c)
        }
    }

    private fun selectPresetChip(name: String?) {
        for (i in 0 until presetGroup.childCount) {
            val c = presetGroup.getChildAt(i) as Chip
            c.isChecked = c.text.toString() == name
        }
    }

    private fun applyPreset(name: String, vals: IntArray) {
        settingPreset = true
        levels = vals.copyOf(levels.size)
        bands.forEachIndexed { i, b -> b.value = levels[i] }
        curve.set(levels, fx.minLevel, fx.maxLevel, true)
        fx.setLevels(levels, name)
        selectPresetChip(name)
        settingPreset = false
        if (!Prefs.eqEnabled) {
            Prefs.eqEnabled = true
            findViewById<SwitchCompat>(R.id.eqEnable).isChecked = true
            svc.effectsChanged()
        }
    }

    private fun setEnabled(on: Boolean) {
        val a = if (on) 1f else 0.45f
        findViewById<View>(R.id.eqGraph).animate().alpha(a).setDuration(200).start()
        bands.forEach { it.isEnabled = on }
    }
}
