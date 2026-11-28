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
 * "JSLW Audio+" at the top of the Sound screen. Built for a personal experience, not a mixing desk:
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
        head.addView(text("JSLW Audio+", 18f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = SwitchCompat(a)
        head.addView(sw)
        box.addView(head)
        box.addView(text(
            if (AudioPlus.supported) "Your personal sound, powered by the JSLW Audio Engine. Pick a preset or let Auto choose – fine-tune only if you want."
            else "JSLW Audio+ needs Android 5.0 or newer.", 13f
        ).apply { alpha = 0.85f })

        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(body)

        // ---- this device
        fun kindIcon(k: String) = when (k) {
            AudioPlus.KIND_SPEAKER -> R.drawable.ic_out_speaker
            AudioPlus.KIND_CAR -> R.drawable.ic_out_car
            AudioPlus.KIND_SOUNDBAR -> R.drawable.ic_out_soundbar
            else -> R.drawable.ic_out_headphones
        }
        val outLabel = label("  ${AudioPlus.outputName}")
        fun outIcon() {
            val res = if (AudioPlus.speaker) R.drawable.ic_out_phone else kindIcon(AudioPlus.kind)
            val d = androidx.core.content.ContextCompat.getDrawable(a, res)?.mutate()
            d?.setTint(sk.accent); d?.setBounds(0, 0, px(22), px(22))
            outLabel.setCompoundDrawablesRelative(d, null, null, null)
            outLabel.compoundDrawablePadding = px(6)
        }
        outIcon()
        body.addView(outLabel)
        val kinds = ChipGroup(a).apply { isSingleSelection = true; isSelectionRequired = true }
        for ((k, name) in listOf(AudioPlus.KIND_HEADPHONES to "Headphones", AudioPlus.KIND_SPEAKER to "Speaker",
                AudioPlus.KIND_SOUNDBAR to "Soundbar", AudioPlus.KIND_CAR to "Car")) {
            val c = Chip(a)
            c.text = name; c.isCheckable = true; c.id = View.generateViewId()
            SkinPainter.styleChip(c); Touch.spring(c)
            c.setChipIconResource(kindIcon(k)); c.isChipIconVisible = true
            c.chipIconSize = px(18).toFloat(); c.chipIconTint = c.textColors; c.iconStartPadding = px(4).toFloat()
            c.isChecked = AudioPlus.kind == k
            c.setOnClickListener { Touch.confirm(c); AudioPlus.setKind(k); outIcon() }
            kinds.addView(c)
        }
        body.addView(kinds)
        body.addView(note("Tell Audio+ what this is – speakers, soundbars and cars get gentler bass so songs never boom or pump; a soundbar also gets a wider stage and clearer voices. Everything you choose is remembered for this device."))
        if (AudioPlus.bluetooth) body.addView(note("Tip: keep your earphones' own EQ on Default / Balanced, so two equalizers don't fight."))
        AudioPlus.phoneEffects()?.let { body.addView(note("This phone also has $it. If the sound feels too strong or thin, switch $it off while using Audio+.")) }

        // ---- SoundFit: "Tune to my ears" (per device)
        val sfRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(14), 0, 0) }
        val sfTexts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        sfTexts.addView(text("SoundFit · Tune to my ears", 15.5f, true))
        val sfStatus = note("")
        sfTexts.addView(sfStatus)
        sfRow.addView(sfTexts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sfSw = SwitchCompat(a)
        sfRow.addView(sfSw)
        body.addView(sfRow)
        val sfBtn = com.google.android.material.button.MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false; cornerRadius = px(20); setTextColor(sk.accent)
            setOnClickListener { Touch.confirm(this); a.startActivity(android.content.Intent(a, SoundFitActivity::class.java)) }
        }
        body.addView(sfBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
        fun sfSync() {
            val p = com.jslw.genz.audio.SoundFitStore.get()
            sfSw.setOnCheckedChangeListener(null)
            sfSw.visibility = if (p != null) View.VISIBLE else View.GONE
            sfSw.isChecked = p?.on == true
            sfSw.setOnCheckedChangeListener { _, v -> Touch.confirm(sfSw); com.jslw.genz.audio.SoundFitStore.setOn(v); svc.audioPlusChanged(); sfSync() }
            sfStatus.text = if (p == null) "A 2-minute listening test through these earphones – Audio+ then corrects them for your ears."
                else "Tuned for ${p.device}" + (if (p.on) " – correcting both ears." else " – off.")
            sfBtn.text = if (p == null) "Start the test" else "Test again"
            sfBtn.visibility = if (AudioPlus.speaker && p == null) View.GONE else View.VISIBLE
            if (AudioPlus.speaker && p == null) sfStatus.text = "Connect your earphones to tune them to your ears."
        }
        sfSync()
        soundFitSync = { sfSync() }

        // ---- live: what Auto has chosen for the song playing now
        body.addView(com.jslw.genz.ui.views.AutoNowView(a, sk.text, (sk.accent and 0x00FFFFFF) or 0x26000000, center = false),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })

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

        // ---- Quality: repair the source (detail restore for lossy files, dynamics restore for squashed masters)
        body.addView(text("Quality", 16f, true).apply { setPadding(0, px(18), 0, 0) })
        fun qRow(title: String, on: Boolean, onChange: (Boolean) -> Unit): Pair<View, TextView> {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8), 0, 0) }
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(title, 14.5f))
            val st = note("")
            col.addView(st)
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val sw = SwitchCompat(a).apply { isChecked = on }
            sw.setOnCheckedChangeListener { _, v -> Touch.confirm(sw); onChange(v) }
            row.addView(sw)
            return row to st
        }
        val (detRow, detNote) = qRow("Detail restore", Prefs.hqDetail) { v -> Prefs.hqDetail = v }
        body.addView(detRow)
        val (dynRow, dynNote) = qRow("Dynamics restore", Prefs.hqDynamics) { v -> Prefs.hqDynamics = v }
        body.addView(dynRow)
        fun qSync() {
            val cut = AudioPlus.liveCutoffHz
            detNote.text = when {
                !Prefs.hqDetail -> "Rebuilds the treble and air that lossy files (Opus, MP3, AAC) lose."
                cut > 0 -> "Restoring above ${"%.1f".format(cut / 1000)} kHz – this song was cut there by its encoder."
                else -> "This song is full range – nothing to restore."
            }
            val cr = AudioPlus.liveCrestDb
            dynNote.text = when {
                !Prefs.hqDynamics -> "Gives over-compressed (\"loudness war\") songs their punch back."
                cr in 0.0..12.0 -> "Bringing back punch – this master is compressed (${"%.0f".format(cr)} dB crest)."
                cr > 12.0 -> "This song has natural dynamics – untouched."
                else -> "Listening…"
            }
        }
        qSync()
        qualitySync = { qSync() }

        // ---- Hi-Fi: the JSLW Audio Engine C++ core (true-peak limiter, equal-loudness, crossfeed)
        val hifi = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(hifi)
        fun toggleRow(title: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): Pair<View, SwitchCompat> {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8), 0, 0) }
            row.addView(text(title, 14.5f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val s = SwitchCompat(a).apply { isChecked = on; isEnabled = enabled }
            s.setOnCheckedChangeListener { _, v -> Touch.confirm(s); onChange(v) }
            row.addView(s)
            return row to s
        }
        val hfHead = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(18), 0, 0) }
        hfHead.addView(text("Hi-Fi", 16f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val hfSw = SwitchCompat(a)
        hfHead.addView(hfSw)
        hifi.addView(hfHead)
        val coreOk = com.jslw.genz.audio.HiFi.coreAvailable
        hifi.addView(note(if (coreOk) "Studio-grade processing from the JSLW Audio Engine core (C++20), applied after everything above."
            else "The JSLW Audio Engine core isn't available on this device, so Hi-Fi is off."))
        val hfBody = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        hifi.addView(hfBody)
        val (loudRow, _) = toggleRow("Loudness compensation", Prefs.hfLoudness, coreOk) { v -> Prefs.hfLoudness = v }
        hfBody.addView(loudRow)
        val loudNote = note("")
        hfBody.addView(loudNote)
        val (xfRow, _) = toggleRow("Crossfeed", Prefs.hfCrossfeed, coreOk) { v -> Prefs.hfCrossfeed = v }
        hfBody.addView(xfRow)
        val xfNote = note("Headphones only: blends a little of each side into the other, the way speakers reach both ears – a natural, less tiring stereo.")
        hfBody.addView(xfNote)
        // the C++ limiter changes the chain (and its delay): switching it moves the song to a fresh track
        val (tpRow, _) = toggleRow("True-peak protection", Prefs.hfTruePeak, coreOk) { v -> Prefs.hfTruePeak = v; svc.hiFiCoreToggled() }
        hifi.addView(tpRow)
        hifi.addView(note("Catches the peaks between samples (4× oversampled) and holds them at −1 dBTP, so loud songs never distort on Bluetooth or DACs; " +
            "untouched – bit-identical – whenever the music stays below. Adds " + "%.1f".format(com.jslw.genz.audio.HiFi.latencyMs(48000).takeIf { it > 0 } ?: 1.8) + " ms, which the timeline accounts for."))
        hifi.addView(note("Proprietary software of JSL WORKS PVT LTD · Made in India with ❤ · Partho Ghosh").apply { alpha = 0.6f; textSize = 11f; setPadding(0, px(10), 0, 0) })
        fun hfSync() {
            hfSw.isEnabled = coreOk && Prefs.hfTruePeak
            hfSw.isChecked = coreOk && Prefs.hfTruePeak && Prefs.hfOn
            hfBody.visibility = if (hfSw.isChecked) View.VISIBLE else View.GONE
            val hp = com.jslw.genz.audio.HiFi.headphones
            xfRow.visibility = if (hp) View.VISIBLE else View.GONE
            xfNote.visibility = if (hp) View.VISIBLE else View.GONE
            loudNote.text = "Restores bass and treble at lower volume (ISO 226 equal-loudness), from your volume and the song's loudness – now about " +
                "${com.jslw.genz.audio.HiFi.listeningPhon(-14.0).toInt()} phon."
        }
        hfSw.setOnCheckedChangeListener { _, v ->
            Touch.confirm(hfSw)
            android.transition.TransitionManager.beginDelayedTransition(box, android.transition.AutoTransition().setDuration(220))
            Prefs.hfOn = v; hfSync()   // loudness and crossfeed glide in and out inside the engine – no track change
        }
        hfSync()
        hifiSync = { hfSync() }

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
                hifiSync?.invoke()
                soundFitSync?.invoke()
                qualitySync?.invoke()
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
    private var hifiSync: (() -> Unit)? = null
    private var soundFitSync: (() -> Unit)? = null
    private var qualitySync: (() -> Unit)? = null
    private var autoRoomTile: Tile? = null
}
