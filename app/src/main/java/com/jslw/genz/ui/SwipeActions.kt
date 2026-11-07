package com.jslw.genz.ui

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import com.jslw.genz.R
import com.jslw.genz.data.LibraryDb
import com.jslw.genz.model.Song
import com.jslw.genz.playback.Player
import com.jslw.genz.util.toast

/** What a swipe on a song does – chosen in Settings › Gestures. */
object SwipeActions {
    class Action(val id: String, val label: String, val icon: Int)

    val ALL = listOf(
        Action("next", "Play next", R.drawable.ic_play_next),
        Action("queue", "Add to queue", R.drawable.ic_queue),
        Action("fav", "Favourite", R.drawable.ic_favorite),
        Action("playlist", "Add to playlist", R.drawable.ic_playlist_add),
        Action("airshare", "Send with AIR share", R.drawable.ic_airshare),
        Action("delete", "Delete from device", R.drawable.ic_delete),
        Action("none", "Nothing", R.drawable.ic_close),
    )

    fun of(id: String) = ALL.firstOrNull { it.id == id } ?: ALL.last()

    fun run(a: Activity, id: String, s: Song) {
        when (id) {
            "next" -> { Player.with(a) { it.playNext(listOf(s)) }; a.toast("Playing next: ${s.title}") }
            "queue" -> { Player.with(a) { it.addToQueue(listOf(s)) }; a.toast("Added to queue: ${s.title}") }
            "fav" -> { val on = LibraryDb.toggleFavorite(s.id); Player.dispatch(); a.toast(if (on) "Added to Favourites" else "Removed from Favourites") }
            "playlist" -> Sheets.addToPlaylist(a, listOf(s))
            "airshare" -> AirShare.send(a, listOf(s))
            "delete" -> SongDelete.confirm(a, s)
        }
    }
}
