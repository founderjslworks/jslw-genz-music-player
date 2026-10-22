package com.jslw.genz.skin

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.jslw.genz.R
import androidx.core.graphics.ColorUtils
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider

/** Colours used on the Now Playing screen; may come from the skin or from the album art. */
data class NpColors(
    val bgTop: Int, val bgBottom: Int, val text: Int, val text2: Int, val accent: Int, val onAccent: Int,
) {
    companion object {
        fun of(s: Skin) = NpColors(s.npBackground, s.npBackgroundEnd, s.npText, s.npTextSecondary, s.accent, s.onAccent)
    }
}

/**
 * Applies the active skin to a view tree. Views opt in through android:tag="skin_…" roles,
 * so any layout can be re-skinned at runtime without recreating it.
 */
object SkinPainter {

    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    fun apply(root: View, skin: Skin = SkinManager.current, np: NpColors = NpColors.of(skin)) {
        val d = root.resources.displayMetrics.density
        paint(root, skin, np, d)
        if (root is ImageView) Icons.applyTo(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) apply(root.getChildAt(i), skin, np)
    }

    private fun tint(v: View, color: Int) {
        if (v is ImageView) Icons.tintFor(v, ColorStateList.valueOf(color))
    }

    /** Swaps in the theme's Google Font, remembering whether the layout asked for bold. */
    private fun font(v: View, skin: Skin) {
        if (v !is TextView) return
        val bold = (v.getTag(R.id.tag_bold) as? Boolean)
            ?: (v.typeface?.isBold == true).also { v.setTag(R.id.tag_bold, it) }
        v.typeface = if (bold) skin.boldTypeface else skin.typeface
    }

    private fun rowStateText(skin: Skin, normal: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(skin.rowHighlightText, normal)
    )

    private fun paint(v: View, skin: Skin, np: NpColors, d: Float) {
        val tag = v.tag as? String ?: return
        if (!tag.startsWith("skin_")) return
        val radius = skin.cornerRadiusDp * d
        when (tag) {
            "skin_bg" -> v.background = backgroundFor(skin, v)
            "skin_bg_inner" -> if (skin.backgroundImage != null || skin.translucent) v.setBackgroundColor(Color.TRANSPARENT) else v.setBackgroundColor(skin.background)
            "skin_surface" -> v.setBackgroundColor(skin.surface)
            "skin_surface_round" -> v.background = GradientDrawable().apply {
                setColor(skin.surface); cornerRadius = radius
            }
            "skin_header" -> v.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skin.header, skin.headerEnd)
            )
            "skin_header_text" -> (v as? TextView)?.let { it.setTextColor(skin.headerText); font(it, skin) }
            "skin_header_icon" -> tint(v, skin.headerText)
            "skin_text" -> (v as? TextView)?.let {
                it.setTextColor(rowStateText(skin, skin.text)); font(it, skin)
                if (it is CompoundButton) CompoundButtonCompat.setButtonTintList(it, ColorStateList.valueOf(skin.accent))
            }
            "skin_text2" -> (v as? TextView)?.let { it.setTextColor(rowStateText(skin, skin.textSecondary)); font(it, skin) }
            "skin_accent_text" -> (v as? TextView)?.let { it.setTextColor(skin.accent); font(it, skin) }
            "skin_icon" -> if (v is ImageView) Icons.tintFor(v, rowStateText(skin, skin.text))
            "skin_accent_icon" -> if (v is ImageView) Icons.tintFor(v, rowStateText(skin, skin.accent))
            "skin_divider" -> v.setBackgroundColor(skin.divider)
            "skin_button" -> (v as? MaterialButton)?.let {
                it.backgroundTintList = ColorStateList.valueOf(skin.accent)
                it.setTextColor(skin.onAccent)
                it.iconTint = ColorStateList.valueOf(skin.onAccent)
                it.cornerRadius = radius.toInt().coerceAtLeast((4 * d).toInt())
                font(it, skin)
            }
            "skin_button_tonal" -> (v as? MaterialButton)?.let {
                it.backgroundTintList = ColorStateList.valueOf(ColorUtils.compositeColors(withAlpha(skin.accent, 0x38), skin.surface))
                it.setTextColor(if (skin.dark) skin.text else skin.accent)
                it.iconTint = ColorStateList.valueOf(skin.accent)
                it.cornerRadius = radius.toInt().coerceAtLeast((4 * d).toInt())
                font(it, skin)
            }
            "skin_slider" -> slider(v, skin.accent, skin.divider)
            "skin_np_slider" -> slider(v, np.accent, withAlpha(np.text, 0x40))
            "skin_progress" -> when (v) {
                is LinearProgressIndicator -> { v.setIndicatorColor(skin.accent); v.trackColor = skin.divider }
                is com.jslw.genz.ui.views.SparkleSeekBar -> {
                    v.setStyle(skin.progressStyle, skin.progressColors)
                    v.setColors(skin.accent, skin.divider)
                }
            }
            "skin_badge" -> (v as? TextView)?.let {
                it.background = GradientDrawable().apply { setColor(np.accent); cornerRadius = 6 * d }
                it.setTextColor(np.onAccent)
            }
            "skin_play" -> {
                v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(np.accent) }
                if (Build.VERSION.SDK_INT >= 21) v.elevation = 6 * d
            }
            "skin_on_accent" -> tint(v, np.onAccent)
            "skin_lcd" -> (v as? TextView)?.let {
                it.visibility = if (skin.lcd) View.VISIBLE else View.GONE
                it.background = GradientDrawable().apply {
                    setColor(skin.lcdBackground); cornerRadius = 4 * d
                    setStroke((1 * d).toInt(), withAlpha(skin.lcdText, 0x55))
                }
                it.setTextColor(skin.lcdText)
                it.typeface = skin.lcdTypeface
            }
            "skin_input" -> (v as? EditText)?.let {
                it.setTextColor(skin.text)
                it.setHintTextColor(skin.textSecondary)
                it.background = GradientDrawable().apply {
                    setColor(skin.surface); cornerRadius = radius.coerceAtLeast(6 * d)
                    setStroke((1 * d).toInt(), skin.divider)
                }
                font(it, skin)
            }
            "skin_switch" -> (v as? SwitchCompat)?.let {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                it.thumbTintList = ColorStateList(states, intArrayOf(skin.accent, if (skin.dark) 0xFFB0B0B0.toInt() else 0xFFF5F5F5.toInt()))
                it.trackTintList = ColorStateList(states, intArrayOf(withAlpha(skin.accent, 0x80), withAlpha(skin.text, 0x40)))
            }
            "skin_row" -> v.background = rowBackground(skin, d)
            "skin_flow_bg" -> v.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skin.flowBackground, skin.flowBackgroundEnd)
            )
            "skin_flow_text" -> (v as? TextView)?.let { it.setTextColor(skin.flowText); font(it, skin) }
            "skin_flow_text2" -> (v as? TextView)?.let { it.setTextColor(withAlpha(skin.flowText, 0xA0)); font(it, skin) }
            "skin_np_text" -> (v as? TextView)?.let { it.setTextColor(np.text); font(it, skin) }
            "skin_np_text2" -> (v as? TextView)?.let { it.setTextColor(np.text2); font(it, skin) }
            "skin_np_icon" -> tint(v, np.text)
        }
    }

    private fun slider(v: View, accent: Int, inactive: Int) {
        val a = ColorStateList.valueOf(accent)
        val i = ColorStateList.valueOf(inactive)
        val halo = ColorStateList.valueOf(withAlpha(accent, 0x30))
        when (v) {
            is Slider -> { v.trackActiveTintList = a; v.trackInactiveTintList = i; v.thumbTintList = a; v.haloTintList = halo }
            is RangeSlider -> { v.trackActiveTintList = a; v.trackInactiveTintList = i; v.thumbTintList = a; v.haloTintList = halo }
        }
    }

    /** Row background: classic gradient highlight bar when pressed, optional hairline divider. */
    fun rowBackground(skin: Skin, d: Float): Drawable {
        if (skin.cards) {
            // dashboard tiles: every row is its own rounded (translucent) card
            val r = skin.cornerRadiusDp * d
            fun card(c: Drawable) = android.graphics.drawable.InsetDrawable(c, (8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), card(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(skin.rowHighlight, skin.rowHighlightEnd)).apply { cornerRadius = r }))
                addState(intArrayOf(android.R.attr.state_activated), card(GradientDrawable().apply {
                    setColor(withAlpha(skin.accent, 0x40)); cornerRadius = r }))
                addState(intArrayOf(), card(GradientDrawable().apply {
                    setColor(skin.surface); cornerRadius = r; setStroke((1 * d).toInt(), skin.divider) }))
                setExitFadeDuration(220)
            }
        }
        val pressed = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skin.rowHighlight, skin.rowHighlightEnd))
        val normal = RowDivider(if (skin.rowDividers) skin.divider else Color.TRANSPARENT, d)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_activated), GradientDrawable().apply { setColor(withAlpha(skin.accent, 0x22)) })
            addState(intArrayOf(), normal)
            setExitFadeDuration(220)
        }
    }

    private var bgCache: Pair<String, Bitmap?>? = null

    /** Solid colour, or the pack's background picture (centre-cropped, dimmed with the background colour). */
    fun backgroundFor(skin: Skin, v: View): Drawable {
        if (skin.translucent) {
            val art = Backdrop.art
            return if (art != null) CropDrawable(art, skin.background)
            else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                androidx.core.graphics.ColorUtils.blendARGB(skin.accent, Color.BLACK, 0.55f),
                skin.background or 0xFF000000.toInt(),
            ))
        }
        val rel = skin.backgroundImage ?: return android.graphics.drawable.ColorDrawable(skin.background)
        val key = skin.id + rel
        val bmp = if (bgCache?.first == key) bgCache?.second else {
            val f = skin.packFile(rel)
            val dm = v.resources.displayMetrics
            val b = f?.let { com.jslw.genz.util.ArtLoader.decodeBytes(it.readBytes(), maxOf(dm.widthPixels, dm.heightPixels) / 2) }
            bgCache = key to b
            b
        } ?: return android.graphics.drawable.ColorDrawable(skin.background)
        return CropDrawable(bmp, withAlpha(skin.background, 0xB0))
    }

    private class CropDrawable(private val bmp: Bitmap, private val overlay: Int) : Drawable() {
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)
        private val src = android.graphics.Rect()
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val scale = maxOf(b.width().toFloat() / bmp.width, b.height().toFloat() / bmp.height)
            val w = (b.width() / scale).toInt(); val h = (b.height() / scale).toInt()
            src.set((bmp.width - w) / 2, (bmp.height - h) / 2, (bmp.width + w) / 2, (bmp.height + h) / 2)
            canvas.drawBitmap(bmp, src, b, p)
            canvas.drawColor(overlay)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    fun styleChip(chip: Chip, skin: Skin = SkinManager.current) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        chip.chipBackgroundColor = ColorStateList(states, intArrayOf(skin.accent, skin.surface))
        chip.setTextColor(ColorStateList(states, intArrayOf(skin.onAccent, skin.text)))
        chip.chipStrokeColor = ColorStateList.valueOf(skin.divider)
        chip.chipStrokeWidth = chip.resources.displayMetrics.density
        chip.isCheckedIconVisible = false
        chip.typeface = skin.typeface
    }

    private class RowDivider(private val color: Int, private val d: Float) : Drawable() {
        private val p = Paint().apply { this.color = this@RowDivider.color }
        override fun draw(canvas: Canvas) {
            if (Color.alpha(color) == 0) return
            val b = bounds
            canvas.drawRect(b.left + 14 * d, b.bottom - d, b.right.toFloat(), b.bottom.toFloat(), p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
