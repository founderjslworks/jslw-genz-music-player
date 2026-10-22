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
    private lateinit var coverFx: com.jslw.genz.ui.views.CoverEffectView
    private var fxTracking = false
    private lateinit var charging: com.jslw.genz.ui.views.ChargingDoodleView
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
            i ?: return
            val status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val plugged = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val lvl = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            if (lvl >= 0 && scale > 0) charging.level = lvl / scale.toFloat()
            charging.visibility = if (plugged) View.VISIBLE else View.GONE
        }
    }
    private val artLoc = IntArray(2)
    private val rootLoc = IntArray(2)
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
        coverFx = findViewById(R.id.lockCoverFx)
        charging = findViewById(R.id.lockCharging)
        charging.accent = SkinManager.current.accent
        charging.typeface = SkinManager.current.boldTypeface
        coverFx.setThemeColors(SkinManager.current.accent, SkinManager.current.headerEnd)
        coverFx.setEffect(SkinManager.current.activeCoverEffect)
        com.jslw.genz.util.SystemBars.setup(this, findViewById(R.id.lockRoot), 0xFF000000.toInt(), 0xFF000000.toInt())
        val tf = SkinManager.current.typeface
        listOf<TextView>(titleTv, artist, findViewById(R.id.lockClock), findViewById(R.id.lockDate)).forEach { it.typeface = tf }
        titleTv.typeface = SkinManager.current.boldTypeface
        progress.setIndicatorColor(SkinManager.current.accent)

        findViewById<View>(R.id.lockPlayWrap).setOnClickListener { Player.service?.toggle() }
        findViewById<com.jslw.genz.ui.views.SlideToUnlockBar>(R.id.lockSlide).apply {
            text = "slide to unlock"
            typeface = SkinManager.current.typeface
            tint = SkinManager.current.accent
            onUnlock = { close() }
        }
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
        findViewById<View>(R.id.lockRoot).setOnTouchListener { root, e ->
            // the cover plays its touch effect when the finger starts on it
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                coverFx.getLocationOnScreen(artLoc)
                fxTracking = e.rawX >= artLoc[0] && e.rawX <= artLoc[0] + coverFx.width &&
                    e.rawY >= artLoc[1] && e.rawY <= artLoc[1] + coverFx.height
            }
            if (fxTracking) {
                root.getLocationOnScreen(rootLoc)
                coverFx.getLocationOnScreen(artLoc) // the cover moves while swiping up
                coverFx.feed(e, (artLoc[0] - rootLoc[0]).toFloat(), (artLoc[1] - rootLoc[1]).toFloat())
            }
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
        registerReceiver(batteryReceiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStop() {
        Player.remove(this)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
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
            ArtLoader.load(art, s, dpi(320), large = true) { bmp -> setBackdrop(bmp); coverFx.setArt(bmp) }
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
