package com.jslw.genz.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.jslw.genz.R
import com.jslw.genz.data.Library
import com.jslw.genz.data.LibraryDb
import com.jslw.genz.data.Prefs
import com.jslw.genz.playback.Lyrics
import com.jslw.genz.playback.MusicService
import com.jslw.genz.playback.Player
import com.jslw.genz.skin.NpColors
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.ui.views.DragDismissLayout
import com.jslw.genz.util.ArtLoader
import com.jslw.genz.util.SystemBars
import com.jslw.genz.util.dpi
import com.jslw.genz.util.formatTime
import com.jslw.genz.util.isLight
import com.jslw.genz.util.toast
import kotlin.math.abs

class NowPlayingActivity : BaseActivity(), Player.Listener {
    private lateinit var root: DragDismissLayout
    private lateinit var content: View
    private lateinit var art: ImageView
    private lateinit var artSquare: View
    private lateinit var coverFx: com.jslw.genz.ui.views.CoverEffectView
    private lateinit var celebrate: com.jslw.genz.ui.views.CelebrationView
    private lateinit var titleTv: TextView
    private lateinit var artistTv: TextView
    private lateinit var contextTv: TextView
    private lateinit var format: TextView
    private lateinit var lcd: TextView
    private lateinit var seek: com.jslw.genz.ui.views.SparkleSeekBar
    private lateinit var elapsed: TextView
    private lateinit var durationTv: TextView
    private lateinit var playWrap: View
    private lateinit var playIcon: ImageView
    private lateinit var shuffleBtn: ImageButton
    private lateinit var repeatBtn: ImageButton
    private lateinit var favBtn: ImageButton
    private lateinit var timerText: TextView
    private lateinit var lyricsPanel: View
    private lateinit var lyricsList: RecyclerView
    private lateinit var lyricsEmpty: TextView
    private val lyricsAdapter = LyricsAdapter()

    private val handler = Handler(Looper.getMainLooper())
    private val frames = com.jslw.genz.util.FrameTicker { tick() }
    private var lastSecond = -1L
    private var lastRemainSecond = -1L
    private var lastLyricCheck = 0L
    private var userSeeking = false
    private var shownSongId: Long? = null
    private var shownLyrics: Lyrics? = null
    private var np: NpColors = NpColors.of(skin)
    private var colorAnim: ValueAnimator? = null
    private var wasPlaying: Boolean? = null

    override fun themeRes() = if (skin.dark) R.style.Theme_GenZ_NowPlaying else R.style.Theme_GenZ_NowPlaying_Light

    private val pickLyrics = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val s = Player.current ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        LibraryDb.setLyricLink(s.id, uri.toString())
        Player.service?.reloadLyrics()
        showLyrics(true)
    }

    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            Prefs.floatingLyrics = true
            toast("Floating lyrics on")
        } else toast("Permission not given – floating lyrics stay off")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        root = findViewById(R.id.npRoot)
        content = findViewById(R.id.npContent)
        art = findViewById(R.id.npArt)
        artSquare = findViewById(R.id.npArtSquare)
        coverFx = findViewById(R.id.npCoverFx)
        celebrate = findViewById(R.id.npCelebrate)
        celebrate.setColors(skin.accent, skin.headerEnd)
        coverFx.setThemeColors(skin.accent, skin.headerEnd)
        coverFx.setEffect(skin.activeCoverEffect)
        titleTv = findViewById(R.id.npTitle)
        artistTv = findViewById(R.id.npArtist)
        contextTv = findViewById(R.id.npContext)
        format = findViewById(R.id.npFormat)
        lcd = findViewById(R.id.npLcd)
        seek = findViewById(R.id.npSeek)
        elapsed = findViewById(R.id.npElapsed)
        durationTv = findViewById(R.id.npDuration)
        playWrap = findViewById(R.id.npPlayWrap)
        playIcon = findViewById(R.id.npPlay)
        shuffleBtn = findViewById(R.id.npShuffle)
        repeatBtn = findViewById(R.id.npRepeat)
        favBtn = findViewById(R.id.npFav)
        timerText = findViewById(R.id.npTimerText)
        lyricsPanel = findViewById(R.id.npLyricsPanel)
        lyricsList = findViewById(R.id.npLyrics)
        lyricsEmpty = findViewById(R.id.npLyricsEmpty)

        applyColors(np, animate = false)
        setupInsets()
        if (Prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // swipe down anywhere to dismiss (the seek bar and a scrolled lyrics list keep their own gestures)
        root.excluded += seek
        root.excluded += lyricsList
        root.onDismiss = { finish(); overridePendingTransition(0, 0) }
        root.onDrag = { f -> art.alpha = 1f - f * 0.4f }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (lyricsPanel.visibility == View.VISIBLE) showLyrics(false) else root.dismiss()
            }
        })

        findViewById<View>(R.id.npClose).setOnClickListener { root.dismiss() }
        findViewById<View>(R.id.npMore).setOnClickListener { moreMenu(it) }
        playWrap.setOnClickListener { pulse(playWrap); Player.service?.toggle() }
        findViewById<View>(R.id.npNext).setOnClickListener { slideArt(-1); Player.service?.next() }
        findViewById<View>(R.id.npPrev).setOnClickListener { slideArt(1); Player.service?.prev() }
        shuffleBtn.setOnClickListener { Player.service?.let { it.setShuffleMode(!it.shuffle); toast(if (it.shuffle) "Shuffle on" else "Shuffle off") } }
        repeatBtn.setOnClickListener {
            Player.service?.let { it.cycleRepeat(); toast(arrayOf("Repeat off", "Repeat all", "Repeat one")[it.repeatMode]) }
        }
        favBtn.setOnClickListener {
            val s = Player.current ?: return@setOnClickListener
            val on = LibraryDb.toggleFavorite(s.id)
            heartPop(favBtn, on)
            updateFav(on)
            celebrateAt(on)
        }
        findViewById<View>(R.id.npLyricsBtn).setOnClickListener { showLyrics(lyricsPanel.visibility != View.VISIBLE) }
        findViewById<View>(R.id.npTimerBtn).setOnClickListener { Sheets.sleepTimer(this) }
        findViewById<View>(R.id.npEqBtn).setOnClickListener {
            startActivity(Intent(this, EqualizerActivity::class.java))
            com.jslw.genz.util.Transitions.open(this)
        }
        findViewById<View>(R.id.npQueueBtn).setOnClickListener { Sheets.queue(this) }

        seek.onSeekStart = { userSeeking = true }
        seek.onSeeking = { v -> elapsed.text = formatTime(v.toLong()) }
        seek.onSeekEnd = { v ->
            userSeeking = false
            Player.service?.seekTo(v.toLong())
        }

        setupArtGestures()
        lyricsList.layoutManager = LinearLayoutManager(this)
        // tapping empty space (or the "no lyrics" message) goes back to the cover
        lyricsEmpty.setOnClickListener { showLyrics(false) }
        lyricsPanel.setOnClickListener { showLyrics(false) }
        val lyricTap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent) = true
        })
        lyricsList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (lyricTap.onTouchEvent(e) && rv.findChildViewUnder(e.x, e.y) == null) showLyrics(false)
                return false
            }
        })
        lyricsList.adapter = lyricsAdapter
        lyricsList.post { val pad = lyricsList.height / 2; lyricsList.setPadding(0, pad, 0, pad) }

        Player.connect(this)
        debugShot()
    }

    /** Debug screenshot tour: open lyrics / queue, or trigger the favourite celebration. */
    private fun debugShot() {
        if (!com.jslw.genz.BuildConfig.DEBUG) return
        when (intent.getStringExtra(Tour.EXTRA_SHOT)) {
            "lyrics" -> handler.postDelayed({ showLyrics(true) }, 1500)
            "queue" -> handler.postDelayed({ Sheets.queue(this) }, 1500)
        }
    }

    private fun setupInsets() {
        if (Build.VERSION.SDK_INT >= 21) {
            SystemBars.setup(this, content, np.bgTop, np.bgBottom)
            ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
    }

    /** Touch: cover effect · double-tap: play/pause · swipe sideways: next/prev · long-press: song menu. */
    private fun setupArtGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                pulse(artSquare); Player.service?.toggle(); return true
            }
            override fun onLongPress(e: MotionEvent) {
                Player.current?.let { Sheets.songOptions(this@NowPlayingActivity, it) }
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val total = e2.x - (e1?.x ?: e2.x)
                if (abs(total) > abs(e2.y - (e1?.y ?: e2.y))) {
                    artSquare.translationX = total * 0.6f
                    artSquare.rotation = total / artSquare.width.coerceAtLeast(1) * 6f
                }
                return false
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) > dpi(60) && abs(vx) > abs(vy)) {
                    val svc = Player.service ?: return false
                    val dir = if (dx < 0) -1 else 1
                    artSquare.animate().translationX(dir * artSquare.width.toFloat()).rotation(dir * 8f).alpha(0f).setDuration(160).withEndAction {
                        if (dir < 0) svc.next() else svc.prev()
                        artSquare.translationX = -dir * artSquare.width * 0.6f
                        artSquare.rotation = 0f
                        artSquare.animate().translationX(0f).alpha(1f).setDuration(260).start()
                    }.start()
                    return true
                }
                return false
            }
        })
        artSquare.setOnTouchListener { _, e ->
            coverFx.feed(e)
            val handled = gd.onTouchEvent(e)
            if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && artSquare.alpha == 1f && artSquare.translationX != 0f) {
                artSquare.animate().translationX(0f).rotation(0f).setDuration(220).start()
            }
            handled
        }
    }

    // ---------------------------------------------------------------- updates
    override fun onStart() {
        super.onStart()
        Player.add(this)
        onPlayerChanged()
        frames.start()
    }

    override fun onStop() {
        Player.remove(this)
        frames.stop()
        super.onStop()
    }

    override fun onPlayerChanged() {
        val svc = Player.service ?: return
        val s = svc.current
        if (s == null) { finish(); return }
        if (shownSongId != s.id) {
            shownSongId = s.id
            titleTv.text = s.title
            titleTv.isSelected = true // marquee
            artistTv.text = listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" — ")
            contextTv.text = svc.contextLabel.ifBlank { s.album }
            lcd.text = "  ♪ ${s.title} — ${s.artist}   ·   ${s.album}  "
            lcd.isSelected = true
            updateFav(LibraryDb.isFavorite(s.id))
            ArtLoader.load(art, s, dpi(360).coerceAtMost(900), large = true) { bmp -> onArt(bmp); coverFx.setArt(bmp) }
            if (svc.lyrics == null) shownLyrics = null
        }
        val playing = svc.isPlaying
        if (wasPlaying != playing) {
            wasPlaying = playing
            playIcon.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(90).withEndAction {
                com.jslw.genz.skin.Icons.set(playIcon, if (playing) R.drawable.ic_pause_large else R.drawable.ic_play_large)
                playIcon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
            }.start()
            // art "breathes": slightly smaller while paused, like modern players
            artSquare.animate().scaleX(if (playing) 1f else 0.92f).scaleY(if (playing) 1f else 0.92f)
                .setDuration(380).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        }
        if (playing) frames.start()
        coverFx.ambient = playing
        tintToggle(shuffleBtn, svc.shuffle)
        com.jslw.genz.skin.Icons.set(repeatBtn, if (svc.repeatMode == MusicService.REPEAT_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        tintToggle(repeatBtn, svc.repeatMode != MusicService.REPEAT_OFF)
        val info = svc.formatInfo
        if (info != null) {
            format.text = info.label() + if (svc.bitPerfectStatus.startsWith("Bit-perfect:")) " · BIT-PERFECT" else ""
            if (format.visibility != View.VISIBLE) { format.alpha = 0f; format.visibility = View.VISIBLE; format.animate().alpha(1f).setDuration(250).start() }
        } else format.visibility = View.GONE
        if (svc.lyrics !== shownLyrics) {
            shownLyrics = svc.lyrics
            lyricsAdapter.set(svc.lyrics)
            lyricsEmpty.visibility = if (svc.lyrics == null) View.VISIBLE else View.GONE
        }
        tick()
    }

    private fun tick() {
        val svc = Player.service ?: return
        val dur = svc.duration().coerceAtLeast(1)
        val pos = svc.position().coerceIn(0, dur)
        // Runs every display frame (60–144 Hz): only the slider moves per frame; text changes once a second.
        if (!userSeeking) {
            if (seek.max != dur.toFloat()) seek.max = dur.toFloat()
            seek.progress = pos.toFloat()
            seek.playing = svc.isPlaying
            val sec = pos / 1000
            if (sec != lastSecond) { lastSecond = sec; elapsed.text = formatTime(pos) }
        }
        val remain = (dur - pos) / 1000
        if (remain != lastRemainSecond) {
            lastRemainSecond = remain
            durationTv.text = "-" + formatTime(dur - pos)
            val sleep = svc.sleepRemainingMs()
            if (sleep > 0) { timerText.text = formatTime(sleep); timerText.visibility = View.VISIBLE } else timerText.visibility = View.GONE
        }
        if (!svc.isPlaying && !userSeeking) frames.stop()
        val now = android.os.SystemClock.uptimeMillis()
        if (lyricsPanel.visibility == View.VISIBLE && now - lastLyricCheck > 50) {
            lastLyricCheck = now
            val l = shownLyrics
            if (l != null && l.synced) {
                val i = l.indexAt(pos)
                if (lyricsAdapter.setCurrent(i) && i >= 0) {
                    val sc = object : LinearSmoothScroller(this) {
                        override fun calculateDtToFit(vs: Int, ve: Int, bs: Int, be: Int, pref: Int) = (bs + (be - bs) / 2) - (vs + (ve - vs) / 2)
                        override fun calculateSpeedPerPixel(dm: android.util.DisplayMetrics) = 60f / dm.densityDpi
                    }
                    sc.targetPosition = i
                    lyricsList.layoutManager?.startSmoothScroll(sc)
                }
            }
        }
    }

    private fun updateFav(on: Boolean) {
        com.jslw.genz.skin.Icons.set(favBtn, if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        com.jslw.genz.skin.Icons.tintFor(favBtn, android.content.res.ColorStateList.valueOf(if (on) np.accent else np.text))
    }

    private fun tintToggle(b: ImageButton, on: Boolean) {
        com.jslw.genz.skin.Icons.tintFor(b, android.content.res.ColorStateList.valueOf(if (on) np.accent else SkinPainter.withAlpha(np.text, 0x80)))
    }

    /** Bursts from the centre of the cover, filling the screen with hearts and confetti. */
    private fun celebrateAt(on: Boolean) {
        val a = IntArray(2); val r = IntArray(2)
        artSquare.getLocationInWindow(a); celebrate.getLocationInWindow(r)
        val cx = a[0] - r[0] + artSquare.width / 2f
        val cy = a[1] - r[1] + artSquare.height / 2f
        val size = minOf(artSquare.width, artSquare.height).toFloat().coerceAtLeast(dpi(160).toFloat())
        if (on) celebrate.celebrate(cx, cy, size) else celebrate.unlove(cx, cy, size)
    }

    /** The heart button itself jumps, spins a little and settles. */
    private fun heartPop(v: View, on: Boolean) {
        v.animate().cancel()
        if (on) {
            v.animate().scaleX(1.6f).scaleY(1.6f).rotation(-12f).setDuration(140).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(420)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }.start()
        } else {
            v.animate().scaleX(0.7f).scaleY(0.7f).setDuration(120).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
            }.start()
        }
    }

    private fun pulse(v: View) {
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }.start()
    }

    private fun slideArt(dir: Int) {
        artSquare.animate().translationX(dir * artSquare.width * 0.25f).alpha(0.4f).setDuration(110).withEndAction {
            artSquare.animate().translationX(0f).alpha(1f).setDuration(240).start()
        }.start()
    }

    private fun showLyrics(show: Boolean) {
        if (show == (lyricsPanel.visibility == View.VISIBLE)) return
        if (show) {
            lyricsEmpty.visibility = if (shownLyrics == null) View.VISIBLE else View.GONE
            lyricsPanel.alpha = 0f
            lyricsPanel.visibility = View.VISIBLE
            lyricsPanel.animate().alpha(1f).setDuration(220).start()
            artSquare.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(220).withEndAction { artSquare.visibility = View.INVISIBLE }.start()
            lyricsAdapter.setCurrent(-1)
        } else {
            artSquare.visibility = View.VISIBLE
            artSquare.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
            lyricsPanel.animate().alpha(0f).setDuration(180).withEndAction { lyricsPanel.visibility = View.GONE }.start()
        }
    }

    // ---------------------------------------------------------------- colours from album art
    private fun onArt(bmp: Bitmap?) {
        // progress bar: this song's own colours (or the theme's when the setting is off / no cover)
        val songCols = com.jslw.genz.util.SongColors.from(bmp)
        seek.setStyle(skin.progressStyle, songCols ?: skin.progressColors)
        songCols?.let { celebrate.setColors(it[2], it[1]) }
        val base = NpColors.of(skin)
        if (bmp == null || !(skin.npDynamicColors && Prefs.npDynamicColors)) { applyColors(base, true); return }
        Palette.from(bmp).maximumColorCount(16).generate { p ->
            if (p == null || isFinishing) return@generate
            val dom = p.getDarkMutedColor(p.getDominantColor(base.bgTop))
            val top = ColorUtils.blendARGB(dom, Color.BLACK, 0.15f)
            val bottom = ColorUtils.blendARGB(dom, Color.BLACK, 0.7f)
            var accent = p.getLightVibrantColor(p.getVibrantColor(base.accent))
            if (ColorUtils.calculateContrast(accent, bottom) < 3.0) accent = ColorUtils.blendARGB(accent, Color.WHITE, 0.5f)
            val text = Color.WHITE
            val on = if (isLight(accent)) 0xFF111111.toInt() else Color.WHITE
            applyColors(NpColors(top, bottom, text, 0xB3FFFFFF.toInt(), accent, on), true)
        }
    }

    private fun applyColors(target: NpColors, animate: Boolean) {
        val from = np
        np = target
        colorAnim?.cancel()
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(from.bgTop, from.bgBottom))
        content.background = bg
        if (!animate) {
            bg.colors = intArrayOf(target.bgTop, target.bgBottom)
        } else {
            val ev = ArgbEvaluator()
            colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                addUpdateListener {
                    val f = it.animatedValue as Float
                    if (Build.VERSION.SDK_INT >= 16) bg.colors = intArrayOf(
                        ev.evaluate(f, from.bgTop, target.bgTop) as Int,
                        ev.evaluate(f, from.bgBottom, target.bgBottom) as Int,
                    )
                }
                start()
            }
        }
        SkinPainter.apply(content, skin, target)
        if (seek.styleName != skin.progressStyle) seek.setStyle(skin.progressStyle, skin.progressColors)
        // keep the play-head/halo in step with the song accent even when the gradient comes from the cover
        seek.setColors(target.accent, SkinPainter.withAlpha(target.text, 0x33))
        lyricsAdapter.colors(target)
        Player.service?.let {
            tintToggle(shuffleBtn, it.shuffle)
            tintToggle(repeatBtn, it.repeatMode != MusicService.REPEAT_OFF)
        }
        Player.current?.let { updateFav(LibraryDb.isFavorite(it.id)) }
        if (Build.VERSION.SDK_INT >= 21) SystemBars.colorIcons(window, content, target.bgTop, target.bgBottom)
    }

    // ---------------------------------------------------------------- ⋮ menu
    private fun moreMenu(anchor: View) {
        val s = Player.current ?: return
        val svc = Player.service ?: return
        val m = PopupMenu(this, anchor)
        val items = linkedMapOf<String, () -> Unit>(
            "Add to playlist…" to { Sheets.addToPlaylist(this, listOf(s)) },
            "Go to album" to { Sheets.openInMain(this, MainActivity.EXTRA_ALBUM, Library.albumOf(s)?.id ?: s.albumId) },
            "Go to artist" to { Sheets.openInMain(this, MainActivity.EXTRA_ARTIST, s.artist) },
            "Choose lyrics file (.lrc / .txt)…" to { pickLyrics.launch(arrayOf("*/*")) },
            (if (Prefs.floatingLyrics) "Floating lyrics: on" else "Floating lyrics: off") to { toggleFloating() },
            "Edit tags & cover" to { startActivity(Intent(this, TagEditorActivity::class.java).putExtra("song", s.id)) },
            "Make ringtone" to { startActivity(Intent(this, RingtoneCutterActivity::class.java).putExtra("song", s.id)) },
            "File info" to { Sheets.fileInfo(this, s) },
        )
        items["Cover touch effect…"] = { Sheets.coverEffect(this) { coverFx.setEffect(skin.activeCoverEffect) } }
        if (svc.canChangeSpeed) items["Speed & pitch…"] = { Sheets.speedPitch(this) }
        if (LibraryDb.lyricLink(s.id) != null) items["Forget chosen lyrics file"] = { LibraryDb.setLyricLink(s.id, null); svc.reloadLyrics() }
        items.keys.forEach { m.menu.add(it) }
        m.setOnMenuItemClickListener { items[it.title.toString()]?.invoke(); true }
        m.show()
    }

    private fun toggleFloating() {
        if (Prefs.floatingLyrics) { Prefs.floatingLyrics = false; toast("Floating lyrics off"); return }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Sheets.explainPermission(this, Sheets.OVERLAY_TITLE,
                "Floating lyrics show the current line of the song in a small bubble on top of other apps. To draw " +
                    "that bubble, Android requires the \"Display over other apps\" permission. GEN-Z uses it only for the lyrics bubble.") {
                overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            return
        }
        Prefs.floatingLyrics = true
        toast("Floating lyrics on – they appear when you leave the app")
    }

    override fun finish() {
        super.finish()
        if (root.translationY == 0f && content.translationY == 0f) overridePendingTransition(R.anim.stay, R.anim.slide_down)
    }

    // ---------------------------------------------------------------- lyrics list
    inner class LyricsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var lines: List<com.jslw.genz.playback.LyricLine> = emptyList()
        private var synced = false
        private var current = -1
        private var c: NpColors = np

        fun set(l: Lyrics?) {
            lines = l?.lines ?: emptyList(); synced = l?.synced == true; current = -1
            notifyDataSetChanged()
        }

        fun colors(n: NpColors) { c = n; notifyDataSetChanged() }

        /** @return true if the highlighted line changed. */
        fun setCurrent(i: Int): Boolean {
            if (i == current) return false
            val old = current
            current = i
            if (old >= 0) notifyItemChanged(old)
            if (i >= 0) notifyItemChanged(i)
            return true
        }

        override fun getItemCount() = lines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
                setPadding(dpi(20), dpi(9), dpi(20), dpi(9))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_20))
                typeface = skin.boldTypeface
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val tv = h.itemView as TextView
            val line = lines[position]
            tv.text = line.text.ifBlank { "♪" }
            val active = !synced || position == current
            tv.setTextColor(if (active) c.text else SkinPainter.withAlpha(c.text, 0x66))
            tv.animate().scaleX(if (synced && active) 1.06f else 1f).scaleY(if (synced && active) 1.06f else 1f).setDuration(200).start()
            tv.setOnClickListener {
                if (synced) Player.service?.seekTo(line.timeMs) else showLyrics(false)
            }
        }
    }
}
