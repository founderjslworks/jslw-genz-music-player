package com.jslw.genz.util

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.jslw.genz.data.LibraryDb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup / restore of GEN-Z: every setting (sound, Audio+, Home, gestures…), themes and skin packs,
 * custom and online covers, playlists, favourites, play counts, tag edits and lyric links.
 * Saved as one ".genzbackup" file (a zip) the user keeps anywhere – restore it after reinstalling or on a new phone.
 * Playlists and favourites are matched to songs by file path, then by title + artist + length, so they
 * survive a reinstall and a move to another phone.
 */
object Backup {
    private const val FORMAT = 1
    private const val PREFS = "genz"
    private const val DB = "genz.db"
    private val SONG_TABLES = listOf("playlist_songs", "favorites", "stats", "tag_overrides", "lyric_links")

    class Summary(val created: Long, val version: String, val device: String)

    fun defaultName(): String =
        "GEN-Z-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".genzbackup"

    // ------------------------------------------------------------------ backup
    fun write(ctx: Context, out: OutputStream) {
        val app = ctx.applicationContext
        ZipOutputStream(out.buffered()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }

            val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
            put("manifest.json", JSONObject()
                .put("app", "GEN-Z Music")
                .put("publisher", "JSL WORKS PVT LTD")
                .put("format", FORMAT)
                .put("version", pkg.versionName)
                .put("created", System.currentTimeMillis())
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .toString(2).toByteArray())

            put("prefs.json", prefsToJson(app).toString().toByteArray())

            // files: themes/skins, covers, online covers, first-seen list…
            val root = app.filesDir
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                z.putNextEntry(ZipEntry("files/$rel"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }

            val db = app.getDatabasePath(DB)
            if (db.exists()) {
                LibraryDb.close()   // flush everything to the file; reopens on next use
                put("db/$DB", db.readBytes())
                put("songs.tsv", songIndex(app).joinToString("\n") { it.toTsv() }.toByteArray())
            }
        }
    }

    private fun prefsToJson(ctx: Context): JSONObject {
        val o = JSONObject()
        for ((k, v) in ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all) {
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    // ------------------------------------------------------------------ restore
    fun peek(ctx: Context, uri: Uri): Summary? = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            ZipInputStream(s.buffered()).use { z ->
                var e = z.nextEntry
                var found: Summary? = null
                while (e != null && found == null) {
                    if (e.name == "manifest.json") {
                        val m = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                        if (m.optString("app") == "GEN-Z Music") {
                            found = Summary(m.optLong("created"), m.optString("version"), m.optString("device"))
                        }
                    }
                    e = z.nextEntry
                }
                found
            }
        }
    } catch (_: Exception) { null }

    /** Restores everything from [uri]. The app must restart afterwards ([restart]). */
    fun restore(ctx: Context, uri: Uri) {
        val app = ctx.applicationContext
        val input: InputStream = app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Can't open the backup file")
        val staging = File(app.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var manifest: JSONObject? = null
        input.use { s ->
            ZipInputStream(s.buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val target = File(staging, e.name)
                        // zip-slip guard: never write outside the staging folder
                        if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) throw SecurityException("Invalid backup entry")
                        target.parentFile?.mkdirs()
                        target.outputStream().use { z.copyTo(it) }
                        if (e.name == "manifest.json") manifest = JSONObject(target.readText())
                    }
                    e = z.nextEntry
                }
            }
        }
        val m = manifest ?: throw IllegalStateException("This isn't a GEN-Z backup")
        if (m.optString("app") != "GEN-Z Music") throw IllegalStateException("This isn't a GEN-Z backup")
        if (m.optInt("format") > FORMAT) throw IllegalStateException("This backup is from a newer GEN-Z – update the app first")

        // settings
        File(staging, "prefs.json").takeIf { it.exists() }?.let { f ->
            val o = JSONObject(f.readText())
            val ed = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> ed.putString(k, e.getString("v"))
                    "ss" -> { val a = e.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            ed.commit()
        }

        // files (themes, covers…)
        File(staging, "files").takeIf { it.isDirectory }?.let { src ->
            app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
            src.copyRecursively(app.filesDir, overwrite = true)
        }

        // library database, with song ids remapped to this phone's library
        File(staging, "db/$DB").takeIf { it.exists() }?.let { src ->
            LibraryDb.close()
            val dst = app.getDatabasePath(DB)
            dst.parentFile?.mkdirs()
            for (suffix in listOf("", "-journal", "-wal", "-shm")) File(dst.path + suffix).delete()
            src.copyTo(dst, overwrite = true)
            val old = File(staging, "songs.tsv").takeIf { it.exists() }?.readLines()?.mapNotNull { SongRef.parse(it) }.orEmpty()
            if (old.isNotEmpty()) remap(dst, old, songIndex(app))
        }
        staging.deleteRecursively()
    }

    private fun remap(dbFile: File, old: List<SongRef>, now: List<SongRef>) {
        val byPath = now.associateBy { it.path.lowercase() }
        val byKey = now.groupBy { it.key() }
        val map = HashMap<Long, Long>()
        for (o in old) {
            val n = byPath[o.path.lowercase()]
                ?: byKey[o.key()]?.minByOrNull { kotlin.math.abs(it.durationMs - o.durationMs) }?.takeIf { kotlin.math.abs(it.durationMs - o.durationMs) < 3000 }
            if (n != null && n.id != o.id) map[o.id] = n.id
        }
        if (map.isEmpty()) return
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            for (t in SONG_TABLES) {
                // two passes through negative ids so remapped rows never collide with each other
                for ((o, n) in map) db.execSQL("UPDATE OR REPLACE $t SET song_id = ? WHERE song_id = ?", arrayOf<Any>(-n - 1, o))
                db.execSQL("UPDATE OR REPLACE $t SET song_id = -song_id - 1 WHERE song_id < 0")
            }
            db.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            try { db.endTransaction() } catch (_: Exception) {}
            db.close()
        }
    }

    /** Restarts GEN-Z so every screen, theme and the player load the restored data. */
    fun restart(ctx: Context) {
        val app = ctx.applicationContext
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { i ->
            app.startActivity(Intent.makeRestartActivityTask(i.component))
        }
        Runtime.getRuntime().exit(0)
    }

    // ------------------------------------------------------------------ song index
    private class SongRef(val id: Long, val path: String, val title: String, val artist: String, val durationMs: Long) {
        fun key() = (title.trim() + "\u0001" + artist.trim()).lowercase()
        fun toTsv() = listOf(id.toString(), clean(path), clean(title), clean(artist), durationMs.toString()).joinToString("\t")

        companion object {
            private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
            fun parse(line: String): SongRef? {
                val p = line.split('\t')
                if (p.size < 5) return null
                return SongRef(p[0].toLongOrNull() ?: return null, p[1], p[2], p[3], p[4].toLongOrNull() ?: 0)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun songIndex(ctx: Context): List<SongRef> {
        val out = ArrayList<SongRef>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION,
        )
        try {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out += SongRef(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4))
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
