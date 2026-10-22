package com.jslw.genz.util

import android.app.Activity
import android.os.Build
import android.view.Choreographer
import android.view.Display
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.jslw.genz.data.Prefs

/**
 * Smoothness helpers.
 *
 * - Asks the panel for its highest refresh rate (90 / 120 / 144 / 165 Hz phones) at the current
 *   resolution. LTPO panels still drop to low rates when nothing moves, so this costs little battery.
 * - [FrameTicker] runs UI updates on the display's vsync (Choreographer), so progress bars and lyrics
 *   move at the panel's real frame rate instead of jumping on a timer.
 */
object Smooth {

    fun preferHighRefreshRate(a: Activity) {
        if (!Prefs.highRefresh) return
        try {
            @Suppress("DEPRECATION")
            val display: Display? = if (Build.VERSION.SDK_INT >= 30) a.display else a.windowManager.defaultDisplay
            display ?: return
            val lp = a.window.attributes
            if (Build.VERSION.SDK_INT >= 23) {
                val cur = display.mode
                val best = display.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .maxByOrNull { it.refreshRate } ?: return
                if (lp.preferredDisplayModeId != best.modeId) {
                    lp.preferredDisplayModeId = best.modeId
                    a.window.attributes = lp
                }
            } else if (Build.VERSION.SDK_INT >= 21) {
                @Suppress("DEPRECATION")
                val max = display.supportedRefreshRates.maxOrNull() ?: return
                lp.preferredRefreshRate = max
                a.window.attributes = lp
            }
        } catch (_: Exception) {}
    }

    /** Lists: fixed size, bigger view cache, no "blink" change animation. */
    fun tuneList(rv: RecyclerView) {
        rv.setHasFixedSize(true)
        rv.setItemViewCacheSize(12)
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }
}

/** Calls [onFrame] once per display frame while started. Allocation-free. */
class FrameTicker(private val onFrame: (frameTimeNanos: Long) -> Unit) : Choreographer.FrameCallback {
    private var running = false

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        onFrame(frameTimeNanos)
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }
}
