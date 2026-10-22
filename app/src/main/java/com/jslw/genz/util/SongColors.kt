package com.jslw.genz.util

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.jslw.genz.data.Prefs

/**
 * Three vivid colours taken from a song's cover (dark → bright), so progress bars and effects
 * change with every song instead of sticking to one accent.
 */
object SongColors {
    private var lastBmp: Bitmap? = null
    private var last: IntArray? = null

    fun from(bmp: Bitmap?): IntArray? {
        if (bmp == null || !Prefs.progressFollowsSong) return null
        if (bmp === lastBmp) return last
        val p = try { Palette.from(bmp).maximumColorCount(24).generate() } catch (e: Exception) { return null }
        val swatches = listOfNotNull(p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch,
            p.lightMutedSwatch, p.dominantSwatch) + p.swatches.sortedByDescending { it.population }.take(6)
        val hsl = FloatArray(3)
        // most colourful first, skipping near-duplicates
        val picked = ArrayList<Int>()
        for (sw in swatches.sortedByDescending { it.hsl[1] * (1f - kotlin.math.abs(it.hsl[2] - 0.5f)) * kotlin.math.sqrt(it.population.toFloat()) }) {
            val c = sw.rgb
            if (picked.none { hueDistance(it, c) < 28f }) picked += c
            if (picked.size == 3) break
        }
        if (picked.isEmpty()) return null
        while (picked.size < 3) {
            ColorUtils.colorToHSL(picked[0], hsl)
            hsl[0] = (hsl[0] + 35f * picked.size) % 360f
            picked += ColorUtils.HSLToColor(hsl)
        }
        // make them glow: rich saturation, dark → light order for the gradient
        val out = picked.map { c ->
            ColorUtils.colorToHSL(c, hsl)
            hsl[1] = hsl[1].coerceAtLeast(0.6f)
            hsl[2] = hsl[2].coerceIn(0.45f, 0.72f)
            ColorUtils.HSLToColor(hsl)
        }.sortedBy { ColorUtils.calculateLuminance(it) }.toIntArray()
        lastBmp = bmp; last = out
        return out
    }

    private fun hueDistance(a: Int, b: Int): Float {
        val x = FloatArray(3); val y = FloatArray(3)
        ColorUtils.colorToHSL(a, x); ColorUtils.colorToHSL(b, y)
        val d = kotlin.math.abs(x[0] - y[0])
        return minOf(d, 360f - d) + kotlin.math.abs(x[2] - y[2]) * 60f
    }
}
