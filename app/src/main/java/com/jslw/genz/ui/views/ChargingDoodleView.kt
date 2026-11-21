package com.jslw.genz.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.jslw.genz.util.FrameTicker
import kotlin.math.sin
import kotlin.random.Random

/**
 * Charging doodle for the lock-screen player: a battery filling with a sloshing liquid and rising
 * bubbles, the percentage, and a little seasonal companion (blossom, sun, leaf or snowflake) bobbing beside it.
 */
class ChargingDoodleView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var level = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var accent = 0xFF34C759.toInt()
    var typeface: android.graphics.Typeface? = null
    /** liquid · ring · pulse · segments · orbit · wave (see [STYLES]). */
    var style = "liquid"
        set(v) { field = if (STYLES.any { it.first == v }) v else "liquid"; invalidate() }

    companion object {
        val STYLES = listOf(
            "liquid" to "Liquid battery", "ring" to "Glow ring", "pulse" to "Power pulse",
            "segments" to "Battery bars", "orbit" to "Orbiting sparks", "wave" to "Energy wave",
        )
    }

    private val d = resources.displayMetrics.density
    private val body = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.LEFT }
    private val wave = Path()
    private val clip = Path()
    private val shape = Path()
    private var time = 0f
    private var last = 0L
    private val bx = FloatArray(14); private val by = FloatArray(14); private val bs = FloatArray(14)
    private val rnd = Random(3)
    private val season = CoverEffectView.seasonNow()
    private val ticker = FrameTicker { now ->
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now; time += dt
        for (i in bx.indices) {
            by[i] -= dt * (0.25f + bs[i] * 0.1f)
            if (by[i] < 0f) { by[i] = 1f; bx[i] = rnd.nextFloat() }
        }
        invalidate()
    }

    init {
        for (i in bx.indices) { bx[i] = rnd.nextFloat(); by[i] = rnd.nextFloat(); bs[i] = rnd.nextFloat() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize((200 * d).toInt(), widthMeasureSpec), resolveSize((56 * d).toInt(), heightMeasureSpec))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.start() }
    override fun onDetachedFromWindow() { ticker.stop(); last = 0L; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.start() else { ticker.stop(); last = 0L }
    }

    private fun levelColor() = when {
        level < 0.2f -> 0xFFFF3B30.toInt()
        level < 0.5f -> 0xFFFFCC00.toInt()
        else -> accent
    }

    /** Percentage and "Charging" beside the picture, starting at [x]. */
    private fun label(c: Canvas, x: Float, h: Float) {
        text.textSize = 18 * d; text.typeface = typeface
        c.drawText("${(level * 100).toInt()}%", x, h / 2 - 1 * d, text)
        text.textSize = 11 * d; text.alpha = 190
        c.drawText(if (level >= 1f) "Charged" else "Charging", x, h / 2 + 14 * d, text)
        text.alpha = 255
    }

    private fun bolt(c: Canvas, cx: Float, cy: Float, k: Float, alpha: Int) {
        shape.reset()
        shape.moveTo(cx + 2 * d * k, cy - 11 * d * k); shape.lineTo(cx - 6 * d * k, cy + 2 * d * k); shape.lineTo(cx - 0.5f * d * k, cy + 2 * d * k)
        shape.lineTo(cx - 2 * d * k, cy + 11 * d * k); shape.lineTo(cx + 6 * d * k, cy - 2 * d * k); shape.lineTo(cx + 0.5f * d * k, cy - 2 * d * k); shape.close()
        p.color = Color.WHITE; p.alpha = alpha.coerceIn(0, 255); c.drawPath(shape, p); p.alpha = 255
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        when (style) {
            "ring" -> { drawRing(c, h); return }
            "pulse" -> { drawPulse(c, h); return }
            "segments" -> { drawSegments(c, h); return }
            "orbit" -> { drawOrbit(c, h); return }
            "wave" -> { drawWave(c, h); return }
        }
        // companion on the left
        drawCompanion(c, 22 * d, h / 2 + sin(time * 2.2f) * 4 * d)
        // battery
        val left = 52 * d; val top = h / 2 - 15 * d
        body.set(left, top, left + 66 * d, top + 30 * d)
        clip.reset(); clip.addRoundRect(body, 8 * d, 8 * d, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        p.color = 0x33FFFFFF; c.drawRect(body, p)
        val fillTop = body.bottom - body.height() * level
        wave.reset(); wave.moveTo(body.left, body.bottom)
        var x = body.left
        while (x <= body.right) {
            wave.lineTo(x, fillTop + sin((x - body.left) / (10 * d) + time * 4f) * 2.2f * d)
            x += 3 * d
        }
        wave.lineTo(body.right, body.bottom); wave.close()
        val col = when {
            level < 0.2f -> 0xFFFF3B30.toInt()
            level < 0.5f -> 0xFFFFCC00.toInt()
            else -> accent
        }
        p.color = col; c.drawPath(wave, p)
        p.color = Color.WHITE
        for (i in bx.indices) {
            val yy = body.bottom - (1f - by[i]) * body.height() * level
            if (yy < fillTop) continue
            p.alpha = (150 * by[i]).toInt()
            c.drawCircle(body.left + bx[i] * body.width(), yy, (1 + bs[i] * 1.8f) * d, p)
        }
        p.alpha = 255
        c.restore()
        s.color = Color.WHITE; s.strokeWidth = 2 * d
        c.drawRoundRect(body, 8 * d, 8 * d, s)
        p.color = Color.WHITE
        c.drawRoundRect(body.right + 2 * d, h / 2 - 6 * d, body.right + 6 * d, h / 2 + 6 * d, 2 * d, 2 * d, p)
        // bolt
        shape.reset()
        val cx = body.centerX(); val cy = body.centerY()
        shape.moveTo(cx + 2 * d, cy - 11 * d); shape.lineTo(cx - 6 * d, cy + 2 * d); shape.lineTo(cx - 0.5f * d, cy + 2 * d)
        shape.lineTo(cx - 2 * d, cy + 11 * d); shape.lineTo(cx + 6 * d, cy - 2 * d); shape.lineTo(cx + 0.5f * d, cy - 2 * d); shape.close()
        p.color = Color.WHITE; p.alpha = (200 + 55 * sin(time * 3f)).toInt().coerceIn(0, 255)
        c.drawPath(shape, p); p.alpha = 255
        // percentage
        text.textSize = 18 * d; text.typeface = typeface
        c.drawText("${(level * 100).toInt()}%", body.right + 14 * d, h / 2 - 1 * d, text)
        text.textSize = 11 * d; text.alpha = 190
        c.drawText(if (level >= 1f) "Charged" else "Charging", body.right + 14 * d, h / 2 + 14 * d, text)
        text.alpha = 255
    }

    // ---------------------------------------------------------------- other styles
    private val arc = RectF()

    /** A glowing progress ring that fills with the level, a light running around it. */
    private fun drawRing(c: Canvas, h: Float) {
        val cx = 60 * d; val cy = h / 2; val r = 22 * d
        arc.set(cx - r, cy - r, cx + r, cy + r)
        s.strokeWidth = 5 * d; s.color = 0x33FFFFFF; c.drawCircle(cx, cy, r, s)
        val col = levelColor()
        s.color = ColorUtils.setAlphaComponent(col, 70); s.strokeWidth = 11 * d
        c.drawArc(arc, -90f, 360f * level, false, s)
        s.color = col; s.strokeWidth = 5 * d
        c.drawArc(arc, -90f, 360f * level, false, s)
        // running spark
        val a = Math.toRadians((time * 140.0) % 360 - 90)
        p.color = Color.WHITE; p.alpha = 220
        c.drawCircle(cx + r * Math.cos(a).toFloat(), cy + r * Math.sin(a).toFloat(), 3 * d, p); p.alpha = 255
        bolt(c, cx, cy, 0.9f, (200 + 55 * sin(time * 3f)).toInt())
        label(c, cx + r + 18 * d, h)
    }

    /** A bolt sending out rings of power, like a heartbeat. */
    private fun drawPulse(c: Canvas, h: Float) {
        val cx = 60 * d; val cy = h / 2
        val col = levelColor()
        for (k in 0 until 3) {
            val t = ((time * 0.8f + k / 3f) % 1f)
            s.color = col; s.alpha = ((1f - t) * 200).toInt(); s.strokeWidth = 2.5f * d
            c.drawCircle(cx, cy, (8 + t * 24) * d, s)
        }
        s.alpha = 255
        p.color = col; c.drawCircle(cx, cy, (13 + sin(time * 6f) * 1.2f) * d, p)
        bolt(c, cx, cy, 0.95f, 255)
        label(c, cx + 40 * d, h)
    }

    /** Ten bars light up one by one up to the level, then the next bar blinks while charging. */
    private fun drawSegments(c: Canvas, h: Float) {
        val left = 40 * d; val top = h / 2 - 13 * d
        body.set(left, top, left + 86 * d, top + 26 * d)
        s.color = Color.WHITE; s.strokeWidth = 2 * d
        c.drawRoundRect(body, 6 * d, 6 * d, s)
        p.color = Color.WHITE
        c.drawRoundRect(body.right + 2 * d, h / 2 - 5 * d, body.right + 6 * d, h / 2 + 5 * d, 2 * d, 2 * d, p)
        val n = 10; val gap = 2 * d
        val w = (body.width() - 8 * d - gap * (n - 1)) / n
        val lit = (level * n).toInt().coerceIn(0, n)
        val col = levelColor()
        for (i in 0 until n) {
            val x = body.left + 4 * d + i * (w + gap)
            p.color = when {
                i < lit -> col
                i == lit && level < 1f -> ColorUtils.setAlphaComponent(col, (60 + 195 * (0.5f + 0.5f * sin(time * 6f))).toInt())
                else -> 0x26FFFFFF
            }
            c.drawRoundRect(x, body.top + 4 * d, x + w, body.bottom - 4 * d, 2 * d, 2 * d, p)
        }
        label(c, body.right + 16 * d, h)
    }

    /** A glowing core with sparks circling it – more sparks the fuller the battery. */
    private fun drawOrbit(c: Canvas, h: Float) {
        val cx = 60 * d; val cy = h / 2
        val col = levelColor()
        p.color = ColorUtils.setAlphaComponent(col, 60); c.drawCircle(cx, cy, 18 * d, p)
        p.color = col; c.drawCircle(cx, cy, (10 + sin(time * 4f) * 1.5f) * d, p)
        val count = 3 + (level * 9).toInt()
        for (i in 0 until count) {
            val sp = 1f + (i % 3) * 0.35f
            val r = (16 + (i % 3) * 6) * d
            val a = time * sp * 2.2f + i * (6.283f / count)
            p.color = Color.WHITE; p.alpha = 120 + (i * 37) % 135
            c.drawCircle(cx + r * kotlin.math.cos(a), cy + r * 0.55f * sin(a), (1.6f + (i % 2)) * d, p)
        }
        p.alpha = 255
        bolt(c, cx, cy, 0.6f, 255)
        label(c, cx + 40 * d, h)
    }

    /** An energy line pulsing through a capsule, its height growing with the level. */
    private fun drawWave(c: Canvas, h: Float) {
        val left = 36 * d; val top = h / 2 - 15 * d
        body.set(left, top, left + 90 * d, top + 30 * d)
        p.color = 0x22FFFFFF; c.drawRoundRect(body, 15 * d, 15 * d, p)
        clip.reset(); clip.addRoundRect(body, 15 * d, 15 * d, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        val col = levelColor()
        val amp = (3 + level * 9) * d
        for (k in 0 until 2) {
            wave.reset()
            var x = body.left
            wave.moveTo(x, body.centerY())
            while (x <= body.right) {
                val t = (x - body.left) / body.width()
                wave.lineTo(x, body.centerY() + sin(t * 12f - time * (5f + k * 2f) + k) * amp * sin(t * 3.1416f))
                x += 2 * d
            }
            s.color = if (k == 0) col else ColorUtils.setAlphaComponent(Color.WHITE, 150)
            s.strokeWidth = (if (k == 0) 3f else 1.5f) * d
            c.drawPath(wave, s)
        }
        // filled part of the capsule
        p.color = ColorUtils.setAlphaComponent(col, 50)
        c.drawRect(body.left, body.top, body.left + body.width() * level, body.bottom, p)
        c.restore()
        s.color = Color.WHITE; s.strokeWidth = 1.5f * d
        c.drawRoundRect(body, 15 * d, 15 * d, s)
        label(c, body.right + 14 * d, h)
    }

    private fun drawCompanion(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y)
        when (season) {
            "spring" -> {
                c.rotate(time * 30f)
                p.color = 0xFFFFB7C5.toInt()
                for (k in 0 until 5) { c.drawCircle(0f, -7 * d, 5.5f * d, p); c.rotate(72f) }
                p.color = 0xFFFFD54F.toInt(); c.drawCircle(0f, 0f, 4 * d, p)
            }
            "summer" -> {
                c.rotate(time * 40f)
                s.color = 0xFFFFC107.toInt(); s.strokeWidth = 2.5f * d
                for (k in 0 until 8) { c.drawLine(0f, 10 * d, 0f, 15 * d, s); c.rotate(45f) }
                p.color = 0xFFFFD54F.toInt(); c.drawCircle(0f, 0f, 8 * d, p)
            }
            "autumn" -> {
                c.rotate(sin(time * 2f) * 20f)
                shape.reset()
                shape.moveTo(0f, -13 * d); shape.cubicTo(10 * d, -6 * d, 10 * d, 6 * d, 0f, 12 * d)
                shape.cubicTo(-10 * d, 6 * d, -10 * d, -6 * d, 0f, -13 * d); shape.close()
                p.color = 0xFFFF8F00.toInt(); c.drawPath(shape, p)
                s.color = ColorUtils.blendARGB(0xFFFF8F00.toInt(), Color.BLACK, 0.4f); s.strokeWidth = 1.5f * d
                c.drawLine(0f, -10 * d, 0f, 15 * d, s)
            }
            else -> {
                c.rotate(time * 25f)
                s.color = Color.WHITE; s.strokeWidth = 2 * d
                for (k in 0 until 6) {
                    c.drawLine(0f, 0f, 0f, -13 * d, s)
                    c.drawLine(0f, -7 * d, 4 * d, -11 * d, s); c.drawLine(0f, -7 * d, -4 * d, -11 * d, s)
                    c.rotate(60f)
                }
            }
        }
        c.restore()
    }
}
