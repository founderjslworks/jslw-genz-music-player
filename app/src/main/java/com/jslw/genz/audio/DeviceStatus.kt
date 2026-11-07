package com.jslw.genz.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * What the music is playing on: the Bluetooth device's name, its own battery (when it reports one),
 * the Bluetooth codec in use, and this phone's battery.
 *
 * Android does not give apps the signal strength (RSSI) of an already-connected Bluetooth audio device,
 * so the link is described by its codec and sample rate instead.
 */
object DeviceStatus {
    class Info(
        val name: String?, val soundbar: Boolean, val bluetooth: Boolean,
        val deviceBattery: Int?, val codec: String?, val phoneBattery: Int, val charging: Boolean,
        val needsPermission: Boolean,
    )

    const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private val main = Handler(Looper.getMainLooper())

    fun canReadBluetooth(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun phoneBattery(ctx: Context): Pair<Int, Boolean> {
        val i = try { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Throwable) { null }
            ?: return -1 to false
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return (if (lvl < 0) -1 else lvl * 100 / scale) to charging
    }

    /** Reads everything; [done] runs on the main thread (null = not playing on Bluetooth / HDMI). */
    @SuppressLint("MissingPermission")
    fun read(ctx: Context, done: (Info?) -> Unit) {
        val app = ctx.applicationContext
        val out = SoundEngine.detect(app)
        val (pb, chg) = phoneBattery(app)
        val name = SoundEngine.deviceName(app)
        val bt = out == SoundEngine.Output.BLUETOOTH || (out == SoundEngine.Output.SOUNDBAR && name != null && !isHdmi(app))
        if (out != SoundEngine.Output.BLUETOOTH && out != SoundEngine.Output.SOUNDBAR) { done(null); return }
        val base = { batt: Int?, codec: String?, perm: Boolean ->
            Info(name, out == SoundEngine.Output.SOUNDBAR, bt, batt, codec, pb, chg, perm)
        }
        if (!bt) { done(base(null, null, false)); return }
        if (!canReadBluetooth(app)) { done(base(null, null, true)); return }
        val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (_: Throwable) { null }
        if (adapter == null) { done(base(null, null, false)); return }
        var finished = false
        val finish = { i: Info -> if (!finished) { finished = true; main.post { done(i) } } }
        main.postDelayed({ finish(base(null, null, false)) }, 1500)
        try {
            adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                @SuppressLint("MissingPermission")
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val dev = try { proxy.connectedDevices.firstOrNull() } catch (_: Throwable) { null }
                    val batt = dev?.let { batteryOf(it) }
                    val codec = dev?.let { codecOf(proxy, it) }
                    try { adapter.closeProfileProxy(profile, proxy) } catch (_: Throwable) {}
                    finish(base(batt, codec, false))
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
        } catch (_: Throwable) { finish(base(null, null, false)) }
    }

    private fun isHdmi(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        return try {
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == android.media.AudioDeviceInfo.TYPE_HDMI || it.type == android.media.AudioDeviceInfo.TYPE_HDMI_ARC
            } && am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).none { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        } catch (_: Throwable) { false }
    }

    /** Battery the device reports over Bluetooth (Android 8.1+), or null when it doesn't report one. */
    fun batteryOf(d: BluetoothDevice): Int? = try {
        (BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as Int).takeIf { it in 0..100 }
    } catch (_: Throwable) { null }

    private fun codecOf(proxy: BluetoothProfile, d: BluetoothDevice): String? = try {
        val status = if (Build.VERSION.SDK_INT >= 28)
            proxy.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(proxy, d)
        else proxy.javaClass.getMethod("getCodecStatus").invoke(proxy)
        val cfg = status?.javaClass?.getMethod("getCodecConfig")?.invoke(status)
        val type = cfg?.javaClass?.getMethod("getCodecType")?.invoke(cfg) as? Int
        val rate = cfg?.javaClass?.getMethod("getSampleRate")?.invoke(cfg) as? Int
        val n = when (type) {
            0 -> "SBC"; 1 -> "AAC"; 2 -> "aptX"; 3 -> "aptX HD"; 4 -> "LDAC"; 5 -> "LC3"; 6 -> "Opus"
            else -> null
        }
        // BluetoothCodecConfig sample-rate flags
        val r = when (rate) { 0x1 -> "44.1 kHz"; 0x2 -> "48 kHz"; 0x4 -> "88.2 kHz"; 0x8 -> "96 kHz"; else -> null }
        n?.let { if (r != null) "$it · $r" else it }
    } catch (_: Throwable) { null }
}
