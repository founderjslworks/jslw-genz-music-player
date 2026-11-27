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
                refreshHz = best.refreshRate
                if (lp.preferredDisplayModeId != best.modeId) {
                    lp.preferredDisplayModeId = best.modeId
                    a.window.attributes = lp
                }
                // Android 15+: jump to the panel's top rate the moment a finger lands (LTPO / 120–240 Hz panels),
                // then let it relax when nothing moves – instant response without draining the battery.
                if (Build.VERSION.SDK_INT >= 35) {
                    try { a.window.setFrameRateBoostOnTouchEnabled(true) } catch (_: Throwable) {}
                }
            } else if (Build.VERSION.SDK_INT >= 21) {
                @Suppress("DEPRECATION")
                val max = display.supportedRefreshRates.maxOrNull() ?: return
                lp.preferredRefreshRate = max
                a.window.attributes = lp
            }
        } catch (_: Exception) {}
    }

    /** Highest refresh rate this screen asked for (60 until known). */
    @Volatile var refreshHz = 60f; private set

    /** True on 90 Hz and faster panels. */
    val highRefresh: Boolean get() = refreshHz >= 89f

    /** Lists: fixed size, bigger view cache (more on 120/144/240 Hz panels), no "blink" change animation. */
    fun tuneList(rv: RecyclerView) {
        rv.setHasFixedSize(true)
        rv.setItemViewCacheSize(if (highRefresh) 24 else 12)
        // build the next rows ahead of time while scrolling (smaller frame budget at high refresh rates)
        (rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.initialPrefetchItemCount = if (highRefresh) 6 else 3
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        ListAnim.attach(rv)
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

/**
 * "Feather touch": a light haptic tick plus a small spring on press, for chips, buttons and slider steps.
 * Uses the phone's own crisp haptic effects where available (Android 11+ / 14+).
 */
object Touch {
    fun tick(v: android.view.View) {
        val c = when {
            Build.VERSION.SDK_INT >= 34 -> android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            Build.VERSION.SDK_INT >= 21 -> android.view.HapticFeedbackConstants.CLOCK_TICK
            else -> android.view.HapticFeedbackConstants.VIRTUAL_KEY
        }
        v.performHapticFeedback(c)
    }

    fun confirm(v: android.view.View) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Springy press: shrinks slightly under the finger and bounces back on release. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun spring(v: android.view.View) {
        val overshoot = android.view.animation.OvershootInterpolator(3f)
        val press = android.view.animation.DecelerateInterpolator()
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).setInterpolator(press).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(overshoot).start()
            }
            false
        }
    }
}
