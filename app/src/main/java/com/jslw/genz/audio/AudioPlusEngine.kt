package com.jslw.genz.audio

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.jslw.genz.model.Song
import java.nio.ByteOrder

/**
 * JSL Audio+ playback: Android's own decoders (MediaExtractor + MediaCodec: MP3, AAC, FLAC, OGG, Opus, WAV…)
 * → JSL Audio Engine → 32-bit float AudioTrack on GEN-Z's audio session (so the 1.8 equalizer still applies).
 * Android 5.0+. If a file can't be decoded this way the service falls back to the normal player.
 */
@TargetApi(21)
class AudioPlusEngine(ctx: Context, override val sessionId: Int) : Engine {
    override var onCompletion: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val lock = Object()
    private val main = Handler(Looper.getMainLooper())

    private var outRate = 44100
    private var inChannels = 2
    private var pcmFloat = false
    private var durationMs = 0L
    private var processor: AudioPlusProcessor? = null

    @Volatile private var playing = false
    @Volatile private var released = false
    @Volatile private var seekReq = -1L
    @Volatile private var basePosMs = 0L
    @Volatile private var baseHead = 0L
    private var volume = 1f
    private var speed = 1f
    private var pitch = 1f
    private var auxId = 0
    private var auxLevel = 0f

    override fun prepare(ctx: Context, song: Song): Boolean {
        if (Build.VERSION.SDK_INT < 21) return false
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, song.uri, null)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
            }
            if (idx < 0 || fmt == null) { ex.release(); return false }
            ex.selectTrack(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            durationMs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            if (Build.VERSION.SDK_INT >= 24) fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            val d = MediaCodec.createDecoderByType(mime)
            dec = d
            try {
                d.configure(fmt, null, null, 0)
            } catch (e: Exception) {
                // some decoders refuse float output: retry with their default 16-bit PCM
                d.reset()
                if (Build.VERSION.SDK_INT >= 24) fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                d.configure(fmt, null, null, 0)
            }
            d.start()
            inChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val t = createTrack(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)) ?: run { d.release(); ex.release(); return false }
            extractor = ex; codec = d; track = t
            thread = Thread({ loop() }, "genz-audioplus").apply { start() }
            return true
        } catch (e: Throwable) {
            try { dec?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
            codec = null; extractor = null
            return false
        }
    }

    private fun createTrack(rate: Int): AudioTrack? = try {
        val mask = AudioFormat.CHANNEL_OUT_STEREO
        val enc = AudioFormat.ENCODING_PCM_FLOAT
        val min = AudioTrack.getMinBufferSize(rate, mask, enc)
        if (min <= 0) null else {
            val t = AudioTrack(
                Engine.musicAttributes()!!,
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(enc).build(),
                maxOf(min * 2, rate * 2 * 4 / 4), AudioTrack.MODE_STREAM, sessionId
            )
            if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); null } else {
                outRate = rate
                processor = AudioPlusProcessor(rate)
                t
            }
        }
    } catch (e: Exception) { null }

    private fun head(t: AudioTrack): Long = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val ex = extractor ?: return
        val dec = codec ?: return
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var skipUntilUs = -1L
        var out = FloatArray(8192)
        try {
            while (!released) {
                val sk = seekReq
                if (sk >= 0) {
                    seekReq = -1
                    synchronized(lock) {
                        val t = track!!
                        try { t.pause(); t.flush() } catch (_: Exception) {}
                        ex.seekTo(sk * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        dec.flush()
                        inputDone = false
                        skipUntilUs = sk * 1000
                        basePosMs = sk
                        baseHead = head(t)
                        if (playing) t.play()
                    }
                    continue
                }
                if (!playing) {
                    synchronized(lock) { if (!playing && seekReq < 0 && !released) lock.wait(250) }
                    continue
                }

                if (!inputDone) {
                    val ii = dec.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val buf = dec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val oi = dec.dequeueOutputBuffer(info, 5_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(dec.outputFormat)
                    oi >= 0 -> {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0 && (skipUntilUs < 0 || info.presentationTimeUs + 100_000 >= skipUntilUs)) {
                            if (skipUntilUs >= 0 && info.presentationTimeUs >= skipUntilUs) skipUntilUs = -1
                            val bb = dec.getOutputBuffer(oi)!!
                            bb.position(info.offset); bb.limit(info.offset + info.size)
                            bb.order(ByteOrder.LITTLE_ENDIAN)
                            val samples = if (pcmFloat) info.size / 4 else info.size / 2
                            val frames = samples / inChannels
                            if (out.size < frames * 2) out = FloatArray(frames * 2)
                            toStereo(bb, frames, out)
                            dec.releaseOutputBuffer(oi, false)
                            processor?.process(out, frames, 2)
                            write(out, frames * 2)
                        } else dec.releaseOutputBuffer(oi, false)
                        if (eos) finish()
                    }
                }
            }
        } catch (e: Throwable) {
            if (!released) main.post { onError?.invoke("Audio+ decoder error: ${e.message}") }
        }
    }

    private fun onFormat(f: MediaFormat) {
        inChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        pcmFloat = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (rate != outRate) synchronized(lock) {
            // e.g. HE-AAC reports half the real rate until decoding starts: move to a matching track
            val old = track
            val pos = position()
            val n = createTrack(rate) ?: return
            n.setVolume(volume)
            if (auxId != 0) try { n.attachAuxEffect(auxId); n.setAuxEffectSendLevel(auxLevel) } catch (_: Exception) {}
            track = n
            basePosMs = pos; baseHead = 0
            if (playing) { n.play(); applyParams() }
            try { old?.pause(); old?.flush(); old?.release() } catch (_: Exception) {}
        }
    }

    /** Decoder output (16-bit or float, 1…8 channels) → interleaved stereo float. */
    private fun toStereo(bb: java.nio.ByteBuffer, frames: Int, out: FloatArray) {
        val ch = inChannels
        if (pcmFloat) {
            val fb = bb.asFloatBuffer()
            for (i in 0 until frames) {
                val l = fb.get(i * ch)
                out[2 * i] = l
                out[2 * i + 1] = if (ch > 1) fb.get(i * ch + 1) else l
            }
        } else {
            val sb = bb.asShortBuffer()
            for (i in 0 until frames) {
                val l = sb.get(i * ch) / 32768f
                out[2 * i] = l
                out[2 * i + 1] = if (ch > 1) sb.get(i * ch + 1) / 32768f else l
            }
        }
    }

    private fun write(data: FloatArray, len: Int) {
        val t = track ?: return
        var off = 0
        while (off < len && !released && seekReq < 0) {
            val w = t.write(data, off, len - off, AudioTrack.WRITE_BLOCKING)
            if (w <= 0) break
            off += w
        }
    }

    private fun finish() {
        val t = track ?: return
        try { t.stop() } catch (_: Exception) {}
        var last = -1L; var still = 0
        while (!released && seekReq < 0 && still < 6) {
            val h = head(t)
            if (h == last) still++ else still = 0
            last = h
            Thread.sleep(50)
        }
        if (!released && seekReq < 0) {
            playing = false
            main.post { onCompletion?.invoke() }
            synchronized(lock) { while (!released && seekReq < 0) lock.wait(250) }
        }
    }

    override fun start() {
        val t = track ?: return
        playing = true
        try { t.play(); applyParams() } catch (_: Exception) {}
        synchronized(lock) { lock.notifyAll() }
    }

    override fun pause() {
        playing = false
        try { track?.pause() } catch (_: Exception) {}
    }

    override val isPlaying: Boolean get() = playing

    override fun position(): Long {
        val t = track ?: return 0
        val p = basePosMs + (head(t) - baseHead) * 1000L / outRate
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    override fun duration(): Long = durationMs

    override fun seekTo(ms: Long) {
        seekReq = ms.coerceAtLeast(0)
        basePosMs = seekReq
        if (!playing) try { track?.flush() } catch (_: Exception) {}
        synchronized(lock) { lock.notifyAll() }
    }

    override fun setVolume(v: Float) {
        volume = v
        try { track?.setVolume(v) } catch (_: Exception) {}
    }

    override fun setSpeedPitch(speed: Float, pitch: Float): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        this.speed = speed; this.pitch = pitch
        applyParams()
        return true
    }

    private fun applyParams() {
        if (Build.VERSION.SDK_INT >= 23 && (speed != 1f || pitch != 1f)) {
            try { track?.playbackParams = PlaybackParams().setSpeed(speed).setPitch(pitch) } catch (_: Exception) {}
        }
    }

    override fun attachAux(effectId: Int, level: Float) {
        auxId = effectId; auxLevel = level
        try { track?.attachAuxEffect(effectId); track?.setAuxEffectSendLevel(level) } catch (_: Exception) {}
    }

    override fun setNext(next: Engine?): Boolean = false

    override fun release() {
        released = true
        playing = false
        synchronized(lock) { lock.notifyAll() }
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        try { thread?.join(500) } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { extractor?.release() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Exception) {}
        track = null; codec = null; extractor = null
    }
}
