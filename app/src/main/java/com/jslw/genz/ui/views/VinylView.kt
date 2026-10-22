package com.jslw.genz.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Turntable cover: the album art becomes the label of a black vinyl record that spins while music plays.
 * A tonearm swings onto the record on play and lifts off on pause. The record slows down and speeds up
 * smoothly like a real platter.
 */
class VinylView(ctx: Context) : View(ctx) {
    var plinthTop = 0xFFF1E4EA.toInt()
    var plinthBottom = 0xFFD7C3CD.toInt()
    var labelColor = 0xFFC8333F.toInt()
    var armColor = 0xFFE9E9EC.toInt()
    var headColor = 0xFFE8742A.toInt()
    /** Draw the recessed platter well behind the record. */
    var well = true

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var art: Bitmap? = null
    private var labelShader: BitmapShader? = null
    private var angle = 0f
    private var speed = 0f       // degrees per frame-second, eases toward target
    private var target = 0f
    private var arm = 0f         // 0 = rest, 1 = on the record
    private var lastNs = 0L
    private var playing = false

    private val frame = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { step() }
    }
    private var armAnim: ValueAnimator? = null

    fun setArt(b: Bitmap?) { art = b; labelShader = null; invalidate() }

    fun setPlaying(on: Boolean) {
        if (on == playing && (frame.isRunning || !on)) return
        playing = on
        target = if (on) 200f else 0f // ~33 rpm
        armAnim?.cancel()
        armAnim = ValueAnimator.ofFloat(arm, if (on) 1f else 0f).apply {
            duration = 650; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { arm = it.animatedValue as Float; invalidate() }
            start()
        }
        if (on && !frame.isRunning) { lastNs = 0L; frame.start() }
    }

    private fun step() {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now
        speed += (target - speed) * (dt * 2.2f).coerceAtMost(1f)
        angle = (angle + speed * dt) % 360f
        if (!playing && speed < 1f) { speed = 0f; frame.cancel(); lastNs = 0L }
        invalidate()
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); frame.cancel(); armAnim?.cancel() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val size = min(w, h)
        val cx = w * 0.46f; val cy = h / 2f
        val r = size * 0.43f
        if (well) {
            p.shader = RadialGradient(cx, cy, r * 1.12f, intArrayOf(0x40000000, 0x10000000, 0x00000000), floatArrayOf(0.85f, 0.95f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 1.12f, p)
            p.shader = LinearGradient(0f, cy - r * 1.08f, 0f, cy + r * 1.08f, plinthBottom, plinthTop, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 1.07f, p)
        }
        // record shadow + body
        p.shader = RadialGradient(cx, cy + 6 * d, r * 1.06f, intArrayOf(0x88000000.toInt(), 0x00000000), floatArrayOf(0.88f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + 6 * d, r * 1.06f, p)
        p.shader = null; p.color = 0xFF0C0B0D.toInt(); c.drawCircle(cx, cy, r, p)
        c.save(); c.rotate(angle, cx, cy)
        // grooves
        p.style = Paint.Style.STROKE
        var rr = r * 0.97f; var k = 0
        while (rr > r * 0.38f) {
            p.strokeWidth = 0.7f * d
            p.color = if (k % 3 == 0) 0x22FFFFFF else 0x0EFFFFFF
            c.drawCircle(cx, cy, rr, p); rr -= 1.8f * d; k++
        }
        p.style = Paint.Style.FILL
        // label = album art
        val lr = r * 0.36f
        val a = art
        if (a != null) {
            if (labelShader == null) labelShader = BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = (lr * 2) / min(a.width, a.height)
            val m = Matrix().apply { setScale(s, s); postTranslate(cx - a.width * s / 2, cy - a.height * s / 2) }
            labelShader!!.setLocalMatrix(m)
            p.shader = labelShader
            c.drawCircle(cx, cy, lr, p); p.shader = null
        } else { p.color = labelColor; c.drawCircle(cx, cy, lr, p) }
        p.color = 0xFF1A1A1A.toInt(); c.drawCircle(cx, cy, lr * 0.09f, p)
        p.color = 0xFFD9D9D9.toInt(); c.drawCircle(cx, cy, lr * 0.045f, p)
        c.restore()
        // static sheen (light doesn't rotate with the record)
        p.shader = SweepGradient(cx, cy, intArrayOf(0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x1CFFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.08f, 0.18f, 0.5f, 0.58f, 0.68f, 1f))
        c.drawCircle(cx, cy, r, p); p.shader = null
        // tonearm
        drawArm(c, cx, cy, r)
    }

    private fun drawArm(c: Canvas, cx: Float, cy: Float, r: Float) {
        val px = cx + r * 1.02f; val py = cy - r * 0.92f
        // rest angle points down past the record edge; play angle reaches the outer grooves
        val restDeg = 98f; val playDeg = 122f
        val deg = restDeg + (playDeg - restDeg) * arm
        val len = r * 1.2f
        val rad = Math.toRadians(deg.toDouble())
        val ex = px + (cos(rad) * len).toFloat(); val ey = py + (sin(rad) * len).toFloat()
        // shadow
        p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
        p.color = 0x44000000; p.strokeWidth = 5 * d
        c.drawLine(px + 4 * d, py + 6 * d, ex + 4 * d, ey + 6 * d, p)
        // arm tube
        p.shader = LinearGradient(px, py, ex, ey, armColor, 0xFF9C9CA3.toInt(), Shader.TileMode.CLAMP)
        p.strokeWidth = 3.6f * d
        c.drawLine(px, py, ex, ey, p); p.shader = null
        p.style = Paint.Style.FILL
        // headshell
        c.save(); c.rotate(deg - 90f, ex, ey)
        p.color = headColor
        c.drawRoundRect(RectF(ex - 6 * d, ey - 2 * d, ex + 6 * d, ey + 14 * d), 2 * d, 2 * d, p)
        p.color = 0xFF2A2A2A.toInt(); c.drawRect(ex - 3 * d, ey + 12 * d, ex + 3 * d, ey + 16 * d, p)
        c.restore()
        // pivot
        p.shader = RadialGradient(px - 3 * d, py - 3 * d, 15 * d, 0xFFFFFFFF.toInt(), 0xFF8E8E95.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(px, py, 13 * d, p); p.shader = null
        p.color = 0xFF2B2B30.toInt(); c.drawCircle(px, py, 5 * d, p)
    }
}
