package com.jslw.genz.skin

import android.graphics.Bitmap

/**
 * Soft, blurred copy of the playing song's cover used by translucent ("glass") themes as the screen
 * background. Blur = shrink to a few pixels, then enlarge with filtering (cheap and smooth on every phone).
 */
object Backdrop {
    @Volatile var art: Bitmap? = null
        private set
    @Volatile var generation = 0
        private set

    fun set(full: Bitmap?) {
        art = full?.let {
            try {
                val tiny = Bitmap.createScaledBitmap(it, 20, 20, true)
                val soft = Bitmap.createScaledBitmap(tiny, 120, 120, true)
                if (tiny != it) tiny.recycle()
                soft
            } catch (e: Exception) { null }
        }
        generation++
    }
}
