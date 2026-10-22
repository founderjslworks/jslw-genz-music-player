package com.jslw.genz.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.jslw.genz.R
import com.jslw.genz.skin.Fonts
import com.jslw.genz.skin.Skin
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.util.dpi
import com.jslw.genz.util.toast
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Create or modify a theme on the phone: colours, Google Font, LCD font, corners and retro options.
 * The preview updates live. Saved themes appear in Themes and can be exported as skin packs.
 */
class ThemeEditorActivity : BaseActivity() {
    private lateinit var o: JSONObject
    private lateinit var colors: JSONObject
    private var base: Skin? = null
    private lateinit var adapter: Adapter
    private lateinit var root: View

    private val COLOR_KEYS = listOf(
        "background" to "Background", "surface" to "Cards & mini player",
        "header" to "Header – top", "headerEnd" to "Header – bottom", "headerText" to "Header text",
        "text" to "Text", "textSecondary" to "Secondary text", "accent" to "Accent", "onAccent" to "Text on accent",
        "divider" to "Dividers", "rowHighlight" to "Selection – top", "rowHighlightEnd" to "Selection – bottom",
        "rowHighlightText" to "Selected text", "npBackground" to "Now Playing – top", "npBackgroundEnd" to "Now Playing – bottom",
        "npText" to "Now Playing text", "npTextSecondary" to "Now Playing secondary", "lcdBackground" to "LCD background",
        "lcdText" to "LCD text", "flowBackground" to "Album Flow – top", "flowBackgroundEnd" to "Album Flow – bottom",
        "flowText" to "Album Flow text",
    )

    private val SWATCHES = intArrayOf(
        0xFFFF3D9A.toInt(), 0xFFF107A3.toInt(), 0xFF7B2FF7.toInt(), 0xFF5856D6.toInt(), 0xFF007AFF.toInt(), 0xFF22D3EE.toInt(),
        0xFF00C7BE.toInt(), 0xFF34C759.toInt(), 0xFF33FF66.toInt(), 0xFFFFCC00.toInt(), 0xFFFF9500.toInt(), 0xFFF28C28.toInt(),
        0xFFFF3B30.toInt(), 0xFFD9A441.toInt(), 0xFF8E8E93.toInt(), 0xFFFFFFFF.toInt(), 0xFFF2F3F5.toInt(), 0xFFC8CDD4.toInt(),
        0xFF2A2A36.toInt(), 0xFF16142B.toInt(), 0xFF121218.toInt(), 0xFF0B0A16.toInt(), 0xFF2A1E17.toInt(), 0xFF000000.toInt(),
    )

    private sealed class Item {
        class Section(val t: String) : Item()
        class Color(val key: String, val label: String) : Item()
        class Toggle(val key: String, val label: String, val def: Boolean) : Item()
        class Choice(val label: String, val run: () -> Unit, val value: () -> String) : Item()
    }

    private val items = ArrayList<Item>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_list)
        root = findViewById(R.id.root)
        val baseId = intent.getStringExtra("base") ?: skin.id
        base = SkinManager.all().firstOrNull { it.id == baseId } ?: skin
        val b = base!!
        o = JSONObject(b.json)
        colors = o.optJSONObject("colors") ?: JSONObject().also { o.put("colors", it) }
        // fill in every colour so the editor shows real values even for sparse theme files
        val resolved = mapOf(
            "background" to b.background, "surface" to b.surface, "header" to b.header, "headerEnd" to b.headerEnd,
            "headerText" to b.headerText, "text" to b.text, "textSecondary" to b.textSecondary, "accent" to b.accent,
            "onAccent" to b.onAccent, "divider" to b.divider, "rowHighlight" to b.rowHighlight, "rowHighlightEnd" to b.rowHighlightEnd,
            "rowHighlightText" to b.rowHighlightText, "npBackground" to b.npBackground, "npBackgroundEnd" to b.npBackgroundEnd,
            "npText" to b.npText, "npTextSecondary" to b.npTextSecondary, "lcdBackground" to b.lcdBackground, "lcdText" to b.lcdText,
            "flowBackground" to b.flowBackground, "flowBackgroundEnd" to b.flowBackgroundEnd, "flowText" to b.flowText,
        )
        for ((k, v) in resolved) if (!colors.has(k)) colors.put(k, hex(v))
        if (b.builtIn || intent.getBooleanExtra("copy", false)) {
            o.put("name", "My " + b.name)
            o.put("id", "my_" + b.id.removePrefix("user_") + "_" + (System.currentTimeMillis() / 1000 % 100000))
            o.put("version", 1)
        } else o.put("version", b.version + 1)
        o.put("author", o.optString("author").ifBlank { "Me" })

        buildItems()
        setupScreen(root)
        header("Theme editor", R.drawable.ic_check) { save() }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = Adapter()
        list.adapter = adapter
        findViewById<View>(R.id.footer).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.footerBtn1).apply { text = "Save theme"; setOnClickListener { save() } }
        findViewById<MaterialButton>(R.id.footerBtn2).apply { text = "Rename"; setOnClickListener { rename() } }
        preview()
    }

    private fun hex(c: Int) = String.format(Locale.US, "#%06X", c and 0xFFFFFF).let { if (Color.alpha(c) != 255) String.format(Locale.US, "#%08X", c) else it }

    private fun buildItems() {
        items.clear()
        items += Item.Section("Theme")
        items += Item.Choice("Name", { rename() }) { o.optString("name") }
        items += Item.Toggle("dark", "Dark theme (dialogs & system bars)", true)
        items += Item.Choice("Body font (Google Fonts)", { pickFont("font") }) { o.optString("font", "inter") }
        items += Item.Choice("Heading font", { pickFont("displayFont") }) { o.optString("displayFont", "").ifBlank { o.optString("font", "inter") } }
        items += Item.Choice("Progress bar style", { pickProgress() }) {
            com.jslw.genz.ui.views.SparkleSeekBar.LABELS[o.optString("progressStyle", "sparkle")] ?: "Sparkle stars"
        }
        items += Item.Choice("LCD / display font", { pickFont("lcdFont") }) { o.optString("lcdFont", "vt323") }
        items += Item.Choice("Corner roundness", { pickRadius() }) { "${o.optDouble("cornerRadius", 10.0).toInt()} dp" }
        items += Item.Section("Style")
        items += Item.Toggle("translucent", "Translucent glass (blurred cover behind panels)", false)
        items += Item.Toggle("cards", "Rows as rounded cards (dashboard look)", false)
        items += Item.Toggle("colorfulIcons", "Colourful icon tiles", false)
        items += Item.Toggle("lcd", "Retro LCD strip on Now Playing", false)
        items += Item.Toggle("npDynamicColors", "Now Playing follows album-art colours", false)
        items += Item.Toggle("reflection", "Album Flow reflections", true)
        items += Item.Toggle("chevrons", "Classic › arrows in lists", true)
        items += Item.Toggle("rowDividers", "Divider lines between rows", false)
        items += Item.Toggle("tintIcons", "Tint pack icons with theme colours", true)
        items += Item.Section("Colours")
        COLOR_KEYS.forEach { (k, l) -> items += Item.Color(k, l) }
    }

    private fun current(): Skin = Skin.parse(o.toString(), false, o.optString("id"), base?.packDir)

    /** Live preview: re-skin this screen with the theme being edited. */
    private fun preview() {
        val s = current()
        SkinPainter.apply(root, s)
        findViewById<RecyclerView>(R.id.list).let { rv -> for (i in 0 until rv.childCount) SkinPainter.apply(rv.getChildAt(i), s) }
    }

    private fun changed() {
        adapter.notifyDataSetChanged()
        root.post { preview() }
    }

    private fun rename() = Sheets.askText(this, "Theme name", o.optString("name"), "OK") { o.put("name", it); changed() }

    private fun pickFont(key: String) {
        val packFonts = base?.packDir?.let { d -> File(d, "fonts").listFiles()?.map { "fonts/" + it.name } } ?: emptyList()
        val all = Fonts.names + packFonts
        MaterialAlertDialogBuilder(this).setTitle("Font").setSingleChoiceItems(all.toTypedArray(), all.indexOf(o.optString(key))) { d, w ->
            o.put(key, all[w]); changed(); d.dismiss()
        }.show()
    }

    private fun pickProgress() {
        val keys = com.jslw.genz.ui.views.SparkleSeekBar.STYLES
        val labels = keys.map { com.jslw.genz.ui.views.SparkleSeekBar.LABELS[it] ?: it }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Progress bar style")
            .setSingleChoiceItems(labels, keys.indexOf(o.optString("progressStyle", "sparkle"))) { dlg, w ->
                o.put("progressStyle", keys[w]); changed(); dlg.dismiss()
            }.show()
    }

    private fun pickRadius() {
        val v = listOf(0, 2, 4, 6, 8, 10, 12, 16, 20, 24)
        MaterialAlertDialogBuilder(this).setTitle("Corner roundness").setSingleChoiceItems(v.map { "$it dp" }.toTypedArray(),
            v.indexOf(o.optDouble("cornerRadius", 10.0).toInt())) { d, w -> o.put("cornerRadius", v[w]); changed(); d.dismiss() }.show()
    }

    /** Swatch grid + hex field + brightness slider. */
    private fun pickColor(key: String, label: String) {
        var c = try { Color.parseColor(colors.optString(key, "#FFFFFF")) } catch (e: Exception) { Color.WHITE }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20), dpi(8), dpi(20), 0) }
        val swatch = View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(44)) }
        val hexField = EditText(this).apply { setSingleLine(); setText(hex(c)); gravity = Gravity.CENTER }
        val light = Slider(this).apply { valueFrom = -1f; valueTo = 1f; value = 0f }
        var baseColor = c
        fun show(fromText: Boolean) {
            swatch.background = GradientDrawable().apply { setColor(c); cornerRadius = dpi(10).toFloat(); setStroke(dpi(1), 0x55888888) }
            if (!fromText) hexField.setText(hex(c))
        }
        show(false)
        box.addView(swatch)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(10), 0, dpi(4)) }
        SWATCHES.toList().chunked(6).forEach { rowColors ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowColors.forEach { sc ->
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dpi(36), 1f).apply { setMargins(dpi(3), dpi(3), dpi(3), dpi(3)) }
                    background = GradientDrawable().apply { setColor(sc); cornerRadius = dpi(8).toFloat(); setStroke(dpi(1), 0x33888888) }
                    setOnClickListener { c = sc; baseColor = sc; light.value = 0f; show(false) }
                })
            }
            grid.addView(row)
        }
        box.addView(grid)
        box.addView(TextView(this).apply { text = "Darker  ←  →  Lighter"; gravity = Gravity.CENTER })
        light.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            c = if (v >= 0) androidx.core.graphics.ColorUtils.blendARGB(baseColor, Color.WHITE, v)
            else androidx.core.graphics.ColorUtils.blendARGB(baseColor, Color.BLACK, -v)
            show(false)
        }
        box.addView(light)
        box.addView(hexField)
        hexField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (!hexField.hasFocus()) return
                try { c = Color.parseColor(e.toString().trim()); baseColor = c; show(true) } catch (_: Exception) {}
            }
        })
        MaterialAlertDialogBuilder(this).setTitle(label).setView(box)
            .setPositiveButton("Use") { _, _ -> colors.put(key, hex(c)); changed() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun save() {
        try {
            val s = SkinManager.saveUserTheme(o.toString(2), base?.packDir)
            SkinManager.select(s.id)
            toast("Saved and applied \"${s.name}\"")
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } catch (e: Exception) {
            toast(e.message ?: "Couldn't save")
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is Item.Section) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(if (viewType == 1) R.layout.item_section else R.layout.item_setting, parent, false)
            SkinPainter.apply(v, current())
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            when (val it = items[position]) {
                is Item.Section -> (v as TextView).text = it.t
                is Item.Toggle -> {
                    v.findViewById<TextView>(R.id.setTitle).apply { text = it.label; setCompoundDrawables(null, null, null, null) }
                    v.findViewById<TextView>(R.id.setSummary).visibility = View.GONE
                    val sw = v.findViewById<SwitchCompat>(R.id.setSwitch)
                    sw.visibility = View.VISIBLE
                    sw.isChecked = o.optBoolean(it.key, it.def)
                    v.setOnClickListener { _ -> o.put(it.key, !o.optBoolean(it.key, it.def)); changed() }
                }
                is Item.Choice -> {
                    v.findViewById<TextView>(R.id.setTitle).apply { text = it.label; setCompoundDrawables(null, null, null, null) }
                    v.findViewById<TextView>(R.id.setSummary).apply { visibility = View.VISIBLE; text = it.value() }
                    v.findViewById<SwitchCompat>(R.id.setSwitch).visibility = View.GONE
                    v.setOnClickListener { _ -> it.run() }
                }
                is Item.Color -> {
                    val value = colors.optString(it.key)
                    val c = try { Color.parseColor(value) } catch (e: Exception) { Color.GRAY }
                    val chip = GradientDrawable().apply {
                        setColor(c); cornerRadius = dpi(6).toFloat(); setStroke(dpi(1), 0x55888888); setSize(dpi(30), dpi(30))
                    }
                    chip.setBounds(0, 0, dpi(30), dpi(30))
                    v.findViewById<TextView>(R.id.setTitle).apply {
                        text = it.label
                        setCompoundDrawables(null, null, chip, null)
                        compoundDrawablePadding = dpi(12)
                    }
                    v.findViewById<TextView>(R.id.setSummary).apply { visibility = View.VISIBLE; text = value.uppercase(Locale.US) }
                    v.findViewById<SwitchCompat>(R.id.setSwitch).visibility = View.GONE
                    v.setOnClickListener { _ -> pickColor(it.key, it.label) }
                }
            }
            SkinPainter.apply(v, current())
        }
    }
}
