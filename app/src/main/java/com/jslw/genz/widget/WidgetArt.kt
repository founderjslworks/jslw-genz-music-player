package com.jslw.genz.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.jslw.genz.skin.Skin

/**
 * Renders the home-screen widget artwork: a frosted backdrop made from the album cover, tinted with the
 * song's own colours, a soft glass edge and highlight, a rounded cover with a shadow and a glowing play button.
 * Drawn once per song / state change – no cost while the music plays.
 */
object WidgetArt {

    /** Two colours that represent the song (from its cover), or the theme's header gradient. */
    fun palette(art: Bitmap?, skin: Skin): IntArray {
        val c = com.jslw.genz.util.SongColors.from(art)
        return if (c != null && c.size >= 2) intArrayOf(c[0], c[c.size - 1]) else intArrayOf(skin.header, skin.headerEnd)
    }

    private fun darken(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    /** Cheap, smooth blur: shrink a lot, then scale back up with filtering (twice). */
    private fun blurred(src: Bitmap, w: Int, h: Int): Bitmap {
        val tiny = Bitmap.createScaledBitmap(src, 12, 12, true)
        val mid = Bitmap.createScaledBitmap(tiny, 48, 48, true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = maxOf(w, h).toFloat() / 48f
        val m = Matrix().apply { setScale(s, s); postTranslate((w - 48 * s) / 2f, (h - 48 * s) / 2f) }
        c.drawBitmap(mid, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun background(w: Int, h: Int, art: Bitmap?, skin: Skin, cols: IntArray, d: Float, square: Boolean): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = 26 * d
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val clip = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
        c.clipPath(clip)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (art != null) {
            if (square) {
                // full-bleed sharp cover
                val s = maxOf(w.toFloat() / art.width, h.toFloat() / art.height)
                val m = Matrix().apply { setScale(s, s); postTranslate((w - art.width * s) / 2f, (h - art.height * s) / 2f) }
                c.drawBitmap(art, m, p)
            } else {
                c.drawBitmap(blurred(art, w, h), 0f, 0f, p)
                // song-colour wash: deep and rich, never a flat black slab
                p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf((darken(cols[0], 0.55f) and 0x00FFFFFF) or 0xC8000000.toInt(), (darken(cols[1], 0.35f) and 0x00FFFFFF) or 0xE0000000.toInt()),
                    null, Shader.TileMode.CLAMP)
                c.drawRect(rect, p)
            }
        } else {
            p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(darken(cols[0], 0.8f), darken(cols[1], 0.5f)), null, Shader.TileMode.CLAMP)
            c.drawRect(rect, p)
        }
        if (square) {
            p.shader = LinearGradient(0f, h * 0.35f, 0f, h.toFloat(), intArrayOf(0x00000000, 0xE6000000.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRect(rect, p)
        }
        // soft light from the top-left, like glass catching the light
        p.shader = RadialGradient(w * 0.12f, -h * 0.2f, maxOf(w, h) * 0.75f, intArrayOf(0x38FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(rect, p)
        // colour glow behind the cover / bottom-right accent
        p.shader = RadialGradient(w * 0.95f, h * 1.05f, maxOf(w, h) * 0.6f, intArrayOf((cols[1] and 0x00FFFFFF) or 0x55000000, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(rect, p)
        p.shader = null
        // glass edge: bright on top, fading down
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.4f * d
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x66FFFFFF, 0x14FFFFFF), null, Shader.TileMode.CLAMP)
        val e = p.strokeWidth / 2
        c.drawRoundRect(RectF(e, e, w - e, h - e), r - e, r - e, p)
        return out
    }

    /** Rounded cover with a soft shadow. */
    fun cover(art: Bitmap, size: Int, d: Float): Bitmap {
        val pad = (6 * d).toInt()
        val out = Bitmap.createBitmap(size + pad * 2, size + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = 16 * d
        p.color = 0x66000000
        p.setShadowLayer(5 * d, 0f, 2 * d, 0x88000000.toInt())
        c.drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + size).toFloat(), (pad + size).toFloat()), r, r, p)
        p.clearShadowLayer()
        val s = maxOf(size.toFloat() / art.width, size.toFloat() / art.height)
        val m = Matrix().apply { setScale(s, s); postTranslate(pad + (size - art.width * s) / 2f, pad + (size - art.height * s) / 2f) }
        p.shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        p.isFilterBitmap = true
        c.drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + size).toFloat(), (pad + size).toFloat()), r, r, p)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1 * d; p.color = 0x40FFFFFF
        c.drawRoundRect(RectF(pad + 0.5f, pad + 0.5f, pad + size - 0.5f, pad + size - 0.5f), r, r, p)
        return out
    }

    /** Glowing round play / pause button in the song's colours. */
    fun playButton(playing: Boolean, size: Int, cols: IntArray, d: Float): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f; val r = size * 0.36f
        p.shader = RadialGradient(cx, cx, size / 2f, intArrayOf((cols[0] and 0x00FFFFFF) or 0x77000000, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, size / 2f, p)
        p.shader = LinearGradient(cx - r, cx - r, cx + r, cx + r, intArrayOf(0xFFFFFFFF.toInt(), 0xFFE9E6F2.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, r, p)
        p.shader = null
        p.color = darken(cols[0], 0.7f)
        if (playing) {
            val bw = r * 0.22f; val bh = r * 0.9f
            c.drawRoundRect(RectF(cx - r * 0.32f - bw / 2, cx - bh / 2, cx - r * 0.32f + bw / 2, cx + bh / 2), bw / 2, bw / 2, p)
            c.drawRoundRect(RectF(cx + r * 0.32f - bw / 2, cx - bh / 2, cx + r * 0.32f + bw / 2, cx + bh / 2), bw / 2, bw / 2, p)
        } else {
            val t = Path().apply {
                moveTo(cx - r * 0.3f, cx - r * 0.48f); lineTo(cx - r * 0.3f, cx + r * 0.48f); lineTo(cx + r * 0.52f, cx); close()
            }
            p.strokeJoin = Paint.Join.ROUND; p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = r * 0.08f
            c.drawPath(t, p)
        }
        return out
    }

    // ================================================================ turntable widget (standard style)
    private fun noise(c: Canvas, w: Int, h: Int, d: Float) {
        val r = java.util.Random(11); val p = Paint()
        for (i in 0 until (w * h / 90)) {
            p.color = if (r.nextBoolean()) 0x0C000000 else 0x10FFFFFF
            val x = r.nextInt(w).toFloat(); val y = r.nextInt(h).toFloat(); c.drawRect(x, y, x + d * 0.6f, y + d * 0.6f, p)
        }
    }

    /** Pearl-rose body with grain, soft top light and a fine edge. */
    fun pearl(w: Int, h: Int, radius: Float, d: Float): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rect = RectF(2 * d, 2 * d, w - 2 * d, h - 4 * d)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x55000000; p.setShadowLayer(4 * d, 0f, 2 * d, 0x66000000)
        c.drawRoundRect(rect, radius, radius, p); p.clearShadowLayer()
        p.shader = LinearGradient(0f, rect.top, rect.right, rect.bottom, intArrayOf(0xFFF6EDF1.toInt(), 0xFFE7D6DE.toInt(), 0xFFD3BCC8.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, p)
        c.save(); c.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        noise(c, w, h, d)
        p.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(rect, p)
        c.restore()
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d
        p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0xCCFFFFFF.toInt(), 0x33A07F90, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, p)
        return out
    }

    /** Record in a recessed well with the album cover as its label and a tonearm (on the record while playing). */
    fun turntable(art: Bitmap?, playing: Boolean, size: Int, d: Float): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cx = size * 0.46f; val cy = size * 0.54f; val r = size * 0.4f
        // well
        p.shader = LinearGradient(0f, cy - r * 1.1f, 0f, cy + r * 1.1f, 0xFFC9B3BF.toInt(), 0xFFF3E9EE.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.09f, p)
        // record
        p.shader = RadialGradient(cx, cy + 3 * d, r * 1.04f, intArrayOf(0x99000000.toInt(), 0x00000000), floatArrayOf(0.9f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + 3 * d, r * 1.04f, p)
        p.shader = null; p.color = 0xFF0D0C0E.toInt(); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.6f * d
        var rr = r * 0.96f; var k = 0
        while (rr > r * 0.4f) { p.color = if (k % 3 == 0) 0x26FFFFFF else 0x10FFFFFF; c.drawCircle(cx, cy, rr, p); rr -= 1.5f * d; k++ }
        p.style = Paint.Style.FILL
        val lr = r * 0.38f
        if (art != null) {
            val sh = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = (lr * 2) / minOf(art.width, art.height)
            sh.setLocalMatrix(Matrix().apply { setScale(s, s); postTranslate(cx - art.width * s / 2, cy - art.height * s / 2) })
            p.shader = sh; c.drawCircle(cx, cy, lr, p); p.shader = null
        } else { p.color = 0xFFC8333F.toInt(); c.drawCircle(cx, cy, lr, p) }
        p.color = 0xFFE0E0E0.toInt(); c.drawCircle(cx, cy, lr * 0.07f, p)
        // sheen
        p.shader = android.graphics.SweepGradient(cx, cy, intArrayOf(0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x22FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.1f, 0.2f, 0.5f, 0.6f, 0.7f))
        c.drawCircle(cx, cy, r, p); p.shader = null
        // tonearm
        val px = cx + r * 0.98f; val py = cy - r * 0.98f
        val deg = if (playing) 122.0 else 100.0
        val len = r * 1.18f
        val ex = px + (Math.cos(Math.toRadians(deg)) * len).toFloat(); val ey = py + (Math.sin(Math.toRadians(deg)) * len).toFloat()
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.color = 0x44000000; p.strokeWidth = 4 * d; c.drawLine(px + 3 * d, py + 4 * d, ex + 3 * d, ey + 4 * d, p)
        p.shader = LinearGradient(px, py, ex, ey, 0xFFF4F4F6.toInt(), 0xFF9C9CA3.toInt(), Shader.TileMode.CLAMP)
        p.strokeWidth = 3f * d; c.drawLine(px, py, ex, ey, p); p.shader = null
        p.style = Paint.Style.FILL
        c.save(); c.rotate((deg - 90).toFloat(), ex, ey)
        p.color = 0xFFEDEDED.toInt(); c.drawRoundRect(RectF(ex - 5 * d, ey - 2 * d, ex + 5 * d, ey + 11 * d), 2 * d, 2 * d, p)
        p.color = 0xFF3A3A3A.toInt(); c.drawRect(ex - 2.5f * d, ey + 9 * d, ex + 2.5f * d, ey + 13 * d, p)
        c.restore()
        p.shader = RadialGradient(px - 2 * d, py - 2 * d, 11 * d, 0xFFFFFFFF.toInt(), 0xFF8E8E95.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(px, py, 9 * d, p); p.shader = null
        p.color = 0xFF2B2B30.toInt(); c.drawCircle(px, py, 3.5f * d, p)
        return out
    }

    /** Round 3D button: grey, or red for play / pause. Glyphs are drawn, so they stay crisp. */
    fun puck(kind: String, accent: Boolean, size: Int, d: Float): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f; val cy = size / 2f - 1.5f * d; val r = size * 0.42f
        p.shader = RadialGradient(cx, cy + 3 * d, r * 1.18f, intArrayOf(0x66000000, 0x00000000), floatArrayOf(0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + 3 * d, r * 1.18f, p)
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0xFFFFFFFF.toInt(), 0xFFBDB3B8.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        val ri = r * 0.84f
        p.shader = LinearGradient(0f, cy - ri, 0f, cy + ri,
            if (accent) 0xFFE2454F.toInt() else 0xFF8C8A91.toInt(), if (accent) 0xFFA81F2A.toInt() else 0xFF5E5C63.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, ri, p)
        p.shader = LinearGradient(0f, cy - ri, 0f, cy, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - ri * 0.78f, cy - ri * 0.94f, cx + ri * 0.78f, cy - ri * 0.05f), p); p.shader = null
        p.color = if (accent) 0xFFFFFFFF.toInt() else 0xFF2E2C33.toInt()
        val g = ri * 0.42f
        fun tri(x: Float, dir: Int) {
            c.drawPath(Path().apply { moveTo(x - dir * g * 0.5f, cy - g * 0.55f); lineTo(x - dir * g * 0.5f, cy + g * 0.55f); lineTo(x + dir * g * 0.5f, cy); close() }, p)
        }
        when (kind) {
            "prev" -> { tri(cx - g * 0.45f, -1); tri(cx + g * 0.45f, -1) }
            "next" -> { tri(cx - g * 0.45f, 1); tri(cx + g * 0.45f, 1) }
            "play" -> tri(cx + g * 0.12f, 1)
            "pause" -> {
                val bw = g * 0.32f
                c.drawRoundRect(RectF(cx - g * 0.45f, cy - g * 0.6f, cx - g * 0.45f + bw, cy + g * 0.6f), bw / 3, bw / 3, p)
                c.drawRoundRect(RectF(cx + g * 0.45f - bw, cy - g * 0.6f, cx + g * 0.45f, cy + g * 0.6f), bw / 3, bw / 3, p)
            }
            "queue" -> {
                for (k in 0..2) {
                    val y = cy - g * 0.5f + k * g * 0.5f
                    c.drawCircle(cx - g * 0.6f, y, g * 0.09f, p)
                    c.drawRoundRect(RectF(cx - g * 0.38f, y - g * 0.07f, cx + g * 0.7f, y + g * 0.07f), g * 0.07f, g * 0.07f, p)
                }
            }
        }
        return out
    }
}
