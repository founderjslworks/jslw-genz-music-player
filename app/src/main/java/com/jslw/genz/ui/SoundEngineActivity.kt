package com.jslw.genz.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.jslw.genz.R
import com.jslw.genz.audio.SoundEngine
import com.jslw.genz.data.Prefs
import com.jslw.genz.playback.MusicService
import com.jslw.genz.playback.Player
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.util.Perf

/**
 * Advanced audio – the JSLW Sound Engine: listening profiles, output tuning (wired, Bluetooth, speaker),
 * surround, dialogue clarity, bass, treble, room and volume leveller.
 */
class SoundEngineActivity : BaseActivity() {
    private lateinit var box: LinearLayout
    private var svc: MusicService? = null
    private val sliders = LinkedHashMap<String, Pair<Slider, TextView>>()
    private lateinit var profiles: ChipGroup
    private lateinit var outputLine: TextView
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sound_engine)
        setupScreen(findViewById(R.id.root))
        header("Advanced audio")
        box = findViewById(R.id.seBox)
        Player.with(this) { s -> svc = s }
        build()
    }

    override fun onResume() {
        super.onResume()
        if (::outputLine.isInitialized) showOutput()
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)
    private fun sp(id: Int) = resources.getDimension(id)

    private fun changed() { svc?.effectsChanged() }

    private fun build() {
        val sk = SkinManager.current

        // ---- hero: title, switch
        val hero = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconTile(R.drawable.ic_eq_surround, sk.header, sk.accent, 46f))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f).toInt(), 0, 0, 0) }
        titles.addView(text("JSLW Sound Engine", R.dimen.text_18, "skin_text", bold = true))
        titles.addView(text("Cinema-style sound for every output – wired, Bluetooth and speaker", R.dimen.text_12, "skin_text2"))
        top.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = SwitchCompat(this).apply { isChecked = Prefs.seOn; tag = "skin_switch" }
        sw.setOnCheckedChangeListener { _, on -> Prefs.seOn = on; changed(); setEnabled(on) }
        top.addView(sw)
        hero.addView(top)
        outputLine = text("", R.dimen.text_13, "skin_accent_text", bold = true).apply { setPadding(0, dp(12f).toInt(), 0, 0) }
        hero.addView(outputLine)
        box.addView(hero)

        // ---- profile
        box.addView(sectionTitle("Profile"))
        profiles = chipRow(box)
        for (p in SoundEngine.PROFILES) {
            val c = chip(p.name, profiles)
            c.isChecked = Prefs.seProfile == p.id
            c.setOnClickListener {
                SoundEngine.applyProfile(p.id)
                syncSliders(animate = true)
                selectProfile()
                changed()
            }
        }
        val blurb = text(SoundEngine.profile(Prefs.seProfile).blurb, R.dimen.text_12, "skin_text2").apply { tag = "skin_text2" }
        blurb.id = View.generateViewId(); blurbView = blurb
        box.addView(blurb)

        // ---- output
        box.addView(sectionTitle("Tune for"))
        val outs = chipRow(box)
        for ((id, name) in listOf("auto" to "Automatic", "headphones" to "Headphones", "speaker" to "Speaker")) {
            val c = chip(name, outs)
            c.isChecked = Prefs.seOutput == id
            c.setOnClickListener { Prefs.seOutput = id; showOutput(); changed() }
        }

        // ---- sliders
        slider("surround", R.drawable.ic_eq_surround, "Surround", "How wide and spacious the sound feels", Prefs.seSurround) { Prefs.seSurround = it }
        slider("dialogue", R.drawable.ic_eq_vocal, "Dialogue clarity", "Lifts voices and lyrics above the mix", Prefs.seDialogue) { Prefs.seDialogue = it }
        slider("bass", R.drawable.ic_eq_bass, "Bass", "Warm, deep low end", Prefs.seBass) { Prefs.seBass = it }
        slider("treble", R.drawable.ic_eq_treble, "Treble", "Sparkle and air", Prefs.seTreble) { Prefs.seTreble = it }
        slider("room", R.drawable.ic_eq_hall, "Room", "A touch of real-room ambience", Prefs.seRoom) { Prefs.seRoom = it }
        slider("level", R.drawable.ic_eq_loudness, "Volume leveller", "Evens out loud and quiet songs and scenes", Prefs.seLeveller) { Prefs.seLeveller = it }

        // ---- notes
        val note = text(
            "Works on GEN-Z's music with any headphones – including Bluetooth – and on speakers. It runs inside GEN-Z, so it " +
                "doesn't need the phone's own sound features. When both this and the Equalizer are off, GEN-Z adds nothing to the " +
                "sound, and your phone's built-in sound enhancement applies as usual.",
            R.dimen.text_12, "skin_text2"
        ).apply { setPadding(dp(4f).toInt(), dp(18f).toInt(), dp(4f).toInt(), dp(8f).toInt()) }
        box.addView(note)

        SkinPainter.apply(findViewById(R.id.root))
        showOutput()
        setEnabled(Prefs.seOn)
        animateIn()
    }

    private var blurbView: TextView? = null

    private fun selectProfile() {
        val idx = SoundEngine.PROFILES.indexOfFirst { it.id == Prefs.seProfile }
        for (i in 0 until profiles.childCount) (profiles.getChildAt(i) as Chip).isChecked = i == idx
        blurbView?.text = SoundEngine.profile(Prefs.seProfile).blurb
    }

    private fun showOutput() {
        val detected = SoundEngine.detect(this)
        val tuned = SoundEngine.output(this)
        outputLine.text = if (Prefs.seOutput == "auto") "Listening on: ${detected.label}"
        else "Listening on: ${detected.label} · tuned for ${tuned.label.lowercase()}"
    }

    private fun setEnabled(on: Boolean) {
        for (i in 1 until box.childCount) {
            val v = box.getChildAt(i)
            v.animate().alpha(if (on) 1f else 0.45f).setDuration(200).start()
        }
        sliders.values.forEach { it.first.isEnabled = on }
        for (i in 0 until profiles.childCount) profiles.getChildAt(i).isEnabled = on
    }

    // ---------------------------------------------------------------- building blocks
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = "skin_surface_round"
        setPadding(dp(14f).toInt(), dp(14f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12f).toInt() }
    }

    private fun text(s: String, size: Int, skinTag: String, bold: Boolean = false) = TextView(this).apply {
        text = s
        tag = skinTag
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(size))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun sectionTitle(s: String) = text(s.uppercase(), R.dimen.text_12, "skin_accent_text", bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4f).toInt(), dp(20f).toInt(), 0, dp(6f).toInt())
    }

    private fun iconTile(icon: Int, c1: Int, c2: Int, size: Float) = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(0xFFFFFFFF.toInt())
        val p = (size * 0.22f)
        setPadding(dp(p).toInt(), dp(p).toInt(), dp(p).toInt(), dp(p).toInt())
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { cornerRadius = dp(size * 0.3f) }
        layoutParams = LinearLayout.LayoutParams(dp(size).toInt(), dp(size).toInt())
    }

    private fun chipRow(parent: LinearLayout): ChipGroup {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val g = ChipGroup(this).apply { isSingleLine = true; isSingleSelection = true }
        scroll.addView(g)
        parent.addView(scroll)
        return g
    }

    private fun chip(name: String, group: ChipGroup) = Chip(this).apply {
        text = name
        isCheckable = true
        id = View.generateViewId()
        SkinPainter.styleChip(this)
        Sheets.pressFeedback(this)
        group.addView(this)
    }

    private fun slider(key: String, icon: Int, title: String, sub: String, value: Int, set: (Int) -> Unit) {
        val sk = SkinManager.current
        val c = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(iconTile(icon, sk.header, sk.headerEnd, 40f))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f).toInt(), 0, 0, 0) }
        t.addView(text(title, R.dimen.text_15, "skin_text", bold = true))
        t.addView(text(sub, R.dimen.text_12, "skin_text2"))
        row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val v = text("$value%", R.dimen.text_15, "skin_accent_text", bold = true)
        row.addView(v)
        c.addView(row)
        val s = Slider(this).apply {
            valueFrom = 0f; valueTo = 100f; stepSize = 1f
            this.value = value.coerceIn(0, 100).toFloat()
            tag = "skin_slider"
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
        }
        s.addOnChangeListener { _, x, fromUser ->
            v.text = "${x.toInt()}%"
            if (fromUser && !updating) {
                set(x.toInt())
                if (Prefs.seProfile != "custom") { Prefs.seProfile = "custom"; selectProfile() }
                changed()
            }
        }
        c.addView(s)
        sliders[key] = s to v
        box.addView(c)
    }

    /** After choosing a profile the sliders glide to its values. */
    private fun syncSliders(animate: Boolean) {
        val target = mapOf(
            "surround" to Prefs.seSurround, "dialogue" to Prefs.seDialogue, "bass" to Prefs.seBass,
            "treble" to Prefs.seTreble, "room" to Prefs.seRoom, "level" to Prefs.seLeveller,
        )
        for ((k, pair) in sliders) {
            val (s, _) = pair
            val to = (target[k] ?: 0).toFloat()
            if (!animate || Perf.light) { updating = true; s.value = to; updating = false; continue }
            android.animation.ValueAnimator.ofFloat(s.value, to).apply {
                duration = 420
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { updating = true; s.value = Math.round(it.animatedValue as Float).toFloat(); updating = false }
                start()
            }
        }
    }

    private fun animateIn() {
        if (Perf.light) return
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i)
            v.alpha = 0f; v.translationY = dp(24f)
            v.animate().alpha(if (i == 0 || Prefs.seOn) 1f else 0.45f).translationY(0f)
                .setStartDelay(40L + i * 35L).setDuration(340).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
    }
}
