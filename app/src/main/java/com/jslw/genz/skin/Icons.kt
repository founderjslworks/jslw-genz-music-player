package com.jslw.genz.skin

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.core.widget.ImageViewCompat
import com.jslw.genz.R

/**
 * Icon packs: a skin pack may replace any built-in icon with icons/<name>.png (or .webp).
 * Icons set from code go through [set]; icons declared in layouts are matched by their
 * content description when a screen is skinned.
 */
object Icons {
    val NAMES: Map<Int, String> = linkedMapOf(
        R.drawable.ic_play to "play", R.drawable.ic_pause to "pause", R.drawable.ic_play_large to "play",
        R.drawable.ic_pause_large to "pause", R.drawable.ic_next to "next", R.drawable.ic_prev to "prev",
        R.drawable.ic_shuffle to "shuffle", R.drawable.ic_repeat to "repeat", R.drawable.ic_repeat_one to "repeat_one",
        R.drawable.ic_queue to "queue", R.drawable.ic_lyrics to "lyrics", R.drawable.ic_timer to "timer",
        R.drawable.ic_equalizer to "equalizer", R.drawable.ic_favorite to "favorite", R.drawable.ic_favorite_border to "favorite_border",
        R.drawable.ic_more to "more", R.drawable.ic_chevron to "chevron", R.drawable.ic_back to "back",
        R.drawable.ic_expand_more to "collapse", R.drawable.ic_search to "search", R.drawable.ic_settings to "settings",
        R.drawable.ic_close to "close", R.drawable.ic_add to "add", R.drawable.ic_drag to "drag",
        R.drawable.ic_album to "album", R.drawable.ic_artist to "artist", R.drawable.ic_genre to "genre",
        R.drawable.ic_folder to "folder", R.drawable.ic_playlist to "playlist", R.drawable.ic_note to "song",
        R.drawable.ic_album_flow to "album_flow", R.drawable.ic_palette to "themes", R.drawable.ic_cut to "ringtone",
        R.drawable.ic_edit to "edit", R.drawable.ic_speed to "speed", R.drawable.ic_delete to "delete",
        R.drawable.ic_history to "recent", R.drawable.ic_star to "most_played", R.drawable.ic_playlist_add to "playlist_add",
        R.drawable.ic_check to "check", R.drawable.ic_play_next to "play_next", R.drawable.ic_info to "info",
        R.drawable.ic_export to "share", R.drawable.ic_import to "import",
    )

    /** Layout icons, recognised by contentDescription. */
    private val BY_DESC: Map<String, Int> = mapOf(
        "Back" to R.drawable.ic_back, "Search" to R.drawable.ic_search, "Next" to R.drawable.ic_next,
        "Previous" to R.drawable.ic_prev, "Shuffle" to R.drawable.ic_shuffle, "Repeat" to R.drawable.ic_repeat,
        "Queue" to R.drawable.ic_queue, "Lyrics" to R.drawable.ic_lyrics, "Sleep timer" to R.drawable.ic_timer,
        "Equalizer" to R.drawable.ic_equalizer, "More" to R.drawable.ic_more, "Close" to R.drawable.ic_close,
        "Favorite" to R.drawable.ic_favorite_border, "Reorder" to R.drawable.ic_drag, "Now playing" to R.drawable.ic_equalizer,
        "Save as playlist" to R.drawable.ic_playlist_add, "Play album" to R.drawable.ic_play,
        "Theme options" to R.drawable.ic_more, "Active" to R.drawable.ic_check,
    )

    private val cache = HashMap<String, Bitmap?>()

    fun clear() = cache.clear()

    private fun custom(res: Int): Bitmap? {
        val skin = SkinManager.current
        if (skin.packDir == null) return null
        val name = NAMES[res] ?: return null
        val key = skin.id + "/" + name
        if (cache.containsKey(key)) return cache[key]
        val f = skin.packFile("icons/$name.png") ?: skin.packFile("icons/$name.webp") ?: skin.packFile("icons/$name.jpg")
        val bmp = f?.let { try { BitmapFactory.decodeFile(it.absolutePath) } catch (e: Exception) { null } }
        cache[key] = bmp
        return bmp
    }

    /** Use instead of setImageResource for any themable icon. */
    fun set(iv: ImageView, res: Int) {
        iv.setTag(R.id.tag_icon, res)
        val b = custom(res)
        if (b != null) {
            iv.setImageDrawable(BitmapDrawable(iv.resources, b))
            if (!SkinManager.current.tintIcons) ImageViewCompat.setImageTintList(iv, null)
        } else iv.setImageResource(res)
    }

    /** Called by SkinPainter for every ImageView in a skinned layout. */
    fun applyTo(iv: ImageView) {
        val res = (iv.getTag(R.id.tag_icon) as? Int) ?: BY_DESC[iv.contentDescription?.toString() ?: return] ?: return
        if (custom(res) != null) set(iv, res)
    }

    fun tintFor(iv: ImageView, color: ColorStateList?) {
        val res = iv.getTag(R.id.tag_icon) as? Int
        if (res != null && custom(res) != null && !SkinManager.current.tintIcons) ImageViewCompat.setImageTintList(iv, null)
        else ImageViewCompat.setImageTintList(iv, color)
    }

    fun readme(): String = buildString {
        append("GEN-Z icon pack\n===============\n")
        append("Put PNG (or WEBP) files in this icons/ folder, named exactly as below. 96x96 px or larger, white on\n")
        append("transparent works best (they are tinted with the theme colours unless \"tintIcons\": false).\n\n")
        NAMES.values.distinct().forEach { append(it).append(".png\n") }
    }
}
