package com.jslw.genz.playback

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.session.MediaButtonReceiver
import com.jslw.genz.R
import com.jslw.genz.audio.AudioFx
import com.jslw.genz.audio.BitPerfect
import com.jslw.genz.audio.Engine
import com.jslw.genz.audio.FormatInfo
import com.jslw.genz.audio.Formats
import com.jslw.genz.audio.MediaPlayerEngine
import com.jslw.genz.audio.PcmEngine
import com.jslw.genz.data.Library
import com.jslw.genz.data.LibraryDb
import com.jslw.genz.data.Prefs
import com.jslw.genz.model.Song
import com.jslw.genz.ui.NowPlayingActivity
import com.jslw.genz.util.ArtLoader
import com.jslw.genz.widget.Widgets
import java.util.concurrent.Executors

/**
 * Background playback: queue, gapless / crossfade, fades, audio focus, media session (lock screen,
 * headset & Bluetooth buttons, Android Auto-style controls), notification, sleep timer, shake-to-skip,
 * floating lyrics, effects and widget updates.
 */
class MusicService : Service() {

    inner class LocalBinder : Binder() {
        val service: MusicService get() = this@MusicService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

    // ---------------------------------------------------------------- queue state
    private val _queue = ArrayList<Song>()
    private val original = ArrayList<Song>()
    val queue: List<Song> get() = _queue
    var index = -1; private set
    val current: Song? get() = _queue.getOrNull(index)
    var shuffle = Prefs.shuffle; private set
    var repeatMode = Prefs.repeat; private set
    var contextLabel = Prefs.queueLabel; private set

    // ---------------------------------------------------------------- engines
    private var cur: Engine? = null
    private var next: Engine? = null
    private var nextIndex = -1
    private var xfade: Engine? = null
    private var xfadeIndex = -1
    private var xfadeAnim: ValueAnimator? = null
    private var sessionHolder: MediaPlayer? = null
    var audioSessionId = 0; private set
    lateinit var fx: AudioFx; private set

    var isPlaying = false; private set
    var formatInfo: FormatInfo? = null; private set
    var lyrics: Lyrics? = null; private set
    var bitPerfectStatus = ""; private set
    private var art: Bitmap? = null
    private var errorsInRow = 0
    private var started = false
    private var stopAfterCurrent = false

    // volume
    private var fadeLevel = 1f
    private var duckLevel = 1f
    /** ReplayGain × EQ headroom – never above 1.0, so nothing is ever pushed into clipping. */
    private var trackGain = 1f
    private fun baseGain() = (trackGain * fx.headroom()).coerceIn(0.05f, 1f) * duckLevel
    private var fadeAnim: ValueAnimator? = null

    // focus / receivers / helpers
    private lateinit var am: AudioManager
    private var focusRequest: AudioFocusRequestCompat? = null
    private var resumeOnFocusGain = false
    private var pausedByNoisy = false
    private var noisyRegistered = false
    private lateinit var session: MediaSessionCompat
    private lateinit var shake: ShakeDetector
    private lateinit var floating: FloatingLyrics

    // sleep timer
    private var sleepAt = 0L
    var sleepFinishSong = false; private set

    override fun onBind(intent: Intent?): IBinder = binder

    // ================================================================ lifecycle
    override fun onCreate() {
        super.onCreate()
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioSessionId = if (Build.VERSION.SDK_INT >= 21) am.generateAudioSessionId() else {
            // KitKat: reserve a session by keeping one idle MediaPlayer around.
            MediaPlayer().also { sessionHolder = it }.audioSessionId
        }
        fx = AudioFx(audioSessionId)
        sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        })

        createChannel()
        session = MediaSessionCompat(this, "GEN-Z", ComponentName(this, MediaButtonReceiver::class.java), null).apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(sessionCallback)
            setSessionActivity(openAppIntent())
        }
        shake = ShakeDetector(this) { handler.post { next(); toastShort("Shake → next") } }
        floating = FloatingLyrics(this)
        registerReceiver(headsetReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))

        if (Library.loaded) restoreQueue() else {
            val once = object : () -> Unit {
                override fun invoke() {
                    Library.removeListener(this)
                    if (_queue.isEmpty()) restoreQueue()
                }
            }
            Library.addListener(once)
            Library.load(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        started = true
        val action = intent?.action ?: return START_NOT_STICKY
        if (action == ACTION_CLOSE) {
            // must still satisfy a startForegroundService() contract before closing
            if (Build.VERSION.SDK_INT >= 26) goForeground()
            close()
            return START_NOT_STICKY
        }
        // Widget / headset / Bluetooth buttons may start us with startForegroundService(): Android 8+
        // requires startForeground() within seconds, so go foreground first and step back if paused.
        goForeground()
        when (action) {
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            Intent.ACTION_MEDIA_BUTTON -> MediaButtonReceiver.handleIntent(session, intent)
        }
        if (!isPlaying) handler.postDelayed({ if (!isPlaying) leaveForeground(false) }, 800)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaying) { saveState(); stopSelfSafely() }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveState()
        handler.removeCallbacksAndMessages(null)
        releaseEngines()
        abandonFocus()
        unregisterNoisy()
        setScreenReceiver(false)
        try { unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
        shake.setActive(false)
        floating.hide()
        fx.release()
        sessionHolder?.release()
        sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        })
        session.isActive = false
        session.release()
        exec.shutdown()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        leaveForeground(true)
        stopSelf()
        started = false
    }

    /** Notification "✕": stop playing and get out of the way. */
    fun close() {
        pause(fade = false)
        leaveForeground(true)
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        floating.hide()
        stopSelfSafely()
        Player.dispatch()
    }

    // ================================================================ queue API
    fun playQueue(songs: List<Song>, start: Int, label: String) {
        if (songs.isEmpty()) return
        original.clear(); original.addAll(songs)
        contextLabel = label
        val startSong = songs[start.coerceIn(0, songs.lastIndex)]
        _queue.clear()
        if (shuffle) {
            _queue.add(startSong)
            _queue.addAll(songs.filterIndexed { i, _ -> i != start }.shuffled())
            loadIndex(0, true)
        } else {
            _queue.addAll(songs)
            loadIndex(start.coerceIn(0, songs.lastIndex), true)
        }
        saveQueue()
    }

    fun playShuffled(songs: List<Song>, label: String) {
        if (songs.isEmpty()) return
        shuffle = true; Prefs.shuffle = true
        playQueue(songs, (songs.indices).random(), label)
    }

    fun playAt(i: Int) { if (i in _queue.indices) loadIndex(i, true) }

    fun playNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (_queue.isEmpty()) { playQueue(songs, 0, "Queue"); return }
        _queue.addAll(index + 1, songs)
        val oi = original.indexOfFirst { it === current }
        original.addAll(if (oi >= 0) oi + 1 else original.size, songs)
        queueChanged()
    }

    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (_queue.isEmpty()) { playQueue(songs, 0, "Queue"); return }
        _queue.addAll(songs)
        original.addAll(songs)
        queueChanged()
    }

    fun removeAt(i: Int) {
        if (i !in _queue.indices) return
        val s = _queue.removeAt(i)
        original.remove(s)
        when {
            _queue.isEmpty() -> { pause(false); releaseEngines(); index = -1; leaveForeground(true) ; Player.dispatch() }
            i < index -> index--
            i == index -> loadIndex(index.coerceAtMost(_queue.lastIndex), isPlaying)
        }
        queueChanged()
    }

    fun move(from: Int, to: Int) {
        if (from !in _queue.indices || to !in _queue.indices || from == to) return
        val s = _queue.removeAt(from)
        _queue.add(to, s)
        index = when (index) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) index - 1 else index + 1
            else -> index
        }
        if (!shuffle) { original.clear(); original.addAll(_queue) }
        queueChanged()
    }

    private fun queueChanged() {
        dropPreparedNext()
        prepareNext()
        saveQueue()
        Player.dispatch()
    }

    fun setShuffleMode(on: Boolean) {
        if (on == shuffle) return
        shuffle = on; Prefs.shuffle = on
        val c = current
        if (c != null) {
            if (on) {
                val rest = original.filter { it !== c }.shuffled()
                _queue.clear(); _queue.add(c); _queue.addAll(rest)
                index = 0
            } else {
                _queue.clear(); _queue.addAll(original)
                index = _queue.indexOfFirst { it === c }.coerceAtLeast(0)
            }
        }
        session.setShuffleMode(if (on) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        queueChanged()
    }

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        Prefs.repeat = repeatMode
        session.setRepeatMode(
            when (repeatMode) {
                1 -> PlaybackStateCompat.REPEAT_MODE_ALL
                2 -> PlaybackStateCompat.REPEAT_MODE_ONE
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            }
        )
        dropPreparedNext(); prepareNext()
        Player.dispatch()
    }

    // ================================================================ transport
    fun toggle() = if (isPlaying) pause() else play()

    fun play() {
        if (cur == null) {
            if (_queue.isEmpty()) {
                // nothing queued: shuffle the whole library
                if (Library.songs.isNotEmpty()) playShuffled(Library.songs, "All songs")
                return
            }
            loadIndex(index.coerceAtLeast(0), true, Prefs.queuePosition)
            return
        }
        startPlayback()
    }

    fun pause(fade: Boolean = Prefs.fadeOutOnPause) {
        val e = cur ?: return
        if (xfade != null) finishCrossfade()
        if (!isPlaying) return
        isPlaying = false
        pausedByNoisy = false
        fadeAnim?.cancel()
        if (fade) {
            fadeTo(0f, 260) { if (!isPlaying) cur?.pause() }
        } else e.pause()
        afterStateChange()
        saveState()
        scheduleAutoStop()
    }

    fun next() {
        if (_queue.isEmpty()) return
        val ni = if (index + 1 < _queue.size) index + 1 else 0
        loadIndex(ni, isPlaying || cur == null)
    }

    fun prev() {
        if (_queue.isEmpty()) return
        if (position() > 3000) { seekTo(0); return }
        val pi = if (index - 1 >= 0) index - 1 else _queue.lastIndex
        loadIndex(pi, isPlaying || cur == null)
    }

    fun seekTo(ms: Long) {
        cur?.seekTo(ms)
        if (xfade != null) finishCrossfade()
        updatePlaybackState()
        Player.dispatch()
    }

    fun position(): Long = cur?.position() ?: if (index >= 0) Prefs.queuePosition else 0
    fun duration(): Long = cur?.duration()?.takeIf { it > 0 } ?: current?.duration ?: 0

    // ================================================================ engine management
    private fun loadIndex(i: Int, play: Boolean, seekMs: Long = 0) {
        releaseEngines()
        index = i
        val s = current ?: return
        var e: Engine = Engine.create(this, s, audioSessionId)
        var ok = e.prepare(this, s)
        if (!ok && s.ext == "wav" && e is MediaPlayerEngine) {
            // e.g. 32-bit float or 24-bit WAV on an old phone: use our own decoder
            e.release()
            e = PcmEngine(this, audioSessionId)
            ok = e.prepare(this, s)
        }
        if (!ok) {
            e.release()
            errorsInRow++
            toastShort(
                if (s.ext == "wma" || s.ext == "ape") "${s.ext.uppercase()} isn't supported by Android – skipping \"${s.title}\""
                else "Can't play \"${s.title}\" – skipping"
            )
            if (errorsInRow < _queue.size && _queue.size > 1) {
                handler.post { loadIndex((i + 1) % _queue.size, play) }
            } else {
                errorsInRow = 0; isPlaying = false; afterStateChange()
            }
            return
        }
        errorsInRow = 0
        cur = e
        wire(e)
        if (seekMs > 0) e.seekTo(seekMs)
        onTrackChanged()
        if (play) startPlayback() else {
            isPlaying = false
            afterStateChange()
        }
        prepareNext()
    }

    private fun wire(e: Engine) {
        e.onCompletion = { onEngineCompleted(e) }
        e.onError = { msg -> handler.post { if (e === cur) { toastShort(msg); onEngineCompleted(e) } } }
        e.setSpeedPitch(Prefs.speed, Prefs.pitch)
        if (fx.reverbEffectId != 0 && Prefs.eqEnabled && Prefs.reverb > 0) e.attachAux(fx.reverbEffectId, 1f)
    }

    private fun startPlayback() {
        val e = cur ?: return
        if (!requestFocus()) { toastShort("Another app is using audio"); return }
        if (!started) {
            try { startService(Intent(this, MusicService::class.java)); started = true } catch (_: Exception) {}
        }
        handler.removeCallbacks(autoStop)
        fadeAnim?.cancel()
        if (Prefs.fadeIn) { fadeLevel = 0f; applyVolume() } else { fadeLevel = 1f; applyVolume() }
        e.start()
        isPlaying = true
        if (Prefs.fadeIn) fadeTo(1f, 450, null)
        session.isActive = true
        registerNoisy()
        afterStateChange()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun onEngineCompleted(e: Engine) {
        if (e !== cur) return
        if (xfade != null) { finishCrossfade(); return }
        if (stopAfterCurrent) {
            stopAfterCurrent = false
            cancelSleepTimer()
            isPlaying = false
            cur?.seekTo(0)
            afterStateChange()
            scheduleAutoStop()
            return
        }
        if (repeatMode == REPEAT_ONE) {
            e.seekTo(0); e.start()
            current?.let { s -> exec.execute { LibraryDb.recordPlay(s.id) } }
            return
        }
        val n = next
        if (n != null && nextIndex >= 0) {
            // true gapless hand-off already happened inside MediaPlayer
            cur = n; next = null
            index = nextIndex; nextIndex = -1
            e.release()
            onTrackChanged()
            prepareNext()
            return
        }
        val ni = autoNextIndex()
        if (ni < 0) {
            // End of the queue: stop on the first song, like a classic player.
            isPlaying = false
            loadIndex(0, false)
            return
        }
        loadIndex(ni, true)
    }

    private fun autoNextIndex(): Int = when {
        index + 1 < _queue.size -> index + 1
        repeatMode == REPEAT_ALL && _queue.isNotEmpty() -> 0
        else -> -1
    }

    /** Pre-loads the next track so MediaPlayer can start it with zero gap. */
    private fun prepareNext() {
        val c = cur as? MediaPlayerEngine ?: return
        if (!Prefs.gapless || Prefs.crossfadeSec > 0 || repeatMode == REPEAT_ONE || next != null) return
        val ni = autoNextIndex()
        if (ni < 0) return
        val s = _queue[ni]
        if (s.needsPcmEngine) return
        val token = c
        exec.execute {
            val n = MediaPlayerEngine(this, audioSessionId)
            val ok = n.prepare(this, s)
            handler.post {
                if (!ok || cur !== token || next != null || autoNextIndex() != ni || _queue.getOrNull(ni) !== s) {
                    n.release(); return@post
                }
                wire(n)
                if (c.setNext(n)) { next = n; nextIndex = ni } else n.release()
            }
        }
    }

    private fun dropPreparedNext() {
        cur?.setNext(null)
        next?.release()
        next = null; nextIndex = -1
    }

    private fun releaseEngines() {
        xfadeAnim?.cancel(); xfadeAnim = null
        xfade?.release(); xfade = null
        dropPreparedNext()
        cur?.release(); cur = null
    }

    // ---------------------------------------------------------------- crossfade
    private fun maybeCrossfade() {
        val cf = Prefs.crossfadeSec * 1000L
        val c = cur ?: return
        if (cf <= 0 || !isPlaying || xfade != null || repeatMode == REPEAT_ONE || stopAfterCurrent) return
        val dur = c.duration()
        val rem = dur - c.position()
        if (dur < cf * 3 || rem > cf || rem <= 200) return
        val ni = autoNextIndex()
        if (ni < 0) return
        val s = _queue[ni]
        val e = Engine.create(this, s, audioSessionId)
        if (!e.prepare(this, s)) { e.release(); return }
        wire(e)
        e.setVolume(0f)
        e.start()
        xfade = e; xfadeIndex = ni
        xfadeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = rem
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                e.setVolume(f * baseGain())
                cur?.setVolume((1 - f) * baseGain() * fadeLevel)
            }
            start()
        }
    }

    private fun finishCrossfade() {
        val n = xfade ?: return
        xfadeAnim?.cancel(); xfadeAnim = null
        val old = cur
        dropPreparedNext()
        cur = n; xfade = null
        index = xfadeIndex
        old?.release()
        fadeLevel = 1f
        applyVolume()
        if (!isPlaying) n.pause()
        onTrackChanged()
        prepareNext()
    }

    // ---------------------------------------------------------------- volume & fades
    private fun applyVolume() { cur?.setVolume(fadeLevel * baseGain()) }

    private fun fadeTo(target: Float, ms: Long, end: (() -> Unit)?) {
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(fadeLevel, target).apply {
            duration = ms
            addUpdateListener { fadeLevel = it.animatedValue as Float; applyVolume() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) end?.invoke() }
            })
            start()
        }
    }

    // ---------------------------------------------------------------- audio focus
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (isPlaying) { resumeOnFocusGain = true; pause(false) } }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { duckLevel = 0.25f; applyVolume() }
            AudioManager.AUDIOFOCUS_GAIN -> {
                duckLevel = 1f; applyVolume()
                if (resumeOnFocusGain) { resumeOnFocusGain = false; play() }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC).build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build().also { focusRequest = it }
        return AudioManagerCompat.requestAudioFocus(am, req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { AudioManagerCompat.abandonAudioFocusRequest(am, it) }
    }

    // ---------------------------------------------------------------- receivers
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (isPlaying) { pause(false); pausedByNoisy = true }
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        noisyRegistered = false
    }

    // ---------------------------------------------------------------- lock-screen player
    private var screenRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!isPlaying || !Prefs.lockScreenPlayer) return
            // Android 10+ only lets apps open a screen from the background with "display over other apps".
            if (Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(this@MusicService)) return
            try {
                startActivity(Intent(this@MusicService, com.jslw.genz.ui.LockScreenActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                ))
            } catch (_: Exception) {}
        }
    }

    private fun setScreenReceiver(on: Boolean) {
        if (on == screenRegistered) return
        if (on) registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        else try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        screenRegistered = on
    }

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (isInitialStickyBroadcast) return
            if (i?.getIntExtra("state", 0) == 1 && Prefs.resumeOnHeadset && !isPlaying && cur != null) play()
        }
    }

    // ---------------------------------------------------------------- periodic tick
    private var lastSave = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!isPlaying) { floating.hide(); return }
            maybeCrossfade()
            updateFloatingLyrics()
            val now = SystemClock.elapsedRealtime()
            if (now - lastSave > 5000) { lastSave = now; Prefs.queuePosition = position() }
            if (sleepAt in 1..now && !sleepFinishSong) sleepFire()
            handler.postDelayed(this, 250)
        }
    }

    // ---------------------------------------------------------------- sleep timer
    fun setSleepTimer(minutes: Int, finishSong: Boolean) {
        sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        sleepFinishSong = finishSong
        handler.removeCallbacks(sleepRunnable)
        handler.postDelayed(sleepRunnable, minutes * 60_000L)
        Player.dispatch()
    }

    fun cancelSleepTimer() {
        sleepAt = 0
        stopAfterCurrent = false
        handler.removeCallbacks(sleepRunnable)
        Player.dispatch()
    }

    fun sleepRemainingMs(): Long = if (sleepAt == 0L) 0 else (sleepAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    private val sleepRunnable = Runnable { sleepFire() }

    private fun sleepFire() {
        if (sleepAt == 0L) return
        if (sleepFinishSong) {
            stopAfterCurrent = true
            sleepAt = 0
        } else {
            sleepAt = 0
            // gentle 8 second fade, then pause
            fadeTo(0f, 8000) { pause(false); fadeLevel = 1f }
        }
        Player.dispatch()
    }

    // ---------------------------------------------------------------- speed / pitch / effects / hi-res
    val canChangeSpeed: Boolean get() = Build.VERSION.SDK_INT >= 23

    fun setSpeedPitch(speed: Float, pitch: Float) {
        Prefs.speed = speed; Prefs.pitch = pitch
        cur?.setSpeedPitch(speed, pitch)
        updatePlaybackState()
    }

    fun effectsChanged() {
        fx.applyAll()
        applyVolume()
        val id = fx.reverbEffectId
        val on = Prefs.eqEnabled && Prefs.reverb > 0 && id != 0
        cur?.attachAux(if (on) id else 0, if (on) 1f else 0f)
        next?.attachAux(if (on) id else 0, if (on) 1f else 0f)
    }

    fun applyBitPerfect() {
        bitPerfectStatus = BitPerfect.apply(this, Prefs.bitPerfect, formatInfo)
        Player.dispatch()
    }

    fun refreshShake() {
        shake.strength = Prefs.shakeStrength
        shake.setActive(Prefs.shakeToSkip && isPlaying)
    }

    fun reloadLyrics() {
        val s = current ?: return
        exec.execute {
            val l = Lyrics.load(this, s)
            handler.post { if (current === s) { lyrics = l; Player.dispatch() } }
        }
    }

    private fun updateFloatingLyrics() {
        val l = lyrics
        if (!Prefs.floatingLyrics || l == null || !l.synced || !isPlaying) { floating.hide(); return }
        if (!floating.showing) floating.show()
        val i = l.indexAt(position())
        floating.setLines(l.lines.getOrNull(i)?.text ?: "♪", l.lines.getOrNull(i + 1)?.text)
    }

    // ---------------------------------------------------------------- track change side-effects
    private fun onTrackChanged() {
        val s = current ?: return
        formatInfo = null
        lyrics = null
        art = null
        if (isPlaying || cur?.isPlaying == true) exec.execute { LibraryDb.recordPlay(s.id) }
        updateMetadata()
        exec.execute {
            val info = try { Formats.probe(this, s) } catch (e: Exception) { null }
            val rg = if (Prefs.replayGain > 0) try {
                com.jslw.genz.audio.TagIO.replayGainDb(this, s, Prefs.replayGain == 2)
            } catch (e: Exception) { null } else null
            val l = Lyrics.load(this, s)
            val bmp = ArtLoader.loadSync(this, s, 512)
            handler.post {
                if (current !== s) return@post
                formatInfo = info; lyrics = l; art = bmp
                trackGain = rg?.let { Math.pow(10.0, it / 20.0).toFloat().coerceAtMost(1f) } ?: 1f
                applyVolume()
                if (Prefs.bitPerfect) applyBitPerfect()
                updateMetadata()
                updateNotification()
                Player.dispatch()
            }
        }
        saveQueue()
        Player.dispatch()
    }

    private fun afterStateChange() {
        updatePlaybackState()
        updateNotification()
        refreshShake()
        setScreenReceiver(isPlaying && Prefs.lockScreenPlayer)
        if (isPlaying) goForeground() else leaveForeground(false)
        if (!isPlaying) { unregisterNoisy(); floating.hide() }
        Player.dispatch()
    }

    private val autoStop = Runnable {
        if (!isPlaying) {
            // Power saving: drop the notification and release the decoder after a long pause.
            saveState()
            releaseEngines()
            abandonFocus()
            leaveForeground(true)
            NotificationManagerCompat.from(this).cancel(NOTIF_ID)
            stopSelfSafely()
            Player.dispatch()
        }
    }

    private fun scheduleAutoStop() {
        handler.removeCallbacks(autoStop)
        val m = Prefs.autoStopMinutes
        if (m > 0) handler.postDelayed(autoStop, m * 60_000L)
    }

    // ================================================================ media session
    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = play()
        override fun onPause() = pause()
        override fun onSkipToNext() = next()
        override fun onSkipToPrevious() = prev()
        override fun onStop() = pause()
        override fun onSeekTo(pos: Long) = seekTo(pos)
        override fun onSetShuffleMode(shuffleMode: Int) = setShuffleMode(shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE)
        override fun onSetRepeatMode(repeatMode: Int) {
            val want = when (repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> 1
                PlaybackStateCompat.REPEAT_MODE_ONE -> 2
                else -> 0
            }
            while (this@MusicService.repeatMode != want) cycleRepeat()
        }
        override fun onSkipToQueueItem(id: Long) = playAt(id.toInt())
    }

    private fun updateMetadata() {
        val s = current ?: return
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, s.album)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, s.albumArtist.ifBlank { s.artist })
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration())
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (s.track % 1000).toLong())
        art?.let { b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(b.build())
    }

    private fun updatePlaybackState() {
        val state = when {
            isPlaying -> PlaybackStateCompat.STATE_PLAYING
            cur != null -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                        PlaybackStateCompat.ACTION_SET_REPEAT_MODE or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
                )
                .setState(state, position(), if (isPlaying) Prefs.speed else 0f)
                .build()
        )
    }

    // ================================================================ notification
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun openAppIntent() = PendingIntentCompat.getActivity(
        this, 10, Intent(this, NowPlayingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT, false
    )

    private fun serviceIntent(action: String, rc: Int) = PendingIntentCompat.getService(
        this, rc, Intent(this, MusicService::class.java).setAction(action),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT, false
    )

    private fun buildNotification(): Notification {
        val s = current
        val playPause = if (isPlaying)
            NotificationCompat.Action(R.drawable.ic_pause, "Pause", serviceIntent(ACTION_TOGGLE, 2))
        else NotificationCompat.Action(R.drawable.ic_play, "Play", serviceIntent(ACTION_TOGGLE, 2))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(s?.title ?: getString(R.string.app_name))
            .setContentText(s?.artist ?: "")
            .setSubText(s?.album)
            .setLargeIcon(art)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_CLOSE, 5))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(R.drawable.ic_prev, "Previous", serviceIntent(ACTION_PREV, 1))
            .addAction(playPause)
            .addAction(R.drawable.ic_next, "Next", serviceIntent(ACTION_NEXT, 3))
            .addAction(R.drawable.ic_close, "Close", serviceIntent(ACTION_CLOSE, 4))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(serviceIntent(ACTION_CLOSE, 6))
            )
            .build()
    }

    private var foreground = false

    private fun goForeground() {
        val n = buildNotification()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
            foreground = true
        } catch (e: Exception) {
            // Android 12+ may refuse when the app is in the background; keep a normal notification instead.
            postNotification(n)
        }
    }

    private fun leaveForeground(remove: Boolean) {
        if (foreground) {
            ServiceCompat.stopForeground(this, if (remove) ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH)
            foreground = false
        }
        if (!remove && current != null) postNotification(buildNotification())
    }

    private fun updateNotification() {
        if (current == null) return
        if (isPlaying || foreground || cur != null) postNotification(buildNotification())
        Widgets.update(this, current, isPlaying, art)
    }

    private fun postNotification(n: Notification) {
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }

    // ================================================================ persistence
    private fun saveQueue() {
        Prefs.queueIds = _queue.take(3000).joinToString(",") { it.id.toString() }
        Prefs.originalQueueIds = original.take(3000).joinToString(",") { it.id.toString() }
        Prefs.queueIndex = index
        Prefs.queueLabel = contextLabel
    }

    private fun saveState() {
        saveQueue()
        if (cur != null) Prefs.queuePosition = position()
    }

    private fun restoreQueue() {
        if (_queue.isNotEmpty()) return
        fun ids(s: String) = s.split(',').mapNotNull { it.toLongOrNull() }
        val q = Library.songs(ids(Prefs.queueIds))
        if (q.isEmpty()) return
        val o = Library.songs(ids(Prefs.originalQueueIds))
        _queue.addAll(q)
        original.addAll(if (o.isEmpty()) q else o)
        index = Prefs.queueIndex.coerceIn(0, _queue.lastIndex)
        updateMetadata()
        Player.dispatch()
        // load art for the restored song (mini player / lock screen) without starting playback
        val s = current ?: return
        exec.execute {
            val bmp = ArtLoader.loadSync(this, s, 512)
            handler.post { if (current === s) { art = bmp; updateMetadata() } }
        }
    }

    private fun toastShort(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val CHANNEL = "playback"
        const val NOTIF_ID = 42
        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2
        const val ACTION_TOGGLE = "com.jslw.genz.TOGGLE"
        const val ACTION_NEXT = "com.jslw.genz.NEXT"
        const val ACTION_PREV = "com.jslw.genz.PREV"
        const val ACTION_CLOSE = "com.jslw.genz.CLOSE"
    }
}
