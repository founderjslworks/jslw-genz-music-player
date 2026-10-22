package com.jslw.genz.skin

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A theme ("skin") is a small JSON file. Built-in skins live in assets/skins, imported ones in
 * files/skins. The full format is documented in THEMES.md in the project root.
 */
data class Skin(
    val id: String,
    val name: String,
    val author: String,
    val dark: Boolean,
    val builtIn: Boolean,
    val background: Int,
    val surface: Int,
    val header: Int,
    val headerEnd: Int,
    val headerText: Int,
    val text: Int,
    val textSecondary: Int,
    val accent: Int,
    val onAccent: Int,
    val divider: Int,
    val rowHighlight: Int,
    val rowHighlightEnd: Int,
    val rowHighlightText: Int,
    val npBackground: Int,
    val npBackgroundEnd: Int,
    val npText: Int,
    val npTextSecondary: Int,
    val lcdBackground: Int,
    val lcdText: Int,
    val flowBackground: Int,
    val flowBackgroundEnd: Int,
    val flowText: Int,
    val cornerRadiusDp: Float,
    val font: String,
    val lcdFont: String,
    val colorfulIcons: Boolean,
    val lcd: Boolean,
    val npDynamicColors: Boolean,
    val reflection: Boolean,
    val chevrons: Boolean,
    val rowDividers: Boolean,
    /** Tint icon-pack PNGs with theme colours (false keeps full-colour icons). */
    val tintIcons: Boolean,
    /** Optional background picture inside the pack, e.g. "images/bg.jpg". */
    val backgroundImage: String?,
    val version: Int,
    /** Folder of an installed skin pack (fonts/, icons/, images/), null for built-ins. */
    val packDir: String?,
    val json: String,
) {
    fun packFile(rel: String): File? = packDir?.let { d ->
        val f = File(d, rel)
        if (f.canonicalPath.startsWith(File(d).canonicalPath) && f.exists()) f else null
    }

    val typeface: Typeface get() = Fonts.forSkin(this, font, false)
    val boldTypeface: Typeface get() = Fonts.forSkin(this, font, true)
    val lcdTypeface: Typeface get() = Fonts.forSkin(this, lcdFont, false)

    companion object {
        /** Parses a theme file. Missing colours fall back to sensible values derived from the others. */
        fun parse(json: String, builtIn: Boolean, fallbackId: String, packDir: String? = null): Skin {
            val o = JSONObject(json)
            val c = o.optJSONObject("colors") ?: JSONObject()
            val dark = o.optBoolean("dark", true)
            fun col(k: String, d: Int): Int = try {
                if (c.has(k)) Color.parseColor(c.getString(k)) else d
            } catch (e: Exception) { d }

            val bg = col("background", if (dark) 0xFF121218.toInt() else 0xFFFBFAFF.toInt())
            val text = col("text", if (dark) Color.WHITE else 0xFF1A1726.toInt())
            val text2 = col("textSecondary", (text and 0x00FFFFFF) or 0x99000000.toInt())
            val accent = col("accent", 0xFF8E7BFF.toInt())
            val surface = col("surface", bg)
            val header = col("header", bg)
            val rowHi = col("rowHighlight", (accent and 0x00FFFFFF) or 0x33000000)
            val np = col("npBackground", surface)
            return Skin(
                id = o.optString("id", fallbackId).ifBlank { fallbackId },
                name = o.optString("name", fallbackId),
                author = o.optString("author", ""),
                dark = dark,
                builtIn = builtIn,
                background = bg,
                surface = surface,
                header = header,
                headerEnd = col("headerEnd", header),
                headerText = col("headerText", text),
                text = text,
                textSecondary = text2,
                accent = accent,
                onAccent = col("onAccent", if (dark) Color.BLACK else Color.WHITE),
                divider = col("divider", (text and 0x00FFFFFF) or 0x22000000),
                rowHighlight = rowHi,
                rowHighlightEnd = col("rowHighlightEnd", rowHi),
                rowHighlightText = col("rowHighlightText", text),
                npBackground = np,
                npBackgroundEnd = col("npBackgroundEnd", bg),
                npText = col("npText", text),
                npTextSecondary = col("npTextSecondary", text2),
                lcdBackground = col("lcdBackground", surface),
                lcdText = col("lcdText", accent),
                flowBackground = col("flowBackground", Color.BLACK),
                flowBackgroundEnd = col("flowBackgroundEnd", 0xFF1A1A1A.toInt()),
                flowText = col("flowText", Color.WHITE),
                cornerRadiusDp = o.optDouble("cornerRadius", 10.0).toFloat(),
                font = o.optString("font", "inter"),
                lcdFont = o.optString("lcdFont", "vt323"),
                colorfulIcons = o.optBoolean("colorfulIcons", false),
                lcd = o.optBoolean("lcd", false),
                npDynamicColors = o.optBoolean("npDynamicColors", false),
                reflection = o.optBoolean("reflection", true),
                chevrons = o.optBoolean("chevrons", true),
                rowDividers = o.optBoolean("rowDividers", false),
                tintIcons = o.optBoolean("tintIcons", true),
                backgroundImage = o.optString("backgroundImage", "").ifBlank { null },
                version = o.optInt("version", 1),
                packDir = packDir,
                json = json,
            )
        }
    }
}

/**
 * Loads, lists, imports, updates and activates themes / skin packs. 100% offline: packs are files the
 * user imports manually. A pack is either a single theme .json, or a .zip with theme.json plus optional
 * fonts/ (.ttf/.otf), icons/ (.png named after icons, e.g. play.png) and images/ (background).
 * Importing a pack whose id already exists replaces it when its "version" is the same or newer.
 */
object SkinManager {
    private lateinit var app: Context
    private var cache: List<Skin>? = null
    lateinit var current: Skin
        private set
    /** Bumped whenever the active skin's look changes, so screens know to re-skin. */
    var generation = 0
        private set

    fun init(ctx: Context) {
        app = ctx.applicationContext
        current = all().firstOrNull { it.id == com.jslw.genz.data.Prefs.skinId } ?: all().first()
    }

    private fun root() = File(app.filesDir, "skins").apply { mkdirs() }

    fun all(): List<Skin> {
        cache?.let { return it }
        val out = ArrayList<Skin>()
        val order = listOf("vivid_genz", "modern_dark", "modern_light", "pure_black", "ipod_classic", "winamp_classic",
            "walkman_80s", "vinyl_hifi", "aqua_2002", "terminal_green")
        val assets = app.assets.list("skins")?.toList() ?: emptyList()
        for (f in assets.sortedBy { order.indexOf(it.removeSuffix(".json")).let { i -> if (i < 0) 99 else i } }) {
            try {
                val txt = app.assets.open("skins/$f").bufferedReader().use { it.readText() }
                out += Skin.parse(txt, true, f.removeSuffix(".json"))
            } catch (_: Exception) {}
        }
        root().listFiles()?.sortedBy { it.name }?.forEach { f ->
            try {
                val s = when {
                    f.isDirectory -> File(f, "theme.json").takeIf { it.exists() }?.let {
                        Skin.parse(it.readText(), false, f.name, f.absolutePath)
                    }
                    f.name.endsWith(".json") -> Skin.parse(f.readText(), false, f.nameWithoutExtension)
                    else -> null
                } ?: return@forEach
                out += if (out.any { it.id == s.id }) s.copy(id = "user_" + s.id) else s
            } catch (_: Exception) {}
        }
        cache = out
        return out
    }

    fun select(id: String) {
        val s = all().firstOrNull { it.id == id } ?: return
        current = s
        generation++
        Fonts.clearPackFonts()
        Icons.clear()
        com.jslw.genz.data.Prefs.skinId = id
        com.jslw.genz.widget.Widgets.refresh(app)
    }

    private fun safeId(raw: String): String {
        var id = raw.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(48).ifBlank { "theme" }
        if (all().any { it.builtIn && it.id == id }) id = "user_$id"
        return id
    }

    /**
     * Imports (or updates) a theme .json or a skin-pack .zip. Throws IllegalArgumentException with a
     * readable message when the file isn't a usable theme.
     */
    fun import(uri: Uri): Skin {
        val cr = app.contentResolver
        val head = cr.openInputStream(uri)?.use { ins -> ByteArray(4).also { ins.read(it) } }
            ?: throw IllegalArgumentException("Couldn't read the file")
        val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        val result = if (isZip) importZip(uri) else {
            val txt = cr.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            if (txt.length > 300_000) throw IllegalArgumentException("File is too large to be a theme")
            val parsed = parseOrThrow(txt)
            val id = safeId(parsed.id)
            checkVersion(id, parsed.version)
            File(root(), id).deleteRecursively()
            File(root(), "$id.json").writeText(txt)
            id
        }
        cache = null
        if (current.id == result) select(result)
        return all().first { it.id == result || it.id == "user_$result" }
    }

    private fun parseOrThrow(txt: String): Skin = try { Skin.parse(txt, false, "imported") } catch (e: Exception) {
        throw IllegalArgumentException("Not a valid GEN-Z theme (JSON error: ${e.message})")
    }

    /** Refuse to downgrade an installed pack. */
    private fun checkVersion(id: String, version: Int) {
        val old = all().firstOrNull { !it.builtIn && (it.id == id) } ?: return
        if (version < old.version) throw IllegalArgumentException(
            "\"${old.name}\" v${old.version} is already installed – this file is older (v$version)."
        )
    }

    private val ALLOWED = Regex("^(theme\\.json|fonts/[^/]+\\.(ttf|otf)|icons/[^/]+\\.(png|webp|jpg)|images/[^/]+\\.(png|jpg|jpeg|webp))$", RegexOption.IGNORE_CASE)

    private fun importZip(uri: Uri): String {
        val tmp = File(app.cacheDir, "pack_import").apply { deleteRecursively(); mkdirs() }
        var total = 0L
        app.contentResolver.openInputStream(uri)!!.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    // accept packs zipped with or without a top folder
                    var name = e.name.replace('\\', '/').trimStart('/')
                    if (!ALLOWED.matches(name) && name.contains('/')) name = name.substringAfter('/')
                    if (!ALLOWED.matches(name)) continue
                    val out = File(tmp, name)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath)) continue // zip-slip guard
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val n = zip.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > 25L * 1024 * 1024) throw IllegalArgumentException("Skin pack is larger than 25 MB")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
        }
        val themeFile = File(tmp, "theme.json")
        if (!themeFile.exists()) throw IllegalArgumentException("The pack has no theme.json")
        val parsed = parseOrThrow(themeFile.readText())
        val id = safeId(parsed.id)
        checkVersion(id, parsed.version)
        val dest = File(root(), id)
        dest.deleteRecursively()
        File(root(), "$id.json").delete()
        if (!tmp.renameTo(dest)) { tmp.copyRecursively(dest, true); tmp.deleteRecursively() }
        return id
    }

    /** Saves a theme made in the Theme Editor (keeps any pack assets of the theme it was based on). */
    fun saveUserTheme(json: String, basePackDir: String?): Skin {
        val parsed = parseOrThrow(json)
        val id = safeId(parsed.id)
        val dir = File(root(), id).apply { mkdirs() }
        if (basePackDir != null && File(basePackDir).absolutePath != dir.absolutePath) {
            for (sub in listOf("fonts", "icons", "images")) {
                val src = File(basePackDir, sub)
                if (src.exists()) src.copyRecursively(File(dir, sub), true)
            }
        }
        File(root(), "$id.json").delete()
        File(dir, "theme.json").writeText(json)
        cache = null
        return all().first { it.id == id }
    }

    /** Writes a skin as a shareable .zip pack (theme.json + its fonts/icons/images). */
    fun exportPack(skin: Skin, out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("theme.json"))
            zip.write(skin.json.toByteArray())
            zip.closeEntry()
            skin.packDir?.let { d ->
                File(d).walkTopDown().filter { it.isFile && it.name != "theme.json" }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(File(d)).path.replace('\\', '/')))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("icons/README.txt"))
            zip.write(Icons.readme().toByteArray())
            zip.closeEntry()
        }
    }

    fun delete(skin: Skin) {
        if (skin.builtIn) return
        val baseId = skin.id.removePrefix("user_")
        File(root(), skin.id).deleteRecursively(); File(root(), "${skin.id}.json").delete()
        File(root(), baseId).deleteRecursively(); File(root(), "$baseId.json").delete()
        cache = null
        if (current.id == skin.id) select("vivid_genz")
    }

    fun reload() { cache = null; init(app) }
}
