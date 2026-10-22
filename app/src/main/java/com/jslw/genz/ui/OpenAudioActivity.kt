package com.jslw.genz.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import com.jslw.genz.data.Library
import com.jslw.genz.data.MediaScanner
import com.jslw.genz.model.Song
import com.jslw.genz.playback.Player

/** "Open with GEN-Z" from a file manager: plays the file right away and shows Now Playing. */
class OpenAudioActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) { finish(); return }
        val song = Library.songs.firstOrNull { it.uri == uri } ?: songFor(uri)
        Player.play(this, listOf(song), 0, "Opened file")
        startActivity(Intent(this, NowPlayingActivity::class.java))
        finish()
        overridePendingTransition(com.jslw.genz.R.anim.slide_up, com.jslw.genz.R.anim.stay)
    }

    private fun songFor(uri: Uri): Song {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
        var size = 0L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    size = c.getLong(1)
                }
            }
        } catch (_: Exception) {}
        var ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) ext = when (contentResolver.getType(uri)) {
            "audio/flac" -> "flac"; "audio/x-wav", "audio/wav" -> "wav"; "audio/aiff", "audio/x-aiff" -> "aiff"
            "audio/mp4" -> "m4a"; "audio/ogg" -> "ogg"; else -> "mp3"
        }
        val dur = try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(this, uri)
            val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release(); d
        } catch (e: Exception) { 0L }
        return Song(
            id = -(MediaScanner.stableHash(uri.toString()) and Long.MAX_VALUE) - 1,
            title = name.substringBeforeLast('.'), artist = Song.UNKNOWN, album = "", albumId = 0,
            albumArtist = "", genre = "", year = 0, track = 0, duration = dur,
            path = "/opened/" + if (name.contains('.')) name else "$name.$ext", folder = "/opened",
            dateAdded = System.currentTimeMillis() / 1000, mime = contentResolver.getType(uri) ?: "audio/*", size = size, uri = uri,
        )
    }
}
