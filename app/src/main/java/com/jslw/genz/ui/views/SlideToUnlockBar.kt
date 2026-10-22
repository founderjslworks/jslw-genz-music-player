package com.jslw.genz.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import com.jslw.genz.util.FrameTicker

/**
 * The classic "slide to unlock" slider: a dark, inset glass track, a glossy silver knob with an arrow,
 * and the label with a soft light sweeping across it.
 *
 * Two modes:
 *  - scrubber ([steps] > 1): the knob sits on a position (e.g. album 12 of 64) and you can drag to any step.
 *  - unlock ([steps] == 1): drag the knob all the way to the right to trigger [onUnlock]; otherwise it springs back.
 */
class SlideToUnlockBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var text = "slide to unlock"
        set(v) { field = v; invalidate() }
    var steps = 1
        set(v) { field = v.coerceAtLeast(1); invalidate() }
    /** Current step (scrubber mode). */
    private var pos = 0
    var position: Int
        get() = pos
        set(v) { if (!dragging) { pos = v.coerceIn(0, steps - 1); knobX = knobFor(pos); invalidate() } }
    var onScrub: ((Int) -> Unit)? = null
    var onUnlock: (() -> Unit)? = null
    var typeface: Typeface? = null
        set(v) { field = v; textPaint.typeface = v; invalidate() }
    var tint = 0xFF7EC8FF.toInt()

    private val d = resources.displayMetrics.density
    private val track = RectF()
    private val knob = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val arrow = Path()
    private val shimmerMatrix = Matrix()
    private var shimmer: LinearGradient? = null
    private var trackShader: LinearGradient? = null
    private var knobShader: LinearGradient? = null
    private var glossShader: LinearGradient? = null
    private var shaderH = 0f
    private var knobX = -1f
    private var dragging = false
    private var grabOffset = 0f
    private var time = 0f
    private var last = 0L
    private var spring: android.animation.ValueAnimator? = null
    private val ticker = FrameTicker { now ->
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now; time += dt; invalidate()
    }

    private val pad get() = 4 * d
    private val knobW get() = (height - 2 * pad) * 1.45f
    private fun minX() = track.left + pad
    private fun maxX() = track.right - pad - knobW
    private fun knobFor(step: Int) = if (steps <= 1) minX() else minX() + (maxX() - minX()) * step / (steps - 1f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), resolveSize((58 * d).toInt(), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        track.set(1 * d, 1 * d, w - 1 * d, h - 1 * d)
        knobX = knobFor(position)
        shaderH = 0f
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.start() }
    override fun onDetachedFromWindow() { ticker.stop(); last = 0L; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) ticker.start() else { ticker.stop(); last = 0L }
    }

    private fun buildShaders() {
        val h = height.toFloat()
        shaderH = h
        trackShader = LinearGradient(0f, track.top, 0f, track.bottom, intArrayOf(0xE6000000.toInt(), 0xCC1C1C1C.toInt(), 0xCC2E2E2E.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        knobShader = LinearGradient(0f, pad, 0f, h - pad, intArrayOf(0xFFFFFFFF.toInt(), 0xFFE2E2E2.toInt(), 0xFFB9B9B9.toInt(), 0xFFD4D4D4.toInt()),
            floatArrayOf(0f, 0.48f, 0.52f, 1f), Shader.TileMode.CLAMP)
        glossShader = LinearGradient(0f, pad, 0f, h / 2, 0x99FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        // a narrow bright band in grey text – sweeping it across gives the famous shimmer
        val band = 70 * d
        shimmer = LinearGradient(0f, 0f, band, 0f, intArrayOf(0xFF6E6E6E.toInt(), 0xFF6E6E6E.toInt(), Color.WHITE, 0xFF6E6E6E.toInt(), 0xFF6E6E6E.toInt()),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        if (shaderH != height.toFloat()) buildShaders()
        if (knobX < 0f) knobX = knobFor(position)
        val r = (height - 2 * d) / 2f * 0.55f
        // inset track
        p.shader = trackShader
        c.drawRoundRect(track, r, r, p)
        p.shader = null
        stroke.strokeWidth = 1 * d
        stroke.color = 0x66000000; c.drawRoundRect(track, r, r, stroke)
        stroke.color = 0x33FFFFFF
        c.drawLine(track.left + r, track.bottom - 0.5f * d, track.right - r, track.bottom - 0.5f * d, stroke)

        // shimmering label in the free space right of the knob
        val textLeft = knobX + knobW + 6 * d
        val textRight = track.right - 10 * d
        val fade = if (steps <= 1) (1f - (knobX - minX()) / (maxX() - minX()).coerceAtLeast(1f) * 1.6f).coerceIn(0f, 1f) else 1f
        if (textRight - textLeft > 40 * d && fade > 0f) {
            textPaint.textSize = (height * 0.34f).coerceAtMost(20 * d)
            val cx = (textLeft + textRight) / 2
            val span = textRight - textLeft + 140 * d
            val sweep = (time * 0.55f % 1.3f) / 1.3f
            shimmerMatrix.setTranslate(textLeft - 70 * d + span * sweep, 0f)
            shimmer?.setLocalMatrix(shimmerMatrix)
            textPaint.shader = shimmer
            textPaint.alpha = (255 * fade).toInt()
            c.save()
            c.clipRect(textLeft, track.top, textRight, track.bottom)
            c.drawText(text, cx, height / 2f - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
            c.restore()
            textPaint.shader = null
        }

        // glossy knob
        knob.set(knobX, pad + 1 * d, knobX + knobW, height - pad - 1 * d)
        val kr = (knob.height() / 2f) * 0.42f
        p.color = 0x66000000
        c.drawRoundRect(knob.left, knob.top + 1.5f * d, knob.right, knob.bottom + 1.5f * d, kr, kr, p)
        p.shader = knobShader; c.drawRoundRect(knob, kr, kr, p)
        p.shader = glossShader
        c.drawRoundRect(knob.left + 1 * d, knob.top + 1 * d, knob.right - 1 * d, knob.centerY(), kr, kr, p)
        p.shader = null
        stroke.color = 0x99808080.toInt(); stroke.strokeWidth = 1 * d
        c.drawRoundRect(knob, kr, kr, stroke)
        // arrow (tinted when dragging)
        val ax = knob.centerX(); val ay = knob.centerY(); val s = knob.height() * 0.2f
        arrow.reset()
        arrow.moveTo(ax - s * 1.1f, ay - s * 0.38f); arrow.lineTo(ax + s * 0.1f, ay - s * 0.38f); arrow.lineTo(ax + s * 0.1f, ay - s)
        arrow.lineTo(ax + s * 1.2f, ay); arrow.lineTo(ax + s * 0.1f, ay + s); arrow.lineTo(ax + s * 0.1f, ay + s * 0.38f)
        arrow.lineTo(ax - s * 1.1f, ay + s * 0.38f); arrow.close()
        p.color = if (dragging) tint else 0xFF8A8A8A.toInt()
        c.drawPath(arrow, p)
        if (steps > 1) {
            textPaint.textSize = 10 * d; textPaint.color = 0xFF5A5A5A.toInt(); textPaint.alpha = 255
            c.drawText("${position + 1}", knob.centerX(), knob.bottom - 4 * d, textPaint)
            textPaint.color = Color.WHITE
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                spring?.cancel()
                val onKnob = e.x >= knobX - 8 * d && e.x <= knobX + knobW + 8 * d
                if (!onKnob && steps <= 1) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                grabOffset = if (onKnob) e.x - knobX else knobW / 2
                move(e.x)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) move(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                if (steps <= 1) {
                    if (knobX >= maxX() - 4 * d) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onUnlock?.invoke()
                    } else springTo(minX())
                } else springTo(knobFor(position))
                performClick()
            }
        }
        return true
    }

    private fun move(x: Float) {
        knobX = (x - grabOffset).coerceIn(minX(), maxX())
        if (steps > 1) {
            val step = Math.round((knobX - minX()) / (maxX() - minX()).coerceAtLeast(1f) * (steps - 1))
            if (step != pos) {
                pos = step.coerceIn(0, steps - 1)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onScrub?.invoke(pos)
            }
        }
        invalidate()
    }

    private fun springTo(target: Float) {
        spring = android.animation.ValueAnimator.ofFloat(knobX, target).apply {
            duration = 380
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { knobX = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /** Resets the unlock knob to the start (e.g. when the screen is shown again). */
    fun reset() { spring?.cancel(); knobX = minX(); invalidate() }
}
