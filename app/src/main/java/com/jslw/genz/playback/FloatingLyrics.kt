package com.jslw.genz.playback

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.jslw.genz.skin.SkinManager
import kotlin.math.abs

/** "Desktop" lyrics: a small draggable overlay showing the current synced line over other apps. */
class FloatingLyrics(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastText = ""

    fun canShow(): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)
    val showing: Boolean get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null || !canShow()) return
        val d = ctx.resources.displayMetrics.density
        val skin = SkinManager.current
        val tv = TextView(ctx).apply {
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(skin.accent)
            setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
            typeface = skin.boldTypeface
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = 14 * d }
            maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.9f).toInt()
            text = "♪"
        }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (120 * d).toInt()
        }
        var downY = 0f; var startY = 0; var downX = 0f; var startX = 0
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; true }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (e.rawX - downX).toInt()
                    p.y = startY + (e.rawY - downY).toInt()
                    try { wm.updateViewLayout(tv, p) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - downX) < 10 && abs(e.rawY - downY) < 10) tv.alpha = if (tv.alpha > 0.6f) 0.35f else 1f
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(tv, p)
            view = tv; params = p
        } catch (_: Exception) {}
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    fun setLines(current: String, next: String?) {
        val tv = view ?: return
        val key = current + "|" + next
        if (key == lastText) return
        lastText = key
        val sb = SpannableStringBuilder(current.ifBlank { "♪" })
        if (!next.isNullOrBlank()) {
            val start = sb.length
            sb.append('\n').append(next)
            sb.setSpan(ForegroundColorSpan(0xCCFFFFFF.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.8f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.NORMAL), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.animate().alpha(0.2f).setDuration(90).withEndAction {
            tv.text = sb
            tv.animate().alpha(1f).setDuration(160).start()
        }.start()
    }
}
