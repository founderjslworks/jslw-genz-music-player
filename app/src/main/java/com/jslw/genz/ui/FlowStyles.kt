package com.jslw.genz.ui

import android.view.View
import com.jslw.genz.data.Prefs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Album Flow layouts. Each one places a cover from its signed distance to the centre [d] (in cover widths):
 * rotation, scale, offset and fade. The list scrolls normally underneath, so every style snaps and flings alike.
 */
object FlowStyles {
    val ALL = listOf(
        "classic" to "Classic flow",
        "carousel" to "3D carousel ring",
        "stack" to "Card stack",
        "wave" to "Wave",
        "fan" to "Fan of cards",
        "crate" to "Record crate",
        "depth" to "Depth tunnel",
    )

    /** The style to draw: the user's pick, or the active theme's suggestion ("theme"). */
    fun current(): String {
        val id = Prefs.flowStyle.let { if (it == "theme") com.jslw.genz.skin.SkinManager.current.flowStyle else it }
        return id.takeIf { x -> ALL.any { it.first == x } } ?: "classic"
    }

    /** For the pickers: "Theme default" first. */
    val CHOICES: List<Pair<String, String>>
        get() {
            val t = com.jslw.genz.skin.SkinManager.current.flowStyle
            val name = ALL.firstOrNull { it.first == t }?.second ?: ALL[0].second
            return listOf("theme" to "Theme default ($name)") + ALL
        }

    fun apply(style: String, c: View, d: Float, cover: Float, lean: Float) {
        val ad = abs(d)
        val cl = d.coerceIn(-1f, 1f)
        val sg = sign(d)
        var rx = 0f; var ry = 0f; var rz = 0f; var ty = 0f
        val tx: Float; val s: Float; val alpha: Float
        when (style) {
            "carousel" -> {
                // covers sit on a turning drum: angle grows with distance, depth shrinks them
                val ang = (d * 34f).coerceIn(-150f, 150f)
                val rad = Math.toRadians(ang.toDouble())
                val r = cover * 1.05f
                tx = (sin(rad) * r).toFloat() - d * cover
                s = (0.74f + 0.26f * cos(rad).toFloat()).coerceAtLeast(0.45f)
                ry = -ang - lean
                alpha = if (abs(ang) > 95f) ((150f - abs(ang)) / 55f).coerceIn(0f, 1f) else 1f
            }
            "stack" -> {
                // a deck: the next covers peek out from behind the front one
                val k = min(ad, 4f)
                tx = -d * cover + sg * k * cover * 0.11f
                ty = -k * cover * 0.055f
                s = 1f - 0.09f * k
                ry = -lean * 0.4f
                alpha = (1f - (ad - 3f).coerceAtLeast(0f)).coerceIn(0f, 1f)
            }
            "wave" -> {
                // flat covers riding a gentle wave that rolls as you browse
                tx = -sg * min(ad, 3f) * cover * 0.22f
                ty = sin(d * 1.3f) * cover * 0.11f
                s = 1f - 0.2f * min(ad, 1.5f) / 1.5f
                rz = (-d * 5f).coerceIn(-12f, 12f)
                ry = -lean * 0.5f
                alpha = (1f - 0.12f * (ad - 1.5f).coerceAtLeast(0f)).coerceIn(0.3f, 1f)
            }
            "fan" -> {
                // a hand of cards pivoting from below the screen
                c.pivotY = cover * 1.9f
                rz = (d * 14f).coerceIn(-70f, 70f)
                tx = -d * cover * 0.66f
                ty = min(ad, 3f) * cover * 0.03f
                s = 1f - 0.07f * min(ad, 2f)
                alpha = (1f - 0.18f * (ad - 2f).coerceAtLeast(0f)).coerceIn(0.2f, 1f)
            }
            "crate" -> {
                // flicking through records in a crate: side covers tip back and drop
                rx = -min(ad, 1f) * 32f
                ry = -cl * 22f - lean
                val shift = if (ad <= 1f) ad * 0.42f else 0.42f + (ad - 1f) * 0.74f
                tx = -sg * shift * cover
                ty = min(ad, 1f) * cover * 0.09f
                s = 1f - 0.12f * min(ad, 1f)
                alpha = (1f - 0.15f * (ad - 1f).coerceAtLeast(0f)).coerceIn(0.25f, 1f)
            }
            "depth" -> {
                // a tunnel: neighbours shrink into the distance
                tx = -d * cover * 0.56f
                s = 1f / (1f + ad * 0.62f)
                ry = -cl * 14f - lean * 0.6f
                alpha = (1f - ad * 0.22f).coerceIn(0.15f, 1f)
            }
            else -> {
                ry = -cl * 58f - lean
                s = 1f - 0.16f * min(ad, 1f)
                val shift = if (ad <= 1f) ad * 0.48f else 0.48f + (ad - 1f) * 0.70f
                tx = -sg * shift * cover
                alpha = (1f - 0.14f * (ad - 1f).coerceAtLeast(0f)).coerceIn(0.25f, 1f)
            }
        }
        c.rotationX = rx; c.rotationY = ry; c.rotation = rz
        c.scaleX = s; c.scaleY = s
        c.translationX = tx; c.translationY = ty
        c.alpha = alpha
    }

    // ---- smooth hand-over between styles
    fun snapshot(v: View) = floatArrayOf(v.rotationX, v.rotationY, v.rotation, v.scaleX, v.translationX, v.translationY, v.alpha, v.pivotY)

    fun blend(v: View, a: FloatArray, b: FloatArray, t: Float) {
        fun m(i: Int) = a[i] + (b[i] - a[i]) * t
        v.pivotY = b[7]
        v.rotationX = m(0); v.rotationY = m(1); v.rotation = m(2)
        v.scaleX = m(3); v.scaleY = m(3)
        v.translationX = m(4); v.translationY = m(5)
        v.alpha = m(6).coerceIn(0f, 1f)
    }
}
