package com.jslw.genz.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.jslw.genz.model.Song
import java.util.Locale

/** What the current file actually is – shown as the "FLAC · 24-bit · 96 kHz · HI-RES" badge. */
data class FormatInfo(
    val codec: String,
    val sampleRate: Int,
    val bits: Int,
    val channels: Int,
    val bitrateKbps: Int,
    val lossless: Boolean,
    val dsdRate: Int = 0,
) {
    val isHiRes: Boolean get() = dsdRate > 0 || (lossless && (sampleRate > 48000 || bits > 16))

    fun label(): String {
        val parts = ArrayList<String>()
        if (dsdRate > 0) {
            parts += "DSD${dsdRate / 44100}"
            parts += String.format(Locale.US, "%.1f MHz", dsdRate / 1_000_000.0)
            parts += "→ PCM"
        } else {
            parts += codec
            if (lossless && bits > 0) parts += "$bits-bit"
            if (!lossless && bitrateKbps > 0) parts += "$bitrateKbps kbps"
            if (sampleRate > 0) parts += khz(sampleRate)
        }
        if (channels > 2) parts += "${channels}ch"
        if (isHiRes) parts += "HI-RES"
        return parts.joinToString(" · ")
    }

    private fun khz(r: Int): String =
        if (r % 1000 == 0) "${r / 1000} kHz" else String.format(Locale.US, "%.1f kHz", r / 1000.0)
}

/** Raw PCM layout of an uncompressed file (WAV / AIFF) for the PCM engine. */
class PcmLayout(
    val dataOffset: Long,
    val dataLength: Long,
    val sampleRate: Int,
    val channels: Int,
    val bits: Int,
    val bigEndian: Boolean,
    val isFloat: Boolean,
) {
    val frameBytes: Int get() = channels * (bits / 8)
    val durationMs: Long get() = if (frameBytes == 0 || sampleRate == 0) 0 else dataLength / frameBytes * 1000L / sampleRate
}

/** Layout of a DSD stream (DSF or DSDIFF). */
class DsdLayout(
    val dataOffset: Long,
    val dataLength: Long,
    val dsdRate: Int,
    val channels: Int,
    val lsbFirst: Boolean,
    /** DSF stores blocks of N bytes per channel; DSDIFF interleaves byte by byte (0). */
    val blockPerChannel: Int,
) {
    val durationMs: Long get() = if (dsdRate == 0 || channels == 0) 0 else dataLength * 8L / channels * 1000L / dsdRate
}

object Formats {

    fun parseWav(f: AudioFile): PcmLayout? = try {
        if (f.ascii(0, 4) != "RIFF" || f.ascii(8, 4) != "WAVE") null else {
            var pos = 12L
            var fmt = 0; var ch = 0; var rate = 0; var bits = 0
            var out: PcmLayout? = null
            while (pos + 8 <= f.size && out == null) {
                val id = f.ascii(pos, 4)
                val size = f.leInt(pos + 4).toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        fmt = f.leShort(pos + 8)
                        ch = f.leShort(pos + 10)
                        rate = f.leInt(pos + 12)
                        bits = f.leShort(pos + 22)
                        if (fmt == 0xFFFE && size >= 26) fmt = f.leShort(pos + 8 + 24)
                    }
                    "data" -> {
                        val start = pos + 8
                        val len = if (size == 0L || size == 0xFFFFFFFFL || start + size > f.size) f.size - start else size
                        if ((fmt == 1 || fmt == 3) && ch > 0 && rate > 0) {
                            out = PcmLayout(start, len, rate, ch, bits, false, fmt == 3)
                        }
                    }
                }
                pos += 8 + size + (size and 1)
            }
            out
        }
    } catch (e: Exception) { null }

    /** @return layout plus the offset of an embedded "ID3 " chunk (or -1). */
    fun parseAiff(f: AudioFile): Pair<PcmLayout?, Long> {
        var id3 = -1L
        try {
            if (f.ascii(0, 4) != "FORM") return null to -1
            val type = f.ascii(8, 4)
            if (type != "AIFF" && type != "AIFC") return null to -1
            val aifc = type == "AIFC"
            var pos = 12L
            var ch = 0; var bits = 0; var rate = 0.0
            var big = true; var flt = false; var ok = true
            var layout: PcmLayout? = null
            while (pos + 8 <= f.size) {
                val id = f.ascii(pos, 4)
                val size = f.beInt(pos + 4).toLong() and 0xFFFFFFFFL
                when (id) {
                    "COMM" -> {
                        ch = f.beShort(pos + 8)
                        bits = f.beShort(pos + 14)
                        rate = ext80(f.read(pos + 16, 10))
                        if (aifc && size >= 22) {
                            when (f.ascii(pos + 26, 4)) {
                                "NONE", "twos", "in24", "in32" -> big = true
                                "sowt" -> big = false
                                "fl32", "FL32" -> { flt = true; bits = 32 }
                                else -> ok = false
                            }
                        }
                    }
                    "SSND" -> {
                        val offset = f.beInt(pos + 8).toLong()
                        val start = pos + 16 + offset
                        val len = (size - 8 - offset).coerceAtMost(f.size - start)
                        if (ok && ch > 0 && rate > 0) layout = PcmLayout(start, len, rate.toInt(), ch, bits, big, flt)
                    }
                    "ID3 ", "id3 " -> id3 = pos + 8
                }
                pos += 8 + size + (size and 1)
            }
            // 8-bit AIFF is signed; we treat it in the decoder
            return layout to id3
        } catch (e: Exception) {
            return null to id3
        }
    }

    /** @return layout plus the offset of the ID3 tag (DSF metadata pointer) or -1. */
    fun parseDsf(f: AudioFile): Pair<DsdLayout?, Long> = try {
        if (f.ascii(0, 4) != "DSD ") null to -1L else {
            val meta = f.leLong(20)
            val fmtSize = f.leLong(32)
            val ch = f.leInt(52)
            val rate = f.leInt(56)
            val bps = f.leInt(60)
            val block = f.leInt(72)
            val dataPos = 28 + fmtSize
            val dataSize = f.leLong(dataPos + 4)
            val layout = if (f.ascii(dataPos, 4) == "data")
                DsdLayout(dataPos + 12, (dataSize - 12).coerceAtMost(f.size - dataPos - 12), rate, ch, bps == 1, block)
            else null
            layout to (if (meta > 0) meta else -1L)
        }
    } catch (e: Exception) { null to -1L }

    /** DSDIFF (.dff). Only uncompressed DSD (not DST). @return layout, ID3 offset, (title, artist). */
    fun parseDff(f: AudioFile): Triple<DsdLayout?, Long, Pair<String?, String?>> {
        var id3 = -1L
        var title: String? = null
        var artist: String? = null
        try {
            if (f.ascii(0, 4) != "FRM8" || f.ascii(12, 4) != "DSD ") return Triple(null, -1, null to null)
            var pos = 16L
            var rate = 0; var ch = 0; var compressed = false
            var layout: DsdLayout? = null
            while (pos + 12 <= f.size) {
                val id = f.ascii(pos, 4)
                val size = f.beLong(pos + 4)
                when (id) {
                    "PROP" -> {
                        var sp = pos + 16
                        val end = pos + 12 + size
                        while (sp + 12 <= end) {
                            val sid = f.ascii(sp, 4)
                            val ssize = f.beLong(sp + 4)
                            when (sid) {
                                "FS  " -> rate = f.beInt(sp + 12)
                                "CHNL" -> ch = f.beShort(sp + 12)
                                "CMPR" -> compressed = f.ascii(sp + 12, 4) != "DSD "
                            }
                            sp += 12 + ssize + (ssize and 1)
                        }
                    }
                    "DSD " -> if (!compressed && rate > 0 && ch > 0) {
                        layout = DsdLayout(pos + 12, size.coerceAtMost(f.size - pos - 12), rate, ch, false, 0)
                    }
                    "DIIN" -> {
                        var sp = pos + 12
                        val end = pos + 12 + size
                        while (sp + 12 <= end) {
                            val sid = f.ascii(sp, 4)
                            val ssize = f.beLong(sp + 4)
                            if (sid == "DITI" || sid == "DIAR") {
                                val n = f.beInt(sp + 12)
                                val txt = String(f.read(sp + 16, n.coerceIn(0, 512)), Charsets.ISO_8859_1)
                                if (sid == "DITI") title = txt else artist = txt
                            }
                            sp += 12 + ssize + (ssize and 1)
                        }
                    }
                    "ID3 " -> id3 = pos + 12
                }
                pos += 12 + size + (size and 1)
            }
            return Triple(layout, id3, title to artist)
        } catch (e: Exception) {
            return Triple(null, id3, title to artist)
        }
    }

    /** FLAC STREAMINFO → real bit depth / sample rate. */
    fun flacInfo(f: AudioFile): FormatInfo? = try {
        var start = 0L
        if (f.ascii(0, 3) == "ID3") start = 10L + TagIO.synchsafe(f.read(6, 4), 0)
        if (f.ascii(start, 4) != "fLaC") null else {
            val si = f.read(start + 8, 34).map { it.toInt() and 0xFF }
            val rate = (si[10] shl 12) or (si[11] shl 4) or (si[12] shr 4)
            val ch = ((si[12] shr 1) and 7) + 1
            val bits = (((si[12] and 1) shl 4) or (si[13] shr 4)) + 1
            FormatInfo("FLAC", rate, bits, ch, 0, true)
        }
    } catch (e: Exception) { null }

    fun ext80(b: ByteArray): Double {
        if (b.size < 10) return 0.0
        val exp = ((b[0].toInt() and 0x7F) shl 8) or (b[1].toInt() and 0xFF)
        var mant = 0L
        for (i in 2 until 10) mant = (mant shl 8) or (b[i].toLong() and 0xFF)
        if (exp == 0 && mant == 0L) return 0.0
        val m = (mant ushr 1).toDouble() * 2.0 + (mant and 1L).toDouble()
        val v = m * Math.pow(2.0, (exp - 16383 - 63).toDouble())
        return if (b[0].toInt() and 0x80 != 0) -v else v
    }

    /** Works out the badge for a song. Runs on a background thread. */
    fun probe(ctx: Context, song: Song): FormatInfo? {
        val kbps = if (song.duration > 0) (song.size * 8 / song.duration).toInt() else 0
        when (song.ext) {
            "dsf", "dff" -> AudioFile.open(ctx, song.uri)?.use { f ->
                val l = if (song.ext == "dsf") parseDsf(f).first else parseDff(f).first
                if (l != null) return FormatInfo("DSD", l.dsdRate, 1, l.channels, 0, true, l.dsdRate)
            }
            "aif", "aiff", "aifc" -> AudioFile.open(ctx, song.uri)?.use { f ->
                parseAiff(f).first?.let { return FormatInfo("AIFF", it.sampleRate, it.bits, it.channels, kbps, true) }
            }
            "wav" -> AudioFile.open(ctx, song.uri)?.use { f ->
                parseWav(f)?.let { return FormatInfo("WAV", it.sampleRate, it.bits, it.channels, kbps, true) }
            }
            "flac" -> AudioFile.open(ctx, song.uri)?.use { f -> flacInfo(f)?.let { return it } }
            "mid", "midi" -> return FormatInfo("MIDI", 0, 0, 0, 0, false)
        }
        return try {
            val ex = MediaExtractor()
            ex.setDataSource(ctx, song.uri, null)
            var info: FormatInfo? = null
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                val br = if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) fmt.getInteger(MediaFormat.KEY_BIT_RATE) / 1000 else kbps
                val bits = if (fmt.containsKey("bits-per-sample")) fmt.getInteger("bits-per-sample") else 0
                val (name, lossless) = when {
                    mime.contains("mpeg") -> "MP3" to false
                    mime.contains("mp4a") || mime.contains("aac") -> "AAC" to false
                    mime.contains("alac") -> "ALAC" to true
                    mime.contains("flac") -> "FLAC" to true
                    mime.contains("vorbis") -> "OGG Vorbis" to false
                    mime.contains("opus") -> "Opus" to false
                    mime.contains("raw") -> "PCM" to true
                    mime.contains("amr") -> "AMR" to false
                    mime.contains("wma") || mime.contains("x-ms") -> "WMA" to false
                    else -> mime.substringAfter('/').uppercase(Locale.US) to false
                }
                info = FormatInfo(name, rate, if (lossless && bits == 0 && name == "ALAC") 16 else bits, ch, br, lossless)
                break
            }
            ex.release()
            info
        } catch (e: Exception) {
            null
        }
    }
}
