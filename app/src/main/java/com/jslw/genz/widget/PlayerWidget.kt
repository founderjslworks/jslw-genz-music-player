package com.jslw.genz.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.PendingIntentCompat
import com.jslw.genz.R
import com.jslw.genz.model.Song
import com.jslw.genz.playback.Player
import com.jslw.genz.playback.MusicService
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.ui.MainActivity
import com.jslw.genz.util.ArtLoader

/** Wide 4×1 widget. */
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.refresh(ctx)
}

/** Square 2×2 cover widget. */
class SquareWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.refresh(ctx)
}

/** Large 4×2 widget. */
class LargeWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.refresh(ctx)
}

/**
 * Updates every home-screen / lock-screen (KitKat) widget, coloured with the active theme.
 * Only runs when the song, play state or art changes – no periodic updates, no battery cost.
 */
object Widgets {
    private var lastKey = ""
    private val KINDS = listOf(
        PlayerWidget::class.java to R.layout.widget_player,
        SquareWidget::class.java to R.layout.widget_square,
        LargeWidget::class.java to R.layout.widget_large,
    )

    fun update(ctx: Context, song: Song?, playing: Boolean, art: Bitmap?, force: Boolean = false) {
        val key = "${song?.id}|$playing|${art?.hashCode()}|${SkinManager.current.id}|${SkinManager.generation}"
        if (!force && key == lastKey) return
        lastKey = key
        val mgr = AppWidgetManager.getInstance(ctx)
        val small = art?.let { Bitmap.createScaledBitmap(it, 320, 320, true) }
        val cols = WidgetArt.palette(small, SkinManager.current)
        for ((cls, layout) in KINDS) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isEmpty()) continue
            try {
                mgr.updateAppWidget(ids, views(ctx, layout, song, playing, small, cols))
            } catch (e: Exception) {
                // never let a widget problem affect playback
            }
        }
    }

    /** Called by the system (e.g. a widget was just added) – uses whatever the player currently has. */
    fun refresh(ctx: Context) {
        val s = Player.service
        val song = s?.current
        Thread {
            val art = song?.let { ArtLoader.loadSync(ctx.applicationContext, it, 256) }
            update(ctx.applicationContext, song, s?.isPlaying == true, art, force = true)
        }.start()
    }

    /** Android 8+: ask the launcher to place a widget directly from the app. */
    fun canPin(ctx: Context) = Build.VERSION.SDK_INT >= 26 && AppWidgetManager.getInstance(ctx).isRequestPinAppWidgetSupported

    fun pin(ctx: Context, which: Int): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val cls = KINDS.getOrNull(which)?.first ?: return false
        return AppWidgetManager.getInstance(ctx).requestPinAppWidget(ComponentName(ctx, cls), null, null)
    }

    private fun service(ctx: Context, action: String, rc: Int): PendingIntent? {
        val i = Intent(ctx, MusicService::class.java).setAction(action)
        // Android 8+ won't start a background service from a widget, so use a foreground-service intent there.
        return if (Build.VERSION.SDK_INT >= 26)
            PendingIntent.getForegroundService(ctx, 100 + rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        else PendingIntentCompat.getService(ctx, 100 + rc, i, PendingIntent.FLAG_UPDATE_CURRENT, false)
    }

    private fun views(ctx: Context, layout: Int, song: Song?, playing: Boolean, art: Bitmap?, cols: IntArray): RemoteViews {
        val skin = SkinManager.current
        val v = RemoteViews(ctx.packageName, layout)
        val d = 2f // render at 2x for crisp edges on any launcher
        val square = layout == R.layout.widget_square
        val (bw, bh) = when (layout) {
            R.layout.widget_square -> 260 to 260
            R.layout.widget_large -> 500 to 250
            else -> 500 to 130
        }
        v.setImageViewBitmap(R.id.wBg, WidgetArt.background((bw * d).toInt(), (bh * d).toInt(), art, skin, cols, d, square))
        v.setInt(R.id.wBg, "setColorFilter", 0)
        v.setInt(R.id.wBg, "setAlpha", 255)
        // always light text on the rich backdrop
        v.setTextColor(R.id.wTitle, 0xFFFFFFFF.toInt())
        v.setTextColor(R.id.wArtist, 0xD9FFFFFF.toInt())
        if (layout == R.layout.widget_large) v.setTextColor(R.id.wAlbum, 0x99FFFFFF.toInt())
        for (id in intArrayOf(R.id.wPrev, R.id.wNext)) v.setInt(id, "setColorFilter", 0xF2FFFFFF.toInt())
        v.setInt(R.id.wPlay, "setColorFilter", 0)
        v.setImageViewBitmap(R.id.wPlay, WidgetArt.playButton(playing, (56 * d).toInt(), cols, d))

        v.setTextViewText(R.id.wTitle, song?.title ?: ctx.getString(R.string.nothing_playing))
        v.setTextViewText(R.id.wArtist, song?.artist ?: ctx.getString(R.string.tap_to_play))
        if (layout == R.layout.widget_large) v.setTextViewText(R.id.wAlbum, song?.album ?: "GEN-Z Music")
        if (square) {
            // the cover is the backdrop itself
            v.setViewVisibility(R.id.wArt, android.view.View.GONE)
        } else if (art != null) {
            val size = if (layout == R.layout.widget_large) (104 * d).toInt() else (56 * d).toInt()
            v.setImageViewBitmap(R.id.wArt, WidgetArt.cover(art, size, d))
            v.setInt(R.id.wArt, "setColorFilter", 0)
        } else {
            v.setImageViewResource(R.id.wArt, R.drawable.ic_note)
            v.setInt(R.id.wArt, "setColorFilter", 0xFFFFFFFF.toInt())
        }
        v.setOnClickPendingIntent(R.id.wPlay, service(ctx, MusicService.ACTION_TOGGLE, 1))
        v.setOnClickPendingIntent(R.id.wNext, service(ctx, MusicService.ACTION_NEXT, 2))
        v.setOnClickPendingIntent(R.id.wPrev, service(ctx, MusicService.ACTION_PREV, 3))
        v.setOnClickPendingIntent(
            R.id.wRoot,
            PendingIntentCompat.getActivity(ctx, 104, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT, false)
        )
        return v
    }
}
