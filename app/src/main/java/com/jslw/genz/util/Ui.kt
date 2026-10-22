package com.jslw.genz.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatTotal(ms: Long): String {
    val min = ms / 60000
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

fun isLight(color: Int) = ColorUtils.calculateLuminance(color) > 0.5

object SystemBars {
    /**
     * Edge-to-edge on Android 5+ (required on Android 15+), with insets padded so content never
     * hides under the status bar or the gesture-navigation handle. KitKat keeps the classic layout.
     */
    fun setup(activity: Activity, root: View, statusColor: Int, navColor: Int) {
        val window = activity.window
        if (Build.VERSION.SDK_INT >= 21) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = if (Build.VERSION.SDK_INT >= 26) Color.TRANSPARENT else ColorUtils.setAlphaComponent(navColor, 0xE6)
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(root)
        }
        colorIcons(window, root, statusColor, navColor)
    }

    fun colorIcons(window: Window, view: View, statusColor: Int, navColor: Int) {
        val c = WindowInsetsControllerCompat(window, view)
        if (Build.VERSION.SDK_INT >= 23) c.isAppearanceLightStatusBars = isLight(statusColor)
        if (Build.VERSION.SDK_INT >= 26) c.isAppearanceLightNavigationBars = isLight(navColor)
    }
}
