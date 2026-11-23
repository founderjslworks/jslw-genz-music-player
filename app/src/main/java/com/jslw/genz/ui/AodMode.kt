package com.jslw.genz.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.jslw.genz.data.Prefs
import com.jslw.genz.playback.Player
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.util.ArtLoader

/**
 * GEN-Z's own always-on display for the lock-screen player. Apps can't draw on the phone's built-in AOD, so
 * when the player sits untouched it fades to a black, very dim screen with the clock and the song instead of
 * switching off. AMOLED pixels that are black use no power; the content drifts a little every minute so
 * nothing burns in. A tap brings the full player back. It only stays on under the user's chosen condition
 * (while charging, or while music plays) – otherwise the screen sleeps as usual.
 */
class AodMode(private val a: Activity, private val root: FrameLayout, private val onEnter: () -> Unit, private val onExit: () -> Unit) {
    private val h = Handler(Looper.getMainLooper())
    private val d = a.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()
    var charging = false
        set(v) { field = v; evaluate() }
    var active = false; private set
    private var layer: FrameLayout? = null
    private var box: LinearLayout? = null
    private var title: TextView? = null
    private var sub: TextView? = null
    private var art: ShapeableImageView? = null
    private var bar: View? = null
    private var barTrack: View? = null
    private var shownSong = Long.MIN_VALUE
    private var pausedSince = 0L

    private val idle = Runnable { if (allowed()) enter() }
    private val drift = object : Runnable {
        override fun run() {
            val b = box ?: return
            b.animate().translationX(((Math.random() * 2 - 1) * px(28)).toFloat())
                .translationY(((Math.random() * 2 - 1) * px(40)).toFloat()).setDuration(1800).start()
            h.postDelayed(this, 60_000)
        }
    }
    private val tick = object : Runnable {
        override fun run() { update(); evaluate(); if (active) h.postDelayed(this, 1000) }
    }

    private fun allowed(): Boolean {
        val playing = Player.isPlaying
        if (playing) pausedSince = 0L else if (pausedSince == 0L) pausedSince = System.currentTimeMillis()
        // paused for a while: stop keeping the screen on (unless on the charger)
        val recentlyPlaying = playing || System.currentTimeMillis() - pausedSince < 3 * 60_000
        return when (Prefs.aodMode) {
            1 -> charging
            2 -> charging || recentlyPlaying
            else -> false
        }
    }

    /** Keeps the screen awake only while the AOD may be used. */
    private fun evaluate() {
        if (allowed()) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else {
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (active) exit(false)
        }
    }

    /** Call on every touch / when the player is shown: restarts the idle countdown. */
    fun poke() {
        h.removeCallbacks(idle)
        if (active) exit(true)
        evaluate()
        if (Prefs.aodMode != 0) h.postDelayed(idle, 12_000)
    }

    fun stop() {
        h.removeCallbacks(idle); h.removeCallbacks(drift); h.removeCallbacks(tick)
        if (active) exit(false)
        a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun build(): FrameLayout {
        layer?.let { return it }
        val l = FrameLayout(a).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setOnClickListener { poke() }
            alpha = 0f
        }
        val tf = SkinManager.current.typeface
        val b = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        b.addView(TextClock(a).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            textSize = 64f; setTextColor(0xFFE6E6E6.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        b.addView(TextClock(a).apply {
            format12Hour = "EEE, d MMM"; format24Hour = "EEE, d MMM"
            textSize = 15f; setTextColor(0xFF9A9A9A.toInt()); typeface = tf
        })
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cover = ShapeableImageView(a).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = shapeAppearanceModel.withCornerSize(px(10).toFloat())
            alpha = 0.85f
        }
        row.addView(cover, LinearLayout.LayoutParams(px(52), px(52)))
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, 0, 0) }
        val t = TextView(a).apply { textSize = 15f; setTextColor(0xFFDADADA.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create(tf, Typeface.BOLD) }
        val s = TextView(a).apply { textSize = 13f; setTextColor(0xFF8A8A8A.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = tf }
        texts.addView(t); texts.addView(s)
        row.addView(texts, LinearLayout.LayoutParams(px(200), ViewGroup.LayoutParams.WRAP_CONTENT))
        b.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(28) })
        val track = FrameLayout(a).apply { setBackgroundColor(0x33FFFFFF) }
        val fill = View(a).apply { setBackgroundColor(0xB3FFFFFF.toInt()); pivotX = 0f }
        track.addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        b.addView(track, LinearLayout.LayoutParams(px(264), px(2)).apply { topMargin = px(14) })
        l.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(l, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = l; box = b; title = t; sub = s; art = cover; bar = fill; barTrack = track
        return l
    }

    private fun update() {
        val svc = Player.service ?: return
        val s = svc.current ?: return
        if (s.id != shownSong) {
            shownSong = s.id
            title?.text = s.title; sub?.text = s.artist
            art?.let { ArtLoader.load(it, s, px(52)) }
        }
        val dur = svc.duration()
        bar?.scaleX = if (dur > 0) (svc.smoothPosition().toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    private fun enter() {
        if (active) return
        active = true
        val l = build()
        l.visibility = View.VISIBLE
        l.bringToFront()
        update()
        l.animate().alpha(1f).setDuration(900).start()
        a.window.attributes = a.window.attributes.apply { screenBrightness = 0.02f }
        onEnter()
        h.post(tick)
        h.postDelayed(drift, 60_000)
    }

    private fun exit(animate: Boolean) {
        active = false
        h.removeCallbacks(tick); h.removeCallbacks(drift)
        a.window.attributes = a.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        layer?.let { l ->
            if (animate) l.animate().alpha(0f).setDuration(220).withEndAction { l.visibility = View.GONE }.start()
            else { l.alpha = 0f; l.visibility = View.GONE }
        }
        onExit()
    }
}
