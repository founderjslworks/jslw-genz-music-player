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
        class Room(val rt60: Double, val id: String = "") : Kind()
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
            is Kind.Room -> drawRoom(c, b, k)
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

    /**
     * Each room as its own little picture (its shape and size), with a line underneath showing how long its echo
     * lasts – so Small Room, Concert Hall, Temple or Cathedral are told apart at a glance.
     */
    private fun drawRoom(c: Canvas, b: RectF, k: Kind.Room) {
        val w = b.width(); val h = b.height()
        val ground = b.top + h * 0.74f
        val cx = b.left + w / 2
        line.strokeWidth = h * 0.045f
        fill.color = faint; fill.alpha = 70
        val solid = Paint(fill).apply { color = color0(); alpha = 255 }
        fun box(bw: Float, bh: Float, columns: Int = 0) {
            val r = RectF(cx - w * bw / 2, ground - h * bh, cx + w * bw / 2, ground)
            c.drawRect(r, fill); c.drawRect(r, line)
            for (i in 1..columns) {
                val x = r.left + r.width() * i / (columns + 1)
                c.drawLine(x, r.top + h * 0.06f, x, r.bottom, Paint(line).apply { strokeWidth = h * 0.03f; alpha = 170 })
            }
        }
        when (k.id) {
            "small_room" -> { box(0.3f, 0.3f); c.drawCircle(cx, ground - h * 0.12f, h * 0.045f, solid) }
            "medium_room" -> { box(0.45f, 0.42f); c.drawCircle(cx, ground - h * 0.15f, h * 0.05f, solid) }
            "large_room" -> { box(0.66f, 0.56f); c.drawCircle(cx, ground - h * 0.18f, h * 0.055f, solid) }
            "medium_hall" -> box(0.7f, 0.46f, 3)
            "large_hall" -> box(0.88f, 0.6f, 5)
            "party_hall" -> {
                box(0.78f, 0.6f)
                val by = ground - h * 0.42f
                c.drawLine(cx, ground - h * 0.6f, cx, by - h * 0.07f, line)
                c.drawCircle(cx, by, h * 0.07f, solid)
                val ray = Paint(line).apply { strokeWidth = h * 0.025f; alpha = 180 }
                for (a in listOf(-60.0, -30.0, 30.0, 60.0, 150.0, 210.0)) {
                    val rad = Math.toRadians(a + 90)
                    c.drawLine(cx + (Math.cos(rad) * h * 0.11).toFloat(), by + (Math.sin(rad) * h * 0.11).toFloat(),
                        cx + (Math.cos(rad) * h * 0.22).toFloat(), by + (Math.sin(rad) * h * 0.22).toFloat(), ray)
                }
            }
            "concert_hall" -> {
                // stage shell in front, tiered seats rising behind the listener
                val shell = RectF(cx - w * 0.22f, ground - h * 0.62f, cx + w * 0.22f, ground - h * 0.1f)
                c.drawArc(shell, 180f, 180f, false, line)
                c.drawLine(shell.left, shell.centerY(), shell.right, shell.centerY(), line)
                val seat = Paint(line).apply { strokeWidth = h * 0.035f; alpha = 190 }
                for (i in 0..2) {
                    val y = ground - h * (0.02f + i * 0.04f)
                    val half = w * (0.32f + i * 0.08f)
                    c.drawLine(cx - half, y, cx - w * 0.24f, y, seat); c.drawLine(cx + w * 0.24f, y, cx + half, y, seat)
                }
            }
            "plate" -> {
                // a hanging metal plate and its vibration
                val r = RectF(cx - w * 0.2f, b.top + h * 0.18f, cx + w * 0.2f, ground)
                c.drawRect(r, fill); c.drawRect(r, line)
                val v = Paint(line).apply { strokeWidth = h * 0.028f; alpha = 170 }
                for (i in 1..3) {
                    val x0 = r.right + w * 0.05f * i
                    c.drawArc(RectF(x0 - w * 0.06f, r.centerY() - h * 0.12f * i / 1.5f, x0 + w * 0.02f, r.centerY() + h * 0.12f * i / 1.5f), -60f, 120f, false, v)
                }
            }
            "temple" -> {
                // shikhara: stepped tower, kalash and flag on a plinth
                path.reset()
                val baseW = w * 0.42f; val top = b.top + h * 0.16f
                path.moveTo(cx - baseW / 2, ground)
                path.lineTo(cx - baseW / 2, ground - h * 0.16f)
                path.lineTo(cx - baseW * 0.36f, ground - h * 0.2f)
                path.quadTo(cx - baseW * 0.3f, top + h * 0.1f, cx, top)
                path.quadTo(cx + baseW * 0.3f, top + h * 0.1f, cx + baseW * 0.36f, ground - h * 0.2f)
                path.lineTo(cx + baseW / 2, ground - h * 0.16f)
                path.lineTo(cx + baseW / 2, ground); path.close()
                c.drawPath(path, fill); c.drawPath(path, line)
                c.drawCircle(cx, top - h * 0.03f, h * 0.035f, solid)
                c.drawLine(cx, top - h * 0.06f, cx, top - h * 0.16f, line)
                c.drawLine(cx, top - h * 0.16f, cx + w * 0.07f, top - h * 0.12f, line)
                val door = RectF(cx - w * 0.05f, ground - h * 0.12f, cx + w * 0.05f, ground)
                c.drawArc(RectF(door.left, door.top - h * 0.04f, door.right, door.top + h * 0.04f), 180f, 180f, false, line)
                c.drawLine(door.left, door.top, door.left, ground, line); c.drawLine(door.right, door.top, door.right, ground, line)
            }
            "cathedral" -> {
                // gothic nave with a pointed arch, rose window and spire
                path.reset()
                val half = w * 0.24f; val shoulder = ground - h * 0.32f; val apex = b.top + h * 0.2f
                path.moveTo(cx - half, ground); path.lineTo(cx - half, shoulder)
                path.quadTo(cx - half, apex + h * 0.08f, cx, apex)
                path.quadTo(cx + half, apex + h * 0.08f, cx + half, shoulder)
                path.lineTo(cx + half, ground); path.close()
                c.drawPath(path, fill); c.drawPath(path, line)
                c.drawLine(cx, apex, cx, b.top + h * 0.02f, line)
                c.drawLine(cx - w * 0.04f, b.top + h * 0.08f, cx + w * 0.04f, b.top + h * 0.08f, line)
                c.drawCircle(cx, shoulder - h * 0.04f, h * 0.07f, Paint(line).apply { strokeWidth = h * 0.03f })
                val door = Path().apply {
                    moveTo(cx - w * 0.06f, ground); lineTo(cx - w * 0.06f, ground - h * 0.12f)
                    quadTo(cx - w * 0.06f, ground - h * 0.2f, cx, ground - h * 0.22f)
                    quadTo(cx + w * 0.06f, ground - h * 0.2f, cx + w * 0.06f, ground - h * 0.12f); lineTo(cx + w * 0.06f, ground)
                }
                c.drawPath(door, line)
            }
            "off" -> {
                // open air: no walls, just the ground and a struck-out room
                val r = RectF(cx - w * 0.22f, ground - h * 0.4f, cx + w * 0.22f, ground)
                c.drawRect(r, Paint(line).apply { alpha = 90 })
                c.drawLine(r.left - w * 0.04f, r.bottom, r.right + w * 0.04f, r.top, line)
            }
            else -> {
                // "Preset's room": the preset's own room – a room with a music note inside
                box(0.5f, 0.46f)
                val nx = cx + w * 0.02f; val ny = ground - h * 0.12f
                c.drawCircle(nx - w * 0.04f, ny, h * 0.05f, solid)
                c.drawLine(nx - w * 0.04f + h * 0.045f, ny, nx - w * 0.04f + h * 0.045f, ny - h * 0.24f, line)
                c.drawLine(nx - w * 0.04f + h * 0.045f, ny - h * 0.24f, nx + w * 0.06f, ny - h * 0.2f, line)
            }
        }
        // the echo: a line whose length shows how long the room rings
        if (k.rt60 > 0 && k.id != "off" && k.id != "mood") {
            val y = b.bottom - h * 0.08f
            val len = (w * 0.86f * (k.rt60 / 3.2).coerceIn(0.08, 1.0)).toFloat()
            val x0 = b.left + w * 0.07f
            val e = Paint(line).apply { strokeWidth = h * 0.04f }
            val steps = 14
            for (i in 0 until steps) {
                e.alpha = (230 * exp(-3.0 * i / steps)).toInt()
                c.drawLine(x0 + len * i / steps, y, x0 + len * (i + 1) / steps, y, e)
            }
        }
    }

    private fun color0() = color

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
