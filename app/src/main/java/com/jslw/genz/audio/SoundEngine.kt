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

    enum class Output(val label: String) {
        HEADPHONES("Headphones"), BLUETOOTH("Bluetooth headphones"), SPEAKER("Speaker"),
        SOUNDBAR("Soundbar / home theatre"),
    }

    /** Bluetooth device names that are soundbars or home-theatre systems rather than headphones. */
    private val BAR_WORDS = listOf("bar", "aavante", "theatre", "theater", "home", "tv", "cinema", "hts", "subwoofer", "sound system")

    fun isSoundbarName(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return BAR_WORDS.any { n.contains(it) }
    }

    /** What the music is playing through right now. */
    fun detect(ctx: Context): Output {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return Output.SPEAKER
        if (Build.VERSION.SDK_INT >= 23) {
            val outs = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Throwable) { emptyArray() }
            outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.let { bt ->
                return if (isSoundbarName(bt.productName?.toString())) Output.SOUNDBAR else Output.BLUETOOTH
            }
            if (outs.any { it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC }) return Output.SOUNDBAR
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

    /** The connected Bluetooth / HDMI device's own name (e.g. a soundbar's model), if Android reports it. */
    fun deviceName(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 23) return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val outs = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Throwable) { return null }
        return outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC
        }?.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
    }

    /** The output the engine tunes for: the user's choice, or what is connected. */
    fun output(ctx: Context): Output = when (Prefs.seOutput) {
        "headphones" -> Output.HEADPHONES
        "speaker" -> Output.SPEAKER
        "soundbar" -> Output.SOUNDBAR
        else -> detect(ctx)
    }

    /** Effective settings after tuning for the output (speakers get less surround and bass). 0..1 each. */
    class Values(val surround: Float, val dialogue: Float, val bass: Float, val treble: Float, val leveller: Float, val room: Float,
                 val headphones: Boolean, val soundbar: Boolean = false)

    fun values(ctx: Context): Values {
        val out = output(ctx)
        fun f(v: Int) = v.coerceIn(0, 100) / 100f
        if (out == Output.SOUNDBAR) {
            // Soundbar mode: speaker-style (transaural) widening for a bar in front of you, voices always lifted
            // for TV and films, a firm leveller for late-night viewing, and bass left to the bar's subwoofer.
            return Values(
                surround = (0.35f + f(Prefs.seSurround) * 0.65f).coerceAtMost(1f),
                dialogue = maxOf(f(Prefs.seDialogue), 0.35f),
                bass = f(Prefs.seBass) * 0.6f,
                treble = f(Prefs.seTreble) * 0.85f,
                leveller = maxOf(f(Prefs.seLeveller), 0.3f),
                room = f(Prefs.seRoom) * 0.8f,
                headphones = false, soundbar = true,
            )
        }
        val hp = out != Output.SPEAKER
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
