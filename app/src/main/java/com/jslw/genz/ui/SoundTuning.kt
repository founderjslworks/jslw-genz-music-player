package com.jslw.genz.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
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
 * The JSLW Sound Engine part of the Sound screen, laid out by category:
 * Sound profile (icon tiles) · Tune for (output) · Room (icon tiles) · Fine tuning (one compact, collapsible card).
 */
class SoundTuning(private val a: Activity, private val changed: () -> Unit) {
    private val sliders = LinkedHashMap<String, Pair<Slider, TextView>>()
    private val profileTiles = LinkedHashMap<String, View>()
    private val roomTiles = LinkedHashMap<String, View>()
    private lateinit var outputLine: TextView
    private var blurb: TextView? = null
    private var summary: TextView? = null
    private var updating = false
    private val views = ArrayList<View>()
    private val sk get() = SkinManager.current

    private fun dp(v: Float) = v * a.resources.displayMetrics.density

    /** Profile tiles, output chips and room tiles. */
    fun profileViews(): List<View> {
        val out = ArrayList<View>()
        out += sectionTitle("Sound profile", R.drawable.ic_se_music)
        out += tiles(SoundEngine.PROFILES.map { Triple(it.id, it.name, profileIcon(it.id)) }, Prefs.seProfile, profileTiles) { id ->
            SoundEngine.applyProfile(id); syncSliders(); selectProfile(); changed()
        }
        blurb = text(SoundEngine.profile(Prefs.seProfile).blurb, R.dimen.text_12, "skin_text2").apply { setPadding(dp(2f).toInt(), dp(4f).toInt(), 0, 0) }
        out += blurb!!

        out += sectionTitle("Tune for", R.drawable.ic_bt_link)
        val outs = ChipGroup(a).apply { isSingleLine = true; isSingleSelection = true; isSelectionRequired = true }
        for ((id, name, ic) in listOf(
            Triple("auto", "Auto-detect", R.drawable.ic_room_auto), Triple("headphones", "Headphones", R.drawable.ic_headphones),
            Triple("speaker", "Speaker", R.drawable.ic_speaker_small), Triple("soundbar", "Soundbar mode", R.drawable.ic_soundbar),
        )) {
            val c = chip(name, outs)
            c.setChipIconResource(ic); c.isChipIconVisible = true; c.chipIconSize = dp(18f); c.chipIconTint = c.textColors
            c.isChecked = Prefs.seOutput == id
            c.setOnClickListener { Prefs.seOutput = id; showOutput(); changed() }
        }
        out += HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(outs) }
        outputLine = text("", R.dimen.text_12, "skin_accent_text", bold = true).apply { setPadding(dp(2f).toInt(), dp(2f).toInt(), 0, 0) }
        out += outputLine
        showOutput()

        out += sectionTitle("Room", R.drawable.ic_eq_hall)
        out += tiles(SoundEngine.ROOMS.map { Triple(it.id, it.name, roomIcon(it.id)) }, Prefs.seRoomType, roomTiles) { id ->
            SoundEngine.applyRoom(id); syncSliders(); changed()
        }
        views += out
        return out
    }

    /** One compact card with the six sliders, collapsed to a one-line summary until opened. */
    fun sliderViews(): List<View> {
        val out = ArrayList<View>()
        out += sectionTitle("Fine tuning", R.drawable.ic_room_studio)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            tag = "skin_surface_round"
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(10f).toInt())
        }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconTile(R.drawable.ic_room_studio, 36f, true))
        val ht = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f).toInt(), 0, 0, 0) }
        ht.addView(text("Bass, treble, surround & more", R.dimen.text_15, "skin_text", bold = true))
        summary = text("", R.dimen.text_12, "skin_text2")
        ht.addView(summary)
        head.addView(ht, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val chevron = ImageView(a).apply { setImageResource(R.drawable.ic_expand_more); setColorFilter(sk.textSecondary) }
        head.addView(chevron, LinearLayout.LayoutParams(dp(24f).toInt(), dp(24f).toInt()))
        card.addView(head)
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = if (expanded) View.VISIBLE else View.GONE }
        chevron.rotation = if (expanded) 180f else 0f
        body.addView(slider("bass", R.drawable.ic_eq_bass, "Bass", Prefs.seBass) { Prefs.seBass = it })
        body.addView(slider("treble", R.drawable.ic_eq_treble, "Treble", Prefs.seTreble) { Prefs.seTreble = it })
        body.addView(slider("surround", R.drawable.ic_eq_surround, "Surround", Prefs.seSurround) { Prefs.seSurround = it })
        body.addView(slider("dialogue", R.drawable.ic_eq_vocal, "Dialogue clarity", Prefs.seDialogue) { Prefs.seDialogue = it })
        body.addView(slider("room", R.drawable.ic_eq_hall, "Room amount", Prefs.seRoom) {
            Prefs.seRoom = it
            if (it > 0 && Prefs.seRoomType == "off") { Prefs.seRoomType = "auto"; selectTile(roomTiles, "auto") }
        })
        body.addView(slider("level", R.drawable.ic_eq_loudness, "Volume leveller", Prefs.seLeveller) { Prefs.seLeveller = it })
        card.addView(body)
        head.setOnClickListener {
            expanded = !expanded
            chevron.animate().rotation(if (expanded) 180f else 0f).setDuration(220).start()
            if (expanded) {
                body.visibility = View.VISIBLE; body.alpha = 0f; body.translationY = -dp(8f)
                body.animate().alpha(1f).translationY(0f).setDuration(260).start()
            } else body.visibility = View.GONE
        }
        updateSummary()
        out += card
        views += out
        return out
    }

    fun showOutput() {
        if (!::outputLine.isInitialized) return
        val d = SoundEngine.detect(a)
        val name = SoundEngine.deviceName(a)?.let { "$it (${d.label.lowercase()})" } ?: d.label
        outputLine.text = if (Prefs.seOutput == "auto") "Listening on: $name" else "Listening on: $name · tuned for ${SoundEngine.output(a).label.lowercase()}"
    }

    fun setEnabled(on: Boolean) {
        views.forEach { it.animate().alpha(if (on) 1f else 0.45f).setDuration(200).start() }
        sliders.values.forEach { it.first.isEnabled = on }
        (profileTiles.values + roomTiles.values).forEach { it.isEnabled = on }
    }

    private fun selectProfile() {
        selectTile(profileTiles, Prefs.seProfile)
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
        updateSummary()
    }

    private fun updateSummary() {
        summary?.text = "Bass ${Prefs.seBass} · Treble ${Prefs.seTreble} · Surround ${Prefs.seSurround} · Voice ${Prefs.seDialogue} · Room ${Prefs.seRoom} · Level ${Prefs.seLeveller}"
    }

    // ---------------------------------------------------------------- icons
    private fun profileIcon(id: String) = when (id) {
        "music" -> R.drawable.ic_se_music; "movie" -> R.drawable.ic_se_movie; "game" -> R.drawable.ic_se_game
        "voice" -> R.drawable.ic_eq_vocal; "night" -> R.drawable.ic_eq_night; else -> R.drawable.ic_eq_custom
    }

    private fun roomIcon(id: String) = when (id) {
        "auto" -> R.drawable.ic_room_auto; "off" -> R.drawable.ic_eq_none; "bedroom" -> R.drawable.ic_room_bed
        "living" -> R.drawable.ic_room_sofa; "studio" -> R.drawable.ic_room_studio; "theatre" -> R.drawable.ic_room_theatre
        "club" -> R.drawable.ic_room_club; "hall" -> R.drawable.ic_eq_hall; else -> R.drawable.ic_room_stadium
    }

    // ---------------------------------------------------------------- building blocks
    private fun text(s: String, size: Int, skinTag: String, bold: Boolean = false) = TextView(a).apply {
        text = s
        tag = skinTag
        setTextSize(TypedValue.COMPLEX_UNIT_PX, a.resources.getDimension(size))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun sectionTitle(s: String, icon: Int) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2f).toInt(), dp(20f).toInt(), 0, dp(8f).toInt())
        addView(ImageView(a).apply { setImageResource(icon); setColorFilter(sk.accent) }, LinearLayout.LayoutParams(dp(16f).toInt(), dp(16f).toInt()))
        addView(text(s.uppercase(), R.dimen.text_12, "skin_accent_text", bold = true).apply {
            letterSpacing = 0.08f; setPadding(dp(8f).toInt(), 0, 0, 0)
        })
    }

    /** Rounded gradient tile with a white glyph – the premium icon style used across the Sound screen. */
    private fun iconTile(icon: Int, size: Float, on: Boolean) = ImageView(a).apply {
        setImageResource(icon)
        val p = (size * 0.24f).let { dp(it).toInt() }; setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(size).toInt(), dp(size).toInt())
        paintTile(this, on)
    }

    private fun paintTile(iv: ImageView, on: Boolean) {
        iv.background = if (on) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(sk.header, sk.headerEnd)).apply { cornerRadius = dp(14f) }
        else GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(ColorUtils.blendARGB(sk.surface, sk.accent, 0.10f))
            setStroke(dp(1f).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(sk.accent, 0x40))
        }
        iv.setColorFilter(if (on) 0xFFFFFFFF.toInt() else sk.accent)
    }

    private fun tiles(items: List<Triple<String, String, Int>>, selected: String, into: MutableMap<String, View>, pick: (String) -> Unit): View {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for ((id, name, ic) in items) {
            val t = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(2f).toInt())
                layoutParams = LinearLayout.LayoutParams(dp(74f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                isClickable = true; isFocusable = true
                contentDescription = name
            }
            t.addView(iconTile(ic, 50f, id == selected))
            t.addView(text(name, R.dimen.text_11, "skin_text").apply {
                tag = null
                gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(6f).toInt(), 0, 0)
                setTextColor(if (id == selected) sk.text else sk.textSecondary)
                setTypeface(sk.typeface, if (id == selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            })
            t.setOnClickListener { selectTile(into, id); pick(id) }
            Sheets.pressFeedback(t)
            into[id] = t
            row.addView(t)
        }
        return HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun selectTile(map: Map<String, View>, id: String) {
        for ((k, v) in map) {
            val g = v as LinearLayout
            val on = k == id
            val iv = g.getChildAt(0) as ImageView
            val tv = g.getChildAt(1) as TextView
            paintTile(iv, on)
            tv.setTextColor(if (on) sk.text else sk.textSecondary)
            tv.setTypeface(sk.typeface, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (on && !Perf.light) {
                iv.scaleX = 0.82f; iv.scaleY = 0.82f
                iv.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
            }
        }
    }

    private fun chip(name: String, group: ChipGroup) = Chip(a).apply {
        text = name
        isCheckable = true
        id = View.generateViewId()
        SkinPainter.styleChip(this)
        Sheets.pressFeedback(this)
        group.addView(this)
    }

    /** Compact slider row: small icon, name, value, then the slider. */
    private fun slider(key: String, icon: Int, title: String, value: Int, set: (Int) -> Unit): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12f).toInt(), 0, 0) }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(iconTile(icon, 28f, false))
        row.addView(text(title, R.dimen.text_14, "skin_text", bold = true).apply { setPadding(dp(10f).toInt(), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val v = text("$value%", R.dimen.text_14, "skin_accent_text", bold = true)
        row.addView(v)
        box.addView(row)
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
                updateSummary()
                changed()
            }
        }
        box.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        sliders[key] = s to v
        return box
    }

    companion object { private var expanded = false }
}
