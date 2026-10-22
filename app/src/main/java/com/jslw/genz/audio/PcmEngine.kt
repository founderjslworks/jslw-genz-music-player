package com.jslw.genz.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.jslw.genz.model.Song

/**
 * GEN-Z's own decoder + AudioTrack output, for formats Android can't play:
 * AIFF/AIFC and DSD (DSF/DFF). Also usable for WAV. Output is 32-bit float on Android 5+
 * at the file's native rate when the device supports it (hi-res), 16-bit on KitKat.
 * Shares the audio session, so the equalizer and effects apply here too.
 */
class PcmEngine(ctx: Context, override val sessionId: Int) : Engine {
    override var onCompletion: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private var file: AudioFile? = null
    private var src: PcmSource? = null
    private var track: AudioTrack? = null
    private var outRate = 44100
    private var outCh = 2
    private var useFloat = false
    private var thread: Thread? = null
    private val lock = Object()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var playing = false
    @Volatile private var released = false
    @Volatile private var seekReq = -1L
    @Volatile private var basePosMs = 0L
    @Volatile private var baseHead = 0L
    private var seed = 22222
    private var speed = 1f
    private var pitch = 1f

    override fun prepare(ctx: Context, song: Song): Boolean {
        val f = AudioFile.open(ctx, song.uri) ?: return false
        try {
            val ok = when (song.ext) {
                "dsf" -> Formats.parseDsf(f).first?.let { openDsd(f, it) } ?: false
                "dff" -> Formats.parseDff(f).first?.let { openDsd(f, it) } ?: false
                "aif", "aiff", "aifc" -> Formats.parseAiff(f).first?.let { openPcm(LinearPcmSource(f, it, false)) } ?: false
                "wav" -> Formats.parseWav(f)?.let { openPcm(LinearPcmSource(f, it, true)) } ?: false
                else -> false
            }
            if (!ok) { f.close(); return false }
            file = f
            thread = Thread({ loop() }, "genz-pcm").apply { start() }
            return true
        } catch (e: Exception) {
            f.close()
            return false
        }
    }

    private fun openDsd(f: AudioFile, l: DsdLayout): Boolean {
        // DSD64 → 88.2 kHz (÷32); DSD128 → 88.2 kHz (÷64)... fall back to 44.1 kHz on old devices.
        for (target in intArrayOf(88200, 44100)) {
            val dec = (l.dsdRate / target).coerceAtLeast(8)
            if (dec % 8 != 0) continue
            val t = createTrack(l.dsdRate / dec, l.channels) ?: continue
            track = t
            src = DsdSource(f, l, dec)
            return true
        }
        return false
    }

    private fun openPcm(s: PcmSource): Boolean {
        createTrack(s.sampleRate, s.channels)?.let { track = it; src = s; return true }
        val fallback = if (s.sampleRate % 44100 == 0) 44100 else 48000
        createTrack(fallback, s.channels)?.let { track = it; src = ResamplingSource(s, fallback); return true }
        return false
    }

    private fun createTrack(rate: Int, channels: Int): AudioTrack? = try {
        outCh = channels.coerceIn(1, 2)
        val mask = if (outCh == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        useFloat = Build.VERSION.SDK_INT >= 21
        val enc = if (useFloat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val min = AudioTrack.getMinBufferSize(rate, mask, enc)
        if (min <= 0) null else {
            val bytes = if (useFloat) 4 else 2
            val size = maxOf(min * 2, rate * outCh * bytes / 4)
            val t = if (Build.VERSION.SDK_INT >= 21) {
                AudioTrack(
                    Engine.musicAttributes()!!,
                    AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(enc).build(),
                    size, AudioTrack.MODE_STREAM, sessionId
                )
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(AudioManager.STREAM_MUSIC, rate, mask, enc, size, AudioTrack.MODE_STREAM, sessionId)
            }
            if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); null } else { outRate = rate; t }
        }
    } catch (e: Exception) { null }

    private fun head(t: AudioTrack): Long = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val s = src ?: return
        val t = track ?: return
        val ch = s.channels
        val frames = 2048
        val fbuf = FloatArray(frames * ch)
        val mix = FloatArray(frames * outCh)
        val sbuf = ShortArray(frames * outCh)
        try {
            while (!released) {
                val sk = seekReq
                if (sk >= 0) {
                    seekReq = -1
                    synchronized(lock) {
                        try { t.pause(); t.flush() } catch (_: Exception) {}
                        s.seek(sk)
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
                val n = s.read(fbuf, 0, frames)
                if (n <= 0) {
                    drain(t)
                    if (!released && seekReq < 0) {
                        playing = false
                        main.post { onCompletion?.invoke() }
                    }
                    synchronized(lock) { while (!released && seekReq < 0) lock.wait(250) }
                    continue
                }
                val data: FloatArray
                if (ch == outCh) data = fbuf else {
                    for (i in 0 until n) {
                        mix[i * 2] = fbuf[i * ch]
                        mix[i * 2 + 1] = fbuf[i * ch + 1]
                    }
                    data = mix
                }
                val len = n * outCh
                if (useFloat && Build.VERSION.SDK_INT >= 21) {
                    var off = 0
                    while (off < len && !released && seekReq < 0) {
                        val w = t.write(data, off, len - off, AudioTrack.WRITE_BLOCKING)
                        if (w <= 0) break
                        off += w
                    }
                } else {
                    for (i in 0 until len) {
                        // TPDF dither: two uniform randoms -> triangular noise of ±1 LSB, masks quantisation distortion
                        seed = seed * 1664525 + 1013904223
                        val r1 = (seed ushr 8) * (1f / 16777216f)
                        seed = seed * 1664525 + 1013904223
                        val r2 = (seed ushr 8) * (1f / 16777216f)
                        val v = Math.round(data[i] * 32767f + (r1 - r2)).coerceIn(-32768, 32767)
                        sbuf[i] = v.toShort()
                    }
                    var off = 0
                    while (off < len && !released && seekReq < 0) {
                        val w = t.write(sbuf, off, len - off)
                        if (w <= 0) break
                        off += w
                    }
                }
            }
        } catch (e: Exception) {
            if (!released) main.post { onError?.invoke("Decoder error: ${e.message}") }
        }
    }

    /** Let the last buffer play out before reporting completion. */
    private fun drain(t: AudioTrack) {
        try { t.stop() } catch (_: Exception) { return }
        var last = -1L
        var still = 0
        while (!released && seekReq < 0 && still < 6) {
            val h = head(t)
            if (h == last) still++ else still = 0
            last = h
            Thread.sleep(50)
        }
    }

    override fun start() {
        val t = track ?: return
        playing = true
        try {
            t.play()
            applyParams()
        } catch (_: Exception) {}
        synchronized(lock) { lock.notifyAll() }
    }

    override fun pause() {
        playing = false
        try { track?.pause() } catch (_: Exception) {}
    }

    override val isPlaying: Boolean get() = playing

    override fun position(): Long {
        val t = track ?: return 0
        return (basePosMs + (head(t) - baseHead) * 1000L / outRate).coerceIn(0, duration().coerceAtLeast(1))
    }

    override fun duration(): Long = src?.durationMs ?: 0

    override fun seekTo(ms: Long) {
        seekReq = ms.coerceAtLeast(0)
        basePosMs = seekReq
        if (!playing) try { track?.flush() } catch (_: Exception) {}
        synchronized(lock) { lock.notifyAll() }
    }

    override fun setVolume(v: Float) {
        val t = track ?: return
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                t.setVolume(v)
            } else {
                @Suppress("DEPRECATION")
                t.setStereoVolume(v, v)
            }
        } catch (_: Exception) {}
    }

    override fun setSpeedPitch(speed: Float, pitch: Float): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        this.speed = speed; this.pitch = pitch
        applyParams()
        return true
    }

    private fun applyParams() {
        if (Build.VERSION.SDK_INT >= 23) {
            try { track?.playbackParams = PlaybackParams().setSpeed(speed).setPitch(pitch) } catch (_: Exception) {}
        }
    }

    override fun attachAux(effectId: Int, level: Float) {
        try {
            track?.attachAuxEffect(effectId)
            track?.setAuxEffectSendLevel(level)
        } catch (_: Exception) {}
    }

    override fun setNext(next: Engine?): Boolean = false

    override fun release() {
        released = true
        playing = false
        synchronized(lock) { lock.notifyAll() }
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        try { thread?.join(500) } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        file?.close()
        track = null
    }
}
