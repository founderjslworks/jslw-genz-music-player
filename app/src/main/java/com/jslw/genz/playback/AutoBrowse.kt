package com.jslw.genz.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import com.jslw.genz.BuildConfig
import com.jslw.genz.R
import com.jslw.genz.data.Library
import com.jslw.genz.data.LibraryDb
import com.jslw.genz.model.Song
import com.jslw.genz.model.Source

/**
 * The library as Android Auto (and Android Automotive, the Assistant and Android's media controls) see it:
 * a small browse tree of categories → albums / artists / playlists → songs.
 *
 * Media ids:  "root", category ids ("recent", "albums", …), containers ("al:<id>", "ar:<name>", "ge:<name>",
 * "pl:<id>") and playable songs "<container>|<songId>", so a tapped song plays with the rest of its list.
 */
object AutoBrowse {
    const val ROOT = "root"
    const val EMPTY_ROOT = "__empty__"
    const val RECENT_ROOT = "__recent__"
    const val SHUFFLE_ALL = "shuffle_all"
    private const val SEP = '|'
    private const val MAX_ITEMS = 400

    // content-style hints understood by Android Auto / Automotive (strings, so no extra library is needed)
    const val STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    private const val STYLE_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
    private const val LIST = 1
    private const val GRID = 2

    /** Apps allowed to browse the library; anyone else only gets the play controls. */
    private val TRUSTED = setOf(
        "com.google.android.projection.gearhead",     // Android Auto
        "com.google.android.autosimulator",           // Desktop Head Unit
        "com.android.car.media", "com.android.car.carlauncher", "com.google.android.car.media", // Android Automotive
        "com.google.android.googlequicksearchbox",     // Assistant
        "com.google.android.wearable.app", "com.google.android.apps.wearables.maestro.companion",
        "com.android.systemui", "com.android.bluetooth",
    )

    fun trusted(ctx: Context, pkg: String, uid: Int): Boolean =
        pkg == ctx.packageName || pkg in TRUSTED || uid == android.os.Process.SYSTEM_UID

    fun rootExtras() = Bundle().apply {
        putBoolean(STYLE_SUPPORTED, true)
        putInt(STYLE_BROWSABLE, LIST)
        putInt(STYLE_PLAYABLE, LIST)
        putBoolean(SEARCH_SUPPORTED, true)
    }

    private fun res(ctx: Context, id: Int): Uri =
        Uri.parse("android.resource://${ctx.packageName}/${ctx.resources.getResourceTypeName(id)}/${ctx.resources.getResourceEntryName(id)}")

    /** Album art for a song, served by [ArtProvider] so the car can load it. */
    fun artUri(s: Song): Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.art/song/${s.id}")

    private fun folder(ctx: Context, id: String, title: String, sub: String?, icon: Int?, art: Uri? = null, childStyle: Int = LIST) =
        MediaItem(
            MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(sub)
                .setIconUri(art ?: icon?.let { res(ctx, it) })
                .setExtras(Bundle().apply { putInt(STYLE_BROWSABLE, childStyle); putInt(STYLE_PLAYABLE, LIST) })
                .build(),
            MediaItem.FLAG_BROWSABLE
        )

    private fun playable(ctx: Context, id: String, title: String, sub: String?, icon: Int?) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).setIconUri(icon?.let { res(ctx, it) }).build(),
        MediaItem.FLAG_PLAYABLE
    )

    fun songItem(container: String, s: Song, group: String? = null) = MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId("$container$SEP${s.id}")
            .setTitle(s.title)
            .setSubtitle(s.artist)
            .setDescription(s.album)
            .setIconUri(artUri(s))
            .apply { if (group != null) setExtras(Bundle().apply { putString(STYLE_GROUP_TITLE, group) }) }
            .build(),
        MediaItem.FLAG_PLAYABLE
    )

    /** Children of a node. Call off the main thread (the database is read for playlists, favourites, history). */
    fun children(ctx: Context, parent: String): List<MediaItem> = when {
        parent == ROOT -> listOf(
            playable(ctx, SHUFFLE_ALL, "Shuffle all", "${Library.songs.size} songs", R.drawable.ic_shuffle),
            folder(ctx, "recent", "Recently played", null, R.drawable.ic_history),
            folder(ctx, "favs", "Favourites", null, R.drawable.ic_favorite),
            folder(ctx, "playlists", "Playlists", null, R.drawable.ic_playlist),
            folder(ctx, "albums", "Albums", "${Library.albums.size} albums", R.drawable.ic_album, childStyle = GRID),
            folder(ctx, "artists", "Artists", "${Library.artists.size} artists", R.drawable.ic_artist),
            folder(ctx, "genres", "Genres", null, R.drawable.ic_genre),
            folder(ctx, "most", "Most played", null, R.drawable.ic_star),
            folder(ctx, "songs", "All songs", null, R.drawable.ic_note),
        )
        parent == RECENT_ROOT -> listOfNotNull(Library.songs(LibraryDb.recentIds(1)).firstOrNull()?.let { songItem("recent", it) })
        parent == "albums" -> Library.albums.take(MAX_ITEMS).map {
            folder(ctx, "al:${it.id}", it.title, it.artist, null, artUri(it.artSong))
        }
        parent == "artists" -> Library.artists.take(MAX_ITEMS).map {
            folder(ctx, "ar:${it.name}", it.name, "${it.songs.size} songs", R.drawable.ic_artist, it.songs.firstOrNull()?.let(::artUri))
        }
        parent == "genres" -> Library.genres.take(MAX_ITEMS).map {
            folder(ctx, "ge:${it.name}", it.name, "${it.songs.size} songs", R.drawable.ic_genre)
        }
        parent == "playlists" -> LibraryDb.playlists().map {
            folder(ctx, "pl:${it.id}", it.name, "${it.count} songs", R.drawable.ic_playlist)
        }
        else -> songsOf(parent).take(MAX_ITEMS).map { songItem(parent, it) }
    }

    /** The songs behind a container id (also used to play a tapped song with its neighbours). */
    fun songsOf(container: String): List<Song> = when {
        container == "songs" -> Library.songsFor(Source.AllSongs)
        container == "recent" -> Library.songsFor(Source.Recent)
        container == "favs" -> Library.songsFor(Source.Favorites)
        container == "most" -> Library.songsFor(Source.MostPlayed)
        container.startsWith("al:") -> container.substring(3).toLongOrNull()?.let { Library.album(it)?.songs } ?: emptyList()
        container.startsWith("ar:") -> Library.artist(container.substring(3))?.songs ?: emptyList()
        container.startsWith("ge:") -> Library.genres.firstOrNull { it.name == container.substring(3) }?.songs ?: emptyList()
        container.startsWith("pl:") -> container.substring(3).toLongOrNull()?.let { Library.songs(LibraryDb.playlistSongIds(it)) } ?: emptyList()
        container == "search" -> lastSearch
        else -> emptyList()
    }

    fun label(container: String): String = when {
        container == "songs" -> "All songs"
        container == "recent" -> "Recently played"
        container == "favs" -> "Favourites"
        container == "most" -> "Most played"
        container.startsWith("al:") -> container.substring(3).toLongOrNull()?.let { Library.album(it)?.title } ?: "Album"
        container.startsWith("ar:") -> container.substring(3)
        container.startsWith("ge:") -> container.substring(3)
        container.startsWith("pl:") -> container.substring(3).toLongOrNull()?.let { id -> LibraryDb.playlists().firstOrNull { it.id == id }?.name } ?: "Playlist"
        container == "search" -> "Search"
        else -> "Android Auto"
    }

    /** "container|songId" → (container, songId); a bare container id plays from its first song. */
    fun split(mediaId: String): Pair<String, Long?> {
        val i = mediaId.lastIndexOf(SEP)
        return if (i < 0) mediaId to null else mediaId.substring(0, i) to mediaId.substring(i + 1).toLongOrNull()
    }

    @Volatile private var lastSearch: List<Song> = emptyList()

    /** Search for the car's search screen: songs, then albums and artists as browsable rows. */
    fun search(ctx: Context, query: String): List<MediaItem> {
        val r = Library.search(query)
        lastSearch = r.songs
        val out = ArrayList<MediaItem>()
        r.songs.take(50).forEach { out += songItem("search", it, "Songs") }
        r.albums.take(15).forEach { a ->
            out += MediaItem(
                MediaDescriptionCompat.Builder().setMediaId("al:${a.id}").setTitle(a.title).setSubtitle(a.artist).setIconUri(artUri(a.artSong))
                    .setExtras(Bundle().apply { putString(STYLE_GROUP_TITLE, "Albums") }).build(),
                MediaItem.FLAG_BROWSABLE
            )
        }
        r.artists.take(15).forEach { a ->
            out += MediaItem(
                MediaDescriptionCompat.Builder().setMediaId("ar:${a.name}").setTitle(a.name).setSubtitle("${a.songs.size} songs")
                    .setIconUri(a.songs.firstOrNull()?.let(::artUri))
                    .setExtras(Bundle().apply { putString(STYLE_GROUP_TITLE, "Artists") }).build(),
                MediaItem.FLAG_BROWSABLE
            )
        }
        return out
    }

    /**
     * Voice search ("play … on GEN-Z"): artist, album or genre when the Assistant says which it is,
     * otherwise the best match of artist → album → songs → genre. Returns the songs to play and a label.
     */
    fun voice(query: String?, extras: Bundle?): Pair<List<Song>, String> {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return Library.songs.shuffled() to "Shuffle all"
        val focus = extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS)
        val artistQ = extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST)
        val albumQ = extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_ALBUM)
        fun eq(a: String, b: String?) = b != null && a.equals(b, ignoreCase = true)
        if (focus == android.provider.MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE && albumQ != null)
            Library.albums.firstOrNull { eq(it.title, albumQ) }?.let { return it.songs to it.title }
        if (focus == android.provider.MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE && artistQ != null)
            Library.artists.firstOrNull { eq(it.name, artistQ) }?.let { return it.songs to it.name }
        Library.artists.firstOrNull { eq(it.name, q) }?.let { return it.songs to it.name }
        Library.albums.firstOrNull { eq(it.title, q) }?.let { return it.songs to it.title }
        Library.genres.firstOrNull { eq(it.name, q) }?.let { return it.songs.shuffled() to it.name }
        val r = Library.search(q)
        r.artists.firstOrNull()?.let { return it.songs to it.name }
        if (r.songs.isNotEmpty()) return r.songs to "“$q”"
        r.albums.firstOrNull()?.let { return it.songs to it.title }
        r.genres.firstOrNull()?.let { return it.songs.shuffled() to it.name }
        return emptyList<Song>() to q
    }
}
