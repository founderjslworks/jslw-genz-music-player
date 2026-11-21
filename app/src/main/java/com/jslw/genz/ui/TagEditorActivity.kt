package com.jslw.genz.ui

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.jslw.genz.R
import com.jslw.genz.audio.TagIO
import com.jslw.genz.data.Library
import com.jslw.genz.data.LibraryDb
import com.jslw.genz.model.Song
import com.jslw.genz.model.TagOverride
import com.jslw.genz.playback.Player
import com.jslw.genz.util.ArtLoader
import com.jslw.genz.util.dpi
import com.jslw.genz.util.toast
import java.io.ByteArrayOutputStream

/** Edit title / artist / album / genre / year / track and the cover. Optionally writes ID3 tags into MP3 files. */
class TagEditorActivity : BaseActivity() {
    private lateinit var song: Song
    private lateinit var fTitle: EditText
    private lateinit var fArtist: EditText
    private lateinit var fAlbum: EditText
    private lateinit var fAlbumArtist: EditText
    private lateinit var fGenre: EditText
    private lateinit var fYear: EditText
    private lateinit var fTrack: EditText
    private lateinit var writeFile: CheckBox
    private lateinit var art: ImageView
    private var newCover: ByteArray? = null
    private var clearCover = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val bmp = ArtLoader.decodeBytes(bytes, 1000) ?: throw IllegalArgumentException()
            // store as a reasonably sized JPEG (keeps tags and the cache small)
            val out = ByteArrayOutputStream()
            scaleDown(bmp, 1000).compress(Bitmap.CompressFormat.JPEG, 90, out)
            newCover = out.toByteArray()
            clearCover = false
            art.setImageBitmap(BitmapFactory.decodeByteArray(newCover, 0, newCover!!.size))
            art.scaleType = ImageView.ScaleType.CENTER_CROP
        } catch (e: Exception) { toast("Couldn't read that picture") }
    }

    private val writeConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) writeIntoFile() else finishSave(false)
    }

    private val legacyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) writeIntoFile() else finishSave(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Library.song(intent.getLongExtra("song", 0)) ?: run { finish(); return }
        song = s
        setContentView(R.layout.activity_tag_editor)
        setupScreen(findViewById(R.id.root))
        header("Edit tags", R.drawable.ic_check) { save() }
        fTitle = findViewById(R.id.tagTitle); fArtist = findViewById(R.id.tagArtist); fAlbum = findViewById(R.id.tagAlbum)
        fAlbumArtist = findViewById(R.id.tagAlbumArtist); fGenre = findViewById(R.id.tagGenre)
        fYear = findViewById(R.id.tagYear); fTrack = findViewById(R.id.tagTrack)
        writeFile = findViewById(R.id.tagWriteFile)
        art = findViewById(R.id.tagArt)
        fTitle.setText(s.title); fArtist.setText(s.artist); fAlbum.setText(s.album)
        fAlbumArtist.setText(s.albumArtist); fGenre.setText(s.genre)
        if (s.year > 0) fYear.setText(s.year.toString())
        if (s.track > 0) fTrack.setText((s.track % 1000).toString())
        ArtLoader.load(art, s, dpi(220), large = true)
        (fTitle.parent as? android.view.ViewGroup)?.let { com.jslw.genz.util.Stagger.enter(it, step = 30) }
        val mp3 = s.ext == "mp3"
        writeFile.isEnabled = mp3
        if (!mp3) writeFile.text = "Writing into the file is available for MP3 (edits are still saved in GEN-Z)"
        findViewById<android.view.View>(R.id.tagPickArt).setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        findViewById<android.view.View>(R.id.tagFindOnline).setOnClickListener { findOnline(null) }
        if (intent.getBooleanExtra("online", false) && savedInstanceState == null) findOnline(null)
        findViewById<android.view.View>(R.id.tagClearArt).setOnClickListener {
            newCover = null; clearCover = true
            ArtLoader.placeholder(art, true)
        }
    }

    // ---------------------------------------------------------------- find details & cover online
    private fun findOnline(query: String?) {
        val wait = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Searching online…").setMessage(query ?: (song.title + if (song.artist.isNotBlank()) " – " + song.artist else ""))
            .setCancelable(true).show()
        Thread {
            val found = try { com.jslw.genz.util.OnlineTags.search(song, query, applicationContext) } catch (_: Exception) { null }
            runOnUiThread {
                wait.dismiss()
                if (isFinishing) return@runOnUiThread
                when {
                    found == null -> toast("Couldn't reach the music catalogues – check your internet connection")
                    found.isEmpty() -> askQuery("Nothing found. Try other words, or paste the song's YouTube link:")
                    else -> pickMatch(found)
                }
            }
        }.start()
    }

    private fun askQuery(title: String) {
        // a YouTube link on the clipboard (copied from the YouTube app's Share › Copy link) is filled in for you
        val clip = try {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) { null }
        val link = clip?.takeIf { com.jslw.genz.util.YouTubeMeta.idFromText(it) != null }?.trim()
        Sheets.askText(this, title, link ?: com.jslw.genz.util.OnlineTags.defaultTerm(song), "Search") { q -> findOnline(q) }
    }

    private fun pickMatch(list: List<com.jslw.genz.util.OnlineTags.Match>) {
        val d = resources.displayMetrics.density
        val thumbs = HashMap<Int, Bitmap>()
        val sk = com.jslw.genz.skin.SkinManager.current
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(i: Int) = list[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val m = list[i]
                val row = (cv as? android.widget.LinearLayout) ?: android.widget.LinearLayout(this@TagEditorActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((20 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
                    addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, android.widget.LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
                    addView(android.widget.TextView(context).apply { setPadding((14 * d).toInt(), 0, 0, 0); textSize = 14f },
                        android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                val iv = row.getChildAt(0) as ImageView
                val tv = row.getChildAt(1) as android.widget.TextView
                tv.text = android.text.SpannableStringBuilder().apply {
                    append(m.title, android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0)
                    append("\n" + m.artist + "\n" + m.album + (if (m.year > 0) " · ${m.year}" else ""))
                    append("\n" + m.source, android.text.style.ForegroundColorSpan(sk.accent), 0)
                }
                tv.setTextColor(sk.text)
                iv.tag = i
                val b = thumbs[i]
                if (b != null) iv.setImageBitmap(b) else {
                    iv.setImageDrawable(null); iv.setBackgroundColor(sk.surface)
                    m.thumbUrl?.let { url ->
                        Thread {
                            val bmp = com.jslw.genz.util.OnlineTags.get(url)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            if (bmp != null) runOnUiThread { thumbs[i] = bmp; if (iv.tag == i) iv.setImageBitmap(bmp) }
                        }.start()
                    }
                }
                return row
            }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Pick the right one")
            .setAdapter(adapter) { _, i -> apply(list[i]) }
            .setNeutralButton("Search again") { _, _ -> askQuery("Search for:") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun apply(m: com.jslw.genz.util.OnlineTags.Match) {
        if (m.source == "Deezer" && (m.year == 0 || m.genre.isEmpty())) {
            // fetch the album's year and genre first
            Thread { try { com.jslw.genz.util.OnlineTags.complete(m) } catch (_: Exception) {}; runOnUiThread { if (!isFinishing) fill(m) } }.start()
        } else fill(m)
    }

    private fun fill(m: com.jslw.genz.util.OnlineTags.Match) {
        if (m.title.isNotBlank()) fTitle.setText(m.title)
        if (m.artist.isNotBlank()) fArtist.setText(m.artist)
        if (m.album.isNotBlank()) fAlbum.setText(m.album)
        if (m.albumArtist.isNotBlank()) fAlbumArtist.setText(m.albumArtist)
        if (m.genre.isNotBlank()) fGenre.setText(m.genre)
        if (m.year > 0) fYear.setText(m.year.toString())
        if (m.track > 0) fTrack.setText(m.track.toString())
        // opened from the song menu ("Find cover & details online"): save straight away
        val quick = intent.getBooleanExtra("online", false)
        val url = m.coverUrl ?: run { if (quick) save() else toast("Details filled in – tap ✓ to save"); return }
        toast("Downloading the cover…")
        Thread {
            val bmp = if (url.startsWith("yt:")) com.jslw.genz.util.YouTubeMeta.cover(url.removePrefix("yt:"))
                else com.jslw.genz.util.OnlineTags.get(url)?.let { ArtLoader.decodeBytes(it, 1200) }
            val out = ByteArrayOutputStream()
            bmp?.let { scaleDown(it, 1200).compress(Bitmap.CompressFormat.JPEG, 92, out) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (bmp == null) { toast("Details filled in, but the cover couldn't be downloaded"); return@runOnUiThread }
                newCover = out.toByteArray(); clearCover = false
                art.setImageBitmap(bmp); art.scaleType = ImageView.ScaleType.CENTER_CROP
                if (quick) { save(); return@runOnUiThread }
                toast("Cover and details filled in – tap ✓ to save" + if (writeFile.isEnabled) " (tick \"write into file\" to keep them in the song)" else "")
            }
        }.start()
    }

    private fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val s = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    private fun save() {
        val o = TagOverride(
            song.id,
            fTitle.text.toString().trim().ifEmpty { null },
            fArtist.text.toString().trim().ifEmpty { null },
            fAlbum.text.toString().trim().ifEmpty { null },
            fAlbumArtist.text.toString().trim().ifEmpty { null },
            fGenre.text.toString().trim().ifEmpty { null },
            fYear.text.toString().trim().toIntOrNull(),
            fTrack.text.toString().trim().toIntOrNull()?.let { t -> (song.track / 1000) * 1000 + t },
        )
        LibraryDb.saveTagOverride(o)
        // a song that had no real album, or whose album is being changed here, keeps the cover to itself:
        // otherwise it would land on the album id it shares with other songs and show up on them too
        val ownCover = com.jslw.genz.util.OnlineArt.ownCover(song) || (o.album != null && !o.album.equals(song.album, true))
        // show the new details everywhere at once; the full library rebuild follows in the background
        o.title?.let { song.title = it }; o.artist?.let { song.artist = it }; o.album?.let { song.album = it }
        o.albumArtist?.let { song.albumArtist = it }; o.genre?.let { song.genre = it }
        o.year?.let { song.year = it }; o.track?.let { song.track = it }
        if (newCover != null && ownCover) ArtLoader.setSongCover(this, song, newCover)
        else {
            val cover = ArtLoader.customCoverFile(this, song)
            if (newCover != null) cover.writeBytes(newCover!!)
            if (clearCover) { cover.delete(); ArtLoader.setSongCover(this, song, null) }
            if (newCover != null || clearCover) ArtLoader.invalidate(song.albumId)
        }

        if (!(writeFile.isChecked && writeFile.isEnabled)) { finishSave(true); return }
        // Ask Android for permission to modify the file where needed
        when {
            song.id < 0 -> writeIntoFile() // folder chosen by the user: we already have access
            Build.VERSION.SDK_INT >= 30 -> {
                val pi = MediaStore.createWriteRequest(contentResolver, listOf(song.uri))
                writeConsent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }
            Build.VERSION.SDK_INT == 29 -> writeIntoFile()
            Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ->
                legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else -> writeIntoFile()
        }
    }

    private fun writeIntoFile() {
        val cover = newCover
        Thread {
            val result: Any = try {
                TagIO.writeMp3(
                    this, song.uri,
                    fTitle.text.toString().trim(), fArtist.text.toString().trim(), fAlbum.text.toString().trim(),
                    fAlbumArtist.text.toString().trim(), fGenre.text.toString().trim(), fYear.text.toString().trim(),
                    fTrack.text.toString().trim(), cover,
                )
            } catch (e: Exception) { e }
            runOnUiThread {
                when {
                    result == true -> { toast("Tags written to the file"); finishSave(true) }
                    Build.VERSION.SDK_INT == 29 && result is RecoverableSecurityException ->
                        writeConsent.launch(IntentSenderRequest.Builder(result.userAction.actionIntent.intentSender).build())
                    else -> { toast("Couldn't write the file – edits saved in GEN-Z only"); finishSave(true) }
                }
            }
        }.start()
    }

    private fun finishSave(ok: Boolean) {
        if (ok) toast("Saved")
        Player.service?.refreshCurrent()
        Player.dispatch()
        Library.load(this)
        finish()
    }
}
