package com.jslw.genz.skin

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.jslw.genz.R

/**
 * Bundled Google Fonts (SIL Open Font License) – packaged in the APK so the app stays 100% offline.
 *  inter · lora · vt323 · barlow-condensed · orbitron  (+ system: sans, serif, monospace)
 */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    private val FILES = mapOf(
        "inter" to (R.font.inter_regular to R.font.inter_semibold),
        "inter-black" to (R.font.inter_semibold to R.font.inter_extrabold),
        "lora" to (R.font.lora_regular to R.font.lora_bold),
        "vt323" to (R.font.vt323_regular to R.font.vt323_regular),
        "barlow-condensed" to (R.font.barlow_condensed_medium to R.font.barlow_condensed_bold),
        "orbitron" to (R.font.orbitron_medium to R.font.orbitron_bold),
    )

    private val packCache = HashMap<String, Typeface>()

    /** Theme fonts may be a bundled name ("inter") or a file inside a skin pack ("fonts/MyFont.ttf"). */
    fun forSkin(skin: Skin, name: String, bold: Boolean): Typeface {
        if (name.startsWith("fonts/")) {
            val f = skin.packFile(name)
            if (f != null) {
                val key = f.absolutePath + "/" + bold
                packCache[key]?.let { return it }
                val base = try { Typeface.createFromFile(f) } catch (e: Exception) { null }
                if (base != null) {
                    val tf = if (bold) Typeface.create(base, Typeface.BOLD) else base
                    packCache[key] = tf
                    return tf
                }
            }
            return get(com.jslw.genz.GenZApp.ctx, "inter", bold)
        }
        return get(com.jslw.genz.GenZApp.ctx, name, bold)
    }

    fun clearPackFonts() = packCache.clear()

    val names: List<String> = FILES.keys.toList() + listOf("sans", "serif", "monospace")

    fun get(ctx: Context, name: String, bold: Boolean): Typeface {
        val key = "$name/$bold"
        cache[key]?.let { return it }
        val tf = FILES[name]?.let { (reg, b) ->
            try { ResourcesCompat.getFont(ctx, if (bold) b else reg) } catch (e: Exception) { null }
        } ?: when (name) {
            "serif" -> Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            "monospace" -> Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            "sans-condensed" -> Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
            else -> if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        cache[key] = tf
        return tf
    }
}
