/*
 * Copyright (c) 2026 JSL WORKS PVT LTD. All rights reserved.
 * Product of JSL WORKS PVT LTD — www.jslworks.in
 * Proprietary and Confidential. Unauthorized copying or distribution is strictly prohibited.
 */
package com.jslw.genz.audio

import com.jslw.genz.data.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSLW SoundFit profiles – one per output device (earphones, neckband, headphones…), from the "Tune to my ears"
 * hearing test. The active device's profile is the first stage of JSLW Audio+ and switches with the device.
 */
object SoundFitStore {
    class Profile(
        val device: String,
        val full: Boolean,
        val frequenciesHz: DoubleArray,
        val left: DoubleArray,
        val right: DoubleArray,
        val time: Long,
        val on: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().put("full", full).put("time", time).put("on", on)
            .put("f", JSONArray(frequenciesHz.toList())).put("l", JSONArray(left.toList())).put("r", JSONArray(right.toList()))
    }

    private fun all(): JSONObject = try { JSONObject(Prefs.sfProfiles) } catch (_: Exception) { JSONObject() }

    fun get(device: String = AudioPlus.outputName): Profile? {
        val o = all().optJSONObject(device) ?: return null
        fun arr(k: String): DoubleArray { val a = o.optJSONArray(k) ?: JSONArray(); return DoubleArray(a.length()) { a.optDouble(it) } }
        val f = arr("f"); val l = arr("l"); val r = arr("r")
        if (f.isEmpty() || l.size != f.size || r.size != f.size || l.any { it.isNaN() } || r.any { it.isNaN() }) return null
        return Profile(device, o.optBoolean("full"), f, l, r, o.optLong("time"), o.optBoolean("on", true))
    }

    fun save(p: Profile) { Prefs.sfProfiles = all().put(p.device, p.toJson()).toString() }

    fun setOn(on: Boolean, device: String = AudioPlus.outputName) {
        val p = get(device) ?: return
        save(Profile(p.device, p.full, p.frequenciesHz, p.left, p.right, p.time, on))
    }

    fun delete(device: String = AudioPlus.outputName) { val o = all(); o.remove(device); Prefs.sfProfiles = o.toString() }

    /** The profile Audio+ applies right now (current device, switched on), or null. */
    fun active(): Profile? = get()?.takeIf { it.on }
}
