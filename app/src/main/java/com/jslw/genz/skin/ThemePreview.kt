package com.jslw.genz.skin

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * A tiny phone screen drawn in a theme's colours – header, Album Flow covers, sound chips, seek bar and the
 * round play button with its ring – so the Themes list shows how today's GEN-Z screens will look.
 */
class ThemePreview(private val s: Skin, private val d: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val x0 = b.left.toFloat(); val y0 = b.top.toFloat()
        val corner = 9 * d
        // screen
        p.shader = LinearGradient(0f, y0, 0f, y0 + h, s.npBackground, s.npBackgroundEnd, Shader.TileMode.CLAMP)
        r.set(x0, y0, x0 + w, y0 + h); c.drawRoundRect(r, corner, corner, p)
        p.shader = null
        // header
        c.save(); c.clipRect(x0, y0, x0 + w, y0 + h * 0.16f)
        p.shader = LinearGradient(x0, 0f, x0 + w, 0f, s.header, s.headerEnd, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, corner, corner, p); p.shader = null; c.restore()
        // album flow: centre cover + two tilted side covers
        val cw = w * 0.36f; val cy = y0 + h * 0.22f
        val cx = x0 + w / 2f
        for (side in intArrayOf(-1, 1)) {
            c.save()
            val sx = cx + side * cw * 0.72f
            c.translate(sx, cy + cw * 0.08f)
            c.skew(0f, side * 0.22f)
            p.color = ColorUtils.blendARGB(s.accent, s.npBackground, 0.55f)
            r.set(-cw * 0.38f, 0f, cw * 0.38f, cw * 0.84f); c.drawRoundRect(r, 2 * d, 2 * d, p)
            c.restore()
        }
        p.shader = LinearGradient(cx - cw / 2, cy, cx + cw / 2, cy + cw, s.header, s.accent, Shader.TileMode.CLAMP)
        r.set(cx - cw / 2, cy, cx + cw / 2, cy + cw); c.drawRoundRect(r, 3 * d, 3 * d, p); p.shader = null
        // chips
        val chipY = cy + cw + h * 0.07f; val chH = h * 0.065f
        p.color = s.accent; r.set(x0 + w * 0.12f, chipY, x0 + w * 0.42f, chipY + chH); c.drawRoundRect(r, chH, chH, p)
        p.color = ColorUtils.setAlphaComponent(s.npText, 0x40); r.set(x0 + w * 0.47f, chipY, x0 + w * 0.88f, chipY + chH); c.drawRoundRect(r, chH, chH, p)
        // seek bar
        val sy = chipY + chH + h * 0.07f
        p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 1.6f * d; p.style = Paint.Style.STROKE
        p.color = ColorUtils.setAlphaComponent(s.npText, 0x40); c.drawLine(x0 + w * 0.12f, sy, x0 + w * 0.88f, sy, p)
        p.color = s.accent; c.drawLine(x0 + w * 0.12f, sy, x0 + w * 0.6f, sy, p)
        // play button with ring
        val pr = w * 0.11f; val py = y0 + h - pr * 1.9f
        p.strokeWidth = 1.2f * d; p.color = ColorUtils.setAlphaComponent(s.accent, 0x99); c.drawCircle(cx, py, pr * 1.35f, p)
        p.style = Paint.Style.FILL; p.color = s.accent; c.drawCircle(cx, py, pr, p)
        p.color = s.onAccent
        val t = pr * 0.42f
        c.drawRect(cx - t * 0.8f, py - t, cx - t * 0.25f, py + t, p); c.drawRect(cx + t * 0.25f, py - t, cx + t * 0.8f, py + t, p)
        // prev / next dots
        p.color = ColorUtils.setAlphaComponent(s.npText, 0xAA)
        c.drawCircle(cx - pr * 2.6f, py, pr * 0.28f, p); c.drawCircle(cx + pr * 2.6f, py, pr * 0.28f, p)
        // outline
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = s.divider
        r.set(x0 + d / 2, y0 + d / 2, x0 + w - d / 2, y0 + h - d / 2); c.drawRoundRect(r, corner, corner, p)
        p.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
