package com.jslw.genz.ui.views

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import com.jslw.genz.R
import com.jslw.genz.audio.DeviceStatus

/**
 * "Playing on" strip: the soundbar / Bluetooth device with its battery and link codec, plus this phone's
 * battery. Hidden when the music plays on the phone or wired headphones. Updates itself while attached.
 */
class DeviceStrip(context: Context, private var fg: Int, private var accent: Int) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val icon = ImageView(context)
    private val title = TextView(context)
    private val stats = LinearLayout(context)
    private var registered = false
    private val tick = Runnable { refresh() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        val p = (10 * d).toInt()
        setPadding(p, (8 * d).toInt(), p, (8 * d).toInt())
        icon.layoutParams = LayoutParams((34 * d).toInt(), (34 * d).toInt())
        val ip = (7 * d).toInt(); icon.setPadding(ip, ip, ip, ip)
        addView(icon)
        val col = LinearLayout(context).apply { orientation = VERTICAL; setPadding((10 * d).toInt(), 0, 0, 0) }
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.maxLines = 1; title.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(title)
        stats.orientation = HORIZONTAL; stats.gravity = Gravity.CENTER_VERTICAL
        col.addView(stats)
        addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        paint()
    }

    fun setColors(fg: Int, accent: Int) { this.fg = fg; this.accent = accent; paint(); refresh() }

    private fun paint() {
        background = GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(ColorUtils.setAlphaComponent(fg, 0x14))
            setStroke(d.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(accent, 0x55))
        }
        icon.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        icon.setColorFilter(if (ColorUtils.calculateLuminance(accent or 0xFF000000.toInt()) > 0.55) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        title.setTextColor(fg)
    }

    private fun stat(res: Int, text: String, warn: Boolean = false) {
        val tv = TextView(context)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        val c = if (warn) 0xFFFF6B6B.toInt() else ColorUtils.setAlphaComponent(fg, 0xCC)
        tv.setTextColor(c)
        AppCompatResources.getDrawable(context, res)?.mutate()?.let { dr ->
            val w = DrawableCompat.wrap(dr); DrawableCompat.setTint(w, if (warn) c else accent)
            val s = (13 * d).toInt(); w.setBounds(0, 0, s, s)
            tv.setCompoundDrawables(w, null, null, null)
            tv.compoundDrawablePadding = (3 * d).toInt()
        }
        tv.setPadding(0, (2 * d).toInt(), (12 * d).toInt(), 0)
        stats.addView(tv)
    }

    fun refresh() {
        removeCallbacks(tick)
        DeviceStatus.read(context) { info -> show(info) }
        postDelayed(tick, 30_000)
    }

    private fun show(i: DeviceStatus.Info?) {
        if (i == null) { if (visibility != GONE) animate().alpha(0f).setDuration(180).withEndAction { visibility = GONE }.start(); return }
        icon.setImageDrawable(AppCompatResources.getDrawable(context, when { i.soundbar -> R.drawable.ic_soundbar; i.name?.contains("speaker", true) == true || i.name?.contains("boombox", true) == true -> R.drawable.ic_speaker_small; else -> R.drawable.ic_headphones }))
        title.text = "Playing on " + (i.name ?: if (i.soundbar) "soundbar" else "Bluetooth")
        stats.removeAllViews()
        when {
            i.deviceBattery != null -> stat(R.drawable.ic_battery, "${i.deviceBattery}%", i.deviceBattery <= 15)
            i.bluetooth && !i.needsPermission -> stat(R.drawable.ic_battery, "Not reported")
        }
        if (i.codec != null) stat(R.drawable.ic_bt_link, i.codec)
        else if (i.bluetooth && !i.needsPermission) stat(R.drawable.ic_bt_link, "Connected")
        if (i.phoneBattery >= 0) stat(R.drawable.ic_phone_batt, "Phone ${i.phoneBattery}%" + if (i.charging) " ⚡" else "", i.phoneBattery <= 15 && !i.charging)
        if (i.needsPermission) {
            stat(R.drawable.ic_bt_link, "Tap to show battery")
            setOnClickListener {
                (context as? Activity)?.let { a ->
                    if (Build.VERSION.SDK_INT >= 31) a.requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 7410)
                }
            }
        } else setOnClickListener(null)
        if (visibility != VISIBLE) {
            alpha = 0f; translationY = -8 * d; visibility = VISIBLE
            animate().alpha(1f).translationY(0f).setDuration(320).start()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { removeCallbacks(tick); postDelayed(tick, 400) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!registered) {
            val f = IntentFilter().apply {
                addAction(DeviceStatus.ACTION_BT_BATTERY)
                addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
                addAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
                else context.registerReceiver(receiver, f)
                registered = true
            } catch (_: Throwable) {}
        }
        refresh()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        if (registered) { try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}; registered = false }
        super.onDetachedFromWindow()
    }

    // back from the Bluetooth permission prompt (or any dialog): read again
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && registered) refresh()
    }

    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        if (v == View.VISIBLE && registered) refresh()
    }
}
