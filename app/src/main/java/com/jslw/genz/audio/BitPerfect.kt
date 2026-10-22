package com.jslw.genz.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Bit-perfect output to a USB DAC (Android 14+). Asks the system mixer to pass audio through
 * unchanged at the file's sample rate. Effects (EQ etc.) are bypassed by Android in this mode.
 */
object BitPerfect {

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 34

    /** @return a human readable status line. */
    fun apply(ctx: Context, enable: Boolean, info: FormatInfo?): String {
        if (!supported()) return "Bit-perfect output needs Android 14 or newer"
        return apply34(ctx, enable, info)
    }

    @RequiresApi(34)
    private fun apply34(ctx: Context, enable: Boolean, info: FormatInfo?): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = Engine.musicAttributes()!!
        val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        } ?: return if (enable) "Connect a USB DAC to use bit-perfect output" else "Off"
        return try {
            if (!enable) {
                am.clearPreferredMixerAttributes(attrs, usb)
                "Off"
            } else {
                val list = am.getSupportedMixerAttributes(usb)
                    .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
                if (list.isEmpty()) return "This DAC/phone doesn't offer a bit-perfect path"
                val want = info?.sampleRate ?: 44100
                val pick = list.firstOrNull { it.format.sampleRate == want } ?: list.maxByOrNull { it.format.sampleRate }!!
                if (am.setPreferredMixerAttributes(attrs, usb, pick))
                    "Bit-perfect: ${pick.format.sampleRate / 1000.0} kHz to ${usb.productName}"
                else "The system refused bit-perfect mode"
            }
        } catch (e: Exception) {
            "Bit-perfect unavailable: ${e.message}"
        }
    }
}
