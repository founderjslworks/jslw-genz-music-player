package com.jslw.genz.ui.views

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.jslw.genz.audio.AutoSound

/**
 * A small live line: "✦ Auto · Happy · 104 BPM · Medium Hall · Stage Light · EQ treble +2.0" – what JSLW Audio+ Auto
 * has chosen for the song playing now. Refreshes every second while on screen; hidden when nothing is on Auto.
 */
class AutoNowView(ctx: Context, private val color: Int, private val tint: Int, private val center: Boolean = true) : TextView(ctx) {
    private val d = resources.displayMetrics.density
    private val tick = object : Runnable {
        override fun run() { refresh(); postDelayed(this, 1000) }
    }

    init {
        textSize = 12f
        setTextColor(color)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = if (center) Gravity.CENTER else Gravity.START
        setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
        background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(tint) }
        visibility = View.GONE
    }

    fun refresh() {
        val s = AutoSound.summary()
        if (s == null) { visibility = View.GONE; return }
        text = "✦ Auto  ·  $s"
        visibility = View.VISIBLE
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(tick); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
}
