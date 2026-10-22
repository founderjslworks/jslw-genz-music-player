package com.jslw.genz.playback

import android.content.Context
import android.net.Uri
import com.jslw.genz.audio.TagIO
import com.jslw.genz.data.LibraryDb
import com.jslw.genz.model.Song
import java.io.File

class LyricLine(val timeMs: Long, val text: String)

/** Synced (.lrc) or plain lyrics. */
class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {

    /** Index of the line being sung at [pos], or -1. */
    fun indexAt(pos: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    companion object {
        private val TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        private val OFFSET = Regex("\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE)

        fun parse(raw: String?): Lyrics? {
            if (raw.isNullOrBlank()) return null
            val text = raw.removePrefix("﻿")
            val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val timed = ArrayList<LyricLine>()
            val plain = ArrayList<LyricLine>()
            for (line in text.lines()) {
                val tags = TAG.findAll(line).toList()
                if (tags.isEmpty()) {
                    val t = line.trim()
                    // skip [ar:..] style metadata
                    if (!(t.startsWith("[") && t.endsWith("]") && t.contains(':'))) plain += LyricLine(0, t)
                    continue
                }
                val content = line.substring(tags.last().range.last + 1).trim()
                for (m in tags) {
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                    timed += LyricLine((min * 60_000 + sec * 1000 + ms - offset).coerceAtLeast(0), content)
                }
            }
            return if (timed.isNotEmpty()) Lyrics(timed.sortedBy { it.timeMs }, true)
            else if (plain.any { it.text.isNotBlank() }) Lyrics(plain.dropWhile { it.text.isBlank() }, false)
            else null
        }

        /** Linked lyrics file → .lrc next to the song → lyrics embedded in the tags. Background thread only. */
        fun load(ctx: Context, song: Song): Lyrics? {
            LibraryDb.lyricLink(song.id)?.let { u ->
                try {
                    ctx.contentResolver.openInputStream(Uri.parse(u))?.use { ins ->
                        parse(ins.readBytes().toString(Charsets.UTF_8))?.let { return it }
                    }
                } catch (_: Exception) {}
            }
            try {
                val base = song.path.substringBeforeLast('.')
                for (ext in arrayOf(".lrc", ".LRC", ".txt")) {
                    val f = File(base + ext)
                    if (f.exists() && f.canRead()) parse(f.readText())?.let { return it }
                }
            } catch (_: Exception) {}
            return try { parse(TagIO.embeddedLyrics(ctx, song)) } catch (e: Exception) { null }
        }
    }
}
