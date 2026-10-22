package com.jslw.genz.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jslw.genz.R
import com.jslw.genz.skin.Skin
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.util.toast

/** Pick a theme, import / update skin packs, export packs, or open the Theme Editor. */
class ThemesActivity : BaseActivity() {
    private lateinit var list: RecyclerView
    private var exporting: Skin? = null

    private val importPack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        Thread {
            val res = try { Result.success(SkinManager.import(uri)) } catch (e: Exception) { Result.failure<Skin>(e) }
            runOnUiThread {
                res.onSuccess { s ->
                    MaterialAlertDialogBuilder(this).setTitle("Installed \"${s.name}\"")
                        .setMessage("Version ${s.version}" + (if (s.author.isNotBlank()) " · by ${s.author}" else "") + "\nApply it now?")
                        .setPositiveButton("Apply") { _, _ -> apply(s) }
                        .setNegativeButton("Later") { _, _ -> list.adapter?.notifyDataSetChanged() }
                        .show()
                }.onFailure { e -> toast(e.message ?: "Couldn't import that file") }
                list.adapter?.notifyDataSetChanged()
            }
        }.start()
    }

    private val exportPack = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val s = exporting ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { SkinManager.exportPack(s, it) }
            toast("Exported \"${s.name}\" – edit theme.json / add fonts, icons, images, then import it back")
        } catch (e: Exception) { toast("Export failed: ${e.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_list)
        setupScreen(findViewById(R.id.root))
        header("Themes & skin packs")
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()
        findViewById<View>(R.id.footer).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.footerBtn1).apply {
            text = "Import pack"
            setOnClickListener { importPack.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain", "*/*")) }
        }
        findViewById<MaterialButton>(R.id.footerBtn2).apply {
            text = "Create theme"
            setOnClickListener { startActivity(Intent(this@ThemesActivity, ThemeEditorActivity::class.java).putExtra("base", skin.id)) }
        }
    }

    override fun onResume() {
        super.onResume()
        list.adapter?.notifyDataSetChanged()
    }

    /** Applies a theme with a soft cross-fade instead of a hard flash. */
    private fun apply(s: Skin) {
        SkinManager.select(s.id)
        startActivity(Intent(this, ThemesActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun menu(anchor: View, s: Skin) {
        val m = PopupMenu(this, anchor)
        m.menu.add("Apply")
        m.menu.add("Edit a copy…")
        if (!s.builtIn) m.menu.add("Edit…")
        m.menu.add("Export as skin pack (.zip)")
        if (!s.builtIn) m.menu.add("Delete")
        m.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Apply" -> apply(s)
                "Edit a copy…" -> startActivity(Intent(this, ThemeEditorActivity::class.java).putExtra("base", s.id).putExtra("copy", true))
                "Edit…" -> startActivity(Intent(this, ThemeEditorActivity::class.java).putExtra("base", s.id))
                "Delete" -> MaterialAlertDialogBuilder(this).setTitle("Delete \"${s.name}\"?")
                    .setPositiveButton("Delete") { _, _ -> SkinManager.delete(s); if (skin.id != SkinManager.current.id) apply(SkinManager.current) else list.adapter?.notifyDataSetChanged() }
                    .setNegativeButton("Cancel", null).show()
                else -> { exporting = s; exportPack.launch("${s.id}.genzskin.zip") }
            }
            true
        }
        m.show()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val items: List<Skin> get() = SkinManager.all()
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_theme, parent, false)
            SkinPainter.apply(v)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val s = items[position]
            val v = h.itemView
            val d = resources.displayMetrics.density
            v.findViewById<View>(R.id.themePreview).background = GradientDrawable().apply {
                setColor(s.background); cornerRadius = 8 * d; setStroke((1 * d).toInt(), s.divider)
            }
            v.findViewById<View>(R.id.themeSw1).background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(s.header, s.headerEnd)).apply {
                cornerRadii = floatArrayOf(8 * d, 8 * d, 8 * d, 8 * d, 0f, 0f, 0f, 0f)
            }
            v.findViewById<View>(R.id.themeSw3).background = GradientDrawable().apply { setColor(s.text); cornerRadius = 3 * d }
            v.findViewById<View>(R.id.themeSw4).background = GradientDrawable().apply { setColor(s.accent); cornerRadius = 3 * d }
            v.findViewById<TextView>(R.id.themeName).apply { text = s.name; typeface = s.boldTypeface }
            v.findViewById<TextView>(R.id.themeAuthor).text = listOfNotNull(
                if (s.builtIn) "Built-in" else if (s.packDir != null) "Skin pack" else "Theme file",
                com.jslw.genz.ui.views.SparkleSeekBar.LABELS[s.progressStyle],
                s.author.takeIf { it.isNotBlank() }?.let { "by $it" },
                "v${s.version}",
            ).joinToString(" · ")
            v.findViewById<ImageView>(R.id.themeCheck).visibility = if (s.id == SkinManager.current.id) View.VISIBLE else View.INVISIBLE
            v.setOnClickListener { if (s.id != SkinManager.current.id) apply(s) }
            v.findViewById<View>(R.id.themeMenu).setOnClickListener { b -> menu(b, s) }
        }
    }
}
