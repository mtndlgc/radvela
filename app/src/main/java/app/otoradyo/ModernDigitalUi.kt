package app.otoradyo

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Modern Digital: OLED siyahı, büyük dijital gösterge, canlı ses spektrumu; FM ve DAB+ ayrı vurgu rengi. */
internal class ModernDigitalUi(h: MainActivity) : BaseUi(h) {
    override val wantsSpectrum = true
    private val dabColor = h.accent
    private val fmColor = 0xFFFFA726.toInt()
    private val surf = 0xFF0B1A1C.toInt()
    private val surfP = 0xFF16302F.toInt()
    private val spectrumSrc = { h.service?.spectrum }
    private val live = { h.prefs.animations && h.service?.state?.playing == true && h.service?.state?.muted == false }

    private val seg = ModeSeg(GradientDrawable().apply { setColor(0xFF183033.toInt()); cornerRadius = h.m(3f).toFloat() }, Color.WHITE, 0xFF5F7A7C.toInt(), h.tx(2.7f))
    private val status = h.txt("", h.tx(2f), 0xFF5F7A7C.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val big = h.txt("", h.tx(16f), dabColor).apply { typeface = condensed(); maxLines = 1; gravity = Gravity.CENTER_VERTICAL }
    private val title = h.txt("", h.tx(5.4f), Color.WHITE, true).apply { maxLines = 1 }
    private val info = h.txt("", h.tx(2.4f), 0xFF83A9AB.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val text = h.txt("", h.tx(2.6f), 0xFFCFE4E5.toInt()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val msg = h.txt("", h.tx(2.2f), Pal.WARN).apply { maxLines = 1; visibility = View.GONE }
    private val signal = SignalBars(ctx).apply { accent = dabColor }
    private val level = LevelBar(ctx, spectrumSrc, live)
    private val spectrum = SpectrumView(ctx, spectrumSrc, live)
    private val btnPlay = btn(Glyph.PLAY, Color.BLACK, dabColor, h.alpha(dabColor, 0xCC), h.m(5f), 0.3f, play)
    private val btnFav = btn(Glyph.STAR_O, Color.WHITE, surf, surfP, h.m(4f), 0.3f, fav)
    private val btnRec = btn(Glyph.REC, Pal.DANGER, surf, surfP, h.m(4f), 0.32f) { h.toggleRec() }
    private val btnVol = btn(Glyph.VOLUME, Color.WHITE, surf, surfP, h.m(4f), 0.28f) { h.showVolume() }
    private val scan = ScanPanel(dabColor, 0xFF071113.toInt())
    private val strip = StationStrip(if (h.land) h.wPct(19f) else h.wPct(44f), 0xFF051012.toInt(), surfP, dabColor, h.m(1.6f))

    private val controls = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        val s = h.m(8.2f)
        addView(btn(Glyph.PREV, Color.WHITE, surf, surfP, h.m(4f), 0.3f, prev), h.lpm(s, s, 0f, 0, 0, h.m(1f), 0))
        addView(btnPlay, h.lpm(h.m(10f), h.m(10f), 0f, 0, 0, h.m(1f), 0))
        addView(btn(Glyph.NEXT, Color.WHITE, surf, surfP, h.m(4f), 0.3f, next), h.lpm(s, s, 0f, 0, 0, h.m(1f), 0))
        addView(btnFav, h.lpm(s, s, 0f, 0, 0, h.m(1f), 0))
        addView(btnRec, h.lpm(s, s, 0f, 0, 0, h.m(1f), 0))
        addView(btnVol, h.lp(s, s))
    }

    override val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        setPadding(h.m(2.2f), h.m(1.4f), h.m(2.2f), h.m(1.6f))
        h.autoSize(big, h.mf(6f), h.mf(if (h.land) 18f else 15f))
        h.autoSize(title, h.mf(2.6f), h.mf(5.4f))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(seg.view, h.lp(-2, h.m(5.6f)))
            addView(status, h.lpm(0, -2, 1f, h.m(2f), 0, h.m(1f), 0))
            addView(level, h.lpm(h.m(11f), h.m(1f), 0f, 0, 0, h.m(1.6f), 0))
            addView(signal, h.lpm(h.m(9f), h.m(3.4f), 0f, 0, 0, h.m(1.6f), 0))
            addView(clock(Color.WHITE, h.tx(3f)), h.lpm(-2, -2, 0f, 0, 0, h.m(1.4f), 0))
            addView(btn(Glyph.MENU, Color.WHITE, surf, surfP, h.m(3f), 0.28f) { h.openMenu() }, h.lp(h.m(6.2f), h.m(6.2f)))
        }, h.lp(-1, h.m(6.6f)))

        val names = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, h.lp(-1, h.m(6.4f)))
            addView(info, h.lp(-1, -2))
            addView(text, h.lpm(-1, -2, 0f, 0, h.m(0.6f), 0, 0))
            addView(msg, h.lp(-1, -2))
        }
        if (h.land) {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(big, h.lpm(h.wPct(30f), -1, 0f, 0, 0, h.m(2f), 0))
                addView(names, h.lp(0, -2, 1f))
            }, h.lp(-1, h.m(21f)))
        } else {
            addView(big, h.lp(-1, h.m(18f)))
            addView(names, h.lp(-1, -2))
        }
        addView(spectrum, h.lpm(-1, 0, 1f, 0, h.m(1f), 0, h.m(1f)))
        addView(controls, h.lpm(-1, h.m(10.2f), 0f, 0, 0, 0, h.m(1.2f)))
        addView(scan.view, h.lpm(-1, -2, 0f, 0, 0, 0, h.m(1.2f)))
        addView(strip.view, h.lp(-1, h.m(9f)))
    }

    private var wasLive = false

    override fun render(s: RadioService?, n: Now) {
        val c = if (n.mode == "fm") fmColor else dabColor
        seg.update(n.mode)
        status.text = n.status
        big.setTextColor(c)
        big.text = n.main
        title.text = if (n.title == n.main + " " + n.unit) "" else n.title   // istasyon adı yoksa frekans zaten büyük göstergede
        info.text = (listOf(n.unit, n.sub) + n.chips.drop(1).map { it.first }).filter { it.isNotEmpty() }.joinToString("  ·  ")
        text.text = n.text
        msg.text = n.message
        msg.visibility = if (n.message.isEmpty()) View.GONE else View.VISIBLE
        signal.accent = c
        signal.level = n.signal
        level.color = c
        spectrum.color = c
        val l = live()
        if (l != wasLive) {
            wasLive = l
            spectrum.invalidate()
            level.invalidate()
        }
        btnPlay.set(if (n.playing) Glyph.PAUSE else Glyph.PLAY, Color.BLACK)
        btnFav.set(if (n.fav) Glyph.STAR else Glyph.STAR_O, if (n.fav) c else Color.WHITE)
        btnRec.set(if (n.recording) Glyph.STOP else Glyph.REC, Pal.DANGER)
        btnVol.set(if (n.muted) Glyph.MUTED else Glyph.VOLUME, if (n.muted) c else Color.WHITE)
        controls.visibility = if (n.scanning) View.GONE else View.VISIBLE
        scan.update(n)
        s?.let { strip.update(it, n.mode) }
    }
}
