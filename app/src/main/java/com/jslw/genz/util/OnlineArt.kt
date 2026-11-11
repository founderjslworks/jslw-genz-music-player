package com.jslw.genz.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.jslw.genz.data.Prefs
import com.jslw.genz.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Optional high-quality album art from the internet (Settings › Album art online). Off by default, and only
 * used while the phone is online (Wi-Fi only unless the user allows mobile data).
 *
 * Looks the album up in the iTunes catalogue (1200 × 1200 covers), falling back to MusicBrainz + Cover Art
 * Archive, and keeps the picture on the phone so each album is downloaded once. Songs without a proper album
 * (single downloads, "Unknown album") are looked up by title and artist and get their own cover.
 */
object OnlineArt {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val queued = HashSet<String>()
    private const val RETRY_MS = 7L * 24 * 3600 * 1000      // try a "not found" again after a week
    private var lastMb = 0L

    /** OFF, MISSING (only songs with no art), ALL (prefer high-quality art everywhere). */
    val enabled get() = Prefs.onlineArt != "off"
    val preferOnline get() = Prefs.onlineArt == "all"

    fun dir(ctx: Context) = File(ctx.filesDir, "online_covers").apply { mkdirs() }

    /** Songs whose album isn't real get a cover of their own. */
    fun ownCover(s: Song): Boolean {
        val a = s.album.trim().lowercase()
        if (a.isEmpty() || a.startsWith("unknown") || a == "<unknown>") return true
        val folder = File(s.path).parentFile?.name?.lowercase()
        return folder != null && a == folder
    }

    fun key(s: Song) = if (ownCover(s)) "s${s.id}" else "a${s.albumId}"
    fun file(ctx: Context, s: Song) = File(dir(ctx), key(s) + ".jpg")
    private fun miss(ctx: Context, s: Song) = File(dir(ctx), key(s) + ".none")

    /** Deletes every downloaded cover (Settings). */
    fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() }; synchronized(queued) { queued.clear() } }

    fun cachedCount(ctx: Context) = dir(ctx).listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0

    private fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val ni = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        ni != null && ni.isConnected && (Prefs.onlineArtMobile || !cm.isActiveNetworkMetered)
    } catch (_: Throwable) { false }

    /** Called by [ArtLoader] when a song needs (better) art. Downloads in the background; [done] runs on the main thread. */
    fun request(ctx: Context, s: Song, done: () -> Unit) {
        if (!enabled) return
        val app = ctx.applicationContext
        val k = key(s)
        if (file(app, s).exists()) return
        val m = miss(app, s)
        if (m.exists() && System.currentTimeMillis() - m.lastModified() < RETRY_MS) return
        synchronized(queued) { if (!queued.add(k)) return }
        exec.execute {
            try {
                if (!online(app)) { synchronized(queued) { queued.remove(k) }; return@execute }
                val ok = fetch(app, s)
                if (ok) main.post(done) else try { m.writeText("") ; m.setLastModified(System.currentTimeMillis()) } catch (_: Exception) {}
            } catch (_: Throwable) {
            } finally { synchronized(queued) { queued.remove(k) } }
        }
    }

    private fun fetch(ctx: Context, s: Song): Boolean {
        val urls = ArrayList<String>()
        itunes(s)?.let { urls += it }
        if (urls.isEmpty()) musicBrainz(s)?.let { urls += it }
        for (u in urls) {
            val bytes = get(u, binary = true) as? ByteArray ?: continue
            if (bytes.size < 4000) continue
            val tmp = File(dir(ctx), key(s) + ".tmp")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(file(ctx, s))) return true
        }
        return false
    }

    // ---------------------------------------------------------------- lookups
    private fun clean(t: String): String = t
        .replace(Regex("\\(.*?\\)|\\[.*?]|\\{.*?\\}"), " ")
        .replace(Regex("(?i)\\b(official|video|audio|lyrics?|lyrical|hd|4k|full song|remastered|slowed|reverb|lofi|lo-fi)\\b"), " ")
        .replace(Regex("[#_|]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun knownArtist(a: String) = a.isNotBlank() && !a.startsWith("unknown", true) && a != "<unknown>"

    private fun similar(a0: String?, b0: String?): Double {
        val a = clean(a0 ?: "").lowercase(); val b = clean(b0 ?: "").lowercase()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.8
        val wa = a.split(' ').toSet(); val wb = b.split(' ').toSet()
        return (wa intersect wb).size.toDouble() / maxOf(1, (wa union wb).size)
    }

    /** Best iTunes match: by album when the song has one, otherwise by song. Returns a 1200 px cover URL. */
    private fun itunes(s: Song): String? {
        val byAlbum = !ownCover(s)
        val term = (if (byAlbum) clean(s.album) else clean(s.title)) + if (knownArtist(s.artist)) " " + clean(s.artist) else ""
        if (term.trim().length < 2) return null
        val url = "https://itunes.apple.com/search?media=music&limit=10&entity=" + (if (byAlbum) "album" else "song") + "&term=" + Uri.encode(term)
        val json = get(url) as? String ?: return null
        val results = JSONObject(json).optJSONArray("results") ?: return null
        var best: JSONObject? = null; var bestScore = 0.0
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            var sc = if (byAlbum) similar(r.optString("collectionName"), s.album) * 2 else similar(r.optString("trackName"), s.title) * 2
            sc += if (knownArtist(s.artist)) similar(r.optString("artistName"), s.artist) else 0.5
            if (!byAlbum && s.duration > 0 && r.has("trackTimeMillis") && Math.abs(r.optLong("trackTimeMillis") - s.duration) < 8000) sc += 0.5
            if (sc > bestScore) { bestScore = sc; best = r }
        }
        if (best == null || bestScore < 1.4) return null
        return best.optString("artworkUrl100").takeIf { it.isNotEmpty() }?.replace("100x100bb", "1200x1200bb")
    }

    /** Backup: Cover Art Archive front cover through a MusicBrainz release search (max 1 request a second). */
    private fun musicBrainz(s: Song): String? {
        if (!knownArtist(s.artist)) return null
        val byAlbum = !ownCover(s)
        val q = "artist:\"" + clean(s.artist) + "\" AND " + if (byAlbum) "release:\"" + clean(s.album) + "\"" else "recording:\"" + clean(s.title) + "\""
        val wait = 1100 - (System.currentTimeMillis() - lastMb)
        if (wait > 0) Thread.sleep(wait)
        lastMb = System.currentTimeMillis()
        val url = "https://musicbrainz.org/ws/2/" + (if (byAlbum) "release" else "recording") + "/?fmt=json&limit=3&query=" + Uri.encode(q)
        val json = get(url) as? String ?: return null
        val o = JSONObject(json)
        val id = if (byAlbum) o.optJSONArray("releases")?.optJSONObject(0)?.optString("id")
        else o.optJSONArray("recordings")?.optJSONObject(0)?.optJSONArray("releases")?.optJSONObject(0)?.optString("id")
        return id?.takeIf { it.isNotEmpty() }?.let { "https://coverartarchive.org/release/$it/front-1200" }
    }

    private fun get(url: String, binary: Boolean = false): Any? {
        var u = URL(url)
        repeat(4) {
            val c = u.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000; c.readTimeout = 12000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "GENZMusic/" + com.jslw.genz.BuildConfig.VERSION_NAME + " (https://www.jslworks.in/)")
                val code = c.responseCode
                if (code in 300..399) { u = URL(u, c.getHeaderField("Location") ?: return null); return@repeat }
                if (code != 200) return null
                val bytes = c.inputStream.use { it.readBytes() }
                return if (binary) bytes else String(bytes, Charsets.UTF_8)
            } catch (_: Exception) { return null } finally { c.disconnect() }
        }
        return null
    }
}
