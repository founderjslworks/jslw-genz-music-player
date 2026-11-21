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
        val genre: String, val year: Int, val track: Int, val coverUrl: String?, val thumbUrl: String?,
    )

    private fun clean(t: String) = t
        .replace(Regex("\\(.*?\\)|\\[.*?]|\\{.*?\\}"), " ")
        .replace(Regex("(?i)\\b(official|video|audio|lyrics?|lyrical|hd|4k|full song|remastered|slowed|reverb|lofi|lo-fi)\\b"), " ")
        .replace(Regex("[#_|]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** Runs on a background thread. [query] defaults to the song's title and artist. */
    fun search(s: Song, query: String? = null): List<Match> {
        val term = query?.trim()?.ifEmpty { null }
            ?: (clean(s.title) + if (s.artist.isNotBlank() && !s.artist.startsWith("unknown", true)) " " + clean(s.artist) else "")
        val json = get("https://itunes.apple.com/search?media=music&entity=song&limit=12&term=" + Uri.encode(term)) ?: return emptyList()
        val arr = JSONObject(String(json, Charsets.UTF_8)).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Match>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val art = r.optString("artworkUrl100").ifEmpty { null }
            out += Match(
                r.optString("trackName"), r.optString("artistName"), r.optString("collectionName"),
                r.optString("collectionArtistName").ifEmpty { r.optString("artistName") },
                r.optString("primaryGenreName"), r.optString("releaseDate").take(4).toIntOrNull() ?: 0,
                r.optInt("trackNumber", 0), art?.replace("100x100bb", "1200x1200bb"), art?.replace("100x100bb", "200x200bb"),
            )
        }
        // closest duration first when the catalogue has several versions
        if (s.duration > 0) {
            val dur = HashMap<Match, Long>()
            for (i in 0 until arr.length()) dur[out[i]] = Math.abs(arr.getJSONObject(i).optLong("trackTimeMillis") - s.duration)
            return out.sortedBy { if ((dur[it] ?: Long.MAX_VALUE) < 6000) 0 else 1 }
        }
        return out
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
