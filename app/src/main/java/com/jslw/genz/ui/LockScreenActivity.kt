package com.jslw.genz.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.jslw.genz.R
import com.jslw.genz.playback.Player
import com.jslw.genz.skin.Icons
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.util.ArtLoader
import com.jslw.genz.util.FrameTicker
import com.jslw.genz.util.dpi
import kotlin.math.abs

/**
 * Full-screen player shown over the lock screen while music plays. It never unlocks the phone:
 * swiping up just closes it and the normal (secure) lock screen is underneath.
 */
class LockScreenActivity : BaseActivity(), Player.Listener {
    private lateinit var bg: ImageView
    private lateinit var art: ImageView
    private lateinit var titleTv: TextView
    private lateinit var artist: TextView
    private lateinit var play: ImageView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var content: View
    private var shown: Long? = null
    private val frames = FrameTicker { tick() }

    override fun themeRes() = R.style.Theme_GenZ_Dark

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        setContentView(R.layout.activity_lock)
        bg = findViewById(R.id.lockBg)
        art = findViewById(R.id.lockArt)
        titleTv = findViewById(R.id.lockTitle)
        artist = findViewById(R.id.lockArtist)
        play = findViewById(R.id.lockPlay)
        progress = findViewById(R.id.lockProgress)
        content = findViewById(R.id.lockContent)
        com.jslw.genz.util.SystemBars.setup(this, findViewById(R.id.lockRoot), 0xFF000000.toInt(), 0xFF000000.toInt())
        val tf = SkinManager.current.typeface
        listOf<TextView>(titleTv, artist, findViewById(R.id.lockClock), findViewById(R.id.lockDate)).forEach { it.typeface = tf }
        titleTv.typeface = SkinManager.current.boldTypeface
        progress.setIndicatorColor(SkinManager.current.accent)

        findViewById<View>(R.id.lockPlayWrap).setOnClickListener { Player.service?.toggle() }
        findViewById<View>(R.id.lockNext).setOnClickListener { Player.service?.next() }
        findViewById<View>(R.id.lockPrev).setOnClickListener { Player.service?.prev() }

        // swipe up to close, the content following the finger
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val total = (e2.y - (e1?.y ?: e2.y)).coerceAtMost(0f)
                content.translationY = total
                content.alpha = 1f + total / content.height.coerceAtLeast(1)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy < -1200 && abs(vy) > abs(vx)) { close(); return true }
                return false
            }
        })
        findViewById<View>(R.id.lockRoot).setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                if (content.translationY < -dpi(160)) close()
                else content.animate().translationY(0f).alpha(1f).setDuration(220).start()
            }
            true
        }
        Player.connect(this)
    }

    private fun close() {
        content.animate().translationY(-content.height.toFloat()).alpha(0f).setDuration(200).withEndAction {
            finish(); overridePendingTransition(0, android.R.anim.fade_out)
        }.start()
    }

    override fun onStart() {
        super.onStart()
        Player.add(this)
        onPlayerChanged()
    }

    override fun onStop() {
        Player.remove(this)
        frames.stop()
        super.onStop()
    }

    override fun onPlayerChanged() {
        val svc = Player.service ?: return
        val s = svc.current ?: run { finish(); return }
        if (shown != s.id) {
            shown = s.id
            titleTv.text = s.title; titleTv.isSelected = true
            artist.text = listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" — ")
            ArtLoader.load(art, s, dpi(320), large = true) { bmp -> setBackdrop(bmp) }
        }
        Icons.set(play, if (svc.isPlaying) R.drawable.ic_pause_large else R.drawable.ic_play_large)
        if (svc.isPlaying) frames.start() else { frames.stop(); tick() }
    }

    /** Cheap, smooth "blur": shrink the art to a few pixels and let bilinear filtering scale it up. */
    private fun setBackdrop(bmp: Bitmap?) {
        if (bmp == null) { bg.setImageDrawable(null); return }
        val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        bg.alpha = 0f
        bg.setImageBitmap(small)
        bg.animate().alpha(1f).setDuration(400).start()
    }

    private fun tick() {
        val svc = Player.service ?: return
        val d = svc.duration()
        val p = if (d > 0) (svc.position() * 1000 / d).toInt() else 0
        if (p != progress.progress) progress.progress = p
    }
}
