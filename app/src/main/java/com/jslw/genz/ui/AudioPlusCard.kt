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
import com.jslw.genz.util.Touch
import com.jslworks.soundengine.dsp.SoundPresets

/**
 * "JSL Audio+" at the top of the Sound screen – presets first: tap a sound preset and enjoy.
 * Room and intensity sit under "Customize"; the 10-band equalizer below is the fine-tune and runs inside Audio+.
 * [onMode] tells the screen to hide the 1.8 effects that Audio+ replaces (bass boost, 3D, loudness, reverb, EQ presets).
 */
object AudioPlusCard {

    @SuppressLint("ClickableViewAccessibility")
    fun create(a: Activity, svc: MusicService, onMode: (Boolean) -> Unit): View {
        val d = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        fun text(s: String, size: Float, bold: Boolean = false) = TextView(a).apply {
            text = s; textSize = size; tag = "skin_text"
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        fun label(s: String) = text(s, 14f, true).apply { setPadding(0, px(14), 0, px(4)) }
        fun note(s: String) = text(s, 12.5f).apply { alpha = 0.75f; setPadding(0, px(4), 0, 0) }

        AudioPlus.changed(a)   // fresh output detection for the labels below

        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, px(18)) }

        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("JSL Audio+", 18f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = SwitchCompat(a)
        head.addView(sw)
        box.addView(head)
        box.addView(text(
            if (AudioPlus.supported) "Tap a sound preset and enjoy. Fine-tune only if you want."
            else "JSL Audio+ needs Android 5.0 or newer.", 13f
        ).apply { alpha = 0.85f })

        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(body)

        // ---- output + tips
        val output = note("").apply { alpha = 0.9f }
        body.addView(output)
        fun showOutput() {
            output.text = "${if (AudioPlus.speaker) "🔊" else "🎧"} ${AudioPlus.outputName} – your preset is remembered for this device"
        }
        showOutput()
        if (AudioPlus.bluetooth) body.addView(note("Tip: keep your earphones' own EQ on Default / Balanced, so two equalizers don't fight."))
        AudioPlus.phoneEffects()?.let { body.addView(note("This phone also has $it. If the sound feels too strong or thin, switch $it off while using Audio+.")) }

        // ---- presets
        body.addView(label("Sound presets"))
        val desc = note("").apply { alpha = 0.85f }
        val presets = ChipGroup(a).apply { isSingleSelection = true; isSelectionRequired = true }
        fun showDesc() { desc.text = AudioPlus.mood().description }
        for (m in SoundPresets.MOODS) {
            val c = Chip(a)
            c.text = m.name
            c.isCheckable = true
            c.id = View.generateViewId()
            SkinPainter.styleChip(c)
            c.isChecked = Prefs.apMood == m.id
            Touch.spring(c)
            c.setOnClickListener {
                Touch.confirm(c)
                Prefs.apMood = m.id
                Prefs.apRoom = "mood"          // a preset is complete: it brings its own room
                Prefs.apIntensity = 100
                AudioPlus.rememberForDevice()
                showDesc()
                svc.audioPlusChanged()
                a.recreateCustomize()
            }
            presets.addView(c)
        }
        body.addView(presets)
        body.addView(desc)
        showDesc()

        // ---- compare
        val compare = MaterialButton(a).apply {
            text = "Hold to hear the original"
            setIconResource(R.drawable.ic_play)
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { Touch.tick(this); animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start(); AudioPlus.setCompare(true); text = "Original sound…" }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        Touch.tick(this)
                        animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                        AudioPlus.setCompare(false); text = "Hold to hear the original"
                    }
                }
                false
            }
        }
        body.addView(compare, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })

        // ---- customize (collapsed by default)
        val toggle = MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        body.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
        val custom = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(custom)

        val rooms = ChipGroup(a).apply { isSingleSelection = true; isSelectionRequired = true }
        val intLabel = label("")
        val slider = Slider(a).apply { valueFrom = 0f; valueTo = 150f; stepSize = 10f }
        custom.addView(label("Room"))
        custom.addView(rooms)
        custom.addView(note("Rooms are modelled from their size and walls (reverberation time shown)."))
        custom.addView(intLabel)
        custom.addView(slider)
        custom.addView(note("Your equalizer curve below is applied inside Audio+, and the volume is matched to the original song."))

        val roomChoices = listOf("mood" to "Preset's room", "off" to "No room") +
            SoundPresets.ROOMS.map { it.id to "${it.name} · ${"%.1f".format(it.rt60)} s" }
        val roomChips = roomChoices.map { (id, name) ->
            Chip(a).apply {
                text = name; isCheckable = true; this.id = View.generateViewId()
                SkinPainter.styleChip(this)
                Touch.spring(this)
                setOnClickListener { Touch.confirm(this); Prefs.apRoom = id; AudioPlus.rememberForDevice(); svc.audioPlusChanged() }
                rooms.addView(this)
            } to id
        }
        slider.addOnChangeListener { _, v, fromUser ->
            intLabel.text = "Intensity · ${v.toInt()}%"
            if (fromUser) { Touch.tick(slider); Prefs.apIntensity = v.toInt(); AudioPlus.rememberForDevice(); svc.audioPlusChanged() }
        }
        fun syncCustom() {
            for ((chip, id) in roomChips) chip.isChecked = Prefs.apRoom == id
            slider.value = (Prefs.apIntensity.coerceIn(0, 150) / 10 * 10).toFloat()
            intLabel.text = "Intensity · ${slider.value.toInt()}%"
        }
        customizeSync = ::syncCustom
        syncCustom()
        fun showCustom() {
            val open = Prefs.apCustomOpen
            custom.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "Customize ▴" else "Customize ▾"
        }
        toggle.setOnClickListener {
            Touch.tick(toggle)
            android.transition.TransitionManager.beginDelayedTransition(box, android.transition.AutoTransition().setDuration(220))
            Prefs.apCustomOpen = !Prefs.apCustomOpen
            showCustom()
        }
        showCustom()

        fun setOn(on: Boolean) {
            body.visibility = if (on) View.VISIBLE else View.GONE
            onMode(on)
        }
        sw.isEnabled = AudioPlus.supported
        sw.isChecked = AudioPlus.supported && Prefs.apOn
        setOn(sw.isChecked)
        sw.setOnCheckedChangeListener { _, on ->
            Touch.confirm(sw)
            android.transition.TransitionManager.beginDelayedTransition(box, android.transition.AutoTransition().setDuration(240))
            Prefs.apOn = on
            setOn(on)
            svc.audioPlusToggled()
            showOutput()
        }
        return box
    }

    private var customizeSync: (() -> Unit)? = null

    /** A preset resets room + intensity: refresh the Customize controls. */
    private fun Activity.recreateCustomize() { customizeSync?.invoke() }
}
