package app.otoradyo

import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Station Wall: istasyon kartlarından oluşan duvar; dokununca çalar, altta çalan istasyon şeridi. */
internal class StationWallUi(h: MainActivity) : BaseUi(h) {
    private val acc = h.accent
    private val surf = 0xFF1A1C21.toInt()
    private val surfP = 0xFF2C2F36.toInt()
    private val dim = 0xFF9A9DA6.toInt()
    private var tab = "fav"
    private var tabChosen = false

    private fun tabView(t: String, id: String) = h.txt(t, h.tx(2.8f), dim, true).apply {
        gravity = Gravity.CENTER
        setPadding(h.m(2.4f), 0, h.m(2.4f), 0)
        setOnClickListener { tab = id; sig = ""; h.refresh() }
    }

    private val tabs = listOf("fav" to tabView(h.getString(R.string.tab_fav), "fav"), "dab" to tabView("DAB+", "dab"), "fm" to tabView("FM", "fm"))
    private val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val empty = h.txt("", h.tx(2.8f), dim).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val nowBadge = BadgeView(ctx)
    private val nowTitle = h.txt("", h.tx(3.2f), Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val nowSub = h.txt("", h.tx(2.1f), dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val msg = h.txt("", h.tx(2.2f), Pal.WARN).apply { maxLines = 1; visibility = View.GONE }
    private val btnPlay = btn(Glyph.PLAY, Color.BLACK, acc, h.alpha(acc, 0xCC), h.m(3.6f), 0.3f, play)
    private val btnVol = btn(Glyph.VOLUME, Color.WHITE, surfP, surf, h.m(3.6f), 0.28f) { h.showVolume() }
    private val scan = ScanPanel(acc, surf)
    private val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(h.m(1.2f), h.m(0.8f), h.m(1.2f), h.m(0.8f))
        background = h.rr(surf, h.m(1.6f))
        val s = h.m(8f)
        addView(nowBadge, h.lpm(h.m(7f), h.m(7f), 0f, 0, 0, h.m(1.2f), 0))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(nowTitle, h.lp(-1, -2))
            addView(nowSub, h.lp(-1, -2))
            addView(msg, h.lp(-1, -2))
        }, h.lp(0, -2, 1f))
        addView(btn(Glyph.PREV, Color.WHITE, surfP, surf, h.m(3.6f), 0.3f, prev), h.lpm(s, s, 0f, h.m(1f), 0, 0, 0))
        addView(btnPlay, h.lpm(s, s, 0f, h.m(1f), 0, 0, 0))
        addView(btn(Glyph.NEXT, Color.WHITE, surfP, surf, h.m(3.6f), 0.3f, next), h.lpm(s, s, 0f, h.m(1f), 0, 0, 0))
        addView(btnVol, h.lpm(s, s, 0f, h.m(1f), 0, 0, 0))
    }

    override val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF101114.toInt())
        setPadding(h.m(2f), h.m(1.4f), h.m(2f), h.m(1.6f))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            for ((_, t) in tabs) addView(t, h.lpm(-2, -1, 0f, 0, 0, h.m(0.8f), 0))
            addView(View(ctx), h.lp(0, 1, 1f))
            addView(clock(Color.WHITE, h.tx(3f)), h.lpm(-2, -2, 0f, 0, 0, h.m(1.6f), 0))
            addView(btn(Glyph.SEARCH, Color.WHITE, surf, surfP, h.m(3f), 0.28f) {
                h.service?.let { if ((if (tab == "fav") h.uiModeNow() else tab) == "dab") it.scanDab() else it.scanFm() }
            }, h.lpm(h.m(6.2f), h.m(6.2f), 0f, 0, 0, h.m(1f), 0))
            addView(btn(Glyph.MENU, Color.WHITE, surf, surfP, h.m(3f), 0.28f) { h.openMenu() }, h.lp(h.m(6.2f), h.m(6.2f)))
        }, h.lp(-1, h.m(6.4f)))
        addView(FrameLayout(ctx).apply {
            addView(ScrollView(ctx).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(grid, FrameLayout.LayoutParams(-1, -2))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }, h.lpm(-1, 0, 1f, 0, h.m(1.2f), 0, h.m(1.2f)))
        addView(bar, h.lp(-1, -2))
        addView(scan.view, h.lpm(-1, -2, 0f, 0, h.m(0.8f), 0, 0))
    }

    private var sig = ""

    private fun stations(s: RadioService): List<MainActivity.Row> {
        val fm = h.presetRows(s, "fm")
        val dab = h.presetRows(s, "dab")
        return when (tab) {
            "fm" -> fm
            "dab" -> dab
            else -> (dab + fm).filter { it.fav }
        }
    }

    private fun tile(r: MainActivity.Row, style: Int, tall: Int): View {
        val radius = h.m(1.4f)
        val box = LinearLayout(ctx).apply {
            isClickable = true
            background = h.pressable(h.rr(surf, radius, if (r.current) acc else 0, h.m(0.3f)), h.rr(surfP, radius, if (r.current) acc else 0, h.m(0.3f)))
            setPadding(h.m(1.2f), h.m(1f), h.m(1.2f), h.m(1f))
            setOnClickListener { r.onClick() }
            setOnLongClickListener { r.onLong?.invoke(); true }
        }
        val badge = BadgeView(ctx).apply { label = h.initialsOf(r.title); color = h.badgeColor(r.title); logo = h.logoFor(r.logoName) }
        val name = h.txt(r.title, h.tx(if (style == 0) 2.8f else 2.4f), Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = h.txt(r.subtitle, h.tx(1.9f), dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val star = GlyphView(ctx, Glyph.STAR, acc).apply { visibility = if (r.fav) View.VISIBLE else View.INVISIBLE }
        if (style == 2) {
            box.orientation = LinearLayout.HORIZONTAL
            box.gravity = Gravity.CENTER_VERTICAL
            box.addView(badge, h.lpm(h.m(6f), h.m(6f), 0f, 0, 0, h.m(1.2f), 0))
            box.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(name, h.lp(-1, -2))
                addView(sub, h.lp(-1, -2))
            }, h.lp(0, -2, 1f))
            box.addView(star, h.lp(h.m(3f), h.m(3f)))
        } else {
            box.orientation = LinearLayout.VERTICAL
            box.addView(FrameLayout(ctx).apply {
                addView(badge, FrameLayout.LayoutParams(tall, tall))
                addView(star, FrameLayout.LayoutParams(h.m(2.6f), h.m(2.6f), Gravity.END or Gravity.TOP))
            }, h.lp(-1, 0, 1f))
            box.addView(name, h.lpm(-1, -2, 0f, 0, h.m(0.6f), 0, 0))
            box.addView(sub, h.lp(-1, -2))
        }
        return box
    }

    private fun rebuild(list: List<MainActivity.Row>) {
        grid.removeAllViews()
        val style = h.prefs.listStyle.coerceIn(0, 2)
        val cols = when (style) {
            0 -> if (h.land) 5 else 3
            1 -> if (h.land) 7 else 4
            else -> if (h.land) 2 else 1
        }
        val rowH = when (style) { 0 -> h.m(21f); 1 -> h.m(16f); else -> h.m(9.4f) }
        val logo = if (style == 0) h.m(9f) else h.m(6.4f)
        val gap = h.m(0.6f)
        var i = 0
        while (i < list.size) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val r = list.getOrNull(i + c)
                row.addView(if (r != null) tile(r, style, logo) else View(ctx), h.lpm(0, -1, 1f, gap, gap, gap, gap))
            }
            grid.addView(row, h.lp(-1, rowH))
            i += cols
        }
    }

    override fun render(s: RadioService?, n: Now) {
        for ((id, t) in tabs) {
            val on = id == tab
            if (t.tag != on) {
                t.tag = on
                t.background = if (on) h.rr(acc, h.m(1.2f)) else null
                t.setTextColor(if (on) Color.BLACK else dim)
            }
        }
        nowBadge.label = h.initialsOf(n.title)
        nowBadge.color = h.badgeColor(n.title)
        nowBadge.logo = logoFor(n)
        nowTitle.text = n.title
        nowSub.text = (listOf(n.main + " " + n.unit, n.sub) + n.chips.drop(1).map { it.first }).filter { it.isNotBlank() }.joinToString("  ·  ")
        msg.text = n.message
        msg.visibility = if (n.message.isEmpty()) View.GONE else View.VISIBLE
        btnPlay.set(if (n.playing) Glyph.PAUSE else Glyph.PLAY, Color.BLACK)
        btnVol.set(if (n.muted) Glyph.MUTED else Glyph.VOLUME, if (n.muted) acc else Color.WHITE)
        bar.visibility = if (n.scanning) View.GONE else View.VISIBLE
        scan.update(n)
        if (s == null) return
        if (!tabChosen) {
            tabChosen = true
            if (s.favorites.isEmpty() && n.mode != "music") tab = n.mode   // favori yoksa boş ekranla değil çalan bantla aç
        }
        val list = stations(s)
        val next = tab + "|" + h.prefs.listStyle + "|" + Logos.version + "|" + list.joinToString(";") { it.key + it.title + it.current + it.fav }
        if (next != sig) {
            sig = next
            rebuild(list)
        }
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = h.getString(if (tab == "fav") R.string.empty_fav else R.string.empty_list)
    }
}
