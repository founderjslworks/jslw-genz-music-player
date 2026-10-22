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
}
