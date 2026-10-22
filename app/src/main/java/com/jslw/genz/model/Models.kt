package com.jslw.genz.model

import android.net.Uri

/**
 * A playable track. Songs from MediaStore have positive ids; songs found by the
 * folder scanner (formats Android doesn't index, e.g. DSD / AIFF) have negative ids.
 */
data class Song(
    val id: Long,
    var title: String,
    var artist: String,
    var album: String,
    val albumId: Long,
    var albumArtist: String,
    var genre: String,
    var year: Int,
    var track: Int,
    val duration: Long,
    val path: String,
    val folder: String,
    val dateAdded: Long,
    val mime: String,
    val size: Long,
    val uri: Uri,
) {
    val ext: String get() = path.substringAfterLast('.', "").lowercase()
    val subtitle: String get() = if (album.isNotEmpty()) "$artist • $album" else artist

    /** Formats that the built-in Android MediaPlayer cannot decode and our own PCM engine handles. */
    val needsPcmEngine: Boolean get() = ext in PCM_ENGINE_EXT

    companion object {
        val PCM_ENGINE_EXT = setOf("aif", "aiff", "aifc", "dsf", "dff")
        val SCAN_EXT = setOf("aif", "aiff", "aifc", "dsf", "dff", "flac", "wav", "ape", "wma", "m4a", "mp3", "ogg", "opus", "mid", "midi")
        const val UNKNOWN = "Unknown"
    }
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<Song>,
) {
    val artSong: Song get() = songs.first()
}

data class Artist(val name: String, val songs: List<Song>, val albums: List<Album>)

data class Genre(val name: String, val songs: List<Song>)

data class Folder(val path: String, val name: String, val songs: List<Song>)

data class Playlist(val id: Long, val name: String, val count: Int)

/** User edits applied on top of file tags (stored in GEN-Z's database). */
data class TagOverride(
    val songId: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val track: Int?,
)

/** What a list screen is showing; also used as the "Playing from" label. */
sealed class Source(val label: String) {
    object AllSongs : Source("Songs")
    object Favorites : Source("Favorites")
    object Recent : Source("Recently played")
    object MostPlayed : Source("Most played")
    object RecentlyAdded : Source("Recently added")
    class OfAlbum(val albumId: Long, name: String) : Source(name)
    class OfArtist(val artist: String) : Source(artist)
    class OfGenre(val genre: String) : Source(genre)
    class OfFolder(val path: String, name: String) : Source(name)
    class OfPlaylist(val playlistId: Long, name: String) : Source(name)
}
