package com.jslw.genz.data

import android.content.Context
import com.jslw.genz.model.Song
import java.io.File

/**
 * When GEN-Z first found each song. Android's own "date added" can be the file's old timestamp (copied or
 * downloaded files often keep it), so newly scanned songs wouldn't reach the top of Recently added.
 * The first time GEN-Z ever sees a library, every song keeps its Android date, so nothing looks "new".
 */
object FirstSeen {
    private val map = HashMap<String, Long>()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "first_seen.tsv")

    /** Records new songs; returns how many were new since the last scan. Called from the library loader. */
    @Synchronized
    fun update(ctx: Context, songs: List<Song>): Int {
        val f = file(ctx)
        if (!loaded) {
            loaded = true
            if (f.exists()) try {
                f.forEachLine { l -> val t = l.lastIndexOf('\t'); if (t > 0) l.substring(t + 1).toLongOrNull()?.let { map[l.substring(0, t)] = it } }
            } catch (_: Exception) {}
        }
        val first = map.isEmpty()
        val now = System.currentTimeMillis() / 1000
        var added = 0
        for (s in songs) if (!map.containsKey(s.path)) {
            map[s.path] = if (first) s.dateAdded else maxOf(now, s.dateAdded)
            if (!first) added++
        }
        if (added > 0 || first) save(f)
        return added
    }

    private fun save(f: File) = try {
        f.bufferedWriter().use { w -> for ((k, v) in map) { w.write(k); w.write("\t"); w.write(v.toString()); w.newLine() } }
    } catch (_: Exception) {}

    /** When the song arrived in the library (seconds). */
    @Synchronized
    fun of(s: Song) = maxOf(map[s.path] ?: 0L, s.dateAdded)

    /** Songs that arrived in the last [days] days. */
    fun recentCount(songs: List<Song>, days: Int = 7): Int {
        val since = System.currentTimeMillis() / 1000 - days * 86400L
        return songs.count { of(it) >= since }
    }
}
