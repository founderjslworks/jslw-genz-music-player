package com.jslw.genz.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.jslw.genz.audio.AudioFile
import com.jslw.genz.audio.Formats
import com.jslw.genz.audio.TagIO
import com.jslw.genz.model.Song
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Ringtone cutter/maker. Cuts without re-encoding (no quality loss):
 * MP3 by whole frames, M4A/AAC with MediaMuxer, WAV by samples (with optional fades).
 */
object RingtoneMaker {

    fun supported(s: Song) = s.ext in setOf("mp3", "m4a", "aac", "mp4", "wav")

    fun outExt(s: Song) = when (s.ext) { "mp3" -> "mp3"; "wav" -> "wav"; else -> "m4a" }

    fun mime(s: Song) = when (s.ext) { "mp3" -> "audio/mpeg"; "wav" -> "audio/x-wav"; else -> "audio/mp4" }

    fun cut(ctx: Context, s: Song, startMs: Long, endMs: Long, fade: Boolean, out: File): Boolean = try {
        when (s.ext) {
            "mp3" -> mp3(ctx, s, startMs, endMs, out)
            "wav" -> wav(ctx, s, startMs, endMs, fade, out)
            else -> mp4(ctx, s, startMs, endMs, out)
        }
    } catch (e: Exception) { e.printStackTrace(); false }

    // ------------------------------------------------------------------ MP3
    private val BR1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BR2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val SR = intArrayOf(44100, 48000, 32000)

    private fun mp3(ctx: Context, s: Song, startMs: Long, endMs: Long, out: File): Boolean {
        val f = AudioFile.open(ctx, s.uri) ?: return false
        f.use {
            var pos = 0L
            if (f.ascii(0, 3) == "ID3") pos = 10L + TagIO.synchsafe(f.read(6, 4), 0)
            var t = 0.0
            var startByte = -1L
            var endByte = f.size
            var first = true
            val h4 = ByteArray(4)
            while (pos + 4 <= f.size) {
                f.readInto(pos, h4, 0, 4)
                val h = ((h4[0].toInt() and 0xFF) shl 24) or ((h4[1].toInt() and 0xFF) shl 16) or
                    ((h4[2].toInt() and 0xFF) shl 8) or (h4[3].toInt() and 0xFF)
                val ver = (h ushr 19) and 3
                val layer = (h ushr 17) and 3
                val brIdx = (h ushr 12) and 0xF
                val srIdx = (h ushr 10) and 3
                if ((h ushr 21) and 0x7FF != 0x7FF || layer != 1 || brIdx == 0 || brIdx == 15 || srIdx == 3 || ver == 1) {
                    pos++; continue
                }
                val mpeg1 = ver == 3
                val br = if (mpeg1) BR1[brIdx] else BR2[brIdx]
                val sr = SR[srIdx] / (if (ver == 3) 1 else if (ver == 2) 2 else 4)
                val len = (if (mpeg1) 144000 * br / sr else 72000 * br / sr) + ((h ushr 9) and 1)
                if (len < 24) { pos++; continue }
                if (first) {
                    first = false
                    // skip a Xing/Info header frame (it describes the whole file's length)
                    val probe = String(f.read(pos, minOf(len, 64)), Charsets.ISO_8859_1)
                    if (probe.contains("Xing") || probe.contains("Info")) { pos += len; continue }
                }
                if (startByte < 0 && t >= startMs) startByte = pos
                if (t >= endMs) { endByte = pos; break }
                t += (if (mpeg1) 1152 else 576) * 1000.0 / sr
                pos += len
            }
            if (startByte < 0) return false
            FileOutputStream(out).use { o ->
                val buf = ByteArray(65536)
                var p = startByte
                while (p < endByte) {
                    val n = f.readInto(p, buf, 0, minOf(buf.size.toLong(), endByte - p).toInt())
                    if (n <= 0) break
                    o.write(buf, 0, n); p += n
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ M4A / AAC
    private fun mp4(ctx: Context, s: Song, startMs: Long, endMs: Long, out: File): Boolean {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, s.uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        if (track < 0) { ex.release(); return false }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val ti = mux.addTrack(fmt)
        mux.start()
        ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val size = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
        val buf = ByteBuffer.allocate(maxOf(size, 64 * 1024))
        val info = MediaCodec.BufferInfo()
        var first = -1L
        var wrote = false
        while (true) {
            info.offset = 0
            info.size = ex.readSampleData(buf, 0)
            if (info.size < 0) break
            val t = ex.sampleTime
            if (t > endMs * 1000) break
            if (first < 0) first = t
            info.presentationTimeUs = t - first
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            mux.writeSampleData(ti, buf, info)
            wrote = true
            ex.advance()
        }
        try { mux.stop() } catch (_: Exception) {}
        mux.release()
        ex.release()
        return wrote
    }

    // ------------------------------------------------------------------ WAV
    private fun wav(ctx: Context, s: Song, startMs: Long, endMs: Long, fade: Boolean, out: File): Boolean {
        val f = AudioFile.open(ctx, s.uri) ?: return false
        f.use {
            val l = Formats.parseWav(f) ?: return false
            val fb = l.frameBytes
            val a = (startMs * l.sampleRate / 1000) * fb
            val b = minOf((endMs * l.sampleRate / 1000) * fb, l.dataLength)
            val len = (b - a).coerceAtLeast(0)
            RandomAccessFile(out, "rw").use { o ->
                o.setLength(0)
                val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                hdr.put("RIFF".toByteArray()); hdr.putInt((36 + len).toInt()); hdr.put("WAVE".toByteArray())
                hdr.put("fmt ".toByteArray()); hdr.putInt(16); hdr.putShort((if (l.isFloat) 3 else 1).toShort())
                hdr.putShort(l.channels.toShort()); hdr.putInt(l.sampleRate); hdr.putInt(l.sampleRate * fb)
                hdr.putShort(fb.toShort()); hdr.putShort(l.bits.toShort())
                hdr.put("data".toByteArray()); hdr.putInt(len.toInt())
                o.write(hdr.array())
                val buf = ByteArray(fb * 4096)
                var p = 0L
                val fadeBytes = if (fade && l.bits == 16 && !l.isFloat) minOf(len / 2, (l.sampleRate / 2L) * fb) else 0L
                while (p < len) {
                    val n = f.readInto(l.dataOffset + a + p, buf, 0, minOf(buf.size.toLong(), len - p).toInt())
                    if (n <= 0) break
                    if (fadeBytes > 0) {
                        val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                        var i = 0
                        while (i + 1 < n) {
                            val abs = p + i
                            val g = when {
                                abs < fadeBytes -> abs.toFloat() / fadeBytes
                                abs > len - fadeBytes -> (len - abs).toFloat() / fadeBytes
                                else -> 1f
                            }
                            if (g < 1f) bb.putShort(i, (bb.getShort(i) * g).toInt().toShort())
                            i += 2
                        }
                    }
                    o.write(buf, 0, n)
                    p += n
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ publish & set
    private fun typeDir(type: Int) = when (type) {
        RingtoneManager.TYPE_NOTIFICATION -> Environment.DIRECTORY_NOTIFICATIONS
        RingtoneManager.TYPE_ALARM -> Environment.DIRECTORY_ALARMS
        else -> Environment.DIRECTORY_RINGTONES
    }

    /** Copies the cut file into the phone's Ringtones/Notifications/Alarms folder and registers it. */
    fun publish(ctx: Context, file: File, title: String, ext: String, mime: String, type: Int): Uri? {
        val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "." + ext
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.TITLE, title)
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.IS_RINGTONE, type == RingtoneManager.TYPE_RINGTONE)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, type == RingtoneManager.TYPE_NOTIFICATION)
            put(MediaStore.Audio.Media.IS_ALARM, type == RingtoneManager.TYPE_ALARM)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, typeDir(type) + "/")
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(typeDir(type)).apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            @Suppress("DEPRECATION")
            cv.put(MediaStore.MediaColumns.DATA, dest.absolutePath)
            @Suppress("DEPRECATION")
            val base = MediaStore.Audio.Media.getContentUriForPath(dest.absolutePath) ?: MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            ctx.contentResolver.delete(base, MediaStore.MediaColumns.DATA + "=?", arrayOf(dest.absolutePath))
            ctx.contentResolver.insert(base, cv)
        }
    }

    fun canSetDefault(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(ctx)

    fun setDefault(ctx: Context, uri: Uri, type: Int): Boolean = try {
        RingtoneManager.setActualDefaultRingtoneUri(ctx, type, uri); true
    } catch (e: Exception) { false }

    // ------------------------------------------------------------------ waveform
    /** Peak level per bucket (0..1), decoded with MediaCodec. Gives up after ~8 s on slow phones. */
    @Suppress("DEPRECATION")
    fun waveform(ctx: Context, s: Song, buckets: Int): FloatArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, s.uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            if (track < 0) return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else s.duration * 1000
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val peaks = FloatArray(buckets)
            val info = MediaCodec.BufferInfo()
            val started = System.currentTimeMillis()
            var inputDone = false
            var outputDone = false
            while (!outputDone && System.currentTimeMillis() - started < 8000) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val ib = codec.inputBuffers[ii]
                        ib.clear()
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val ob = codec.outputBuffers[oi]
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val bucket = ((info.presentationTimeUs.toDouble() / dur.coerceAtLeast(1)) * buckets).toInt().coerceIn(0, buckets - 1)
                    var m = 0
                    var k = 0
                    while (k < sb.limit()) { val v = abs(sb.get(k).toInt()); if (v > m) m = v; k += 7 }
                    val p = m / 32768f
                    if (p > peaks[bucket]) peaks[bucket] = p
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            codec.stop(); codec.release()
            val max = peaks.maxOrNull()?.takeIf { it > 0 } ?: 1f
            return FloatArray(buckets) { (peaks[it] / max).coerceIn(0f, 1f) }
        } catch (e: Exception) {
            return null
        } finally {
            ex.release()
        }
    }
}
