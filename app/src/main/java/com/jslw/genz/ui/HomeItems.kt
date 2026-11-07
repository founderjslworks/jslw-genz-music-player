package com.jslw.genz.ui

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jslw.genz.data.Prefs
import com.jslw.genz.playback.Player

/** What the Home screen shows – chosen with check boxes in Settings. Settings itself is always shown. */
object HomeItems {
    val ALL = listOf(
        "flow" to "Album Flow", "favs" to "Favourites", "playlists" to "Playlists", "recent" to "Recently played",
        "most" to "Most played", "artists" to "Artists", "albums" to "Albums", "songs" to "Songs", "genres" to "Genres",
        "folders" to "Folders", "shuffle" to "Shuffle Songs", "nowplaying" to "Now Playing", "refresh" to "Refresh library",
        "sound" to "Sound & equalizer", "themes" to "Themes", "sleep" to "Sleep Timer",
    )
    private val DEFAULT_OFF = setOf("recent", "most")

    fun visible(): Set<String> {
        val saved = Prefs.homeItems
        return if (saved.isBlank()) ALL.map { it.first }.filter { it !in DEFAULT_OFF }.toSet()
        else saved.split(',').filter { it.isNotBlank() }.toSet()
    }

    fun summary(): String {
        val v = visible()
        return ALL.filter { it.first in v }.joinToString(", ") { it.second }.ifEmpty { "Only Settings" }
    }

    fun choose(a: Activity, done: () -> Unit) {
        val v = visible()
        val checked = BooleanArray(ALL.size) { ALL[it].first in v }
        MaterialAlertDialogBuilder(a)
            .setTitle("Show on Home")
            .setMultiChoiceItems(ALL.map { it.second }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                Prefs.homeItems = ALL.filterIndexed { i, _ -> checked[i] }.joinToString(",") { it.first }.ifEmpty { "," }
                Player.dispatch()
                done()
            }
            .setNeutralButton("Defaults") { _, _ -> Prefs.homeItems = ""; Player.dispatch(); done() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
