package com.jslw.genz.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.jslw.genz.R
import com.jslw.genz.audio.AudioPlus
import com.jslw.genz.audio.AutoSound
import com.jslw.genz.data.Prefs
import com.jslw.genz.playback.MusicService
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.util.Touch
import com.jslworks.soundengine.dsp.SoundPresets

/**
 * "JSL Audio+" at the top of the Sound screen. Built for a personal experience, not a mixing desk:
 * pick a preset (or Auto) and enjoy; Stage modes are one tap each; room and intensity sit under Customize.
 * Every tile carries a small graphic that shows what it does (the preset's real curve, a room's echo, the stage).
 * [onMode] hides the 1.8 effects that Audio+ replaces (bass boost, 3D, loudness, reverb).
 */
object AudioPlusCard {

    private class Tile(val root: LinearLayout, val caption: TextView)

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun create(a: Activity, svc: MusicService, onMode: (Boolean) -> Unit): View {
        val sk = SkinManager.current
        val d = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        fun text(s: String, size: Float, bold: Boolean = false) = TextView(a).apply {
            text = s; textSize = size; tag = "skin_text"
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        fun label(s: String) = text(s, 14f, true).apply { setPadding(0, px(16), 0, px(6)) }
        fun note(s: String) = text(s, 12.5f).apply { alpha = 0.75f; setPadding(0, px(4), 0, 0) }
        val faint = ColorUtils.setAlphaComponent(sk.accent, 110)

        fun tile(art: FxArt, title: String, caption: String = ""): Tile {
            val t = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(px(10), px(10), px(10), px(8))
                isClickable = true; isFocusable = true
            }
            t.addView(ImageView(a).apply { setImageDrawable(art) }, LinearLayout.LayoutParams(px(84), px(46)))
            t.addView(text(title, 13.5f, true).apply { gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0) })
            val cap = text(caption, 11f).apply { gravity = Gravity.CENTER; alpha = 0.7f; visibility = if (caption.isEmpty()) View.GONE else View.VISIBLE }
            t.addView(cap)
            Touch.spring(t)
            return Tile(t, cap)
        }
        fun paint(t: Tile, selected: Boolean) {
            t.root.background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(if (selected) ColorUtils.setAlphaComponent(sk.accent, 70) else ColorUtils.setAlphaComponent(sk.text, 14))
                setStroke(px(if (selected) 2 else 1), if (selected) sk.accent else ColorUtils.setAlphaComponent(sk.text, 40))
            }
        }
        fun row(): Pair<HorizontalScrollView, LinearLayout> {
            val inner = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            val sc = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(inner) }
            return sc to inner
        }
        fun lp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(10) }

        AudioPlus.changed(a)

        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, px(18)) }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("JSL Audio+", 18f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = SwitchCompat(a)
        head.addView(sw)
        box.addView(head)
        box.addView(text(
            if (AudioPlus.supported) "Your personal sound, powered by the JSL Audio Engine. Pick a preset or let Auto choose – fine-tune only if you want."
            else "JSL Audio+ needs Android 5.0 or newer.", 13f
        ).apply { alpha = 0.85f })

        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(body)

        // ---- this device
        body.addView(label("${if (AudioPlus.speaker) "🔊" else "🎧"}  ${AudioPlus.outputName}"))
        val kinds = ChipGroup(a).apply { isSingleSelection = true; isSelectionRequired = true }
        for ((k, name) in listOf(AudioPlus.KIND_HEADPHONES to "Headphones", AudioPlus.KIND_SPEAKER to "Speaker", AudioPlus.KIND_CAR to "Car")) {
            val c = Chip(a)
            c.text = name; c.isCheckable = true; c.id = View.generateViewId()
            SkinPainter.styleChip(c); Touch.spring(c)
            c.isChecked = AudioPlus.kind == k
            c.setOnClickListener { Touch.confirm(c); AudioPlus.setKind(k) }
            kinds.addView(c)
        }
        body.addView(kinds)
        body.addView(note("Tell Audio+ what this is – speakers and cars get gentler bass so songs never boom or pump. Everything you choose is remembered for this device."))
        if (AudioPlus.bluetooth) body.addView(note("Tip: keep your earphones' own EQ on Default / Balanced, so two equalizers don't fight."))
        AudioPlus.phoneEffects()?.let { body.addView(note("This phone also has $it. If the sound feels too strong or thin, switch $it off while using Audio+.")) }

        // ---- presets (Auto first)
        body.addView(label("Sound presets"))
        val (presetScroll, presetRow) = row()
        body.addView(presetScroll)
        val desc = note("").apply { alpha = 0.85f }
        body.addView(desc)
        val presetTiles = ArrayList<Pair<Tile, String>>()
        fun showDesc() {
            desc.text = if (Prefs.apMood == AudioPlus.AUTO) {
                val t = AudioPlus.nowTag
                "Auto listens to each song (tempo, tone, loudness), picks the preset and corrects the tone – and remembers it. " +
                    if (t != null) "Now: ${AudioPlus.mood(t.moodId).name} · ${t.bpm.toInt()} BPM · ${t.tone}" else "Now: listening…"
            } else AudioPlus.mood().description
        }
        fun selectPreset() { for ((t, id) in presetTiles) paint(t, Prefs.apMood == id) }
        val presets = listOf<Pair<String, com.jslworks.soundengine.dsp.SoundPreset?>>(AudioPlus.AUTO to null) + SoundPresets.MOODS.map { it.id to it }
        for ((id, m) in presets) {
            val art = if (m == null) FxArt(FxArt.Kind.Auto, sk.accent, faint) else FxArt(FxArt.Kind.Curve(FxArt.presetCurve(m)), sk.accent, faint)
            val t = tile(art, m?.name ?: "Auto")
            t.root.setOnClickListener {
                Touch.confirm(t.root)
                Prefs.apMood = id; if (Prefs.apRoom != AutoSound.ROOM_AUTO) Prefs.apRoom = "mood"; Prefs.apIntensity = 100
                AudioPlus.rememberForDevice(); svc.audioPlusChanged()
                selectPreset(); showDesc(); customizeSync?.invoke()
            }
            presetRow.addView(t.root, lp())
            presetTiles += t to id
        }
        selectPreset(); showDesc()

        // ---- stage modes (one tap: Off → Medium → Strong)
        body.addView(label("Modes"))
        val (modeScroll, modeRow) = row()
        body.addView(modeScroll)
        fun level(v: Int) = when { v <= 0 -> "Off"; v < 75 -> "Medium"; else -> "Strong" }
        val modes = listOf<Triple<String, FxArt.Kind, Int>>(
            Triple("Sound Stage", FxArt.Kind.Stage, 0),
            Triple("Sound Separation", FxArt.Kind.Separation, 1),
            Triple("Instrument Separation", FxArt.Kind.Instruments, 2),
        )
        fun getMode(i: Int) = when (i) { 0 -> Prefs.apStage; 1 -> Prefs.apSep; else -> Prefs.apInst }
        fun setMode(i: Int, v: Int) { when (i) { 0 -> Prefs.apStage = v; 1 -> Prefs.apSep = v; else -> Prefs.apInst = v } }
        val modeTiles = ArrayList<Tile>()
        val autoModes = tile(FxArt(FxArt.Kind.Auto, sk.accent, faint), "Auto", "")
        fun liveMode(i: Int) = when (i) { 0 -> AutoSound.live.stage; 1 -> AutoSound.live.sep; else -> AutoSound.live.inst }
        modesSync = {
            val auto = Prefs.apModesAuto
            paint(autoModes, auto)
            autoModes.caption.text = if (auto) (if (AutoSound.live.listening) "listening…" else "follows the song") else "Off"
            modeTiles.forEachIndexed { i, t ->
                if (auto) { t.caption.text = "Auto · ${AutoSound.level(liveMode(i))}"; paint(t, false); t.root.alpha = 0.85f }
                else { t.caption.text = level(getMode(i)); paint(t, getMode(i) > 0); t.root.alpha = 1f }
            }
        }
        autoModes.root.setOnClickListener {
            Touch.confirm(autoModes.root)
            Prefs.apModesAuto = !Prefs.apModesAuto
            AudioPlus.rememberForDevice(); svc.audioPlusChanged()
            modesSync?.invoke()
        }
        modeRow.addView(autoModes.root, lp())
        for ((name, art, i) in modes) {
            val t = tile(FxArt(art, sk.accent, faint), name, level(getMode(i)))
            paint(t, getMode(i) > 0)
            t.root.setOnClickListener {
                Touch.confirm(t.root)
                // choosing a level yourself turns Auto off for the modes
                val wasAuto = Prefs.apModesAuto
                Prefs.apModesAuto = false
                val cur = if (wasAuto) 0 else getMode(i)
                val next = when { cur <= 0 -> 50; cur < 75 -> 100; else -> 0 }
                setMode(i, next)
                AudioPlus.rememberForDevice(); svc.audioPlusChanged()
                modesSync?.invoke()
            }
            modeRow.addView(t.root, lp())
            modeTiles += t
        }
        modesSync?.invoke()
        body.addView(note("Auto sets all three from the song as it plays. Sound Stage puts the music around and in front of you. Sound Separation lifts the voice out of the band. Instrument Separation keeps the bass centred and spreads the instruments. Tap a mode for Medium, again for Strong, once more for Off."))

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
        body.addView(compare, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })

        // ---- customize: room + intensity
        val toggle = MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        body.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        val custom = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(custom)
        custom.addView(label("Room"))
        val (roomScroll, roomRow) = row()
        custom.addView(roomScroll)
        val roomTiles = ArrayList<Pair<Tile, String>>()
        val roomChoices = listOf(Triple(AutoSound.ROOM_AUTO, "Auto", 0.0), Triple("mood", "Preset's room", 1.0), Triple("off", "No room", 0.15)) +
            SoundPresets.ROOMS.map { Triple(it.id, it.name, it.rt60) }
        for ((id, name, rt) in roomChoices) {
            val art = if (id == AutoSound.ROOM_AUTO) FxArt(FxArt.Kind.Auto, sk.accent, faint) else FxArt(FxArt.Kind.Room(rt, id), sk.accent, faint)
            val t = tile(art, name, when (id) { "mood", "off" -> ""; AutoSound.ROOM_AUTO -> "follows the song"; else -> "${"%.1f".format(rt)} s echo" })
            if (id == AutoSound.ROOM_AUTO) autoRoomTile = t
            t.root.setOnClickListener {
                Touch.confirm(t.root)
                Prefs.apRoom = id; AudioPlus.rememberForDevice(); svc.audioPlusChanged()
                for ((x, xid) in roomTiles) paint(x, Prefs.apRoom == xid)
            }
            roomRow.addView(t.root, lp())
            roomTiles += t to id
        }
        custom.addView(note("Auto picks the room from the song as it plays – bigger for quiet, open passages, smaller and drier for busy, loud or fast ones. Each room is modelled from its size and walls; the line under it shows how long its echo lasts."))
        val intLabel = label("")
        val slider = Slider(a).apply { valueFrom = 0f; valueTo = 150f; stepSize = 10f }
        custom.addView(intLabel); custom.addView(slider)
        custom.addView(note("Your equalizer below is applied inside Audio+, and the volume is matched to the original song."))
        slider.addOnChangeListener { _, v, fromUser ->
            intLabel.text = "Intensity · ${v.toInt()}%"
            if (fromUser) { Touch.tick(slider); Prefs.apIntensity = v.toInt(); AudioPlus.rememberForDevice(); svc.audioPlusChanged() }
        }
        customizeSync = {
            for ((x, xid) in roomTiles) paint(x, Prefs.apRoom == xid)
            slider.value = (Prefs.apIntensity.coerceIn(0, 150) / 10 * 10).toFloat()
            intLabel.text = "Intensity · ${slider.value.toInt()}%"
        }
        customizeSync?.invoke()
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

        // ---- on / off
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
        }

        // Auto: refresh "Now: …" while the screen is open
        val h = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (Prefs.apMood == AudioPlus.AUTO) showDesc()
                if (Prefs.apModesAuto) modesSync?.invoke()
                autoRoomTile?.caption?.text = if (Prefs.apRoom == AutoSound.ROOM_AUTO)
                    (AutoSound.live.roomId?.let { "Now: " + AutoSound.roomName(it) } ?: "listening…") else "follows the song"
                h.postDelayed(this, 1000)
            }
        }
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { h.post(tick) }
            override fun onViewDetachedFromWindow(v: View) { h.removeCallbacks(tick) }
        })
        return box
    }

    private var customizeSync: (() -> Unit)? = null
    private var modesSync: (() -> Unit)? = null
    private var autoRoomTile: Tile? = null
}
