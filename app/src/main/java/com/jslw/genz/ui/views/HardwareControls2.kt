package com.jslw.genz.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.jslw.genz.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Shared plumbing for the hardware-style transport controls (deck, tuner, gauge, pucks). */
abstract class HardwareControl(ctx: Context, var colors: HardwareColors) : View(ctx) {
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onPlayPause: () -> Unit = {}
    var onQueue: () -> Unit = {}
    /** Relative scrub in milliseconds. */
    var onScrub: (Long) -> Unit = {}
    /** Absolute seek in milliseconds. */
    var onSeekTo: (Long) -> Unit = {}
    var playing = false; set(v) { if (field != v) { field = v; invalidate() } }
    protected var pos = 0L
    protected var dur = 1L
    protected val d = resources.displayMetrics.density
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val pixelFont: Typeface? = try { ResourcesCompat.getFont(ctx, R.font.vt323_regular) } catch (e: Exception) { null }
    protected val lightFont: Typeface? = try { ResourcesCompat.getFont(ctx, R.font.inter_regular) } catch (e: Exception) { null }

    fun setPosition(position: Long, duration: Long) {
        val np = position.coerceAtLeast(0); val nd = duration.coerceAtLeast(1)
        if (np / 250 != pos / 250 || nd != dur) { pos = np; dur = nd; invalidate() }
    }

    protected fun frac() = (pos.toFloat() / dur).coerceIn(0f, 1f)
    protected fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
    protected fun tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    protected fun press() = performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    protected fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    protected fun icon(c: Canvas, res: Int, x: Float, y: Float, size: Float, color: Int) {
        val dr = AppCompatResources.getDrawable(context, res)?.mutate() ?: return
        val w = DrawableCompat.wrap(dr); DrawableCompat.setTint(w, color)
        w.setBounds((x - size / 2).toInt(), (y - size / 2).toInt(), (x + size / 2).toInt(), (y + size / 2).toInt())
        w.draw(c)
    }

    /** Round "3D" button: shadow, rim, gradient face, gloss, glyph. */
    protected fun puck(c: Canvas, cx: Float, cy: Float, r: Float, top: Int, bottom: Int, glyph: Int, glyphColor: Int, pressed: Boolean) {
        val off = if (pressed) 1.5f * d else 0f
        p.shader = RadialGradient(cx, cy + 4 * d, r * 1.25f, intArrayOf(0x55000000, 0x00000000), floatArrayOf(0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + 4 * d, r * 1.25f, p)
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0xFFFFFFFF.toInt(), 0xFFB9B4B8.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + off, r, p)
        val ri = r * 0.84f
        p.shader = LinearGradient(0f, cy - ri, 0f, cy + ri, if (pressed) bottom else top, if (pressed) top else bottom, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + off, ri, p)
        p.shader = LinearGradient(0f, cy - ri, 0f, cy, 0x50FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - ri * 0.8f, cy - ri * 0.95f + off, cx + ri * 0.8f, cy + off), p)
        p.shader = null
        icon(c, glyph, cx, cy + off, r * 0.8f, glyphColor)
    }

    protected fun panel(c: Canvas, radius: Float) {
        if (Color.alpha(colors.panelTop) == 0) return
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), colors.panelTop, colors.panelBottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, p)
        p.shader = null
    }
}

/**
 * Radio-deck: grey LCD read-out, chrome previous / next buttons and a big seek knob
 * (turn it to scrub, tap it to play / pause) with a status LED.
 */
class DeckView(ctx: Context, colors: HardwareColors) : HardwareControl(ctx, colors) {
    private var pressed = -1 // 0 prev, 1 knob, 2 next
    private var lastA = 0f; private var acc = 0f; private var turning = false
    private val speckle = FloatArray(240).also { val r = java.util.Random(7); for (i in it.indices) it[i] = r.nextFloat() }

    override fun onMeasure(wm: Int, hm: Int) {
        val w = MeasureSpec.getSize(wm); setMeasuredDimension(w, min((w * 0.72f).toInt(), (290 * d).toInt()))
    }

    private fun lcd() = RectF(16 * d, 14 * d, width - 16 * d, height * 0.34f)
    private fun knobR() = min(height * 0.25f, width * 0.17f)
    private fun knobC() = Pair(width / 2f, height * 0.66f)
    private fun btnC(i: Int) = Pair(if (i == 0) width * 0.16f else width * 0.84f, height * 0.66f)

    override fun onDraw(c: Canvas) {
        panel(c, 24 * d)
        // brushed speckle texture
        p.color = 0x14FFFFFF
        for (i in 0 until speckle.size / 2) c.drawCircle(speckle[i * 2] * width, speckle[i * 2 + 1] * height, 0.8f * d, p)
        // LCD
        val l = lcd()
        p.shader = LinearGradient(0f, l.top, 0f, l.bottom, colors.centerTop, colors.centerBottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(l, 10 * d, 10 * d, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = 0x33000000; c.drawRoundRect(l, 10 * d, 10 * d, p); p.style = Paint.Style.FILL
        p.typeface = pixelFont; p.color = colors.label
        p.textSize = l.height() * 0.22f
        c.drawText(if (playing) "NOW PLAYING" else "PAUSED", l.left + 14 * d, l.top + l.height() * 0.32f, p)
        p.textSize = l.height() * 0.52f
        val t = fmt(pos); c.drawText(t, l.centerX() - p.measureText(t) / 2, l.bottom - l.height() * 0.16f, p)
        p.textSize = l.height() * 0.2f
        val r2 = "-" + fmt(dur - pos); c.drawText(r2, l.right - 14 * d - p.measureText(r2), l.bottom - l.height() * 0.16f, p)
        p.typeface = null
        // chrome buttons
        val br = knobR() * 0.46f
        for (i in 0..1) {
            val (x, y) = btnC(i)
            chrome(c, x, y, br, pressed == (if (i == 0) 0 else 2))
            icon(c, if (i == 0) R.drawable.ic_prev else R.drawable.ic_next, x, y, br * 0.85f, colors.glyph)
            label(c, if (i == 0) "PREV" else "NEXT", x, y + br + 16 * d)
        }
        // seek knob
        val (kx, ky) = knobC(); val kr = knobR()
        p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.strokeCap = Paint.Cap.ROUND
        p.color = 0x33000000; c.drawArc(RectF(kx - kr * 1.18f, ky - kr * 1.18f, kx + kr * 1.18f, ky + kr * 1.18f), 135f, 270f, false, p)
        p.color = colors.accent; c.drawArc(RectF(kx - kr * 1.18f, ky - kr * 1.18f, kx + kr * 1.18f, ky + kr * 1.18f), 135f, 270f * frac(), false, p)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(kx, ky + 5 * d, kr * 1.1f, intArrayOf(0x66000000, 0x00000000), floatArrayOf(0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(kx, ky + 5 * d, kr * 1.1f, p)
        p.shader = LinearGradient(0f, ky - kr, 0f, ky + kr, colors.face, colors.faceEdge, Shader.TileMode.CLAMP)
        c.drawCircle(kx, ky, kr, p)
        p.shader = RadialGradient(kx - kr * 0.3f, ky - kr * 0.4f, kr, intArrayOf(0x30FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(kx, ky, kr * 0.86f, p); p.shader = null
        val a = Math.toRadians((135 + 270 * frac()).toDouble())
        p.color = colors.glyph
        c.drawCircle(kx + (cos(a) * kr * 0.68f).toFloat(), ky + (sin(a) * kr * 0.68f).toFloat(), kr * 0.09f, p)
        label(c, if (playing) "TURN TO SEEK · TAP TO PAUSE" else "TURN TO SEEK · TAP TO PLAY", kx, ky + kr * 1.18f + 18 * d)
        // LED
        val lx = kx + kr * 1.05f; val ly = ky - kr * 1.05f
        if (playing) { p.shader = RadialGradient(lx, ly, 10 * d, intArrayOf(colors.led, alpha(colors.led, 0)), null, Shader.TileMode.CLAMP); c.drawCircle(lx, ly, 10 * d, p); p.shader = null }
        p.color = if (playing) colors.led else alpha(colors.led, 0x55); c.drawCircle(lx, ly, 4.5f * d, p)
    }

    private fun chrome(c: Canvas, x: Float, y: Float, r: Float, pressed: Boolean) {
        p.shader = RadialGradient(x, y + 3 * d, r * 1.2f, intArrayOf(0x55000000, 0x00000000), floatArrayOf(0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y + 3 * d, r * 1.2f, p)
        p.shader = LinearGradient(x - r, y - r, x + r, y + r, if (pressed) colors.faceEdge else colors.face, if (pressed) colors.face else colors.faceEdge, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = LinearGradient(0f, y - r, 0f, y, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(x, y - r * 0.15f, r * 0.8f, p); p.shader = null
    }

    private fun label(c: Canvas, t: String, x: Float, y: Float) {
        p.textSize = 9 * d; p.typeface = lightFont; p.color = alpha(colors.glyph, 0xB0)
        c.drawText(t, x - p.measureText(t) / 2, y, p); p.typeface = null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val (kx, ky) = knobC()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = when {
                    hypot(e.x - kx, e.y - ky) < knobR() * 1.25f -> 1
                    hypot(e.x - btnC(0).first, e.y - btnC(0).second) < knobR() * 0.8f -> 0
                    hypot(e.x - btnC(1).first, e.y - btnC(1).second) < knobR() * 0.8f -> 2
                    else -> -1
                }
                if (pressed < 0) return false
                lastA = Math.toDegrees(atan2((e.y - ky).toDouble(), (e.x - kx).toDouble())).toFloat(); acc = 0f; turning = false
                parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> if (pressed == 1) {
                val a = Math.toDegrees(atan2((e.y - ky).toDouble(), (e.x - kx).toDouble())).toFloat()
                var dl = a - lastA; if (dl > 180) dl -= 360; if (dl < -180) dl += 360
                lastA = a; acc += dl
                if (!turning && abs(acc) > 12) turning = true
                if (turning) {
                    while (acc >= 15) { acc -= 15; onScrub(5000); tick() }
                    while (acc <= -15) { acc += 15; onScrub(-5000); tick() }
                }
            }
            MotionEvent.ACTION_UP -> {
                val k = pressed; pressed = -1; invalidate()
                if (!turning) { press(); when (k) { 0 -> onPrev(); 1 -> onPlayPause(); 2 -> onNext() } }
                turning = false
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; turning = false; invalidate() }
        }
        return true
    }
}

/**
 * Radio tuner: big pixel read-out, a glowing tuning band with minute markings under a fixed needle
 * (drag it to move through the song) and a ribbed dial wheel; round buttons skip tracks.
 */
class TunerView(ctx: Context, colors: HardwareColors) : HardwareControl(ctx, colors) {
    private var dragging = false; private var lastX = 0f; private var accPx = 0f; private var downX = 0f
    private var pressedSide = -1

    override fun onMeasure(wm: Int, hm: Int) {
        val w = MeasureSpec.getSize(wm); setMeasuredDimension(w, min((w * 0.74f).toInt(), (300 * d).toInt()))
    }

    private val tickPx get() = 12 * d // one tick = 10 s
    private fun band() = RectF(0f, height * 0.46f, width.toFloat(), height * 0.7f)

    override fun onDraw(c: Canvas) {
        panel(c, 26 * d)
        val w = width.toFloat(); val h = height.toFloat()
        // read-out
        p.typeface = pixelFont; p.color = colors.glyph; p.textSize = h * 0.25f
        val t = fmt(pos); val tw = p.measureText(t)
        c.drawText(t, w / 2 - tw / 2, h * 0.3f, p)
        p.textSize = h * 0.07f; p.color = colors.label; c.drawText("min", w / 2 + tw / 2 + 6 * d, h * 0.3f, p)
        p.typeface = lightFont; p.textSize = 12 * d
        val sub = if (playing) "Now playing" else "Paused"
        c.drawText(sub, w / 2 - p.measureText(sub) / 2, h * 0.39f, p)
        // band
        val b = band()
        p.typeface = null
        p.shader = LinearGradient(0f, b.top, 0f, b.bottom, colors.centerTop, colors.centerBottom, Shader.TileMode.CLAMP)
        c.drawRect(b, p)
        p.shader = LinearGradient(0f, b.top, 0f, b.top + b.height() * 0.35f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(b, p)
        p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x99000000.toInt(), 0x00000000, 0x00000000, 0x99000000.toInt()), floatArrayOf(0f, 0.22f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(b, p); p.shader = null
        val centerX = w / 2
        val offset = (pos / 10000f) * tickPx
        val first = ((offset - centerX) / tickPx).toInt() - 1
        val last = ((offset + centerX) / tickPx).toInt() + 1
        p.strokeWidth = 1.4f * d
        for (k in first..last) {
            if (k < 0 || k * 10000L > dur) continue
            val x = centerX + k * tickPx - offset
            val major = k % 6 == 0
            p.color = alpha(colors.face, if (major) 0xF0 else 0x99)
            c.drawLine(x, b.top + 6 * d, x, b.top + (if (major) 22 else 13) * d, p)
            if (major) {
                p.typeface = pixelFont; p.textSize = b.height() * 0.36f
                val lab = "${k / 6}"
                c.drawText(lab, x - p.measureText(lab) / 2, b.bottom - 8 * d, p); p.typeface = null
            }
        }
        // needle
        p.shader = LinearGradient(centerX - 6 * d, 0f, centerX + 6 * d, 0f, intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(centerX - 6 * d, b.top, centerX + 6 * d, b.bottom, p); p.shader = null
        p.color = 0xFFFFFFFF.toInt(); c.drawRect(centerX - 1.2f * d, b.top + 2 * d, centerX + 1.2f * d, b.bottom - 2 * d, p)
        // ribbed dial wheel
        val k = RectF(0f, b.bottom, w, h - 14 * d)
        p.shader = LinearGradient(0f, k.top, 0f, k.bottom, colors.faceEdge, 0xFF0A0A0A.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(k, p); p.shader = null
        val step = 4 * d; var x = -((offset * 0.6f) % step)
        while (x < w) {
            val dist = abs(x - centerX) / centerX
            p.color = alpha(0xFFFFFFFF.toInt(), (60 * (1 - dist)).toInt().coerceIn(0, 60))
            c.drawLine(x, k.top + 3 * d, x, k.bottom - 3 * d, p); x += step
        }
        // side buttons
        for (i in 0..1) {
            val cx = if (i == 0) 26 * d else w - 26 * d; val cy = b.top
            p.color = 0x66000000; c.drawCircle(cx, cy + 2 * d, 17 * d, p)
            p.shader = LinearGradient(0f, cy - 16 * d, 0f, cy + 16 * d, 0xFFF4F4F4.toInt(), 0xFFBDBDBD.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, if (pressedSide == i) 15 * d else 16 * d, p); p.shader = null
            icon(c, if (i == 0) R.drawable.ic_prev else R.drawable.ic_next, cx, cy, 16 * d, 0xFF2A2A2A.toInt())
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat(); val b = band()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedSide = when {
                    hypot(e.x - 26 * d, e.y - b.top) < 24 * d -> 0
                    hypot(e.x - (w - 26 * d), e.y - b.top) < 24 * d -> 1
                    else -> -1
                }
                downX = e.x; lastX = e.x; accPx = 0f; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> if (pressedSide < 0 && e.y > b.top - 20 * d) {
                val dx = e.x - lastX; lastX = e.x
                if (!dragging && abs(e.x - downX) > 8 * d) dragging = true
                if (dragging) {
                    accPx -= dx
                    val step = tickPx / 10 // 1 s
                    while (accPx >= step) { accPx -= step; onScrub(1000); if ((pos / 1000) % 10 == 0L) tick() }
                    while (accPx <= -step) { accPx += step; onScrub(-1000); if ((pos / 1000) % 10 == 0L) tick() }
                }
            }
            MotionEvent.ACTION_UP -> {
                val s = pressedSide; pressedSide = -1; invalidate()
                if (!dragging) { press(); when (s) { 0 -> onPrev(); 1 -> onNext(); else -> onPlayPause() } }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> { pressedSide = -1; dragging = false; invalidate() }
        }
        return true
    }
}

/**
 * Analog gauge: a round dial with a minute scale and a percentage scale, a red needle showing where the song is
 * (drag the needle anywhere to jump there), a big read-out and a two-way pill for previous / next.
 */
class GaugeView(ctx: Context, colors: HardwareColors) : HardwareControl(ctx, colors) {
    private var dragging = false; private var pill = -1

    override fun onMeasure(wm: Int, hm: Int) {
        val w = MeasureSpec.getSize(wm); setMeasuredDimension(w, min((w * 0.92f).toInt(), (360 * d).toInt()))
    }

    private fun cx() = width / 2f
    private fun cy() = height * 0.47f
    private fun R() = min(width, height) * 0.44f
    private fun pillRect() = RectF(cx() - R() * 0.42f, cy() + R() * 0.52f, cx() + R() * 0.42f, cy() + R() * 0.74f)
    private val start = 150f; private val sweep = 240f

    override fun onDraw(c: Canvas) {
        panel(c, 28 * d)
        val cx = cx(); val cy = cy(); val r = R()
        // dial face
        p.shader = RadialGradient(cx, cy - r * 0.3f, r * 1.3f, colors.centerTop, colors.centerBottom, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = alpha(colors.glyph, 0x30); c.drawCircle(cx, cy, r, p)
        c.drawCircle(cx, cy, r * 0.62f, p)
        // outer minute scale
        val mins = (dur / 60000f).coerceAtLeast(1f)
        val ticks = (mins * 6).toInt().coerceAtMost(240)
        for (k in 0..ticks) {
            val f = k / (mins * 6)
            if (f > 1f) break
            val a = Math.toRadians((start + sweep * f).toDouble())
            val major = k % 6 == 0
            val r1 = r * 0.9f; val r2 = r * (if (major) 0.8f else 0.85f)
            p.strokeWidth = (if (major) 2f else 1.1f) * d
            p.color = alpha(colors.label, if (major) 0xFF else 0x80)
            c.drawLine(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(), cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), p)
            if (major) {
                p.style = Paint.Style.FILL; p.typeface = lightFont; p.textSize = r * 0.085f
                val lab = "${k / 6}"; val rl = r * 0.72f
                c.drawText(lab, cx + (cos(a) * rl).toFloat() - p.measureText(lab) / 2, cy + (sin(a) * rl).toFloat() + p.textSize * 0.35f, p)
                p.style = Paint.Style.STROKE
            }
        }
        // inner percent scale
        for (k in 0..10) {
            val a = Math.toRadians((start + sweep * k / 10f).toDouble())
            p.strokeWidth = 1.6f * d; p.color = colors.glyph
            val r1 = r * 0.6f; val r2 = r * 0.55f
            c.drawLine(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(), cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), p)
            if (k % 2 == 0) {
                p.style = Paint.Style.FILL; p.typeface = lightFont; p.textSize = r * 0.075f; p.color = alpha(colors.glyph, 0xD0)
                val lab = "${k * 10}"; val rl = r * 0.47f
                c.drawText(lab, cx + (cos(a) * rl).toFloat() - p.measureText(lab) / 2, cy + (sin(a) * rl).toFloat() + p.textSize * 0.35f, p)
                p.style = Paint.Style.STROKE
            }
        }
        p.style = Paint.Style.FILL; p.typeface = null
        // read-out
        p.typeface = lightFont; p.color = colors.glyph; p.textSize = r * 0.3f
        val t = fmt(pos); val tw = p.measureText(t)
        c.drawText(t, cx - tw / 2, cy + r * 0.42f, p)
        p.textSize = r * 0.08f; p.color = colors.label; c.drawText("min", cx + tw / 2 + 4 * d, cy + r * 0.42f, p)
        // pill: previous | next
        val pr = pillRect()
        p.color = alpha(0xFF000000.toInt(), 0x66); c.drawRoundRect(pr, pr.height() / 2, pr.height() / 2, p)
        if (pill >= 0) {
            p.color = alpha(colors.glyph, 0x30)
            val half = RectF(if (pill == 0) pr.left else pr.centerX(), pr.top, if (pill == 0) pr.centerX() else pr.right, pr.bottom)
            c.drawRoundRect(half, pr.height() / 2, pr.height() / 2, p)
        }
        icon(c, R.drawable.ic_prev, pr.left + pr.width() * 0.25f, pr.centerY(), pr.height() * 0.6f, colors.glyph)
        icon(c, R.drawable.ic_next, pr.left + pr.width() * 0.75f, pr.centerY(), pr.height() * 0.6f, colors.glyph)
        // needle
        val a = Math.toRadians((start + sweep * frac()).toDouble())
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0x55000000; p.strokeWidth = 5 * d
        c.drawLine(cx + 2 * d, cy + 3 * d, cx + (cos(a) * r * 0.9f).toFloat() + 2 * d, cy + (sin(a) * r * 0.9f).toFloat() + 3 * d, p)
        p.color = colors.accent; p.strokeWidth = 3.4f * d
        c.drawLine(cx - (cos(a) * r * 0.12f).toFloat(), cy - (sin(a) * r * 0.12f).toFloat(), cx + (cos(a) * r * 0.9f).toFloat(), cy + (sin(a) * r * 0.9f).toFloat(), p)
        p.shader = RadialGradient(cx, cy - 4 * d, r * 0.13f, 0xFF3A3A3A.toInt(), 0xFF0E0E0E.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.11f, p); p.shader = null
        p.color = colors.accent; c.drawCircle(cx, cy, r * 0.04f, p)
        // status LED
        p.color = if (playing) colors.led else alpha(colors.led, 0x50)
        c.drawRect(22 * d, 20 * d, 30 * d, 28 * d, p)
        p.textSize = 9 * d; p.typeface = lightFont; p.color = colors.label
        c.drawText(if (playing) "PLAY" else "PAUSE", 36 * d, 28 * d, p); p.typeface = null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = cx(); val cy = cy(); val r = R()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pr = pillRect()
                pill = if (pr.contains(e.x, e.y)) (if (e.x < pr.centerX()) 0 else 1) else -1
                dragging = false
                if (pill < 0 && hypot(e.x - cx, e.y - cy) > r * 1.05f) return false
                parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> if (pill < 0) {
                val dist = hypot(e.x - cx, e.y - cy)
                if (dist > r * 0.25f) {
                    dragging = true
                    var a = Math.toDegrees(atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())).toFloat()
                    if (a < 0) a += 360f
                    var rel = a - start; if (rel < 0) rel += 360f
                    if (rel <= sweep) {
                        val target = (rel / sweep * dur).toLong()
                        if (abs(target - pos) > 1500) { pos = target; onSeekTo(target); tick(); invalidate() }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val s = pill; pill = -1; invalidate()
                if (!dragging) {
                    press()
                    when (s) { 0 -> onPrev(); 1 -> onNext(); else -> if (hypot(e.x - cx, e.y - cy) < r * 0.3f) onPlayPause() }
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> { pill = -1; dragging = false; invalidate() }
        }
        return true
    }
}

/** Row of round 3D buttons: previous · play / pause (accent) · next · queue, with an elapsed / total read-out. */
class PuckRowView(ctx: Context, colors: HardwareColors) : HardwareControl(ctx, colors) {
    private var pressed = -1
    override fun onMeasure(wm: Int, hm: Int) {
        val w = MeasureSpec.getSize(wm); setMeasuredDimension(w, min((w * 0.36f).toInt(), (150 * d).toInt()))
    }
    private fun r() = min(width / 10f, height * 0.3f)
    private fun cxOf(i: Int) = width / 2f + (i - 1.5f) * r() * 2.55f
    private fun cy() = height * 0.4f

    override fun onDraw(c: Canvas) {
        panel(c, 24 * d)
        val r = r(); val cy = cy()
        val glyphs = intArrayOf(R.drawable.ic_prev, if (playing) R.drawable.ic_pause else R.drawable.ic_play, R.drawable.ic_next, R.drawable.ic_queue)
        for (i in 0..3) {
            val accent = i == 1
            puck(c, cxOf(i), cy, r, if (accent) colors.centerTop else colors.face, if (accent) colors.centerBottom else colors.faceEdge,
                glyphs[i], if (accent) 0xFFFFFFFF.toInt() else colors.glyph, pressed == i)
        }
        p.typeface = lightFont; p.textSize = 13 * d; p.isFakeBoldText = true; p.color = colors.label
        val t = "Duration: ${fmt(pos)} / ${fmt(dur)}"
        c.drawText(t, width / 2f - p.measureText(t) / 2, cy + r + 26 * d, p)
        p.isFakeBoldText = false; p.typeface = null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = (0..3).firstOrNull { hypot(e.x - cxOf(it), e.y - cy()) < r() * 1.15f } ?: -1
                if (pressed < 0) return false
                press(); invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                val k = pressed; pressed = -1; invalidate()
                when (k) { 0 -> onPrev(); 1 -> onPlayPause(); 2 -> onNext(); 3 -> onQueue() }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
