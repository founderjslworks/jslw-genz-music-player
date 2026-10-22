package com.jslw.genz.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import com.jslw.genz.util.FrameTicker
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen "love it!" celebration for favouriting a song: a giant heart pops over the cover with a
 * glow, then bursts into a shower of hearts, confetti and sparkles that rain over the screen, with
 * shock-wave rings. Unfavouriting plays a small, gentle heart shrink. Touches pass straight through.
 */
class CelebrationView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val rnd = Random(5)
    private val heart = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var colors = intArrayOf(0xFFFF2D55.toInt(), 0xFFFF5C8A.toInt(), 0xFFFFC93C.toInt(), 0xFFB388FF.toInt(), 0xFFFFFFFF.toInt())

    // the big heart
    private var bigX = 0f
    private var bigY = 0f
    private var bigT = -1f
    private var bigSize = 0f
    private var love = true
    // particles: 0 heart, 1 confetti, 2 sparkle
    private val n = 220
    private val x = FloatArray(n); private val y = FloatArray(n); private val vx = FloatArray(n); private val vy = FloatArray(n)
    private val life = FloatArray(n); private val max = FloatArray(n); private val size = FloatArray(n)
    private val rot = FloatArray(n); private val vr = FloatArray(n); private val col = IntArray(n); private val kind = IntArray(n)
    private var count = 0
    // shock-wave rings
    private val ringT = FloatArray(4)
    private var rings = 0
    private var time = 0f
    private var last = 0L
    private val overshoot = OvershootInterpolator(2.6f)
    private val ticker = FrameTicker { now -> frame(now) }

    init {
        heart.moveTo(0f, 0.9f)
        heart.cubicTo(-1.25f, 0.05f, -0.75f, -1.0f, 0f, -0.38f)
        heart.cubicTo(0.75f, -1.0f, 1.25f, 0.05f, 0f, 0.9f)
        heart.close()
        isClickable = false
        isFocusable = false
    }

    fun setColors(accent: Int, second: Int) {
        colors = intArrayOf(0xFFFF2D55.toInt(), accent, second, 0xFFFFC93C.toInt(), 0xFFFF5C8A.toInt(), 0xFFB388FF.toInt(), Color.WHITE)
    }

    /** @param cx,cy centre of the burst (usually the cover centre), in this view's coordinates. */
    fun celebrate(cx: Float, cy: Float, size: Float) {
        love = true
        bigX = cx; bigY = cy; bigSize = size; bigT = 0f
        rings = 0
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        ticker.start()
    }

    fun unlove(cx: Float, cy: Float, size: Float) {
        love = false
        bigX = cx; bigY = cy; bigSize = size * 0.6f; bigT = 0f
        ticker.start()
    }

    private fun burst() {
        // hearts flying outwards and up, then raining down over the whole screen
        repeat(70) { spawn(0, bigX, bigY, r(260f, 900f), r(18f, 40f) * 0.5f) }
        repeat(90) { spawn(1, bigX, bigY, r(300f, 1100f), r(4f, 8f)) }
        repeat(40) { spawn(2, bigX, bigY, r(150f, 700f), r(3f, 7f)) }
        // a second wave of hearts falling from the top edge so the shower fills the screen
        repeat(18) {
            val i = add() ?: return@repeat
            kind[i] = 0; x[i] = r(0f, width.toFloat()); y[i] = -r(10f, 160f) * d
            vx[i] = r(-40f, 40f) * d; vy[i] = r(60f, 220f) * d; max[i] = r(1.8f, 2.6f)
            size[i] = r(9f, 18f) * d; rot[i] = r(-30f, 30f); vr[i] = r(-90f, 90f); col[i] = colors[rnd.nextInt(colors.size)]
        }
        rings = 3; ringT[0] = 0f; ringT[1] = -0.08f; ringT[2] = -0.18f
    }

    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    private fun add(): Int? {
        if (count >= n) return null
        val i = count++
        life[i] = 0f
        return i
    }

    private fun spawn(k: Int, cx: Float, cy: Float, speed: Float, sz: Float) {
        val i = add() ?: return
        val ang = r(0f, 6.283f)
        kind[i] = k
        x[i] = cx + cos(ang) * bigSize * 0.25f; y[i] = cy + sin(ang) * bigSize * 0.25f
        vx[i] = cos(ang) * speed * d; vy[i] = sin(ang) * speed * d - r(150f, 450f) * d
        max[i] = when (k) { 0 -> r(1.6f, 2.6f); 1 -> r(1.8f, 3f); else -> r(0.6f, 1.3f) }
        size[i] = sz * d * (if (k == 0) 1.4f else 1f)
        rot[i] = r(0f, 360f); vr[i] = r(-540f, 540f)
        col[i] = colors[rnd.nextInt(colors.size)]
    }

    private fun frame(now: Long) {
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now; time += dt
        if (bigT >= 0f) {
            val before = bigT
            bigT += dt
            if (love && before < 0.32f && bigT >= 0.32f) burst()
            if (bigT > (if (love) 1.1f else 0.55f)) bigT = -1f
        }
        for (k in 0 until rings) ringT[k] += dt
        if (rings > 0 && ringT[rings - 1] > 0.9f) rings = 0
        var i = 0
        while (i < count) {
            life[i] += dt
            if (life[i] >= max[i] || y[i] > height + 60 * d) {
                val l = count - 1
                x[i] = x[l]; y[i] = y[l]; vx[i] = vx[l]; vy[i] = vy[l]; life[i] = life[l]; max[i] = max[l]
                size[i] = size[l]; rot[i] = rot[l]; vr[i] = vr[l]; col[i] = col[l]; kind[i] = kind[l]
                count--; continue
            }
            val g = when (kind[i]) { 0 -> 520f; 1 -> 700f; else -> 300f } * d
            val drag = when (kind[i]) { 0 -> 1.6f; 1 -> 2.4f; else -> 2f }
            vy[i] += g * dt
            vx[i] *= 1f - drag * dt; vy[i] *= 1f - drag * dt * 0.6f
            x[i] += vx[i] * dt; y[i] += vy[i] * dt
            rot[i] += vr[i] * dt
            i++
        }
        invalidate()
        if (bigT < 0f && count == 0 && rings == 0) { ticker.stop(); last = 0L }
    }

    override fun onDraw(c: Canvas) {
        // shock-wave rings
        for (k in 0 until rings) {
            val t = ringT[k]
            if (t < 0f) continue
            val f = min(1f, t / 0.9f)
            s.color = colors[k % colors.size]; s.alpha = (220 * (1 - f)).toInt()
            s.strokeWidth = (14 * (1 - f) + 1) * d
            c.drawCircle(bigX, bigY, bigSize * 0.4f + f * max(width, height) * 0.75f, s)
        }
        // particles
        for (i in 0 until count) {
            val f = life[i] / max[i]
            val a = if (f > 0.75f) (1 - f) / 0.25f else 1f
            c.save(); c.translate(x[i], y[i]); c.rotate(rot[i])
            when (kind[i]) {
                0 -> {
                    val sc = size[i] * (0.9f + 0.15f * sin(time * 10 + i))
                    c.scale(sc, sc)
                    p.color = col[i]; p.alpha = (240 * a).toInt()
                    c.drawPath(heart, p)
                    p.color = Color.WHITE; p.alpha = (110 * a).toInt()
                    c.drawCircle(-0.38f, -0.32f, 0.16f, p)
                }
                1 -> {
                    // confetti ribbon: flips as it falls
                    val flip = cos(time * 9 + i)
                    p.color = col[i]; p.alpha = (255 * a).toInt()
                    rect.set(-size[i], -size[i] * 0.45f * flip, size[i], size[i] * 0.45f * flip)
                    c.drawRect(rect, p)
                }
                else -> {
                    val tw = 0.5f + 0.5f * sin(time * 20 + i * 3)
                    p.color = Color.WHITE; p.alpha = (255 * a * tw).toInt()
                    val sz = size[i] * (0.6f + 0.6f * tw)
                    c.drawRect(-sz * 0.18f, -sz, sz * 0.18f, sz, p)
                    c.drawRect(-sz, -sz * 0.18f, sz, sz * 0.18f, p)
                }
            }
            c.restore()
        }
        // the big heart: pops with an overshoot, glows, then lifts away as the burst happens
        if (bigT >= 0f) {
            if (love) {
                val pop = overshoot.getInterpolation(min(1f, bigT / 0.32f))
                val out = max(0f, (bigT - 0.32f) / 0.78f)
                val sc = bigSize * 0.5f * (pop + out * 0.5f)
                val a = 1f - out
                p.color = colors[0]; p.alpha = (70 * a).toInt()
                c.drawCircle(bigX, bigY, sc * 1.6f, p)
                c.save(); c.translate(bigX, bigY - out * 40 * d); c.scale(sc, sc)
                p.color = colors[0]; p.alpha = (255 * a).toInt()
                c.drawPath(heart, p)
                p.color = ColorUtils.blendARGB(colors[0], Color.WHITE, 0.55f); p.alpha = (200 * a).toInt()
                c.drawCircle(-0.4f, -0.3f, 0.2f, p)
                c.restore()
            } else {
                val t = min(1f, bigT / 0.55f)
                val sc = bigSize * 0.5f * (1f - t * 0.6f)
                c.save(); c.translate(bigX, bigY + t * 30 * d); c.scale(sc, sc)
                p.color = 0xFF9E9E9E.toInt(); p.alpha = (160 * (1 - t)).toInt()
                c.drawPath(heart, p)
                c.restore()
            }
        }
    }
}
