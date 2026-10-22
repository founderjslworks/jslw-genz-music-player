package com.jslw.genz.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.jslw.genz.R
import com.jslw.genz.audio.TagIO
import com.jslw.genz.model.Song
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import java.io.File
import java.util.concurrent.Executors

/**
 * Album art loading with a memory cache. Order: user's custom cover → embedded/MediaStore art → our own
 * tag parser (DSD/AIFF/FLAC) → placeholder. Bitmaps are downsampled to the requested size.
 */
object ArtLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.rowBytes * value.height
    }
    private val missing = HashSet<String>()
    private val exec = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    private fun key(song: Song, size: Int) = "${song.albumId}:${song.id.takeIf { it < 0 } ?: 0}:$size"

    fun customCoverFile(ctx: Context, albumId: Long) = File(File(ctx.filesDir, "covers").apply { mkdirs() }, "$albumId.jpg")

    fun invalidate(albumId: Long) {
        val snapshot = cache.snapshot().keys.filter { it.startsWith("$albumId:") }
        snapshot.forEach { cache.remove(it) }
        synchronized(missing) { missing.removeAll { it.startsWith("$albumId:") } }
    }

    /** Loads into an ImageView with a soft cross-fade. */
    fun load(iv: ImageView, song: Song?, sizePx: Int, large: Boolean = false, onLoaded: ((Bitmap?) -> Unit)? = null) {
        iv.setTag(R.id.tag_art, song?.id)
        if (song == null) { placeholder(iv, large); onLoaded?.invoke(null); return }
        val k = key(song, sizePx)
        cache.get(k)?.let { setBitmap(iv, it, false); onLoaded?.invoke(it); return }
        if (synchronized(missing) { k in missing }) { placeholder(iv, large); onLoaded?.invoke(null); return }
        placeholder(iv, large)
        val ctx = iv.context.applicationContext
        exec.execute {
            val bmp = loadSync(ctx, song, sizePx)
            main.post {
                if (iv.getTag(R.id.tag_art) != song.id) return@post
                if (bmp != null) setBitmap(iv, bmp, true) else placeholder(iv, large)
                onLoaded?.invoke(bmp)
            }
        }
    }

    private fun setBitmap(iv: ImageView, bmp: Bitmap, fade: Boolean) {
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setBackgroundColor(0)
        androidx.core.widget.ImageViewCompat.setImageTintList(iv, null)
        if (fade) {
            val td = TransitionDrawable(arrayOf(ColorDrawable(0), BitmapDrawable(iv.resources, bmp)))
            td.isCrossFadeEnabled = true
            iv.setImageDrawable(td)
            td.startTransition(220)
        } else iv.setImageBitmap(bmp)
    }

    fun placeholder(iv: ImageView, large: Boolean) {
        val s = SkinManager.current
        iv.scaleType = ImageView.ScaleType.CENTER
        iv.setBackgroundColor(androidx.core.graphics.ColorUtils.compositeColors(SkinPainter.withAlpha(s.accent, 0x30), s.surface))
        iv.setImageResource(if (large) R.drawable.ic_note_large else R.drawable.ic_note)
        androidx.core.widget.ImageViewCompat.setImageTintList(iv, android.content.res.ColorStateList.valueOf(s.accent))
    }

    /** Synchronous load – call from a background thread. */
    fun loadSync(ctx: Context, song: Song, sizePx: Int): Bitmap? {
        val k = key(song, sizePx)
        cache.get(k)?.let { return it }
        val bmp = try { decode(ctx, song, sizePx) } catch (e: Throwable) { null }
        if (bmp != null) cache.put(k, bmp) else synchronized(missing) { missing += k }
        return bmp
    }

    private fun decode(ctx: Context, song: Song, size: Int): Bitmap? {
        customCoverFile(ctx, song.albumId).takeIf { it.exists() }?.let { f ->
            return decodeBytes(f.readBytes(), size)
        }
        if (song.id > 0) {
            if (Build.VERSION.SDK_INT >= 29) {
                try { return ctx.contentResolver.loadThumbnail(song.uri, Size(size, size), null) } catch (_: Exception) {}
            } else {
                try {
                    val albumArt = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
                    ctx.contentResolver.openInputStream(albumArt)?.use { return decodeBytes(it.readBytes(), size) }
                } catch (_: Exception) {}
            }
        }
        if (!song.needsPcmEngine) {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(ctx, song.uri)
                val pic = r.embeddedPicture
                r.release()
                if (pic != null) return decodeBytes(pic, size)
            } catch (_: Exception) {}
        }
        return TagIO.embeddedPicture(ctx, song.uri, song.ext)?.let { decodeBytes(it, size) }
    }

    fun decodeBytes(b: ByteArray, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Mirror image fading to transparent – the classic Cover Flow reflection. */
    fun reflection(src: Bitmap, heightFraction: Float = 0.35f): Bitmap {
        val w = src.width
        val h = (src.height * heightFraction).toInt().coerceAtLeast(1)
        val m = Matrix().apply { preScale(1f, -1f) }
        val flipped = Bitmap.createBitmap(src, 0, src.height - h, w, h, m, false)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(flipped, 0f, 0f, null)
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        if (flipped != src) flipped.recycle()
        return out
    }
}
