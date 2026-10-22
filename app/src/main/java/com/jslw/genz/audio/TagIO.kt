package com.jslw.genz.audio

import android.content.Context
import android.net.Uri
import com.jslw.genz.model.Song
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Small, dependency-free tag reader/writer.
 *  - ID3v2.2/2.3/2.4 (MP3, DSF, AIFF, DFF): text frames, lyrics (USLT), cover (APIC)
 *  - FLAC Vorbis comments + PICTURE block
 *  - MP4/M4A ©lyr lyrics atom
 *  - Writes ID3v2.3 into MP3 files.
 */
object TagIO {

    class Id3(val text: Map<String, String>, val lyrics: String?, val picture: ByteArray?)

    fun synchsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun be24(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 16) or ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off + 2].toInt() and 0xFF)

    private fun unsync(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private val V22 = mapOf(
        "TT2" to "TIT2", "TP1" to "TPE1", "TP2" to "TPE2", "TAL" to "TALB", "TCO" to "TCON",
        "TYE" to "TYER", "TRK" to "TRCK", "ULT" to "USLT", "PIC" to "APIC", "TXX" to "TXXX",
    )

    private fun charset(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun terminator(enc: Int, b: ByteArray, start: Int): Int {
        if (enc == 1 || enc == 2) {
            var i = start
            while (i + 1 < b.size) {
                if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) return i
                i += 2
            }
            return b.size
        }
        for (i in start until b.size) if (b[i] == 0.toByte()) return i
        return b.size
    }

    private fun termLen(enc: Int) = if (enc == 1 || enc == 2) 2 else 1

    private fun decode(enc: Int, b: ByteArray, start: Int, end: Int): String {
        if (start >= end) return ""
        return String(b, start, end - start, charset(enc))
            .replace("\u0000", "; ").trim().trimEnd(';').trim()
    }

    /** Parses a complete in-memory ID3v2 tag that starts with "ID3". */
    fun parseId3(b: ByteArray): Id3? {
        if (b.size < 10 || b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return null
        val ver = b[3].toInt()
        val flags = b[5].toInt()
        val size = synchsafe(b, 6)
        var data = b.copyOfRange(10, minOf(b.size, 10 + size))
        if (flags and 0x80 != 0 && ver < 4) data = unsync(data)
        var p = 0
        if (flags and 0x40 != 0 && data.size > 4) {
            p = if (ver == 3) 4 + be32(data, 0) else synchsafe(data, 0)
        }
        val text = HashMap<String, String>()
        var lyrics: String? = null
        var pic: ByteArray? = null
        while (true) {
            val id: String
            val fsize: Int
            val hdr: Int
            var fflags = 0
            if (ver == 2) {
                if (p + 6 > data.size) break
                id = String(data, p, 3, Charsets.ISO_8859_1)
                fsize = be24(data, p + 3); hdr = 6
            } else {
                if (p + 10 > data.size) break
                id = String(data, p, 4, Charsets.ISO_8859_1)
                fsize = if (ver == 4) synchsafe(data, p + 4) else be32(data, p + 4)
                fflags = data[p + 9].toInt()
                hdr = 10
            }
            if (id[0] == '\u0000' || fsize <= 0 || p + hdr + fsize > data.size) break
            var body = data.copyOfRange(p + hdr, p + hdr + fsize)
            if (ver == 4 && fflags and 0x02 != 0) body = unsync(body)
            if (ver == 4 && fflags and 0x01 != 0 && body.size > 4) body = body.copyOfRange(4, body.size)
            val key = if (ver == 2) V22[id] ?: id else id
            try {
                when {
                    key == "USLT" && body.size > 4 -> {
                        val enc = body[0].toInt()
                        val t = terminator(enc, body, 4)
                        val txt = decode(enc, body, t + termLen(enc), body.size)
                        if (txt.isNotBlank() && lyrics == null) lyrics = txt
                    }
                    key == "APIC" && pic == null && body.size > 4 -> pic = parseApic(body, ver == 2)
                    key == "TXXX" && body.size > 1 -> {
                        val enc = body[0].toInt()
                        val t = terminator(enc, body, 1)
                        val desc = decode(enc, body, 1, t).uppercase()
                        val value = decode(enc, body, t + termLen(enc), body.size)
                        if ((desc == "LYRICS" || desc == "UNSYNCEDLYRICS") && lyrics == null) lyrics = value
                        else text["TXXX:$desc"] = value
                    }
                    key.startsWith("T") && body.size > 1 -> text[key] = decode(body[0].toInt(), body, 1, body.size)
                }
            } catch (_: Exception) {}
            p += hdr + fsize
        }
        return Id3(text, lyrics, pic)
    }

    private fun parseApic(b: ByteArray, v22: Boolean): ByteArray? {
        val enc = b[0].toInt()
        var p: Int
        if (v22) p = 4 else {
            p = terminator(0, b, 1) + 1
        }
        p += 1 // picture type
        val t = terminator(enc, b, p)
        p = t + termLen(enc)
        return if (p < b.size) b.copyOfRange(p, b.size) else null
    }

    /** Reads an ID3 tag found at [pos] (0 for MP3; chunk offsets for DSF/AIFF/DFF). */
    fun readId3At(f: AudioFile, pos: Long): Id3? = try {
        val h = f.read(pos, 10)
        if (h.size < 10 || h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte()) null
        else parseId3(f.read(pos, 10 + synchsafe(h, 6)))
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ FLAC
    class FlacTags(val comments: Map<String, String>, val picture: ByteArray?)

    fun readFlacTags(f: AudioFile): FlacTags? = try {
        var pos = 0L
        if (f.ascii(0, 3) == "ID3") pos = 10L + synchsafe(f.read(6, 4), 0)
        if (f.ascii(pos, 4) != "fLaC") null else {
            pos += 4
            val comments = HashMap<String, String>()
            var pic: ByteArray? = null
            var last = false
            while (!last && pos + 4 <= f.size) {
                val h = f.read(pos, 4)
                last = h[0].toInt() and 0x80 != 0
                val type = h[0].toInt() and 0x7F
                val len = be24(h, 1)
                if (type == 4) {
                    val b = f.read(pos + 4, len)
                    var p = 0
                    val vlen = le32(b, p); p += 4 + vlen
                    val n = le32(b, p); p += 4
                    for (i in 0 until n) {
                        if (p + 4 > b.size) break
                        val l = le32(b, p); p += 4
                        if (l < 0 || p + l > b.size) break
                        val s = String(b, p, l, Charsets.UTF_8); p += l
                        val eq = s.indexOf('=')
                        if (eq > 0) {
                            val k = s.substring(0, eq).uppercase()
                            val v = s.substring(eq + 1)
                            comments[k] = comments[k]?.let { "$it; $v" } ?: v
                        }
                    }
                } else if (type == 6 && pic == null) {
                    val b = f.read(pos + 4, len)
                    var p = 4
                    val ml = be32(b, p); p += 4 + ml
                    val dl = be32(b, p); p += 4 + dl
                    p += 16
                    val datal = be32(b, p); p += 4
                    if (p + datal <= b.size) pic = b.copyOfRange(p, p + datal)
                }
                pos += 4 + len
            }
            FlacTags(comments, pic)
        }
    } catch (e: Exception) { null }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    // ------------------------------------------------------------------ MP4 lyrics
    private fun mp4Lyrics(f: AudioFile): String? {
        fun find(start: Long, end: Long, name: String): Pair<Long, Long>? {
            var p = start
            while (p + 8 <= end) {
                var size = f.beInt(p).toLong() and 0xFFFFFFFFL
                val type = f.ascii(p + 4, 4)
                var hdr = 8L
                if (size == 1L) { size = f.beLong(p + 8); hdr = 16 }
                if (size == 0L) size = end - p
                if (size < hdr) return null
                if (type == name) return (p + hdr) to (p + size)
                p += size
            }
            return null
        }
        return try {
            val moov = find(0, f.size, "moov") ?: return null
            val udta = find(moov.first, moov.second, "udta") ?: return null
            val meta = find(udta.first, udta.second, "meta") ?: return null
            val ilst = find(meta.first + 4, meta.second, "ilst") ?: return null
            val lyr = find(ilst.first, ilst.second, "©lyr") ?: return null
            val data = find(lyr.first, lyr.second, "data") ?: return null
            String(f.read(data.first + 8, (data.second - data.first - 8).toInt()), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    // ------------------------------------------------------------------ high level
    private fun id3Offset(f: AudioFile, ext: String): Long = when (ext) {
        "dsf" -> Formats.parseDsf(f).second
        "dff" -> Formats.parseDff(f).second
        "aif", "aiff", "aifc" -> Formats.parseAiff(f).second
        else -> 0L
    }

    fun embeddedLyrics(ctx: Context, song: Song): String? {
        val f = AudioFile.open(ctx, song.uri) ?: return null
        return f.use {
            when (song.ext) {
                "flac" -> readFlacTags(f)?.comments?.let { c -> c["LYRICS"] ?: c["UNSYNCEDLYRICS"] ?: c["SYNCEDLYRICS"] }
                "m4a", "mp4", "aac", "alac" -> mp4Lyrics(f)
                else -> {
                    val off = id3Offset(f, song.ext)
                    if (off >= 0) readId3At(f, off)?.lyrics else null
                }
            }
        }
    }

    /** ReplayGain in dB from the tags (FLAC/Vorbis comments or ID3 TXXX), or null. */
    fun replayGainDb(ctx: Context, song: Song, album: Boolean): Float? {
        val f = AudioFile.open(ctx, song.uri) ?: return null
        val key = if (album) "REPLAYGAIN_ALBUM_GAIN" else "REPLAYGAIN_TRACK_GAIN"
        val fallback = if (album) "REPLAYGAIN_TRACK_GAIN" else "REPLAYGAIN_ALBUM_GAIN"
        val raw = f.use {
            when (song.ext) {
                "flac" -> readFlacTags(f)?.comments?.let { c -> c[key] ?: c[fallback] }
                "m4a", "mp4", "aac", "alac" -> null
                else -> {
                    val off = id3Offset(f, song.ext)
                    if (off >= 0) readId3At(f, off)?.text?.let { t -> t["TXXX:$key"] ?: t["TXXX:$fallback"] } else null
                }
            }
        } ?: return null
        return Regex("[-+]?\\d+(\\.\\d+)?").find(raw)?.value?.toFloatOrNull()
    }

    /** Cover art for files MediaMetadataRetriever can't read (DSD/AIFF) or as a fallback. */
    fun embeddedPicture(ctx: Context, uri: Uri, ext: String): ByteArray? {
        val f = AudioFile.open(ctx, uri) ?: return null
        return f.use {
            if (ext == "flac") readFlacTags(f)?.picture
            else {
                val off = id3Offset(f, ext)
                if (off >= 0) readId3At(f, off)?.picture else null
            }
        }
    }

    /** title, artist, album, albumArtist, genre, year, track – for files found by the folder scanner. */
    fun basicTags(f: AudioFile, ext: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (ext == "flac") {
            readFlacTags(f)?.comments?.let { c ->
                c["TITLE"]?.let { out["title"] = it }
                c["ARTIST"]?.let { out["artist"] = it }
                c["ALBUM"]?.let { out["album"] = it }
                c["ALBUMARTIST"]?.let { out["albumArtist"] = it }
                c["GENRE"]?.let { out["genre"] = it }
                c["DATE"]?.let { out["year"] = it.take(4) }
                c["TRACKNUMBER"]?.let { out["track"] = it.substringBefore('/') }
            }
            return out
        }
        if (ext == "dff") {
            val (_, _, ta) = Formats.parseDff(f)
            ta.first?.let { out["title"] = it }
            ta.second?.let { out["artist"] = it }
        }
        val off = id3Offset(f, ext)
        if (off >= 0) readId3At(f, off)?.text?.let { t ->
            t["TIT2"]?.let { out["title"] = it }
            t["TPE1"]?.let { out["artist"] = it }
            t["TALB"]?.let { out["album"] = it }
            t["TPE2"]?.let { out["albumArtist"] = it }
            t["TCON"]?.let { out["genre"] = cleanGenre(it) }
            (t["TYER"] ?: t["TDRC"])?.let { out["year"] = it.take(4) }
            t["TRCK"]?.let { out["track"] = it.substringBefore('/') }
        }
        return out
    }

    private val GENRES = arrayOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychadelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )

    /** "(17)" / "17" / "(17)Rock" → "Rock" */
    fun cleanGenre(raw: String): String {
        val s = raw.trim()
        val m = Regex("^\\(?(\\d{1,3})\\)?(.*)$").find(s) ?: return s
        val rest = m.groupValues[2].trim()
        if (rest.isNotEmpty()) return rest
        val n = m.groupValues[1].toIntOrNull() ?: return s
        return GENRES.getOrNull(n) ?: s
    }

    // ------------------------------------------------------------------ ID3 writing (MP3)
    private fun be32Bytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun synchsafeBytes(v: Int) = byteArrayOf(
        ((v ushr 21) and 0x7F).toByte(), ((v ushr 14) and 0x7F).toByte(), ((v ushr 7) and 0x7F).toByte(), (v and 0x7F).toByte()
    )

    private fun buildId3v23(fields: Map<String, String>, lyrics: String?, picture: ByteArray?): ByteArray {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, body: ByteArray) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(be32Bytes(body.size))
            frames.write(0); frames.write(0)
            frames.write(body)
        }
        for ((k, v) in fields) {
            if (v.isBlank()) continue
            frame(k, byteArrayOf(1) + v.toByteArray(Charsets.UTF_16))
        }
        if (!lyrics.isNullOrBlank()) {
            val body = ByteArrayOutputStream()
            body.write(1)
            body.write("eng".toByteArray(Charsets.ISO_8859_1))
            body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)) // empty UTF-16 description
            body.write(lyrics.toByteArray(Charsets.UTF_16))
            frame("USLT", body.toByteArray())
        }
        if (picture != null) {
            val png = picture.size > 4 && picture[1] == 'P'.code.toByte() && picture[2] == 'N'.code.toByte()
            val body = ByteArrayOutputStream()
            body.write(0)
            body.write((if (png) "image/png" else "image/jpeg").toByteArray(Charsets.ISO_8859_1))
            body.write(0)
            body.write(3) // front cover
            body.write(0) // empty description
            body.write(picture)
            frame("APIC", body.toByteArray())
        }
        val content = frames.toByteArray()
        val padding = 1024
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(3); out.write(0); out.write(0)
        out.write(synchsafeBytes(content.size + padding))
        out.write(content)
        out.write(ByteArray(padding))
        return out.toByteArray()
    }

    private fun skipFully(ins: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = ins.skip(left)
            if (s <= 0) { if (ins.read() == -1) return; left-- } else left -= s
        }
    }

    /**
     * Rewrites the ID3 tag of an MP3. Keeps existing lyrics and cover unless replaced.
     * Throws SecurityException (incl. RecoverableSecurityException) when Android needs the user's consent.
     */
    fun writeMp3(
        ctx: Context, uri: Uri,
        title: String, artist: String, album: String, albumArtist: String, genre: String, year: String, track: String,
        newPicture: ByteArray?,
    ): Boolean {
        var oldTagLen = 0L
        var oldLyrics: String? = null
        var oldPic: ByteArray? = null
        AudioFile.open(ctx, uri)?.use { f ->
            val h = f.read(0, 10)
            if (h.size == 10 && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
                oldTagLen = 10L + synchsafe(h, 6) + if (h[5].toInt() and 0x10 != 0) 10 else 0
                readId3At(f, 0)?.let { oldLyrics = it.lyrics; oldPic = it.picture }
            }
        } ?: return false
        val tag = buildId3v23(
            linkedMapOf(
                "TIT2" to title, "TPE1" to artist, "TALB" to album, "TPE2" to albumArtist,
                "TCON" to genre, "TYER" to year, "TRCK" to track,
            ),
            oldLyrics, newPicture ?: oldPic,
        )
        val tmp = File(ctx.cacheDir, "tagwrite.tmp")
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            skipFully(ins, oldTagLen)
            tmp.outputStream().use { ins.copyTo(it) }
        } ?: return false
        // Note: if writing fails half-way, the original audio is still in the cache temp file.
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: return false
        out.use {
            it.write(tag)
            tmp.inputStream().use { t -> t.copyTo(it) }
        }
        tmp.delete()
        return true
    }
}
