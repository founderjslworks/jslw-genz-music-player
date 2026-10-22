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

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
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
