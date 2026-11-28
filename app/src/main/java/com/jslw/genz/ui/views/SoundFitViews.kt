/*
 * Copyright (c) 2026 JSL WORKS PVT LTD. All rights reserved.
 * Product of JSL WORKS PVT LTD — www.jslworks.in
 * Proprietary and Confidential. Unauthorized copying or distribution is strictly prohibited.
 */
package com.jslw.genz.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * A head seen from above with both ears. Sound waves pulse out of the ear being tested, in time with the beeps;
 * [ear] 0 = left, 1 = right, 2 = both (centre check), -1 = none. [balance] (−1…+1) moves the "sound dot"
 * between the ears for the centre check.
 */
class HeadView(ctx: Context, private val accent: Int, private val ink: Int) : View(ctx) {
    var ear = -1
        set(v) { field = v; invalidate() }
    var balance = 0f
        set(v) { field = v.coerceIn(-1f, 1f); invalidate() }
    var showDot = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = ink; alpha = 200 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 22 }
    private val earOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val earOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 70 }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = accent; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private var phase = 0f          // 0…1 pulse phase while playing
    private var playing = false
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 400; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    /** Pulse the waves for [ms] (the length of the beeps). */
    fun pulse(ms: Long) {
        playing = true
        anim.cancel(); anim.start()
        removeCallbacks(stopper); postDelayed(stopper, ms)
    }
    private val stopper = Runnable { playing = false; anim.cancel(); invalidate() }
    fun stopPulse() { removeCallbacks(stopper); stopper.run() }

    override fun onDetachedFromWindow() { anim.cancel(); removeCallbacks(stopper); super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (150 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(height * 0.36f, width * 0.16f)
        // head (from above) with a small nose to show the front
        c.drawCircle(cx, cy, r, fill); c.drawCircle(cx, cy, r, head)
        val nose = Path().apply { moveTo(cx - r * 0.18f, cy - r * 0.96f); lineTo(cx, cy - r * 1.22f); lineTo(cx + r * 0.18f, cy - r * 0.96f) }
        c.drawPath(nose, head)
        // ears
        for (side in 0..1) {
            val ex = if (side == 0) cx - r else cx + r
            val active = ear == side || ear == 2
            val rect = RectF(ex - r * 0.16f, cy - r * 0.34f, ex + r * 0.16f, cy + r * 0.34f)
            c.drawRoundRect(rect, r * 0.16f, r * 0.16f, if (active) earOn else earOff)
            if (active && playing) {
                // three arcs travelling outwards
                for (k in 0..2) {
                    val t = ((phase + k / 3f) % 1f)
                    val rr = r * (0.45f + t * 0.9f)
                    wave.alpha = (255 * (1f - t)).toInt()
                    val box = RectF(ex - rr, cy - rr, ex + rr, cy + rr)
                    if (side == 0) c.drawArc(box, 140f, 80f, false, wave) else c.drawArc(box, -40f, 80f, false, wave)
                }
            }
        }
        // centre check: where the sound sits between the ears
        if (showDot) {
            val x = cx + balance * r * 0.8f
            c.drawCircle(x, cy, r * 0.13f, dot)
            dot.alpha = 70; c.drawCircle(x, cy, r * 0.24f, dot); dot.alpha = 255
        }
    }
}

/** A rounded progress bar that glides to each new value. */
class GlideProgress(ctx: Context, private val accent: Int, private val track: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 0…1 */
    fun setProgress(p: Float, animate: Boolean = true) {
        val to = p.coerceIn(0f, 1f)
        anim?.cancel()
        if (!animate) { shown = to; invalidate(); return }
        anim = ValueAnimator.ofFloat(shown, to).apply {
            duration = 450; interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (8 * d).toInt())

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, bg)
        val end = (w * shown).coerceAtLeast(h)
        fg.shader = LinearGradient(0f, 0f, end, 0f, (accent and 0x00FFFFFF) or 0x99000000.toInt(), accent, Shader.TileMode.CLAMP)
        if (shown > 0f) c.drawRoundRect(0f, 0f, end, h, h / 2, h / 2, fg)
    }
}
