package com.jslw.genz.audio

/**
 * DSD (DSF / DSDIFF) → PCM converter.
 *
 * The 1-bit stream is low-pass filtered with a linear-phase windowed-sinc FIR and decimated.
 * Filtering a 1-bit signal is just summing ±coefficients, so the filter runs on whole bytes via
 * 256-entry lookup tables (one table per byte of filter length) – fast enough for DSD64–DSD256 on phones.
 *
 * Output: DSD rate / [decimation] (88.2 kHz for DSD64/32, DSD128/64, ...).
 */
class DsdSource(
    private val file: AudioFile,
    private val l: DsdLayout,
    private val decimation: Int,
) : PcmSource {
    override val sampleRate = l.dsdRate / decimation
    override val channels = l.channels.coerceAtMost(2)
    override val durationMs = l.durationMs

    private val srcChannels = l.channels
    private val bytesPerOut = decimation / 8
    private val taps: Int
    private val tableBytes: Int
    private val tables: Array<FloatArray>
    private val hist: Array<IntArray>
    private val histPos = IntArray(srcChannels)
    private val phase = IntArray(srcChannels)

    // decoded frames waiting to be handed out
    private var pend = FloatArray(0)
    private var pendFrames = 0
    private var pendRead = 0

    private val block = if (l.blockPerChannel > 0) l.blockPerChannel else 4096
    private val raw = ByteArray(block * srcChannels)
    private var pos = 0L // byte offset in data

    init {
        // ~384 taps at DSD64, scaled with the rate so the analog cutoff stays put.
        val scale = (l.dsdRate / 2822400).coerceAtLeast(1)
        taps = 384 * scale
        tableBytes = taps / 8
        val h = design(taps, cutoffHz = 24000.0 + 6000.0 * (scale - 1).coerceAtMost(1), fs = l.dsdRate.toDouble())
        tables = Array(tableBytes) { j ->
            FloatArray(256) { v ->
                var s = 0f
                for (bit in 0 until 8) {
                    // MSB = earliest sample in time
                    val on = (v shr (7 - bit)) and 1
                    s += if (on == 1) h[j * 8 + bit] else -h[j * 8 + bit]
                }
                s
            }
        }
        hist = Array(srcChannels) { IntArray(tableBytes) { 0x69 } } // 0x69 = DSD "silence" pattern
    }

    /** Blackman-windowed sinc, normalised to unity DC gain. */
    private fun design(n: Int, cutoffHz: Double, fs: Double): FloatArray {
        val fc = cutoffHz / fs
        val h = DoubleArray(n)
        val m = (n - 1) / 2.0
        var sum = 0.0
        for (i in 0 until n) {
            val x = i - m
            val sinc = if (x == 0.0) 2 * Math.PI * fc else Math.sin(2 * Math.PI * fc * x) / x
            val w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (n - 1))
            h[i] = sinc * w
            sum += h[i]
        }
        return FloatArray(n) { (h[it] / sum).toFloat() }
    }

    private val reverse = IntArray(256) { v ->
        var r = 0
        for (b in 0 until 8) if ((v shr b) and 1 == 1) r = r or (1 shl (7 - b))
        r
    }

    private fun decodeNextChunk(): Boolean {
        val chunkBytes = block * srcChannels
        val left = l.dataLength - pos
        if (left <= 0) return false
        val want = minOf(chunkBytes.toLong(), left).toInt()
        val got = file.readInto(l.dataOffset + pos, raw, 0, want)
        if (got <= 0) return false
        pos += got
        val perCh = if (l.blockPerChannel > 0) got / srcChannels else got / srcChannels
        val outPerCh = perCh / bytesPerOut + 1
        if (pend.size < outPerCh * channels) pend = FloatArray(outPerCh * channels)
        val produced = IntArray(srcChannels)
        for (c in 0 until srcChannels) {
            val hc = hist[c]
            var hp = histPos[c]
            var ph = phase[c]
            var outIdx = 0
            for (k in 0 until perCh) {
                val idx = if (l.blockPerChannel > 0) c * block + k else k * srcChannels + c
                var v = raw[idx].toInt() and 0xFF
                if (l.lsbFirst) v = reverse[v]
                hc[hp] = v
                hp++; if (hp == tableBytes) hp = 0
                ph++
                if (ph == bytesPerOut) {
                    ph = 0
                    if (c < channels) {
                        var acc = 0f
                        var r = hp // oldest byte
                        for (j in 0 until tableBytes) {
                            acc += tables[j][hc[r]]
                            r++; if (r == tableBytes) r = 0
                        }
                        pend[outIdx * channels + c] = acc.coerceIn(-1f, 1f)
                    }
                    outIdx++
                }
            }
            histPos[c] = hp
            phase[c] = ph
            produced[c] = outIdx
        }
        pendFrames = produced.minOrNull() ?: 0
        pendRead = 0
        return true
    }

    override fun read(buf: FloatArray, offFrames: Int, maxFrames: Int): Int {
        var n = 0
        while (n < maxFrames) {
            if (pendRead >= pendFrames) {
                if (!decodeNextChunk()) break
                if (pendFrames == 0) continue
            }
            val take = minOf(maxFrames - n, pendFrames - pendRead)
            System.arraycopy(pend, pendRead * channels, buf, (offFrames + n) * channels, take * channels)
            pendRead += take
            n += take
        }
        return n
    }

    override fun seek(ms: Long) {
        val perChByte = (ms * (l.dsdRate / 8L) / 1000L)
        pos = if (l.blockPerChannel > 0) {
            val blk = perChByte / block
            (blk * block * srcChannels).coerceIn(0, l.dataLength)
        } else {
            (perChByte * srcChannels).coerceIn(0, l.dataLength)
        }
        for (c in 0 until srcChannels) { hist[c].fill(0x69); histPos[c] = 0; phase[c] = 0 }
        pendFrames = 0; pendRead = 0
    }
}
