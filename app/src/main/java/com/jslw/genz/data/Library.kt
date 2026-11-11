package com.jslw.genz.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jslw.genz.model.Album
import com.jslw.genz.model.Artist
import com.jslw.genz.model.Folder
import com.jslw.genz.model.Genre
import com.jslw.genz.model.Song
import com.jslw.genz.model.Source
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** In-memory music library, built in the background and grouped for classic browsing. */
object Library {
    @Volatile var songs: List<Song> = emptyList(); private set
    @Volatile var albums: List<Album> = emptyList(); private set
    @Volatile var artists: List<Artist> = emptyList(); private set
    @Volatile var genres: List<Genre> = emptyList(); private set
    @Volatile var folders: List<Folder> = emptyList(); private set
    @Volatile var loaded = false; private set

    private var byId: Map<Long, Song> = emptyMap()
    private var albumById: Map<Long, Album> = emptyMap()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var pending = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Ask every screen to redraw (e.g. new album art arrived). */
    fun notifyListeners() { main.post { listeners.forEach { it() } } }

    @Volatile private var again = false
    @Volatile var lastLoad = 0L; private set

    fun load(ctx: Context) {
        if (pending) { again = true; return }   // a change arrived mid-scan: scan once more afterwards
        pending = true
        val app = ctx.applicationContext
        exec.execute {
            try {
                build(app)
            } finally {
                lastLoad = android.os.SystemClock.elapsedRealtime()
                pending = false
                main.post { listeners.forEach { it() } }
                if (again) { again = false; main.post { load(app) } }
            }
        }
    }

    /**
     * Manual "Refresh library": asks Android to index any music files it hasn't noticed yet (songs copied over
     * USB, received with AIR share or downloaded), then rebuilds the library. [done] gets the number of new songs.
     */
    fun refresh(ctx: Context, done: (Int) -> Unit) {
        val app = ctx.applicationContext
        val before = songs.size
        Thread {
            val known = HashSet<String>()
            try {
                app.contentResolver.query(android.provider.MediaStore.Files.getContentUri("external"),
                    arrayOf(android.provider.MediaStore.Files.FileColumns.DATA), null, null, null)?.use { c ->
                    while (c.moveToNext()) c.getString(0)?.let { known += it }
                }
            } catch (_: Exception) {}
            val missing = ArrayList<String>()
            fun walk(dir: java.io.File, depth: Int) {
                if (depth > 7 || missing.size > 5000) return
                val list = dir.listFiles() ?: return
                for (f in list) {
                    if (f.isDirectory) { if (!f.name.startsWith(".") && f.name != "Android") walk(f, depth + 1) }
                    else if (f.extension.lowercase() in Song.SCAN_EXT && f.absolutePath !in known) missing += f.absolutePath
                }
            }
            try { walk(android.os.Environment.getExternalStorageDirectory(), 0) } catch (_: Exception) {}
            val finish = Runnable {
                val once = object : () -> Unit {
                    override fun invoke() { removeListener(this); done((songs.size - before).coerceAtLeast(0)) }
                }
                addListener(once)
                load(app)
            }
            if (missing.isEmpty()) { main.post(finish); return@Thread }
            val left = java.util.concurrent.atomic.AtomicInteger(missing.size)
            val fired = java.util.concurrent.atomic.AtomicBoolean(false)
            val fire = { if (fired.compareAndSet(false, true)) main.post(finish) }
            android.media.MediaScannerConnection.scanFile(app, missing.toTypedArray(), null) { _, _ ->
                if (left.decrementAndGet() <= 0) fire()
            }
            main.postDelayed({ fire() }, 15_000)   // never wait forever on the system scanner
        }.start()
    }

    private fun build(ctx: Context) {
        val base = MediaScanner.queryMediaStore(ctx)
        val extra = MediaScanner.scanExtraFolders(ctx, base).let { it + MediaScanner.unindexedOpus(ctx, base + it) }
        val overrides = LibraryDb.tagOverrides()
        val minMs = Prefs.minDurationSec * 1000L
        val visible = HiddenFolders.filter()
        val all = (base + extra).filter { (it.duration == 0L || it.duration >= minMs) && visible(it) }
        val overriddenAlbum = HashSet<Long>()
        for (s in all) overrides[s.id]?.let { o ->
            o.title?.let { s.title = it }
            o.artist?.let { s.artist = it }
            o.album?.let { s.album = it; overriddenAlbum += s.id }
            o.albumArtist?.let { s.albumArtist = it }
            o.genre?.let { s.genre = it }
            o.year?.let { s.year = it }
            o.track?.let { s.track = it }
        }
        val coll = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        val byTitle = Comparator<String> { a, b -> coll.compare(a, b) }

        // albums
        val albumGroups = LinkedHashMap<String, MutableList<Song>>()
        for (s in all) {
            val key = if (s.id in overriddenAlbum) "o:" + s.album.lowercase(Locale.ROOT) else "id:" + s.albumId
            albumGroups.getOrPut(key) { ArrayList() } += s
        }
        val albumList = albumGroups.map { (key, list) ->
            val sorted = list.sortedWith(compareBy<Song>({ it.track }, { it.title.lowercase(Locale.ROOT) }))
            val first = sorted.first()
            val id = if (key.startsWith("o:")) -(MediaScanner.stableHash(key) and Long.MAX_VALUE) - 1 else first.albumId
            val albumArtist = sorted.firstOrNull { it.albumArtist.isNotBlank() }?.albumArtist
                ?: sorted.map { it.artist }.distinct().let { if (it.size == 1) it[0] else "Various Artists" }
            Album(id, first.album, albumArtist, sorted.maxOf { it.year }, sorted)
        }.sortedWith(compareBy(byTitle) { it.title })

        val artistMap = all.groupBy { it.artist }
        val artistList = artistMap.map { (name, list) ->
            val albs = albumList.filter { a -> a.songs.any { it.artist == name } || a.artist == name }
            Artist(name, list.sortedWith(compareBy<Song>({ it.album.lowercase(Locale.ROOT) }, { it.track })), albs)
        }.sortedWith(compareBy(byTitle) { it.name })

        val genreList = all.groupBy { it.genre.ifBlank { Song.UNKNOWN } }
            .map { (g, list) -> Genre(g, list.sortedBy { it.title.lowercase(Locale.ROOT) }) }
            .sortedWith(compareBy(byTitle) { it.name })

        val folderList = all.groupBy { it.folder }
            .map { (p, list) -> Folder(p, p.substringAfterLast('/').ifEmpty { "/" }, list.sortedWith(compareBy<Song>({ it.track }, { it.title.lowercase(Locale.ROOT) }))) }
            .sortedWith(compareBy(byTitle) { it.name })

        songs = all
        albums = albumList
        artists = artistList
        genres = genreList
        folders = folderList
        byId = all.associateBy { it.id }
        albumById = albumList.associateBy { it.id }
        loaded = true
    }

    fun song(id: Long): Song? = byId[id]
    fun songs(ids: List<Long>): List<Song> = ids.mapNotNull { byId[it] }
    fun album(id: Long): Album? = albumById[id]
    fun albumOf(song: Song): Album? = albums.firstOrNull { a -> a.songs.any { it.id == song.id } }
    fun artist(name: String): Artist? = artists.firstOrNull { it.name == name }

    fun sortedSongs(): List<Song> = when (Prefs.songSort) {
        1 -> songs.sortedWith(compareBy<Song>({ it.artist.lowercase(Locale.ROOT) }, { it.title.lowercase(Locale.ROOT) }))
        2 -> songs.sortedWith(compareBy<Song>({ it.album.lowercase(Locale.ROOT) }, { it.track }))
        3 -> songs.sortedByDescending { it.dateAdded }
        4 -> songs.sortedByDescending { it.duration }
        else -> songs.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    /** Songs for any list screen. Database-backed sources must be called off the main thread for very large libraries. */
    fun songsFor(source: Source): List<Song> = when (source) {
        is Source.AllSongs -> sortedSongs()
        is Source.Favorites -> songs(LibraryDb.favoriteIds())
        is Source.Recent -> songs(LibraryDb.recentIds())
        is Source.MostPlayed -> songs(LibraryDb.mostPlayedIds())
        is Source.RecentlyAdded -> songs.sortedByDescending { it.dateAdded }.take(200)
        is Source.OfAlbum -> album(source.albumId)?.songs ?: emptyList()
        is Source.OfArtist -> artist(source.artist)?.songs ?: emptyList()
        is Source.OfGenre -> genres.firstOrNull { it.name == source.genre }?.songs ?: emptyList()
        is Source.OfFolder -> folders.firstOrNull { it.path == source.path }?.songs ?: emptyList()
        is Source.OfPlaylist -> songs(LibraryDb.playlistSongIds(source.playlistId))
    }

    class SearchResult(val songs: List<Song>, val albums: List<Album>, val artists: List<Artist>, val genres: List<Genre>)

    fun search(q: String): SearchResult {
        val n = q.trim().lowercase(Locale.ROOT)
        if (n.isEmpty()) return SearchResult(emptyList(), emptyList(), emptyList(), emptyList())
        fun m(s: String) = s.lowercase(Locale.ROOT).contains(n)
        return SearchResult(
            songs.filter { m(it.title) || m(it.artist) || m(it.album) }.take(100),
            albums.filter { m(it.title) || m(it.artist) }.take(30),
            artists.filter { m(it.name) }.take(30),
            genres.filter { m(it.name) }.take(20),
        )
    }
}
