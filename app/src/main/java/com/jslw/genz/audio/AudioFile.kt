package com.jslw.genz.audio

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Random-access reader over any content:// or file:// audio URI. */
class AudioFile private constructor(
    private val pfd: ParcelFileDescriptor,
    private val stream: FileInputStream,
) : Closeable {
    val channel: FileChannel = stream.channel
    val size: Long = try { channel.size() } catch (e: Exception) { pfd.statSize }

    fun read(pos: Long, len: Int): ByteArray {
        val avail = if (size > 0) (size - pos).coerceAtLeast(0L) else len.toLong()
        val n = minOf(len.toLong(), avail).toInt()
        val buf = ByteBuffer.allocate(n)
        var p = pos
        while (buf.hasRemaining()) {
            val r = channel.read(buf, p)
            if (r <= 0) break
            p += r
        }
        return if (buf.position() == buf.capacity()) buf.array() else buf.array().copyOf(buf.position())
    }

    /** Reads into [dst] from [pos]; returns bytes read (may be short at EOF). */
    fun readInto(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        val bb = ByteBuffer.wrap(dst, off, len)
        var p = pos
        var total = 0
        while (bb.hasRemaining()) {
            val r = channel.read(bb, p)
            if (r <= 0) break
            p += r; total += r
        }
        return total
    }

    fun ascii(pos: Long, len: Int): String = String(read(pos, len), Charsets.ISO_8859_1)

    fun beInt(pos: Long): Int = ByteBuffer.wrap(read(pos, 4)).order(ByteOrder.BIG_ENDIAN).int
    fun leInt(pos: Long): Int = ByteBuffer.wrap(read(pos, 4)).order(ByteOrder.LITTLE_ENDIAN).int
    fun beLong(pos: Long): Long = ByteBuffer.wrap(read(pos, 8)).order(ByteOrder.BIG_ENDIAN).long
    fun leLong(pos: Long): Long = ByteBuffer.wrap(read(pos, 8)).order(ByteOrder.LITTLE_ENDIAN).long
    fun beShort(pos: Long): Int = ByteBuffer.wrap(read(pos, 2)).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
    fun leShort(pos: Long): Int = ByteBuffer.wrap(read(pos, 2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    override fun close() {
        try { stream.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }

    companion object {
        fun open(ctx: Context, uri: Uri): AudioFile? = try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            AudioFile(pfd, FileInputStream(pfd.fileDescriptor))
        } catch (e: Exception) {
            null
        }
    }
}
