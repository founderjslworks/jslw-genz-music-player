package com.jslw.genz.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.jslw.genz.data.Prefs

/**
 * Performance mode. "Automatic" picks Light on low-memory or slow phones; Light trims decorative motion
 * (staggered menus, cover-flow extras, list scroll effects) so scrolling and playback stay smooth.
 */
object Perf {
    @Volatile private var weak: Boolean? = null

    fun init(ctx: Context) {
        if (weak != null) return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val ramGb = if (Build.VERSION.SDK_INT >= 16) mi.totalMem / 1073741824.0 else 1.0
        weak = (am?.isLowRamDevice == true) || ramGb < 2.6 || Runtime.getRuntime().availableProcessors() <= 2 || Build.VERSION.SDK_INT < 21
    }

    val light: Boolean
        get() = when (Prefs.performance) {
            1 -> false
            2 -> true
            else -> weak == true
        }

    fun describe(): String = when (Prefs.performance) {
        1 -> "Best looks"
        2 -> "Light"
        else -> if (weak == true) "Automatic (Light on this phone)" else "Automatic (Best looks on this phone)"
    }
}
