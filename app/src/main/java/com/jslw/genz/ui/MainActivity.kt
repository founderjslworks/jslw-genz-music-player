package com.jslw.genz.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.jslw.genz.ui.views.SparkleSeekBar
import com.jslw.genz.R
import com.jslw.genz.data.Library
import com.jslw.genz.playback.Player
import com.jslw.genz.util.ArtLoader
import com.jslw.genz.util.dpi
import com.jslw.genz.util.toast
import kotlin.math.abs

/**
 * Classic, classic drill-down navigation: Music ▸ Artists ▸ Albums ▸ Songs, sliding left/right,
 * with a mini player that you swipe up (or tap) to open Now Playing and swipe sideways to skip.
 */
class MainActivity : BaseActivity(), Player.Listener {

    private lateinit var titleTv: TextView
    private lateinit var back: ImageButton
    private lateinit var headerPlaying: ImageView
    private lateinit var mini: View
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var miniProgress: SparkleSeekBar
    private val handler = Handler(Looper.getMainLooper())
    private var shownSongId: Long? = -1
    private var backdropGen = -1

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasAudioPermission()) Library.load(this)
        else toast("GEN-Z needs access to your music files to show your library.")
    }

    private val libraryListener: () -> Unit = { currentFragment()?.let { (it as? Refreshable)?.refresh() } }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 2500)
        }
    }
    private val reload = Runnable { Library.load(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        Tour.prepare(intent) // debug screenshot tour only
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        titleTv = findViewById(R.id.txtTitle)
        back = findViewById(R.id.btnBack)
        headerPlaying = findViewById(R.id.headerPlaying)
        mini = findViewById(R.id.miniPlayer)
        miniArt = findViewById(R.id.miniArt)
        miniTitle = findViewById(R.id.miniTitle)
        miniArtist = findViewById(R.id.miniArtist)
        miniPlay = findViewById(R.id.miniPlay)
        miniProgress = findViewById(R.id.miniProgress)
        miniProgress.compact = true
        miniProgress.max = 1000f
        setupScreen(root, findViewById(R.id.header))

        back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.btnSearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
            com.jslw.genz.util.Transitions.open(this)
        }
        setupMiniPlayer()

        supportFragmentManager.addOnBackStackChangedListener { updateBack() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.content, HomeFragment()).commit()
            if (!splashShown && !(com.jslw.genz.BuildConfig.DEBUG && intent.hasExtra(Tour.EXTRA_SHOT))) {
                splashShown = true; Brand.splash(this)
            }
        }
        updateBack()

        Library.addListener(libraryListener)
        if (hasAudioPermission()) {
            if (!Library.loaded) Library.load(this)
        } else requestAudioPermission()
        try {
            contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (_: Exception) {}

        Player.connect(this)
        handleIntent(intent)
        Tour.run(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Go to album / artist" from Now Playing or a song menu. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.hasExtra(EXTRA_ALBUM)) {
            val id = i.getLongExtra(EXTRA_ALBUM, 0)
            i.removeExtra(EXTRA_ALBUM)
            val run = { Library.album(id)?.let { open(SongsFragment.forSource(com.jslw.genz.model.Source.OfAlbum(it.id, it.title))) } }
            if (Library.loaded) run() else handler.postDelayed({ run() }, 800)
        }
        if (i.hasExtra(EXTRA_GENRE)) {
            val g = i.getStringExtra(EXTRA_GENRE) ?: return
            i.removeExtra(EXTRA_GENRE)
            open(SongsFragment.forSource(com.jslw.genz.model.Source.OfGenre(g)))
        }
        if (i.hasExtra(EXTRA_ARTIST)) {
            val name = i.getStringExtra(EXTRA_ARTIST) ?: return
            i.removeExtra(EXTRA_ARTIST)
            open(BrowseFragment.artist(name))
        }
    }

    private fun hasAudioPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return Build.VERSION.SDK_INT < 23 || ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        val list = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissionLauncher.launch(list)
    }

    // ---------------------------------------------------------------- navigation
    fun open(f: Fragment) {
        val t = com.jslw.genz.util.Transitions.fragment()
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(t[0], t[1], t[2], t[3])
            .replace(R.id.content, f)
            .addToBackStack(null)
            .commit()
    }

    fun goHome() {
        if (supportFragmentManager.backStackEntryCount > 0)
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    private fun currentFragment(): Fragment? = supportFragmentManager.findFragmentById(R.id.content)

    /** Called by fragments when they become visible. */
    fun setScreenTitle(t: String) {
        if (titleTv.text == t) return
        titleTv.animate().alpha(0f).setDuration(90).withEndAction {
            titleTv.text = t
            titleTv.animate().alpha(1f).setDuration(160).start()
        }.start()
    }

    private fun updateBack() {
        back.visibility = if (supportFragmentManager.backStackEntryCount > 0) View.VISIBLE else View.INVISIBLE
    }

    // ---------------------------------------------------------------- mini player
    private fun setupMiniPlayer() {
        val body = findViewById<View>(R.id.miniBody)
        body.setOnClickListener { openNowPlaying() }
        miniPlay.setOnClickListener { Player.service?.toggle() }
        findViewById<View>(R.id.miniNext).setOnClickListener { Player.service?.next() }

        // swipe up → Now Playing; swipe left/right → next/previous with a slide animation
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { openNowPlaying(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (abs(dy) > abs(dx) && dy < -dpi(20)) { openNowPlaying(); return true }
                if (abs(dx) > dpi(40) && abs(dx) > abs(dy)) {
                    val svc = Player.service ?: return false
                    val dir = if (dx < 0) -1 else 1
                    body.animate().translationX(dir * body.width * 0.4f).alpha(0f).setDuration(140).withEndAction {
                        if (dir < 0) svc.next() else svc.prev()
                        body.translationX = -dir * body.width * 0.4f
                        body.animate().translationX(0f).alpha(1f).setDuration(220).start()
                    }.start()
                    return true
                }
                return false
            }
        })
        body.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
    }

    private fun openNowPlaying() {
        if (Player.current == null) return
        startActivity(Intent(this, NowPlayingActivity::class.java))
        overridePendingTransition(R.anim.slide_up, R.anim.stay)
    }

    override fun onPlayerChanged() {
        val s = Player.current
        if (s == null) {
            if (mini.visibility == View.VISIBLE) {
                mini.animate().translationY(mini.height.toFloat()).setDuration(200).withEndAction { mini.visibility = View.GONE }.start()
            }
            headerPlaying.visibility = View.INVISIBLE
            return
        }
        if (mini.visibility != View.VISIBLE) {
            mini.visibility = View.VISIBLE
            mini.translationY = dpi(70).toFloat()
            mini.animate().translationY(0f).setDuration(260).start()
        }
        if (shownSongId != s.id) {
            shownSongId = s.id
            miniTitle.text = s.title
            miniArtist.text = s.artist
            ArtLoader.load(miniArt, s, dpi(46) * 2) { bmp ->
                val cols = com.jslw.genz.util.SongColors.from(bmp)
                miniProgress.setStyle(skin.progressStyle, cols ?: skin.progressColors)
                miniProgress.setColors(cols?.get(1) ?: skin.accent, skin.divider)
            }
        }
        if (skin.translucent && backdropGen != com.jslw.genz.skin.Backdrop.generation) {
            backdropGen = com.jslw.genz.skin.Backdrop.generation
            val root = findViewById<View>(R.id.root)
            val old = root.background
            val new = com.jslw.genz.skin.SkinPainter.backgroundFor(skin, root)
            if (old != null) {
                val td = android.graphics.drawable.TransitionDrawable(arrayOf(old, new))
                td.isCrossFadeEnabled = true
                root.background = td
                td.startTransition(600)
            } else root.background = new
        }
        val playing = Player.isPlaying
        com.jslw.genz.skin.Icons.set(miniPlay, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        com.jslw.genz.skin.Icons.set(headerPlaying, if (playing) R.drawable.ic_play else R.drawable.ic_pause)
        headerPlaying.visibility = View.VISIBLE
        (currentFragment() as? Refreshable)?.playingChanged()
        tickProgress()
    }

    // progress bar runs on the display's vsync (60–144 Hz) while playing, and stops when paused
    private val progressFrames = com.jslw.genz.util.FrameTicker { tickProgress() }

    private fun tickProgress() {
        val svc = Player.service ?: return
        val d = svc.duration()
        miniProgress.progress = if (d > 0) svc.position() * 1000f / d else 0f
        miniProgress.playing = svc.isPlaying
        if (svc.isPlaying && !isFinishing) progressFrames.start() else progressFrames.stop()
    }

    override fun onStart() {
        super.onStart()
        Player.add(this)
        onPlayerChanged()
    }

    override fun onStop() {
        Player.remove(this)
        progressFrames.stop()
        super.onStop()
    }

    override fun onDestroy() {
        Library.removeListener(libraryListener)
        try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ALBUM = "open_album"
        const val EXTRA_ARTIST = "open_artist"
        const val EXTRA_GENRE = "open_genre"
        /** Splash only once per app launch (not on rotation or when returning). */
        private var splashShown = false
    }
}

/** Screens that redraw when the library or the playing song changes. */
interface Refreshable {
    fun refresh()
    fun playingChanged() {}
}
