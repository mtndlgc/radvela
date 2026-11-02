package app.otoradyo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView

/** Ana ekranın ihtiyaç duyduğu tüm veriler; radyo durumundan üretilir, temalar yalnız bunu görür. */
internal class Now(
    val mode: String,
    val title: String,
    val main: String,          // "105.00" ya da "12A"
    val unit: String,          // "MHz" ya da "DAB+"
    val sub: String,
    val text: String,          // RadioText / DLS
    val chips: List<Pair<String, Boolean>>,
    val signal: Float,
    val playing: Boolean,
    val fav: Boolean,
    val key: String,
    val logoName: String,
    val slide: Bitmap?,
    val recording: Boolean,
    val muted: Boolean,
    val scanning: Boolean,
    val scanLine: String,
    val scanPct: Int,
    val message: String,
    val status: String,
    val connected: Boolean,
)

/** Bir tema: kök görünümü kurar ve durumu çizer. Radyo komutları yalnız RadioService üzerinden gider. */
internal interface RadioUi {
    val root: LinearLayout
    val wantsSpectrum: Boolean get() = false
    fun render(s: RadioService?, n: Now)
}

internal class IconButton(
    ctx: Context, glyph: Glyph, tint: Int, bg: Drawable?, inset: Float = 0.28f, click: () -> Unit
) : FrameLayout(ctx) {
    private val icon = GlyphView(ctx, glyph, tint).apply { this.inset = inset }

    init {
        addView(icon, LayoutParams(-1, -1))
        background = bg
        isClickable = true
        isFocusable = true
        contentDescription = glyph.name
        setOnClickListener { click() }
    }

    fun set(g: Glyph, color: Int) = icon.set(g, color)
}

/** Temalarda ortak parçalar. */
internal abstract class BaseUi(protected val h: MainActivity) : RadioUi {
    protected val ctx: Context get() = h

    protected fun btn(g: Glyph, tint: Int, fill: Int, press: Int, radius: Int, inset: Float = 0.28f, click: () -> Unit) =
        IconButton(ctx, g, tint, h.pressable(h.rr(fill, radius), h.rr(press, radius)), inset, click)

    protected fun clock(color: Int, size: Float) = TextClock(ctx).apply {
        format24Hour = "HH:mm"
        format12Hour = "HH:mm"
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        setTextColor(color)
    }

    /** FM / DAB+ seçici. */
    inner class ModeSeg(private val onBg: Drawable?, private val onFg: Int, private val offFg: Int, private val size: Float) {
        private fun seg(t: String, mode: String) = h.txt(t, size, offFg, true).apply {
            gravity = Gravity.CENTER
            setPadding(h.m(3f), 0, h.m(3f), 0)
            setOnClickListener { h.selectMode(mode) }
        }
        private val fm = seg("FM", "fm")
        private val dab = seg("DAB+", "dab")
        private val music = seg(h.getString(R.string.music_seg), "music")
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(fm, h.lp(-2, -1))
            addView(dab, h.lp(-2, -1))
            addView(music, h.lp(-2, -1))
        }
        private var last = ""

        fun update(mode: String) {
            if (mode == last) return
            last = mode
            fm.background = if (mode == "fm") onBg?.constantState?.newDrawable()?.mutate() else null
            dab.background = if (mode == "dab") onBg?.constantState?.newDrawable()?.mutate() else null
            music.background = if (mode == "music") onBg?.constantState?.newDrawable()?.mutate() else null
            fm.setTextColor(if (mode == "fm") onFg else offFg)
            dab.setTextColor(if (mode == "dab") onFg else offFg)
            music.setTextColor(if (mode == "music") onFg else offFg)
        }
    }

    /** Tarama ilerlemesi ve durdurma; düzgün çalışan tarama görünümü her temada aynıdır. */
    inner class ScanPanel(color: Int, bg: Int) {
        private val text = h.txt("", h.tx(2.8f), Color.WHITE, true).apply { maxLines = 1 }
        private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(color)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF2A2A2A.toInt())
        }
        private val stop = btn(Glyph.CLOSE, Color.WHITE, 0xFF262626.toInt(), 0xFF3A3A3A.toInt(), h.m(2f), 0.26f) { h.service?.cancelScan() }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(h.m(2f), h.m(1f), h.m(2f), h.m(1f))
            background = h.rr(bg, h.m(2f))
            visibility = View.GONE
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text, h.lp(-1, -2))
                addView(bar, h.lpm(-1, h.m(1.6f), 0f, 0, h.m(1f), 0, 0))
            }
            addView(col, h.lp(0, -2, 1f))
            addView(stop, h.lpm(h.m(8f), h.m(8f), 0f, h.m(2f), 0, 0, 0))
        }

        fun update(n: Now) {
            view.visibility = if (n.scanning) View.VISIBLE else View.GONE
            if (n.scanning) {
                text.text = n.scanLine
                bar.progress = n.scanPct
            }
        }
    }

    /** Favori / hazır istasyon şeridi. */
    inner class StationStrip(
        private val tileW: Int,
        private val normal: Int,
        private val pressed: Int,
        private val onColor: Int,
        private val radius: Int,
    ) {
        private val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val view = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, FrameLayout.LayoutParams(-2, -1))
        }
        private var sig = ""

        fun update(s: RadioService, mode: String) {
            val items = h.presetItems(s, mode)
            val next = mode + Logos.version + items.joinToString(";") { it.key + it.title + it.current + it.fav }
            if (next == sig) return
            sig = next
            row.removeAllViews()
            if (items.isEmpty()) {
                row.addView(h.txt(h.getString(if (mode == "dab") R.string.empty_dab else R.string.empty_fm), h.tx(2.4f), 0xFF9A9A9A.toInt()).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 2
                }, h.lp(h.winW - h.m(5f), -1))
                return
            }
            var cur: View? = null
            for (r in items) {
                val t = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(h.m(1.2f), 0, h.m(1.2f), 0)
                    isClickable = true
                    background = h.pressable(
                        h.rr(normal, radius, if (r.current) onColor else 0, h.m(0.28f)),
                        h.rr(pressed, radius, if (r.current) onColor else 0, h.m(0.28f))
                    )
                    setOnClickListener { r.onClick() }
                    setOnLongClickListener { r.onLong?.invoke(); true }
                    addView(BadgeView(ctx).apply {
                        label = h.initialsOf(r.title); color = h.badgeColor(r.title); logo = h.logoFor(r.logoName)
                    }, h.lpm(h.m(6f), h.m(6f), 0f, 0, 0, h.m(1f), 0))
                    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(h.txt(r.title, h.tx(2.6f), Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    col.addView(h.txt(r.subtitle, h.tx(1.9f), 0xFF9A9A9A.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(col, h.lp(0, -2, 1f))
                }
                row.addView(t, h.lpm(tileW, -1, 0f, 0, 0, h.m(1f), 0))
                if (r.current) cur = t
            }
            cur?.let { c -> view.post { view.smoothScrollTo(maxOf(0, c.left - (h.winW - tileW) / 2), 0) } }
        }
    }

    protected fun logoFor(n: Now): Bitmap? = n.slide ?: h.logoFor(n.logoName)
    protected val prev: () -> Unit = { h.service?.seek(-1) }
    protected val next: () -> Unit = { h.service?.seek(+1) }
    protected val play: () -> Unit = { h.service?.togglePlay() }
    protected val fav: () -> Unit = { h.service?.let { it.toggleFavorite(it.currentKey()) } }
}

/** Çıkan sesin spektrum çubukları. Canlıyken kendini yeniler; durunca taban çizgisi kalır. */
internal class SpectrumView(ctx: Context, private val source: () -> AudioSpectrum?, private val live: () -> Boolean) : View(ctx) {
    var color = 0xFF3DE0D0.toInt()
        set(v) { if (v != field) { field = v; shader = null; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var shader: Shader? = null

    override fun onDraw(c: Canvas) {
        val lv = source()?.levels ?: return
        if (shader == null) shader = LinearGradient(0f, height.toFloat(), 0f, 0f, color, 0xFF2B6CFF.toInt(), Shader.TileMode.CLAMP)
        p.shader = shader
        val n = lv.size
        val gap = width * 0.006f
        val w = (width - gap * (n - 1)) / n
        val on = live()
        for (i in 0 until n) {
            val v = if (on) lv[i] else 0f
            val bh = kotlin.math.max(height * 0.02f, height * v)
            val x = i * (w + gap)
            r.set(x, height - bh, x + w, height.toFloat())
            c.drawRoundRect(r, w * 0.2f, w * 0.2f, p)
        }
        if (on) postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { shader = null }
}

/** Ses seviyesi çizgisi (çıkan sesin anlık gücü). */
internal class LevelBar(ctx: Context, private val source: () -> AudioSpectrum?, private val live: () -> Boolean) : View(ctx) {
    var color = 0xFF3DE0D0.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val on = live()
        p.color = 0xFF10262A.toInt()
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(r, height / 2f, height / 2f, p)
        p.color = color
        val v = if (on) ((source()?.rms ?: 0f) * 4f).coerceIn(0f, 1f) else 0f
        r.set(0f, 0f, width * v, height.toFloat())
        c.drawRoundRect(r, height / 2f, height / 2f, p)
        if (on) postInvalidateOnAnimation()
    }
}

/** Tema Galerisi küçük önizlemesi: temanın ana düzenini basitçe çizer. */
internal class ThemePreview(ctx: Context, private val id: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private fun box(c: Canvas, x: Float, y: Float, w: Float, hh: Float, color: Int, rad: Float = 0.015f) {
        p.color = color
        r.set(x * width, y * height, (x + w) * width, (y + hh) * height)
        c.drawRoundRect(r, rad * width, rad * width, p)
    }

    override fun onDraw(c: Canvas) {
        when (id) {
            ThemeManager.OEM -> {
                c.drawColor(0xFF111214.toInt())
                box(c, 0.05f, 0.08f, 0.2f, 0.05f, 0xFFD8202F.toInt())
                box(c, 0.05f, 0.24f, 0.5f, 0.2f, 0xFFEFEFEF.toInt())
                box(c, 0.05f, 0.5f, 0.3f, 0.06f, 0xFF77797C.toInt())
                box(c, 0.66f, 0.2f, 0.28f, 0.42f, 0xFF26282B.toInt())
                for (i in 0 until 4) box(c, 0.05f + i * 0.23f, 0.74f, 0.21f, 0.16f, 0xFF232528.toInt())
            }
            ThemeManager.MODERN -> {
                c.drawColor(Color.BLACK)
                box(c, 0.06f, 0.1f, 0.38f, 0.22f, 0xFF3DE0D0.toInt())
                box(c, 0.5f, 0.14f, 0.3f, 0.06f, Color.WHITE)
                for (i in 0 until 24) {
                    val hh = 0.08f + 0.3f * Math.abs(Math.sin(i * 0.7)).toFloat()
                    box(c, 0.06f + i * 0.037f, 0.74f - hh, 0.026f, hh, 0xFF2BB7D8.toInt(), 0f)
                }
                for (i in 0 until 4) box(c, 0.06f + i * 0.225f, 0.8f, 0.2f, 0.12f, 0xFF0B2224.toInt())
            }
            ThemeManager.CLASSIC -> {
                c.drawColor(0xFF1E1D1B.toInt())
                p.style = Paint.Style.STROKE
                p.strokeWidth = height * 0.018f
                p.color = 0xFFE9D3A6.toInt()
                val rad = height * 0.56f
                r.set(width / 2f - rad, height * 0.72f - rad, width / 2f + rad, height * 0.72f + rad)
                c.drawArc(r, 200f, 140f, false, p)
                p.style = Paint.Style.FILL
                p.color = 0xFFF0A431.toInt()
                p.strokeWidth = height * 0.02f
                c.drawLine(width / 2f, height * 0.72f, width * 0.72f, height * 0.24f, p)
                c.drawCircle(width / 2f, height * 0.72f, height * 0.05f, p)
            }
            ThemeManager.MINIMAL -> {
                c.drawColor(0xFF050505.toInt())
                box(c, 0.14f, 0.2f, 0.72f, 0.26f, Color.WHITE)
                for (i in 0 until 3) box(c, 0.05f + i * 0.31f, 0.62f, 0.28f, 0.28f, 0xFF232323.toInt())
            }
            else -> {
                c.drawColor(0xFF101114.toInt())
                for (y in 0 until 3) for (x in 0 until 4)
                    box(c, 0.05f + x * 0.23f, 0.08f + y * 0.26f, 0.21f, 0.22f, if (x == 0 && y == 0) 0xFF7DA7FF.toInt() else 0xFF22252B.toInt())
                box(c, 0.05f, 0.87f, 0.9f, 0.08f, 0xFF22252B.toInt())
            }
        }
    }
}

internal fun condensed(): Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
internal fun lightFace(): Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
