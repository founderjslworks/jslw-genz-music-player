package com.jslw.genz.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jslw.genz.model.Playlist
import com.jslw.genz.model.TagOverride

/**
 * GEN-Z's own database: playlists, favorites, play statistics, tag edits and lyric links.
 * Everything is local – nothing ever leaves the phone.
 */
object LibraryDb {
    private lateinit var helper: Helper
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun init(ctx: Context) {
        helper = Helper(ctx.applicationContext)
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "genz.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER)")
            db.execSQL("CREATE TABLE playlist_songs(playlist_id INTEGER, song_id INTEGER, pos INTEGER)")
            db.execSQL("CREATE INDEX ps_idx ON playlist_songs(playlist_id, pos)")
            db.execSQL("CREATE TABLE favorites(song_id INTEGER PRIMARY KEY, added INTEGER)")
            db.execSQL("CREATE TABLE stats(song_id INTEGER PRIMARY KEY, play_count INTEGER, last_played INTEGER)")
            db.execSQL(
                "CREATE TABLE tag_overrides(song_id INTEGER PRIMARY KEY, title TEXT, artist TEXT, album TEXT, " +
                    "album_artist TEXT, genre TEXT, year INTEGER, track INTEGER)"
            )
            db.execSQL("CREATE TABLE lyric_links(song_id INTEGER PRIMARY KEY, uri TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    // ---------- playlists ----------
    fun playlists(): List<Playlist> {
        val out = ArrayList<Playlist>()
        db.rawQuery(
            "SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_songs s WHERE s.playlist_id = p.id) " +
                "FROM playlists p ORDER BY p.name COLLATE NOCASE", null
        ).use { c ->
            while (c.moveToNext()) out += Playlist(c.getLong(0), c.getString(0 + 1), c.getInt(2))
        }
        return out
    }

    fun createPlaylist(name: String): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("created", System.currentTimeMillis())
        }
        return db.insert("playlists", null, cv)
    }

    fun renamePlaylist(id: Long, name: String) {
        db.update("playlists", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        db.delete("playlist_songs", "playlist_id=?", arrayOf(id.toString()))
        db.delete("playlists", "id=?", arrayOf(id.toString()))
    }

    fun playlistSongIds(id: Long): List<Long> {
        val out = ArrayList<Long>()
        db.rawQuery("SELECT song_id FROM playlist_songs WHERE playlist_id=? ORDER BY pos", arrayOf(id.toString())).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    fun addToPlaylist(id: Long, songIds: List<Long>) {
        var pos = 0
        db.rawQuery("SELECT IFNULL(MAX(pos), -1) FROM playlist_songs WHERE playlist_id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) pos = c.getInt(0) + 1
        }
        db.beginTransaction()
        try {
            for (sid in songIds) {
                db.insert("playlist_songs", null, ContentValues().apply {
                    put("playlist_id", id); put("song_id", sid); put("pos", pos++)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Rewrites the whole order (used after drag-reorder or removal). */
    fun setPlaylistSongs(id: Long, songIds: List<Long>) {
        db.beginTransaction()
        try {
            db.delete("playlist_songs", "playlist_id=?", arrayOf(id.toString()))
            songIds.forEachIndexed { i, sid ->
                db.insert("playlist_songs", null, ContentValues().apply {
                    put("playlist_id", id); put("song_id", sid); put("pos", i)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- favorites ----------
    fun favoriteIds(): List<Long> {
        val out = ArrayList<Long>()
        db.rawQuery("SELECT song_id FROM favorites ORDER BY added DESC", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    fun isFavorite(songId: Long): Boolean =
        db.rawQuery("SELECT 1 FROM favorites WHERE song_id=?", arrayOf(songId.toString())).use { it.moveToFirst() }

    /** @return the new state */
    fun toggleFavorite(songId: Long): Boolean {
        return if (isFavorite(songId)) {
            db.delete("favorites", "song_id=?", arrayOf(songId.toString())); false
        } else {
            db.insert("favorites", null, ContentValues().apply {
                put("song_id", songId); put("added", System.currentTimeMillis())
            }); true
        }
    }

    // ---------- statistics ----------
    fun recordPlay(songId: Long) {
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT OR IGNORE INTO stats(song_id, play_count, last_played) VALUES(?, 0, ?)",
            arrayOf<Any>(songId, now)
        )
        db.execSQL(
            "UPDATE stats SET play_count = play_count + 1, last_played = ? WHERE song_id = ?",
            arrayOf<Any>(now, songId)
        )
    }

    fun recentIds(limit: Int = 100): List<Long> {
        val out = ArrayList<Long>()
        db.rawQuery("SELECT song_id FROM stats ORDER BY last_played DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    fun mostPlayedIds(limit: Int = 100): List<Long> {
        val out = ArrayList<Long>()
        db.rawQuery("SELECT song_id FROM stats WHERE play_count > 0 ORDER BY play_count DESC, last_played DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    fun playCount(songId: Long): Int =
        db.rawQuery("SELECT play_count FROM stats WHERE song_id=?", arrayOf(songId.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    // ---------- tag overrides ----------
    fun tagOverrides(): Map<Long, TagOverride> {
        val out = HashMap<Long, TagOverride>()
        db.rawQuery("SELECT song_id, title, artist, album, album_artist, genre, year, track FROM tag_overrides", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out[id] = TagOverride(
                    id,
                    c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
                    if (c.isNull(6)) null else c.getInt(6),
                    if (c.isNull(7)) null else c.getInt(7),
                )
            }
        }
        return out
    }

    fun saveTagOverride(o: TagOverride) {
        val cv = ContentValues().apply {
            put("song_id", o.songId)
            put("title", o.title); put("artist", o.artist); put("album", o.album)
            put("album_artist", o.albumArtist); put("genre", o.genre)
            if (o.year != null) put("year", o.year) else putNull("year")
            if (o.track != null) put("track", o.track) else putNull("track")
        }
        db.insertWithOnConflict("tag_overrides", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- lyric file links ----------
    fun lyricLink(songId: Long): String? =
        db.rawQuery("SELECT uri FROM lyric_links WHERE song_id=?", arrayOf(songId.toString())).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun setLyricLink(songId: Long, uri: String?) {
        if (uri == null) db.delete("lyric_links", "song_id=?", arrayOf(songId.toString()))
        else db.insertWithOnConflict("lyric_links", null, ContentValues().apply {
            put("song_id", songId); put("uri", uri)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
