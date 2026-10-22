package com.jslw.genz.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.RangeSlider
import com.jslw.genz.R
import com.jslw.genz.data.Library
import com.jslw.genz.model.Song
import com.jslw.genz.playback.Player
import com.jslw.genz.ui.views.WaveformView
import com.jslw.genz.util.FrameTicker
import com.jslw.genz.util.RingtoneMaker
import com.jslw.genz.util.formatTime
import com.jslw.genz.util.toast
import java.io.File

/** Ringtone cutter / maker: pick a range on the waveform, preview it, save it as ringtone, notification or alarm. */
class RingtoneCutterActivity : BaseActivity() {
    private lateinit var song: Song
    private lateinit var range: RangeSlider
    private lateinit var wave: WaveformView
    private lateinit var startTv: TextView
    private lateinit var endTv: TextView
    private lateinit var lenTv: TextView
    private lateinit var previewBtn: MaterialButton
    private var preview: MediaPlayer? = null
    private var pendingDefault: Pair<Uri, Int>? = null
    private val frames = FrameTicker { onFrame() }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) save() else toast("Storage permission is needed to save ringtones on this Android version")
    }

    private val writeSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val p = pendingDefault ?: return@registerForActivityResult
        pendingDefault = null
        if (RingtoneMaker.canSetDefault(this) && RingtoneMaker.setDefault(this, p.first, p.second)) toast("Set as default ✓")
        else toast("Saved, but not set as default (permission not given)")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        song = Library.song(intent.getLongExtra("song", 0)) ?: run { finish(); return }
        setContentView(R.layout.activity_ringtone)
        setupScreen(findViewById(R.id.root))
        header("Ringtone maker")
        findViewById<TextView>(R.id.rtSong).text = "${song.title} — ${song.artist}"
        range = findViewById(R.id.rtRange)
        wave = findViewById(R.id.rtWave)
        startTv = findViewById(R.id.rtStart)
        endTv = findViewById(R.id.rtEnd)
        lenTv = findViewById(R.id.rtLength)
        previewBtn = findViewById(R.id.rtPreview)

        val dur = song.duration.coerceAtLeast(1000).toFloat()
        range.valueFrom = 0f
        range.valueTo = dur
        range.minSeparation = 1000f
        val start = (dur * 0.3f).coerceAtMost(dur - 1000f)
        range.values = listOf(start, (start + 30_000f).coerceAtMost(dur))
        range.addOnChangeListener { _, _, _ -> updateRange() }
        updateRange()

        if (!RingtoneMaker.supported(song)) {
            findViewById<TextView>(R.id.rtNote).text = "This format (${song.ext.uppercase()}) can't be cut without re-encoding. " +
                "Ringtone maker supports MP3, M4A/AAC and WAV."
            findViewById<MaterialButton>(R.id.rtSave).isEnabled = false
        }
        findViewById<MaterialButton>(R.id.rtSave).setOnClickListener { save() }
        (wave.parent as? android.view.ViewGroup)?.let { com.jslw.genz.util.Stagger.enter(it) }
        previewBtn.setOnClickListener { togglePreview() }

        Thread {
            val peaks = if (RingtoneMaker.supported(song)) RingtoneMaker.waveform(this, song, 120) else null
            runOnUiThread { if (!isFinishing) wave.peaks = peaks }
        }.start()
    }

    private fun updateRange() {
        val v = range.values
        val dur = range.valueTo
        startTv.text = formatTime(v[0].toLong())
        endTv.text = formatTime(v[1].toLong())
        lenTv.text = formatTime((v[1] - v[0]).toLong())
        wave.select(v[0] / dur, v[1] / dur)
    }

    private fun togglePreview() {
        if (preview != null) { stopPreview(); return }
        Player.service?.pause(false)
        try {
            val mp = MediaPlayer()
            mp.setDataSource(this, song.uri)
            mp.prepare()
            mp.seekTo(range.values[0].toInt())
            mp.start()
            preview = mp
            previewBtn.text = "Stop"
            frames.start()
        } catch (e: Exception) { toast("Can't preview this file") }
    }

    private fun onFrame() {
        val mp = preview ?: return
        val pos = mp.currentPosition.toFloat()
        wave.playhead = pos / range.valueTo
        wave.invalidate()
        if (pos >= range.values[1] || !mp.isPlaying) stopPreview()
    }

    private fun stopPreview() {
        frames.stop()
        if (!::previewBtn.isInitialized) return
        preview?.release()
        preview = null
        previewBtn.text = "Preview"
        wave.playhead = -1f
        wave.invalidate()
    }

    private fun save() {
        if (Build.VERSION.SDK_INT in 23..28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return }
        stopPreview()
        val type = when (findViewById<RadioGroup>(R.id.rtType).checkedRadioButtonId) {
            R.id.rtNotification -> RingtoneManager.TYPE_NOTIFICATION
            R.id.rtAlarm -> RingtoneManager.TYPE_ALARM
            else -> RingtoneManager.TYPE_RINGTONE
        }
        val setDefault = findViewById<CheckBox>(R.id.rtSetDefault).isChecked
        val fade = findViewById<CheckBox>(R.id.rtFade).isChecked
        val (a, b) = range.values.let { it[0].toLong() to it[1].toLong() }
        toast("Cutting…")
        Thread {
            val tmp = File(cacheDir, "cut." + RingtoneMaker.outExt(song))
            val ok = RingtoneMaker.cut(this, song, a, b, fade, tmp)
            val uri = if (ok) try {
                RingtoneMaker.publish(this, tmp, "${song.title} (ringtone)", RingtoneMaker.outExt(song), RingtoneMaker.mime(song), type)
            } catch (e: Exception) { null } else null
            tmp.delete()
            runOnUiThread {
                if (uri == null) { toast("Couldn't create the ringtone"); return@runOnUiThread }
                if (!setDefault) { toast("Saved to your ${if (type == RingtoneManager.TYPE_ALARM) "alarms" else if (type == RingtoneManager.TYPE_NOTIFICATION) "notification sounds" else "ringtones"} ✓"); return@runOnUiThread }
                if (RingtoneMaker.canSetDefault(this)) {
                    toast(if (RingtoneMaker.setDefault(this, uri, type)) "Saved and set as default ✓" else "Saved ✓")
                } else {
                    toast("Saved ✓")
                    Sheets.explainPermission(this, "Allow \"Modify system settings\"?",
                        "To make your new sound the phone's default ringtone, notification or alarm, Android requires the " +
                            "\"Modify system settings\" permission. GEN-Z uses it only to set the sound you just made.") {
                        pendingDefault = uri to type
                        writeSettings.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                    }
                }
            }
        }.start()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }
}
