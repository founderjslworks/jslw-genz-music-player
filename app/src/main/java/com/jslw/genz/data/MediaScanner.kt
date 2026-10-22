package com.jslw.genz.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.jslw.genz.audio.AudioFile
import com.jslw.genz.audio.Formats
import com.jslw.genz.audio.TagIO
import com.jslw.genz.model.Song
import java.io.File

/** Reads the device's music: MediaStore (all Android versions) + optional extra folders for DSD/AIFF. */
object MediaScanner {

    fun queryMediaStore(ctx: Context): List<Song> {
        val out = ArrayList<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val modern = Build.VERSION.SDK_INT >= 30
        if (modern) {
            proj += "genre"
            proj += "album_artist"
        }
        if (Build.VERSION.SDK_INT >= 29) proj += "relative_path"

        val genres = if (modern) emptyMap() else legacyGenres(ctx)

        try {
            ctx.contentResolver.query(uri, proj.toTypedArray(), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iGenre = if (modern) c.getColumnIndex("genre") else -1
                val iAlbumArtist = if (modern) c.getColumnIndex("album_artist") else -1
                val iRel = c.getColumnIndex("relative_path")
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val name = c.getString(iName) ?: ""
                    var path = c.getString(iData) ?: ""
                    if (path.isEmpty()) path = (if (iRel >= 0) c.getString(iRel) ?: "" else "") + name
                    val folder = path.substringBeforeLast('/', "")
                    out += Song(
                        id = id,
                        title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                        artist = clean(c.getString(iArtist)),
                        album = c.getString(iAlbum)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: folder.substringAfterLast('/'),
                        albumId = c.getLong(iAlbumId),
                        albumArtist = if (iAlbumArtist >= 0) c.getString(iAlbumArtist) ?: "" else "",
                        genre = if (iGenre >= 0) TagIO.cleanGenre(c.getString(iGenre) ?: "") else genres[id] ?: "",
                        year = c.getInt(iYear),
                        track = c.getInt(iTrack),
                        duration = c.getLong(iDur),
                        path = path,
                        folder = folder,
                        dateAdded = c.getLong(iAdded),
                        mime = c.getString(iMime) ?: "",
                        size = c.getLong(iSize),
                        uri = ContentUris.withAppendedId(uri, id),
                    )
                }
            }
        } catch (e: SecurityException) {
            // permission not granted yet
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

    private fun clean(a: String?): String = if (a.isNullOrBlank() || a == "<unknown>") Song.UNKNOWN else a

    /** Before Android 11 genres live in a separate table. */
    private fun legacyGenres(ctx: Context): Map<Long, String> {
        val map = HashMap<Long, String>()
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, null
            )?.use { g ->
                while (g.moveToNext()) {
                    val gid = g.getLong(0)
                    val name = TagIO.cleanGenre(g.getString(1) ?: continue)
                    ctx.contentResolver.query(
                        MediaStore.Audio.Genres.Members.getContentUri("external", gid),
                        arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null
                    )?.use { m ->
                        while (m.moveToNext()) map[m.getLong(0)] = name
                    }
                }
            }
        } catch (_: Exception) {}
        return map
    }

    // ---------------------------------------------------------------- extra folders
    private class Found(val uri: Uri, val name: String, val size: Long, val dir: String, val modified: Long)

    /** Scans user-added folders for files Android doesn't index (DSD, AIFF) or that MediaStore missed. */
    fun scanExtraFolders(ctx: Context, known: List<Song>): List<Song> {
        val folders = Prefs.extraFolders
        if (folders.isEmpty()) return emptyList()
        val knownKeys = known.mapTo(HashSet()) { it.path.substringAfterLast('/') + "|" + it.size }
        val found = ArrayList<Found>()
        for (f in folders) {
            try {
                if (f.startsWith("content://")) {
                    if (Build.VERSION.SDK_INT >= 21) walkTree(ctx, Uri.parse(f), found)
                } else walkFiles(File(f), found, 0)
            } catch (_: Exception) {}
        }
        val out = ArrayList<Song>()
        for (x in found) {
            if ((x.name + "|" + x.size) in knownKeys) continue
            songFromFile(ctx, x)?.let { out += it }
        }
        return out
    }

    /** On KitKat, the default music folder is scanned when the user enables it. */
    fun defaultMusicDir(): String = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath

    private fun walkFiles(dir: File, out: MutableList<Found>, depth: Int) {
        if (depth > 8) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (f.isDirectory) {
                if (!f.name.startsWith(".")) walkFiles(f, out, depth + 1)
            } else if (f.extension.lowercase() in Song.SCAN_EXT) {
                out += Found(Uri.fromFile(f), f.name, f.length(), dir.absolutePath, f.lastModified() / 1000)
            }
        }
    }

    @androidx.annotation.RequiresApi(21)
    private fun walkTree(ctx: Context, tree: Uri, out: MutableList<Found>) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootId to rootId.substringAfter(':', rootId))
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 5000) {
            val (docId, dirName) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) stack.addLast(id to "$dirName/$name")
                    } else if (name.substringAfterLast('.', "").lowercase() in Song.SCAN_EXT) {
                        out += Found(
                            DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3),
                            dirName, c.getLong(4) / 1000
                        )
                    }
                }
            }
        }
    }

    private fun songFromFile(ctx: Context, x: Found): Song? {
        val ext = x.name.substringAfterLast('.', "").lowercase()
        val f = AudioFile.open(ctx, x.uri) ?: return null
        return f.use {
            val duration: Long = when (ext) {
                "dsf" -> Formats.parseDsf(f).first?.durationMs ?: return null
                "dff" -> Formats.parseDff(f).first?.durationMs ?: return null
                "aif", "aiff", "aifc" -> Formats.parseAiff(f).first?.durationMs ?: return null
                "wav" -> Formats.parseWav(f)?.durationMs ?: 0L
                else -> durationViaRetriever(ctx, x.uri)
            }
            val tags = try { TagIO.basicTags(f, ext) } catch (e: Exception) { emptyMap() }
            val key = x.uri.toString()
            val folderName = x.dir.substringAfterLast('/')
            val album = tags["album"] ?: folderName
            val artist = tags["artist"] ?: Song.UNKNOWN
            Song(
                id = -(stableHash(key) and Long.MAX_VALUE) - 1,
                title = tags["title"] ?: x.name.substringBeforeLast('.'),
                artist = artist,
                album = album,
                albumId = -(stableHash("album:" + album.lowercase() + "|" + (tags["albumArtist"] ?: artist).lowercase()) and Long.MAX_VALUE) - 1,
                albumArtist = tags["albumArtist"] ?: "",
                genre = tags["genre"] ?: "",
                year = tags["year"]?.toIntOrNull() ?: 0,
                track = tags["track"]?.toIntOrNull() ?: 0,
                duration = duration,
                path = x.dir + "/" + x.name,
                folder = x.dir,
                dateAdded = x.modified,
                mime = "audio/$ext",
                size = x.size,
                uri = x.uri,
            )
        }
    }

    private fun durationViaRetriever(ctx: Context, uri: Uri): Long = try {
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(ctx, uri)
        val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        d
    } catch (e: Exception) { 0L }

    /** 64-bit FNV-1a, stable across runs (unlike String.hashCode collisions). */
    fun stableHash(s: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (ch in s) {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }
}
