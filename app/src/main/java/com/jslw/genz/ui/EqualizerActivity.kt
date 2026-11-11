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

/**
 * Sound: one switch for everything – JSLW Sound Engine profile and output, the graphic equalizer
 * (10-band on Android 9+) with presets, and six tuning sliders.
 */
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
        header("Sound")
        val strip = com.jslw.genz.ui.views.DeviceStrip(this, skin.text, skin.accent)
        val contentBox = findViewById<View>(R.id.eqGraph).parent as LinearLayout
        contentBox.addView(strip, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() })
        bandsBox = findViewById(R.id.eqBands)
        curve = findViewById(R.id.eqCurve)
        presetGroup = findViewById(R.id.eqPresets)
        Player.with(this) { s -> svc = s; fx = s.fx; build() }
    }

    private var tuning: SoundTuning? = null
    private var classicWired = false
    private var engineOpen = false

    /**
     * The JSLW Sound Engine as one optional card at the end: its own switch, and its options (profile, output,
     * room, fine tuning) folded away until opened, so the screen stays simple.
     */
    private fun engineCard(t: SoundTuning): View {
        val d = resources.displayMetrics.density
        val sk = com.jslw.genz.skin.SkinManager.current
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "skin_surface_round"
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (14 * d).toInt() }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_eq_surround); setColorFilter(0xFFFFFFFF.toInt())
            val p = (9 * d).toInt(); setPadding(p, p, p, p)
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(sk.header, sk.headerEnd)).apply { cornerRadius = 12 * d }
        }
        head.addView(icon, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * d).toInt(), 0, (8 * d).toInt(), 0) }
        col.addView(TextView(this).apply { text = "JSLW Sound Engine"; tag = "skin_text"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(TextView(this).apply {
            text = "Optional extras for Bluetooth, soundbars and films. Leave it off for the classic GEN-Z sound."
            tag = "skin_text2"; textSize = 12f
        })
        head.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = SwitchCompat(this).apply { isChecked = Prefs.seOn; tag = "skin_switch" }
        head.addView(sw)
        card.addView(head)
        val more = TextView(this).apply {
            tag = "skin_accent_text"; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
        }
        card.addView(more)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (v in t.profileViews()) body.addView(v)
        for (v in t.sliderViews()) body.addView(v)
        card.addView(body)
        fun show() {
            body.visibility = if (engineOpen && Prefs.seOn) View.VISIBLE else View.GONE
            more.visibility = if (Prefs.seOn) View.VISIBLE else View.GONE
            more.text = if (engineOpen) "Hide engine settings ▴" else "Engine settings ▾"
        }
        more.setOnClickListener {
            engineOpen = !engineOpen; show()
            if (engineOpen && !com.jslw.genz.util.Perf.light) { body.alpha = 0f; body.animate().alpha(1f).setDuration(240).start() }
        }
        sw.setOnCheckedChangeListener { _, on ->
            Prefs.seOn = on; svc.effectsChanged(); t.setEnabled(on)
            if (on) engineOpen = true
            show()
        }
        t.setEnabled(Prefs.seOn)
        show()
        return card
    }

    private fun build() {
        // 1.9.3: back to the classic GEN-Z sound. The equalizer and its effects work on their own again (as in 1.8);
        // the JSLW Sound Engine is an optional extra with its own switch, off unless you turn it on.
        if (!Prefs.classicSound) {
            Prefs.seOn = false
            Prefs.classicSound = true
            svc.effectsChanged()
        }
        val enable = findViewById<SwitchCompat>(R.id.eqEnable)
        val info = findViewById<TextView>(R.id.eqInfo)
        val note = findViewById<TextView>(R.id.eqNote)
        if (!fx.available) {
            info.text = "This device doesn't provide audio effects to apps."
            enable.isEnabled = false
        } else info.text = if (fx.tenBand) "10-band graphic equalizer · bass boost · 3D surround · loudness · reverb" else "${fx.bandFreqs.size}-band equalizer (device) · bass boost · 3D surround · loudness · reverb"
        note.text = buildString {
            append("Works on the music GEN-Z plays – wired and Bluetooth headphones, speakers, DSD and AIFF. ")
            append("Switched off, GEN-Z adds nothing to the sound, so your phone's own sound enhancement applies.")
            if (Prefs.bitPerfect) append("\nBit-perfect USB output is on: Android bypasses all effects in that mode.")
        }
        enable.isChecked = Prefs.eqEnabled
        enable.setOnCheckedChangeListener { _, on -> Prefs.eqEnabled = on; svc.effectsChanged(); setEnabled(on) }

        // the classic effect cards (bass boost, 3D surround, loudness, reverb) – as in GEN-Z 1.8
        if (!classicWired) {
            classicWired = true
            slider(R.id.eqBass, R.id.eqBassVal, Prefs.bassStrength) { Prefs.bassStrength = it }
            slider(R.id.eqVirt, R.id.eqVirtVal, Prefs.virtStrength) { Prefs.virtStrength = it }
            slider(R.id.eqLoud, R.id.eqLoudVal, Prefs.loudness) { Prefs.loudness = it }
        }
        if (tuning == null) {
            val t = SoundTuning(this) { svc.effectsChanged() }
            tuning = t
            val content = findViewById<View>(R.id.eqGraph).parent as LinearLayout
            content.addView(engineCard(t), content.indexOfChild(findViewById(R.id.eqNote)))
        }

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
                category = "Mine"
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
            "bollywood" in n || "tv" in n -> R.drawable.ic_se_movie
            "bhangra" in n || "indian" in n -> R.drawable.ic_cat_dhol
            "sufi" in n || "devotional" in n -> R.drawable.ic_diya
            "lo-fi" in n -> R.drawable.ic_cat_chill
            "piano" in n -> R.drawable.ic_piano
            "gaming" in n -> R.drawable.ic_se_game
            "car" == n -> R.drawable.ic_car
            "small" in n -> R.drawable.ic_speaker_small
            "podcast" in n -> R.drawable.ic_eq_vocal
            "r&b" in n -> R.drawable.ic_se_music
            "dance" in n -> R.drawable.ic_room_club
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

    private var category: String? = null
    private var catGroup: ChipGroup? = null

    private fun categoryIcon(c: String) = when (c) {
        "Popular" -> R.drawable.ic_star; "Indian" -> R.drawable.ic_cat_dhol; "Chill" -> R.drawable.ic_cat_chill
        "Tone" -> R.drawable.ic_room_studio; "Situations" -> R.drawable.ic_cat_scene; "Mine" -> R.drawable.ic_eq_custom
        else -> R.drawable.ic_eq_device
    }

    /** Presets sorted into categories: a row of category chips, then that category's presets. */
    private fun buildPresets() {
        presets = fx.presets()
        val mine = fx.customPresets().keys
        val catOf = presets.keys.associateWith { n ->
            AudioFx.CATEGORIES.firstOrNull { n in it.second }?.first ?: if (n in mine) "Mine" else "Device"
        }
        val cats = AudioFx.CATEGORIES.map { it.first } +
            listOfNotNull("Device".takeIf { "Device" in catOf.values }, "Mine".takeIf { "Mine" in catOf.values })
        if (category !in cats) category = catOf[Prefs.eqPreset]?.takeIf { it in cats } ?: cats.first()
        val cg = catGroup ?: ChipGroup(this).apply { isSingleLine = true; isSingleSelection = true; isSelectionRequired = true }.also { g ->
            catGroup = g
            val scroll = presetGroup.parent as View
            val box = scroll.parent as android.view.ViewGroup
            box.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false; addView(g)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(10f).toInt() }
            }, box.indexOfChild(scroll))
        }
        cg.removeAllViews()
        for (c in cats) {
            val chip = Chip(this)
            chip.text = c
            chip.isCheckable = true
            chip.id = View.generateViewId()
            SkinPainter.styleChip(chip)
            chipIcon(chip, categoryIcon(c))
            chip.isChecked = c == category
            chip.setOnClickListener { category = c; fillPresets(catOf) }
            Sheets.pressFeedback(chip)
            cg.addView(chip)
        }
        fillPresets(catOf)
    }

    private fun fillPresets(catOf: Map<String, String>) {
        presetGroup.removeAllViews()
        for ((name, vals) in presets) {
            if (catOf[name] != category) continue
            val c = Chip(this)
            c.text = name
            c.isCheckable = true
            c.id = View.generateViewId()
            SkinPainter.styleChip(c)
            chipIcon(c, presetIcon(name))
            c.isChecked = name == Prefs.eqPreset
            c.setOnClickListener { applyPreset(name, vals) }
            presetGroup.addView(c)
            if (!com.jslw.genz.util.Perf.light) {
                c.alpha = 0f; c.translationX = dp(16f)
                c.animate().alpha(1f).translationX(0f).setStartDelay(presetGroup.childCount * 30L).setDuration(260).start()
            }
        }
        (presetGroup.parent as? android.widget.HorizontalScrollView)?.scrollTo(0, 0)
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

    override fun onResume() {
        super.onResume()
        tuning?.showOutput()
    }

    private fun setEnabled(on: Boolean) {
        val a = if (on) 1f else 0.45f
        findViewById<View>(R.id.eqGraph).animate().alpha(a).setDuration(200).start()
        bands.forEach { it.isEnabled = on }
        // the classic effects follow the equalizer switch, as in 1.8
        for (id in listOf(R.id.eqBass, R.id.eqVirt, R.id.eqLoud)) {
            findViewById<View>(id).isEnabled = on
            (findViewById<View>(id).parent as View).animate().alpha(a).setDuration(200).start()
        }
        (findViewById<View>(R.id.eqReverb).parent as View).animate().alpha(a).setDuration(200).start()
    }
}
