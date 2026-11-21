package com.jslw.genz.util

import android.net.Uri
import com.jslw.genz.model.Song
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Find details & cover online" for one song: looks it up in the iTunes catalogue and returns the best matches
 * (title, artist, album, album artist, genre, year, track and a 1200 px cover) for the user to pick from.
 * Only runs when the user asks for it.
 */
object OnlineTags {
    class Match(
        val title: String, val artist: String, val album: String, val albumArtist: String,
        var genre: String, var year: Int, val track: Int, val coverUrl: String?, val thumbUrl: String?,
        val source: String = "iTunes", val durationMs: Long = 0, val deezerAlbum: Long = 0,
    )

    private fun clean(t: String) = t
        .replace(Regex("\\(.*?\\)|\\[.*?]|\\{.*?\\}"), " ")
        .replace(Regex("(?i)\\b(official|video|audio|lyrics?|lyrical|hd|4k|full song|remastered|slowed|reverb|lofi|lo-fi)\\b"), " ")
        .replace(Regex("[#_|]+"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Runs on a background thread. Asks several catalogues at once – iTunes (Apple Music), Deezer and
     * MusicBrainz / Cover Art Archive – and merges the answers, closest length first.
     * [query] defaults to the song's title and artist.
     */
    fun search(s: Song, query: String? = null, ctx: android.content.Context? = null): List<Match> {
        // the video the song was saved from (YouTube downloads), looked up first: its real title and channel
        // also make a much better search than the file's noisy name
        val ytId = if (query == null && ctx != null) runCatching { YouTubeMeta.videoId(ctx, s) }.getOrNull() else null
        val yt = ytId?.let { runCatching { YouTubeMeta.match(it) }.getOrNull() }
        val term = query?.trim()?.ifEmpty { null } ?: defaultTerm(s, yt)
        val results = arrayOfNulls<List<Match>>(3)
        val jobs = listOf(
            Thread { results[0] = runCatching { itunes(term) }.getOrNull() },
            Thread { results[1] = runCatching { deezer(term) }.getOrNull() },
            Thread { results[2] = runCatching { musicBrainz(term) }.getOrNull() },
        )
        jobs.forEach { it.start() }; jobs.forEach { it.join(16000) }
        if (results.all { it == null } && yt == null) throw java.io.IOException("offline")
        val found = merge(s, results)
        return if (yt != null) listOf(yt) + found else found
    }

    /** What to search for when the user didn't type anything – YouTube-style names are tidied first. */
    fun defaultTerm(s: Song, yt: Match? = null): String {
        if (yt != null) return (yt.artist + " " + yt.title).trim()
        val artist = s.artist.takeIf { it.isNotBlank() && !it.startsWith("unknown", true) && !it.startsWith("<unknown") }
        if (YouTubeMeta.looksLikeYouTube(s)) {
            val (a, t) = YouTubeMeta.split(s.title, YouTubeMeta.cleanChannel(artist.orEmpty()))
            return (t + " " + a).trim()
        }
        return clean(s.title) + (artist?.let { " " + clean(it) } ?: "")
    }

    private fun merge(s: Song, results: Array<List<Match>?>): List<Match> {
        // interleave so every source shows near the top, then closest duration first
        val merged = ArrayList<Match>()
        val lists = results.map { it ?: emptyList() }
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) l.getOrNull(i)?.let { merged += it }
        if (s.duration <= 0) return merged
        return merged.sortedBy { m -> if (m.durationMs > 0 && Math.abs(m.durationMs - s.duration) < 6000) 0 else 1 }
    }

    private fun json(url: String) = get(url)?.let { JSONObject(String(it, Charsets.UTF_8)) }

    private fun itunes(term: String): List<Match> {
        val arr = json("https://itunes.apple.com/search?media=music&entity=song&limit=8&term=" + Uri.encode(term))?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val art = r.optString("artworkUrl100").ifEmpty { null }
            Match(
                r.optString("trackName"), r.optString("artistName"), r.optString("collectionName"),
                r.optString("collectionArtistName").ifEmpty { r.optString("artistName") },
                r.optString("primaryGenreName"), r.optString("releaseDate").take(4).toIntOrNull() ?: 0,
                r.optInt("trackNumber", 0), art?.replace("100x100bb", "1200x1200bb"), art?.replace("100x100bb", "200x200bb"),
                "Apple Music", r.optLong("trackTimeMillis"),
            )
        }
    }

    /** Deezer's open catalogue – strong on Indian film and devotional music. Covers are 1000 px. */
    private fun deezer(term: String): List<Match> {
        val arr = json("https://api.deezer.com/search?limit=8&q=" + Uri.encode(term))?.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val al = r.optJSONObject("album"); val ar = r.optJSONObject("artist")
            Match(
                r.optString("title"), ar?.optString("name") ?: "", al?.optString("title") ?: "", ar?.optString("name") ?: "",
                "", 0, 0, al?.optString("cover_xl")?.ifEmpty { null }, al?.optString("cover_medium")?.ifEmpty { null },
                "Deezer", r.optLong("duration") * 1000, al?.optLong("id") ?: 0,
            )
        }
    }

    /** MusicBrainz recordings with their Cover Art Archive front covers. */
    private fun musicBrainz(term: String): List<Match> {
        val arr = json("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=6&query=" + Uri.encode(term))?.optJSONArray("recordings") ?: return emptyList()
        val out = ArrayList<Match>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val rel = r.optJSONArray("releases")?.optJSONObject(0) ?: continue
            val artist = r.optJSONArray("artist-credit")?.optJSONObject(0)?.optString("name") ?: ""
            val id = rel.optString("id")
            out += Match(
                r.optString("title"), artist, rel.optString("title"), artist, "",
                rel.optString("date").take(4).toIntOrNull() ?: 0, 0,
                "https://coverartarchive.org/release/$id/front-1200", "https://coverartarchive.org/release/$id/front-250",
                "MusicBrainz", r.optLong("length"),
            )
        }
        return out
    }

    /** Year and genre for a Deezer match (its search doesn't include them). Background thread. */
    fun complete(m: Match) {
        if (m.source != "Deezer" || m.deezerAlbum == 0L) return
        val a = json("https://api.deezer.com/album/" + m.deezerAlbum) ?: return
        if (m.year == 0) m.year = a.optString("release_date").take(4).toIntOrNull() ?: 0
        if (m.genre.isEmpty()) m.genre = a.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)?.optString("name") ?: ""
    }

    fun get(url: String): ByteArray? {
        var u = URL(url)
        repeat(4) {
            val c = u.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000; c.readTimeout = 15000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "GENZMusic/" + com.jslw.genz.BuildConfig.VERSION_NAME + " (https://www.jslworks.in/)")
                val code = c.responseCode
                if (code in 300..399) { u = URL(u, c.getHeaderField("Location") ?: return null); return@repeat }
                if (code != 200) return null
                return c.inputStream.use { it.readBytes() }
            } catch (_: Exception) { return null } finally { c.disconnect() }
        }
        return null
    }
}
