package com.jslw.genz.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Square container (album art) that fits inside whatever space it gets, portrait or landscape. */
class SquareFrameLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = View.MeasureSpec.getSize(heightMeasureSpec)
        val s = if (h == 0) w else min(w, h)
        val spec = View.MeasureSpec.makeMeasureSpec(s, View.MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }
}

/**
 * Swipe-down-to-dismiss container for Now Playing. The first child follows the finger; on release it
 * either springs back or slides away and calls [onDismiss].
 */
class DragDismissLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    var onDismiss: (() -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null
    /** Views that keep their own touch handling (seek bar, scrolled lyrics). */
    val excluded = ArrayList<View>()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null
    private val tmp = Rect()

    private val content: View? get() = if (childCount > 0) getChildAt(0) else null

    private fun isExcluded(ev: MotionEvent): Boolean = excluded.any { v ->
        if (v.visibility != View.VISIBLE || !v.isShown) return@any false
        v.getGlobalVisibleRect(tmp)
        tmp.contains(ev.rawX.toInt(), ev.rawY.toInt()) && (v !is androidx.recyclerview.widget.RecyclerView || v.canScrollVertically(-1))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; dragging = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
                if (isExcluded(ev)) downY = Float.NaN
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (downY.isNaN()) return false
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX
                if (dy > slop && dy > abs(dx) * 1.3f) {
                    dragging = true
                    downY = ev.rawY
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val c = content ?: return false
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (!dragging) {
                // touch started on an empty area (no child took it)
                if (!downY.isNaN()) {
                    val dy = ev.rawY - downY
                    if (dy > slop && dy > abs(ev.rawX - downX) * 1.3f) { dragging = true; downY = ev.rawY }
                }
            } else {
                val dy = max(0f, ev.rawY - downY)
                c.translationY = dy
                onDrag?.invoke(dy / height.coerceAtLeast(1))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                vt?.computeCurrentVelocity(1000)
                val v = vt?.yVelocity ?: 0f
                if (c.translationY > height * 0.22f || v > 1800) dismiss() else springBack()
            }
        }
        return true
    }

    fun dismiss() {
        val c = content ?: return
        c.animate().translationY(height.toFloat()).setDuration(220).setInterpolator(DecelerateInterpolator())
            .setUpdateListener { onDrag?.invoke(c.translationY / height.coerceAtLeast(1)) }
            .withEndAction { onDismiss?.invoke() }.start()
    }

    private fun springBack() {
        val c = content ?: return
        c.animate().translationY(0f).setDuration(320).setInterpolator(OvershootInterpolator(0.8f))
            .setUpdateListener { onDrag?.invoke(c.translationY / height.coerceAtLeast(1)) }.start()
    }
}

/** A vertical EQ fader with value label and frequency label. Values in millibels. */
class EqBandView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var min = -1200
    var max = 1200
    var value = 0
        set(v) { field = v.coerceIn(min, max); invalidate() }
    var label = ""
    var onChange: ((Int) -> Unit)? = null
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val active = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun yFor(v: Int): Float {
        val top = 22 * d; val bottom = height - 22 * d
        return bottom - (v - min).toFloat() / (max - min) * (bottom - top)
    }

    override fun onDraw(canvas: Canvas) {
        val s = SkinManager.current
        track.color = s.divider; active.color = s.accent; thumb.color = s.accent
        text.color = s.textSecondary; text.textSize = 10 * d; text.typeface = s.typeface
        val cx = width / 2f
        val top = 22 * d; val bottom = height - 22 * d
        track.strokeWidth = 4 * d; track.strokeCap = Paint.Cap.ROUND
        active.strokeWidth = 4 * d; active.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx, top, cx, bottom, track)
        val zero = yFor(0); val y = yFor(value)
        canvas.drawLine(cx, zero, cx, y, active)
        canvas.drawCircle(cx, y, 8 * d, thumb)
        val db = value / 100f
        canvas.drawText((if (db > 0) "+" else "") + String.format(java.util.Locale.US, "%.0f", db), cx, 12 * d, text)
        canvas.drawText(label, cx, height - 6 * d, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val top = 22 * d; val bottom = height - 22 * d
                val f = ((bottom - e.y) / (bottom - top)).coerceIn(0f, 1f)
                var v = (min + f * (max - min)).toInt()
                v = (v / 50) * 50 // 0.5 dB steps
                if (abs(v) < 60) v = 0 // gentle snap to 0
                if (v != value) { value = v; onChange?.invoke(v) }
            }
        }
        return true
    }
}

/** Smooth response curve drawn behind the faders. */
class EqCurveView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var values = IntArray(0)
    private var min = -1200
    private var max = 1200
    private var shown = FloatArray(0)
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zero = Paint()
    private var anim: ValueAnimator? = null
    private val p = Path()
    private val f = Path()

    fun set(v: IntArray, mn: Int, mx: Int, animate: Boolean) {
        min = mn; max = mx
        val from = if (shown.size == v.size) shown.copyOf() else FloatArray(v.size)
        values = v.copyOf()
        anim?.cancel()
        if (!animate) { shown = FloatArray(v.size) { v[it].toFloat() }; invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                shown = FloatArray(values.size) { i -> from[i] + (values[i] - from[i]) * t }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (shown.isEmpty()) return
        val s = SkinManager.current
        line.color = s.accent; line.strokeWidth = 2.5f * d
        fill.color = SkinPainter.withAlpha(s.accent, 0x26)
        zero.color = s.divider
        val top = 22 * d; val bottom = height - 22 * d
        val n = shown.size
        val slot = (width - 12 * d) / n
        fun x(i: Int) = 6 * d + slot * (i + 0.5f)
        fun y(v: Float) = bottom - (v - min) / (max - min) * (bottom - top)
        val zy = y(0f)
        canvas.drawLine(0f, zy, width.toFloat(), zy, zero)
        p.reset()
        p.moveTo(0f, y(shown[0]))
        p.lineTo(x(0), y(shown[0]))
        for (i in 0 until n - 1) {
            val x0 = x(i); val x1 = x(i + 1)
            val y0 = y(shown[i]); val y1 = y(shown[i + 1])
            val mx = (x0 + x1) / 2
            p.cubicTo(mx, y0, mx, y1, x1, y1)
        }
        p.lineTo(width.toFloat(), y(shown[n - 1]))
        f.set(p)
        f.lineTo(width.toFloat(), zy); f.lineTo(0f, zy); f.close()
        canvas.drawPath(f, fill)
        canvas.drawPath(p, line)
    }
}

/** Waveform bars with a highlighted selection (ringtone maker). */
class WaveformView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var peaks: FloatArray? = null
        set(v) { field = v; invalidate() }
    var selStart = 0f
    var selEnd = 1f
    var playhead = -1f
    private val d = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val head = Paint()

    fun select(a: Float, b: Float) { selStart = a; selEnd = b; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val s = SkinManager.current
        val p = peaks
        val mid = height / 2f
        val n = p?.size ?: 80
        val w = width.toFloat() / n
        for (i in 0 until n) {
            val f = i.toFloat() / n
            val amp = p?.get(i) ?: 0.08f
            val inSel = f >= selStart && f <= selEnd
            bar.color = if (inSel) s.accent else SkinPainter.withAlpha(s.textSecondary, 0x60)
            val h = max(1.5f * d, amp * (height / 2f - 6 * d))
            canvas.drawRect(i * w + w * 0.18f, mid - h, (i + 1) * w - w * 0.18f, mid + h, bar)
        }
        sel.color = SkinPainter.withAlpha(s.accent, 0x18)
        canvas.drawRect(selStart * width, 0f, selEnd * width, height.toFloat(), sel)
        if (playhead >= 0) {
            head.color = s.text; head.strokeWidth = 2 * d
            canvas.drawLine(playhead * width, 0f, playhead * width, height.toFloat(), head)
        }
    }
}
