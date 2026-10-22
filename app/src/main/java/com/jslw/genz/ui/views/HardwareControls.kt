package com.jslw.genz.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.jslw.genz.R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** Colours for the hardware-style controls, taken from the theme JSON ("wheel" / "keypad" blocks). */
class HardwareColors(
    val panelTop: Int, val panelBottom: Int, val face: Int, val faceEdge: Int,
    val centerTop: Int, val centerBottom: Int, val glyph: Int, val accent: Int,
    val label: Int,
    val led: Int = accent,
)

private fun tinted(ctx: Context, res: Int, color: Int): Drawable? =
    AppCompatResources.getDrawable(ctx, res)?.mutate()?.let { DrawableCompat.wrap(it).also { d -> DrawableCompat.setTint(d, color) } }

/**
 * Classic click wheel: tap the top (shuffle), left (previous), right (next), bottom or centre (play / pause).
 * Circle a finger around the ring to scrub through the song, with a haptic tick every step.
 */
class ClickWheelView(ctx: Context, var colors: HardwareColors) : View(ctx) {
    var onShuffle: () -> Unit = {}
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onPlayPause: () -> Unit = {}
    /** Scrub by this many milliseconds (positive = forward). */
    var onScrub: (Long) -> Unit = {}
    var playing = false; set(v) { field = v; invalidate() }
    var shuffleOn = false; set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressedZone = -1 // 0 top, 1 right, 2 bottom, 3 left, 4 centre
    private var lastAngle = 0f
    private var accum = 0f
    private var scrubbing = false
    private var downX = 0f; private var downY = 0f

    override fun onMeasure(wm: Int, hm: Int) {
        val w = MeasureSpec.getSize(wm)
        val h = min((w * 0.86f).toInt(), (300 * d).toInt())
        setMeasuredDimension(w, h)
    }

    private fun cx() = width / 2f
    private fun cy() = height / 2f
    private fun outerR() = min(width, height) * 0.45f
    private fun innerR() = outerR() * 0.36f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // panel behind the wheel (the "body" of the player)
        if (Color.alpha(colors.panelTop) > 0) {
            p.shader = LinearGradient(0f, 0f, 0f, h, colors.panelTop, colors.panelBottom, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(0f, 0f, w, h), 26 * d, 26 * d, p)
            p.shader = null
        }
        val cx = cx(); val cy = cy(); val r = outerR(); val ri = innerR()
        // soft drop shadow
        p.shader = RadialGradient(cx, cy + 6 * d, r * 1.12f, intArrayOf(0x40000000, 0x00000000), floatArrayOf(0.82f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + 6 * d, r * 1.12f, p)
        // wheel face
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r, colors.face, colors.faceEdge, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        // pressed segment
        if (pressedZone in 0..3) {
            p.color = (colors.accent and 0x00FFFFFF) or 0x2E000000
            val start = arrayOf(225f, 315f, 45f, 135f)[pressedZone]
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), start, 90f, true, p)
            p.color = colors.face; c.drawCircle(cx, cy, ri * 1.08f, p)
        }
        // rim
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = (colors.glyph and 0x00FFFFFF) or 0x22000000
        c.drawCircle(cx, cy, r, p); p.style = Paint.Style.FILL
        // centre button
        p.shader = LinearGradient(0f, cy - ri, 0f, cy + ri,
            if (pressedZone == 4) colors.centerBottom else colors.centerTop,
            if (pressedZone == 4) colors.centerTop else colors.centerBottom, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, ri, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = (colors.glyph and 0x00FFFFFF) or 0x26000000
        c.drawCircle(cx, cy, ri, p); p.style = Paint.Style.FILL
        // glyphs
        val gs = (r * 0.17f).toInt()
        val mid = (r + ri) / 2f
        draw(c, R.drawable.ic_shuffle, cx, cy - mid, gs, if (shuffleOn) colors.accent else colors.glyph)
        draw(c, R.drawable.ic_prev, cx - mid, cy, gs, colors.glyph)
        draw(c, R.drawable.ic_next, cx + mid, cy, gs, colors.glyph)
        draw(c, if (playing) R.drawable.ic_pause else R.drawable.ic_play, cx, cy + mid, gs, colors.glyph)
    }

    private fun draw(c: Canvas, res: Int, x: Float, y: Float, size: Int, color: Int) {
        val dr = tinted(context, res, color) ?: return
        dr.setBounds((x - size / 2f).toInt(), (y - size / 2f).toInt(), (x + size / 2f).toInt(), (y + size / 2f).toInt())
        dr.draw(c)
    }

    private fun angle(x: Float, y: Float) = Math.toDegrees(atan2((y - cy()).toDouble(), (x - cx()).toDouble())).toFloat()

    private fun zone(x: Float, y: Float): Int {
        val dist = hypot(x - cx(), y - cy())
        if (dist <= innerR()) return 4
        if (dist > outerR() * 1.15f) return -1
        val a = (angle(x, y) + 360f) % 360f
        return when {
            a >= 225 && a < 315 -> 0
            a >= 315 || a < 45 -> 1
            a >= 45 && a < 135 -> 2
            else -> 3
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedZone = zone(e.x, e.y)
                if (pressedZone < 0) return false
                downX = e.x; downY = e.y
                lastAngle = angle(e.x, e.y); accum = 0f; scrubbing = false
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedZone == 4) return true
                val a = angle(e.x, e.y)
                var delta = a - lastAngle
                if (delta > 180) delta -= 360f
                if (delta < -180) delta += 360f
                lastAngle = a
                accum += delta
                if (!scrubbing && kotlin.math.abs(accum) > 14f) { scrubbing = true; pressedZone = -1; invalidate() }
                if (scrubbing) {
                    while (accum >= 15f) { accum -= 15f; onScrub(5000); tick() }
                    while (accum <= -15f) { accum += 15f; onScrub(-5000); tick() }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val z = pressedZone
                pressedZone = -1; invalidate()
                if (!scrubbing) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    when (z) { 0 -> onShuffle(); 1 -> onNext(); 2, 4 -> onPlayPause(); 3 -> onPrev() }
                }
                scrubbing = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressedZone = -1; scrubbing = false; invalidate() }
        }
        return true
    }

    private fun tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

/**
 * Pocket-recorder keypad: a speaker grille above three hardware keys
 * (previous · play / pause in an orange "record" key · next).
 */
class KeypadView(ctx: Context, var colors: HardwareColors) : View(ctx) {
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onPlayPause: () -> Unit = {}
    var playing = false; set(v) { field = v; invalidate() }
    /** Show the speaker grille above the keys (pocket-recorder look). */
    var grille = true

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f * resources.displayMetrics.scaledDensity; letterSpacing = 0.25f }
    private var pressed = -1
    private val keys = Array(3) { RectF() }

    override fun onMeasure(wm: Int, hm: Int) {
        val w = MeasureSpec.getSize(wm)
        setMeasuredDimension(w, if (grille) min((w * 0.62f).toInt(), (240 * d).toInt()) else min((w * 0.3f).toInt(), (120 * d).toInt()))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, 0f, h, colors.panelTop, colors.panelBottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, w, h), 22 * d, 22 * d, p); p.shader = null
        // speaker grille
        val gTop = 22 * d; val gBot = if (grille) h * 0.42f else -10 * d
        p.color = (colors.glyph and 0x00FFFFFF) or 0x8C000000.toInt()
        val step = 7 * d; val rr = 1.5f * d
        var y = gTop
        while (grille && y <= gBot) {
            var x = w * 0.16f
            while (x <= w * 0.84f) { c.drawCircle(x, y, rr, p); x += step }
            y += step
        }
        tp.color = colors.label
        if (grille) { val lab = "SPEAKERS"; c.drawText(lab, (w - tp.measureText(lab)) / 2f, gBot + 16 * d, tp) }
        // keys
        val pad = 14 * d; val gap = 6 * d; val top = if (grille) gBot + 26 * d else 12 * d; val bottom = h - (if (grille) 26 else 12) * d
        val kw = (w - pad * 2 - gap * 2) / 3f
        for (i in 0..2) {
            val l = pad + i * (kw + gap)
            val k = keys[i]; k.set(l, top, l + kw, bottom)
            val off = if (pressed == i) 2 * d else 0f
            // key shadow + body
            p.color = 0x33000000; c.drawRoundRect(RectF(k.left, k.top + 3 * d, k.right, k.bottom + 3 * d), 12 * d, 12 * d, p)
            p.shader = LinearGradient(0f, k.top, 0f, k.bottom, colors.face, colors.faceEdge, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(k.left, k.top + off, k.right, k.bottom + off), 12 * d, 12 * d, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = (colors.glyph and 0x00FFFFFF) or 0x22000000
            c.drawRoundRect(RectF(k.left, k.top + off, k.right, k.bottom + off), 12 * d, 12 * d, p); p.style = Paint.Style.FILL
            val kcx = k.centerX(); val kcy = k.centerY() + off
            val btn = min(kw, k.height()) * 0.42f
            if (i == 1) {
                // orange "record" key
                p.shader = RadialGradient(kcx - btn * 0.2f, kcy - btn * 0.3f, btn * 0.9f, colors.centerTop, colors.centerBottom, Shader.TileMode.CLAMP)
                c.drawCircle(kcx, kcy, btn * 0.62f, p); p.shader = null
                drawIcon(c, if (playing) R.drawable.ic_pause else R.drawable.ic_play, kcx, kcy, (btn * 0.62f).toInt(), 0xFFFFFFFF.toInt())
            } else {
                p.color = (colors.glyph and 0x00FFFFFF) or 0x18000000
                c.drawCircle(kcx, kcy, btn * 0.6f, p)
                drawIcon(c, if (i == 0) R.drawable.ic_prev else R.drawable.ic_next, kcx, kcy, (btn * 0.6f).toInt(), colors.glyph)
            }
        }
        if (grille) {
            val l2 = "KEYPAD"; c.drawText(l2, (w - tp.measureText(l2)) / 2f, h - 10 * d, tp)
            // little status LED
            p.color = if (playing) colors.led else ((colors.glyph and 0x00FFFFFF) or 0x40000000)
            c.drawCircle(pad + 4 * d, h - 13 * d, 3 * d, p)
        }
    }

    private fun drawIcon(c: Canvas, res: Int, x: Float, y: Float, size: Int, color: Int) {
        val dr = tinted(context, res, color) ?: return
        dr.setBounds((x - size / 2f).toInt(), (y - size / 2f).toInt(), (x + size / 2f).toInt(), (y + size / 2f).toInt())
        dr.draw(c)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = keys.indexOfFirst { it.contains(e.x, e.y) }
                if (pressed < 0) return false
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                val k = pressed; pressed = -1; invalidate()
                if (k >= 0 && keys[k].contains(e.x, e.y)) when (k) { 0 -> onPrev(); 1 -> onPlayPause(); 2 -> onNext() }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
