package com.jslw.genz.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jslw.genz.BuildConfig
import com.jslw.genz.R
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter

/** JSL WORKS branding: logo card, credits, copyright, About dialog and the launch splash. */
object Brand {

    /** Inflates and skins the brand block (logo + credits + copyright). */
    fun view(parent: ViewGroup, compact: Boolean = false): View {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.view_brand, parent, false)
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        // The logo has dark text, so it always sits on a soft white card – readable on every theme.
        v.findViewById<View>(R.id.brandCard).background = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = 20 * d
            setStroke((1 * d).toInt(), SkinPainter.withAlpha(SkinManager.current.accent, 0x55))
        }
        if (compact) {
            val lp = v.findViewById<ImageView>(R.id.brandLogo).layoutParams
            lp.width = (lp.width * 0.7f).toInt(); lp.height = (lp.height * 0.7f).toInt()
        }
        v.findViewById<TextView>(R.id.brandTitle).text = "GEN-Z Music ${BuildConfig.VERSION_NAME}"
        v.findViewById<TextView>(R.id.brandCredit).text =
            "Designed & developed by ${ctx.getString(R.string.developer)}\n${ctx.getString(R.string.company)}"
        SkinPainter.apply(v)
        return v
    }

    fun about(a: Activity) {
        val pad = (24 * a.resources.displayMetrics.density).toInt()
        val col = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL }
        col.addView(view(col))
        col.addView(TextView(a).apply {
            text = a.getString(R.string.code_name) + "\n\nThis software, its themes, graphics and code are the property of " +
                a.getString(R.string.company) + ". Unauthorised copying, modification or distribution is prohibited."
            textSize = 12f
            setPadding(pad, 0, pad, pad / 2)
            tag = "skin_text2"
        })
        SkinPainter.apply(col)
        MaterialAlertDialogBuilder(a).setView(android.widget.ScrollView(a).apply { addView(col) })
            .setPositiveButton("OK", null).show()
    }

    /** Branded splash on cold start: the logo pops in, then the whole overlay fades away (~1 s). */
    fun splash(a: Activity) {
        val root = a.findViewById<ViewGroup>(android.R.id.content)
        val overlay = FrameLayout(a).apply {
            setBackgroundColor(SkinManager.current.background)
            isClickable = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val block = view(overlay)
        block.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER)
        overlay.addView(block)
        root.addView(overlay)
        val card = block.findViewById<View>(R.id.brandCard)
        card.scaleX = 0.6f; card.scaleY = 0.6f; card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520).setInterpolator(OvershootInterpolator(1.4f)).start()
        overlay.postDelayed({
            overlay.animate().alpha(0f).setDuration(320).setInterpolator(DecelerateInterpolator())
                .withEndAction { root.removeView(overlay) }.start()
        }, 950)
    }

    /** Footer for the home menu list. */
    class FooterAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1
        override fun getItemViewType(position: Int) = 42
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(view(parent, compact = true)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            holder.itemView.setOnClickListener { v -> (v.context as? Activity)?.let { about(it) } }
        }
    }
}
