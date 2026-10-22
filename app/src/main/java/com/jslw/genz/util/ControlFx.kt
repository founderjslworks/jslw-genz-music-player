package com.jslw.genz.util

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView

/**
 * Animations for the transport controls: play / pause morph with a ripple burst,
 * and next / previous "whoosh" with a directional nudge and ghost chevrons.
 * Everything is drawn in the window overlay, so nothing is clipped and no layout changes.
 */
object ControlFx {

    /** Squash on touch, spring back with overshoot. */
    fun pop(v: View, scale: Float = 0.82f) {
        v.animate().cancel()
        v.animate().scaleX(scale).scaleY(scale).setDuration(80).setInterpolator(DecelerateInterpolator()).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3.2f)).start()
        }.start()
    }

    /**
     * Play ↔ pause: the icon spins and shrinks out, the new icon springs in, the button "heartbeats"
     * and rings burst outward (play) or draw inward (pause).
     */
    fun morphPlay(button: View, icon: ImageView, playing: Boolean, color: Int, swap: () -> Unit) {
        icon.animate().cancel()
        val dir = if (playing) 1 else -1
        icon.animate().rotation(dir * 90f).scaleX(0.3f).scaleY(0.3f).alpha(0f).setDuration(120)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                swap()
                icon.rotation = -dir * 90f
                icon.animate().rotation(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(460)
                    .setInterpolator(OvershootInterpolator(2.6f)).start()
            }.start()
        if (button !== icon) {
            button.animate().cancel()
            button.animate().scaleX(1.14f).scaleY(1.14f).setDuration(140).setInterpolator(DecelerateInterpolator()).withEndAction {
                button.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
        }
        rings(button, color, outward = playing)
    }

    /** Next / previous: the button nudges in its direction while ghost chevrons fly past. */
    fun skip(button: View, forward: Boolean, color: Int) {
        val d = button.resources.displayMetrics.density
        val dx = (if (forward) 1 else -1) * 14 * d
        button.animate().cancel()
        button.animate().translationX(dx).scaleX(0.86f).scaleY(0.86f).setDuration(110).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                button.animate().translationX(0f).scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
        overlay(button, 520) { c, t, cx, cy, r, p ->
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3.2f * d
            p.strokeCap = Paint.Cap.ROUND
            for (k in 0 until 3) {
                val tt = (t * 1.25f - k * 0.14f).coerceIn(0f, 1f)
                if (tt <= 0f || tt >= 1f) continue
                val x = cx + (if (forward) 1 else -1) * (r * 0.4f + tt * r * 1.7f)
                val s = r * 0.42f * (1f - tt * 0.4f)
                p.alpha = (200 * (1f - tt)).toInt()
                val sign = if (forward) 1 else -1
                c.drawLine(x - sign * s * 0.5f, cy - s, x + sign * s * 0.5f, cy, p)
                c.drawLine(x + sign * s * 0.5f, cy, x - sign * s * 0.5f, cy + s, p)
            }
        }.also { it.paint.color = color }
    }

    /** Expanding (or contracting) rings around a view. */
    fun rings(v: View, color: Int, outward: Boolean = true) {
        val d = v.resources.displayMetrics.density
        overlay(v, 700) { c, t, cx, cy, r, p ->
            p.style = Paint.Style.STROKE
            for (k in 0 until 3) {
                val tt = (t * 1.3f - k * 0.15f).coerceIn(0f, 1f)
                if (tt <= 0f || tt >= 1f) continue
                val e = 1f - (1f - tt) * (1f - tt)
                val rad = if (outward) r * (0.95f + e * 0.9f) else r * (1.85f - e * 0.9f)
                p.strokeWidth = (4f - k) * d * (1f - tt * 0.6f)
                p.alpha = (190 * (1f - tt)).toInt()
                c.drawCircle(cx, cy, rad, p)
            }
            // sparkle dots on the first ring
            if (outward) {
                p.style = Paint.Style.FILL
                val e = (t * 1.3f).coerceIn(0f, 1f)
                for (k in 0 until 8) {
                    val a = Math.toRadians((k * 45 + 20).toDouble())
                    val rad = r * (1f + e * 1.1f)
                    p.alpha = (230 * (1f - e)).toInt()
                    c.drawCircle(cx + (Math.cos(a) * rad).toFloat(), cy + (Math.sin(a) * rad).toFloat(), 2.6f * d * (1f - e * 0.5f), p)
                }
            }
        }.also { it.paint.color = color }
    }

    // ---------------------------------------------------------------- overlay plumbing
    private class FxDrawable(val draw: (Canvas, Float, Float, Float, Float, Paint) -> Unit) : Drawable() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var t = 0f; var cx = 0f; var cy = 0f; var r = 0f
        override fun draw(c: Canvas) {
            val col = paint.color
            draw(c, t, cx, cy, r, paint)
            paint.color = col
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun overlay(v: View, duration: Long, draw: (Canvas, Float, Float, Float, Float, Paint) -> Unit): FxDrawable {
        val fx = FxDrawable(draw)
        val root = v.rootView as? ViewGroup ?: return fx
        val loc = IntArray(2); v.getLocationInWindow(loc)
        val rl = IntArray(2); root.getLocationInWindow(rl)
        fx.cx = loc[0] - rl[0] + v.width / 2f
        fx.cy = loc[1] - rl[1] + v.height / 2f
        fx.r = minOf(v.width, v.height) / 2f
        fx.setBounds(0, 0, root.width, root.height)
        val ov = root.overlay
        ov.add(fx)
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { a -> fx.t = a.animatedValue as Float; fx.invalidateSelf(); root.invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { ov.remove(fx) }
            })
            start()
        }
        return fx
    }
}
