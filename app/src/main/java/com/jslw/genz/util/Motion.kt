package com.jslw.genz.util

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.AnticipateInterpolator
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.RotateAnimation
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import androidx.recyclerview.widget.RecyclerView
import com.jslw.genz.R
import com.jslw.genz.data.Prefs
import kotlin.random.Random

/**
 * List scroll animations – the set popularised by XuiMod (Apache License 2.0, © 2013 XuiMod / zst123),
 * re-implemented for RecyclerView: rows animate in as they scroll onto the screen.
 */
object ListAnim {
    val NAMES = listOf(
        "Off", "Wave (left)", "Wave (right)", "Scale", "Fade", "Stack from top", "Stack from bottom",
        "Unfold", "Fold", "Slide from left", "Slide from right", "Rotate", "Fly-in (left)", "Fly-in (right)",
    )
    val INTERPOLATORS = listOf(
        "Accelerate", "Decelerate", "Accelerate–decelerate", "Anticipate", "Overshoot",
        "Anticipate–overshoot", "Bounce", "Cycle", "Linear",
    )

    fun interpolator(i: Int): Interpolator = when (i) {
        0 -> AccelerateInterpolator()
        2 -> AccelerateDecelerateInterpolator()
        3 -> AnticipateInterpolator()
        4 -> OvershootInterpolator()
        5 -> AnticipateOvershootInterpolator()
        6 -> BounceInterpolator()
        7 -> CycleInterpolator(0.5f)
        8 -> LinearInterpolator()
        else -> DecelerateInterpolator(1.6f)
    }

    fun attach(rv: RecyclerView) {
        if (rv.getTag(R.id.tag_list_anim) != null) return
        rv.setTag(R.id.tag_list_anim, true)
        var down = true
        var lastScroll = 0L
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) { down = dy > 0; lastScroll = SystemClock.uptimeMillis() }
            }
        })
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val scrolling = rv.scrollState != RecyclerView.SCROLL_STATE_IDLE || SystemClock.uptimeMillis() - lastScroll < 250
                if (scrolling && Prefs.listAnim != 0) animate(view, down, Prefs.listAnim)
            }
            override fun onChildViewDetachedFromWindow(view: View) { view.clearAnimation() }
        })
    }

    fun animate(view: View, down: Boolean, type: Int) {
        val h = view.height.takeIf { it > 0 }?.toFloat() ?: (64 * view.resources.displayMetrics.density)
        val w = view.width.takeIf { it > 0 }?.toFloat() ?: view.resources.displayMetrics.widthPixels.toFloat()
        val rel = Animation.RELATIVE_TO_SELF
        val anim: Animation = when (type) {
            1 -> ScaleAnimation(0.5f, 1f, 0.5f, 1f)
            2 -> ScaleAnimation(0.5f, 1f, 0.5f, 1f, rel, 1f, rel, 1f)
            3 -> ScaleAnimation(0.5f, 1f, 0.5f, 1f, rel, 0.5f, rel, 0.5f)
            4 -> AlphaAnimation(0f, 1f)
            5 -> TranslateAnimation(0f, 0f, -h, 0f)
            6 -> TranslateAnimation(0f, 0f, h, 0f)
            7 -> TranslateAnimation(0f, 0f, if (down) -h else h, 0f)
            8 -> TranslateAnimation(0f, 0f, if (down) h else -h, 0f)
            9 -> TranslateAnimation(-w, 0f, 0f, 0f)
            10 -> TranslateAnimation(w, 0f, 0f, 0f)
            11 -> RotateAnimation(180f, 0f, rel, 0.5f, rel, 0.5f)
            12, 13 -> AnimationSet(true).apply {
                val left = type == 12
                addAnimation(TranslateAnimation(0f, 0f, if (down) h * 1.4f else -h * 1.4f, 0f))
                addAnimation(AlphaAnimation(0f, 1f))
                addAnimation(RotateAnimation(if (down == left) 12f else -12f, 0f, if (left) 0f else w, 0f))
            }
            else -> return
        }
        anim.duration = Prefs.listDuration.toLong()
        anim.interpolator = interpolator(Prefs.listInterp)
        view.startAnimation(anim)
    }
}

/**
 * Screen-to-screen transitions. "Zoom", "Drop", "Fold" and "Swoop" are adapted from the window
 * animation sets shipped with XuiMod (Apache License 2.0).
 */
object Transitions {
    val STYLES = listOf(
        "genz" to "GEN-Z slide", "fade" to "Fade", "zoom" to "Zoom", "grow" to "Grow",
        "slide_up" to "Slide up", "drop" to "Drop", "fold" to "Fold", "swoop" to "Swoop", "random" to "Random",
    )

    private val SETS = mapOf(
        "genz" to intArrayOf(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right),
        "fade" to intArrayOf(R.anim.tr_fade_in, R.anim.tr_fade_out, R.anim.tr_fade_in, R.anim.tr_fade_out),
        "zoom" to intArrayOf(R.anim.tr_zoom_open_enter, R.anim.tr_zoom_open_exit, R.anim.tr_zoom_close_enter, R.anim.tr_zoom_close_exit),
        "grow" to intArrayOf(R.anim.tr_grow_enter, R.anim.tr_fade_out_slow, R.anim.tr_fade_in, R.anim.tr_shrink_exit),
        "slide_up" to intArrayOf(R.anim.slide_up, R.anim.stay, R.anim.stay, R.anim.slide_down),
        "drop" to intArrayOf(R.anim.tr_drop_open_enter, R.anim.tr_drop_open_exit, R.anim.tr_drop_close_enter, R.anim.tr_drop_close_exit),
        "fold" to intArrayOf(R.anim.tr_fold_open_enter, R.anim.tr_fold_open_exit, R.anim.tr_fold_close_enter, R.anim.tr_fold_close_exit),
        "swoop" to intArrayOf(R.anim.tr_swoop_open_enter, R.anim.tr_swoop_open_exit, R.anim.tr_swoop_close_enter, R.anim.tr_swoop_close_exit),
    )

    private fun set(): IntArray {
        val s = Prefs.transition
        if (s == "random") return SETS.values.elementAt(Random.nextInt(SETS.size))
        return SETS[s] ?: SETS.getValue("genz")
    }

    /** Call right after startActivity(). */
    fun open(a: Activity) { val s = set(); a.overridePendingTransition(s[0], s[1]) }

    /** Call right after finish(). */
    fun close(a: Activity) { val s = set(); a.overridePendingTransition(s[2], s[3]) }

    /** enter, exit, popEnter, popExit for fragment navigation. */
    fun fragment(): IntArray = set()
}

/** Playful, springy motion for menus: rows bounce in, and squish + spring back when pressed. */
object Bounce {
    fun layoutAnimation(): android.view.animation.LayoutAnimationController {
        val set = AnimationSet(false)
        set.addAnimation(TranslateAnimation(Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
            Animation.RELATIVE_TO_SELF, 0.9f, Animation.RELATIVE_TO_SELF, 0f).apply { interpolator = OvershootInterpolator(2.2f) })
        set.addAnimation(ScaleAnimation(0.85f, 1f, 0.85f, 1f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
            .apply { interpolator = OvershootInterpolator(3f) })
        set.addAnimation(AlphaAnimation(0f, 1f).apply { interpolator = DecelerateInterpolator() })
        set.duration = 520
        return android.view.animation.LayoutAnimationController(set, 0.08f)
    }

    /** Plays the bounce-in on a list (call after setting its rows). */
    fun enter(rv: RecyclerView) {
        rv.layoutAnimation = layoutAnimation()
        rv.scheduleLayoutAnimation()
    }

    /** Squish on touch, spring back on release. Doesn't consume the touch, so clicks still work. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun press(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(OvershootInterpolator(4f)).start()
            }
            false
        }
    }
}

/** Staggered, springy entrance for the sections of a form-like screen (Equalizer, editors). */
object Stagger {
    fun enter(container: android.view.ViewGroup, startDelay: Long = 60, step: Long = 45) {
        val d = container.resources.displayMetrics.density
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.visibility != View.VISIBLE) continue
            v.alpha = 0f
            v.translationY = 36 * d
            v.scaleX = 0.96f; v.scaleY = 0.96f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(startDelay + i * step).setDuration(520)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
        }
    }
}
