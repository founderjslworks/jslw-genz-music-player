package com.jslw.genz.ui

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jslw.genz.data.Library
import com.jslw.genz.model.Song
import com.jslw.genz.playback.Player
import com.jslw.genz.util.toast
import java.io.File

/**
 * Deletes a song file from the phone – handy for voice and call recordings that end up in the library.
 * Android 11+ shows its own "Allow GEN-Z to delete this?" prompt; Android 10 asks once per file;
 * Android 6–9 need the storage permission.
 */
object SongDelete {
    const val REQUEST = 7301
    private val main = Handler(Looper.getMainLooper())
    private var pending: Song? = null

    fun confirm(a: Activity, s: Song) {
        MaterialAlertDialogBuilder(a)
            .setTitle("Delete \"${s.title}\"?")
            .setMessage("The file will be removed from your phone for good.\n\n${s.path}")
            .setPositiveButton("Delete") { _, _ -> delete(a, s) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete(a: Activity, s: Song) {
        if (Build.VERSION.SDK_INT in 23..28 &&
            ContextCompat.checkSelfPermission(a, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST)
            a.toast("Allow storage access, then tap Delete again")
            return
        }
        if (Build.VERSION.SDK_INT >= 30 && isMediaStore(s)) {
            pending = s
            val pi = MediaStore.createDeleteRequest(a.contentResolver, listOf(s.uri))
            launch(a, pi.intentSender)
            return
        }
        val ctx = a.applicationContext
        Thread {
            var ok = false
            var ask: IntentSender? = null
            try { ok = remove(ctx, s) } catch (e: Exception) {
                // Android 10: the file belongs to another app – Android asks the user first
                if (Build.VERSION.SDK_INT >= 29) ask = consent(e)
            }
            main.post {
                when {
                    ok -> finished(a, s)
                    ask != null -> { pending = s; launch(a, ask!!) }
                    else -> a.toast("Couldn't delete \"${s.title}\"")
                }
            }
        }.start()
    }

    /** Forwarded from [BaseActivity.onActivityResult] after Android's own permission prompt. */
    fun onResult(a: Activity, requestCode: Int, resultCode: Int) {
        if (requestCode != REQUEST) return
        val s = pending ?: return
        pending = null
        if (resultCode != Activity.RESULT_OK) return
        if (Build.VERSION.SDK_INT >= 30) { finished(a, s); return }
        // Android 10: permission granted for this file, delete it now
        val ctx = a.applicationContext
        Thread {
            val ok = try { remove(ctx, s) } catch (_: Exception) { false }
            main.post { if (ok) finished(a, s) else a.toast("Couldn't delete \"${s.title}\"") }
        }.start()
    }

    @androidx.annotation.RequiresApi(29)
    private fun consent(e: Exception): IntentSender? =
        (e as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender

    private fun launch(a: Activity, sender: IntentSender) {
        try {
            @Suppress("DEPRECATION")
            a.startIntentSenderForResult(sender, REQUEST, null, 0, 0, 0)
        } catch (_: Exception) { a.toast("Couldn't delete \"${pending?.title ?: "the file"}\"") }
    }

    private fun isMediaStore(s: Song) = s.uri.scheme == "content" && s.uri.authority == MediaStore.AUTHORITY

    private fun remove(ctx: Context, s: Song): Boolean {
        val u = s.uri
        return when {
            u.scheme == "file" -> File(u.path ?: return false).let { it.delete() || !it.exists() }
            Build.VERSION.SDK_INT >= 19 && DocumentsContract.isDocumentUri(ctx, u) -> DocumentsContract.deleteDocument(ctx.contentResolver, u)
            else -> {
                val rows = ctx.contentResolver.delete(u, null, null)
                // before Android 10 also make sure the file itself is gone
                val f = File(s.path)
                if (Build.VERSION.SDK_INT < 29 && f.exists()) f.delete()
                rows > 0 || (Build.VERSION.SDK_INT < 29 && !f.exists())
            }
        }
    }

    private fun finished(a: Activity, s: Song) {
        Player.service?.removeSong(s)
        Library.load(a)
        a.toast("Deleted \"${s.title}\"")
    }
}
