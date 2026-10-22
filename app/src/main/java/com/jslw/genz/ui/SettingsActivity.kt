package com.jslw.genz.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jslw.genz.BuildConfig
import com.jslw.genz.R
import com.jslw.genz.audio.BitPerfect
import com.jslw.genz.data.Library
import com.jslw.genz.data.MediaScanner
import com.jslw.genz.data.Prefs
import com.jslw.genz.playback.Player
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.util.toast

/** All settings, built in code so every row follows the active theme file. */
class SettingsActivity : BaseActivity() {

    private sealed class Item {
        class Section(val title: String) : Item()
        class Toggle(val title: String, val summary: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Item()
        class Action(val title: String, val summary: () -> String, val run: () -> Unit) : Item()
    }

    private val items = ArrayList<Item>()
    private lateinit var adapter: Adapter

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
        Prefs.extraFolders = Prefs.extraFolders + uri.toString()
        Library.load(this)
        toast("Folder added – scanning")
        adapter.notifyDataSetChanged()
    }

    private val overlay = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        adapter.notifyDataSetChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_list)
        setupScreen(findViewById(R.id.root))
        header("Settings")
        build()
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = Adapter()
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun build() {
        items += Item.Section("Appearance")
        items += Item.Action("Theme", { SkinManager.current.name }) { startActivity(Intent(this, ThemesActivity::class.java)) }
        items += Item.Toggle("Album-art colours on Now Playing", "Background follows the cover (for themes that allow it)",
            { Prefs.npDynamicColors }, { Prefs.npDynamicColors = it })
        items += Item.Toggle("High refresh rate", "Use the screen's fastest mode (90 / 120 / 144 Hz) for fluid animation",
            { Prefs.highRefresh }, { Prefs.highRefresh = it; recreate() })
        items += Item.Toggle("Keep screen on in Now Playing", "Handy for lyrics or in a dock", { Prefs.keepScreenOn }, { Prefs.keepScreenOn = it })

        items += Item.Section("Playback")
        items += Item.Toggle("Gapless playback", "No silence between tracks (live albums, DJ mixes)", { Prefs.gapless }, { Prefs.gapless = it })
        items += Item.Action("Crossfade", { if (Prefs.crossfadeSec == 0) "Off" else "${Prefs.crossfadeSec} seconds" }) {
            choose("Crossfade", listOf(0, 2, 4, 6, 8, 10, 12), { if (it == 0) "Off" else "$it seconds" }, Prefs.crossfadeSec) { Prefs.crossfadeSec = it }
        }
        items += Item.Action("Volume normalisation (ReplayGain)", { arrayOf("Off", "Per track", "Per album")[Prefs.replayGain.coerceIn(0, 2)] }) {
            choose("Volume normalisation", listOf(0, 1, 2), { arrayOf("Off", "Per track", "Per album")[it] }, Prefs.replayGain) {
                Prefs.replayGain = it
            }
        }
        items += Item.Toggle("Fade in on play", "Smooth start instead of an abrupt one", { Prefs.fadeIn }, { Prefs.fadeIn = it })
        items += Item.Toggle("Fade out on pause", "", { Prefs.fadeOutOnPause }, { Prefs.fadeOutOnPause = it })
        if (Build.VERSION.SDK_INT >= 23) items += Item.Action("Speed & pitch", {
            String.format(java.util.Locale.US, "Speed %.2f× · Pitch %.2f×", Prefs.speed, Prefs.pitch)
        }) { Player.with(this) { Sheets.speedPitch(this) } }
        items += Item.Toggle("Resume when headphones are plugged in", "", { Prefs.resumeOnHeadset }, { Prefs.resumeOnHeadset = it })
        items += Item.Toggle(
            "Bit-perfect USB DAC output",
            if (BitPerfect.supported()) "Android 14+: sends hi-res audio to a USB DAC untouched (effects are bypassed)"
            else "Needs Android 14 or newer",
            { Prefs.bitPerfect },
        ) { on ->
            if (!BitPerfect.supported()) toast("Needs Android 14 or newer")
            else {
                Prefs.bitPerfect = on
                Player.with(this) { s -> s.applyBitPerfect(); toast(s.bitPerfectStatus) }
            }
        }

        items += Item.Section("Lock screen & widgets")
        items += Item.Toggle(
            "Full-screen lock-screen player",
            "Big cover and controls over the lock screen while music plays" +
                (if (Build.VERSION.SDK_INT >= 29) " (needs \"display over other apps\")" else ""),
            { Prefs.lockScreenPlayer },
        ) { on ->
            Prefs.lockScreenPlayer = on
            if (on && Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)) {
                toast("Allow \"display over other apps\" so GEN-Z can show its lock-screen player")
                overlay.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        items += Item.Action("Lock-screen media controls", { "Always on: cover, title and controls on every Android version" }) {}
        items += Item.Action("Add a widget to the home screen", {
            if (com.jslw.genz.widget.Widgets.canPin(this)) "Wide 4×1 · Cover 2×2 · Large 4×2"
            else "Long-press your home screen → Widgets → GEN-Z Music"
        }) {
            if (!com.jslw.genz.widget.Widgets.canPin(this)) {
                toast("Long-press an empty spot on your home screen, choose Widgets, then GEN-Z Music")
            } else {
                MaterialAlertDialogBuilder(this).setTitle("Add widget")
                    .setItems(arrayOf("Wide player (4×1)", "Cover (2×2)", "Large player (4×2)")) { _, w ->
                        if (!com.jslw.genz.widget.Widgets.pin(this, w)) toast("Your launcher didn't accept the widget")
                    }.show()
            }
        }

        items += Item.Section("Library")
        items += Item.Action("Hide short clips", { if (Prefs.minDurationSec == 0) "Show everything" else "Shorter than ${Prefs.minDurationSec} s" }) {
            choose("Hide audio shorter than", listOf(0, 10, 20, 30, 60), { if (it == 0) "Show everything" else "$it seconds" }, Prefs.minDurationSec) {
                Prefs.minDurationSec = it; Library.load(this)
            }
        }
        items += Item.Action("Add music folder…", { "For DSD (.dsf/.dff), AIFF and anything Android doesn't index" }) { addFolder() }
        items += Item.Action("Extra folders", { if (Prefs.extraFolders.isEmpty()) "None" else "${Prefs.extraFolders.size} folder(s) – tap to remove" }) { manageFolders() }
        items += Item.Action("Rescan library", { "${Library.songs.size} songs" }) { Library.load(this); toast("Rescanning…") }

        items += Item.Section("Utilities")
        items += Item.Toggle("Shake to skip", "Shake the phone to jump to the next song (only while playing)", { Prefs.shakeToSkip }, {
            Prefs.shakeToSkip = it; Player.service?.refreshShake()
        })
        items += Item.Action("Shake sensitivity", { arrayOf("", "Very gentle", "Gentle", "Normal", "Firm", "Hard")[Prefs.shakeStrength.coerceIn(1, 5)] }) {
            choose("Shake strength needed", listOf(1, 2, 3, 4, 5), { arrayOf("", "Very gentle", "Gentle", "Normal", "Firm", "Hard")[it] }, Prefs.shakeStrength) {
                Prefs.shakeStrength = it; Player.service?.refreshShake()
            }
        }
        items += Item.Toggle("Floating lyrics", "Show the current synced lyric line over other apps", { Prefs.floatingLyrics }) { on ->
            Prefs.floatingLyrics = on
            if (on && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                overlay.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        items += Item.Action("Sleep timer", { "Stop music after a while" }) { Player.with(this) { Sheets.sleepTimer(this) } }

        items += Item.Section("Power")
        items += Item.Action("Release player after pause", { if (Prefs.autoStopMinutes == 0) "Never" else "After ${Prefs.autoStopMinutes} minutes" }) {
            choose("When paused, release the player after", listOf(5, 15, 30, 60, 0), { if (it == 0) "Never" else "$it minutes" }, Prefs.autoStopMinutes) {
                Prefs.autoStopMinutes = it
            }
        }
        items += Item.Action("How GEN-Z saves battery", { "No background polling · wake lock only while playing" }) {
            MaterialAlertDialogBuilder(this).setTitle("Battery").setMessage(
                "• Nothing runs while music is stopped.\n" +
                    "• The CPU is kept awake only while audio is playing.\n" +
                    "• Sensors (shake to skip) and screen updates stop when paused or when the screen is off.\n" +
                    "• After a long pause the player and its notification are released.\n" +
                    "• GEN-Z never uses the internet."
            ).setPositiveButton("OK", null).show()
        }

        items += Item.Section("About")
        items += Item.Action("GEN-Z ${BuildConfig.VERSION_NAME}", { "100% offline · no internet permission · Android 4.4+" }) {}
    }

    private fun <T> choose(title: String, values: List<T>, label: (T) -> String, current: T, set: (T) -> Unit) {
        val idx = values.indexOf(current)
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setSingleChoiceItems(values.map(label).toTypedArray(), idx) { d, w -> set(values[w]); adapter.notifyDataSetChanged(); d.dismiss() }
            .show()
    }

    private fun addFolder() {
        if (Build.VERSION.SDK_INT >= 21) {
            try { pickFolder.launch(null) } catch (_: Exception) { toast("No folder picker on this device") }
        } else {
            Prefs.extraFolders = Prefs.extraFolders + MediaScanner.defaultMusicDir()
            Library.load(this)
            toast("Scanning the Music folder")
            adapter.notifyDataSetChanged()
        }
    }

    private fun manageFolders() {
        val f = Prefs.extraFolders.toList()
        if (f.isEmpty()) { addFolder(); return }
        val labels = f.map { Uri.decode(it).substringAfterLast(':').ifBlank { it } }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Remove a folder").setItems(labels) { _, w ->
            val u = f[w]
            if (u.startsWith("content://")) try {
                contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            Prefs.extraFolders = Prefs.extraFolders - u
            Library.load(this)
            adapter.notifyDataSetChanged()
        }.setNegativeButton("Close", null).show()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is Item.Section) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            val v = inf.inflate(if (viewType == 1) R.layout.item_section else R.layout.item_setting, parent, false)
            SkinPainter.apply(v)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val it = items[position]) {
                is Item.Section -> (h.itemView as TextView).text = it.title
                is Item.Toggle -> {
                    h.itemView.findViewById<TextView>(R.id.setTitle).text = it.title
                    val sum = h.itemView.findViewById<TextView>(R.id.setSummary)
                    sum.text = it.summary; sum.visibility = if (it.summary.isEmpty()) View.GONE else View.VISIBLE
                    val sw = h.itemView.findViewById<SwitchCompat>(R.id.setSwitch)
                    sw.visibility = View.VISIBLE
                    sw.isChecked = it.get()
                    h.itemView.setOnClickListener { _ -> it.set(!it.get()); sw.isChecked = it.get() }
                }
                is Item.Action -> {
                    h.itemView.findViewById<TextView>(R.id.setTitle).text = it.title
                    val sum = h.itemView.findViewById<TextView>(R.id.setSummary)
                    sum.text = it.summary(); sum.visibility = if (sum.text.isEmpty()) View.GONE else View.VISIBLE
                    h.itemView.findViewById<SwitchCompat>(R.id.setSwitch).visibility = View.GONE
                    h.itemView.setOnClickListener { _ -> it.run() }
                }
            }
        }
    }
}
