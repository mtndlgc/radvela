package app.otoradyo

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Minimal Driving: sürüşte tek bakışta okunur; çok büyük ad, ekranın üçte biri düğme, animasyon yok. */
internal class MinimalDrivingUi(h: MainActivity) : BaseUi(h) {
    private val surf = 0xFF1A1A1A.toInt()
    private val surfP = 0xFF333333.toInt()
    private val r = h.m(1.8f)

    private val seg = ModeSeg(GradientDrawable().apply { setColor(0xFF333333.toInt()); cornerRadius = h.m(1f).toFloat() }, Color.WHITE, 0xFF777777.toInt(), h.tx(3.4f))
    private val signal = SignalBars(ctx).apply { accent = Color.WHITE }
    private val title = h.txt("", h.tx(20f), Color.WHITE, true).apply { maxLines = 1; gravity = Gravity.CENTER }
    private val sub = h.txt("", h.tx(3.2f), 0xFF9A9A9A.toInt()).apply { maxLines = 1; gravity = Gravity.CENTER; ellipsize = TextUtils.TruncateAt.END }
    private val msg = h.txt("", h.tx(2.6f), Pal.WARN).apply { maxLines = 1; gravity = Gravity.CENTER; visibility = View.GONE }
    private val btnPlay = btn(Glyph.PLAY, Color.WHITE, surf, surfP, r, 0.3f, play)
    private val btnFav = btn(Glyph.STAR_O, Color.WHITE, surf, surfP, r, 0.3f, fav)
    private val scan = ScanPanel(Color.WHITE, surf)
    private val nav = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val g = h.m(0.7f)
        addView(btn(Glyph.PREV, Color.WHITE, surf, surfP, r, 0.3f, prev), h.lpm(0, -1, 1.3f, 0, 0, g, 0))
        addView(btnPlay, h.lpm(0, -1, 1f, g, 0, g, 0))
        addView(btnFav, h.lpm(0, -1, 1f, g, 0, g, 0))
        addView(btn(Glyph.NEXT, Color.WHITE, surf, surfP, r, 0.3f, next), h.lpm(0, -1, 1.3f, g, 0, 0, 0))
    }

    override val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF050505.toInt())
        setPadding(h.m(2.2f), h.m(1.4f), h.m(2.2f), h.m(1.8f))
        h.autoSize(title, h.mf(5f), h.mf(if (h.land) 26f else 22f))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(seg.view, h.lp(-2, -1))
            addView(View(ctx), h.lp(0, 1, 1f))
            addView(signal, h.lpm(h.m(12f), h.m(4.4f), 0f, 0, 0, h.m(2f), 0))
            addView(clock(Color.WHITE, h.tx(3.8f)), h.lpm(-2, -2, 0f, 0, 0, h.m(2f), 0))
            addView(btn(Glyph.VOLUME, Color.WHITE, surf, surfP, r, 0.26f) { h.showVolume() }, h.lpm(h.m(8f), h.m(8f), 0f, 0, 0, h.m(1f), 0))
            addView(btn(Glyph.MENU, Color.WHITE, surf, surfP, r, 0.26f) { h.openMenu() }, h.lp(h.m(8f), h.m(8f)))
        }, h.lp(-1, h.m(8f)))
        addView(title, h.lp(-1, 0, 1f))
        addView(sub, h.lp(-1, -2))
        addView(msg, h.lpm(-1, -2, 0f, 0, h.m(0.6f), 0, 0))
        addView(nav, h.lpm(-1, h.m(if (h.land) 28f else 30f), 0f, 0, h.m(2f), 0, 0))
        addView(scan.view, h.lpm(-1, -2, 0f, 0, h.m(2f), 0, 0))
    }

    override fun render(s: RadioService?, n: Now) {
        seg.update(n.mode)
        title.text = n.title
        sub.text = if (n.title == n.main + " " + n.unit) n.sub else n.main + " " + n.unit
        msg.text = n.message
        msg.visibility = if (n.message.isEmpty()) View.GONE else View.VISIBLE
        signal.level = n.signal
        btnPlay.set(if (n.playing) Glyph.PAUSE else Glyph.PLAY, Color.WHITE)
        btnFav.set(if (n.fav) Glyph.STAR else Glyph.STAR_O, Color.WHITE)
        nav.visibility = if (n.scanning) View.GONE else View.VISIBLE
        scan.update(n)
    }
}
