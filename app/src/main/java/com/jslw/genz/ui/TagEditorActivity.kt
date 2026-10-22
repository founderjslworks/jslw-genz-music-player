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
        findViewById<android.view.View>(R.id.tagClearArt).setOnClickListener {
            newCover = null; clearCover = true
            ArtLoader.placeholder(art, true)
        }
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
        val cover = ArtLoader.customCoverFile(this, song.albumId)
        if (newCover != null) cover.writeBytes(newCover!!)
        if (clearCover) cover.delete()
        if (newCover != null || clearCover) ArtLoader.invalidate(song.albumId)

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
        Library.load(this)
        Player.dispatch()
        finish()
    }
}
