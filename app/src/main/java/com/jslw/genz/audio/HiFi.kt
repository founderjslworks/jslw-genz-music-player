/*
 * Copyright (c) 2026 JSL WORKS PVT LTD. All rights reserved.
 * Product of JSL WORKS PVT LTD — www.jslworks.in
 * Proprietary and Confidential. Unauthorized copying or distribution is strictly prohibited.
 */
package com.jslw.genz.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.jslw.genz.data.Prefs
import kotlin.math.log10

/**
 * Hi-Fi settings for the JSL Audio Engine C++ core inside JSL Audio+ (Android 5.0+):
 *  - True-peak protection (feature flag, on by default): the core's 4× oversampled limiter at −1 dBTP is the final
 *    stage – bit-identical while the music stays under the ceiling. Adds [latencyMs] of look-ahead.
 *  - Hi-Fi section switch with Loudness compensation (ISO 226 equal-loudness, from the listening level) and
 *    Crossfeed (headphones only).
 */
object HiFi {
    /** Where the Kotlin sample-peak limiter is parked while the C++ true-peak limiter is the final stage. */
    const val KOTLIN_LIMITER_PARKED_DB = 12.0
    const val CEILING_DB = -1.0

    /** The native library is on this device (loaded once, on first use). */
    val coreAvailable: Boolean by lazy {
        Build.VERSION.SDK_INT >= 21 && try { com.jslworks.soundengine.nativecore.JslwNativeEngine.isAvailable } catch (_: Throwable) { false }
    }

    val coreActive: Boolean get() = Prefs.hfTruePeak && coreAvailable

    /** Crossfeed makes sense only on headphones / earphones. */
    val headphones: Boolean get() = !AudioPlus.speaker && AudioPlus.kind == AudioPlus.KIND_HEADPHONES

    val loudnessOn: Boolean get() = coreActive && Prefs.hfOn && Prefs.hfLoudness
    val crossfeedOn: Boolean get() = coreActive && Prefs.hfOn && Prefs.hfCrossfeed && headphones

    // ---------------------------------------------------------------- listening level → phon
    /** Output level of the music stream relative to full volume, dB (updated about once a second). */
    @Volatile var volumeDb = 0.0; private set

    fun updateVolume(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val idx = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            volumeDb = if (idx <= 0) -60.0 else if (Build.VERSION.SDK_INT >= 28) {
                val dev = when {
                    AudioPlus.speaker -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    AudioPlus.bluetooth -> AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    else -> AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                }
                val db = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, idx, dev).toDouble()
                if (db.isNaN() || db < -96) 40 * log10(idx.toDouble() / max) else db
            } else 40 * log10(idx.toDouble() / max)
        } catch (_: Throwable) {}
    }

    /**
     * Estimated listening level (phon ≈ dB SPL at 1 kHz). Mastering reference: a −14 LUFS song at full volume ≈ 83 phon
     * (the level the curves are flat at). Quieter volume or a quieter song → lower phon → more bass/treble restored.
     */
    fun listeningPhon(songLufs: Double): Double {
        val lufs = if (songLufs.isNaN() || songLufs < -60) -14.0 else songLufs
        return (83.0 + volumeDb + (lufs + 14.0)).coerceIn(30.0, 90.0)
    }

    /** Look-ahead delay of the true-peak limiter at [rate], ms (1.5 ms + 13-sample detector ≈ 85 samples at 48 kHz). */
    fun latencyMs(rate: Int): Double = if (!coreActive || rate <= 0) 0.0 else (Math.floor(1.5 * rate / 1000.0 + 0.5) + 13) * 1000.0 / rate
}
