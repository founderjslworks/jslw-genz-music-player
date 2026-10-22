package com.jslw.genz.ui

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jslw.genz.skin.SkinManager
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The Indian tricolour: saffron, white with the navy Ashoka Chakra, and green. */
class TricolorDrawable(private val density: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val r = 1.6f * density
        val rect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        c.save()
        c.clipRect(rect)
        val band = h / 3f
        p.style = Paint.Style.FILL
        p.color = 0xFFFF9933.toInt(); c.drawRect(rect.left, rect.top, rect.right, rect.top + band, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawRect(rect.left, rect.top + band, rect.right, rect.top + band * 2, p)
        p.color = 0xFF138808.toInt(); c.drawRect(rect.left, rect.top + band * 2, rect.right, rect.bottom, p)
        // Ashoka Chakra: navy wheel with 24 spokes
        val cx = rect.centerX(); val cy = rect.centerY(); val cr = band * 0.46f
        p.color = 0xFF000080.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(0.6f, cr * 0.16f)
        c.drawCircle(cx, cy, cr, p)
        p.strokeWidth = maxOf(0.35f, cr * 0.07f)
        for (k in 0 until 24) {
            val a = Math.PI * 2 * k / 24
            c.drawLine(cx, cy, cx + (cos(a) * cr).toFloat(), cy + (sin(a) * cr).toFloat(), p)
        }
        c.restore()
        // fine edge so the white band shows on light themes
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.6f * density; p.color = 0x40000000
        c.drawRoundRect(RectF(rect.left + 0.3f, rect.top + 0.3f, rect.right - 0.3f, rect.bottom - 0.3f), r * 0.4f, r * 0.4f, p)
        p.style = Paint.Style.FILL
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = (18 * density).toInt()
    override fun getIntrinsicHeight() = (12 * density).toInt()
}

/** "🇮🇳 Made in India with ♥" – a small strip with a beating heart, shown at the bottom of every screen. */
object IndiaBadge {

    fun view(ctx: Context, textColor: Int = SkinManager.current.textSecondary): View {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (3 * d).toInt(), 0, (4 * d).toInt())
            tag = "india_badge"
            contentDescription = "Made in India with love"
        }
        row.addView(ImageView(ctx).apply {
            setImageDrawable(TricolorDrawable(d))
            layoutParams = LinearLayout.LayoutParams((15 * d).toInt(), (10 * d).toInt()).apply { marginEnd = (6 * d).toInt() }
        })
        row.addView(TextView(ctx).apply {
            text = "Made in India with"
            textSize = 10.5f
            setTextColor((textColor and 0x00FFFFFF) or 0xD9000000.toInt())
            if (android.os.Build.VERSION.SDK_INT >= 21) letterSpacing = 0.04f
        })
        val heart = TextView(ctx).apply {
            text = "♥"
            textSize = 13f
            setTextColor(0xFFE53950.toInt())
            setPadding((4 * d).toInt(), 0, 0, 0)
        }
        row.addView(heart)
        heartbeat(heart)
        return row
    }

    /** "Lub-dub" heartbeat: two quick beats, a rest, repeat – only while the view is on screen. */
    fun heartbeat(v: View) {
        val sx = PropertyValuesHolder.ofKeyframe(View.SCALE_X,
            Keyframe.ofFloat(0f, 1f), Keyframe.ofFloat(0.10f, 1.35f), Keyframe.ofFloat(0.22f, 0.95f),
            Keyframe.ofFloat(0.32f, 1.22f), Keyframe.ofFloat(0.46f, 1f), Keyframe.ofFloat(1f, 1f))
        val sy = sx.clone().apply { setPropertyName("scaleY") }
        val anim = ObjectAnimator.ofPropertyValuesHolder(v, sx, sy).apply {
            duration = 1150
            repeatCount = ValueAnimator.INFINITE
        }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { if (!anim.isStarted) anim.start() }
            override fun onViewDetachedFromWindow(view: View) { anim.cancel(); view.scaleX = 1f; view.scaleY = 1f }
        })
        if (v.isAttachedToWindow) anim.start()
    }

    /** Adds the badge to the bottom of a screen's root (once). */
    fun attach(root: View, textColor: Int = SkinManager.current.textSecondary) {
        val g = root as? ViewGroup ?: return
        if (g.findViewWithTag<View>("india_badge") != null) return
        val badge = view(root.context, textColor)
        when (g) {
            is LinearLayout -> if (g.orientation == LinearLayout.VERTICAL)
                g.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            is FrameLayout -> g.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (2 * root.resources.displayMetrics.density).toInt() })
            else -> g.addView(badge)
        }
    }
}
