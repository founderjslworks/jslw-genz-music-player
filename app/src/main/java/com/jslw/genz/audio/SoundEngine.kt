package com.jslw.genz.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.jslw.genz.data.Prefs

/**
 * JSLW Sound Engine – GEN-Z's own "advanced audio": surround, dialogue clarity, bass, treble, room and a
 * volume leveller, tuned per listening profile. It runs inside GEN-Z on the music it plays, before the
 * sound leaves the phone, so it works the same on wired headphones, Bluetooth headphones, speakers and
 * the phone's own speaker.
 */
object SoundEngine {
    class Profile(val id: String, val name: String, val blurb: String,
                  val surround: Int, val dialogue: Int, val bass: Int, val treble: Int, val leveller: Int, val room: Int)

    val PROFILES = listOf(
        Profile("music", "Music", "Wide stage, warm bass, airy highs", 35, 0, 35, 30, 0, 10),
        Profile("movie", "Movie", "Cinema surround and clear dialogue", 70, 45, 50, 25, 45, 35),
        Profile("game", "Game", "Very wide and punchy – hear every direction", 85, 20, 40, 45, 20, 15),
        Profile("voice", "Voice", "Podcasts, talks and devotional speech", 10, 75, 0, 15, 65, 0),
        Profile("night", "Night", "Quiet listening: even volume, soft bass", 25, 35, 10, 10, 80, 5),
        Profile("custom", "Custom", "Your own mix", 0, 0, 0, 0, 0, 0),
    )

    fun profile(id: String) = PROFILES.firstOrNull { it.id == id } ?: PROFILES[0]

    fun applyProfile(id: String) {
        Prefs.seProfile = id
        val p = profile(id)
        if (p.id == "custom") return
        Prefs.seSurround = p.surround; Prefs.seDialogue = p.dialogue; Prefs.seBass = p.bass
        Prefs.seTreble = p.treble; Prefs.seLeveller = p.leveller; Prefs.seRoom = p.room
    }

    enum class Output(val label: String) { HEADPHONES("Headphones"), BLUETOOTH("Bluetooth headphones"), SPEAKER("Speaker") }

    /** What the music is playing through right now. */
    fun detect(ctx: Context): Output {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return Output.SPEAKER
        if (Build.VERSION.SDK_INT >= 23) {
            val outs = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Throwable) { emptyArray() }
            if (outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) return Output.BLUETOOTH
            if (outs.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                }) return Output.HEADPHONES
            return Output.SPEAKER
        }
        @Suppress("DEPRECATION")
        return when {
            am.isBluetoothA2dpOn -> Output.BLUETOOTH
            am.isWiredHeadsetOn -> Output.HEADPHONES
            else -> Output.SPEAKER
        }
    }

    /** The output the engine tunes for: the user's choice, or what is connected. */
    fun output(ctx: Context): Output = when (Prefs.seOutput) {
        "headphones" -> Output.HEADPHONES
        "speaker" -> Output.SPEAKER
        else -> detect(ctx)
    }

    /** Effective settings after tuning for the output (speakers get less surround and bass). 0..1 each. */
    class Values(val surround: Float, val dialogue: Float, val bass: Float, val treble: Float, val leveller: Float, val room: Float,
                 val headphones: Boolean)

    fun values(ctx: Context): Values {
        val out = output(ctx)
        val hp = out != Output.SPEAKER
        fun f(v: Int) = v.coerceIn(0, 100) / 100f
        return Values(
            surround = f(Prefs.seSurround) * (if (hp) 1f else 0.5f),
            dialogue = f(Prefs.seDialogue),
            bass = f(Prefs.seBass) * (if (hp) 1f else 0.7f),
            treble = f(Prefs.seTreble),
            leveller = f(Prefs.seLeveller),
            room = f(Prefs.seRoom),
            headphones = hp,
        )
    }

    // tone curve: six control points (Hz) and their gains in dB
    val TONE_FREQS = intArrayOf(60, 150, 400, 1500, 3500, 10000)

    fun toneDb(v: Values): FloatArray {
        val g = FloatArray(6)
        g[0] += v.bass * 9f; g[1] += v.bass * 4f
        g[2] -= v.dialogue * 1f; g[3] += v.dialogue * 6f; g[4] += v.dialogue * 3.5f
        g[4] += v.treble * 2f; g[5] += v.treble * 7f
        return g
    }
}
