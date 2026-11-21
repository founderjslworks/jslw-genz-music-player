package com.jslw.genz.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.jslw.genz.model.Song
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileInputStream

/**
 * Help for songs saved from YouTube by downloader apps: they rarely carry album art, the "artist" is the channel
 * name and the title is full of extras ("Official Video", "| T-Series"). This finds the video the song came from
 * (its id in the file name or in the file's tags) and reads the real title, channel and thumbnail – all public,
 * no account or key needed – and it tidies YouTube-style names so the music catalogues can match them.
 */
object YouTubeMeta {
    private val idInName = Regex("""[\[(]([A-Za-z0-9_-]{11})[\])]""")
    private val idInUrl = Regex("""(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/)|music\.youtube\.com/watch\?v=)([A-Za-z0-9_-]{11})""")

    /** The YouTube video id this song came from, or null. Reads only the start and end of the file. Background thread. */
    fun videoId(ctx: Context, s: Song): String? {
        idInUrl.find(s.path)?.let { return it.groupValues[1] }
        idInName.findAll(s.path.substringAfterLast('/')).lastOrNull()?.let { m ->
            val id = m.groupValues[1]
            if (id.any { it.isDigit() || it == '_' || it == '-' } || id.any { it.isUpperCase() } && id.any { it.isLowerCase() }) return id
        }
        return try {
            ctx.contentResolver.openFileDescriptor(s.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val size = ch.size()
                    fun scan(pos: Long, len: Int): String? {
                        val buf = java.nio.ByteBuffer.allocate(len)
                        ch.position(pos); while (buf.hasRemaining() && ch.read(buf) > 0) {}
                        // drop the zero bytes of UTF-16 tags so the link reads as plain text
                        val txt = String(buf.array(), 0, buf.position(), Charsets.ISO_8859_1).replace("\u0000", "")
                        return idInUrl.find(txt)?.groupValues?.get(1)
                    }
                    scan(0, minOf(size, 512L * 1024).toInt())
                        ?: if (size > 512L * 1024) scan(maxOf(512L * 1024, size - 256L * 1024), minOf(size - 512L * 1024, 256L * 1024).toInt()) else null
                }
            }
        } catch (_: Exception) { null }
    }

    /** Title, channel and thumbnail of the video as a match the user can pick. Background thread. */
    fun match(id: String): OnlineTags.Match? {
        val j = OnlineTags.get("https://www.youtube.com/oembed?format=json&url=" + Uri.encode("https://www.youtube.com/watch?v=$id"))
            ?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        val rawTitle = j?.optString("title").orEmpty()
        val channel = cleanChannel(j?.optString("author_name").orEmpty())
        val (artist, title) = split(rawTitle, channel)
        return OnlineTags.Match(
            title.ifEmpty { rawTitle }, artist, "", "", "", 0, 0,
            "yt:$id", "https://i.ytimg.com/vi/$id/mqdefault.jpg", "YouTube",
        )
    }

    /** The video's thumbnail as a square cover (the middle of the 16:9 picture), JPEG bytes, or null. */
    fun cover(id: String): Bitmap? {
        for (q in listOf("maxresdefault", "sddefault", "hqdefault")) {
            val b = OnlineTags.get("https://i.ytimg.com/vi/$id/$q.jpg") ?: continue
            val bmp = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) ?: continue
            if (bmp.width <= 120) continue // YouTube's grey "no thumbnail" picture
            return square(bmp)
        }
        return null
    }

    private fun square(b: Bitmap): Bitmap {
        // hq/sd thumbnails are letterboxed 4:3 – trim the black bars first
        var top = 0; var h = b.height
        if (b.width * 3 == b.height * 4) { top = b.height / 8; h = b.height * 3 / 4 }
        val side = minOf(b.width, h)
        return Bitmap.createBitmap(b, (b.width - side) / 2, top + (h - side) / 2, side, side)
    }

    fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()

    fun cleanChannel(c: String) = c
        .replace(Regex("""(?i)\s*-\s*topic$|vevo$|\s*official$|\s*music$"""), "").trim()

    /** "Artist - Song (Official Video) | Label" → ("Artist", "Song"); falls back to the channel as artist. */
    fun split(rawTitle: String, channel: String): Pair<String, String> {
        val t = tidy(rawTitle)
        val parts = t.split(Regex("""\s+[-–—]\s+"""), limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0].trim() to parts[1].trim()
        else channel to t
    }

    /** Strips YouTube extras from a title: brackets, "Official Video", "Lyrics", "| Label", "ft. …" stays. */
    fun tidy(t: String) = t
        .substringBefore(" | ").substringBefore(" || ").substringBefore("｜")
        .replace(Regex("""\(.*?\)|\[.*?]|\{.*?\}|【.*?】"""), " ")
        .replace(Regex("""(?i)\b(official|music|video|audio|lyrics?|lyrical|visualizer|hd|hq|4k|full song|full video|remastered|slowed|reverb|lofi|lo-fi|new song)\b"""), " ")
        .replace(Regex("""(?i)#\S+"""), " ")
        .replace(Regex("""[_]+"""), " ").replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '–', ':', ',')

    /** True when the song looks like it came from YouTube (channel-style artist or a noisy title). */
    fun looksLikeYouTube(s: Song) =
        Regex("""(?i)official|lyric|video|\||vevo|- topic|\[[A-Za-z0-9_-]{11}]""").containsMatchIn(s.title + " " + s.artist + " " + s.path)

    /**
     * Gives every song that has no picture a cover: the YouTube thumbnail when the video can be found, otherwise
     * the catalogue cover of a close match (same name and about the same length). Only covers are changed.
     * Background thread; [progress] gets (done, total); returns how many songs got a cover.
     */
    fun fixMissingCovers(ctx: Context, songs: List<Song>, cancelled: () -> Boolean, progress: (Int, Int) -> Unit): Int {
        var fixed = 0
        songs.forEachIndexed { i, s ->
            if (cancelled()) return fixed
            progress(i, songs.size)
            if (ArtLoader.hasArt(ctx, s)) return@forEachIndexed
            val bmp = videoId(ctx, s)?.let { runCatching { cover(it) }.getOrNull() } ?: catalogueCover(s)
            if (bmp != null) { ArtLoader.setSongCover(ctx, s, jpeg(bmp)); fixed++ }
        }
        progress(songs.size, songs.size)
        return fixed
    }

    private fun catalogueCover(s: Song): Bitmap? {
        val want = norm(split(s.title, "").second)
        if (want.length < 2) return null
        val m = runCatching { OnlineTags.search(s) }.getOrNull()?.firstOrNull { m ->
            val got = norm(m.title)
            m.coverUrl != null && got.isNotEmpty() && (want.contains(got) || got.contains(want)) &&
                (s.duration <= 0 || m.durationMs <= 0 || Math.abs(m.durationMs - s.duration) < 8000)
        } ?: return null
        return OnlineTags.get(m.coverUrl!!)?.let { ArtLoader.decodeBytes(it, 1000) }
    }

    private fun norm(t: String) = t.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")
}
