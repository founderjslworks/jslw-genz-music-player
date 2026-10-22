package com.jslw.genz.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.jslw.genz.util.FrameTicker
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Glowing, animated progress / seek bar.
 *
 * Full mode (Now Playing): a luminous gradient track, a pulsing thumb, and star sparkles that
 * twinkle along the played part and drift down and fade from the play head.
 * Compact mode (mini player): a thin bar with a moving shimmer and a glowing head.
 *
 * Runs on the display's vsync (60–144 Hz) only while music plays or sparkles are still falling,
 * with a fixed particle pool (no allocations per frame).
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

    private var accent = 0xFFFF3D9A.toInt()
    private var accent2 = 0xFF7B2FF7.toInt()
    private var trackColor = 0x40FFFFFF

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmer = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val starPath = Path()
    private var gradient: LinearGradient? = null
    private var gradientW = 0

    private var dragging = false
    private var time = 0f
    private var lastFrame = 0L

    // ---- particle pool ----
    private val N = 64
    private val px = FloatArray(N); private val py = FloatArray(N)
    private val vx = FloatArray(N); private val vy = FloatArray(N)
    private val life = FloatArray(N); private val maxLife = FloatArray(N)
    private val size = FloatArray(N); private val phase = FloatArray(N)
    private val white = BooleanArray(N)
    private var alive = 0
    private var spawnAcc = 0f
    private val rnd = Random(7)

    private val ticker = FrameTicker { now -> step(now) }

    init {
        // 4-point twinkle star, unit size, drawn scaled per particle
        starPath.moveTo(0f, -1f)
        starPath.quadTo(0.12f, -0.12f, 1f, 0f)
        starPath.quadTo(0.12f, 0.12f, 0f, 1f)
        starPath.quadTo(-0.12f, 0.12f, -1f, 0f)
        starPath.quadTo(-0.12f, -0.12f, 0f, -1f)
        starPath.close()
        isClickable = true
    }

    fun setColors(accent: Int, track: Int) {
        this.accent = accent
        // a second, shifted hue so the bar reads as a vivid gradient
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(accent, hsl)
        hsl[0] = (hsl[0] + 40f) % 360f
        hsl[1] = max(hsl[1], 0.6f)
        accent2 = ColorUtils.HSLToColor(hsl)
        trackColor = track
        gradient = null
        invalidate()
    }

    private val barY: Float get() = if (compact) height / 2f else 20 * d
    private val left: Float get() = if (compact) 0f else 14 * d
    private val right: Float get() = if (compact) width.toFloat() else width - 14 * d
    private fun xFor(p: Float) = left + (right - left) * (p / max).coerceIn(0f, 1f)

    // ---------------------------------------------------------------- animation
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing) ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }

    private fun step(now: Long) {
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = now
        time += dt
        if (!compact) {
            if (playing || dragging) {
                spawnAcc += dt * (if (dragging) 70f else 26f)
                while (spawnAcc >= 1f) { spawnAcc -= 1f; spawn(head = rnd.nextFloat() < 0.7f) }
            }
            var i = 0
            while (i < alive) {
                life[i] += dt
                if (life[i] >= maxLife[i]) {
                    // swap-remove
                    val last = alive - 1
                    px[i] = px[last]; py[i] = py[last]; vx[i] = vx[last]; vy[i] = vy[last]
                    life[i] = life[last]; maxLife[i] = maxLife[last]; size[i] = size[last]; phase[i] = phase[last]
                    white[i] = white[last]
                    alive--
                    continue
                }
                vy[i] += 70 * d * dt          // gentle gravity: sparkles rise a little, then fall
                vx[i] *= (1f - 0.8f * dt)     // air drag
                px[i] += vx[i] * dt
                py[i] += vy[i] * dt
                i++
            }
        }
        invalidate()
        if (!playing && !dragging && alive == 0) { ticker.stop(); lastFrame = 0L }
    }

    private fun spawn(head: Boolean) {
        if (alive >= N || width == 0) return
        val i = alive++
        val hx = xFor(progress)
        px[i] = if (head) hx + (rnd.nextFloat() - 0.5f) * 6 * d else left + rnd.nextFloat() * (hx - left).coerceAtLeast(1f)
        py[i] = barY + (rnd.nextFloat() - 0.5f) * 4 * d
        vx[i] = (rnd.nextFloat() - 0.5f) * 46 * d
        vy[i] = -(10 + rnd.nextFloat() * 34) * d
        life[i] = 0f
        maxLife[i] = 0.9f + rnd.nextFloat() * 1.4f
        size[i] = (1.6f + rnd.nextFloat() * (if (head) 3.4f else 2.2f)) * d
        phase[i] = rnd.nextFloat() * 6.28f
        white[i] = rnd.nextFloat() < 0.55f
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(canvas: Canvas) {
        val y = barY
        val l = left; val r = right
        val hx = xFor(progress)
        val th = if (compact) 3 * d else 4 * d

        if (gradient == null || gradientW != width) {
            gradientW = width
            gradient = LinearGradient(l, 0f, r, 0f, intArrayOf(accent2, accent, ColorUtils.blendARGB(accent, Color.WHITE, 0.35f)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }

        track.color = trackColor; track.strokeWidth = th
        canvas.drawLine(l, y, r, y, track)

        // soft glow under the played part (layered strokes – no blur filters, so it stays GPU-fast)
        if (hx > l) {
            glow.shader = gradient
            glow.strokeWidth = th * 4.5f; glow.alpha = 26
            canvas.drawLine(l, y, hx, y, glow)
            glow.strokeWidth = th * 2.4f; glow.alpha = 60
            canvas.drawLine(l, y, hx, y, glow)
            active.shader = gradient; active.strokeWidth = th; active.alpha = 255
            canvas.drawLine(l, y, hx, y, active)

            // travelling shimmer: a bright streak that sweeps along the played part
            val span = hx - l
            if (span > 20 * d && (playing || compact)) {
                val t = (time * 0.45f) % 1f
                val sx = l + span * t
                shimmer.strokeWidth = th
                shimmer.color = Color.WHITE
                shimmer.alpha = (150 * sin(t * PI).toFloat()).toInt().coerceIn(0, 150)
                canvas.drawLine(max(l, sx - 18 * d), y, min(hx, sx + 18 * d), y, shimmer)
            }
        }

        // particles (behind the thumb)
        for (i in 0 until alive) {
            val f = life[i] / maxLife[i]
            val fade = if (f < 0.15f) f / 0.15f else 1f - (f - 0.15f) / 0.85f
            val twinkle = 0.55f + 0.45f * sin(time * 14f + phase[i])
            star.color = if (white[i]) Color.WHITE else ColorUtils.blendARGB(accent, Color.WHITE, 0.25f)
            star.alpha = (255 * fade * twinkle).toInt().coerceIn(0, 255)
            val s = size[i] * (0.7f + 0.3f * twinkle)
            canvas.save()
            canvas.translate(px[i], py[i])
            canvas.rotate(time * 90f + phase[i] * 57f)
            canvas.scale(s, s)
            canvas.drawPath(starPath, star)
            canvas.restore()
        }

        // glowing, pulsing play head
        val pulse = if (playing) 0.5f + 0.5f * sin(time * 4.2f) else 0.3f
        if (compact) {
            halo.color = accent; halo.alpha = (90 + 80 * pulse).toInt()
            canvas.drawCircle(hx, y, (3 + 2.5f * pulse) * d, halo)
            thumb.color = Color.WHITE
            canvas.drawCircle(hx, y, 1.8f * d, thumb)
        } else {
            val big = if (dragging) 1.35f else 1f
            halo.color = accent; halo.alpha = (45 + 55 * pulse).toInt()
            canvas.drawCircle(hx, y, (13 + 5 * pulse) * d * big, halo)
            halo.alpha = 110
            canvas.drawCircle(hx, y, 9.5f * d * big, halo)
            thumb.color = Color.WHITE
            canvas.drawCircle(hx, y, 6.5f * d * big, thumb)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (compact) (6 * d).toInt() else (64 * d).toInt()
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    // ---------------------------------------------------------------- touch
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (compact || !isEnabled) return false
        val p = ((e.x - left) / (right - left)).coerceIn(0f, 1f) * max
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.y > barY + 30 * d) return false
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
                onSeekEnd?.invoke(progressInternal)
                performClick()
            }
        }
        return true
    }

    private var progressInternal = 0f
    private fun setP(p: Float) {
        progressInternal = p
        // write the backing field directly while dragging
        dragging = false; progress = p; dragging = true
        onSeeking?.invoke(p)
        // a little burst of sparkles while scrubbing
        if (rnd.nextFloat() < 0.5f) spawn(head = true)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
