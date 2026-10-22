package com.jslw.genz.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.jslw.genz.model.Song
import java.util.concurrent.CopyOnWriteArrayList

/** UI-side handle to the MusicService. Activities register a listener and get called on every change. */
object Player {
    fun interface Listener { fun onPlayerChanged() }

    var service: MusicService? = null
        private set
    private var binding = false
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val pending = ArrayList<(MusicService) -> Unit>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MusicService.LocalBinder).service
            binding = false
            val todo = ArrayList(pending)
            pending.clear()
            todo.forEach { it(service!!) }
            dispatch()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            dispatch()
        }
    }

    fun connect(ctx: Context) {
        if (service != null || binding) return
        binding = true
        val app = ctx.applicationContext
        app.bindService(Intent(app, MusicService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    /** Runs [block] now if connected, or as soon as the service is bound. */
    fun with(ctx: Context, block: (MusicService) -> Unit) {
        val s = service
        if (s != null) block(s) else {
            pending += block
            connect(ctx)
        }
    }

    fun add(l: Listener) { listeners += l }
    fun remove(l: Listener) { listeners -= l }
    fun dispatch() { listeners.forEach { it.onPlayerChanged() } }

    val current: Song? get() = service?.current
    val isPlaying: Boolean get() = service?.isPlaying == true

    fun play(ctx: Context, songs: List<Song>, start: Int, label: String) =
        with(ctx) { it.playQueue(songs, start, label) }

    fun shuffle(ctx: Context, songs: List<Song>, label: String) =
        with(ctx) { it.playShuffled(songs, label) }
}
