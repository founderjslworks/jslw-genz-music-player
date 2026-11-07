package com.jslw.genz.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.jslw.genz.R
import com.jslw.genz.audio.SoundEngine
import com.jslw.genz.data.Prefs
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.util.Perf

/**
 * The JSLW Sound Engine part of the Sound screen: profile, output and six tuning sliders
 * (bass, treble, surround, dialogue, room, volume leveller).
 */
class SoundTuning(private val a: Activity, private val changed: () -> Unit) {
    private val sliders = LinkedHashMap<String, Pair<Slider, TextView>>()
    private lateinit var profiles: ChipGroup
    private lateinit var outputLine: TextView
    private var blurb: TextView? = null
    private var updating = false
    private val views = ArrayList<View>()

    private fun dp(v: Float) = v * a.resources.displayMetrics.density

    /** Profile chips, their description and the "Listening on" line. */
    fun profileViews(): List<View> {
        val out = ArrayList<View>()
        out += sectionTitle("Sound profile")
        val scroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        profiles = ChipGroup(a).apply { isSingleLine = true; isSingleSelection = true }
        scroll.addView(profiles)
        for (p in SoundEngine.PROFILES) {
            val c = chip(p.name, profiles)
            c.isChecked = Prefs.seProfile == p.id
            c.setOnClickListener { SoundEngine.applyProfile(p.id); syncSliders(); selectProfile(); changed() }
        }
        out += scroll
        blurb = text(SoundEngine.profile(Prefs.seProfile).blurb, R.dimen.text_12, "skin_text2")
        out += blurb!!
        outputLine = text("", R.dimen.text_12, "skin_accent_text", bold = true).apply { setPadding(0, dp(6f).toInt(), 0, 0) }
        out += outputLine
        val outs = ChipGroup(a).apply { isSingleLine = true; isSingleSelection = true }
        for ((id, name) in listOf("auto" to "Auto-detect", "headphones" to "Headphones", "speaker" to "Speaker", "soundbar" to "Soundbar mode")) {
            val c = chip(name, outs)
            if (id == "soundbar") { c.setChipIconResource(R.drawable.ic_soundbar); c.isChipIconVisible = true }
            c.isChecked = Prefs.seOutput == id
            c.setOnClickListener { Prefs.seOutput = id; showOutput(); changed() }
        }
        out += HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(outs) }
        showOutput()
        views += out
        return out
    }

    /** The six tuning sliders. */
    fun sliderViews(): List<View> {
        val out = ArrayList<View>()
        out += sectionTitle("Fine tuning")
        out += slider("bass", R.drawable.ic_eq_bass, "Bass", "Warm, deep low end", Prefs.seBass) { Prefs.seBass = it }
        out += slider("treble", R.drawable.ic_eq_treble, "Treble", "Sparkle and air", Prefs.seTreble) { Prefs.seTreble = it }
        out += slider("surround", R.drawable.ic_eq_surround, "Surround", "Wider, more spacious sound – works on Bluetooth too", Prefs.seSurround) { Prefs.seSurround = it }
        out += slider("dialogue", R.drawable.ic_eq_vocal, "Dialogue clarity", "Lifts voices and lyrics", Prefs.seDialogue) { Prefs.seDialogue = it }
        out += slider("room", R.drawable.ic_eq_hall, "Room", "A touch of real-room ambience", Prefs.seRoom) { Prefs.seRoom = it }
        out += slider("level", R.drawable.ic_eq_loudness, "Volume leveller", "Evens out loud and quiet songs", Prefs.seLeveller) { Prefs.seLeveller = it }
        views += out
        return out
    }

    fun showOutput() {
        if (!::outputLine.isInitialized) return
        val d = SoundEngine.detect(a)
        // show the connected device by its own name, e.g. the soundbar's model
        val name = SoundEngine.deviceName(a)?.let { "$it (${d.label.lowercase()})" } ?: d.label
        outputLine.text = if (Prefs.seOutput == "auto") "Listening on: $name" else "Listening on: $name · tuned for ${SoundEngine.output(a).label.lowercase()}"
    }

    fun setEnabled(on: Boolean) {
        views.forEach { it.animate().alpha(if (on) 1f else 0.45f).setDuration(200).start() }
        sliders.values.forEach { it.first.isEnabled = on }
        if (::profiles.isInitialized) for (i in 0 until profiles.childCount) profiles.getChildAt(i).isEnabled = on
    }

    private fun selectProfile() {
        val idx = SoundEngine.PROFILES.indexOfFirst { it.id == Prefs.seProfile }
        for (i in 0 until profiles.childCount) (profiles.getChildAt(i) as Chip).isChecked = i == idx
        blurb?.text = SoundEngine.profile(Prefs.seProfile).blurb
    }

    private fun syncSliders() {
        val target = mapOf("surround" to Prefs.seSurround, "dialogue" to Prefs.seDialogue, "bass" to Prefs.seBass,
            "treble" to Prefs.seTreble, "room" to Prefs.seRoom, "level" to Prefs.seLeveller)
        for ((k, pair) in sliders) {
            val s = pair.first
            val to = (target[k] ?: 0).toFloat()
            if (Perf.light) { updating = true; s.value = to; updating = false; continue }
            android.animation.ValueAnimator.ofFloat(s.value, to).apply {
                duration = 420
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { updating = true; s.value = Math.round(it.animatedValue as Float).toFloat(); updating = false }
                start()
            }
        }
    }

    // ---------------------------------------------------------------- building blocks
    private fun text(s: String, size: Int, skinTag: String, bold: Boolean = false) = TextView(a).apply {
        text = s
        tag = skinTag
        setTextSize(TypedValue.COMPLEX_UNIT_PX, a.resources.getDimension(size))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun sectionTitle(s: String) = text(s.uppercase(), R.dimen.text_12, "skin_accent_text", bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(2f).toInt(), dp(20f).toInt(), 0, dp(6f).toInt())
    }

    private fun chip(name: String, group: ChipGroup) = Chip(a).apply {
        text = name
        isCheckable = true
        id = View.generateViewId()
        SkinPainter.styleChip(this)
        Sheets.pressFeedback(this)
        group.addView(this)
    }

    private fun slider(key: String, icon: Int, title: String, sub: String, value: Int, set: (Int) -> Unit): View {
        val sk = SkinManager.current
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            tag = "skin_surface_round"
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(2f).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10f).toInt() }
        }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(a).apply {
            setImageResource(icon)
            setColorFilter(0xFFFFFFFF.toInt())
            val p = dp(9f).toInt(); setPadding(p, p, p, p)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(sk.header, sk.headerEnd)).apply { cornerRadius = dp(12f) }
            layoutParams = LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt())
        })
        val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f).toInt(), 0, 0, 0) }
        t.addView(text(title, R.dimen.text_15, "skin_text", bold = true))
        t.addView(text(sub, R.dimen.text_12, "skin_text2"))
        row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val v = text("$value%", R.dimen.text_15, "skin_accent_text", bold = true)
        row.addView(v)
        card.addView(row)
        val s = Slider(a).apply {
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
        card.addView(s)
        sliders[key] = s to v
        return card
    }
}
