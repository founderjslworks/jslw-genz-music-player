package com.jslw.genz.audio

/** A stream of interleaved float frames in [-1, 1]. */
interface PcmSource {
    val sampleRate: Int
    val channels: Int
    val durationMs: Long
    /** Reads up to [maxFrames] frames into [buf] starting at frame [offFrames]. @return frames read, 0 at end. */
    fun read(buf: FloatArray, offFrames: Int, maxFrames: Int): Int
    fun seek(ms: Long)
}

/** Uncompressed WAV / AIFF / AIFC: 8, 16, 24, 32-bit integer and 32/64-bit float, any endianness. */
class LinearPcmSource(
    private val file: AudioFile,
    private val l: PcmLayout,
    private val unsigned8: Boolean,
) : PcmSource {
    override val sampleRate = l.sampleRate
    override val channels = l.channels
    override val durationMs = l.durationMs
    private val bps = l.bits / 8
    private var pos = 0L // byte offset inside data
    private var raw = ByteArray(0)

    override fun read(buf: FloatArray, offFrames: Int, maxFrames: Int): Int {
        val fb = l.frameBytes
        if (fb == 0) return 0
        val remainFrames = ((l.dataLength - pos) / fb).toInt()
        val frames = minOf(maxFrames, remainFrames)
        if (frames <= 0) return 0
        val need = frames * fb
        if (raw.size < need) raw = ByteArray(need)
        val got = file.readInto(l.dataOffset + pos, raw, 0, need)
        val n = got / fb
        pos += n.toLong() * fb
        var o = offFrames * channels
        var p = 0
        val total = n * channels
        for (i in 0 until total) {
            buf[o++] = sample(p)
            p += bps
        }
        return n
    }

    private fun b(i: Int) = raw[i].toInt() and 0xFF

    private fun sample(p: Int): Float {
        if (l.isFloat) {
            return if (bps == 8) {
                var v = 0L
                for (k in 0 until 8) {
                    val idx = if (l.bigEndian) p + k else p + 7 - k
                    v = (v shl 8) or (raw[idx].toLong() and 0xFF)
                }
                java.lang.Double.longBitsToDouble(v).toFloat()
            } else {
                val v = if (l.bigEndian) (b(p) shl 24) or (b(p + 1) shl 16) or (b(p + 2) shl 8) or b(p + 3)
                else (b(p + 3) shl 24) or (b(p + 2) shl 16) or (b(p + 1) shl 8) or b(p)
                java.lang.Float.intBitsToFloat(v)
            }
        }
        return when (bps) {
            1 -> if (unsigned8) (b(p) - 128) / 128f else raw[p] / 128f
            2 -> {
                val v = if (l.bigEndian) (raw[p].toInt() shl 8) or b(p + 1) else (raw[p + 1].toInt() shl 8) or b(p)
                v / 32768f
            }
            3 -> {
                val v = if (l.bigEndian) (raw[p].toInt() shl 16) or (b(p + 1) shl 8) or b(p + 2)
                else (raw[p + 2].toInt() shl 16) or (b(p + 1) shl 8) or b(p)
                v / 8388608f
            }
            4 -> {
                val v = if (l.bigEndian) (b(p) shl 24) or (b(p + 1) shl 16) or (b(p + 2) shl 8) or b(p + 3)
                else (b(p + 3) shl 24) or (b(p + 2) shl 16) or (b(p + 1) shl 8) or b(p)
                v / 2147483648f
            }
            else -> 0f
        }
    }

    override fun seek(ms: Long) {
        val frame = ms * sampleRate / 1000
        pos = (frame * l.frameBytes).coerceIn(0, l.dataLength)
    }
}

/** Linear-interpolation resampler, used only when the device can't output the file's native rate. */
class ResamplingSource(private val src: PcmSource, override val sampleRate: Int) : PcmSource {
    override val channels = src.channels
    override val durationMs = src.durationMs
    private val ratio = src.sampleRate.toDouble() / sampleRate
    private val inBuf = FloatArray(4096 * channels)
    private var inFrames = 0
    private var inPos = 0.0

    override fun read(buf: FloatArray, offFrames: Int, maxFrames: Int): Int {
        var produced = 0
        val ch = channels
        while (produced < maxFrames) {
            if (inPos + 1 >= inFrames) {
                if (inFrames > 0) {
                    val last = inFrames - 1
                    System.arraycopy(inBuf, last * ch, inBuf, 0, ch)
                    inPos -= last
                    inFrames = 1
                }
                val n = src.read(inBuf, inFrames, inBuf.size / ch - inFrames)
                if (n <= 0) break
                inFrames += n
                continue
            }
            val i = inPos.toInt()
            val f = (inPos - i).toFloat()
            val o = (offFrames + produced) * ch
            for (c in 0 until ch) buf[o + c] = inBuf[i * ch + c] * (1 - f) + inBuf[(i + 1) * ch + c] * f
            produced++
            inPos += ratio
        }
        return produced
    }

    override fun seek(ms: Long) {
        src.seek(ms)
        inFrames = 0
        inPos = 0.0
    }
}
