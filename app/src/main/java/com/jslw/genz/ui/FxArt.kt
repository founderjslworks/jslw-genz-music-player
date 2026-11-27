package com.jslw.genz.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.jslworks.soundengine.dsp.BiquadType
import com.jslworks.soundengine.dsp.EqBand
import com.jslworks.soundengine.dsp.ParametricEqualizer
import com.jslworks.soundengine.dsp.SoundPreset
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Small explanatory graphics for JSL Audio+, drawn in the theme's colours so the user can SEE what an option does:
 * a preset's real frequency curve (computed by the JSL Audio Engine), a room's echo decay, and the three stage modes.
 */
class FxArt(private val kind: Kind, private val color: Int, private val faint: Int) : Drawable() {

    sealed class Kind {
        /** Frequency response, dB at 48 log-spaced points (20 Hz – 20 kHz). */
        class Curve(val db: FloatArray) : Kind()
        object Auto : Kind()
        class Room(val rt60: Double) : Kind()
        object Stage : Kind()
        object Separation : Kind()
        object Instruments : Kind()
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = RectF(bounds)
        val w = b.width(); val h = b.height()
        if (w <= 0 || h <= 0) return
        line.color = color; line.strokeWidth = h * 0.06f
        fill.color = faint
        when (val k = kind) {
            is Kind.Curve -> {
                val mid = b.top + h / 2
                fill.alpha = 60
                c.drawLine(b.left, mid, b.right, mid, Paint(fill).apply { strokeWidth = h * 0.02f; style = Paint.Style.STROKE })
                path.reset()
                for (i in k.db.indices) {
                    val x = b.left + w * i / (k.db.size - 1)
                    val y = mid - (k.db[i] / 8f).coerceIn(-1f, 1f) * h * 0.42f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val area = Path(path).apply { lineTo(b.right, mid); lineTo(b.left, mid); close() }
                fill.alpha = 90; c.drawPath(area, fill)
                c.drawPath(path, line)
            }
            Kind.Auto -> {
                // waveform being "read" + a spark
                path.reset()
                for (i in 0..40) {
                    val t = i / 40f
                    val x = b.left + w * 0.05f + t * w * 0.62f
                    val y = b.top + h / 2 - sin(t * 6 * PI).toFloat() * h * 0.28f * (1 - t * 0.5f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                c.drawPath(path, line)
                val cx = b.left + w * 0.82f; val cy = b.top + h * 0.45f; val r = h * 0.32f
                fill.alpha = 255; fill.color = color
                val star = Path().apply {
                    moveTo(cx, cy - r); quadTo(cx, cy, cx + r, cy); quadTo(cx, cy, cx, cy + r)
                    quadTo(cx, cy, cx - r, cy); quadTo(cx, cy, cx, cy - r); close()
                }
                c.drawPath(star, fill)
            }
            is Kind.Room -> {
                // direct sound, early reflections, then the decay envelope (longer room = longer tail)
                val base = b.bottom - h * 0.1f
                val span = 3.0
                fill.alpha = 70
                path.reset(); path.moveTo(b.left + w * 0.08f, b.top + h * 0.12f)
                for (i in 0..60) {
                    val t = i / 60.0 * span
                    val env = exp(-6.91 * t / k.rt60).toFloat()
                    path.lineTo(b.left + w * 0.08f + (w * 0.9f) * (i / 60f), base - (base - b.top - h * 0.12f) * env)
                }
                path.lineTo(b.right - w * 0.02f, base); path.lineTo(b.left + w * 0.08f, base); path.close()
                c.drawPath(path, fill)
                val tick = Paint(line).apply { strokeWidth = h * 0.05f }
                c.drawLine(b.left + w * 0.08f, base, b.left + w * 0.08f, b.top + h * 0.1f, tick)
                for ((i, f) in listOf(0.14f, 0.19f, 0.23f, 0.28f).withIndex()) {
                    val top = b.top + h * (0.35f + i * 0.1f)
                    c.drawLine(b.left + w * f, base, b.left + w * f, top, tick)
                }
            }
            Kind.Stage -> {
                // listener + widening arcs in front
                val cx = b.left + w / 2; val cy = b.bottom - h * 0.12f
                fill.alpha = 255; fill.color = color
                c.drawCircle(cx, cy, h * 0.1f, fill)
                for (i in 1..3) {
                    val r = h * (0.25f + i * 0.2f)
                    line.alpha = 255 - i * 55
                    c.drawArc(RectF(cx - r * 1.4f, cy - r, cx + r * 1.4f, cy + r), 200f, 140f, false, line)
                }
                line.alpha = 255
            }
            Kind.Separation -> {
                // voice (bold) lifted out of the band (faint)
                fun wave(yc: Float, amp: Float, cycles: Float, p: Paint) {
                    path.reset()
                    for (i in 0..48) {
                        val t = i / 48f
                        val x = b.left + w * 0.06f + t * w * 0.88f
                        val y = yc - sin(t * cycles * 2 * PI).toFloat() * amp
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    c.drawPath(path, p)
                }
                wave(b.top + h * 0.72f, h * 0.12f, 5f, Paint(line).apply { color = faint; alpha = 160 })
                wave(b.top + h * 0.3f, h * 0.16f, 2f, line)
            }
            Kind.Instruments -> {
                // bass centred and big, instruments spread out to the sides
                fill.color = color; fill.alpha = 255
                val cy = b.top + h / 2
                c.drawCircle(b.left + w / 2, cy, h * 0.2f, fill)
                fill.alpha = 200
                for ((x, y, r) in listOf(Triple(0.14f, 0.35f, 0.11f), Triple(0.3f, 0.7f, 0.09f), Triple(0.7f, 0.3f, 0.09f), Triple(0.86f, 0.65f, 0.11f))) {
                    c.drawCircle(b.left + w * x, b.top + h * y, h * r, fill)
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** The real response of a preset (tone tilt + bass + Melody), from the engine's own filters. */
        fun presetCurve(p: SoundPreset): FloatArray {
            val eq = ParametricEqualizer(1, 48000)
            eq.setBand(0, EqBand(BiquadType.LowShelf, 110.0, p.lowShelfDb, 0.707))
            eq.setBand(1, EqBand(BiquadType.HighShelf, 8000.0, p.highShelfDb, 0.707))
            eq.setBand(2, EqBand(BiquadType.Peaking, 70.0, 4.0 * p.bassBoost, 1.0))      // perceived lift from virtual bass
            eq.setBand(3, EqBand(BiquadType.Peaking, 2500.0, 4.0 * p.melody, 0.9))
            eq.setBand(4, EqBand(BiquadType.Peaking, 300.0, -2.0 * p.melody, 1.0))
            return FloatArray(48) { i ->
                val f = 20.0 * 10.0.pow(i / 47.0 * log10(1000.0))
                eq.responseDb(f).toFloat()
            }
        }
    }
}
