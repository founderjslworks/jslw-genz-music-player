package com.jslw.genz.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.jslw.genz.R
import com.jslw.genz.data.Library
import com.jslw.genz.data.LibraryDb
import com.jslw.genz.model.Song
import com.jslw.genz.playback.Player
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.util.ArtLoader
import com.jslw.genz.util.dpi
import com.jslw.genz.util.formatTime
import com.jslw.genz.util.formatTotal
import com.jslw.genz.util.toast

/** Bottom sheets and dialogs shared by several screens. */
object Sheets {

    class Option(val icon: Int, val title: String, val action: () -> Unit)

    private fun optionView(a: Activity, parent: LinearLayout, o: Option, dismiss: () -> Unit) {
        val v = LayoutInflater.from(a).inflate(R.layout.item_row, parent, false)
        v.findViewById<android.widget.ImageView>(R.id.rowIcon).also { com.jslw.genz.skin.Icons.set(it, o.icon); it.visibility = View.VISIBLE }
        v.findViewById<TextView>(R.id.rowTitle).text = o.title
        v.setOnClickListener { dismiss(); o.action() }
        SkinPainter.apply(v)
        parent.addView(v)
    }

    /** Long-press / ⋮ menu for a song. [extra] options (e.g. "Remove from playlist") come first. */
    fun songOptions(a: AppCompatActivity, s: Song, extra: List<Option> = emptyList()) {
        val d = BottomSheetDialog(a)
        val v = LayoutInflater.from(a).inflate(R.layout.sheet_song_options, null)
        ArtLoader.load(v.findViewById(R.id.optArt), s, a.dpi(112))
        v.findViewById<TextView>(R.id.optTitle).text = s.title
        v.findViewById<TextView>(R.id.optSubtitle).text = "${s.artist}\n${s.album}"
        val box = v.findViewById<LinearLayout>(R.id.optContainer)
        val fav = LibraryDb.isFavorite(s.id)
        val opts = ArrayList<Option>(extra)
        opts += Option(R.drawable.ic_play_next, "Play next") { Player.with(a) { it.playNext(listOf(s)) }; a.toast("Playing next") }
        opts += Option(R.drawable.ic_queue, "Add to queue") { Player.with(a) { it.addToQueue(listOf(s)) }; a.toast("Added to queue") }
        opts += Option(R.drawable.ic_playlist_add, "Add to playlist…") { addToPlaylist(a, listOf(s)) }
        opts += Option(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border, if (fav) "Remove from favorites" else "Add to favorites") {
            LibraryDb.toggleFavorite(s.id); Player.dispatch()
        }
        opts += Option(R.drawable.ic_album, "Go to album") { openInMain(a, MainActivity.EXTRA_ALBUM, Library.albumOf(s)?.id ?: s.albumId) }
        opts += Option(R.drawable.ic_artist, "Go to artist") { openInMain(a, MainActivity.EXTRA_ARTIST, s.artist) }
        opts += Option(R.drawable.ic_edit, "Edit tags & cover") {
            a.startActivity(Intent(a, TagEditorActivity::class.java).putExtra("song", s.id))
        }
        opts += Option(R.drawable.ic_cut, "Make ringtone") {
            a.startActivity(Intent(a, RingtoneCutterActivity::class.java).putExtra("song", s.id))
        }
        opts += Option(R.drawable.ic_info, "File info") { fileInfo(a, s) }
        if (s.id > 0) opts += Option(R.drawable.ic_export, "Share file") {
            val i = Intent(Intent.ACTION_SEND).setType(s.mime.ifBlank { "audio/*" })
                .putExtra(Intent.EXTRA_STREAM, s.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(Intent.createChooser(i, "Share ${s.title}"))
        }
        for (o in opts) optionView(a, box, o) { d.dismiss() }
        SkinPainter.apply(v)
        d.setContentView(v)
        d.show()
    }

    fun openInMain(a: Activity, key: String, value: Any) {
        val i = Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        when (value) {
            is Long -> i.putExtra(key, value)
            is String -> i.putExtra(key, value)
        }
        a.startActivity(i)
        if (a !is MainActivity) a.finish()
    }

    fun fileInfo(a: Activity, s: Song) {
        Thread {
            val info = com.jslw.genz.audio.Formats.probe(a, s)
            val plays = LibraryDb.playCount(s.id)
            a.runOnUiThread {
                val msg = buildString {
                    append("Format: ").append(info?.label() ?: s.ext.uppercase()).append('\n')
                    append("Length: ").append(formatTime(s.duration)).append('\n')
                    append("Size: ").append(String.format(java.util.Locale.US, "%.1f MB", s.size / 1048576.0)).append('\n')
                    if (s.year > 0) append("Year: ").append(s.year).append('\n')
                    if (s.genre.isNotBlank()) append("Genre: ").append(s.genre).append('\n')
                    append("Played: ").append(plays).append(" times\n")
                    append("Path: ").append(s.path)
                    if (s.needsPcmEngine) append("\n\nDecoded by GEN-Z's own engine (not supported natively by Android).")
                }
                MaterialAlertDialogBuilder(a).setTitle(s.title).setMessage(msg).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    // ----------------------------------------------------------------- playlists
    fun addToPlaylist(a: Activity, songs: List<Song>) {
        val lists = LibraryDb.playlists()
        val names = arrayOf("＋ New playlist…") + lists.map { "${it.name}  (${it.count})" }
        MaterialAlertDialogBuilder(a)
            .setTitle("Add ${if (songs.size == 1) "\"${songs[0].title}\"" else "${songs.size} songs"} to")
            .setItems(names) { _, which ->
                if (which == 0) askText(a, "New playlist", "", "Create") { name ->
                    val id = LibraryDb.createPlaylist(name)
                    LibraryDb.addToPlaylist(id, songs.map { it.id })
                    a.toast("Added to $name")
                    Player.dispatch()
                } else {
                    val p = lists[which - 1]
                    LibraryDb.addToPlaylist(p.id, songs.map { it.id })
                    a.toast("Added to ${p.name}")
                    Player.dispatch()
                }
            }.show()
    }

    fun askText(a: Activity, title: String, initial: String, ok: String, done: (String) -> Unit) {
        val et = EditText(a).apply {
            setText(initial); setSelection(initial.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
        }
        val wrap = FrameLayout(a).apply {
            setPadding(a.dpi(22), a.dpi(8), a.dpi(22), 0)
            addView(et)
        }
        MaterialAlertDialogBuilder(a).setTitle(title).setView(wrap)
            .setPositiveButton(ok) { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton("Cancel", null).show()
    }

    // ----------------------------------------------------------------- queue
    @SuppressLint("NotifyDataSetChanged")
    fun queue(a: AppCompatActivity) {
        val svc = Player.service ?: return
        val d = BottomSheetDialog(a)
        val v = LayoutInflater.from(a).inflate(R.layout.sheet_queue, null)
        val list = v.findViewById<RecyclerView>(R.id.queueList)
        val info = v.findViewById<TextView>(R.id.queueInfo)
        val adapter = RowAdapter()
        list.layoutManager = LinearLayoutManager(a)
        list.adapter = adapter

        fun rebuild() {
            val q = svc.queue
            info.text = "${q.size} songs · ${formatTotal(q.sumOf { it.duration })}" +
                (if (svc.shuffle) " · shuffled" else "")
            adapter.rows = q.mapIndexed { i, s ->
                Row(
                    title = s.title, subtitle = s.artist, extra = formatTime(s.duration), art = s, song = s,
                    drag = true, activated = i == svc.index,
                    onClick = { svc.playAt(i) },
                )
            }
        }
        rebuild()

        val ith = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            var from = -1; var to = -1
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val f = vh.bindingAdapterPosition; val t = target.bindingAdapterPosition
                if (from == -1) from = f
                to = t
                adapter.moveItem(f, t)
                return true
            }
            override fun isLongPressDragEnabled() = false
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (from >= 0 && to >= 0 && from != to) svc.move(from, to)
                from = -1; to = -1
                rebuild()
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                svc.removeAt(vh.bindingAdapterPosition)
                rebuild()
            }
        })
        ith.attachToRecyclerView(list)
        adapter.itemTouchHelper = ith

        v.findViewById<View>(R.id.queueShuffle).setOnClickListener { svc.setShuffleMode(!svc.shuffle); rebuild() }
        v.findViewById<View>(R.id.queueSave).setOnClickListener {
            askText(a, "Save queue as playlist", svc.contextLabel, "Save") { name ->
                val id = LibraryDb.createPlaylist(name)
                LibraryDb.addToPlaylist(id, svc.queue.map { it.id })
                a.toast("Saved \"$name\"")
            }
        }
        val listener = Player.Listener { rebuild() }
        Player.add(listener)
        d.setOnDismissListener { Player.remove(listener) }
        SkinPainter.apply(v)
        d.setContentView(v)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.show()
        list.scrollToPosition((svc.index - 2).coerceAtLeast(0))
    }

    // ----------------------------------------------------------------- sleep timer / speed
    fun sleepTimer(a: Activity) {
        val svc = Player.service ?: return
        val v = LayoutInflater.from(a).inflate(R.layout.dialog_sliders, null)
        val l1 = v.findViewById<TextView>(R.id.dlgLabel1)
        val s1 = v.findViewById<Slider>(R.id.dlgSlider1)
        v.findViewById<View>(R.id.dlgLabel2).visibility = View.GONE
        v.findViewById<View>(R.id.dlgSlider2).visibility = View.GONE
        val cb = v.findViewById<CheckBox>(R.id.dlgCheck)
        cb.text = "Finish the current song first"
        cb.isChecked = svc.sleepFinishSong
        s1.valueFrom = 5f; s1.valueTo = 120f; s1.stepSize = 5f
        val remaining = svc.sleepRemainingMs()
        s1.value = if (remaining > 0) ((remaining / 60000 + 4) / 5 * 5).toFloat().coerceIn(5f, 120f) else 30f
        fun label() { l1.text = "Stop playback in ${s1.value.toInt()} minutes" }
        label()
        s1.addOnChangeListener { _, _, _ -> label() }
        SkinPainter.apply(s1.apply { tag = "skin_slider" })
        val b = MaterialAlertDialogBuilder(a).setTitle("Sleep timer").setView(v)
            .setPositiveButton("Start") { _, _ ->
                svc.setSleepTimer(s1.value.toInt(), cb.isChecked)
                a.toast("Sleep timer: ${s1.value.toInt()} min")
            }
            .setNegativeButton("Close", null)
        if (remaining > 0) b.setNeutralButton("Turn off") { _, _ -> svc.cancelSleepTimer(); a.toast("Sleep timer off") }
        b.show()
    }

    fun speedPitch(a: Activity) {
        val svc = Player.service ?: return
        if (!svc.canChangeSpeed) { a.toast("Speed & pitch need Android 6.0 or newer"); return }
        val v = LayoutInflater.from(a).inflate(R.layout.dialog_sliders, null)
        val l1 = v.findViewById<TextView>(R.id.dlgLabel1)
        val l2 = v.findViewById<TextView>(R.id.dlgLabel2)
        val s1 = v.findViewById<Slider>(R.id.dlgSlider1)
        val s2 = v.findViewById<Slider>(R.id.dlgSlider2)
        val cb = v.findViewById<CheckBox>(R.id.dlgCheck)
        cb.text = "Link pitch to speed (vinyl / tape style)"
        for (s in listOf(s1, s2)) { s.valueFrom = 0.5f; s.valueTo = 2.0f; s.stepSize = 0.05f; s.tag = "skin_slider"; SkinPainter.apply(s) }
        val prefs = com.jslw.genz.data.Prefs
        s1.value = (Math.round(prefs.speed * 20) / 20f).coerceIn(0.5f, 2f)
        s2.value = (Math.round(prefs.pitch * 20) / 20f).coerceIn(0.5f, 2f)
        fun upd() {
            if (cb.isChecked) s2.value = s1.value
            l1.text = String.format(java.util.Locale.US, "Speed  %.2f×", s1.value)
            l2.text = String.format(java.util.Locale.US, "Pitch  %.2f×", s2.value)
            svc.setSpeedPitch(s1.value, s2.value)
        }
        upd()
        s1.addOnChangeListener { _, _, fromUser -> if (fromUser) upd() }
        s2.addOnChangeListener { _, _, fromUser -> if (fromUser) { cb.isChecked = false; upd() } }
        cb.setOnCheckedChangeListener { _, _ -> upd() }
        MaterialAlertDialogBuilder(a).setTitle("Speed & pitch").setView(v)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ -> svc.setSpeedPitch(1f, 1f) }
            .show()
    }

    fun skinColor() = SkinManager.current.accent
}
