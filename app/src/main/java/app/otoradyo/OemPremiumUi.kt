package app.otoradyo

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** OEM Premium: siyah-antrasit zemin, ince tipografi, tek vurgu rengi; fabrika ekranı hissi. */
internal class OemPremiumUi(h: MainActivity) : BaseUi(h) {
    private val acc = h.accent
    private val surf = 0xFF1F2023.toInt()
    private val surfP = 0xFF303236.toInt()

    private val seg = ModeSeg(GradientDrawable().apply { setColor(acc); cornerRadius = h.m(0.8f).toFloat() }, Color.WHITE, 0xFF8B8D90.toInt(), h.tx(3f))
    private val status = h.txt("", h.tx(2.1f), 0xFF8B8D90.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val sub = h.txt("", h.tx(2.7f), 0xFFB9BABC.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val title = h.txt("", h.tx(12f), Color.WHITE).apply { typeface = lightFace(); maxLines = 1; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
    private val freq = h.txt("", h.tx(3.4f), acc, true).apply { maxLines = 1 }
    private val text = h.txt("", h.tx(2.7f), 0xFFD6D6D4.toInt()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val msg = h.txt("", h.tx(2.3f), Pal.WARN).apply { maxLines = 2; visibility = View.GONE }
    private val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val signal = SignalBars(ctx).apply { accent = acc }
    private val art = BadgeView(ctx)
    private val btnPlay = btn(Glyph.PLAY, Color.WHITE, acc, h.alpha(acc, 0xCC), h.m(5f), 0.3f, play)
    private val btnFav = btn(Glyph.STAR_O, Color.WHITE, surf, surfP, h.m(4f), 0.3f, fav)
    private val btnRec = btn(Glyph.REC, Pal.DANGER, surf, surfP, h.m(4f), 0.32f) { h.toggleRec() }
    private val btnVol = btn(Glyph.VOLUME, Color.WHITE, surf, surfP, h.m(4f), 0.28f) { h.showVolume() }
    private val scan = ScanPanel(acc, 0xFF1B1C1E.toInt())
    private val strip = StationStrip(if (h.land) h.wPct(19f) else h.wPct(44f), 0xFF1B1C1E.toInt(), surfP, acc, h.m(1.2f))
    private var chipSig = ""

    private val controls = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val s = h.m(8.4f)
        addView(btn(Glyph.PREV, Color.WHITE, surf, surfP, h.m(4f), 0.3f, prev), h.lpm(s, s, 0f, 0, 0, h.m(1.2f), 0))
        addView(btnPlay, h.lpm(h.m(10.4f), h.m(10.4f), 0f, 0, 0, h.m(1.2f), 0))
        addView(btn(Glyph.NEXT, Color.WHITE, surf, surfP, h.m(4f), 0.3f, next), h.lpm(s, s, 0f, 0, 0, h.m(1.2f), 0))
        addView(btnFav, h.lpm(s, s, 0f, 0, 0, h.m(1.2f), 0))
        addView(btnRec, h.lpm(s, s, 0f, 0, 0, h.m(1.2f), 0))
        addView(btnVol, h.lp(s, s))
    }

    override val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1A1B1D.toInt(), 0xFF0A0A0B.toInt()))
        setPadding(h.m(2.4f), h.m(1.6f), h.m(2.4f), h.m(1.8f))

        h.autoSize(title, h.mf(3.5f), h.mf(if (h.land) 13f else 11f))
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(seg.view, h.lp(-2, h.m(6f)))
            addView(status, h.lpm(0, -2, 1f, h.m(2f), 0, h.m(1f), 0))
            addView(signal, h.lpm(h.m(9f), h.m(3.6f), 0f, 0, 0, h.m(2f), 0))
            addView(clock(Color.WHITE, h.tx(3.2f)), h.lpm(-2, -2, 0f, 0, 0, h.m(1.6f), 0))
            addView(btn(Glyph.MENU, Color.WHITE, surf, surfP, h.m(3.2f), 0.28f) { h.openMenu() }, h.lp(h.m(6.6f), h.m(6.6f)))
        }
        addView(top, h.lp(-1, h.m(7f)))

        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sub, h.lp(-1, -2))
            addView(title, h.lp(-1, h.m(if (h.land) 15f else 13f)))
            addView(freq, h.lp(-1, -2))
            addView(chips, h.lpm(-1, -2, 0f, 0, h.m(1f), 0, 0))
            addView(text, h.lpm(-1, -2, 0f, 0, h.m(1.2f), 0, 0))
            addView(msg, h.lp(-1, -2))
        }
        val body = LinearLayout(ctx).apply {
            orientation = if (h.land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            if (h.land) {
                addView(left, h.lp(0, -2, 1.6f))
                addView(art, h.lpm(0, h.m(30f), 0.9f, h.m(3f), 0, 0, 0))
            } else {
                addView(art, h.lpm(h.m(34f), h.m(34f), 0f, 0, 0, 0, h.m(2f)))
                addView(left, h.lp(-1, -2))
            }
        }
        addView(body, h.lp(-1, 0, 1f))
        addView(controls, h.lpm(-1, h.m(10.8f), 0f, 0, 0, 0, h.m(1.4f)))
        addView(scan.view, h.lpm(-1, -2, 0f, 0, 0, 0, h.m(1.4f)))
        addView(strip.view, h.lp(-1, h.m(9.5f)))
    }

    private fun chipView(t: String, hot: Boolean) = h.txt(t, h.tx(2f), if (hot) acc else 0xFF9A9B9D.toInt(), true).apply {
        setPadding(h.m(1.1f), h.m(0.5f), h.m(1.1f), h.m(0.5f))
        background = h.rr(0, h.m(0.8f), if (hot) acc else 0xFF3A3B3E.toInt())
    }

    override fun render(s: RadioService?, n: Now) {
        seg.update(n.mode)
        status.text = n.status
        sub.text = n.sub
        title.text = n.title
        freq.text = n.main + " " + n.unit
        freq.visibility = if (n.title == freq.text.toString()) View.GONE else View.VISIBLE   // istasyon adı yoksa frekans başlıktadır
        text.text = n.text
        msg.text = n.message
        msg.visibility = if (n.message.isEmpty()) View.GONE else View.VISIBLE
        signal.level = n.signal
        art.label = h.initialsOf(n.title)
        art.color = h.badgeColor(n.title)
        art.logo = logoFor(n)
        btnPlay.set(if (n.playing) Glyph.PAUSE else Glyph.PLAY, Color.WHITE)
        btnFav.set(if (n.fav) Glyph.STAR else Glyph.STAR_O, if (n.fav) acc else Color.WHITE)
        btnRec.set(if (n.recording) Glyph.STOP else Glyph.REC, Pal.DANGER)
        btnVol.set(if (n.muted) Glyph.MUTED else Glyph.VOLUME, if (n.muted) acc else Color.WHITE)
        controls.visibility = if (n.scanning) View.GONE else View.VISIBLE
        scan.update(n)
        val sig = n.chips.joinToString { it.first + it.second }
        if (sig != chipSig) {
            chipSig = sig
            chips.removeAllViews()
            for ((t, hot) in n.chips) chips.addView(chipView(t, hot), h.lpm(-2, -2, 0f, 0, 0, h.m(0.8f), 0))
        }
        s?.let { strip.update(it, n.mode) }
    }
}
