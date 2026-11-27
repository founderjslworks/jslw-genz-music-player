package com.jslw.genz.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.jslw.genz.R
import com.jslw.genz.audio.AudioPlus
import com.jslw.genz.data.Prefs
import com.jslw.genz.playback.MusicService
import com.jslw.genz.skin.SkinPainter
import com.jslworks.soundengine.dsp.SoundPresets

/** "JSL Audio+" section at the top of the Sound screen: switch, moods, rooms, intensity and Compare. */
object AudioPlusCard {

    @SuppressLint("ClickableViewAccessibility")
    fun create(a: Activity, svc: MusicService): View {
        val d = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        fun text(s: String, size: Float, bold: Boolean = false) = TextView(a).apply {
            text = s; textSize = size; tag = "skin_text"
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        fun label(s: String) = text(s, 14f, true).apply { setPadding(0, px(14), 0, px(4)) }

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(18))
        }

        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("JSL Audio+", 18f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = SwitchCompat(a)
        head.addView(sw)
        box.addView(head)

        val sub = text(
            if (AudioPlus.supported) "Advanced audio by the JSL Audio Engine: moods, real room acoustics, Melody and bass. Works together with the equalizer below."
            else "JSL Audio+ needs Android 5.0 or newer.", 13f
        ).apply { alpha = 0.8f }
        box.addView(sub)

        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(body)

        // ---- moods
        body.addView(label("Mood"))
        val moodDesc = text("", 13f).apply { alpha = 0.85f }
        val moods = ChipGroup(a).apply { isSingleSelection = true }
        fun showMood() { moodDesc.text = AudioPlus.mood().description }
        for (m in SoundPresets.MOODS) {
            val c = Chip(a)
            c.text = m.name
            c.isCheckable = true
            c.id = View.generateViewId()
            SkinPainter.styleChip(c)
            c.isChecked = Prefs.apMood == m.id
            c.setOnClickListener { Prefs.apMood = m.id; showMood(); svc.audioPlusChanged() }
            moods.addView(c)
        }
        body.addView(moods)
        body.addView(moodDesc)
        showMood()

        // ---- rooms
        body.addView(label("Room"))
        val rooms = ChipGroup(a).apply { isSingleSelection = true }
        val roomChoices = listOf("mood" to "Mood's own", "off" to "Off") +
            SoundPresets.ROOMS.map { it.id to "${it.name} · ${"%.1f".format(it.rt60)} s" }
        for ((id, name) in roomChoices) {
            val c = Chip(a)
            c.text = name
            c.isCheckable = true
            c.id = View.generateViewId()
            SkinPainter.styleChip(c)
            c.isChecked = Prefs.apRoom == id
            c.setOnClickListener { Prefs.apRoom = id; svc.audioPlusChanged() }
            rooms.addView(c)
        }
        body.addView(rooms)
        body.addView(text("Each room is modelled from its size and walls (Sabine reverberation time shown).", 12f).apply { alpha = 0.7f })

        // ---- intensity
        val intLabel = label("Intensity · ${Prefs.apIntensity}%")
        body.addView(intLabel)
        val slider = Slider(a).apply {
            valueFrom = 0f; valueTo = 150f; stepSize = 10f
            value = (Prefs.apIntensity.coerceIn(0, 150) / 10 * 10).toFloat()
            addOnChangeListener { _, v, fromUser ->
                intLabel.text = "Intensity · ${v.toInt()}%"
                if (fromUser) { Prefs.apIntensity = v.toInt(); svc.audioPlusChanged() }
            }
        }
        body.addView(slider)

        // ---- compare (hold to hear the original)
        val compare = MaterialButton(a).apply {
            text = "Hold to hear the original"
            setIconResource(R.drawable.ic_play)
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { AudioPlus.setCompare(true); text = "Original sound…" }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { AudioPlus.setCompare(false); text = "Hold to hear the original" }
                }
                false
            }
        }
        body.addView(compare, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })

        body.addView(text("Moods are tuned to support a feeling with the right kind of music.", 12f).apply {
            alpha = 0.7f; setPadding(0, px(8), 0, 0)
        })

        fun setOn(on: Boolean) {
            body.alpha = if (on) 1f else 0.45f
            for (g in listOf(moods, rooms)) for (k in 0 until g.childCount) g.getChildAt(k).isEnabled = on
            slider.isEnabled = on
            compare.isEnabled = on
        }
        sw.isEnabled = AudioPlus.supported
        sw.isChecked = AudioPlus.supported && Prefs.apOn
        setOn(sw.isChecked)
        sw.setOnCheckedChangeListener { _, on ->
            Prefs.apOn = on
            setOn(on)
            svc.audioPlusToggled()
        }
        return box
    }
}
