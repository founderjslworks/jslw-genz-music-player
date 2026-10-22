package com.jslw.genz.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.jslw.genz.util.FrameTicker
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Themed, animated progress / seek bar. Each theme picks a style:
 *
 *  sparkle  – glowing gradient, twinkling stars rising and falling      (play-head: glow)
 *  neon     – flickering neon tube with electric sparks                  (ring)
 *  wave     – the played part is a living sine wave                      (glow)
 *  bars     – spectrum / VU bars dancing to the beat                     (light beam)
 *  pixel    – 8-bit blocks with pixel debris                             (pixel block)
 *  fire     – embers rising from a flickering flame                      (flame / diya)
 *  bubbles  – bubbles drifting up from the track                         (ring)
 *  petals   – petals floating down past the play-head                    (glow)
 *  hearts   – hearts floating up from a beating heart                    (heart)
 *  notes    – music notes rising from a spinning record                  (vinyl)
 *  snow     – soft snowfall across the bar                               (glow)
 *  tape     – moving cassette tape                                       (tape reel)
 *  classic  – glossy iPod-style bar                                      (dot)
 *  dots     – dot-matrix level meter                                     (dot column)
 *
 * Runs on the display's vsync (60–144 Hz) only while music plays or particles are still moving.
 * Fixed particle pool and reused paths: no allocations per frame.
 */
class SparkleSeekBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var compact = false
    var max = 1f
        set(v) { field = v.coerceAtLeast(1f); invalidate() }
    var progress = 0f
        set(v) { if (!dragging) { field = v.coerceIn(0f, max); invalidate() } }
    var playing = false
        set(v) { field = v; if (v) ticker.start() }

    var onSeekStart: (() -> Unit)? = null
    var onSeeking: ((Float) -> Unit)? = null
    var onSeekEnd: ((Float) -> Unit)? = null

    // ---------------------------------------------------------------- style
    private var trackKind = T_LINE
    private var partKind = P_STAR
    private var headKind = H_GLOW
    private var neon = false
    private var shimmerOn = true
    var styleName = "sparkle"; private set

    private var accent = 0xFFFF3D9A.toInt()
    private var custom: IntArray? = null
    private var c0 = 0xFF7B2FF7.toInt()
    private var c1 = accent
    private var c2 = Color.WHITE
    private var trackColor = 0x40FFFFFF

    fun setStyle(style: String, colors: IntArray?) {
        styleName = style
        custom = colors
        neon = false; shimmerOn = true
        when (style) {
            "neon" -> { trackKind = T_LINE; partKind = P_SPARK; headKind = H_RING; neon = true }
            "wave" -> { trackKind = T_WAVE; partKind = P_NONE; headKind = H_GLOW }
            "bars" -> { trackKind = T_BARS; partKind = P_NONE; headKind = H_BEAM; shimmerOn = false }
            "pixel" -> { trackKind = T_PIXEL; partKind = P_PIXEL; headKind = H_PIXEL; shimmerOn = false }
            "fire" -> { trackKind = T_LINE; partKind = P_EMBER; headKind = H_FLAME }
            "bubbles" -> { trackKind = T_LINE; partKind = P_BUBBLE; headKind = H_RING }
            "petals" -> { trackKind = T_LINE; partKind = P_PETAL; headKind = H_GLOW }
            "hearts" -> { trackKind = T_LINE; partKind = P_HEART; headKind = H_HEART }
            "notes" -> { trackKind = T_LINE; partKind = P_NOTE; headKind = H_VINYL }
            "snow" -> { trackKind = T_LINE; partKind = P_SNOW; headKind = H_GLOW }
            "tape" -> { trackKind = T_TAPE; partKind = P_NONE; headKind = H_REEL; shimmerOn = false }
            "classic" -> { trackKind = T_CLASSIC; partKind = P_NONE; headKind = H_DOT; shimmerOn = false }
            "dots" -> { trackKind = T_DOTS; partKind = P_NONE; headKind = H_DOTS; shimmerOn = false }
            else -> { styleName = "sparkle"; trackKind = T_LINE; partKind = P_STAR; headKind = H_GLOW }
        }
        alive = 0
        rebuildColors()
        invalidate()
    }

    fun setColors(accent: Int, track: Int) {
        this.accent = accent
        trackColor = track
        rebuildColors()
        invalidate()
    }

    private fun rebuildColors() {
        val c = custom
        if (c != null && c.size >= 2) {
            c0 = c[0]; c1 = c[1]; c2 = if (c.size > 2) c[2] else ColorUtils.blendARGB(c[1], Color.WHITE, 0.4f)
        } else {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(accent, hsl)
            hsl[0] = (hsl[0] + 40f) % 360f
            hsl[1] = maxOf(hsl[1], 0.6f)
            c0 = ColorUtils.HSLToColor(hsl)
            c1 = accent
            c2 = ColorUtils.blendARGB(accent, Color.WHITE, 0.35f)
        }
        gradient = null
    }

    // ---------------------------------------------------------------- paints & shapes
    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var gradient: LinearGradient? = null
    private var gradientW = 0
    private val rect = RectF()
    private val wavePath = Path()
    private val starPath = Path()
    private val heartPath = Path()
    private val notePath = Path()
    private val flamePath = Path()

    init {
        starPath.moveTo(0f, -1f)
        starPath.quadTo(0.12f, -0.12f, 1f, 0f)
        starPath.quadTo(0.12f, 0.12f, 0f, 1f)
        starPath.quadTo(-0.12f, 0.12f, -1f, 0f)
        starPath.quadTo(-0.12f, -0.12f, 0f, -1f)
        starPath.close()

        heartPath.moveTo(0f, 0.9f)
        heartPath.cubicTo(-1.25f, 0.05f, -0.75f, -1.0f, 0f, -0.38f)
        heartPath.cubicTo(0.75f, -1.0f, 1.25f, 0.05f, 0f, 0.9f)
        heartPath.close()

        notePath.addOval(RectF(-0.72f, 0.28f, 0.02f, 0.86f), Path.Direction.CW)
        notePath.addRect(-0.12f, -0.95f, 0.02f, 0.56f, Path.Direction.CW)
        notePath.moveTo(-0.05f, -0.95f)
        notePath.cubicTo(0.45f, -0.7f, 0.65f, -0.4f, 0.42f, 0.02f)
        notePath.lineTo(0.34f, -0.02f)
        notePath.cubicTo(0.48f, -0.36f, 0.3f, -0.56f, -0.05f, -0.68f)
        notePath.close()

        flamePath.moveTo(0f, -1f)
        flamePath.cubicTo(0.5f, -0.4f, 0.72f, 0.1f, 0.55f, 0.45f)
        flamePath.cubicTo(0.38f, 0.88f, -0.38f, 0.88f, -0.55f, 0.45f)
        flamePath.cubicTo(-0.72f, 0.1f, -0.5f, -0.4f, 0f, -1f)
        flamePath.close()

        isClickable = true
    }

    // ---------------------------------------------------------------- geometry
    private val barY: Float get() = if (compact) height / 2f else 36 * d
    private val left: Float get() = if (compact) 0f else 16 * d
    private val right: Float get() = if (compact) width.toFloat() else width - 16 * d
    private fun xFor(p: Float) = left + (right - left) * (p / max).coerceIn(0f, 1f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (compact) (6 * d).toInt() else (80 * d).toInt()
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    // ---------------------------------------------------------------- animation
    private var dragging = false
    private var time = 0f
    private var spin = 0f
    private var lastFrame = 0L
    private val ticker = FrameTicker { now -> step(now) }

    private val n = 72
    private val px = FloatArray(n); private val py = FloatArray(n)
    private val vx = FloatArray(n); private val vy = FloatArray(n)
    private val life = FloatArray(n); private val maxLife = FloatArray(n)
    private val size = FloatArray(n); private val phase = FloatArray(n)
    private val alt = BooleanArray(n)
    private var alive = 0
    private var spawnAcc = 0f
    private val rnd = Random(7)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing) ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }

    private fun rate(): Float = when (partKind) {
        P_STAR -> 26f; P_EMBER -> 32f; P_BUBBLE -> 8f; P_PETAL -> 5f; P_HEART -> 4.5f
        P_NOTE -> 3.2f; P_SNOW -> 14f; P_PIXEL -> 14f; P_SPARK -> 34f; else -> 0f
    }

    private fun gravity(): Float = d * when (partKind) {
        P_STAR -> 70f; P_EMBER -> -40f; P_BUBBLE -> -6f; P_PETAL -> 4f; P_HEART -> -4f
        P_NOTE -> -4f; P_SNOW -> 0f; P_PIXEL -> 160f; P_SPARK -> 120f; else -> 0f
    }

    private fun step(now: Long) {
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = now
        time += dt
        if (playing) spin += dt * 220f
        if (!compact && partKind != P_NONE) {
            if (playing || dragging) {
                spawnAcc += dt * rate() * (if (dragging) 2.5f else 1f)
                while (spawnAcc >= 1f) { spawnAcc -= 1f; spawn() }
            }
            val g = gravity()
            var i = 0
            while (i < alive) {
                life[i] += dt
                if (life[i] >= maxLife[i]) {
                    val last = alive - 1
                    px[i] = px[last]; py[i] = py[last]; vx[i] = vx[last]; vy[i] = vy[last]
                    life[i] = life[last]; maxLife[i] = maxLife[last]; size[i] = size[last]
                    phase[i] = phase[last]; alt[i] = alt[last]
                    alive--
                    continue
                }
                vy[i] += g * dt
                vx[i] *= (1f - 0.8f * dt)
                px[i] += vx[i] * dt
                py[i] += vy[i] * dt
                i++
            }
        }
        invalidate()
        if (!playing && !dragging && alive == 0) { ticker.stop(); lastFrame = 0L }
    }

    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    private fun spawn() {
        if (alive >= n || width == 0) return
        val i = alive++
        val hx = xFor(progress)
        val y = barY
        val l = left
        life[i] = 0f
        phase[i] = rnd.nextFloat() * 6.28f
        alt[i] = rnd.nextBoolean()
        when (partKind) {
            P_STAR -> {
                val atHead = rnd.nextFloat() < 0.7f
                px[i] = if (atHead) hx + r(-3f, 3f) * d else l + rnd.nextFloat() * (hx - l).coerceAtLeast(1f)
                py[i] = y + r(-2f, 2f) * d
                vx[i] = r(-23f, 23f) * d; vy[i] = -r(10f, 44f) * d
                maxLife[i] = r(0.9f, 2.3f)
                size[i] = (if (atHead) r(1.6f, 5f) else r(1.6f, 3.8f)) * d
            }
            P_EMBER -> {
                px[i] = hx + r(-4f, 4f) * d; py[i] = y - 2 * d
                vx[i] = r(-12f, 12f) * d; vy[i] = -r(15f, 40f) * d
                maxLife[i] = r(0.5f, 1.1f); size[i] = r(1.4f, 3.2f) * d
            }
            P_BUBBLE -> {
                px[i] = if (rnd.nextFloat() < 0.4f) hx else l + rnd.nextFloat() * (hx - l).coerceAtLeast(1f)
                py[i] = y
                vx[i] = 0f; vy[i] = -r(8f, 20f) * d
                maxLife[i] = r(1.2f, 2.4f); size[i] = r(1.6f, 4.6f) * d
            }
            P_PETAL -> {
                px[i] = hx + r(-24f, 30f) * d; py[i] = y - rnd.nextFloat() * y * 0.9f
                vx[i] = -r(4f, 14f) * d; vy[i] = r(10f, 22f) * d
                maxLife[i] = r(1.6f, 2.6f); size[i] = r(2.6f, 4.6f) * d
            }
            P_HEART -> {
                px[i] = hx + r(-4f, 4f) * d; py[i] = y - 4 * d
                vx[i] = r(-10f, 10f) * d; vy[i] = -r(14f, 28f) * d
                maxLife[i] = r(1.1f, 1.9f); size[i] = r(3f, 5.6f) * d
            }
            P_NOTE -> {
                px[i] = hx + r(-4f, 4f) * d; py[i] = y - 6 * d
                vx[i] = r(-8f, 8f) * d; vy[i] = -r(12f, 24f) * d
                maxLife[i] = r(1.4f, 2.2f); size[i] = r(4f, 6.5f) * d
            }
            P_SNOW -> {
                px[i] = l + rnd.nextFloat() * (right - l); py[i] = -4 * d
                vx[i] = 0f; vy[i] = r(8f, 20f) * d
                maxLife[i] = (height + 8 * d) / vy[i]; size[i] = r(0.8f, 2.4f) * d
            }
            P_PIXEL -> {
                px[i] = hx + r(-3f, 3f) * d; py[i] = y
                vx[i] = r(-40f, 40f) * d; vy[i] = -r(30f, 60f) * d
                maxLife[i] = r(0.6f, 1.2f); size[i] = r(2f, 3.2f) * d
            }
            P_SPARK -> {
                px[i] = hx; py[i] = y
                vx[i] = r(-70f, 70f) * d; vy[i] = -r(10f, 50f) * d
                maxLife[i] = r(0.25f, 0.55f); size[i] = r(0.9f, 1.9f) * d
            }
        }
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(canvas: Canvas) {
        val y = barY
        val l = left; val rr = right
        val hx = xFor(progress)
        if (gradient == null || gradientW != width) {
            gradientW = width
            gradient = LinearGradient(l, 0f, rr, 0f, intArrayOf(c0, c1, c2), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }
        grad.shader = gradient
        gradStroke.shader = gradient

        if (compact) { drawCompact(canvas, y, l, rr, hx); return }

        when (trackKind) {
            T_WAVE -> drawWave(canvas, y, l, rr, hx)
            T_BARS -> drawBars(canvas, y, l, rr, hx)
            T_PIXEL -> drawPixel(canvas, y, l, rr, hx, 4 * d, 1.5f * d)
            T_CLASSIC -> drawClassic(canvas, y, l, rr, hx)
            T_TAPE -> drawTape(canvas, y, l, rr, hx)
            T_DOTS -> drawDots(canvas, y, l, rr, hx)
            else -> drawLine(canvas, y, l, rr, hx, 4 * d)
        }
        drawParticles(canvas)
        drawHead(canvas, hx, y)
    }

    private fun drawLine(canvas: Canvas, y: Float, l: Float, rr: Float, hx: Float, th: Float) {
        stroke.color = trackColor; stroke.strokeWidth = th; stroke.shader = null
        canvas.drawLine(l, y, rr, y, stroke)
        if (hx <= l) return
        val flicker = if (neon) (0.82f + 0.18f * sin(time * 31f) * sin(time * 7f)) else 1f
        if (neon) {
            gradStroke.strokeWidth = th * 7f; gradStroke.alpha = (22 * flicker).toInt()
            canvas.drawLine(l, y, hx, y, gradStroke)
        }
        gradStroke.strokeWidth = th * 4.5f; gradStroke.alpha = ((if (neon) 50 else 26) * flicker).toInt()
        canvas.drawLine(l, y, hx, y, gradStroke)
        gradStroke.strokeWidth = th * 2.4f; gradStroke.alpha = ((if (neon) 110 else 60) * flicker).toInt()
        canvas.drawLine(l, y, hx, y, gradStroke)
        gradStroke.strokeWidth = th; gradStroke.alpha = 255
        canvas.drawLine(l, y, hx, y, gradStroke)
        if (neon) {
            stroke.color = Color.WHITE; stroke.alpha = (200 * flicker).toInt(); stroke.strokeWidth = th * 0.35f
            canvas.drawLine(l, y, hx, y, stroke)
        }
        drawShimmer(canvas, y, l, hx, th)
    }

    private fun drawShimmer(canvas: Canvas, y: Float, l: Float, hx: Float, th: Float) {
        val span = hx - l
        if (!shimmerOn || span < 20 * d || !(playing || compact)) return
        val t = (time * 0.45f) % 1f
        val sx = l + span * t
        stroke.shader = null; stroke.strokeWidth = th; stroke.color = Color.WHITE
        stroke.alpha = (150 * sin(t * PI).toFloat()).toInt().coerceIn(0, 150)
        canvas.drawLine(maxOf(l, sx - 18 * d), y, minOf(hx, sx + 18 * d), y, stroke)
    }

    private fun drawWave(canvas: Canvas, y: Float, l: Float, rr: Float, hx: Float) {
        val th = 3.5f * d
        stroke.shader = null; stroke.color = trackColor; stroke.strokeWidth = th
        canvas.drawLine(maxOf(l, hx), y, rr, y, stroke)
        if (hx <= l) return
        val amp = (if (playing) 4.5f else 1.5f) * d
        val wl = 26 * d
        wavePath.reset()
        var x = l
        wavePath.moveTo(x, y)
        val stepX = 2.5f * d
        while (x < hx) {
            x = minOf(hx, x + stepX)
            val taper = minOf(1f, (x - l) / (18 * d), (hx - x) / (12 * d) + 0.15f).coerceIn(0f, 1f)
            wavePath.lineTo(x, y + amp * taper * sin(((x - l) / wl) * 2f * PI.toFloat() - time * 6f))
        }
        gradStroke.strokeWidth = th * 3.2f; gradStroke.alpha = 50
        canvas.drawPath(wavePath, gradStroke)
        gradStroke.strokeWidth = th; gradStroke.alpha = 255
        canvas.drawPath(wavePath, gradStroke)
    }

    private fun drawBars(canvas: Canvas, y: Float, l: Float, rr: Float, hx: Float) {
        val pitch = 5.5f * d
        val bw = 3.2f * d
        val count = ((rr - l) / pitch).toInt().coerceAtLeast(1)
        val energy = if (playing) 1f else 0.18f
        for (i in 0 until count) {
            val x = l + i * pitch + (pitch - bw) / 2
            val lvl = 0.5f + 0.25f * sin(time * (3.1f + (i % 7) * 0.83f) + i * 1.7f) +
                0.25f * sin(time * (5.3f + (i % 5) * 0.61f) + i * 0.9f)
            val h = (2.5f + 15f * lvl * energy) * d
            rect.set(x, y - h, x + bw, y + h)
            if (x + bw <= hx) {
                canvas.drawRoundRect(rect, bw / 2, bw / 2, grad)
            } else {
                fill.shader = null; fill.color = trackColor
                canvas.drawRoundRect(rect, bw / 2, bw / 2, fill)
            }
        }
    }

    /** Dot-matrix meter: columns of dots whose height dances to the beat. */
    private fun drawDots(canvas: Canvas, y: Float, l: Float, rr: Float, hx: Float) {
        val pitch = 5.5f * d
        val rad = 1.45f * d
        val rows = 7
        val count = ((rr - l) / pitch).toInt().coerceAtLeast(1)
        val energy = if (playing) 1f else 0.15f
        fill.shader = null
        for (i in 0 until count) {
            val x = l + i * pitch + pitch / 2
            val lvl = 0.5f + 0.25f * sin(time * (2.6f + (i % 6) * 0.7f) + i * 1.3f) + 0.25f * sin(time * (4.1f + (i % 4) * 0.5f) + i * 0.6f)
            val lit = (1 + (rows - 1) * lvl * energy).toInt().coerceIn(1, rows)
            val played = x <= hx
            for (j in 0 until rows) {
                val off = (j - (rows - 1) / 2f) * pitch
                val on = kotlin.math.abs(j - (rows - 1) / 2f) < lit / 2f + 0.01f
                if (played && on) {
                    canvas.drawCircle(x, y + off, rad, grad)
                } else {
                    fill.color = trackColor
                    fill.alpha = if (on) Color.alpha(trackColor).coerceAtLeast(90) else Color.alpha(trackColor) / 2
                    canvas.drawCircle(x, y + off, rad * 0.9f, fill)
                }
            }
        }
    }

    private fun drawPixel(canvas: Canvas, y: Float, l: Float, rr: Float, hx: Float, block: Float, gap: Float) {
        val pitch = block + gap
        val count = ((rr - l) / pitch).toInt().coerceAtLeast(1)
        val blink = ((time * 4f).toInt() % 2 == 0) || !playing
        fill.shader = null
        for (i in 0 until count) {
            val x = l + i * pitch
            rect.set(x, y - block / 2, x + block, y + block / 2)
            when {
                x + block <= hx -> canvas.drawRect(rect, grad)
                x < hx && blink -> { fill.color = c2; canvas.drawRect(rect, fill) }
                else -> { fill.color = trackColor; canvas.drawRect(rect, fill) }
            }
        }
    }

    private fun drawClassic(canvas: Canvas, y: Float, l: Float, rr: Float, hx: Float) {
        val h = 9 * d
        val rad = h / 2
        rect.set(l, y - h / 2, rr, y + h / 2)
        fill.shader = null; fill.color = trackColor
        canvas.drawRoundRect(rect, rad, rad, fill)
        if (hx > l) {
            rect.set(l, y - h / 2, maxOf(hx, l + h), y + h / 2)
            canvas.drawRoundRect(rect, rad, rad, grad)
            // glossy top half
            rect.set(l + 1.5f * d, y - h / 2 + 1.2f * d, maxOf(hx, l + h) - 1.5f * d, y - 0.3f * d)
            fill.color = Color.WHITE; fill.alpha = 85
            canvas.drawRoundRect(rect, rad, rad, fill)
        }
        ring.color = ColorUtils.setAlphaComponent(c1, 120); ring.strokeWidth = 1 * d; ring.shader = null
        rect.set(l, y - h / 2, rr, y + h / 2)
        canvas.drawRoundRect(rect, rad, rad, ring)
    }

    private fun drawTape(canvas: Canvas, y: Float, l: Float, rr: Float, hx: Float) {
        val th = 6.5f * d
        stroke.shader = null; stroke.color = trackColor; stroke.strokeWidth = th; stroke.strokeCap = Paint.Cap.BUTT
        canvas.drawLine(l, y, rr, y, stroke)
        if (hx > l) {
            gradStroke.strokeCap = Paint.Cap.BUTT
            gradStroke.strokeWidth = th; gradStroke.alpha = 255
            canvas.drawLine(l, y, hx, y, gradStroke)
            gradStroke.strokeCap = Paint.Cap.ROUND
            // moving tape texture
            val pitch = 9 * d
            var x = l + (spin * 0.12f * d) % pitch
            stroke.color = Color.BLACK; stroke.alpha = 55; stroke.strokeWidth = 1.2f * d
            while (x < hx) {
                canvas.drawLine(x, y - th / 2, x, y + th / 2, stroke)
                x += pitch
            }
        }
        stroke.strokeCap = Paint.Cap.ROUND
    }

    private fun drawCompact(canvas: Canvas, y: Float, l: Float, rr: Float, hx: Float) {
        val th = 3 * d
        when (trackKind) {
            T_PIXEL -> drawPixel(canvas, y, l, rr, hx, 3 * d, 1 * d)
            T_DOTS -> {
                var x = l + 2 * d
                fill.shader = null
                while (x < rr) {
                    if (x <= hx) canvas.drawCircle(x, y, 1.3f * d, grad)
                    else { fill.color = trackColor; canvas.drawCircle(x, y, 1.1f * d, fill) }
                    x += 4 * d
                }
            }
            T_BARS -> {
                stroke.shader = null; stroke.color = trackColor; stroke.strokeWidth = th
                canvas.drawLine(l, y, rr, y, stroke)
                if (hx > l) { gradStroke.strokeWidth = th; gradStroke.alpha = 255; canvas.drawLine(l, y, hx, y, gradStroke) }
            }
            else -> {
                stroke.shader = null; stroke.color = trackColor; stroke.strokeWidth = th
                canvas.drawLine(l, y, rr, y, stroke)
                if (hx > l) {
                    gradStroke.strokeWidth = th; gradStroke.alpha = 255
                    canvas.drawLine(l, y, hx, y, gradStroke)
                    drawShimmer(canvas, y, l, hx, th)
                }
            }
        }
        val pulse = if (playing) 0.5f + 0.5f * sin(time * 4.2f) else 0.3f
        fill.shader = null
        fill.color = c1; fill.alpha = (90 + 80 * pulse).toInt()
        canvas.drawCircle(hx, y, (3 + 2.5f * pulse) * d, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(hx, y, 1.8f * d, fill)
    }

    private fun drawParticles(canvas: Canvas) {
        fill.shader = null
        for (i in 0 until alive) {
            val f = life[i] / maxLife[i]
            val fade = if (f < 0.15f) f / 0.15f else 1f - (f - 0.15f) / 0.85f
            val s = size[i]
            val swayX = when (partKind) {
                P_BUBBLE -> 4 * d * sin(time * 3f + phase[i])
                P_PETAL -> 10 * d * sin(time * 2f + phase[i])
                P_NOTE -> 6 * d * sin(time * 2.5f + phase[i])
                P_SNOW -> 6 * d * sin(time * 1.5f + phase[i])
                P_HEART -> 4 * d * sin(time * 3f + phase[i])
                else -> 0f
            }
            val x = px[i] + swayX
            val y = py[i]
            when (partKind) {
                P_STAR -> {
                    val tw = 0.55f + 0.45f * sin(time * 14f + phase[i])
                    fill.color = if (alt[i]) Color.WHITE else ColorUtils.blendARGB(c1, Color.WHITE, 0.25f)
                    fill.alpha = (255 * fade * tw).toInt().coerceIn(0, 255)
                    val sc = s * (0.7f + 0.3f * tw)
                    canvas.save(); canvas.translate(x, y); canvas.rotate(time * 90f + phase[i] * 57f); canvas.scale(sc, sc)
                    canvas.drawPath(starPath, fill); canvas.restore()
                }
                P_EMBER -> {
                    val c = if (f < 0.5f) ColorUtils.blendARGB(c2, c1, f * 2f) else ColorUtils.blendARGB(c1, c0, (f - 0.5f) * 2f)
                    val rad = s * (1f - f * 0.5f)
                    fill.color = c; fill.alpha = (70 * fade).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, rad * 2.3f, fill)
                    fill.alpha = (255 * fade).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, rad, fill)
                }
                P_BUBBLE -> {
                    ring.shader = null; ring.strokeWidth = 1.1f * d
                    ring.color = ColorUtils.blendARGB(c2, Color.WHITE, 0.5f); ring.alpha = (210 * fade).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, s, ring)
                    fill.color = Color.WHITE; fill.alpha = (180 * fade).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x - s * 0.35f, y - s * 0.35f, s * 0.22f, fill)
                }
                P_PETAL -> {
                    fill.color = if (alt[i]) c1 else c2; fill.alpha = (235 * fade).toInt().coerceIn(0, 255)
                    canvas.save(); canvas.translate(x, y); canvas.rotate(time * 120f + phase[i] * 57f)
                    rect.set(-s, -s * 0.5f, s, s * 0.5f)
                    canvas.drawOval(rect, fill); canvas.restore()
                }
                P_HEART -> {
                    fill.color = if (alt[i]) c1 else c2; fill.alpha = (240 * fade).toInt().coerceIn(0, 255)
                    val sc = s * (0.8f + 0.4f * f)
                    canvas.save(); canvas.translate(x, y); canvas.rotate(12f * sin(time * 3f + phase[i])); canvas.scale(sc, sc)
                    canvas.drawPath(heartPath, fill); canvas.restore()
                }
                P_NOTE -> {
                    fill.color = if (alt[i]) Color.WHITE else c2; fill.alpha = (240 * fade).toInt().coerceIn(0, 255)
                    canvas.save(); canvas.translate(x, y); canvas.rotate(15f * sin(time * 3f + phase[i])); canvas.scale(s, s)
                    canvas.drawPath(notePath, fill); canvas.restore()
                }
                P_SNOW -> {
                    fill.color = Color.WHITE; fill.alpha = (220 * fade).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, s, fill)
                }
                P_PIXEL -> {
                    val g = 2 * d
                    val sx = floor(x / g) * g; val sy = floor(y / g) * g
                    fill.color = if (alt[i]) c1 else c2; fill.alpha = (255 * fade).toInt().coerceIn(0, 255)
                    canvas.drawRect(sx, sy, sx + s, sy + s, fill)
                }
                P_SPARK -> {
                    fill.color = if (alt[i]) Color.WHITE else c2; fill.alpha = (255 * fade).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, s, fill)
                }
            }
        }
    }

    private fun drawHead(canvas: Canvas, hx: Float, y: Float) {
        val big = if (dragging) 1.3f else 1f
        val pulse = if (playing) 0.5f + 0.5f * sin(time * 4.2f) else 0.3f
        fill.shader = null
        when (headKind) {
            H_GLOW -> {
                fill.color = c1; fill.alpha = (45 + 55 * pulse).toInt()
                canvas.drawCircle(hx, y, (13 + 5 * pulse) * d * big, fill)
                fill.alpha = 110
                canvas.drawCircle(hx, y, 9.5f * d * big, fill)
                fill.color = Color.WHITE
                canvas.drawCircle(hx, y, 6.5f * d * big, fill)
            }
            H_RING -> {
                fill.color = c1; fill.alpha = (40 + 60 * pulse).toInt()
                canvas.drawCircle(hx, y, (14 + 4 * pulse) * d * big, fill)
                ring.shader = null; ring.color = c2; ring.strokeWidth = 2.6f * d
                canvas.drawCircle(hx, y, 8.5f * d * big, ring)
                fill.color = Color.WHITE
                canvas.drawCircle(hx, y, 3.5f * d * big, fill)
            }
            H_VINYL -> {
                val rad = 12 * d * big
                fill.color = c1; fill.alpha = 70
                canvas.drawCircle(hx, y, rad + 4 * d, fill)
                fill.color = 0xFF141414.toInt()
                canvas.drawCircle(hx, y, rad, fill)
                ring.shader = null; ring.color = 0x40FFFFFF; ring.strokeWidth = 0.7f * d
                canvas.drawCircle(hx, y, rad * 0.82f, ring)
                canvas.drawCircle(hx, y, rad * 0.64f, ring)
                fill.color = c1
                canvas.drawCircle(hx, y, rad * 0.36f, fill)
                fill.color = 0xFF141414.toInt()
                canvas.drawCircle(hx, y, 1.3f * d, fill)
                canvas.save(); canvas.translate(hx, y); canvas.rotate(spin)
                stroke.shader = null; stroke.color = Color.WHITE; stroke.alpha = 110; stroke.strokeWidth = 1.3f * d
                canvas.drawLine(0f, -rad * 0.9f, 0f, -rad * 0.46f, stroke)
                canvas.restore()
            }
            H_REEL -> {
                val rad = 12 * d * big
                fill.color = 0xE6202020.toInt()
                canvas.drawCircle(hx, y, rad, fill)
                ring.shader = null; ring.color = c2; ring.strokeWidth = 1.6f * d
                canvas.drawCircle(hx, y, rad, ring)
                canvas.save(); canvas.translate(hx, y); canvas.rotate(spin)
                stroke.shader = null; stroke.color = Color.WHITE; stroke.alpha = 200; stroke.strokeWidth = 2.2f * d
                for (k in 0 until 3) {
                    canvas.drawLine(0f, -rad * 0.32f, 0f, -rad * 0.78f, stroke)
                    canvas.rotate(120f)
                }
                canvas.restore()
                fill.color = c1
                canvas.drawCircle(hx, y, rad * 0.3f, fill)
            }
            H_PIXEL -> {
                val s = 6 * d * big
                fill.color = c1; fill.alpha = (60 + 60 * pulse).toInt()
                canvas.drawRect(hx - s - 3 * d, y - s - 3 * d, hx + s + 3 * d, y + s + 3 * d, fill)
                fill.color = Color.WHITE
                canvas.drawRect(hx - s, y - s, hx + s, y + s, fill)
                fill.color = if (((time * 4f).toInt() % 2 == 0) && playing) c2 else c1
                canvas.drawRect(hx - s * 0.5f, y - s * 0.5f, hx + s * 0.5f, y + s * 0.5f, fill)
            }
            H_HEART -> {
                val beat = 1f + (if (playing) 0.2f * maxOf(0f, sin(time * 7.5f)) else 0f)
                fill.color = c1; fill.alpha = 70
                canvas.drawCircle(hx, y, 14 * d * beat * big, fill)
                fill.color = c1; fill.alpha = 255
                canvas.save(); canvas.translate(hx, y); canvas.scale(10 * d * beat * big, 10 * d * beat * big)
                canvas.drawPath(heartPath, fill); canvas.restore()
                fill.color = Color.WHITE; fill.alpha = 150
                canvas.drawCircle(hx - 3.5f * d * big, y - 3 * d * big, 1.6f * d * big, fill)
            }
            H_FLAME -> {
                val fl = if (playing) 1f + 0.12f * sin(time * 23f) + 0.06f * sin(time * 37f) else 1f
                fill.color = c1; fill.alpha = (55 + 30 * pulse).toInt()
                canvas.drawCircle(hx, y - 4 * d, 15 * d * big, fill)
                // little diya bowl
                rect.set(hx - 8 * d, y - 1 * d, hx + 8 * d, y + 7 * d)
                fill.color = c0; fill.alpha = 255
                canvas.drawArc(rect, 0f, 180f, true, fill)
                canvas.save(); canvas.translate(hx, y - 8 * d); canvas.scale(7.5f * d * big, 11 * d * fl * big)
                fill.color = c1; canvas.drawPath(flamePath, fill)
                canvas.scale(0.55f, 0.6f); canvas.translate(0f, 0.3f)
                fill.color = c2; canvas.drawPath(flamePath, fill)
                canvas.restore()
            }
            H_BEAM -> {
                fill.color = c1; fill.alpha = (50 + 50 * pulse).toInt()
                rect.set(hx - 5 * d, y - 22 * d, hx + 5 * d, y + 22 * d)
                canvas.drawRoundRect(rect, 5 * d, 5 * d, fill)
                fill.color = Color.WHITE; fill.alpha = 255
                rect.set(hx - 1.4f * d, y - 20 * d, hx + 1.4f * d, y + 20 * d)
                canvas.drawRoundRect(rect, 1.4f * d, 1.4f * d, fill)
            }
            H_DOTS -> {
                fill.color = c1; fill.alpha = (40 + 50 * pulse).toInt()
                rect.set(hx - 5 * d, y - 21 * d, hx + 5 * d, y + 21 * d)
                canvas.drawRoundRect(rect, 5 * d, 5 * d, fill)
                fill.color = Color.WHITE; fill.alpha = 255
                for (j in -3..3) canvas.drawCircle(hx, y + j * 5.5f * d, 1.9f * d * big, fill)
            }
            H_DOT -> {
                fill.color = 0x33000000
                canvas.drawCircle(hx, y + 1 * d, 8.5f * d * big, fill)
                fill.color = Color.WHITE
                canvas.drawCircle(hx, y, 8 * d * big, fill)
                ring.shader = null; ring.color = c1; ring.strokeWidth = 1.6f * d
                canvas.drawCircle(hx, y, 8 * d * big, ring)
            }
        }
    }

    // ---------------------------------------------------------------- touch
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (compact || !isEnabled) return false
        val p = ((e.x - left) / (right - left)).coerceIn(0f, 1f) * max
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (abs(e.y - barY) > 32 * d) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                ticker.start()
                onSeekStart?.invoke()
                setP(p)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) setP(p)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                setP(p)
                dragging = false
                onSeekEnd?.invoke(seekValue)
                performClick()
            }
        }
        return true
    }

    private var seekValue = 0f
    private fun setP(p: Float) {
        seekValue = p
        dragging = false; progress = p; dragging = true
        onSeeking?.invoke(p)
        if (partKind != P_NONE && rnd.nextFloat() < 0.5f) spawn()
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        val STYLES = listOf("sparkle", "neon", "wave", "bars", "pixel", "fire", "bubbles", "petals", "hearts", "notes", "snow", "tape", "classic", "dots")
        val LABELS = mapOf(
            "sparkle" to "Sparkle stars", "neon" to "Neon tube", "wave" to "Sound wave", "bars" to "Spectrum bars",
            "pixel" to "8-bit pixels", "fire" to "Fire & diya", "bubbles" to "Bubbles", "petals" to "Falling petals",
            "hearts" to "Floating hearts", "notes" to "Music notes & vinyl", "snow" to "Snowfall", "tape" to "Cassette tape",
            "classic" to "Classic glossy", "dots" to "Dot matrix",
        )

        private const val T_LINE = 0; private const val T_WAVE = 1; private const val T_BARS = 2
        private const val T_PIXEL = 3; private const val T_CLASSIC = 4; private const val T_TAPE = 5
        private const val T_DOTS = 6

        private const val P_NONE = 0; private const val P_STAR = 1; private const val P_EMBER = 2; private const val P_BUBBLE = 3
        private const val P_PETAL = 4; private const val P_HEART = 5; private const val P_NOTE = 6; private const val P_SNOW = 7
        private const val P_PIXEL = 8; private const val P_SPARK = 9

        private const val H_GLOW = 0; private const val H_RING = 1; private const val H_VINYL = 2; private const val H_REEL = 3
        private const val H_PIXEL = 4; private const val H_HEART = 5; private const val H_FLAME = 6; private const val H_BEAM = 7
        private const val H_DOT = 8; private const val H_DOTS = 9
    }
}
