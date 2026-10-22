package com.jslw.genz.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jslw.genz.R
import com.jslw.genz.data.Library
import com.jslw.genz.data.LibraryDb
import com.jslw.genz.data.MediaScanner
import com.jslw.genz.data.Prefs
import com.jslw.genz.model.Album
import com.jslw.genz.model.Song
import com.jslw.genz.model.Source
import com.jslw.genz.playback.Player
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.util.formatTotal
import com.jslw.genz.util.plural
import com.jslw.genz.util.toast
import kotlin.math.abs

/** Common list screen: a skinned RecyclerView inside the main activity's content area. */
abstract class BaseListFragment : Fragment(), Refreshable {
    protected lateinit var list: RecyclerView
    protected lateinit var empty: TextView
    protected val main: MainActivity? get() = activity as? MainActivity
    abstract val screenTitle: String

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_list, container, false)
        list = v.findViewById(R.id.list)
        empty = v.findViewById(R.id.empty)
        list.layoutManager = LinearLayoutManager(requireContext())
        com.jslw.genz.util.Smooth.tuneList(list)
        SkinPainter.apply(v)
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { build() }

    override fun onResume() {
        super.onResume()
        main?.setScreenTitle(screenTitle)
    }

    abstract fun build()
    override fun refresh() { if (view != null) build() }

    protected fun showEmpty(msg: String?) {
        empty.text = msg
        empty.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    protected fun open(f: Fragment) = main?.open(f)
    protected val act: AppCompatActivity get() = requireActivity() as AppCompatActivity
}

// ===================================================================== Home ("Music") menu
class HomeFragment : BaseListFragment() {
    override val screenTitle = "Music"
    private val adapter = RowAdapter()

    override fun build() {
        list.adapter = adapter
        val skin = SkinManager.current
        val rows = ArrayList<Row>()
        val loaded = Library.loaded
        fun n(x: Int) = if (loaded) x.toString() else ""
        rows += Row("Album Flow", icon = R.drawable.ic_coverflow, iconColor = 0xFFFF2D55.toInt(), chevron = true, onClick = { open(AlbumFlowFragment()) })
        rows += Row("Playlists", icon = R.drawable.ic_playlist, iconColor = 0xFFFF9500.toInt(), chevron = true, extra = n(LibraryDb.playlists().size),
            onClick = { open(BrowseFragment.of("playlists")) })
        rows += Row("Artists", icon = R.drawable.ic_artist, iconColor = 0xFFAF52DE.toInt(), chevron = true, extra = n(Library.artists.size), onClick = { open(BrowseFragment.of("artists")) })
        rows += Row("Albums", icon = R.drawable.ic_album, iconColor = 0xFF007AFF.toInt(), chevron = true, extra = n(Library.albums.size), onClick = { open(BrowseFragment.of("albums")) })
        rows += Row("Songs", icon = R.drawable.ic_note, iconColor = 0xFF34C759.toInt(), chevron = true, extra = n(Library.songs.size), onClick = { open(SongsFragment.forSource(Source.AllSongs)) })
        rows += Row("Genres", icon = R.drawable.ic_genre, iconColor = 0xFF5856D6.toInt(), chevron = true, extra = n(Library.genres.size), onClick = { open(BrowseFragment.of("genres")) })
        rows += Row("Folders", icon = R.drawable.ic_folder, iconColor = 0xFFFF9F0A.toInt(), chevron = true, extra = n(Library.folders.size), onClick = { open(BrowseFragment.of("folders")) })
        rows += Row("Shuffle Songs", icon = R.drawable.ic_shuffle, iconColor = 0xFF00C7BE.toInt(), onClick = {
            if (Library.songs.isEmpty()) requireContext().toast("No songs found yet")
            else Player.shuffle(requireContext(), Library.songs, "All songs")
        })
        if (Player.current != null) rows += Row("Now Playing", icon = R.drawable.ic_play, iconColor = 0xFFFF3B30.toInt(), chevron = true, onClick = {
            startActivity(Intent(requireContext(), NowPlayingActivity::class.java))
            requireActivity().overridePendingTransition(R.anim.slide_up, R.anim.stay)
        })
        rows += Row.section("Extras")
        rows += Row("Equalizer", icon = R.drawable.ic_equalizer, iconColor = 0xFF30B0C7.toInt(), chevron = true, onClick = { startScreen(EqualizerActivity::class.java) })
        rows += Row("Themes", subtitle = skin.name, icon = R.drawable.ic_palette, iconColor = 0xFFFF2D92.toInt(), chevron = true, onClick = { startScreen(ThemesActivity::class.java) })
        rows += Row("Sleep Timer", icon = R.drawable.ic_timer, iconColor = 0xFF5E5CE6.toInt(), onClick = { Player.with(requireContext()) { Sheets.sleepTimer(requireActivity()) } })
        rows += Row("Settings", icon = R.drawable.ic_settings, iconColor = 0xFF8E8E93.toInt(), chevron = true, onClick = { startScreen(SettingsActivity::class.java) })
        adapter.rows = rows
        if (loaded && Library.songs.isEmpty()) showEmpty("No music found.\nCopy songs to your phone's Music folder, or add a folder in Settings.")
        else showEmpty(null)
    }

    private fun startScreen(c: Class<*>) {
        startActivity(Intent(requireContext(), c))
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    override fun playingChanged() { if (view != null && adapter.rows.none { it.title == "Now Playing" } && Player.current != null) build() }
}

// ===================================================================== Artists / Albums / Genres / Folders / Playlists
class BrowseFragment : BaseListFragment() {
    private val kind get() = requireArguments().getString("kind") ?: "albums"
    private val key get() = requireArguments().getString("key") ?: ""
    override val screenTitle: String
        get() = when (kind) {
            "artists" -> "Artists"; "albums" -> "Albums"; "genres" -> "Genres"; "folders" -> "Folders"
            "playlists" -> "Playlists"; else -> key
        }
    private val adapter = RowAdapter()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            try { requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
        Prefs.extraFolders = Prefs.extraFolders + uri.toString()
        requireContext().toast("Scanning folder…")
        Library.load(requireContext())
    }

    override fun build() {
        list.adapter = adapter
        val rows = ArrayList<Row>()
        when (kind) {
            "artists" -> Library.artists.forEach { a ->
                rows += Row(a.name, "${plural(a.albums.size, "album")} · ${plural(a.songs.size, "song")}", art = a.songs.firstOrNull(),
                    chevron = true, onClick = { open(artist(a.name)) },
                    onLong = { v -> groupMenu(v, a.songs, a.name) })
            }
            "artist" -> {
                val a = Library.artist(key)
                if (a != null) {
                    rows += Row("All songs", plural(a.songs.size, "song"), icon = R.drawable.ic_note, chevron = true,
                        onClick = { open(SongsFragment.forSource(Source.OfArtist(a.name))) })
                    a.albums.forEach { al -> rows += albumRow(al) }
                }
            }
            "albums" -> Library.albums.forEach { rows += albumRow(it) }
            "genres" -> Library.genres.forEach { g ->
                rows += Row(g.name, plural(g.songs.size, "song"), icon = R.drawable.ic_genre, chevron = true,
                    onClick = { open(SongsFragment.forSource(Source.OfGenre(g.name))) },
                    onLong = { v -> groupMenu(v, g.songs, g.name) })
            }
            "folders" -> {
                rows += Row("Add music folder…", "For DSD (.dsf/.dff), AIFF and files Android doesn't index",
                    icon = R.drawable.ic_add, onClick = { addFolder() })
                Library.folders.forEach { f ->
                    rows += Row(f.name, f.path, extra = f.songs.size.toString(), icon = R.drawable.ic_folder, chevron = true,
                        onClick = { open(SongsFragment.forSource(Source.OfFolder(f.path, f.name))) },
                        onLong = { v -> groupMenu(v, f.songs, f.name) })
                }
            }
            "playlists" -> {
                rows += Row("New playlist…", icon = R.drawable.ic_add, onClick = {
                    Sheets.askText(requireActivity(), "New playlist", "", "Create") { name ->
                        LibraryDb.createPlaylist(name); build()
                    }
                })
                rows += Row("Favorites", plural(LibraryDb.favoriteIds().size, "song"), icon = R.drawable.ic_favorite, chevron = true,
                    onClick = { open(SongsFragment.forSource(Source.Favorites)) })
                rows += Row("Recently played", icon = R.drawable.ic_history, chevron = true, onClick = { open(SongsFragment.forSource(Source.Recent)) })
                rows += Row("Most played", icon = R.drawable.ic_star, chevron = true, onClick = { open(SongsFragment.forSource(Source.MostPlayed)) })
                rows += Row("Recently added", icon = R.drawable.ic_playlist_add, chevron = true, onClick = { open(SongsFragment.forSource(Source.RecentlyAdded)) })
                val pls = LibraryDb.playlists()
                if (pls.isNotEmpty()) rows += Row.section("Your playlists")
                pls.forEach { p ->
                    rows += Row(p.name, plural(p.count, "song"), icon = R.drawable.ic_playlist, chevron = true,
                        onClick = { open(SongsFragment.forSource(Source.OfPlaylist(p.id, p.name))) },
                        onLong = { v -> playlistMenu(v, p.id, p.name) })
                }
            }
        }
        adapter.rows = rows
        showEmpty(if (rows.isEmpty()) (if (Library.loaded) "Nothing here yet" else "Loading…") else null)
    }

    private fun albumRow(al: Album) = Row(
        al.title, listOf(al.artist, if (al.year > 0) al.year.toString() else null, plural(al.songs.size, "song"))
            .filterNotNull().joinToString(" · "),
        art = al.artSong, chevron = true,
        onClick = { open(SongsFragment.forSource(Source.OfAlbum(al.id, al.title))) },
        onLong = { v -> groupMenu(v, al.songs, al.title) },
    )

    private fun groupMenu(anchor: View, songs: List<Song>, label: String) {
        val m = PopupMenu(requireContext(), anchor)
        m.menu.add("Play"); m.menu.add("Shuffle"); m.menu.add("Play next"); m.menu.add("Add to queue"); m.menu.add("Add to playlist…")
        m.setOnMenuItemClickListener { item ->
            val c = requireContext()
            when (item.title.toString()) {
                "Play" -> Player.play(c, songs, 0, label)
                "Shuffle" -> Player.shuffle(c, songs, label)
                "Play next" -> Player.with(c) { it.playNext(songs) }
                "Add to queue" -> Player.with(c) { it.addToQueue(songs) }
                else -> Sheets.addToPlaylist(requireActivity(), songs)
            }
            true
        }
        m.show()
    }

    private fun playlistMenu(anchor: View, id: Long, name: String) {
        val m = PopupMenu(requireContext(), anchor)
        m.menu.add("Play"); m.menu.add("Rename…"); m.menu.add("Delete")
        m.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Play" -> Player.play(requireContext(), Library.songs(LibraryDb.playlistSongIds(id)), 0, name)
                "Rename…" -> Sheets.askText(requireActivity(), "Rename playlist", name, "Rename") { LibraryDb.renamePlaylist(id, it); build() }
                else -> MaterialAlertDialogBuilder(requireContext()).setTitle("Delete \"$name\"?")
                    .setMessage("The songs themselves are not deleted.")
                    .setPositiveButton("Delete") { _, _ -> LibraryDb.deletePlaylist(id); build() }
                    .setNegativeButton("Cancel", null).show()
            }
            true
        }
        m.show()
    }

    private fun addFolder() {
        if (Build.VERSION.SDK_INT >= 21) {
            try { pickFolder.launch(null) } catch (e: Exception) { requireContext().toast("No folder picker available on this device") }
        } else {
            // KitKat: scan the Music folder directly (file access is allowed there)
            val dir = MediaScanner.defaultMusicDir()
            Prefs.extraFolders = Prefs.extraFolders + dir
            requireContext().toast("Scanning $dir…")
            Library.load(requireContext())
        }
    }

    companion object {
        fun of(kind: String) = BrowseFragment().apply { arguments = Bundle().apply { putString("kind", kind) } }
        fun artist(name: String) = BrowseFragment().apply {
            arguments = Bundle().apply { putString("kind", "artist"); putString("key", name) }
        }
    }
}

// ===================================================================== Song lists
class SongsFragment : BaseListFragment() {
    private lateinit var source: Source
    override val screenTitle: String get() = source.label
    private val rowsAdapter = RowAdapter()
    private var header: HeaderAdapter? = null
    private var songs: List<Song> = emptyList()
    private val isPlaylist get() = source is Source.OfPlaylist

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = decode(requireArguments())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val onInfo: ((View) -> Unit)? = if (source is Source.AllSongs) ({ v: View -> sortMenu(v) }) else null
        header = HeaderAdapter("", { playAll() }, { play(0, true) }, onInfo)
        list.adapter = ConcatAdapter(header, rowsAdapter)
        attachGestures()
        super.onViewCreated(view, savedInstanceState)
    }

    override fun build() {
        songs = Library.songsFor(source)
        val trackNo = source is Source.OfAlbum
        rowsAdapter.rows = songs.mapIndexed { i, s ->
            Row.forSong(s, showArt = !trackNo, trackNo = trackNo, onClick = { play(i, false) }, onMore = { songMenu(s, i) }).let {
                if (isPlaylist) Row(it.title, it.subtitle, it.extra, art = it.art, song = s, drag = true, onClick = it.onClick, onLong = it.onLong, onMore = it.onMore) else it
            }
        }
        var info = "${plural(songs.size, "song")} · ${formatTotal(songs.sumOf { it.duration })}"
        if (source is Source.AllSongs) info += " · Sort: " + SORTS[Prefs.songSort] + " ▾"
        header?.setInfo(info)
        showEmpty(if (songs.isEmpty()) (if (Library.loaded) "No songs here" else "Loading…") else null)
    }

    private fun play(i: Int, shuffle: Boolean) {
        if (songs.isEmpty()) return
        if (shuffle) Player.shuffle(requireContext(), songs, source.label)
        else Player.play(requireContext(), songs, i, source.label)
    }

    /** "Play" button: in order, from the top. */
    private fun playAll() {
        if (songs.isEmpty()) return
        Player.with(requireContext()) { svc ->
            if (svc.shuffle) svc.setShuffleMode(false)
            svc.playQueue(songs, 0, source.label)
        }
    }

    private fun songMenu(s: Song, i: Int) {
        val extra = if (isPlaylist) listOf(Sheets.Option(R.drawable.ic_delete, "Remove from this playlist") {
            val pid = (source as Source.OfPlaylist).playlistId
            LibraryDb.setPlaylistSongs(pid, songs.filterIndexed { j, _ -> j != i }.map { it.id })
            build()
        }) else emptyList()
        Sheets.songOptions(act, s, extra)
    }

    private fun sortMenu(anchor: View) {
        val m = PopupMenu(requireContext(), anchor)
        SORTS.forEachIndexed { i, n -> m.menu.add(0, i, i, n) }
        m.setOnMenuItemClickListener { Prefs.songSort = it.itemId; build(); list.scheduleLayoutAnimation(); true }
        m.show()
    }

    /**
     * Gestures: in playlists drag ≡ to reorder and swipe to remove; elsewhere swipe right = play next,
     * swipe left = add to queue (the row springs back).
     */
    private fun attachGestures() {
        val dragDirs = if (isPlaylist) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
        val cb = object : ItemTouchHelper.SimpleCallback(dragDirs, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            private var from = -1
            private var to = -1
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (vh is RowAdapter.VH) super.getMovementFlags(rv, vh) else 0

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                if (target !is RowAdapter.VH) return false
                val f = vh.bindingAdapterPosition; val t = target.bindingAdapterPosition
                if (from == -1) from = f
                to = t
                rowsAdapter.moveItem(f, t)
                return true
            }

            override fun isLongPressDragEnabled() = false

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (isPlaylist && from >= 0 && to >= 0 && from != to) {
                    val ids = songs.map { it.id }.toMutableList()
                    ids.add(to, ids.removeAt(from))
                    LibraryDb.setPlaylistSongs((source as Source.OfPlaylist).playlistId, ids)
                    build()
                }
                from = -1; to = -1
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                val s = songs.getOrNull(pos) ?: return
                val c = requireContext()
                if (isPlaylist) {
                    LibraryDb.setPlaylistSongs((source as Source.OfPlaylist).playlistId, songs.filterIndexed { j, _ -> j != pos }.map { it.id })
                    c.toast("Removed \"${s.title}\"")
                    build()
                    return
                }
                if (direction == ItemTouchHelper.RIGHT) { Player.with(c) { it.playNext(listOf(s)) }; c.toast("Playing next: ${s.title}") }
                else { Player.with(c) { it.addToQueue(listOf(s)) }; c.toast("Added to queue: ${s.title}") }
                rowsAdapter.notifyItemChanged(pos)
            }

            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
                if (state == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    // resist: the row moves at most a third of the width so it reads as a gesture, not a delete
                    val limited = if (isPlaylist) dX else dX.coerceIn(-rv.width / 3f, rv.width / 3f)
                    vh.itemView.alpha = 1f - abs(limited) / rv.width
                    super.onChildDraw(c, rv, vh, limited, dY, state, active)
                } else super.onChildDraw(c, rv, vh, dX, dY, state, active)
            }

            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = if (isPlaylist) 0.5f else 0.3f
        }
        val ith = ItemTouchHelper(cb)
        ith.attachToRecyclerView(list)
        rowsAdapter.itemTouchHelper = ith
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun playingChanged() { rowsAdapter.refreshPlaying() }

    override fun refresh() { if (view != null) build() }

    companion object {
        val SORTS = listOf("Title", "Artist", "Album", "Date added", "Length")

        fun forSource(s: Source) = SongsFragment().apply { arguments = encode(s) }

        fun encode(s: Source): Bundle = Bundle().apply {
            when (s) {
                is Source.AllSongs -> putString("t", "all")
                is Source.Favorites -> putString("t", "fav")
                is Source.Recent -> putString("t", "recent")
                is Source.MostPlayed -> putString("t", "most")
                is Source.RecentlyAdded -> putString("t", "added")
                is Source.OfAlbum -> { putString("t", "album"); putLong("id", s.albumId); putString("name", s.label) }
                is Source.OfArtist -> { putString("t", "artist"); putString("name", s.artist) }
                is Source.OfGenre -> { putString("t", "genre"); putString("name", s.genre) }
                is Source.OfFolder -> { putString("t", "folder"); putString("path", s.path); putString("name", s.label) }
                is Source.OfPlaylist -> { putString("t", "playlist"); putLong("id", s.playlistId); putString("name", s.label) }
            }
        }

        fun decode(b: Bundle): Source {
            val name = b.getString("name") ?: ""
            return when (b.getString("t")) {
                "fav" -> Source.Favorites
                "recent" -> Source.Recent
                "most" -> Source.MostPlayed
                "added" -> Source.RecentlyAdded
                "album" -> Source.OfAlbum(b.getLong("id"), name)
                "artist" -> Source.OfArtist(name)
                "genre" -> Source.OfGenre(name)
                "folder" -> Source.OfFolder(b.getString("path") ?: "", name)
                "playlist" -> Source.OfPlaylist(b.getLong("id"), name)
                else -> Source.AllSongs
            }
        }
    }
}
