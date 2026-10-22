package com.jslw.genz.ui

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.jslw.genz.BuildConfig
import com.jslw.genz.data.Library
import com.jslw.genz.data.Prefs
import com.jslw.genz.playback.Player

/**
 * DEBUG BUILDS ONLY – lets the screenshot job open any screen directly:
 *   adb shell am start -n com.jslw.genz/.ui.MainActivity --es shot nowplaying --es theme dot_matrix --es fx ripple
 * Release builds ignore all of this (BuildConfig.DEBUG is false).
 */
object Tour {
    const val EXTRA_SHOT = "shot"
    const val EXTRA_THEME = "theme"
    const val EXTRA_FX = "fx"

    /** Called before the theme is applied, so the screen opens in the requested theme. */
    fun prepare(i: Intent?) {
        if (!BuildConfig.DEBUG || i == null) return
        i.getStringExtra(EXTRA_THEME)?.let { com.jslw.genz.skin.SkinManager.select(it) }
        i.getStringExtra(EXTRA_FX)?.let { Prefs.coverEffect = it }
        if (i.hasExtra(EXTRA_SHOT)) Prefs.firstRunDone = true
    }

    fun run(a: MainActivity, i: Intent?) {
        if (!BuildConfig.DEBUG || i == null) return
        val shot = i.getStringExtra(EXTRA_SHOT) ?: return
        i.removeExtra(EXTRA_SHOT)
        val whenReady = {
            Handler(Looper.getMainLooper()).postDelayed({ open(a, shot) }, 1600)
        }
        if (Library.loaded) whenReady() else {
            val l = object : () -> Unit {
                override fun invoke() { Library.removeListener(this); whenReady() }
            }
            Library.addListener(l)
        }
    }

    private fun play(a: Activity, index: Int = 0) {
        val songs = Library.sortedSongs()
        if (songs.isNotEmpty()) Player.play(a, songs, index.coerceIn(0, songs.lastIndex), "Demo mix")
    }

    private fun open(a: MainActivity, shot: String) {
        when (shot) {
            "home" -> {}
            "albumflow" -> { play(a, 2); a.open(AlbumFlowFragment()) }
            "albums" -> a.open(BrowseFragment.of("albums"))
            "songs" -> { play(a, 1); a.open(SongsFragment.forSource(com.jslw.genz.model.Source.AllSongs)) }
            "playlists" -> a.open(BrowseFragment.of("playlists"))
            "nowplaying", "lyrics", "queue", "fx", "love" -> {
                play(a, 0)
                Handler(Looper.getMainLooper()).postDelayed({
                    a.startActivity(Intent(a, NowPlayingActivity::class.java).putExtra(EXTRA_SHOT, shot))
                }, 900)
            }
            "lock" -> {
                play(a, 3)
                Handler(Looper.getMainLooper()).postDelayed({ a.startActivity(Intent(a, LockScreenActivity::class.java)) }, 900)
            }
            "equalizer" -> { play(a, 0); a.startActivity(Intent(a, EqualizerActivity::class.java)) }
            "themes" -> a.startActivity(Intent(a, ThemesActivity::class.java))
            "editor" -> a.startActivity(Intent(a, ThemeEditorActivity::class.java))
            "settings" -> a.startActivity(Intent(a, SettingsActivity::class.java))
            "search" -> a.startActivity(Intent(a, SearchActivity::class.java).putExtra("q", "neon"))
        }
    }
}
