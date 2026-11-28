/*
 * Copyright (c) 2026 JSL WORKS PVT LTD. All rights reserved.
 * Product of JSL WORKS PVT LTD — www.jslworks.in
 * Proprietary and Confidential. Unauthorized copying or distribution is strictly prohibited.
 */
package com.jslw.genz.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.jslw.genz.R
import com.jslw.genz.audio.AudioPlus
import com.jslw.genz.audio.SoundFitStore
import com.jslw.genz.data.Prefs
import com.jslw.genz.playback.Player
import com.jslw.genz.skin.SkinManager
import com.jslworks.soundengine.core.JslwAudioBuffer
import com.jslworks.soundengine.dsp.HearingTest
import com.jslworks.soundengine.dsp.SoundFitEqualizer
import com.jslworks.soundengine.dsp.ToneBurst
import kotlin.math.ln

/**
 * JSLW SoundFit — "Tune to my ears". Plays soft beeps one ear at a time; the listener taps "I hear it" or "I don't".
 * The result is a per-ear correction saved for the current earphones and used as the first stage of JSLW Audio+.
 */
class SoundFitActivity : BaseActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private val sk get() = SkinManager.current
    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var test: HearingTest? = null
    private var track: AudioTrack? = null
    private var wasPlaying = false
    private var startVolume = -1
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_soundfit)
        setupScreen(findViewById(R.id.root))
        header("SoundFit")
        body = findViewById(R.id.sfBody)
        showIntro()
    }

    override fun onResume() {
        super.onResume()
        if (test != null && track == null) next()   // back from elsewhere: replay the current beeps
    }

    override fun onPause() {
        super.onPause()
        // leaving mid-test: stop the tones (the test can be started again)
        if (test != null && !isFinishing) stopTone()
    }

    override fun onDestroy() {
        stopTone(); track?.release(); track = null
        if (wasPlaying) Player.service?.play()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI helpers
    private fun text(s: String, size: Float, bold: Boolean = false, dim: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(if (dim) sk.textSecondary else sk.text)
        typeface = android.graphics.Typeface.create(sk.typeface, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        setPadding(0, px(6), 0, px(6))
    }

    private fun button(s: String, filled: Boolean, onClick: () -> Unit) = MaterialButton(this, null,
        if (filled) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = s; isAllCaps = false; textSize = 16f
        cornerRadius = px(26); minHeight = px(56)
        if (filled) { setBackgroundColor(sk.accent); setTextColor(0xFFFFFFFF.toInt()) } else setTextColor(sk.accent)
        setOnClickListener { Touch.confirm(this); onClick() }
    }

    private fun full(v: View, top: Int = 12) = body.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) })

    private fun footer() = full(text("Proprietary software of JSL WORKS PVT LTD · Made in India with ❤ · Partho Ghosh", 11f, dim = true).apply { gravity = Gravity.CENTER; alpha = 0.7f }, 28)

    // ---------------------------------------------------------------- 1. intro
    private fun showIntro() {
        body.removeAllViews()
        val device = AudioPlus.outputName
        full(text("Tune to my ears", 24f, true), 4)
        full(text("A short listening test through your own earphones. JSLW Audio+ then corrects the sound for this device and your ears – " +
            "the earphones' tuning, the fit, noise cancelling and your hearing, all at once.", 15f, dim = true))
        full(text("🎧  $device", 16f, true), 16)
        if (AudioPlus.speaker) full(text("You're on the phone speaker – connect the earphones you want to tune first.", 14f).apply { setTextColor(sk.accent) })
        full(text("Before you start", 16f, true), 18)
        full(text("•  Sit somewhere quiet.\n•  Wear the earphones properly (ANC as you usually use it).\n•  Set the volume where you normally listen – and keep it there.\n" +
            "•  You'll hear three soft beeps at a time, one ear at a time. Tap \"I hear it\" even when they're very faint.", 14.5f, dim = true))
        SoundFitStore.get()?.let { p ->
            val d = android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date(p.time))
            full(text("This device was tuned on $d (${if (p.full) "Full" else "Quick"} test).", 13.5f, dim = true), 14)
            full(curve(p), 6)
        }
        full(button("Quick test  ·  about 2 min", true) { start(false) }, 22)
        full(button("Full test  ·  about 5 min", false) { start(true) }, 10)
        footer()
        full(text("SoundFit is a listening-comfort correction, not a medical hearing test.", 11.5f, dim = true).apply { gravity = Gravity.CENTER })
    }

    // ---------------------------------------------------------------- 2. the test
    private lateinit var earTv: TextView
    private lateinit var pitchTv: TextView
    private lateinit var progress: ProgressBar
    private lateinit var hintTv: TextView
    private lateinit var yes: MaterialButton
    private lateinit var no: MaterialButton

    private fun start(fullTest: Boolean) {
        if (Build.VERSION.SDK_INT < 21) return
        Player.service?.let { s -> wasPlaying = s.isPlaying; if (s.isPlaying) s.pause() }
        startVolume = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
        test = if (fullTest) HearingTest.full() else HearingTest.quick()
        body.removeAllViews()
        earTv = text("", 30f, true).apply { gravity = Gravity.CENTER }
        pitchTv = text("", 15f, dim = true).apply { gravity = Gravity.CENTER }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        hintTv = text("", 13.5f, dim = true).apply { gravity = Gravity.CENTER }
        full(earTv, 24); full(pitchTv, 2); full(progress, 18); full(hintTv, 10)
        yes = button("I hear it", true) { answer(true) }
        no = button("I don't hear it", false) { answer(false) }
        full(yes, 36); full(no, 12)
        full(button("Cancel", false) { stopTone(); test = null; showIntro() }.apply { alpha = 0.7f }, 30)
        next()
    }

    private fun next() {
        val t = test ?: return
        val p = t.current()
        if (p == null) { finishTest(); return }
        earTv.text = if (p.ear == 0) "Left ear" else "Right ear"
        pitchTv.text = "${pitchName(p.frequencyHz)}  ·  ${t.pointsDone + 1} of ${t.totalPoints}"
        progress.progress = t.pointsDone * 1000 / t.totalPoints
        val vol = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
        hintTv.text = if (vol != startVolume) "The volume changed – please put it back where it was." else "Listening…"
        setAnswering(false)
        play(p.frequencyHz, p.levelDbfs, p.ear)
        // answers open once the beeps have played (they stay open – take your time)
        main.postDelayed({ if (test != null) { setAnswering(true); hintTv.text = "Did you hear the beeps?" } }, (ToneBurst.frames(48000) * 1000L / 48000) + 150)
    }

    private fun setAnswering(on: Boolean) { busy = !on; yes.isEnabled = on; no.isEnabled = on; yes.alpha = if (on) 1f else 0.5f; no.alpha = if (on) 1f else 0.5f }

    private fun answer(heard: Boolean) {
        if (busy) return
        test?.answer(heard)
        next()
    }

    private fun pitchName(hz: Double) = when {
        hz < 400 -> "Low pitch"; hz < 1500 -> "Middle pitch"; hz < 5000 -> "High pitch"; else -> "Very high pitch"
    } + "  (" + (if (hz >= 1000) "${"%.0f".format(hz / 1000)} kHz" else "${hz.toInt()} Hz") + ")"

    // ---------------------------------------------------------------- tone playback (its own clean track, no effects)
    private fun play(hz: Double, dbfs: Double, ear: Int) {
        stopTone()
        val rate = 48000
        val b = JslwAudioBuffer(2, ToneBurst.frames(rate), rate)
        ToneBurst.render(b, hz, dbfs, ear)
        val pcm = b.toInterleavedFloat()
        try {
            val t = AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                pcm.size * 4, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            track = t
        } catch (_: Exception) { hintTv.text = "Couldn't play the test tone on this device." }
    }

    private fun stopTone() {
        main.removeCallbacksAndMessages(null)
        track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        track = null
    }

    // ---------------------------------------------------------------- 3. result
    private fun finishTest() {
        val t = test ?: return
        test = null
        stopTone()
        val p = SoundFitStore.Profile(AudioPlus.outputName, t.frequenciesHz.size > 6, t.frequenciesHz,
            t.thresholds[0], t.thresholds[1], System.currentTimeMillis(), true)
        SoundFitStore.save(p)
        Player.service?.audioPlusChanged()
        body.removeAllViews()
        full(text("Tuned to your ears ✓", 24f, true), 4)
        full(text("Saved for ${p.device}. JSLW Audio+ now corrects this device first, before your preset and equalizer – " +
            "it switches automatically when you change earphones.", 15f, dim = true))
        full(curve(p), 14)
        full(text("The curve shows what SoundFit adds or takes away for each ear (left ${legend(0)}, right ${legend(1)}).", 13f, dim = true))
        val noResp = t.noResponse.any { e -> e.any { it } }
        if (noResp) full(text("Some beeps weren't heard even at the loudest test level – those pitches are lifted only as far as is safe (+6 dB).", 13f).apply { setTextColor(sk.accent) })
        if (!AudioPlus.active) full(text("Turn on JSLW Audio+ on the Sound screen to hear it.", 14f).apply { setTextColor(sk.accent) })
        full(button("Done", true) { finish() }, 22)
        full(button("Test again", false) { showIntro() }, 10)
        footer()
    }

    private fun legend(ear: Int) = if (ear == 0) "solid" else "dashed"

    private fun curve(p: SoundFitStore.Profile): View = object : View(this) {
        private val eq = SoundFitEqualizer(48000).apply { setProfile(p.frequenciesHz, p.left, p.right) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = px(3).toFloat(); color = sk.accent; strokeCap = Paint.Cap.ROUND }
        private val dash = Paint(line).apply { pathEffect = android.graphics.DashPathEffect(floatArrayOf(px(8).toFloat(), px(6).toFloat()), 0f); alpha = 200 }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sk.textSecondary; alpha = 60; strokeWidth = px(1).toFloat() }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sk.textSecondary; textSize = px(11).toFloat() }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), px(170))
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val top = px(8).toFloat(); val bot = h - px(20)
            fun y(db: Double) = top + (bot - top) * ((12.0 - db.coerceIn(-12.0, 12.0)) / 24.0).toFloat()
            fun x(f: Double) = w * ((ln(f / 100.0) / ln(160.0)).coerceIn(0.0, 1.0)).toFloat()   // 100 Hz … 16 kHz
            for (db in listOf(-12.0, -6.0, 0.0, 6.0, 12.0)) c.drawLine(0f, y(db), w, y(db), grid.apply { alpha = if (db == 0.0) 140 else 60 })
            for ((f, s) in listOf(250.0 to "250", 1000.0 to "1k", 4000.0 to "4k", 12000.0 to "12k")) c.drawText(s, x(f) - px(8), h - px(4), label)
            for (ear in 0..1) {
                val path = Path()
                for (i in 0..120) {
                    val f = 100.0 * Math.pow(160.0, i / 120.0)
                    val px = x(f); val py = y(eq.responseDb(ear, f))
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                c.drawPath(path, if (ear == 0) line else dash)
            }
        }
    }
}
