package com.jslw.genz.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jslw.genz.R
import com.jslw.genz.data.Library
import com.jslw.genz.playback.Player
import com.jslw.genz.util.Smooth
import com.jslw.genz.util.plural

/** Instant search across songs, albums, artists and genres (all offline, in memory). */
class SearchActivity : BaseActivity() {
    private val adapter = RowAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_list)
        setupScreen(findViewById(R.id.root))
        header("Search")
        input = findViewById(R.id.searchInput)
        input.visibility = View.VISIBLE
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        Smooth.tuneList(list)
        list.adapter = adapter
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ search(s.toString()) }, 120)
            }
        })
        input.requestFocus()
    }

    private fun search(q: String) {
        val r = Library.search(q)
        val rows = ArrayList<Row>()
        if (r.songs.isNotEmpty()) {
            rows += Row.section("Songs")
            r.songs.forEachIndexed { i, s ->
                rows += Row.forSong(s, onClick = { Player.play(this, r.songs, i, "Search: $q") }, onMore = { Sheets.songOptions(this, s) })
            }
        }
        if (r.albums.isNotEmpty()) {
            rows += Row.section("Albums")
            r.albums.forEach { a ->
                rows += Row(a.title, a.artist, art = a.artSong, chevron = true,
                    onClick = { Sheets.openInMain(this, MainActivity.EXTRA_ALBUM, a.id) })
            }
        }
        if (r.artists.isNotEmpty()) {
            rows += Row.section("Artists")
            r.artists.forEach { a ->
                rows += Row(a.name, plural(a.songs.size, "song"), art = a.songs.firstOrNull(), chevron = true,
                    onClick = { Sheets.openInMain(this, MainActivity.EXTRA_ARTIST, a.name) })
            }
        }
        if (r.genres.isNotEmpty()) {
            rows += Row.section("Genres")
            r.genres.forEach { g ->
                rows += Row(g.name, plural(g.songs.size, "song"), icon = R.drawable.ic_genre, chevron = true,
                    onClick = { Sheets.openInMain(this, MainActivity.EXTRA_GENRE, g.name) })
            }
        }
        if (rows.isEmpty() && q.isNotBlank()) rows += Row("No results for \"$q\"")
        adapter.rows = rows
    }

    override fun finish() {
        super.finish()
        com.jslw.genz.util.Transitions.close(this)
    }
}
