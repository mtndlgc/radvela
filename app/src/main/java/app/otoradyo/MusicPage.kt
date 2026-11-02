package app.otoradyo

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/** Müzik ekranı: bulunan dosyalar ve çalma denetimi. Her temada aynıdır, renkleri temadan alır. */
internal class MusicPage(private val h: MainActivity) {
    val view = LinearLayout(h).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Pal.BG)
        setPadding(h.m(2.2f), h.m(1.8f), h.m(2.2f), h.m(1.6f))
    }

    private val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
    private class Group(val key: String, val name: String, val count: Int)

    private var source = ""            // "" | device | sd | usb | f:<klasör adı>
    private var browse = SONGS
    private var group: String? = null  // Klasörler / Albümler / Sanatçılar içinde açık grup
    private var query = ""
    private var shown: List<Track> = emptyList()
    private var groups: List<Group> = emptyList()
    private var base: List<Track> = emptyList()    // ortam ve aramadan geçen tüm dosyalar
    private val showGroups: Boolean get() = browse != SONGS && group == null
    private var libVersion = -1
    private var currentKey = ""
    private var dragging = false

    private val search = EditText(h).apply {
        hint = h.getString(R.string.music_search)
        setHintTextColor(Pal.DIM)
        setTextColor(Pal.FG)
        setSingleLine()
        setPadding(h.m(2f), 0, h.m(2f), 0)
        background = h.rr(Pal.CARD, h.m(2f), Pal.STROKE)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                query = e?.toString()?.trim() ?: ""
                refilter()
            }
        })
    }
    private val chips = LinearLayout(h).apply { orientation = LinearLayout.HORIZONTAL }
    private val tabs = LinearLayout(h).apply { orientation = LinearLayout.HORIZONTAL }
    private val crumb = h.txt("", h.tx(2.4f), h.accentText, true).apply {
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(h.m(1.6f), 0, h.m(1.6f), 0)
        visibility = View.GONE
        setOnClickListener { leaveGroup() }
    }
    private val info = h.txt("", h.tx(2.4f), Pal.DIM).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val grant = h.txt(h.getString(R.string.music_perm_btn), h.tx(2.4f), Color.BLACK, true).apply {
        gravity = Gravity.CENTER
        setPadding(h.m(3f), h.m(1.4f), h.m(3f), h.m(1.4f))
        background = h.rr(h.accent, h.m(2f))
        visibility = View.GONE
        setOnClickListener { h.requestMusicPermission() }
    }
    private val adapter = Rows()
    private val list = ListView(h).apply {
        divider = null
        this.adapter = this@MusicPage.adapter
        setOnItemClickListener { _, _, i, _ ->
            if (showGroups) {
                group = groups[i].key
                query = ""
                search.setText("")
                refilter()
            } else {
                h.service?.playMusic(shown, i)
            }
        }
    }

    private val artBack = GlyphView(h, Glyph.MUSIC, Pal.DIM).apply { inset = 0.28f }
    private val art = ImageView(h).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val artBox = FrameLayout(h).apply {
        background = h.rr(Pal.CARD, h.m(2f), Pal.STROKE)
        clipToOutline = true
        addView(artBack, FrameLayout.LayoutParams(-1, -1))
        addView(art, FrameLayout.LayoutParams(-1, -1))
    }
    private val title = h.txt("", h.tx(3.2f), Pal.FG, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val artist = h.txt("", h.tx(2.4f), Pal.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val elapsed = h.txt("0:00", h.tx(2.1f), Pal.DIM)
    private val total = h.txt("0:00", h.tx(2.1f), Pal.DIM)
    private val seek = SeekBar(h).apply {
        progressTintList = android.content.res.ColorStateList.valueOf(h.accent)
        thumbTintList = android.content.res.ColorStateList.valueOf(h.accent)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, v: Int, user: Boolean) { if (user) elapsed.text = clock(v) }
            override fun onStartTrackingTouch(b: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(b: SeekBar?) {
                dragging = false
                h.service?.music?.seekTo(progress)
            }
        })
    }

    private fun ctl(g: Glyph, fill: Int, tint: Int, click: () -> Unit) =
        IconButton(h, g, tint, h.pressable(h.rr(fill, h.m(4.6f)), h.rr(Pal.SURFACE_PRESSED, h.m(4.6f))), 0.28f, click)

    private val btnShuffle = ctl(Glyph.SHUFFLE, Pal.CARD, Pal.FG) { h.service?.music?.toggleShuffle() }
    private val btnPrev = ctl(Glyph.PREV, Pal.CARD, Pal.FG) { h.service?.seek(-1) }
    private val btnPlay = ctl(Glyph.PLAY, h.accent, Color.BLACK) { toggle() }
    private val btnNext = ctl(Glyph.NEXT, Pal.CARD, Pal.FG) { h.service?.seek(+1) }
    private val btnRepeat = ctl(Glyph.REPEAT, Pal.CARD, Pal.FG) { h.service?.music?.cycleRepeat() }

    init {
        val rescan = IconButton(h, Glyph.SEARCH, Pal.FG, h.pressable(h.rr(Pal.CARD, h.m(2f)), h.rr(Pal.SURFACE_PRESSED, h.m(2f))), 0.28f) { h.service?.reloadMusic() }
        rescan.contentDescription = h.getString(R.string.music_rescan)
        val add = IconButton(h, Glyph.PLUS, Pal.FG, h.pressable(h.rr(Pal.CARD, h.m(2f)), h.rr(Pal.SURFACE_PRESSED, h.m(2f))), 0.28f) { h.pickMusicFolder() }
        add.contentDescription = h.getString(R.string.music_add_folder)
        val eq = IconButton(h, Glyph.SLIDERS, Pal.FG, h.pressable(h.rr(Pal.CARD, h.m(2f)), h.rr(Pal.SURFACE_PRESSED, h.m(2f))), 0.28f) { h.showEq() }
        eq.contentDescription = h.getString(R.string.eq_t)
        val size = h.m(7.2f)
        val top = h.backRow(h.getString(R.string.music_title), eq, rescan, add)
        eq.layoutParams = h.lp(size, size)   // backRow genişliği içeriğe bırakır, simge düğmesi sabit boyut ister
        rescan.layoutParams = h.lpm(size, size, 0f, h.m(1.2f), 0, 0, 0)
        add.layoutParams = h.lpm(size, size, 0f, h.m(1.2f), 0, 0, 0)
        view.addView(top, h.lpm(-1, h.m(7.6f), 0f, 0, 0, 0, h.m(1.4f)))

        val left = LinearLayout(h).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, h.lp(-1, h.m(7f)))
            addView(HorizontalScrollView(h).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }, h.lpm(-1, h.m(6f), 0f, 0, h.m(1f), 0, 0))
            addView(tabs, h.lpm(-1, h.m(5.6f), 0f, 0, h.m(1f), 0, h.m(0.6f)))
            addView(crumb, h.lp(-1, h.m(5.4f)))
            addView(info, h.lpm(-1, -2, 0f, 0, h.m(3f), 0, h.m(1.6f)))
            addView(grant, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(list, h.lp(-1, 0, 1f))
        }

        val times = LinearLayout(h).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(elapsed, h.lp(0, -2, 1f))
            addView(total, h.lp(-2, -2))
        }
        val b = h.m(9.2f)
        val controls = LinearLayout(h).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            for ((i, c) in listOf(btnShuffle, btnPrev, btnPlay, btnNext, btnRepeat).withIndex()) {
                addView(c, h.lpm(b, b, 0f, if (i > 0) h.m(1.2f) else 0, 0, 0, 0))
            }
        }
        if (h.land) {
            val panel = LinearLayout(h).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(artBox, LinearLayout.LayoutParams(0, 0, 1f).apply { width = -1 })
                addView(title, h.lpm(-1, -2, 0f, 0, h.m(1.4f), 0, 0))
                addView(artist, h.lp(-1, -2))
                addView(seek, h.lpm(-1, -2, 0f, 0, h.m(0.8f), 0, 0))
                addView(times, h.lp(-1, -2))
                addView(controls, h.lpm(-1, -2, 0f, 0, h.m(1f), 0, 0))
            }
            view.addView(LinearLayout(h).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, h.lp(0, -1, 1.25f))
                addView(panel, h.lpm(0, -1, 1f, h.m(3f), 0, 0, 0))
            }, h.lp(-1, 0, 1f))
        } else {
            view.addView(left, h.lp(-1, 0, 1f))
            view.addView(LinearLayout(h).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(artBox, h.lp(h.m(12f), h.m(12f)))
                addView(LinearLayout(h).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title, h.lp(-1, -2))
                    addView(artist, h.lp(-1, -2))
                }, h.lpm(0, -2, 1f, h.m(2f), 0, 0, 0))
            }, h.lpm(-1, -2, 0f, 0, h.m(1.4f), 0, 0))
            view.addView(seek, h.lpm(-1, -2, 0f, 0, h.m(0.6f), 0, 0))
            view.addView(times, h.lp(-1, -2))
            view.addView(controls, h.lpm(-1, -2, 0f, 0, h.m(0.8f), 0, 0))
        }
    }

    private companion object {
        const val SONGS = 0
        const val FOLDERS = 1
        const val ALBUMS = 2
        const val ARTISTS = 3
    }

    fun hasPermission() = h.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun toggle() {
        val s = h.service ?: return
        if (s.music.current == null) { shown.ifEmpty { base }.let { if (it.isNotEmpty()) s.playMusic(it, 0) } } else s.togglePlay()
    }

    private fun clock(ms: Int): String {
        val sec = ms / 1000
        return if (sec >= 3600) String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
        else String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
    }

    private fun inSource(t: Track) = when {
        source.isEmpty() -> true
        source.startsWith("f:") -> t.kind == "folder" && t.source == source.substring(2)
        else -> t.kind == source
    }

    private fun groupKey(t: Track) = when (browse) {
        FOLDERS -> t.folder
        ALBUMS -> t.album
        else -> t.artist
    }

    private fun groupName(key: String) = when {
        key.isEmpty() -> h.getString(R.string.music_unknown)
        browse == FOLDERS -> key.substringAfterLast('/')
        else -> key
    }

    private fun refilter() {
        val s = h.service ?: return
        val q = query.lowercase()
        base = s.musicTracks.filter { t ->
            inSource(t) && (q.isEmpty() || t.title.lowercase().contains(q) || t.artist.lowercase().contains(q) || t.album.lowercase().contains(q))
        }
        if (browse == SONGS) {
            groups = emptyList()
            shown = base
        } else {
            val c = java.text.Collator.getInstance()
            groups = base.groupBy { groupKey(it) }.map { (k, v) -> Group(k, groupName(k), v.size) }.sortedWith { a, b -> c.compare(a.name, b.name) }
            shown = group?.let { g -> base.filter { groupKey(it) == g } } ?: emptyList()
        }
        crumb.text = group?.let { "‹  " + groupName(it) } ?: ""
        crumb.visibility = if (group != null) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    /** Açık klasör / albümden gruplar listesine döner. Dönülecek yer yoksa false. */
    fun leaveGroup(): Boolean {
        if (group == null) return false
        group = null
        refilter()
        return true
    }

    private fun rebuildTabs() {
        tabs.removeAllViews()
        for ((id, label) in listOf(SONGS to R.string.music_songs, FOLDERS to R.string.music_folders, ALBUMS to R.string.music_albums, ARTISTS to R.string.music_artists)) {
            val on = id == browse
            tabs.addView(h.txt(h.getString(label), h.tx(2.1f), if (on) h.accentText else Pal.DIM, true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                background = if (on) h.rr(Pal.CARD, h.m(2f), h.accent) else null
                setOnClickListener {
                    browse = id
                    group = null
                    rebuildTabs()
                    refilter()
                }
            }, h.lp(0, -1, 1f))
        }
    }

    private fun chip(label: String, value: String, removable: Boolean) = h.txt(label, h.tx(2.2f), if (value == source) Color.BLACK else Pal.DIM, true).apply {
        gravity = Gravity.CENTER
        setPadding(h.m(2.2f), 0, h.m(2.2f), 0)
        background = if (value == source) h.rr(h.accent, h.m(3f)) else h.rr(Pal.CARD, h.m(3f), Pal.STROKE)
        setOnClickListener { source = value; group = null; libVersion = -1; h.renderMusic() }
        if (removable) setOnLongClickListener { h.confirmRemoveMusicFolder(value.substring(2)); true }
    }

    private fun rebuildChips(s: RadioService) {
        chips.removeAllViews()
        val folders = s.musicTracks.filter { it.kind == "folder" }.map { it.source }.distinct()
        if (source.startsWith("f:") && source.substring(2) !in folders) source = ""
        chips.addView(chip(h.getString(R.string.music_all), "", false), h.lp(-2, -1))
        for ((value, label) in listOf("device" to R.string.music_src_device, "sd" to R.string.music_sd, "usb" to R.string.music_usb)) {
            chips.addView(chip(h.getString(label), value, false), h.lpm(-2, -1, 0f, h.m(1f), 0, 0, 0))
        }
        for (name in folders) chips.addView(chip(name, "f:$name", s.isMusicFolder(name)), h.lpm(-2, -1, 0f, h.m(1f), 0, 0, 0))
    }

    fun render(s: RadioService?) {
        if (s == null) return
        if (libVersion != s.musicVersion) {
            libVersion = s.musicVersion
            rebuildChips(s)
            rebuildTabs()
            refilter()
        }
        val noPerm = !hasPermission()
        val empty = s.musicTracks.isEmpty()
        grant.visibility = if (noPerm && empty) View.VISIBLE else View.GONE
        val msg = when {
            s.musicLoading && empty -> h.getString(R.string.music_loading)
            noPerm && empty -> h.getString(R.string.music_perm)
            empty -> h.getString(R.string.music_empty)
            query.isEmpty() && shown.isEmpty() && groups.isEmpty() -> h.getString(R.string.music_empty_src)
            else -> ""
        }
        info.text = msg
        info.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
        search.hint = h.getString(R.string.music_search) + if (empty) "" else " · " + h.getString(R.string.music_count, base.size)

        val m = s.music
        val t = m.current
        val key = if (m.active) t?.key ?: "" else ""
        if (key != currentKey) {
            currentKey = key
            adapter.notifyDataSetChanged()
        }
        title.text = if (m.active) m.title else h.getString(R.string.music_none)
        artist.text = if (m.active) m.artist else ""
        art.setImageBitmap(if (m.active) m.art else null)
        val dur = m.durationMs
        seek.max = dur.coerceAtLeast(1)
        if (!dragging) {
            seek.progress = m.positionMs
            elapsed.text = clock(m.positionMs)
        }
        total.text = clock(dur)
        btnPlay.set(if (m.active && m.playing) Glyph.PAUSE else Glyph.PLAY, Color.BLACK)
        btnShuffle.set(Glyph.SHUFFLE, if (m.shuffle) h.accentText else Pal.FG)
        btnRepeat.set(if (m.repeat == MusicPlayer.REPEAT_ONE) Glyph.REPEAT_ONE else Glyph.REPEAT, if (m.repeat != MusicPlayer.REPEAT_OFF) h.accentText else Pal.FG)
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = if (showGroups) groups.size else shown.size
        override fun getItem(i: Int): Any = if (showGroups) groups[i] else shown[i]
        override fun getItemId(i: Int) = i.toLong()

        override fun getView(i: Int, old: View?, parent: ViewGroup?): View {
            val row = old as? LinearLayout ?: LinearLayout(h).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(h.m(1.6f), 0, h.m(1.6f), 0)
                layoutParams = android.widget.AbsListView.LayoutParams(-1, h.m(8.6f))
                val col = LinearLayout(h).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(h.txt("", h.tx(2.6f), Pal.FG, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, h.lp(-1, -2))
                    addView(h.txt("", h.tx(2.1f), Pal.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, h.lp(-1, -2))
                }
                addView(col, h.lp(0, -2, 1f))
                addView(h.txt("", h.tx(2.1f), Pal.DIM), h.lpm(-2, -2, 0f, h.m(1.6f), 0, 0, 0))
            }
            val col = row.getChildAt(0) as LinearLayout
            if (showGroups) {
                val g = groups[i]
                (col.getChildAt(0) as TextView).apply {
                    text = g.name
                    setTextColor(Pal.FG)
                }
                (col.getChildAt(1) as TextView).text = h.getString(R.string.music_count, g.count)
                (row.getChildAt(1) as TextView).text = ""
                return row
            }
            val t = shown[i]
            val on = t.key == currentKey
            (col.getChildAt(0) as TextView).apply {
                text = t.title
                setTextColor(if (on) h.accentText else Pal.FG)
            }
            (col.getChildAt(1) as TextView).text = listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString("  ·  ").ifEmpty { t.source }
            (row.getChildAt(1) as TextView).text = if (t.durationMs > 0) clock(t.durationMs.toInt()) else ""
            return row
        }
    }
}
