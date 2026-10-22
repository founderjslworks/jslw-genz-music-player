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

        slider(R.id.eqBass, Prefs.bassStrength) { Prefs.bassStrength = it }
        slider(R.id.eqVirt, Prefs.virtStrength) { Prefs.virtStrength = it }
        slider(R.id.eqLoud, Prefs.loudness) { Prefs.loudness = it }

        val reverb = findViewById<ChipGroup>(R.id.eqReverb)
        reverb.removeAllViews()
        AudioFx.REVERBS.forEachIndexed { i, n ->
            val c = Chip(this)
            c.text = n
            c.isCheckable = true
            c.id = View.generateViewId()
            SkinPainter.styleChip(c)
            c.isChecked = Prefs.reverb == i
            c.setOnClickListener { Prefs.reverb = i; svc.effectsChanged() }
            reverb.addView(c)
        }
        setEnabled(Prefs.eqEnabled)
        SkinPainter.apply(findViewById(R.id.root))
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

    private fun slider(id: Int, value: Int, set: (Int) -> Unit) {
        val s = findViewById<Slider>(id)
        s.value = value.coerceIn(0, 1000).toFloat()
        s.addOnChangeListener { _, v, fromUser -> if (fromUser) { set(v.toInt()); svc.effectsChanged() } }
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
