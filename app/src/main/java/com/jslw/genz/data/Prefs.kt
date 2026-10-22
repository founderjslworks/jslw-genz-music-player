package com.jslw.genz.data

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("genz", Context.MODE_PRIVATE)
    }

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun f(k: String, d: Float) = sp.getFloat(k, d)
    private fun l(k: String, d: Long) = sp.getLong(k, d)
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    // ---- look & feel ----
    var skinId: String
        get() = s("skin", "vivid_genz")
        set(v) = put { putString("skin", v) }

    // ---- playback ----
    var shuffle: Boolean
        get() = b("shuffle", false)
        set(v) = put { putBoolean("shuffle", v) }

    /** 0 = off, 1 = all, 2 = one */
    var repeat: Int
        get() = i("repeat", 0)
        set(v) = put { putInt("repeat", v) }

    var gapless: Boolean
        get() = b("gapless", true)
        set(v) = put { putBoolean("gapless", v) }

    /** Crossfade length in seconds; 0 disables crossfade (gapless is used instead). */
    var crossfadeSec: Int
        get() = i("crossfade", 0)
        set(v) = put { putInt("crossfade", v) }

    var fadeIn: Boolean
        get() = b("fade_in", true)
        set(v) = put { putBoolean("fade_in", v) }

    var fadeOutOnPause: Boolean
        get() = b("fade_out", true)
        set(v) = put { putBoolean("fade_out", v) }

    var speed: Float
        get() = f("speed", 1f)
        set(v) = put { putFloat("speed", v) }

    var pitch: Float
        get() = f("pitch", 1f)
        set(v) = put { putFloat("pitch", v) }

    var bitPerfect: Boolean
        get() = b("bit_perfect", false)
        set(v) = put { putBoolean("bit_perfect", v) }

    /** Volume normalisation: 0 off, 1 track gain, 2 album gain. */
    var replayGain: Int
        get() = i("replay_gain", 1)
        set(v) = put { putInt("replay_gain", v) }

    var resumeOnHeadset: Boolean
        get() = b("resume_headset", false)
        set(v) = put { putBoolean("resume_headset", v) }

    // ---- library ----
    var minDurationSec: Int
        get() = i("min_duration", 20)
        set(v) = put { putInt("min_duration", v) }

    /** 0 title, 1 artist, 2 album, 3 date added, 4 duration */
    var songSort: Int
        get() = i("song_sort", 0)
        set(v) = put { putInt("song_sort", v) }

    /** Folders added via "Add music folder" (SAF tree URIs, or file paths on KitKat). */
    var extraFolders: Set<String>
        get() = sp.getStringSet("extra_folders", emptySet())?.toSet() ?: emptySet()
        set(v) = put { putStringSet("extra_folders", v) }

    // ---- utilities ----
    var shakeToSkip: Boolean
        get() = b("shake", false)
        set(v) = put { putBoolean("shake", v) }

    /** 1 (gentle) .. 5 (hard shake needed) */
    var shakeStrength: Int
        get() = i("shake_strength", 3)
        set(v) = put { putInt("shake_strength", v) }

    var floatingLyrics: Boolean
        get() = b("floating_lyrics", false)
        set(v) = put { putBoolean("floating_lyrics", v) }

    var npDynamicColors: Boolean
        get() = b("np_dynamic", true)
        set(v) = put { putBoolean("np_dynamic", v) }

    /** Power saving: stop the service after being paused this many minutes (0 = never). */
    var autoStopMinutes: Int
        get() = i("auto_stop", 15)
        set(v) = put { putInt("auto_stop", v) }

    /** Use the panel's highest refresh rate (90/120/144 Hz) for fluid animation. */
    var highRefresh: Boolean
        get() = b("high_refresh", true)
        set(v) = put { putBoolean("high_refresh", v) }

    /** Full-screen player over the lock screen while music plays. */
    var lockScreenPlayer: Boolean
        get() = b("lock_player", true)
        set(v) = put { putBoolean("lock_player", v) }

    /** Cover touch effect id, or "theme" to use the active theme's own effect. */
    var coverEffect: String
        get() = s("cover_fx", "theme")
        set(v) = put { putString("cover_fx", v) }

    /** List scroll animation (0 = off) – see ListAnim. */
    var listAnim: Int
        get() = i("list_anim", 13)
        set(v) = put { putInt("list_anim", v) }

    var listInterp: Int
        get() = i("list_interp", 1)
        set(v) = put { putInt("list_interp", v) }

    var listDuration: Int
        get() = i("list_dur", 380)
        set(v) = put { putInt("list_dur", v) }

    /** Screen transition style – see Transitions. */
    var transition: String
        get() = s("transition", "genz")
        set(v) = put { putString("transition", v) }

    var keepScreenOn: Boolean
        get() = b("keep_screen_on", false)
        set(v) = put { putBoolean("keep_screen_on", v) }

    // ---- equalizer / effects ----
    var eqEnabled: Boolean
        get() = b("eq_on", false)
        set(v) = put { putBoolean("eq_on", v) }

    /** Band gains in millibels, comma separated. */
    var eqBands: String
        get() = s("eq_bands", "")
        set(v) = put { putString("eq_bands", v) }

    var eqPreset: String
        get() = s("eq_preset", "Flat")
        set(v) = put { putString("eq_preset", v) }

    /** JSON object { "name": [mB, mB, ...] } of the user's own presets. */
    var eqCustomPresets: String
        get() = s("eq_custom", "{}")
        set(v) = put { putString("eq_custom", v) }

    var bassStrength: Int
        get() = i("bass", 0)
        set(v) = put { putInt("bass", v) }

    var virtStrength: Int
        get() = i("virt", 0)
        set(v) = put { putInt("virt", v) }

    /** 0..1000 mapped to 0..1500 mB of loudness gain. */
    var loudness: Int
        get() = i("loud", 0)
        set(v) = put { putInt("loud", v) }

    /** PresetReverb preset, 0 = none. */
    var reverb: Int
        get() = i("reverb", 0)
        set(v) = put { putInt("reverb", v) }

    // ---- saved queue ----
    var queueIds: String
        get() = s("q_ids", "")
        set(v) = put { putString("q_ids", v) }

    var originalQueueIds: String
        get() = s("q_orig", "")
        set(v) = put { putString("q_orig", v) }

    var queueIndex: Int
        get() = i("q_index", 0)
        set(v) = put { putInt("q_index", v) }

    var queuePosition: Long
        get() = l("q_pos", 0L)
        set(v) = put { putLong("q_pos", v) }

    var queueLabel: String
        get() = s("q_label", "")
        set(v) = put { putString("q_label", v) }

    var firstRunDone: Boolean
        get() = b("first_run", false)
        set(v) = put { putBoolean("first_run", v) }
}
