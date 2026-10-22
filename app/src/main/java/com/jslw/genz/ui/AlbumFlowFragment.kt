package com.jslw.genz.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.jslw.genz.R
import com.jslw.genz.data.Library
import com.jslw.genz.model.Album
import com.jslw.genz.playback.Player
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.util.ArtLoader
import com.jslw.genz.util.formatTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * Album Flow – the classic album carousel:
 * 3D-tilted covers with mirror reflections, fling to browse, tap the centre cover to flip it
 * over and see (and play) its tracks.
 */
class AlbumFlowFragment : Fragment(), Refreshable {
    private lateinit var rv: RecyclerView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var flip: LinearLayout
    private lateinit var flipTracks: RecyclerView
    private lateinit var emptyView: TextView
    private var albums: List<Album> = emptyList()
    private var cover = 0
    private var centerPos = -1
    private var order = IntArray(0)
    private var dist = FloatArray(16)
    private val reflections = LruCache<Long, Bitmap>(24)
    private var flippedAlbum: Album? = null
    private val tracksAdapter = RowAdapter()

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = flipClose()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_album_flow, container, false)
        rv = v.findViewById(R.id.flowList)
        title = v.findViewById(R.id.flowTitle)
        artist = v.findViewById(R.id.flowArtist)
        flip = v.findViewById(R.id.flowFlip)
        flipTracks = v.findViewById(R.id.flipTracks)
        emptyView = v.findViewById(R.id.flowEmpty)
        SkinPainter.apply(v)
        flipTracks.layoutManager = LinearLayoutManager(requireContext())
        flipTracks.adapter = tracksAdapter
        v.findViewById<View>(R.id.flipClose).setOnClickListener { flipClose() }
        v.findViewById<View>(R.id.flipPlay).setOnClickListener {
            flippedAlbum?.let { a -> Player.play(requireContext(), a.songs, 0, a.title) }
        }
        rv.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        LinearSnapHelper().attachToRecyclerView(rv)
        rv.setChildDrawingOrderCallback { count, i -> if (order.size == count) order[i] else i }
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { transform() }
        })
        rv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> transform() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCb)
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rv.post { setup() }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setScreenTitle("Album Flow")
    }

    private fun setup() {
        if (view == null) return
        albums = Library.albums
        emptyView.visibility = if (albums.isEmpty()) View.VISIBLE else View.GONE
        val h = rv.height
        val w = rv.width
        val refl = if (SkinManager.current.reflection) 1.37f else 1.05f
        // big covers: up to 74% of the width in portrait, height-limited in landscape / on tablets
        val portrait = h > w
        cover = min(h * 0.92f / refl, w * (if (portrait) 0.74f else 0.42f)).toInt().coerceAtLeast(80)
        val pad = (w - cover) / 2
        // centre the covers (and their reflections) vertically instead of hugging the top
        val itemH = (cover * (if (SkinManager.current.reflection) 1.36f else 1f)).toInt()
        val top = ((h - itemH) / 2 + cover * 0.08f).toInt().coerceAtLeast(0)
        rv.setPadding(pad, top, pad, 0)
        rv.clipToPadding = false
        rv.adapter = CoverAdapter()
        flip.layoutParams = flip.layoutParams.apply {
            width = (cover * 1.25f).toInt().coerceAtMost(w - 24)
            height = (cover * 1.15f).toInt().coerceAtMost(h)
        }
        // scrubber
        val scrub = view?.findViewById<com.google.android.material.slider.Slider>(R.id.flowScrub)
        if (scrub != null) {
            if (albums.size > 1) {
                scrub.visibility = View.VISIBLE
                scrub.valueFrom = 0f
                scrub.valueTo = (albums.size - 1).toFloat()
                scrub.stepSize = 1f
                scrub.value = 0f
                scrub.clearOnChangeListeners()
                scrub.addOnChangeListener { _, v, fromUser ->
                    if (fromUser) (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(v.toInt(), 0)
                }
            } else scrub.visibility = View.INVISIBLE
        }
        val start = Player.current?.let { s -> Library.albumOf(s)?.let { a -> albums.indexOf(a) } }?.takeIf { it >= 0 } ?: lastIndex
        if (albums.isNotEmpty()) {
            (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start.coerceIn(0, albums.lastIndex), 0)
        }
        rv.post { transform() }
    }

    /** The heart of the effect: tilt, scale, overlap and z-order covers by distance from centre. */
    private fun transform() {
        if (cover == 0) return
        val cx = rv.width / 2f
        val n = rv.childCount
        if (dist.size < n) dist = FloatArray(n + 8)
        val density = resources.displayMetrics.density
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            val c = rv.getChildAt(i)
            val d = ((c.left + c.right) / 2f - cx) / cover
            dist[i] = d
            val ad = abs(d)
            val cl = d.coerceIn(-1f, 1f)
            c.cameraDistance = 9000 * density
            c.pivotX = c.width / 2f
            c.pivotY = cover / 2f
            c.rotationY = -cl * 58f
            val s = 1f - 0.16f * min(ad, 1f)
            c.scaleX = s; c.scaleY = s
            val shift = if (ad <= 1f) ad * 0.48f else 0.48f + (ad - 1f) * 0.70f
            c.translationX = -sign(d) * shift * cover
            c.alpha = (1f - 0.14f * (ad - 1f).coerceAtLeast(0f)).coerceIn(0.25f, 1f)
            if (ad < bestD) { bestD = ad; best = rv.getChildAdapterPosition(c) }
        }
        // draw farthest first, centre cover last (insertion sort: n is ~10, no allocation)
        if (order.size != n) order = IntArray(n)
        for (i in 0 until n) order[i] = i
        for (i in 1 until n) {
            val k = order[i]; var j = i - 1
            while (j >= 0 && abs(dist[order[j]]) < abs(dist[k])) { order[j + 1] = order[j]; j-- }
            order[j + 1] = k
        }
        rv.invalidate()
        if (best != centerPos && best >= 0) {
            centerPos = best
            lastIndex = best
            rv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showCaption(albums.getOrNull(best))
            view?.findViewById<com.google.android.material.slider.Slider>(R.id.flowScrub)?.let { sc ->
                if (best.toFloat() in sc.valueFrom..sc.valueTo && sc.value != best.toFloat()) sc.value = best.toFloat()
            }
        }
    }

    private fun showCaption(a: Album?) {
        title.animate().cancel(); artist.animate().cancel()
        title.text = a?.title ?: ""
        artist.text = a?.let { listOf(it.artist, if (it.year > 0) it.year.toString() else null).filterNotNull().joinToString(" · ") } ?: ""
        title.alpha = 0.3f; artist.alpha = 0.3f
        title.animate().alpha(1f).setDuration(180).start()
        artist.animate().alpha(1f).setDuration(240).start()
    }

    private fun onCoverClick(v: View, pos: Int) {
        if (pos == centerPos) flipOpen(albums[pos], v)
        else rv.smoothScrollBy(((v.left + v.right) / 2f - rv.width / 2f).toInt(), 0, DecelerateInterpolator())
    }

    private fun flipOpen(a: Album, v: View) {
        flippedAlbum = a
        v.animate().rotationY(90f).setDuration(170).setInterpolator(AccelerateInterpolator()).withEndAction {
            v.visibility = View.INVISIBLE
            view?.findViewById<TextView>(R.id.flipTitle)?.text = a.title
            view?.findViewById<TextView>(R.id.flipArtist)?.text = "${a.artist} · ${a.songs.size} songs"
            tracksAdapter.rows = a.songs.mapIndexed { i, s ->
                Row(
                    title = (if (s.track > 0) "${s.track % 1000}. " else "") + s.title,
                    extra = formatTime(s.duration), song = s,
                    onClick = { Player.play(requireContext(), a.songs, i, a.title) },
                    onLong = { Sheets.songOptions(requireActivity() as androidx.appcompat.app.AppCompatActivity, s) },
                )
            }
            flip.visibility = View.VISIBLE
            flip.cameraDistance = 9000 * resources.displayMetrics.density
            flip.rotationY = -90f
            flip.animate().rotationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
            backCb.isEnabled = true
        }.start()
    }

    private fun flipClose() {
        backCb.isEnabled = false
        flip.animate().rotationY(90f).setDuration(170).setInterpolator(AccelerateInterpolator()).withEndAction {
            flip.visibility = View.GONE
            flip.rotationY = 0f
            for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i)
                if (c.visibility != View.VISIBLE) {
                    c.visibility = View.VISIBLE
                    c.rotationY = -90f
                    c.animate().rotationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).withEndAction { transform() }.start()
                }
            }
            flippedAlbum = null
        }.start()
    }

    override fun refresh() { if (view != null) rv.post { setup() } }
    override fun playingChanged() { tracksAdapter.refreshPlaying() }

    inner class CoverAdapter : RecyclerView.Adapter<CoverAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val art: ImageView = v.findViewById(R.id.coverArt)
            val refl: ImageView = v.findViewById(R.id.coverReflection)
        }

        override fun getItemCount() = albums.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cover, parent, false)
            v.layoutParams = RecyclerView.LayoutParams(cover, ViewGroup.LayoutParams.WRAP_CONTENT)
            v.findViewById<View>(R.id.coverArt).layoutParams = LinearLayout.LayoutParams(cover, cover)
            v.findViewById<View>(R.id.coverReflection).layoutParams = LinearLayout.LayoutParams(cover, (cover * 0.35f).toInt())
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val a = albums[position]
            h.itemView.visibility = View.VISIBLE
            h.refl.setImageDrawable(null)
            val wantRefl = SkinManager.current.reflection
            h.refl.visibility = if (wantRefl) View.VISIBLE else View.GONE
            ArtLoader.load(h.art, a.artSong, cover, large = true) { bmp ->
                if (!wantRefl || bmp == null || h.bindingAdapterPosition != position) return@load
                val r = reflections.get(a.id) ?: ArtLoader.reflection(bmp).also { reflections.put(a.id, it) }
                h.refl.setImageBitmap(r)
            }
            h.itemView.setOnClickListener { v -> onCoverClick(v, h.bindingAdapterPosition) }
        }
    }

    companion object {
        private var lastIndex = 0
    }
}
