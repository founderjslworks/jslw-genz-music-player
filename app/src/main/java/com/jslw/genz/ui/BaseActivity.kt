package com.jslw.genz.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jslw.genz.R
import com.jslw.genz.skin.Skin
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.util.SystemBars

/** Applies the active theme file, edge-to-edge insets and the classic header to every screen. */
abstract class BaseActivity : AppCompatActivity() {
    protected val skin: Skin get() = SkinManager.current
    private var appliedSkin = ""

    protected open fun themeRes(): Int = if (skin.dark) R.style.Theme_GenZ_Dark else R.style.Theme_GenZ_Light

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(themeRes())
        super.onCreate(savedInstanceState)
        appliedSkin = skin.id + "#" + SkinManager.generation
        // fluid: highest panel refresh rate (90/120/144 Hz); vivid: wide colour gamut where the screen has it
        com.jslw.genz.util.Smooth.preferHighRefreshRate(this)
        if (Build.VERSION.SDK_INT >= 26 && resources.configuration.isScreenWideColorGamut) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
    }

    override fun onResume() {
        super.onResume()
        if (appliedSkin != SkinManager.current.id + "#" + SkinManager.generation) recreate()
    }

    /**
     * Skins the layout and handles system bars. The header (if any) grows under the status bar so the
     * status bar takes the header's colour; the rest of the root is padded for the gesture-nav bar.
     */
    protected fun setupScreen(root: View, header: View? = root.findViewById(R.id.hdr) ?: root.findViewById(R.id.header)) {
        SkinPainter.apply(root)
        IndiaBadge.attach(root)
        if (Build.VERSION.SDK_INT >= 21) {
            SystemBars.setup(this, root, skin.header, skin.background)
            if (header != null) {
                val base = header.layoutParams.height
                ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                    val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    v.setPadding(b.left, 0, b.right, b.bottom)
                    header.setPadding(header.paddingLeft, b.top, header.paddingRight, header.paddingBottom)
                    if (base > 0) header.layoutParams = header.layoutParams.apply { height = base + b.top }
                    insets
                }
                ViewCompat.requestApplyInsets(root)
            }
        } else {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(skin.background))
        }
    }

    /** Sets up the shared view_header: title, back arrow, optional action. */
    protected fun header(title: String, actionIcon: Int = 0, onAction: (() -> Unit)? = null) {
        findViewById<TextView>(R.id.hdrTitle)?.text = title
        findViewById<ImageButton>(R.id.hdrBack)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val a = findViewById<ImageButton>(R.id.hdrAction) ?: return
        if (actionIcon != 0 && onAction != null) {
            com.jslw.genz.skin.Icons.set(a, actionIcon)
            a.visibility = View.VISIBLE
            a.setOnClickListener { onAction() }
        }
    }

    /** Every screen closes with the chosen transition (screens with their own exit override this afterwards). */
    override fun finish() {
        super.finish()
        if (this !is MainActivity) com.jslw.genz.util.Transitions.close(this)
    }

    fun reskin() = SkinPainter.apply(findViewById<ViewGroup>(android.R.id.content))
}
