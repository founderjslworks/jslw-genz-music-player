package com.jslw.genz.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.jslw.genz.data.Library
import com.jslw.genz.util.ArtLoader
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only album art for Android Auto / Automotive and Android's media controls, which load covers by
 * URI in their own process: content://<app>.art/song/<songId>. Only cover images are served – nothing else.
 */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val ctx = context ?: throw FileNotFoundException()
        val segs = uri.pathSegments
        if (segs.size != 2 || segs[0] != "song") throw FileNotFoundException()
        val id = segs[1].toLongOrNull() ?: throw FileNotFoundException()
        val song = Library.song(id) ?: throw FileNotFoundException()
        val dir = File(ctx.cacheDir, "auto_art").apply { mkdirs() }
        // one file per album (or per song for scanner-only tracks), refreshed when the cover is edited
        val key = if (song.id < 0) "s${-song.id}" else "a${song.albumId}"
        val custom = ArtLoader.customCoverFile(ctx, song.albumId)
        val file = File(dir, "$key.jpg")
        if (!file.exists() || (custom.exists() && custom.lastModified() > file.lastModified())) {
            val bmp = ArtLoader.loadSync(ctx, song, 480) ?: throw FileNotFoundException()
            val tmp = File(dir, "$key.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
