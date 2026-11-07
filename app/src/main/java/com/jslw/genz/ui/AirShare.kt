package com.jslw.genz.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jslw.genz.model.Song
import com.jslw.genz.util.toast
import com.jslw.genz.util.toast

/**
 * Send songs straight to AIR share – JSL WORKS' offline Wi-Fi file sharing app – which opens on its
 * "send to nearby device" screen with the files already picked.
 */
object AirShare {
    const val PACKAGE = "com.jslw.airshare"
    private const val DOWNLOAD = "https://github.com/founderjslworks/jslw-airshare-android/releases/latest"

    fun installed(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (_: Exception) { false }

    /** A URI another app may read: MediaStore and SAF entries; plain files only before Android 7. */
    private fun shareable(s: Song): Uri? = when (s.uri.scheme) {
        "content" -> s.uri
        "file" -> if (Build.VERSION.SDK_INT < 24) s.uri else null
        else -> null
    }

    fun send(a: Activity, songs: List<Song>) {
        val uris = songs.mapNotNull { shareable(it) }
        if (uris.isEmpty()) { a.toast("These files can't be shared"); return }
        if (uris.size < songs.size) a.toast("${songs.size - uris.size} file(s) can't be shared and were left out")
        sendUris(a, uris, "audio/*", label(songs))
    }

    /** Any files (e.g. a theme pack) – content:// URIs this app can grant. */
    fun sendUris(a: Activity, uris: List<Uri>, mime: String, what: String) {
        val i = intent(uris, mime)
        if (!installed(a)) {
            MaterialAlertDialogBuilder(a)
                .setTitle("AIR share isn't installed")
                .setMessage("AIR share sends music to nearby phones and PCs over Wi-Fi – fast, offline and free.")
                .setPositiveButton("Get AIR share") { _, _ ->
                    try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD))) } catch (_: Exception) {}
                }
                .setNeutralButton("Share another way") { _, _ ->
                    a.startActivity(Intent.createChooser(intent(uris, mime), "Share $what"))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        i.setPackage(PACKAGE)
        try { a.startActivity(i) } catch (_: Exception) {
            a.startActivity(Intent.createChooser(intent(uris, mime), "Share $what"))
        }
    }

    fun intent(uris: List<Uri>, mime: String = "audio/*"): Intent {
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.type = mime
        // the grant must cover every file, not only the first
        val clip = ClipData.newRawUri(null, uris[0])
        for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
        i.clipData = clip
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return i
    }

    private fun label(songs: List<Song>) = if (songs.size == 1) songs[0].title else "${songs.size} songs"
}
