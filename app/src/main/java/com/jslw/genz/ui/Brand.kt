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
        v.findViewById<TextView>(R.id.brandCopyright).text = ctx.getString(R.string.copyright) + "\nwww.jslworks.in"
        SkinPainter.apply(v)
        return v
    }

    const val SITE = "https://www.jslworks.in/"
    const val UPDATES = "https://www.jslworks.in/android-devlopment"
    const val PROJECT = "https://github.com/founderjslworks/jslw-genz-music-player"
    const val RELEASES = "https://github.com/founderjslworks/jslw-genz-music-player/releases/latest"

    /** Opens a link in the user's browser (GEN-Z itself never uses the internet). */
    fun open(a: Activity, url: String) {
        try {
            a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(a, url, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun shareText() = "🎧 GEN-Z Music ${BuildConfig.VERSION_NAME} is here!\n$PROJECT\n\n" +
        "An offline music player for Android 4.4 – 16: 34 themes, 10-band EQ, Hi-Res & DSD, lyrics, widgets – no ads, no tracking.\n\n" +
        "⬇️ Download the app + user guide:\n$RELEASES\n\nBy Partho Ghosh · JSL WORKS PVT LTD · www.jslworks.in"

    fun share(a: Activity) {
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "GEN-Z Music")
            .putExtra(android.content.Intent.EXTRA_TEXT, shareText())
        try { a.startActivity(android.content.Intent.createChooser(i, "Share GEN-Z Music")) } catch (_: Exception) {}
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
        col.addView(TextView(a).apply {
            text = "🌐  www.jslworks.in\n⬇️  www.jslworks.in/android-devlopment"
            textSize = 13f
            setPadding(pad, 0, pad, pad / 2)
            tag = "skin_accent_text"
            setOnClickListener { open(a, UPDATES) }
        })
        SkinPainter.apply(col)
        MaterialAlertDialogBuilder(a).setView(android.widget.ScrollView(a).apply { addView(col) })
            .setNeutralButton("Share") { _, _ -> share(a) }
            .setNegativeButton("Website") { _, _ -> open(a, UPDATES) }
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
