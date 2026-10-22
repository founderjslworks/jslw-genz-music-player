package com.jslw.genz.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.jslw.genz.R
import com.jslw.genz.model.Song
import com.jslw.genz.playback.Player
import com.jslw.genz.skin.SkinManager
import com.jslw.genz.skin.SkinPainter
import com.jslw.genz.util.ArtLoader
import com.jslw.genz.util.dpi
import com.jslw.genz.util.formatTime

/** One line in any list. */
class Row(
    val title: String,
    val subtitle: String? = null,
    val extra: String? = null,
    val icon: Int = 0,
    /** Vivid iOS-style icon tile colour (used when the theme has "colorfulIcons"). */
    val iconColor: Int = 0,
    val art: Song? = null,
    val chevron: Boolean = false,
    val song: Song? = null,
    val section: Boolean = false,
    val drag: Boolean = false,
    val activated: Boolean = false,
    val onClick: ((View) -> Unit)? = null,
    val onLong: ((View) -> Unit)? = null,
    val onMore: ((View) -> Unit)? = null,
) {
    companion object {
        fun section(title: String) = Row(title, section = true)

        fun forSong(
            s: Song, showArt: Boolean = true, trackNo: Boolean = false,
            onClick: (View) -> Unit, onMore: (View) -> Unit,
        ) = Row(
            title = if (trackNo && s.track > 0) "${s.track % 1000}. ${s.title}" else s.title,
            subtitle = s.subtitle,
            extra = formatTime(s.duration),
            art = if (showArt) s else null,
            song = s,
            onClick = onClick,
            onLong = onMore,
            onMore = onMore,
        )
    }
}

class RowAdapter(rows: List<Row> = emptyList()) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var data: MutableList<Row> = rows.toMutableList()
    var rows: List<Row>
        get() = data
        @SuppressLint("NotifyDataSetChanged")
        set(v) { data = v.toMutableList(); notifyDataSetChanged() }

    /** Live reorder during a drag (the caller commits the final order on drop). */
    fun moveItem(from: Int, to: Int) {
        if (from !in data.indices || to !in data.indices) return
        data.add(to, data.removeAt(from))
        notifyItemMoved(from, to)
    }

    var itemTouchHelper: ItemTouchHelper? = null
    /** Springy press feedback on rows (main menu, settings). */
    var bouncy = false
    private var playingId: Long? = Player.current?.id

    /** Refreshes the "now playing" indicators only when the track actually changed. */
    @SuppressLint("NotifyDataSetChanged")
    fun refreshPlaying() {
        val id = Player.current?.id
        if (id != playingId) { playingId = id; notifyDataSetChanged() }
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position].section) 1 else 0

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val drag: ImageView = v.findViewById(R.id.rowDrag)
        val icon: ImageView = v.findViewById(R.id.rowIcon)
        val art: ImageView = v.findViewById(R.id.rowArt)
        val title: TextView = v.findViewById(R.id.rowTitle)
        val subtitle: TextView = v.findViewById(R.id.rowSubtitle)
        val playing: ImageView = v.findViewById(R.id.rowPlaying)
        val extra: TextView = v.findViewById(R.id.rowExtra)
        val chevron: ImageView = v.findViewById(R.id.rowChevron)
        val more: ImageButton = v.findViewById(R.id.rowMore)
    }

    class SectionVH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 1) SectionVH(inf.inflate(R.layout.item_section, parent, false).also { SkinPainter.apply(it) })
        else VH(inf.inflate(R.layout.item_row, parent, false).also { SkinPainter.apply(it) })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val r = rows[position]
        if (h is SectionVH) { (h.itemView as TextView).text = r.title; return }
        h as VH
        val skin = SkinManager.current
        h.title.text = r.title
        h.subtitle.text = r.subtitle
        h.subtitle.visibility = if (r.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        h.extra.text = r.extra
        h.extra.visibility = if (r.extra.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (r.icon != 0) {
            com.jslw.genz.skin.Icons.set(h.icon, r.icon)
            h.icon.visibility = View.VISIBLE
            val d = h.icon.resources.displayMetrics.density
            if (r.iconColor != 0 && skin.colorfulIcons) {
                h.icon.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(r.iconColor); cornerRadius = 8 * d
                }
                val p = (5 * d).toInt()
                h.icon.setPadding(p, p, p, p)
                androidx.core.widget.ImageViewCompat.setImageTintList(h.icon, android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE))
                h.icon.layoutParams = h.icon.layoutParams.apply { width = (32 * d).toInt(); height = (32 * d).toInt() }
            } else {
                h.icon.background = null
                h.icon.setPadding(0, 0, 0, 0)
                androidx.core.widget.ImageViewCompat.setImageTintList(h.icon, android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(skin.rowHighlightText, skin.accent)))
                h.icon.layoutParams = h.icon.layoutParams.apply { width = (26 * d).toInt(); height = (26 * d).toInt() }
            }
        } else h.icon.visibility = View.GONE
        if (r.art != null) {
            h.art.visibility = View.VISIBLE
            ArtLoader.load(h.art, r.art, h.art.context.dpi(46) * 2)
        } else h.art.visibility = View.GONE
        h.chevron.visibility = if (r.chevron && skin.chevrons) View.VISIBLE else View.GONE
        h.more.visibility = if (r.onMore != null) View.VISIBLE else View.GONE
        h.more.setOnClickListener { v -> r.onMore?.invoke(v) }
        h.playing.visibility = if (r.song != null && r.song.id == playingId) View.VISIBLE else View.GONE
        h.root.isActivated = r.activated
        if (bouncy) com.jslw.genz.util.Bounce.press(h.root)
        h.root.setOnClickListener { v -> r.onClick?.invoke(v) }
        h.root.isClickable = r.onClick != null
        if (r.onLong != null) h.root.setOnLongClickListener { v -> r.onLong.invoke(v); true }
        else h.root.setOnLongClickListener(null)
        h.drag.visibility = if (r.drag) View.VISIBLE else View.GONE
        h.drag.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) itemTouchHelper?.startDrag(h)
            false
        }
    }
}

/** "Play / Shuffle" header shown above song lists. */
class HeaderAdapter(
    private var info: String,
    private val onPlay: () -> Unit,
    private val onShuffle: () -> Unit,
    private val onInfo: ((View) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var holder: View? = null

    fun setInfo(text: String) {
        info = text
        holder?.findViewById<TextView>(R.id.headerInfo)?.text = text
    }

    override fun getItemCount() = 1
    override fun getItemViewType(position: Int) = 7

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_header_actions, parent, false)
        SkinPainter.apply(v)
        return object : RecyclerView.ViewHolder(v) {}
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        holder = h.itemView
        val t = h.itemView.findViewById<TextView>(R.id.headerInfo)
        t.text = info
        t.setOnClickListener { v -> onInfo?.invoke(v) }
        h.itemView.findViewById<MaterialButton>(R.id.btnPlayAll).setOnClickListener { onPlay() }
        h.itemView.findViewById<MaterialButton>(R.id.btnShuffleAll).setOnClickListener { onShuffle() }
    }
}
