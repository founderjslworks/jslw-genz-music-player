package com.jslw.genz.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.core.graphics.ColorUtils
import com.jslw.genz.util.FrameTicker
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Touch effects for the album cover, inspired by classic phone lock-screen unlock effects.
 * The view sits on top of the cover; touches are fed to it (it never consumes them), so the
 * cover's own gestures keep working. Effects that bend or slice the picture use the real cover.
 * Animation runs on vsync only while an effect is visible.
 */
class CoverEffectView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    companion object {
        /** id → label, in menu order. */
        val EFFECTS: List<Pair<String, String>> = listOf(
            "none" to "Off",
            "ripple" to "Water Ripple",
            "stone" to "Stone Skipping",
            "lens_flare" to "Lens Flare",
            "popping" to "Popping Colours",
            "brilliant_ring" to "Brilliant Ring",
            "ink" to "Ink in Water · Indigo",
            "watercolor" to "Watercolour & Ripple Ink",
            "tiles" to "Abstract Tiles",
            "mosaic" to "Geometric Mosaic",
            "droplet" to "Coloured Droplet",
            "gyro" to "Gyro Droplet (tilt the phone)",
            "bubbles" to "Sparkling Bubbles",
            "blinds" to "Blind",
            "brilliant_cut" to "Brilliant Cut",
            "rect_traveller" to "Rectangle Traveller",
            "bouncing" to "Bouncing Colour",
            "white_hole" to "White Hole",
            "dewdrop" to "Dewdrop",
            "hula" to "Hula Hoop",
            "hula2" to "Hula Hoop V2",
            "soda" to "Soda",
            "particle" to "Particle",
            "light_particle" to "Light Particle",
            "pixelate" to "Pixelate",
            "crystal" to "Crystal",
            "vector" to "Vector",
            "circle_mosaic" to "Circle Mosaic",
            "revolving_glass" to "Revolving Glass",
            "seasonal" to "Seasonal (changes with the month)",
            "spring" to "Spring · blossoms",
            "summer" to "Summer · sunshine",
            "autumn" to "Autumn · falling leaves",
            "winter" to "Winter · snowflakes",
        )

        fun label(id: String) = EFFECTS.firstOrNull { it.first == id }?.second ?: id

        fun seasonNow(): String = when (Calendar.getInstance().get(Calendar.MONTH)) {
            2, 3, 4 -> "spring"
            5, 6, 7 -> "summer"
            8, 9, 10 -> "autumn"
            else -> "winter"
        }
    }

    // ---------------------------------------------------------------- shared state for effects
    internal val d = resources.displayMetrics.density
    internal val rnd = Random(11)
    internal var time = 0f
    internal var art: Bitmap? = null
    internal var palette = intArrayOf(0xFFFF3D9A.toInt(), 0xFF7B2FF7.toInt(), 0xFF00C7BE.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt())
    internal var cornerRadius = 14f * d
    internal val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artMatrix = Matrix()
    internal val blob: Bitmap by lazy { makeBlob() }
    private val filters = HashMap<Int, PorterDuffColorFilter>()
    private val clip = Path()
    private val clipRect = RectF()

    /** Colour filter cache for tinting the soft blob (no allocations per frame). */
    internal fun tint(c: Int): PorterDuffColorFilter =
        filters.getOrPut(c or 0xFF000000.toInt()) { PorterDuffColorFilter(c or 0xFF000000.toInt(), PorterDuff.Mode.SRC_IN) }

    private fun makeBlob(): Bitmap {
        val s = 96
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(0xFFFFFFFF.toInt(), 0x88FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        return b
    }

    /** Draws the soft round blob tinted [color], centred at x,y with radius r. */
    internal fun drawBlob(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int, p: Paint) {
        p.colorFilter = tint(color)
        p.alpha = alpha.coerceIn(0, 255)
        tmpRect.set(x - r, y - r, x + r, y + r)
        c.drawBitmap(blob, null, tmpRect, p)
        p.colorFilter = null
    }

    internal val tmpRect = RectF()

    // ---------------------------------------------------------------- effect selection
    private var fx: Fx? = null
    var effectId = "none"
        private set
    /** Seasonal effects drift gently over the cover while music plays. */
    var ambient = false
        set(v) { field = v; if (v && fx?.hasAmbient == true) ticker.start() }

    fun setEffect(id: String) {
        val real = if (id == "seasonal") seasonNow() else id
        if (real == effectId && fx != null) return
        fx?.release()
        effectId = real
        fx = when (real) {
            "ripple" -> RippleFx(this, stone = false)
            "stone" -> RippleFx(this, stone = true)
            "white_hole" -> WhiteHoleFx(this)
            "lens_flare" -> LensFlareFx(this)
            "popping" -> PoppingFx(this)
            "brilliant_ring" -> BrilliantRingFx(this)
            "ink" -> InkFx(this, watercolor = false)
            "watercolor" -> InkFx(this, watercolor = true)
            "tiles" -> TilesFx(this, triangles = false)
            "mosaic" -> TilesFx(this, triangles = true)
            "droplet" -> DropletFx(this, gyro = false)
            "gyro" -> DropletFx(this, gyro = true)
            "bubbles" -> BubblesFx(this, soda = false)
            "soda" -> BubblesFx(this, soda = true)
            "blinds" -> StripsFx(this, vertical = false)
            "revolving_glass" -> StripsFx(this, vertical = true)
            "brilliant_cut" -> BrilliantCutFx(this)
            "rect_traveller" -> TravellerFx(this)
            "bouncing" -> BouncingFx(this)
            "dewdrop" -> DewdropFx(this)
            "hula" -> HulaFx(this, v2 = false)
            "hula2" -> HulaFx(this, v2 = true)
            "particle" -> ParticleFx(this, light = false)
            "light_particle" -> ParticleFx(this, light = true)
            "pixelate" -> GridFx(this, circles = false)
            "circle_mosaic" -> GridFx(this, circles = true)
            "crystal" -> CrystalFx(this)
            "vector" -> VectorFx(this)
            "spring", "summer", "autumn", "winter" -> SeasonFx(this, real)
            else -> null
        }
        fx?.onArt()
        if (width > 0) fx?.onSize()
        if (ambient && fx?.hasAmbient == true) ticker.start()
        invalidate()
    }

    fun setArt(b: Bitmap?) {
        art = b ?: fallbackArt()
        computePalette()
        updateShader()
        fx?.onArt()
        invalidate()
    }

    private fun fallbackArt(): Bitmap {
        val s = 256
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(), palette[0], palette[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        return b
    }

    fun setThemeColors(accent: Int, second: Int) {
        if (art == null) palette = intArrayOf(accent, second, ColorUtils.blendARGB(accent, Color.WHITE, 0.4f), 0xFFFFCC00.toInt(), 0xFF00C7BE.toInt())
    }

    /** The five most colourful colours of the cover, used by colour effects. */
    private fun computePalette() {
        val a = art ?: return
        val small = Bitmap.createScaledBitmap(a, 12, 12, true)
        val px = IntArray(144)
        small.getPixels(px, 0, 12, 0, 0, 12, 12)
        val hsl = FloatArray(3)
        val scored = px.map { c ->
            ColorUtils.colorToHSL(c, hsl)
            c to hsl[1] * (1f - abs(hsl[2] - 0.55f) * 1.4f)
        }.sortedByDescending { it.second }
        val picked = ArrayList<Int>()
        for ((c, _) in scored) {
            if (picked.none { ColorUtils.calculateContrast(it or 0xFF000000.toInt(), c or 0xFF000000.toInt()) < 1.25 }) picked += c
            if (picked.size == 5) break
        }
        while (picked.size < 5) picked += palette[picked.size]
        // lift dull colours so effects stay vivid
        palette = IntArray(5) { i ->
            ColorUtils.colorToHSL(picked[i], hsl)
            hsl[1] = max(hsl[1], 0.55f); hsl[2] = hsl[2].coerceIn(0.45f, 0.7f)
            ColorUtils.HSLToColor(hsl)
        }
        if (small != a) small.recycle()
    }

    private fun updateShader() {
        val a = art ?: return
        if (width == 0) return
        val sh = BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        // centre-crop like the ImageView underneath
        val scale = max(width.toFloat() / a.width, height.toFloat() / a.height)
        artMatrix.setScale(scale, scale)
        artMatrix.postTranslate((width - a.width * scale) / 2f, (height - a.height * scale) / 2f)
        sh.setLocalMatrix(artMatrix)
        artPaint.shader = sh
    }

    /** Draws the cover itself (for effects that move pieces of it). */
    internal fun drawArt(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), artPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clip.reset()
        clipRect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)
        updateShader()
        fx?.onSize()
    }

    // ---------------------------------------------------------------- touch (fed by the cover)
    private var vt: VelocityTracker? = null

    /** Feed the cover's touch events here (coordinates relative to this view). Never consumes. */
    fun feed(e: MotionEvent, offsetX: Float = 0f, offsetY: Float = 0f) {
        val f = fx ?: return
        val x = e.x - offsetX
        val y = e.y - offsetY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                f.down(x, y); ticker.start()
            }
            MotionEvent.ACTION_MOVE -> { vt?.addMovement(e); f.move(x, y) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                f.up(x, y, vt?.xVelocity ?: 0f, vt?.yVelocity ?: 0f)
                vt?.recycle(); vt = null
            }
        }
    }

    // ---------------------------------------------------------------- frame loop
    private var last = 0L
    private val ticker = FrameTicker { now -> frame(now) }

    private fun frame(now: Long) {
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        time += dt
        val f = fx
        if (f == null) { ticker.stop(); last = 0L; return }
        if (ambient && f.hasAmbient) f.ambient(dt)
        f.step(dt)
        invalidate()
        if (!f.busy && !(ambient && f.hasAmbient)) { ticker.stop(); last = 0L; invalidate() }
    }

    override fun onDetachedFromWindow() {
        ticker.stop(); last = 0L
        fx?.release()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fx?.onAttach()
    }

    override fun onDraw(canvas: Canvas) {
        val f = fx ?: return
        if (!f.visible) return
        canvas.save()
        canvas.clipPath(clip)
        f.draw(canvas)
        canvas.restore()
    }
}

// ======================================================================= effect base & helpers

internal abstract class Fx(val v: CoverEffectView) {
    val d get() = v.d
    val w get() = v.width.toFloat()
    val h get() = v.height.toFloat()
    val rnd get() = v.rnd
    var touching = false
    var tx = 0f
    var ty = 0f
    var lastSpawnX = 0f
    var lastSpawnY = 0f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    open val hasAmbient = false

    open fun down(x: Float, y: Float) { touching = true; tx = x; ty = y; lastSpawnX = x; lastSpawnY = y; onSpawnPoint(x, y, true) }
    open fun move(x: Float, y: Float) {
        tx = x; ty = y
        val dist = hypot(x - lastSpawnX, y - lastSpawnY)
        val stepD = spawnSpacing() * d
        if (dist >= stepD) {
            val n = (dist / stepD).toInt().coerceAtMost(6)
            for (k in 1..n) {
                val t = k / n.toFloat()
                onSpawnPoint(lastSpawnX + (x - lastSpawnX) * t, lastSpawnY + (y - lastSpawnY) * t, false)
            }
            lastSpawnX = x; lastSpawnY = y
        }
    }
    open fun up(x: Float, y: Float, vx: Float, vy: Float) { touching = false }
    open fun onSpawnPoint(x: Float, y: Float, first: Boolean) {}
    open fun spawnSpacing() = 14f
    abstract fun step(dt: Float)
    abstract fun draw(c: Canvas)
    abstract val busy: Boolean
    open val visible: Boolean get() = busy
    open fun ambient(dt: Float) {}
    open fun onArt() {}
    open fun onSize() {}
    open fun onAttach() {}
    open fun release() {}

    fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
    fun pal(i: Int) = v.palette[((i % 5) + 5) % 5]
    fun randPal() = v.palette[rnd.nextInt(5)]
    fun fade(life: Float, max: Float): Float {
        val f = life / max
        return if (f < 0.12f) f / 0.12f else 1f - (f - 0.12f) / 0.88f
    }
}

/** Fixed-size particle pool shared by the particle effects. */
internal class Pool(val n: Int) {
    val x = FloatArray(n); val y = FloatArray(n); val vx = FloatArray(n); val vy = FloatArray(n)
    val life = FloatArray(n); val max = FloatArray(n); val size = FloatArray(n)
    val rot = FloatArray(n); val vr = FloatArray(n); val col = IntArray(n); val kind = IntArray(n)
    var count = 0

    fun add(): Int {
        if (count >= n) return -1
        val i = count++
        life[i] = 0f; rot[i] = 0f; vr[i] = 0f; kind[i] = 0; vx[i] = 0f; vy[i] = 0f
        return i
    }

    fun removeAt(i: Int) {
        val l = count - 1
        x[i] = x[l]; y[i] = y[l]; vx[i] = vx[l]; vy[i] = vy[l]; life[i] = life[l]; max[i] = max[l]
        size[i] = size[l]; rot[i] = rot[l]; vr[i] = vr[l]; col[i] = col[l]; kind[i] = kind[l]
        count--
    }

    /** Integrates motion; [onDeath] may return true to keep (recycle) the particle. */
    inline fun update(dt: Float, gx: Float, gy: Float, drag: Float, onDeath: (Int) -> Boolean = { false }) {
        var i = 0
        while (i < count) {
            life[i] += dt
            if (life[i] >= max[i] && !onDeath(i)) { removeAt(i); continue }
            vx[i] += gx * dt; vy[i] += gy * dt
            if (drag > 0f) { val k = 1f - drag * dt; vx[i] *= k; vy[i] *= k }
            x[i] += vx[i] * dt; y[i] += vy[i] * dt
            rot[i] += vr[i] * dt
            i++
        }
    }
}

// ======================================================================= water ripple & stone skipping

/** Real water simulation on a small copy of the cover; the picture is refracted through the waves. */
internal class RippleFx(v: CoverEffectView, private val stone: Boolean) : Fx(v) {
    private val s = 160
    private var src = IntArray(s * s)
    private var cur = IntArray(s * s)
    private var prev = IntArray(s * s)
    private val out = IntArray(s * s)
    private val bmp: Bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    private var energy = 0
    private val dst = RectF()
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pending = ArrayList<FloatArray>() // x, y, strength, delay

    override fun onArt() {
        val a = v.art ?: return
        val sc = Bitmap.createScaledBitmap(a, s, s, true)
        sc.getPixels(src, 0, s, 0, 0, s, s)
        if (sc != a) sc.recycle()
    }

    private fun drop(x: Float, y: Float, strength: Int, radius: Int) {
        if (w == 0f) return
        val cx = (x / w * s).toInt(); val cy = (y / h * s).toInt()
        for (j in -radius..radius) for (i in -radius..radius) {
            if (i * i + j * j > radius * radius) continue
            val px = cx + i; val py = cy + j
            if (px in 1 until s - 1 && py in 1 until s - 1) cur[py * s + px] += strength
        }
        energy = max(energy, strength)
    }

    override fun spawnSpacing() = 10f
    override fun onSpawnPoint(x: Float, y: Float, first: Boolean) = drop(x, y, if (first) 900 else 380, if (first) 4 else 2)

    override fun up(x: Float, y: Float, vx: Float, vy: Float) {
        super.up(x, y, vx, vy)
        if (!stone) return
        // skip the stone along the fling direction: ever smaller splashes, ever closer together
        val speed = hypot(vx, vy)
        val dx = if (speed > 200) vx / speed else 0f
        val dy = if (speed > 200) vy / speed else -1f
        var px = x; var py = y
        var gap = w * 0.22f
        var st = 1100f
        for (k in 0 until 5) {
            px += dx * gap; py += dy * gap
            if (px < 0 || py < 0 || px > w || py > h) break
            pending += floatArrayOf(px, py, st, 0.13f * (k + 1))
            gap *= 0.7f; st *= 0.72f
        }
    }

    override fun step(dt: Float) {
        if (pending.isNotEmpty()) {
            val it = pending.iterator()
            while (it.hasNext()) {
                val p = it.next()
                p[3] -= dt
                if (p[3] <= 0f) { drop(p[0], p[1], p[2].toInt(), 3); it.remove() }
            }
        }
        if (energy <= 2 && pending.isEmpty()) return
        // propagate
        var mx = 0
        for (y in 1 until s - 1) {
            var i = y * s + 1
            for (x in 1 until s - 1) {
                var n = ((cur[i - 1] + cur[i + 1] + cur[i - s] + cur[i + s]) shr 1) - prev[i]
                n -= n shr 5
                prev[i] = n
                val a = if (n < 0) -n else n
                if (a > mx) mx = a
                i++
            }
        }
        val t = cur; cur = prev; prev = t
        energy = mx
        // refract
        for (y in 1 until s - 1) {
            var i = y * s + 1
            for (x in 1 until s - 1) {
                val dx = cur[i - 1] - cur[i + 1]
                val dy = cur[i - s] - cur[i + s]
                val sx = (x + (dx shr 4)).coerceIn(0, s - 1)
                val sy = (y + (dy shr 4)).coerceIn(0, s - 1)
                val c = src[sy * s + sx]
                val shade = (dx shr 3).coerceIn(-60, 60)
                val r = (((c shr 16) and 255) + shade).coerceIn(0, 255)
                val g = (((c shr 8) and 255) + shade).coerceIn(0, 255)
                val b = ((c and 255) + shade).coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i++
            }
        }
        // borders straight from the source
        for (k in 0 until s) { out[k] = src[k]; out[(s - 1) * s + k] = src[(s - 1) * s + k]; out[k * s] = src[k * s]; out[k * s + s - 1] = src[k * s + s - 1] }
        bmp.setPixels(out, 0, s, 0, 0, s, s)
    }

    override val busy get() = touching || energy > 2 || pending.isNotEmpty()

    override fun draw(c: Canvas) {
        dst.set(0f, 0f, w, h)
        c.drawBitmap(bmp, null, dst, bp)
    }

    override fun release() { cur.fill(0); prev.fill(0); energy = 0; pending.clear() }
}

/** A bright hole that swells the picture outward and bursts with light on release. */
internal class WhiteHoleFx(v: CoverEffectView) : Fx(v) {
    private val s = 150
    private val src = IntArray(s * s)
    private val out = IntArray(s * s)
    private val bmp: Bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    private var power = 0f
    private var burst = 0f
    private val dst = RectF()
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onArt() {
        val a = v.art ?: return
        val sc = Bitmap.createScaledBitmap(a, s, s, true)
        sc.getPixels(src, 0, s, 0, 0, s, s)
        if (sc != a) sc.recycle()
    }

    override fun up(x: Float, y: Float, vx: Float, vy: Float) { super.up(x, y, vx, vy); burst = 1f }

    override fun step(dt: Float) {
        power = if (touching) min(1f, power + dt * 1.6f) else max(0f, power - dt * 1.2f)
        burst = max(0f, burst - dt * 1.4f)
        if (power <= 0.01f) return
        val cx = tx / w * s; val cy = ty / h * s
        val rad = s * (0.28f + 0.22f * power)
        val k = 1f - 0.65f * power
        for (y in 0 until s) {
            for (x in 0 until s) {
                val dx = x - cx; val dy = y - cy
                val dist = sqrt(dx * dx + dy * dy)
                val i = y * s + x
                if (dist >= rad || dist < 0.001f) { out[i] = src[i]; continue }
                val f = dist / rad
                val m = f.pow(k) / f // magnify towards the centre
                val sx = (cx + dx * m).toInt().coerceIn(0, s - 1)
                val sy = (cy + dy * m).toInt().coerceIn(0, s - 1)
                val c = src[sy * s + sx]
                val glow = ((1f - f) * (1f - f) * 230f * power).toInt()
                val r = (((c shr 16) and 255) + glow).coerceAtMost(255)
                val g = (((c shr 8) and 255) + glow).coerceAtMost(255)
                val b = ((c and 255) + glow).coerceAtMost(255)
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(out, 0, s, 0, 0, s, s)
    }

    override val busy get() = touching || power > 0.01f || burst > 0f

    override fun draw(c: Canvas) {
        if (power > 0.01f) { dst.set(0f, 0f, w, h); c.drawBitmap(bmp, null, dst, bp) }
        v.drawBlob(c, tx, ty, (40 + 30 * power) * d, Color.WHITE, (220 * power).toInt(), paint)
        if (burst > 0f) {
            val rr = (1f - burst) * max(w, h)
            stroke.color = Color.WHITE; stroke.alpha = (200 * burst).toInt(); stroke.strokeWidth = 10 * d * burst + 1
            c.drawCircle(tx, ty, rr, stroke)
            v.drawBlob(c, tx, ty, rr * 0.6f, Color.WHITE, (120 * burst).toInt(), paint)
        }
    }
}

// ======================================================================= light effects

internal class LensFlareFx(v: CoverEffectView) : Fx(v) {
    private var a = 0f
    private val hex = Path()
    private val ghosts = floatArrayOf(0.35f, 0.62f, 0.9f, 1.25f, 1.6f, 2.05f)
    private val ghostSize = floatArrayOf(10f, 22f, 7f, 36f, 14f, 52f)
    private val ghostCol = intArrayOf(0xFF7FDBFF.toInt(), 0xFFB388FF.toInt(), 0xFFFFE082.toInt(), 0xFF80CBC4.toInt(), 0xFFFF8A80.toInt(), 0xFF82B1FF.toInt())

    init {
        for (k in 0 until 6) {
            val ang = Math.toRadians(60.0 * k + 30).toFloat()
            if (k == 0) hex.moveTo(cos(ang), sin(ang)) else hex.lineTo(cos(ang), sin(ang))
        }
        hex.close()
    }

    override fun step(dt: Float) { a = if (touching) min(1f, a + dt * 4f) else max(0f, a - dt * 1.8f) }
    override val busy get() = touching || a > 0f

    override fun draw(c: Canvas) {
        val cx = w / 2; val cy = h / 2
        val ax = cx - tx; val ay = cy - ty
        // star-burst streaks
        stroke.color = Color.WHITE; stroke.strokeWidth = 2 * d
        for (k in 0 until 6) {
            val ang = v.time * 0.4f + k * (Math.PI.toFloat() / 6f)
            val len = (if (k % 2 == 0) 90f else 55f) * d * a
            stroke.alpha = (110 * a).toInt()
            c.drawLine(tx - cos(ang) * len, ty - sin(ang) * len, tx + cos(ang) * len, ty + sin(ang) * len, stroke)
        }
        // anamorphic streak
        paint.color = 0xFF9FD8FF.toInt(); paint.alpha = (90 * a).toInt()
        v.tmpRect.set(tx - w * 0.6f, ty - 2.5f * d, tx + w * 0.6f, ty + 2.5f * d)
        c.drawOval(v.tmpRect, paint)
        v.drawBlob(c, tx, ty, 70 * d * a, 0xFFFFF3C4.toInt(), (230 * a).toInt(), paint)
        v.drawBlob(c, tx, ty, 26 * d * a, Color.WHITE, (255 * a).toInt(), paint)
        // rainbow halo
        stroke.strokeWidth = 3 * d
        val hues = intArrayOf(0xFFFF5252.toInt(), 0xFFFFD740.toInt(), 0xFF69F0AE.toInt(), 0xFF40C4FF.toInt(), 0xFFE040FB.toInt())
        for (k in hues.indices) {
            stroke.color = hues[k]; stroke.alpha = (55 * a).toInt()
            c.drawCircle(tx, ty, (95 + k * 3.5f) * d, stroke)
        }
        // ghosts along the axis through the centre
        for (k in ghosts.indices) {
            val gx = tx + ax * ghosts[k] * 2f
            val gy = ty + ay * ghosts[k] * 2f
            val sz = ghostSize[k] * d
            paint.color = ghostCol[k]; paint.alpha = (60 * a).toInt()
            if (k % 2 == 1) {
                c.save(); c.translate(gx, gy); c.scale(sz, sz); c.drawPath(hex, paint); c.restore()
            } else c.drawCircle(gx, gy, sz, paint)
        }
    }
}

internal class BrilliantRingFx(v: CoverEffectView) : Fx(v) {
    private var downX = 0f
    private var downY = 0f
    private var radius = 0f
    private var a = 0f
    private var expand = 0f
    private val sparks = Pool(80)

    override fun down(x: Float, y: Float) { super.down(x, y); downX = x; downY = y; radius = 30 * d; expand = 0f }
    override fun move(x: Float, y: Float) { super.move(x, y); radius = max(30 * d, hypot(x - downX, y - downY)) }
    override fun up(x: Float, y: Float, vx: Float, vy: Float) { super.up(x, y, vx, vy); expand = 1f }

    override fun step(dt: Float) {
        a = if (touching) min(1f, a + dt * 5f) else max(0f, a - dt * 1.6f)
        if (!touching && expand > 0f) radius += dt * 420 * d
        if (a > 0f) repeat(if (touching) 3 else 1) {
            val i = sparks.add()
            if (i >= 0) {
                val ang = r(0f, 6.283f)
                sparks.x[i] = downX + cos(ang) * radius; sparks.y[i] = downY + sin(ang) * radius
                sparks.vx[i] = cos(ang) * r(10f, 40f) * d; sparks.vy[i] = sin(ang) * r(10f, 40f) * d
                sparks.max[i] = r(0.4f, 0.9f); sparks.size[i] = r(1.5f, 4f) * d; sparks.col[i] = if (rnd.nextBoolean()) Color.WHITE else randPal()
            }
        }
        sparks.update(dt, 0f, 0f, 1.5f)
    }

    override val busy get() = touching || a > 0f || sparks.count > 0

    override fun draw(c: Canvas) {
        for (k in 0 until 3) {
            stroke.color = if (k == 1) Color.WHITE else pal(k); stroke.alpha = (180 * a / (k + 1)).toInt()
            stroke.strokeWidth = (6 - k * 2) * d
            c.drawCircle(downX, downY, radius + k * 3 * d, stroke)
        }
        for (i in 0 until sparks.count) {
            val f = fade(sparks.life[i], sparks.max[i]) * (0.6f + 0.4f * sin(v.time * 20 + i))
            v.drawBlob(c, sparks.x[i], sparks.y[i], sparks.size[i] * 2.5f, sparks.col[i], (255 * f).toInt(), paint)
        }
    }
}

internal class BrilliantCutFx(v: CoverEffectView) : Fx(v) {
    private var a = 0f
    private val facet = Path()
    private val n = 16

    override fun step(dt: Float) { a = if (touching) min(1f, a + dt * 4f) else max(0f, a - dt * 1.3f) }
    override val busy get() = touching || a > 0f

    override fun draw(c: Canvas) {
        val big = hypot(w, h)
        val hsv = FloatArray(3)
        for (k in 0 until n) {
            val a0 = k * 6.283f / n
            val a1 = (k + 1) * 6.283f / n
            facet.reset()
            facet.moveTo(tx, ty)
            facet.lineTo(tx + cos(a0) * big, ty + sin(a0) * big)
            facet.lineTo(tx + cos(a1) * big, ty + sin(a1) * big)
            facet.close()
            val shine = 0.5f + 0.5f * sin(a0 * 3f + v.time * 2.2f + tx / w * 6f)
            hsv[0] = (k * 360f / n + v.time * 40f) % 360f; hsv[1] = 0.35f; hsv[2] = 1f
            paint.color = if (k % 2 == 0) Color.WHITE else Color.HSVToColor(hsv)
            paint.alpha = (95 * shine * a).toInt()
            c.drawPath(facet, paint)
        }
        // inner facet ring (table of the diamond)
        stroke.color = Color.WHITE; stroke.strokeWidth = 1.2f * d; stroke.alpha = (130 * a).toInt()
        facet.reset()
        for (k in 0..8) {
            val ang = k * 6.283f / 8 + v.time * 0.3f
            val rr = 46 * d
            if (k == 0) facet.moveTo(tx + cos(ang) * rr, ty + sin(ang) * rr) else facet.lineTo(tx + cos(ang) * rr, ty + sin(ang) * rr)
        }
        c.drawPath(facet, stroke)
        for (k in 0 until 8) {
            val ang = k * 6.283f / 8 + v.time * 0.3f
            c.drawLine(tx + cos(ang) * 46 * d, ty + sin(ang) * 46 * d, tx + cos(ang) * big, ty + sin(ang) * big, stroke)
            val tw = 0.5f + 0.5f * sin(v.time * 9 + k * 1.7f)
            v.drawBlob(c, tx + cos(ang) * 46 * d, ty + sin(ang) * 46 * d, 7 * d * tw + 2 * d, Color.WHITE, (255 * a * tw).toInt(), paint)
        }
        v.drawBlob(c, tx, ty, 40 * d * a, Color.WHITE, (200 * a).toInt(), paint)
    }
}

// ======================================================================= colourful particle effects

internal class PoppingFx(v: CoverEffectView) : Fx(v) {
    private val p = Pool(90)
    override fun spawnSpacing() = 16f

    override fun onSpawnPoint(x: Float, y: Float, first: Boolean) {
        repeat(if (first) 5 else 1) {
            val i = p.add(); if (i < 0) return
            p.x[i] = x + r(-14f, 14f) * d; p.y[i] = y + r(-14f, 14f) * d
            p.max[i] = r(0.55f, 0.95f); p.size[i] = r(10f, 26f) * d; p.col[i] = randPal()
        }
    }

    override fun step(dt: Float) {
        p.update(dt, 0f, 0f, 0f) { i ->
            if (p.kind[i] == 0) { p.kind[i] = 1; p.life[i] = 0f; p.max[i] = 0.35f; true } else false
        }
    }

    override val busy get() = touching || p.count > 0

    override fun draw(c: Canvas) {
        for (i in 0 until p.count) {
            val t = p.life[i] / p.max[i]
            if (p.kind[i] == 0) {
                val sc = if (t < 0.25f) (t / 0.25f) * 1.15f else 1.15f - 0.15f * ((t - 0.25f) / 0.75f)
                paint.color = p.col[i]; paint.alpha = 225
                c.drawCircle(p.x[i], p.y[i], p.size[i] * sc, paint)
                paint.color = Color.WHITE; paint.alpha = 110
                c.drawCircle(p.x[i] - p.size[i] * 0.35f * sc, p.y[i] - p.size[i] * 0.35f * sc, p.size[i] * 0.22f * sc, paint)
            } else {
                stroke.color = p.col[i]; stroke.alpha = (255 * (1 - t)).toInt(); stroke.strokeWidth = 3 * d * (1 - t) + 0.5f
                c.drawCircle(p.x[i], p.y[i], p.size[i] * (1 + t * 0.9f), stroke)
                for (k in 0 until 6) {
                    val ang = k * 1.047f
                    val rr = p.size[i] * (1.2f + t * 1.4f)
                    paint.color = p.col[i]; paint.alpha = (230 * (1 - t)).toInt()
                    c.drawCircle(p.x[i] + cos(ang) * rr, p.y[i] + sin(ang) * rr, 2.4f * d * (1 - t), paint)
                }
            }
        }
    }
}

internal class TravellerFx(v: CoverEffectView) : Fx(v) {
    private val p = Pool(90)
    override fun spawnSpacing() = 14f

    override fun onSpawnPoint(x: Float, y: Float, first: Boolean) {
        repeat(if (first) 8 else 2) {
            val i = p.add(); if (i < 0) return
            val ang = r(0f, 6.283f); val sp = r(70f, 170f) * d
            p.x[i] = x; p.y[i] = y; p.vx[i] = cos(ang) * sp; p.vy[i] = sin(ang) * sp
            p.rot[i] = Math.toDegrees(ang.toDouble()).toFloat(); p.vr[i] = r(-90f, 90f)
            p.max[i] = r(0.9f, 1.6f); p.size[i] = r(8f, 22f) * d; p.col[i] = randPal()
        }
    }

    override fun step(dt: Float) = p.update(dt, 0f, 0f, 0.4f)
    override val busy get() = touching || p.count > 0

    override fun draw(c: Canvas) {
        for (i in 0 until p.count) {
            val f = fade(p.life[i], p.max[i])
            val s = p.size[i]
            c.save(); c.translate(p.x[i], p.y[i]); c.rotate(p.rot[i])
            paint.color = p.col[i]; paint.alpha = (220 * f).toInt()
            v.tmpRect.set(-s, -s * 0.55f, s, s * 0.55f)
            c.drawRoundRect(v.tmpRect, 3 * d, 3 * d, paint)
            stroke.color = Color.WHITE; stroke.alpha = (120 * f).toInt(); stroke.strokeWidth = 1.2f * d
            c.drawRoundRect(v.tmpRect, 3 * d, 3 * d, stroke)
            c.restore()
        }
    }
}

internal class BouncingFx(v: CoverEffectView) : Fx(v) {
    private val p = Pool(60)
    override fun spawnSpacing() = 22f

    override fun onSpawnPoint(x: Float, y: Float, first: Boolean) {
        repeat(if (first) 6 else 1) {
            val i = p.add(); if (i < 0) return
            p.x[i] = x; p.y[i] = y
            p.vx[i] = r(-220f, 220f) * d; p.vy[i] = r(-420f, -120f) * d
            p.max[i] = r(2.4f, 3.6f); p.size[i] = r(6f, 14f) * d; p.col[i] = randPal()
        }
    }

    override fun step(dt: Float) {
        p.update(dt, 0f, 900 * d, 0.15f)
        for (i in 0 until p.count) {
            val s = p.size[i]
            if (p.y[i] > h - s) { p.y[i] = h - s; p.vy[i] = -abs(p.vy[i]) * 0.72f; p.vx[i] *= 0.92f }
            if (p.x[i] < s) { p.x[i] = s; p.vx[i] = abs(p.vx[i]) * 0.8f }
            if (p.x[i] > w - s) { p.x[i] = w - s; p.vx[i] = -abs(p.vx[i]) * 0.8f }
        }
    }

    override val busy get() = touching || p.count > 0

    override fun draw(c: Canvas) {
        for (i in 0 until p.count) {
            val left = p.max[i] - p.life[i]
            val a = if (left < 0.5f) left / 0.5f else 1f
            // squash on impact
            val squash = (1f - min(0.35f, abs(p.vy[i]) / (2400 * d))).coerceIn(0.65f, 1f)
            paint.color = p.col[i]; paint.alpha = (240 * a).toInt()
            v.tmpRect.set(p.x[i] - p.size[i] / squash, p.y[i] - p.size[i] * squash, p.x[i] + p.size[i] / squash, p.y[i] + p.size[i] * squash)
            c.drawOval(v.tmpRect, paint)
            paint.color = Color.WHITE; paint.alpha = (120 * a).toInt()
            c.drawCircle(p.x[i] - p.size[i] * 0.35f, p.y[i] - p.size[i] * 0.4f, p.size[i] * 0.25f, paint)
        }
    }
}

internal class DropletFx(v: CoverEffectView, private val gyro: Boolean) : Fx(v), SensorEventListener {
    private val p = Pool(70)
    private val drop = Path()
    private var gx = 0f
    private var gy = 1f
    private var sm: SensorManager? = null

    init {
        drop.moveTo(0f, -1.4f)
        drop.cubicTo(0.55f, -0.5f, 1f, 0.1f, 1f, 0.45f)
        drop.cubicTo(1f, 1.05f, -1f, 1.05f, -1f, 0.45f)
        drop.cubicTo(-1f, 0.1f, -0.55f, -0.5f, 0f, -1.4f)
        drop.close()
    }

    override fun onAttach() { if (gyro) register() }

    private fun register() {
        if (sm != null) return
        val m = v.context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        m.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        sm = m
    }

    override fun release() { sm?.unregisterListener(this); sm = null }

    override fun onSensorChanged(e: SensorEvent) {
        gx = (-e.values[0] / 9.81f).coerceIn(-1f, 1f)
        gy = (e.values[1] / 9.81f).coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun spawnSpacing() = 18f

    override fun onSpawnPoint(x: Float, y: Float, first: Boolean) {
        if (gyro) register()
        repeat(if (first) 4 else 1) {
            val i = p.add(); if (i < 0) return
            p.x[i] = x + r(-10f, 10f) * d; p.y[i] = y + r(-6f, 6f) * d
            p.vx[i] = r(-30f, 30f) * d; p.vy[i] = r(-40f, 10f) * d
            p.max[i] = if (gyro) r(4f, 6f) else r(1.4f, 2.2f)
            p.size[i] = r(5f, 11f) * d; p.col[i] = randPal()
        }
    }

    override fun step(dt: Float) {
        val g = 700 * d
        p.update(dt, if (gyro) gx * g else 0f, if (gyro) gy * g else g, if (gyro) 1.2f else 0f) { i ->
            if (!gyro && p.kind[i] == 0) { p.kind[i] = 1; p.life[i] = 0f; p.max[i] = 0.5f; p.vx[i] = 0f; p.vy[i] = 0f; true } else false
        }
        for (i in 0 until p.count) {
            val s = p.size[i]
            if (p.kind[i] == 0 && p.y[i] > h - s) {
                if (gyro) { p.y[i] = h - s; p.vy[i] = -abs(p.vy[i]) * 0.3f }
                else { p.kind[i] = 1; p.life[i] = 0f; p.max[i] = 0.5f; p.y[i] = h - 2 * d; p.vx[i] = 0f; p.vy[i] = 0f }
            }
            if (gyro) {
                if (p.y[i] < s) { p.y[i] = s; p.vy[i] = abs(p.vy[i]) * 0.3f }
                if (p.x[i] < s) { p.x[i] = s; p.vx[i] = abs(p.vx[i]) * 0.3f }
                if (p.x[i] > w - s) { p.x[i] = w - s; p.vx[i] = -abs(p.vx[i]) * 0.3f }
            }
        }
    }

    override val busy get() = touching || p.count > 0

    override fun draw(c: Canvas) {
        for (i in 0 until p.count) {
            val s = p.size[i]
            if (p.kind[i] == 1) {
                val t = p.life[i] / p.max[i]
                paint.color = p.col[i]; paint.alpha = (200 * (1 - t)).toInt()
                v.tmpRect.set(p.x[i] - s * (1 + t * 2), p.y[i] - s * 0.3f, p.x[i] + s * (1 + t * 2), p.y[i] + s * 0.3f)
                c.drawOval(v.tmpRect, paint)
                continue
            }
            val left = p.max[i] - p.life[i]
            val a = if (left < 0.6f) left / 0.6f else 1f
            c.save(); c.translate(p.x[i], p.y[i]); c.scale(s, s)
            paint.color = p.col[i]; paint.alpha = (215 * a).toInt()
            c.drawPath(drop, paint)
            paint.color = Color.WHITE; paint.alpha = (150 * a).toInt()
            c.drawCircle(-0.35f, 0.15f, 0.22f, paint)
            c.restore()
        }
    }
}

internal class BubblesFx(v: CoverEffectView, private val soda: Boolean) : Fx(v) {
    private val p = Pool(if (soda) 160 else 70)
    override fun spawnSpacing() = if (soda) 6f else 16f

    override fun onSpawnPoint(x: Float, y: Float, first: Boolean) {
        repeat(if (soda) (if (first) 18 else 4) else (if (first) 5 else 1)) {
            val i = p.add(); if (i < 0) return
            p.x[i] = x + r(-18f, 18f) * d; p.y[i] = y + r(-10f, 10f) * d
            if (soda) {
                p.vy[i] = -r(90f, 220f) * d; p.max[i] = r(0.7f, 1.4f); p.size[i] = r(1f, 3.2f) * d
            } else {
                p.vy[i] = -r(40f, 90f) * d; p.max[i] = r(1.4f, 2.4f); p.size[i] = r(7f, 18f) * d
            }
            p.rot[i] = r(0f, 6.28f)
            p.col[i] = randPal()
        }
    }

    override fun step(dt: Float) {
        if (soda && touching) repeat(2) {
            val i = p.add()
            if (i >= 0) {
                p.x[i] = r(0f, w); p.y[i] = h; p.vy[i] = -r(120f, 260f) * d
                p.max[i] = r(0.8f, 1.5f); p.size[i] = r(0.8f, 2.4f) * d; p.rot[i] = r(0f, 6.28f); p.col[i] = Color.WHITE
            }
        }
        p.update(dt, 0f, if (soda) -60 * d else -10 * d, 0f) { i ->
            if (!soda && p.kind[i] == 0) { p.kind[i] = 1; p.life[i] = 0f; p.max[i] = 0.35f; p.vy[i] = 0f; true } else false
        }
    }

    override val busy get() = touching || p.count > 0

    override fun draw(c: Canvas) {
        for (i in 0 until p.count) {
            val sway = sin(v.time * (if (soda) 9f else 2.5f) + p.rot[i]) * (if (soda) 2f else 6f) * d
            val x = p.x[i] + sway; val y = p.y[i]; val s = p.size[i]
            val f = fade(p.life[i], p.max[i])
            if (soda) {
                paint.color = Color.WHITE; paint.alpha = (200 * f).toInt()
                c.drawCircle(x, y, s, paint)
                continue
            }
            if (p.kind[i] == 1) {
                val t = p.life[i] / p.max[i]
                for (k in 0 until 5) {
                    val ang = k * 1.256f + p.rot[i]
                    val rr = s * (1f + t * 1.6f)
                    v.drawBlob(c, x + cos(ang) * rr, y + sin(ang) * rr, 4 * d * (1 - t) + 1, Color.WHITE, (255 * (1 - t)).toInt(), paint)
                }
                continue
            }
            paint.color = p.col[i]; paint.alpha = (45 * f).toInt()
            c.drawCircle(x, y, s, paint)
            stroke.color = ColorUtils.blendARGB(p.col[i], Color.WHITE, 0.5f); stroke.alpha = (220 * f).toInt(); stroke.strokeWidth = 1.4f * d
            c.drawCircle(x, y, s, stroke)
            stroke.color = Color.WHITE; stroke.alpha = (170 * f).toInt(); stroke.strokeWidth = 1.6f * d
            v.tmpRect.set(x - s * 0.7f, y - s * 0.7f, x + s * 0.7f, y + s * 0.7f)
            c.drawArc(v.tmpRect, 200f, 60f, false, stroke)
            val tw = 0.5f + 0.5f * sin(v.time * 12 + p.rot[i] * 5)
            v.drawBlob(c, x + s * 0.5f, y - s * 0.55f, 3 * d * tw + 1, Color.WHITE, (255 * f * tw).toInt(), paint)
        }
    }
}

internal class HulaFx(v: CoverEffectView, private val v2: Boolean) : Fx(v) {
    private val n = 4
    private val rx = FloatArray(n); private val ry = FloatArray(n); private val rad = FloatArray(n)
    private val tilt = FloatArray(n); private val ph = FloatArray(n); private val alpha = FloatArray(n)
    private var count = 0
    private var hoopX = 0f
    private var hoopY = 0f

    override fun down(x: Float, y: Float) {
        super.down(x, y)
        hoopX = x; hoopY = y
        if (count < n) { val i = count++; rad[i] = 0f; tilt[i] = r(-40f, 40f); ph[i] = r(0f, 6.28f); alpha[i] = 1f }
        else { rad[0] = 0f; alpha[0] = 1f }
    }

    override fun step(dt: Float) {
        hoopX += (tx - hoopX) * min(1f, dt * 8f); hoopY += (ty - hoopY) * min(1f, dt * 8f)
        var i = 0
        while (i < count) {
            ph[i] += dt * (if (v2) 7f else 5f)
            if (touching && i == count - 1) { rad[i] += (60 * d - rad[i]) * min(1f, dt * 6f); rx[i] = hoopX; ry[i] = hoopY }
            else { rad[i] += dt * 160 * d; alpha[i] -= dt * 0.7f }
            if (alpha[i] <= 0f) {
                for (k in i until count - 1) { rx[k] = rx[k + 1]; ry[k] = ry[k + 1]; rad[k] = rad[k + 1]; tilt[k] = tilt[k + 1]; ph[k] = ph[k + 1]; alpha[k] = alpha[k + 1] }
                count--; continue
            }
            i++
        }
    }

    override val busy get() = touching || count > 0

    override fun draw(c: Canvas) {
        for (i in 0 until count) {
            val wob = cos(ph[i])
            c.save(); c.translate(rx[i], ry[i]); c.rotate(tilt[i] + sin(ph[i] * 0.5f) * 20f)
            val rr = rad[i]
            v.tmpRect.set(-rr, -rr * (0.25f + 0.2f * abs(wob)), rr, rr * (0.25f + 0.2f * abs(wob)))
            if (v2) {
                // neon dashed hoop
                val segs = 18
                for (k in 0 until segs) {
                    stroke.color = pal(k); stroke.alpha = (255 * alpha[i]).toInt(); stroke.strokeWidth = 4 * d
                    c.drawArc(v.tmpRect, k * 20f + Math.toDegrees(ph[i].toDouble()).toFloat(), 12f, false, stroke)
                }
            } else {
                for (k in 0 until 3) {
                    stroke.color = pal(i + k); stroke.alpha = (200 * alpha[i]).toInt(); stroke.strokeWidth = (5 - k * 1.5f) * d
                    c.drawOval(v.tmpRect, stroke)
                    v.tmpRect.inset(-3 * d, -1.5f * d)
                }
            }
            c.restore()
        }
    }
}

internal class ParticleFx(v: CoverEffectView, private val light: Boolean) : Fx(v) {
    private val n = if (light) 90 else 140
    private val x = FloatArray(n); private val y = FloatArray(n); private val vx = FloatArray(n); private val vy = FloatArray(n)
    private val px = FloatArray(n); private val py = FloatArray(n); private val col = IntArray(n); private val sz = FloatArray(n)
    private var a = 0f
    private var inited = false
    private var burst = false

    private fun setup() {
        for (i in 0 until n) {
            x[i] = r(0f, w); y[i] = r(0f, h); vx[i] = r(-15f, 15f) * d; vy[i] = r(-15f, 15f) * d
            px[i] = x[i]; py[i] = y[i]; col[i] = if (light) (if (i % 3 == 0) Color.WHITE else pal(i)) else pal(i)
            sz[i] = r(1.4f, if (light) 3f else 3.6f) * d
        }
        inited = true
    }

    override fun onArt() { if (inited) for (i in 0 until n) col[i] = if (light && i % 3 == 0) Color.WHITE else pal(i) }
    override fun down(x: Float, y: Float) { super.down(x, y); if (!inited && w > 0) setup() }
    override fun up(x: Float, y: Float, vx: Float, vy: Float) { super.up(x, y, vx, vy); burst = true }

    override fun step(dt: Float) {
        if (!inited) return
        a = if (touching) min(1f, a + dt * 3f) else max(0f, a - dt * 0.6f)
        for (i in 0 until n) {
            px[i] = x[i]; py[i] = y[i]
            val dx = tx - x[i]; val dy = ty - y[i]
            val dist = max(12 * d, hypot(dx, dy))
            if (touching) {
                val f = 2600 * d * d / dist
                vx[i] += dx / dist * f * dt; vy[i] += dy / dist * f * dt
                // swirl
                vx[i] += -dy / dist * 300 * d * dt; vy[i] += dx / dist * 300 * d * dt
            } else if (burst) {
                val f = 900 * d
                vx[i] -= dx / dist * f * (0.5f + rnd.nextFloat()); vy[i] -= dy / dist * f * (0.5f + rnd.nextFloat())
            }
            vx[i] *= 1f - 1.6f * dt; vy[i] *= 1f - 1.6f * dt
            x[i] += vx[i] * dt; y[i] += vy[i] * dt
            if (x[i] < 0) { x[i] = 0f; vx[i] = abs(vx[i]) } else if (x[i] > w) { x[i] = w; vx[i] = -abs(vx[i]) }
            if (y[i] < 0) { y[i] = 0f; vy[i] = abs(vy[i]) } else if (y[i] > h) { y[i] = h; vy[i] = -abs(vy[i]) }
        }
        burst = false
    }

    override val busy get() = touching || a > 0f

    override fun draw(c: Canvas) {
        if (!inited) return
        for (i in 0 until n) {
            if (light) {
                stroke.color = col[i]; stroke.alpha = (140 * a).toInt(); stroke.strokeWidth = sz[i]
                c.drawLine(px[i] - (x[i] - px[i]) * 3, py[i] - (y[i] - py[i]) * 3, x[i], y[i], stroke)
                v.drawBlob(c, x[i], y[i], sz[i] * 3f, col[i], (255 * a).toInt(), paint)
            } else {
                paint.color = col[i]; paint.alpha = (230 * a).toInt()
                c.drawCircle(x[i], y[i], sz[i], paint)
            }
        }
    }
}

internal class CrystalFx(v: CoverEffectView) : Fx(v) {
    private val p = Pool(80)
    private val kite = Path()
    private val half = Path()

    init {
        kite.moveTo(0f, -1.6f); kite.lineTo(0.45f, -0.2f); kite.lineTo(0f, 1f); kite.lineTo(-0.45f, -0.2f); kite.close()
        half.moveTo(0f, -1.6f); half.lineTo(0.45f, -0.2f); half.lineTo(0f, 1f); half.close()
    }

    override fun spawnSpacing() = 15f

    override fun onSpawnPoint(x: Float, y: Float, first: Boolean) {
        repeat(if (first) 9 else 2) {
            val i = p.add(); if (i < 0) return
            val ang = r(0f, 6.283f); val sp = r(60f, 190f) * d
            p.x[i] = x; p.y[i] = y; p.vx[i] = cos(ang) * sp; p.vy[i] = sin(ang) * sp
            p.rot[i] = Math.toDegrees(ang.toDouble()).toFloat() + 90f; p.vr[i] = r(-120f, 120f)
            p.max[i] = r(0.8f, 1.5f); p.size[i] = r(7f, 18f) * d; p.col[i] = randPal()
        }
    }

    override fun step(dt: Float) = p.update(dt, 0f, 120 * d, 0.9f)
    override val busy get() = touching || p.count > 0

    override fun draw(c: Canvas) {
        for (i in 0 until p.count) {
            val f = fade(p.life[i], p.max[i])
            c.save(); c.translate(p.x[i], p.y[i]); c.rotate(p.rot[i]); c.scale(p.size[i], p.size[i])
            paint.color = p.col[i]; paint.alpha = (200 * f).toInt()
            c.drawPath(kite, paint)
            paint.color = Color.WHITE; paint.alpha = (110 * f).toInt()
            c.drawPath(half, paint)
            c.restore()
        }
    }
}

internal class VectorFx(v: CoverEffectView) : Fx(v) {
    private var a = 0f
    private val g = 13

    override fun step(dt: Float) { a = if (touching) min(1f, a + dt * 4f) else max(0f, a - dt * 1.5f) }
    override val busy get() = touching || a > 0f

    override fun draw(c: Canvas) {
        val cw = w / g; val ch = h / g
        val rad = max(w, h) * 0.45f
        stroke.strokeWidth = 2.2f * d
        for (j in 0 until g) for (i in 0 until g) {
            val x = (i + 0.5f) * cw; val y = (j + 0.5f) * ch
            val dx = tx - x; val dy = ty - y
            val dist = hypot(dx, dy).coerceAtLeast(1f)
            val f = exp(-dist / rad) * a
            val len = min(cw, ch) * 0.9f * f
            stroke.color = pal(i + j); stroke.alpha = (230 * f).toInt()
            c.drawLine(x, y, x + dx / dist * len, y + dy / dist * len, stroke)
            paint.color = Color.WHITE; paint.alpha = (180 * f).toInt()
            c.drawCircle(x, y, 1.6f * d, paint)
        }
    }
}

// ======================================================================= ink & watercolour

internal class InkFx(v: CoverEffectView, private val watercolor: Boolean) : Fx(v) {
    private val p = Pool(110)
    private val indigo = intArrayOf(0xFF1A237E.toInt(), 0xFF283593.toInt(), 0xFF3F1E8C.toInt(), 0xFF4527A0.toInt(), 0xFF0D47A1.toInt())
    override fun spawnSpacing() = 12f

    override fun onSpawnPoint(x: Float, y: Float, first: Boolean) {
        repeat(if (first) 4 else 2) { k ->
            val i = p.add(); if (i < 0) return
            p.x[i] = x + r(-6f, 6f) * d; p.y[i] = y + r(-6f, 6f) * d
            val ang = r(0f, 6.28f); val sp = r(4f, 22f) * d
            p.vx[i] = cos(ang) * sp; p.vy[i] = sin(ang) * sp + (if (watercolor) 0f else 8 * d)
            p.max[i] = r(2.2f, 3.6f); p.size[i] = r(26f, 64f) * d
            p.col[i] = if (watercolor) randPal() else indigo[rnd.nextInt(indigo.size)]
            p.kind[i] = if (watercolor && k == 0 && first) 1 else 0
        }
    }

    override fun step(dt: Float) = p.update(dt, 0f, if (watercolor) 0f else 6 * d, 0.3f)
    override val busy get() = touching || p.count > 0

    override fun draw(c: Canvas) {
        for (i in 0 until p.count) {
            val t = p.life[i] / p.max[i]
            val f = fade(p.life[i], p.max[i])
            val grow = 0.25f + 0.75f * (1f - (1f - t) * (1f - t))
            val rr = p.size[i] * grow
            if (p.kind[i] == 1) {
                // ripple ink: rings spreading from the first touch
                for (k in 0 until 3) {
                    val rt = ((t * 1.5f) + k * 0.25f) % 1f
                    stroke.color = p.col[i]; stroke.alpha = (180 * (1 - rt) * f).toInt(); stroke.strokeWidth = 2 * d
                    c.drawCircle(p.x[i], p.y[i], rt * w * 0.5f, stroke)
                }
                continue
            }
            val curlX = sin(v.time * 0.8f + i) * 4 * d * t
            v.drawBlob(c, p.x[i] + curlX, p.y[i], rr, p.col[i], ((if (watercolor) 150 else 170) * f).toInt(), paint)
            if (watercolor) {
                // pigment gathers at the edge of a watercolour blot
                stroke.color = ColorUtils.blendARGB(p.col[i], Color.BLACK, 0.25f); stroke.alpha = (90 * f).toInt()
                stroke.strokeWidth = 1.6f * d
                c.drawCircle(p.x[i] + curlX, p.y[i], rr * 0.46f, stroke)
                c.drawCircle(p.x[i] + curlX + 2 * d, p.y[i] - 1.5f * d, rr * 0.44f, stroke)
            } else {
                // darker core with a tendril
                v.drawBlob(c, p.x[i] + curlX * 2, p.y[i] + rr * 0.15f, rr * 0.45f, 0xFF0B0B3B.toInt(), (120 * f).toInt(), paint)
            }
        }
    }
}

// ======================================================================= picture-slicing effects

/** Abstract Tiles (squares) / Geometric Mosaic (triangles): pieces of the cover lift and turn near the finger. */
internal class TilesFx(v: CoverEffectView, private val triangles: Boolean) : Fx(v) {
    private val g = 6
    private var paths = arrayOf<Path>()
    private var cx = FloatArray(0); private var cy = FloatArray(0)
    private var lift = FloatArray(0); private var dir = FloatArray(0)

    override fun onSize() {
        val cw = w / g; val ch = h / g
        val list = ArrayList<Path>(); val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (j in 0 until g) for (i in 0 until g) {
            val x0 = i * cw; val y0 = j * ch; val x1 = x0 + cw; val y1 = y0 + ch
            if (triangles) {
                val flip = (i + j) % 2 == 0
                val p1 = Path(); val p2 = Path()
                if (flip) {
                    p1.moveTo(x0, y0); p1.lineTo(x1, y0); p1.lineTo(x0, y1); p1.close()
                    p2.moveTo(x1, y0); p2.lineTo(x1, y1); p2.lineTo(x0, y1); p2.close()
                    list += p1; xs += (x0 * 2 + x1) / 3; ys += (y0 * 2 + y1) / 3
                    list += p2; xs += (x1 * 2 + x0) / 3; ys += (y1 * 2 + y0) / 3
                } else {
                    p1.moveTo(x0, y0); p1.lineTo(x1, y1); p1.lineTo(x0, y1); p1.close()
                    p2.moveTo(x0, y0); p2.lineTo(x1, y0); p2.lineTo(x1, y1); p2.close()
                    list += p1; xs += (x0 * 2 + x1) / 3; ys += (y1 * 2 + y0) / 3
                    list += p2; xs += (x1 * 2 + x0) / 3; ys += (y0 * 2 + y1) / 3
                }
            } else {
                val p = Path(); p.addRect(x0, y0, x1, y1, Path.Direction.CW)
                list += p; xs += (x0 + x1) / 2; ys += (y0 + y1) / 2
            }
        }
        paths = list.toTypedArray(); cx = xs.toFloatArray(); cy = ys.toFloatArray()
        lift = FloatArray(paths.size); dir = FloatArray(paths.size) { r(-1f, 1f) }
    }

    override fun step(dt: Float) {
        val rad = max(w, h) * 0.32f
        for (i in lift.indices) {
            val target = if (touching) (1f - hypot(cx[i] - tx, cy[i] - ty) / rad).coerceIn(0f, 1f) else 0f
            lift[i] += (target - lift[i]) * min(1f, dt * (if (touching) 10f else 5f))
        }
    }

    override val busy get() = touching || lift.any { it > 0.005f }

    override fun draw(c: Canvas) {
        paint.color = Color.BLACK; paint.alpha = 255
        c.drawRect(0f, 0f, w, h, paint)
        for (i in paths.indices) {
            val l = lift[i]
            c.save()
            if (l > 0.003f) {
                val ox = (cx[i] - tx); val oy = (cy[i] - ty)
                val len = hypot(ox, oy).coerceAtLeast(1f)
                c.translate(ox / len * 10 * d * l, oy / len * 10 * d * l)
                c.rotate(dir[i] * 28f * l, cx[i], cy[i])
                val s = 1f - 0.18f * l
                c.scale(s, s, cx[i], cy[i])
            }
            c.drawPath(paths[i], v.artPaint)
            if (l > 0.003f) {
                stroke.color = Color.WHITE; stroke.alpha = (170 * l).toInt(); stroke.strokeWidth = 1.5f * d
                c.drawPath(paths[i], stroke)
                paint.color = Color.WHITE; paint.alpha = (60 * l).toInt()
                c.drawPath(paths[i], paint)
            }
            c.restore()
        }
    }
}

/** Blind (horizontal slats that turn) and Revolving Glass (vertical glass strips that spin). */
internal class StripsFx(v: CoverEffectView, private val vertical: Boolean) : Fx(v) {
    private val n = if (vertical) 9 else 12
    private val ang = FloatArray(n)
    private val vel = FloatArray(n)
    private var sweep = 0f

    override fun up(x: Float, y: Float, vx: Float, vy: Float) {
        super.up(x, y, vx, vy)
        if (vertical) for (i in 0 until n) vel[i] += vx / 6f * (1f - abs((i + 0.5f) / n - tx / w))
    }

    override fun step(dt: Float) {
        sweep += dt
        for (i in 0 until n) {
            val pos = if (vertical) (i + 0.5f) / n * w else (i + 0.5f) / n * h
            val fpos = if (vertical) tx else ty
            val span = if (vertical) w else h
            val near = (1f - abs(pos - fpos) / (span * 0.35f)).coerceIn(0f, 1f)
            if (vertical) {
                if (touching) vel[i] += near * 900f * dt
                vel[i] *= 1f - 1.8f * dt
                ang[i] += vel[i] * dt
                // settle on the nearest face
                if (!touching && abs(vel[i]) < 40f) {
                    val target = Math.round(ang[i] / 180f) * 180f
                    ang[i] += (target - ang[i]) * min(1f, dt * 6f)
                }
            } else {
                val target = if (touching) 80f * near else 0f
                val k = 120f; val damp = 14f
                vel[i] += ((target - ang[i]) * k - vel[i] * damp) * dt
                ang[i] += vel[i] * dt
            }
        }
    }

    override val busy get() = touching || (0 until n).any {
        val m = abs(ang[it] % 180f)
        abs(vel[it]) > 1f || min(m, 180f - m) > 0.5f
    }

    override fun draw(c: Canvas) {
        paint.color = Color.BLACK; paint.alpha = 255
        c.drawRect(0f, 0f, w, h, paint)
        for (i in 0 until n) {
            val a = Math.toRadians(ang[i].toDouble())
            val cs = cos(a).toFloat()
            val sn = abs(sin(a).toFloat())
            c.save()
            if (vertical) {
                val x0 = i * w / n; val x1 = (i + 1) * w / n
                c.clipRect(x0 - w / n, 0f, x1 + w / n, h)
                c.scale(cs, 1f, (x0 + x1) / 2, h / 2)
                c.drawRect(x0, 0f, x1, h, v.artPaint)
                paint.color = Color.BLACK; paint.alpha = (150 * sn + (if (cs < 0) 60 else 0)).toInt().coerceAtMost(255)
                c.drawRect(x0, 0f, x1, h, paint)
                // glass sheen
                paint.color = Color.WHITE; paint.alpha = (90 * sn).toInt()
                c.drawRect(x0, 0f, x0 + (x1 - x0) * 0.25f, h, paint)
            } else {
                val y0 = i * h / n; val y1 = (i + 1) * h / n
                c.scale(1f, cs, w / 2, (y0 + y1) / 2)
                c.drawRect(0f, y0, w, y1, v.artPaint)
                paint.color = Color.BLACK; paint.alpha = (170 * sn).toInt()
                c.drawRect(0f, y0, w, y1, paint)
                paint.color = Color.WHITE; paint.alpha = (80 * sn).toInt()
                c.drawRect(0f, y0, w, y0 + 1.5f * d, paint)
            }
            c.restore()
        }
    }
}

/** A water drop that magnifies the cover under your finger. */
internal class DewdropFx(v: CoverEffectView) : Fx(v) {
    private var a = 0f
    private var dx = 0f
    private var dy = 0f
    private var vy = 0f
    private var squash = 0f
    private val lens = Path()

    override fun down(x: Float, y: Float) { super.down(x, y); dx = x; dy = y; vy = 0f }
    override fun move(x: Float, y: Float) { squash = (hypot(x - tx, y - ty) / (20 * d)).coerceAtMost(0.25f); super.move(x, y) }

    override fun step(dt: Float) {
        a = if (touching) min(1f, a + dt * 6f) else max(0f, a - dt * 1.1f)
        if (touching) { dx += (tx - dx) * min(1f, dt * 14f); dy += (ty - dy) * min(1f, dt * 14f) }
        else { vy += 900 * d * dt; dy += vy * dt }
        squash *= 1f - min(1f, dt * 6f)
    }

    override val busy get() = touching || a > 0f

    override fun draw(c: Canvas) {
        val rad = 52 * d * (0.6f + 0.4f * a)
        c.save()
        lens.reset()
        v.tmpRect.set(dx - rad * (1 + squash), dy - rad * (1 - squash), dx + rad * (1 + squash), dy + rad * (1 - squash))
        lens.addOval(v.tmpRect, Path.Direction.CW)
        c.clipPath(lens)
        c.scale(1.75f, 1.75f, dx, dy)
        v.drawArt(c)
        c.restore()
        // inner shadow, rim and highlight
        stroke.color = Color.BLACK; stroke.alpha = (70 * a).toInt(); stroke.strokeWidth = 5 * d
        c.drawOval(v.tmpRect, stroke)
        stroke.color = Color.WHITE; stroke.alpha = (140 * a).toInt(); stroke.strokeWidth = 1.5f * d
        c.drawOval(v.tmpRect, stroke)
        paint.color = Color.WHITE; paint.alpha = (170 * a).toInt()
        val hl = RectF(dx - rad * 0.55f, dy - rad * 0.72f, dx - rad * 0.05f, dy - rad * 0.42f)
        c.drawOval(hl, paint)
    }
}

/** Pixelate and Circle Mosaic: the cover breaks into blocks / dots around your finger. */
internal class GridFx(v: CoverEffectView, private val circles: Boolean) : Fx(v) {
    private val g = if (circles) 18 else 26
    private val colors = IntArray(g * g)
    private val heat = FloatArray(g * g)

    override fun onArt() {
        val a = v.art ?: return
        val sc = Bitmap.createScaledBitmap(a, g, g, true)
        sc.getPixels(colors, 0, g, 0, 0, g, g)
        if (sc != a) sc.recycle()
    }

    override fun step(dt: Float) {
        val cw = w / g; val ch = h / g
        val rad = max(w, h) * (if (circles) 0.33f else 0.27f)
        for (j in 0 until g) for (i in 0 until g) {
            val k = j * g + i
            if (touching) {
                val near = (1f - hypot((i + 0.5f) * cw - tx, (j + 0.5f) * ch - ty) / rad).coerceIn(0f, 1f)
                if (near > heat[k]) heat[k] = min(1f, heat[k] + dt * 8f * near + 0.02f)
            }
            if (!touching || heat[k] > 0f) heat[k] = max(0f, heat[k] - dt * (if (touching) 0.25f else 0.9f))
        }
    }

    override val busy get() = touching || heat.any { it > 0.01f }

    override fun draw(c: Canvas) {
        val cw = w / g; val ch = h / g
        for (j in 0 until g) for (i in 0 until g) {
            val k = j * g + i
            val t = heat[k]
            if (t < 0.01f) continue
            val x0 = i * cw; val y0 = j * ch
            if (circles) {
                paint.color = Color.BLACK; paint.alpha = (230 * t).toInt()
                c.drawRect(x0, y0, x0 + cw, y0 + ch, paint)
                paint.color = colors[k]; paint.alpha = (255 * t).toInt()
                val pulse = 0.82f + 0.18f * sin(v.time * 4f + i * 0.5f + j * 0.3f)
                c.drawCircle(x0 + cw / 2, y0 + ch / 2, min(cw, ch) / 2 * pulse * (0.5f + 0.5f * t), paint)
            } else {
                paint.color = colors[k]; paint.alpha = (255 * t).toInt()
                c.drawRect(x0, y0, x0 + cw + 0.5f, y0 + ch + 0.5f, paint)
                stroke.color = Color.BLACK; stroke.alpha = (40 * t).toInt(); stroke.strokeWidth = 1f
                c.drawRect(x0, y0, x0 + cw, y0 + ch, stroke)
            }
        }
    }
}

// ======================================================================= seasons

internal class SeasonFx(v: CoverEffectView, private val season: String) : Fx(v) {
    private val p = Pool(110)
    private val petal = Path()
    private val leaf = Path()
    private var glow = 0f
    private var ambAcc = 0f
    override val hasAmbient = true

    init {
        petal.moveTo(0f, -1f); petal.cubicTo(0.8f, -0.6f, 0.7f, 0.6f, 0f, 1f); petal.cubicTo(-0.7f, 0.6f, -0.8f, -0.6f, 0f, -1f); petal.close()
        // simple maple-like leaf
        leaf.moveTo(0f, -1f)
        leaf.lineTo(0.25f, -0.45f); leaf.lineTo(0.75f, -0.6f); leaf.lineTo(0.55f, -0.1f); leaf.lineTo(1f, 0.1f)
        leaf.lineTo(0.45f, 0.35f); leaf.lineTo(0.5f, 0.75f); leaf.lineTo(0.05f, 0.55f); leaf.lineTo(0f, 1.1f)
        leaf.lineTo(-0.05f, 0.55f); leaf.lineTo(-0.5f, 0.75f); leaf.lineTo(-0.45f, 0.35f); leaf.lineTo(-1f, 0.1f)
        leaf.lineTo(-0.55f, -0.1f); leaf.lineTo(-0.75f, -0.6f); leaf.lineTo(-0.25f, -0.45f); leaf.close()
    }

    private fun colorFor(): Int = when (season) {
        "spring" -> intArrayOf(0xFFFFB7C5.toInt(), 0xFFFFD1DC.toInt(), 0xFFFFFFFF.toInt(), 0xFFF8A5C2.toInt())[rnd.nextInt(4)]
        "summer" -> intArrayOf(0xFFFFE082.toInt(), 0xFFFFF59D.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFCC80.toInt())[rnd.nextInt(4)]
        "autumn" -> intArrayOf(0xFFE65100.toInt(), 0xFFFF8F00.toInt(), 0xFFC62828.toInt(), 0xFF8D6E63.toInt(), 0xFFFFB300.toInt())[rnd.nextInt(5)]
        else -> Color.WHITE
    }

    private fun spawn(x: Float, y: Float, fromTop: Boolean) {
        val i = p.add(); if (i < 0) return
        p.x[i] = x; p.y[i] = y
        p.col[i] = colorFor(); p.rot[i] = r(0f, 360f); p.vr[i] = r(-120f, 120f)
        when (season) {
            "summer" -> { p.vx[i] = r(-20f, 20f) * d; p.vy[i] = -r(10f, 40f) * d; p.max[i] = r(1.2f, 2.4f); p.size[i] = r(1.5f, 3.5f) * d }
            "winter" -> { p.vx[i] = r(-10f, 10f) * d; p.vy[i] = r(20f, 50f) * d; p.max[i] = if (fromTop) h / (35 * d) + 1f else r(2f, 3f); p.size[i] = r(3f, 7f) * d }
            "autumn" -> { p.vx[i] = r(-20f, 20f) * d; p.vy[i] = r(30f, 60f) * d; p.max[i] = if (fromTop) h / (40 * d) + 1f else r(2f, 3f); p.size[i] = r(6f, 11f) * d }
            else -> { p.vx[i] = r(-25f, 5f) * d; p.vy[i] = r(18f, 40f) * d; p.max[i] = if (fromTop) h / (28 * d) + 1f else r(2f, 3f); p.size[i] = r(4f, 7.5f) * d }
        }
    }

    override fun spawnSpacing() = 18f
    override fun onSpawnPoint(x: Float, y: Float, first: Boolean) {
        repeat(if (first) 6 else 2) { spawn(x + r(-12f, 12f) * d, y + r(-12f, 12f) * d, false) }
    }

    override fun ambient(dt: Float) {
        ambAcc += dt * (if (season == "summer") 4f else 1.4f)
        while (ambAcc >= 1f) {
            ambAcc -= 1f
            if (season == "summer") spawn(r(0f, w), r(h * 0.3f, h), false) else spawn(r(0f, w), -10 * d, true)
        }
    }

    override fun step(dt: Float) {
        glow = if (touching) min(1f, glow + dt * 3f) else max(0f, glow - dt * 1.2f)
        p.update(dt, 0f, if (season == "summer") -5 * d else 0f, 0f)
    }

    override val busy get() = touching || p.count > 0 || glow > 0f

    override fun draw(c: Canvas) {
        if (season == "summer" && glow > 0f) {
            // sun with slowly turning rays
            c.save(); c.translate(tx, ty); c.rotate(v.time * 12f)
            paint.color = 0xFFFFD54F.toInt()
            for (k in 0 until 12) {
                paint.alpha = (60 * glow).toInt()
                c.drawRect(-2.5f * d, 30 * d, 2.5f * d, (110 + 20 * sin(v.time * 3 + k)) * d, paint)
                c.rotate(30f)
            }
            c.restore()
            v.drawBlob(c, tx, ty, 80 * d * glow, 0xFFFFE082.toInt(), (220 * glow).toInt(), paint)
            v.drawBlob(c, tx, ty, 30 * d * glow, Color.WHITE, (255 * glow).toInt(), paint)
        }
        if (season == "winter" && glow > 0f) {
            // frost creeping in from the corners
            val fr = w * 0.55f * glow
            v.drawBlob(c, 0f, 0f, fr, 0xFFE3F2FD.toInt(), (170 * glow).toInt(), paint)
            v.drawBlob(c, w, 0f, fr, 0xFFE3F2FD.toInt(), (170 * glow).toInt(), paint)
            v.drawBlob(c, 0f, h, fr, 0xFFE3F2FD.toInt(), (170 * glow).toInt(), paint)
            v.drawBlob(c, w, h, fr, 0xFFE3F2FD.toInt(), (170 * glow).toInt(), paint)
        }
        for (i in 0 until p.count) {
            val f = fade(p.life[i], p.max[i])
            val sway = sin(v.time * 1.8f + p.rot[i] * 0.05f) * 10 * d
            val x = p.x[i] + sway; val y = p.y[i]; val s = p.size[i]
            when (season) {
                "summer" -> v.drawBlob(c, x, y, s * 3f, p.col[i], (230 * f * (0.6f + 0.4f * sin(v.time * 6 + i))).toInt(), paint)
                "winter" -> {
                    stroke.color = Color.WHITE; stroke.alpha = (230 * f).toInt(); stroke.strokeWidth = max(1f, s * 0.18f)
                    c.save(); c.translate(x, y); c.rotate(p.rot[i])
                    for (k in 0 until 6) {
                        c.drawLine(0f, 0f, 0f, -s, stroke)
                        c.drawLine(0f, -s * 0.55f, s * 0.28f, -s * 0.8f, stroke)
                        c.drawLine(0f, -s * 0.55f, -s * 0.28f, -s * 0.8f, stroke)
                        c.rotate(60f)
                    }
                    c.restore()
                }
                "autumn" -> {
                    c.save(); c.translate(x, y); c.rotate(p.rot[i]); c.scale(s, s * (0.6f + 0.4f * abs(cos(v.time * 2 + i))))
                    paint.color = p.col[i]; paint.alpha = (240 * f).toInt()
                    c.drawPath(leaf, paint)
                    stroke.color = ColorUtils.blendARGB(p.col[i], Color.BLACK, 0.35f); stroke.alpha = (150 * f).toInt(); stroke.strokeWidth = 0.08f
                    c.drawLine(0f, -0.8f, 0f, 1f, stroke)
                    c.restore()
                }
                else -> {
                    c.save(); c.translate(x, y); c.rotate(p.rot[i]); c.scale(s * 0.7f, s * (0.5f + 0.5f * abs(cos(v.time * 2.5f + i))))
                    paint.color = p.col[i]; paint.alpha = (235 * f).toInt()
                    c.drawPath(petal, paint)
                    c.restore()
                }
            }
        }
    }
}
