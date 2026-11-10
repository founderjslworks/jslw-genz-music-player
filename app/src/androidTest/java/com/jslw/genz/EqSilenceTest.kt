package com.jslw.genz

import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jslw.genz.data.Prefs
import com.jslw.genz.model.Song
import com.jslw.genz.playback.MusicService
import com.jslw.genz.playback.Player
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays a test tone and switches the equalizer / Sound Engine on and off, metering the phone's
 * real output mix (Visualizer on session 0) after every change. Silence = the bug.
 */
@RunWith(AndroidJUnit4::class)
class EqSilenceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext
    private val TAG = "GENZTEST"

    private fun main(block: () -> Unit) = ins.runOnMainSync(block)

    private fun tone(name: String, aiff: Boolean): File {
        val f = File(ctx.filesDir, name)
        val rate = 44100; val secs = 90; val n = rate * secs
        val pcm = ByteBuffer.allocate(n * 4).order(if (aiff) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val v = (Math.sin(2 * Math.PI * 440.0 * i / rate) * 12000).toInt().toShort()
            pcm.putShort(v); pcm.putShort(v)
        }
        FileOutputStream(f).use { o ->
            if (!aiff) {
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
                h.put("data".toByteArray()).putInt(n * 4)
                o.write(h.array())
            } else {
                val h = ByteBuffer.allocate(54).order(ByteOrder.BIG_ENDIAN)
                h.put("FORM".toByteArray()).putInt(46 + n * 4).put("AIFF".toByteArray())
                h.put("COMM".toByteArray()).putInt(18).putShort(2).putInt(n).putShort(16)
                // 44100 as 80-bit IEEE extended
                h.put(byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0))
                h.put("SSND".toByteArray()).putInt(8 + n * 4).putInt(0).putInt(0)
                o.write(h.array())
            }
            o.write(pcm.array())
        }
        return f
    }

    private fun song(id: Long, f: File, mime: String) = Song(
        id, f.name, "Test", "Test", 0, "Test", "", 0, 0, 90_000, f.path, f.parent ?: "", 0, mime, f.length(), Uri.fromFile(f)
    )

    private fun service(): MusicService {
        main { Player.connect(ctx) }
        val end = SystemClock.elapsedRealtime() + 10_000
        while (Player.service == null && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        return Player.service ?: error("MusicService did not bind")
    }

    private lateinit var vis: Visualizer

    /** Loudest output level (millibels, -9600 = silence) over [ms]. */
    private fun meter(ms: Long): Int {
        val m = Visualizer.MeasurementPeakRms()
        var best = -9600
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            vis.getMeasurementPeakRms(m)
            best = maxOf(best, m.mRms)
            SystemClock.sleep(100)
        }
        return best
    }

    @Test
    fun soundKeepsPlayingWhenEffectsToggle() {
        val svc = service()
        vis = Visualizer(0).apply {
            enabled = false
            captureSize = Visualizer.getCaptureSizeRange()[1]
            measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
            scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            enabled = true
        }
        val failures = ArrayList<String>()
        val files = listOf(
            song(900001, tone("tone.wav", false), "audio/wav") to "MediaPlayer (WAV)",
            song(900002, tone("tone.aiff", true), "audio/aiff") to "PcmEngine (AIFF)",
        )
        // UI switch = both together; also each one alone
        val steps = listOf(
            "off" to (false to false), "both on" to (true to true), "both off" to (false to false),
            "eq on" to (true to false), "eq off" to (false to false),
            "engine on" to (false to true), "engine off" to (false to false), "both on again" to (true to true),
        )
        for ((s, label) in files) {
            main { Prefs.eqEnabled = false; Prefs.seOn = false; svc.effectsChanged(); svc.playQueue(listOf(s), 0, "test") }
            SystemClock.sleep(3000)
            for ((name, v) in steps) {
                main { Prefs.eqEnabled = v.first; Prefs.seOn = v.second; svc.effectsChanged() }
                SystemClock.sleep(1500)
                val rms = meter(2500)
                val ok = rms > -6000
                Log.i(TAG, "RESULT engine=$label step=$name rms_mB=$rms ${if (ok) "SOUND" else "SILENT"}")
                if (!ok) failures += "$label / $name"
            }
            main { svc.pause(false) }
            SystemClock.sleep(800)
        }
        vis.release()
        main { Prefs.eqEnabled = false; Prefs.seOn = false; svc.effectsChanged() }
        Log.i(TAG, "SUMMARY failures=${failures.size} $failures")
        assertTrue("Silent after: $failures", failures.isEmpty())
    }
}
