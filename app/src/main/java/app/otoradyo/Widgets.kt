package app.otoradyo

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.Shader
import android.graphics.RadialGradient
import android.graphics.PixelFormat
import android.graphics.LinearGradient
import android.graphics.ColorFilter
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Arayüz renk paleti. Gece: siyah zemin, koyu kartlar. Gündüz (yalnız Modern tema): açık zemin, beyaz kartlar.
 * Klasik temada gündüz modu yalnız ahşabı açar; kartlar, cam ve krom koyu kalır (WOOD_* renkleri ahşap üstündeki yazılar içindir).
 * apply() her arayüz kurulumunda (buildUi) çağrılır; renkler görünümlere o anda işlenir.
 */
object Pal {
    var night = true; private set
    /** Açık (gündüz) Modern palet kullanımda mı */
    var light = false; private set

    var BG = 0xFF050607.toInt(); private set
    var CARD = 0xFF0E1013.toInt(); private set
    var SURFACE = 0xFF15181C.toInt(); private set
    var SURFACE_PRESSED = 0xFF23272D.toInt(); private set
    var STROKE = 0xFF1E2227.toInt(); private set
    var OFF = 0xFF262A30.toInt(); private set
    var DIM = 0xFF8A9099.toInt(); private set
    var TEXT = 0xFFE8EAED.toInt(); private set
    var FG = 0xFFFFFFFF.toInt(); private set          // başlık / düğme yazısı (koyu zeminde beyaz, açık zeminde koyu)
    var WARN = 0xFFFFB74D.toInt(); private set
    var DANGER = 0xFFFF5252.toInt(); private set
    var TICK = 0xFF3A3F46.toInt(); private set        // FM ölçeği: ince çizgiler
    var AXIS = 0xFF2E3238.toInt(); private set        // FM ölçeği: taban çizgisi
    var MARK = 0xFF6B7078.toInt(); private set        // FM ölçeği: kayıtlı istasyon işareti
    var WARM_TEXT = 0xFFF1E9DD.toInt(); private set   // Klasik tema: cam/koyu yüzey üstünde ılık beyaz
    var WARM_DIM = 0xFFA39A8F.toInt(); private set    // Klasik tema: cam/koyu yüzey üstünde ikincil metin
    var WOOD_TEXT = 0xFFF1E9DD.toInt(); private set   // Klasik tema: doğrudan ahşap üstündeki metin
    var WOOD_DIM = 0xFFA39A8F.toInt(); private set
    var WOOD_WARN = 0xFFFFB74D.toInt(); private set

    fun apply(night: Boolean, classic: Boolean) {
        this.night = night
        light = !night && !classic
        if (light) {
            BG = 0xFFEEF1F5.toInt(); CARD = 0xFFFFFFFF.toInt(); SURFACE = 0xFFE6EAEF.toInt(); SURFACE_PRESSED = 0xFFD5DBE2.toInt()
            STROKE = 0xFFD0D6DD.toInt(); OFF = 0xFFC4CBD4.toInt(); DIM = 0xFF5B6470.toInt(); TEXT = 0xFF1C2026.toInt(); FG = 0xFF11151A.toInt()
            WARN = 0xFFB45F06.toInt(); DANGER = 0xFFD32F2F.toInt()
            TICK = 0xFFC2C9D2.toInt(); AXIS = 0xFFB3BBC5.toInt(); MARK = 0xFF8D95A0.toInt()
        } else {
            BG = 0xFF050607.toInt(); CARD = 0xFF0E1013.toInt(); SURFACE = 0xFF15181C.toInt(); SURFACE_PRESSED = 0xFF23272D.toInt()
            STROKE = 0xFF1E2227.toInt(); OFF = 0xFF262A30.toInt(); DIM = 0xFF8A9099.toInt(); TEXT = 0xFFE8EAED.toInt(); FG = 0xFFFFFFFF.toInt()
            WARN = 0xFFFFB74D.toInt(); DANGER = 0xFFFF5252.toInt()
            TICK = 0xFF3A3F46.toInt(); AXIS = 0xFF2E3238.toInt(); MARK = 0xFF6B7078.toInt()
        }
        val woodDay = classic && !night
        WOOD_TEXT = if (woodDay) 0xFF2B1A0E.toInt() else 0xFFF1E9DD.toInt()
        WOOD_DIM = if (woodDay) 0xFF5A3E28.toInt() else 0xFFA39A8F.toInt()
        WOOD_WARN = if (woodDay) 0xFF7A3E00.toInt() else 0xFFFFB74D.toInt()
    }

    /** Vurgu renk şemaları: kırmızı, amber, yeşil, mavi, camgöbeği */
    val SCHEMES = intArrayOf(0xFFE53935.toInt(), 0xFFFFB300.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF00ACC1.toInt())

    /** Açık zeminde metin olarak okunabilsin diye vurgu rengini koyulaştırır (WCAG kontrastı ~4,5'e kadar). */
    fun readable(accent: Int): Int {
        if (!light) return accent
        fun lin(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
        fun lum(c: Int) = 0.2126 * lin((c shr 16) and 255) + 0.7152 * lin((c shr 8) and 255) + 0.0722 * lin(c and 255)
        var r = (accent shr 16) and 255; var g = (accent shr 8) and 255; var b = accent and 255
        var i = 0
        while (i < 14 && (1.05 / (lum(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b) + 0.05)) < 4.5) {
            r = (r * 0.92).toInt(); g = (g * 0.92).toInt(); b = (b * 0.92).toInt(); i++
        }
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Çizimle yapılmış simgeler. Yazı tipi karakterlerine (▶ ★ ● gibi) güvenmeyiz: araç ekranlarının
 * yazı tiplerinde bu karakterler eksik olabilir ve kutucuk olarak görünür.
 */
enum class Glyph { PLAY, PAUSE, PREV, NEXT, STAR, STAR_O, LIST, MENU, REC, STOP, MINUS, PLUS, BACK, SEARCH, CLOSE, SLIDERS, PULSE, INFO, TRASH, PALETTE, CHECK, GLOBE, CONTRAST, VOLUME, MUTED, MUSIC, SHUFFLE, REPEAT, REPEAT_ONE }

/**
 * Simgelerin 24x24 birimlik uzayda çizimi (GlyphView ve ChromeButton ortak kullanır).
 * Çağıran, tuvali bu 24x24 uzaya taşıyıp ölçekler.
 */
internal object GlyphPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f
    }
    private val path = Path()
    private val rect = RectF()

    private fun star(filled: Boolean, c: Canvas) {
        path.reset()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 10.2f else 4.5f
            val a = -PI / 2 + i * PI / 5
            val x = (12 + r * cos(a)).toFloat()
            val y = (12.8 + r * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, if (filled) fill else line)
    }


    /** Tuvali (0,0)-(24,24) uzayında simge çizer. Yalnız ana (çizim) iş parçacığından çağrılır. */
    fun draw(c: Canvas, glyph: Glyph, tint: Int) {
        fill.color = tint
        line.color = tint
        line.strokeWidth = 2.3f
        when (glyph) {
            Glyph.PLAY -> {
                path.reset(); path.moveTo(7f, 4.5f); path.lineTo(20f, 12f); path.lineTo(7f, 19.5f); path.close()
                c.drawPath(path, fill)
            }
            Glyph.PAUSE -> {
                rect.set(6f, 5f, 10.2f, 19f); c.drawRoundRect(rect, 1f, 1f, fill)
                rect.set(13.8f, 5f, 18f, 19f); c.drawRoundRect(rect, 1f, 1f, fill)
            }
            Glyph.PREV -> {
                rect.set(5f, 5f, 7.6f, 19f); c.drawRoundRect(rect, 1f, 1f, fill)
                path.reset(); path.moveTo(19.5f, 5f); path.lineTo(19.5f, 19f); path.lineTo(8.8f, 12f); path.close()
                c.drawPath(path, fill)
            }
            Glyph.NEXT -> {
                rect.set(16.4f, 5f, 19f, 19f); c.drawRoundRect(rect, 1f, 1f, fill)
                path.reset(); path.moveTo(4.5f, 5f); path.lineTo(4.5f, 19f); path.lineTo(15.2f, 12f); path.close()
                c.drawPath(path, fill)
            }
            Glyph.STAR -> star(true, c)
            Glyph.STAR_O -> star(false, c)
            Glyph.LIST -> {
                for (i in 0 until 3) {
                    val y = 6f + i * 6f
                    c.drawCircle(5f, y, 1.5f, fill)
                    c.drawLine(9f, y, 20f, y, line)
                }
            }
            Glyph.MENU -> {
                for (i in 0 until 3) {
                    val y = 6f + i * 6f
                    c.drawLine(4f, y, 20f, y, line)
                }
            }
            Glyph.REC -> c.drawCircle(12f, 12f, 7.5f, fill)
            Glyph.STOP -> { rect.set(6f, 6f, 18f, 18f); c.drawRoundRect(rect, 2f, 2f, fill) }
            Glyph.MINUS -> c.drawLine(5f, 12f, 19f, 12f, line)
            Glyph.PLUS -> { c.drawLine(5f, 12f, 19f, 12f, line); c.drawLine(12f, 5f, 12f, 19f, line) }
            Glyph.BACK -> {
                path.reset(); path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f)
                c.drawPath(path, line)
            }
            Glyph.SEARCH -> {
                c.drawCircle(10.5f, 10.5f, 6f, line)
                c.drawLine(15f, 15f, 20f, 20f, line)
            }
            Glyph.CLOSE -> { c.drawLine(6f, 6f, 18f, 18f, line); c.drawLine(18f, 6f, 6f, 18f, line) }
            Glyph.SLIDERS -> {
                val ys = floatArrayOf(6f, 12f, 18f)
                val xs = floatArrayOf(8f, 16f, 10f)
                for (i in 0 until 3) {
                    c.drawLine(4f, ys[i], 20f, ys[i], line)
                    c.drawCircle(xs[i], ys[i], 2.6f, fill)
                }
            }
            Glyph.PULSE -> {
                path.reset(); path.moveTo(3f, 12f); path.lineTo(8f, 12f); path.lineTo(10.5f, 5.5f)
                path.lineTo(13.5f, 18.5f); path.lineTo(16f, 12f); path.lineTo(21f, 12f)
                c.drawPath(path, line)
            }
            Glyph.INFO -> {
                c.drawCircle(12f, 12f, 9f, line)
                c.drawCircle(12f, 7.8f, 1.3f, fill)
                c.drawLine(12f, 11f, 12f, 17f, line)
            }
            Glyph.TRASH -> {
                rect.set(6.5f, 8f, 17.5f, 20f); c.drawRoundRect(rect, 1.5f, 1.5f, line)
                c.drawLine(4.5f, 6f, 19.5f, 6f, line)
                c.drawLine(9.5f, 3.8f, 14.5f, 3.8f, line)
            }
            Glyph.PALETTE -> {
                c.drawCircle(12f, 12f, 9f, line)
                c.drawCircle(8.5f, 10f, 1.5f, fill); c.drawCircle(12f, 7.6f, 1.5f, fill)
                c.drawCircle(15.5f, 10f, 1.5f, fill); c.drawCircle(9.5f, 15f, 1.5f, fill)
            }
            Glyph.CONTRAST -> {
                c.drawCircle(12f, 12f, 9f, line)
                path.reset(); path.moveTo(12f, 3f); path.arcTo(3f, 3f, 21f, 21f, -90f, -180f, false); path.close()
                c.drawPath(path, fill)
            }
            Glyph.GLOBE -> {
                c.drawCircle(12f, 12f, 9f, line)
                c.drawLine(3f, 12f, 21f, 12f, line)
                rect.set(7.6f, 3f, 16.4f, 21f); c.drawOval(rect, line)
            }
            Glyph.VOLUME, Glyph.MUTED -> {
                path.reset(); path.moveTo(4f, 9.5f); path.lineTo(8f, 9.5f); path.lineTo(13f, 5f); path.lineTo(13f, 19f); path.lineTo(8f, 14.5f); path.lineTo(4f, 14.5f); path.close()
                c.drawPath(path, fill)
                if (glyph == Glyph.VOLUME) {
                    rect.set(11f, 7.5f, 18f, 16.5f); c.drawArc(rect, -60f, 120f, false, line)
                    rect.set(11f, 4f, 22f, 20f); c.drawArc(rect, -55f, 110f, false, line)
                } else {
                    c.drawLine(16f, 9.5f, 21f, 14.5f, line); c.drawLine(21f, 9.5f, 16f, 14.5f, line)
                }
            }
            Glyph.CHECK -> {
                path.reset(); path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19f, 7f)
                c.drawPath(path, line)
            }
            Glyph.MUSIC -> {
                c.drawLine(9.5f, 17f, 9.5f, 6f, line)
                c.drawLine(19f, 15f, 19f, 4f, line)
                c.drawLine(9.5f, 6f, 19f, 4f, line)
                c.drawCircle(6.8f, 17.2f, 2.8f, fill)
                c.drawCircle(16.3f, 15.2f, 2.8f, fill)
            }
            Glyph.SHUFFLE -> {
                path.reset(); path.moveTo(3.5f, 7f); path.lineTo(7.5f, 7f); path.lineTo(16f, 17f); path.lineTo(20f, 17f)
                path.moveTo(3.5f, 17f); path.lineTo(7.5f, 17f); path.lineTo(16f, 7f); path.lineTo(20f, 7f)
                path.moveTo(17.5f, 4.2f); path.lineTo(20.3f, 7f); path.lineTo(17.5f, 9.8f)
                path.moveTo(17.5f, 14.2f); path.lineTo(20.3f, 17f); path.lineTo(17.5f, 19.8f)
                c.drawPath(path, line)
            }
            Glyph.REPEAT, Glyph.REPEAT_ONE -> {
                path.reset(); path.moveTo(5f, 11f); path.lineTo(5f, 7.5f); path.lineTo(19f, 7.5f)
                path.moveTo(16.2f, 4.7f); path.lineTo(19f, 7.5f); path.lineTo(16.2f, 10.3f)
                path.moveTo(19f, 13f); path.lineTo(19f, 16.5f); path.lineTo(5f, 16.5f)
                path.moveTo(7.8f, 13.7f); path.lineTo(5f, 16.5f); path.lineTo(7.8f, 19.3f)
                c.drawPath(path, line)
                if (glyph == Glyph.REPEAT_ONE) c.drawLine(12f, 10f, 12f, 14f, line)
            }
        }
    }

    fun drawAt(c: Canvas, glyph: Glyph, cx: Float, cy: Float, size: Float, tint: Int) {
        val s = size / 24f
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(s, s)
        draw(c, glyph, tint)
        c.restore()
    }
}

class GlyphView(ctx: Context, glyph: Glyph, tint: Int) : View(ctx) {
    var glyph: Glyph = glyph
        private set
    var tint: Int = tint
        private set

    /** Çizim alanının her kenardan boşluk oranı (0..0,4): düğme boyutuyla birlikte ölçeklenen simgeler için */
    var inset = 0f
        set(v) { field = v; invalidate() }

    fun set(g: Glyph, color: Int) {
        if (g == glyph && color == tint) return
        glyph = g
        tint = color
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val size = min(width, height) * (1f - 2f * inset)
        if (size <= 0f) return
        GlyphPainter.drawAt(c, glyph, width / 2f, height / 2f, size, tint)
    }
}

/** Ana ekrandaki düğmelerin ortak arayüzü (modern ve klasik tema farklı sınıflar kullanır). */
interface Ctl {
    /** Simge, dolu (vurgu rengi) ya da boş durumu ve tonu */
    fun glyph(g: Glyph, filled: Boolean, tint: Int)
    /** Düğmenin alt yazısı ve rengi (yazısız düğmelerde yok sayılır) */
    fun caption(text: String, color: Int)
}

/** Sinyal kalitesi çubukları. level: 0..1 */
class SignalBars(ctx: Context) : View(ctx) {
    var accent: Int = Pal.TEXT
    var level = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c != field) { field = c; invalidate() }
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val n = 10
        val gap = width * 0.035f
        val bw = (width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = height * (0.22f + 0.78f * (i + 1) / n)
            p.color = if ((i + 1).toFloat() / n <= level + 0.001f) accent else Pal.OFF
            val x = i * (bw + gap)
            r.set(x, height - h, x + bw, height.toFloat())
            c.drawRoundRect(r, bw * 0.35f, bw * 0.35f, p)
        }
    }
}

/**
 * FM ayar şeridi: 87,5–108 MHz ölçeği, kayıtlı istasyon işaretleri ve ibre.
 * Parmakla sürüklenince önizleme (onPreview), bırakınca onay (onCommit) verir; 0,1 MHz'e yuvarlanır.
 */
class TunerView(ctx: Context) : View(ctx) {
    companion object {
        const val MIN_HZ = 87_500_000
        const val MAX_HZ = 108_000_000
    }

    var accent: Int = 0xFFE53935.toInt()
        set(v) { if (v != field) { field = v; invalidate() } }
    var freqHz: Int = 100_000_000
        set(v) { if (v != field) { field = v; invalidate() } }
    var markers: List<Int> = emptyList()
        set(v) { if (v != field) { field = v; invalidate() } }
    var favs: Set<Int> = emptySet()
        set(v) { if (v != field) { field = v; invalidate() } }
    var onPreview: ((Int) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null

    private var dragHz = -1
    val dragging: Boolean get() = dragHz >= 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val path = Path()

    private fun padL() = width * 0.025f
    private fun plotW() = width - 2f * padL()
    private fun xOf(hz: Int) = padL() + (hz - MIN_HZ).toFloat() / (MAX_HZ - MIN_HZ) * plotW()
    private fun hzAt(x: Float): Int {
        val f = ((x - padL()) / plotW()).coerceIn(0f, 1f)
        val steps = (f * ((MAX_HZ - MIN_HZ) / 100_000)).roundToInt()
        return MIN_HZ + steps * 100_000
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = h * 0.66f
        val showHz = if (dragHz >= 0) dragHz else freqHz

        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        // Ölçek çizgileri
        for (i in 0..205) {
            val hz = MIN_HZ + i * 100_000
            val x = xOf(hz)
            val major = i % 10 == 0
            val mid = i % 5 == 0
            val len = h * (if (major) 0.26f else if (mid) 0.17f else 0.10f)
            p.color = if (major) Pal.DIM else Pal.TICK
            p.strokeWidth = max(1f, h * (if (major) 0.02f else 0.012f))
            c.drawLine(x, base, x, base - len, p)
        }
        p.color = Pal.AXIS
        p.strokeWidth = max(1.5f, h * 0.018f)
        c.drawLine(padL(), base, w - padL(), base, p)

        // Etiketler (88, 92, ... 108)
        tp.color = Pal.DIM
        tp.textSize = h * 0.17f
        var mhz = 88
        while (mhz <= 108) {
            c.drawText(mhz.toString(), xOf(mhz * 1_000_000), h * 0.95f, tp)
            mhz += 4
        }

        // Kayıtlı istasyon işaretleri
        p.style = Paint.Style.FILL
        val tw = h * 0.06f
        for (m in markers) {
            val x = xOf(m)
            p.color = if (m in favs) accent else Pal.MARK
            path.reset()
            path.moveTo(x - tw, base - h * 0.33f)
            path.lineTo(x + tw, base - h * 0.33f)
            path.lineTo(x, base - h * 0.22f)
            path.close()
            c.drawPath(path, p)
        }

        // İbre
        val nx = xOf(showHz)
        val top = h * 0.10f
        val glow = if (dragHz >= 0) 1.25f else 1f
        p.color = (accent and 0x00FFFFFF) or 0x22000000
        c.drawCircle(nx, top, h * 0.14f * glow, p)
        p.color = (accent and 0x00FFFFFF) or 0x44000000
        c.drawCircle(nx, top, h * 0.10f * glow, p)
        p.color = accent
        c.drawCircle(nx, top, h * 0.065f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(2f, h * 0.035f)
        c.drawLine(nx, top, nx, base + h * 0.02f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val hz = hzAt(e.x)
                if (hz != dragHz) {
                    dragHz = hz
                    onPreview?.invoke(hz)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val hz = if (dragHz >= 0) dragHz else hzAt(e.x)
                dragHz = -1
                invalidate()
                onCommit?.invoke(hz)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragHz = -1
                invalidate()
                onPreview?.invoke(freqHz)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

/** Açma/kapama anahtarı (sistem Switch'inin yerine: boyutu ve rengi araç ekranına göre ayarlanır). */
class ToggleView(ctx: Context) : View(ctx) {
    var accent: Int = 0xFFE53935.toInt()
    var on = false
        set(v) { if (v != field) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = if (on) accent else Pal.OFF
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, h / 2f, h / 2f, p)
        p.color = if (on) Color.BLACK else Pal.DIM
        val kr = h * 0.36f
        val cx = if (on) w - h / 2f else h / 2f
        c.drawCircle(cx, h / 2f, kr, p)
    }
}

/** İçeriği kadar büyür ama en çok maxPx yüksekliğe ulaşır, sonra kaydırılır. */
class MaxScroll(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
    }
}


/**
 * Kontrol bloğu: üstte [önceki] [çal/durdur] [sonraki] daireleri, altında düğme satırları.
 * Ölçüler birim (u) cinsinden istenir; mevcut alana sığmazsa tüm blok orantılı küçülür (bölünmüş ekran, kısa ekran).
 */
class ControlPad(
    ctx: Context,
    private val u: Float,
    private val prev: View,
    private val play: View,
    private val next: View,
    private val rows: List<View>,
    private val bigU: Float,
    private val sideU: Float
) : ViewGroup(ctx) {
    private val row1U = bigU + 1.5f
    private val rowU = 11.5f
    private val gapU = 1.5f
    private val circleGapU = 1.8f
    private var scale = 1f

    init {
        addView(prev); addView(play); addView(next)
        rows.forEach { addView(it) }
    }

    private fun visibleRows() = rows.filter { it.visibility != GONE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val n = visibleRows().size
        val needH = (row1U + n * (rowU + gapU)) * u
        val needW = (2 * sideU + bigU + 2 * circleGapU) * u
        scale = min(1f, min(if (h > 0) h / needH else 1f, if (w > 0) w / needW else 1f))
        val s = u * scale
        fun exact(px: Float) = MeasureSpec.makeMeasureSpec(max(1, px.roundToInt()), MeasureSpec.EXACTLY)
        prev.measure(exact(sideU * s), exact(sideU * s))
        next.measure(exact(sideU * s), exact(sideU * s))
        play.measure(exact(bigU * s), exact(bigU * s))
        for (r in visibleRows()) r.measure(exact(w.toFloat()), exact(rowU * s))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val s = u * scale
        val vis = visibleRows()
        val totalH = (row1U + vis.size * (rowU + gapU)) * s
        var y = (h - totalH) / 2f
        val row1H = row1U * s
        val trioW = (2 * sideU + bigU + 2 * circleGapU) * s
        var x = (w - trioW) / 2f
        fun place(v: View, left: Float, top: Float) = v.layout(left.roundToInt(), top.roundToInt(), left.roundToInt() + v.measuredWidth, top.roundToInt() + v.measuredHeight)
        place(prev, x, y + (row1H - prev.measuredHeight) / 2f)
        x += (sideU + circleGapU) * s
        place(play, x, y + (row1H - play.measuredHeight) / 2f)
        x += (bigU + circleGapU) * s
        place(next, x, y + (row1H - next.measuredHeight) / 2f)
        y += row1H + gapU * s
        for (row in vis) {
            place(row, 0f, y)
            y += (rowU + gapU) * s
        }
    }
}


// =====================================================================================================
// Klasik Analog tema: ahşap zemin, krom çerçeve, analog kadran, krom düğmeler.
// Gradyan ve katman yığınlarıyla çizilir (BlurMaskFilter kullanılmaz: API 26-27'de donanım çiziminde yok sayılır).
// =====================================================================================================

internal object Chrome {
    val COLORS = intArrayOf(0xFFF4F6F8.toInt(), 0xFF9AA0A8.toInt(), 0xFF3A3E44.toInt(), 0xFFB9BEC5.toInt(), 0xFF6B7078.toInt(), 0xFFE4E7EB.toInt())
    val POS = floatArrayOf(0f, 0.22f, 0.5f, 0.72f, 0.9f, 1f)
}

private fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

/**
 * Ahşap zemin, kenarlara vinyet. Kırpılarak tüm alanı kaplar.
 * Gece: koyu ceviz (res/drawable-nodpi/wood_dark.jpg); gündüz: açık meşe (wood_light.jpg, daha hafif vinyet).
 */
class WoodDrawable(private val bmp: Bitmap, private val vigAlpha: Int) : Drawable() {
    companion object {
        @Volatile private var cachedDark: Bitmap? = null
        @Volatile private var cachedLight: Bitmap? = null

        fun create(ctx: Context, night: Boolean): WoodDrawable {
            val cached = if (night) cachedDark else cachedLight
            val b = cached ?: run {
                val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val d = BitmapFactory.decodeResource(ctx.resources, if (night) R.drawable.wood_dark else R.drawable.wood_light, o)
                    ?: Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565).also { it.eraseColor(if (night) 0xFF2A1A10.toInt() else 0xFFB4824E.toInt()) }
                if (night) cachedDark = d else cachedLight = d
                d
            }
            return WoodDrawable(b, if (night) 0x96 else 0x5A)
        }
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val vig = Paint()
    private val dst = RectF()

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        val r = max(b.width(), b.height()) * 0.75f
        vig.shader = RadialGradient(b.exactCenterX(), b.exactCenterY(), max(1f, r),
            intArrayOf(0x00000000, vigAlpha shl 24), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val s = max(w / bmp.width, h / bmp.height)
        val dw = bmp.width * s
        val dh = bmp.height * s
        dst.set(b.left + (w - dw) / 2f, b.top + (h - dh) / 2f, b.left + (w + dw) / 2f, b.top + (h + dh) / 2f)
        c.drawBitmap(bmp, null, dst, p)
        c.drawRect(b, vig)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Krom çerçeveli koyu cam panel. İçerik, cam alanına yerleşir (iç boşluk bu sınıfça verilir).
 * Dışarı taşan gölge için kendi sınırları içinde pay bırakılır.
 */
class ChromeFrame(ctx: Context, private val bezel: Float, private val radius: Float) : FrameLayout(ctx) {
    private val shadow = bezel * 0.9f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer = RectF()
    private val inner = RectF()
    private val glass = RectF()
    private var chromeShader: Shader? = null
    private var glassShader: Shader? = null
    private var hiShader: Shader? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        val pad = (shadow + bezel).roundToInt()
        setPadding(pad, pad, pad, pad)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        outer.set(shadow, shadow, w - shadow, h - shadow)
        inner.set(outer.left + bezel * 0.55f, outer.top + bezel * 0.55f, outer.right - bezel * 0.55f, outer.bottom - bezel * 0.55f)
        glass.set(outer.left + bezel, outer.top + bezel, outer.right - bezel, outer.bottom - bezel)
        chromeShader = LinearGradient(outer.left, outer.top, outer.right, outer.bottom, Chrome.COLORS, Chrome.POS, Shader.TileMode.CLAMP)
        glassShader = RadialGradient(glass.centerX(), glass.bottom, max(1f, glass.width() * 0.55f),
            intArrayOf(0xFF2A1D12.toInt(), 0xFF120C08.toInt(), 0xFF070607.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        hiShader = LinearGradient(0f, glass.top, 0f, glass.top + glass.height() * 0.42f, 0x1EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        if (outer.isEmpty) return
        // Yumuşak gölge: genişleyen, soluk katmanlar
        p.shader = null
        p.style = Paint.Style.FILL
        for (i in 4 downTo 1) {
            val g = shadow * i / 4f
            p.color = withAlpha(0xFF000000.toInt(), 34)
            c.drawRoundRect(outer.left - g * 0.4f, outer.top - g * 0.2f + shadow * 0.35f, outer.right + g * 0.4f, outer.bottom + g * 0.9f, radius + g * 0.4f, radius + g * 0.4f, p)
        }
        p.shader = chromeShader
        c.drawRoundRect(outer, radius, radius, p)
        p.shader = null
        p.color = withAlpha(0xFF000000.toInt(), 170)
        c.drawRoundRect(inner, max(2f, radius - bezel * 0.5f), max(2f, radius - bezel * 0.5f), p)
        val gr = max(2f, radius - bezel)
        p.shader = glassShader
        c.drawRoundRect(glass, gr, gr, p)
        p.shader = hiShader
        c.drawRoundRect(glass, gr, gr, p)
        p.shader = null
    }
}

private val DAB_MHZ: Map<String, Double> = mapOf(
    "5A" to 174.928, "5B" to 176.640, "5C" to 178.352, "5D" to 180.064, "6A" to 181.936, "6B" to 183.648, "6C" to 185.360, "6D" to 187.072,
    "7A" to 188.928, "7B" to 190.640, "7C" to 192.352, "7D" to 194.064, "8A" to 195.936, "8B" to 197.648, "8C" to 199.360, "8D" to 201.072,
    "9A" to 202.928, "9B" to 204.640, "9C" to 206.352, "9D" to 208.064, "10A" to 209.936, "10B" to 211.648, "10C" to 213.360, "10D" to 215.072,
    "11A" to 216.928, "11B" to 218.640, "11C" to 220.352, "11D" to 222.064, "12A" to 223.936, "12B" to 225.648, "12C" to 227.360, "12D" to 229.072,
    "13A" to 230.784, "13B" to 232.496, "13C" to 234.208, "13D" to 235.776, "13E" to 237.488, "13F" to 239.200
)

/**
 * Analog kadran: yarım daire ölçek, parlayan ibre ve krom düğme.
 * FM'de 87,5–108 MHz ölçeği (parmakla sürüklenir, 0,1 MHz adımlarla), DAB+'ta Band III blokları (5A–13F).
 * İbre hedef açıya yumuşak yaklaşır.
 */
class ClassicDial(ctx: Context) : View(ctx) {
    companion object {
        private const val SWEEP = 75f          // dikeyden ± derece
        private const val FM_MIN = 87.5
        private const val FM_MAX = 108.0
        private const val DAB_MIN = 174.0
        private const val DAB_MAX = 240.0
    }

    var accent: Int = 0xFFFFB300.toInt()
        set(v) { if (v != field) { field = v; invalidate() } }
    var dabMode = false
        set(v) { if (v != field) { field = v; retarget(true); invalidate() } }
    var freqHz: Int = 100_000_000
        set(v) { if (v != field) { field = v; retarget(false); invalidate() } }
    var dabChannel: String = ""
        set(v) { if (v != field) { field = v; retarget(false); invalidate() } }
    var markers: List<Int> = emptyList()
        set(v) { if (v != field) { field = v; invalidate() } }
    var favs: Set<Int> = emptySet()
        set(v) { if (v != field) { field = v; invalidate() } }
    var dabHave: Set<String> = emptySet()
        set(v) { if (v != field) { field = v; invalidate() } }
    var onPreview: ((Int) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null
    val dragging: Boolean get() = dragHz >= 0

    /** false: ibre yeni konuma anında geçer (animasyon ayarı). */
    var animate = true
    private var dragHz = -1
    private var target = -SWEEP
    private var shown = -SWEEP
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private val tmp = FloatArray(2)
    private val labelFace: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    private fun fmAngle(mhz: Double) = (-SWEEP + (mhz - FM_MIN) / (FM_MAX - FM_MIN) * 2 * SWEEP).toFloat()
    private fun dabAngle(mhz: Double) = (-SWEEP + (mhz - DAB_MIN) / (DAB_MAX - DAB_MIN) * 2 * SWEEP).toFloat()

    private fun retarget(snap: Boolean) {
        target = when {
            dragHz >= 0 -> fmAngle(dragHz / 1e6)
            dabMode -> DAB_MHZ[dabChannel]?.let { dabAngle(it) } ?: -SWEEP
            else -> fmAngle(freqHz / 1e6)
        }.coerceIn(-SWEEP, SWEEP)
        if (snap || dragHz >= 0 || width == 0) shown = target
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val h = if (hMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec)
        else (w * 0.56f).roundToInt().let { if (hMode == MeasureSpec.AT_MOST) min(it, MeasureSpec.getSize(heightMeasureSpec)) else it }
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        radius = min(w / 2.12f, h / 1.185f)
        cx = w / 2f
        cy = h - radius * 0.1208f
        retarget(true)   // frekans varsayılan değere eşitse ayarlayıcı tetiklenmez: ibre burada yerine oturur
    }

    private fun pt(a: Float, r: Float) {
        val t = Math.toRadians(a.toDouble())
        tmp[0] = (cx + r * sin(t)).toFloat()
        tmp[1] = (cy - r * cos(t)).toFloat()
    }

    private fun line(a: Float, r1: Float, r2: Float, color: Int, w: Float) {
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.color = color; p.strokeWidth = w
        pt(a, r1); val x1 = tmp[0]; val y1 = tmp[1]
        pt(a, r2)
        pathCanvas?.drawLine(x1, y1, tmp[0], tmp[1], p)
    }

    private var pathCanvas: Canvas? = null

    private fun label(text: String, a: Float, r: Float, size: Float, color: Int) {
        pt(a, r)
        val c = pathCanvas ?: return
        p.shader = null; p.style = Paint.Style.FILL; p.color = color; p.textSize = size; p.typeface = labelFace
        val w = p.measureText(text)
        c.save()
        c.translate(tmp[0], tmp[1])
        c.rotate(a)
        c.drawText(text, -w / 2f, size * 0.35f, p)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val R = radius
        if (R <= 0f) return
        pathCanvas = c
        // ibre animasyonu
        val diff = target - shown
        var animating = false
        if (animate && abs(diff) > 0.04f) { shown += diff * 0.25f; animating = true } else shown = target
        val a0 = shown

        val warm = 0xFFE6DCCB.toInt()
        // ışık huzmesi
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, R, intArrayOf(withAlpha(accent, 70), withAlpha(accent, 0)), floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP)
        oval.set(cx - R, cy - R, cx + R, cy + R)
        path.reset(); path.moveTo(cx, cy); path.arcTo(oval, -90f + a0 - 14f, 28f, false); path.close()
        c.drawPath(path, p)
        p.shader = null

        // dış yay
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT; p.color = withAlpha(0xFFD8DCE0.toInt(), 170); p.strokeWidth = max(1.5f, R * 0.012f)
        oval.set(cx - R * 0.93f, cy - R * 0.93f, cx + R * 0.93f, cy + R * 0.93f)
        c.drawArc(oval, -90f - SWEEP, 2 * SWEEP, false, p)

        if (dabMode) drawDabScale(R, a0, warm) else drawFmScale(R, a0, warm)

        // ibre: yumuşak ışıma (üst üste çizgiler), sonra gövde
        pt(a0, R * 0.90f); val tx = tmp[0]; val ty = tmp[1]
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.color = withAlpha(accent, 34); p.strokeWidth = R * 0.055f; c.drawLine(cx, cy, tx, ty, p)
        p.color = withAlpha(accent, 56); p.strokeWidth = R * 0.035f; c.drawLine(cx, cy, tx, ty, p)
        pt(a0 - 90f, R * 0.016f); val b1x = tmp[0]; val b1y = tmp[1]
        pt(a0 + 90f, R * 0.016f); val b2x = tmp[0]; val b2y = tmp[1]
        path.reset(); path.moveTo(tx, ty); path.lineTo(b1x, b1y); path.lineTo(b2x, b2y); path.close()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(cx, cy, tx, ty, intArrayOf(0xFFFFFFFF.toInt(), accent, accent), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null

        // krom düğme (hub)
        val hr = R * 0.105f
        p.style = Paint.Style.FILL; p.color = withAlpha(0xFF000000.toInt(), 150)
        c.drawCircle(cx, cy + hr * 0.15f, hr * 1.18f, p)
        p.shader = RadialGradient(cx - hr * 0.35f, cy - hr * 0.4f, hr * 1.5f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFBFC4CB.toInt(), 0xFF5E636A.toInt(), 0xFF2C2F33.toInt()), floatArrayOf(0f, 0.35f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, hr, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.color = 0xFF1A1C1F.toInt(); p.strokeWidth = max(1f, hr * 0.06f)
        c.drawCircle(cx, cy, hr, p)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx - hr * 0.2f, cy - hr * 0.25f, hr * 0.8f, intArrayOf(0xFFF2F4F6.toInt(), 0xFF8C9198.toInt()), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, hr * 0.55f, p)
        p.shader = null
        pathCanvas = null
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawFmScale(R: Float, a0: Float, warm: Int) {
        val shownMhz = FM_MIN + (a0 + SWEEP) / (2 * SWEEP) * (FM_MAX - FM_MIN)
        // iç ince yay
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT
        p.color = withAlpha(accent, 120); p.strokeWidth = max(1f, R * 0.008f)
        oval.set(cx - R * 0.52f, cy - R * 0.52f, cx + R * 0.52f, cy + R * 0.52f)
        pathCanvas!!.drawArc(oval, -90f - SWEEP, 2 * SWEEP, false, p)
        // ana ölçek: 0,5 MHz çizgileri (i=1 -> 88,0 MHz; her 4 çizgide bir ana çizgi)
        for (i in 0..41) {
            val f = FM_MIN + i * 0.5
            val a = fmAngle(f)
            val major = (i - 1) % 4 == 0
            val whole = (i - 1) % 2 == 0
            val r2 = R * (if (major) 0.80f else if (whole) 0.855f else 0.885f)
            val lit = abs(f - shownMhz) < 0.6
            val col = if (lit) withAlpha(accent, 235) else if (major) warm else withAlpha(warm, 150)
            line(a, R * 0.93f, r2, col, max(1.2f, R * (if (major) 0.011f else 0.006f)))
        }
        // etiketler (4 MHz'de bir, büyük ve okunaklı)
        var f = 88
        while (f <= 108) {
            label(f.toString(), fmAngle(f.toDouble()), R * 0.68f, R * 0.118f, withAlpha(0xFFF1E9DD.toInt(), 235))
            f += 4
        }
        // iç ince çizgiler
        var i = 0
        while (i < 206) {
            line(fmAngle(FM_MIN + i * 0.1), R * 0.52f, R * 0.47f, withAlpha(accent, 110), max(1f, R * 0.006f))
            i += 5
        }
        // kayıtlı istasyon işaretleri (halkanın dışında, içe bakan küçük üçgenler)
        p.shader = null; p.style = Paint.Style.FILL
        for (m in markers) {
            val mh = m / 1e6
            if (mh < FM_MIN || mh > FM_MAX) continue
            val a = fmAngle(mh)
            pt(a, R * 0.975f); val x = tmp[0]; val y = tmp[1]
            pt(a - 1.6f, R * 1.035f); val xa = tmp[0]; val ya = tmp[1]
            pt(a + 1.6f, R * 1.035f); val xb = tmp[0]; val yb = tmp[1]
            path.reset(); path.moveTo(x, y); path.lineTo(xa, ya); path.lineTo(xb, yb); path.close()
            p.color = if (m in favs) accent else withAlpha(0xFFC9BFB0.toInt(), 170)
            pathCanvas!!.drawPath(path, p)
        }
    }

    private fun drawDabScale(R: Float, a0: Float, warm: Int) {
        for ((name, mhz) in DAB_MHZ) {
            val a = dabAngle(mhz)
            val isA = name.endsWith("A")
            val cur = name == dabChannel
            val has = name in dabHave
            val r2 = R * (if (isA) 0.80f else 0.86f)
            val col = when {
                cur -> withAlpha(accent, 240)
                has -> accent
                isA -> warm
                else -> withAlpha(warm, 140)
            }
            val w = max(1.2f, R * (if (cur || has) 0.016f else if (isA) 0.011f else 0.007f))
            line(a, R * 0.93f, r2, col, w)
            if (isA) label(name.dropLast(1), a, R * 0.69f, R * 0.085f, withAlpha(0xFFF1E9DD.toInt(), 235))
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dabMode) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val d = hypot(e.x - cx, e.y - cy)
                if (d < radius * 0.2f || d > radius * 1.25f) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e)
                return true
            }
            MotionEvent.ACTION_MOVE -> { update(e); return true }
            MotionEvent.ACTION_UP -> {
                val hz = if (dragHz >= 0) dragHz else freqHz
                dragHz = -1
                freqHz = hz              // ibre eski frekansa geri sıçramasın; servis onaylayınca zaten aynı değer gelir
                onCommit?.invoke(hz)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragHz = -1
                retarget(true)
                onPreview?.invoke(freqHz)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun update(e: MotionEvent) {
        val ang = Math.toDegrees(atan2((e.x - cx).toDouble(), (cy - e.y).toDouble())).toFloat().coerceIn(-SWEEP, SWEEP)
        val mhz = FM_MIN + (ang + SWEEP) / (2 * SWEEP) * (FM_MAX - FM_MIN)
        val steps = Math.round((mhz - FM_MIN) / 0.1).toInt()
        val hz = 87_500_000 + steps * 100_000
        if (hz != dragHz) {
            dragHz = hz
            retarget(true)
            onPreview?.invoke(hz)
            invalidate()
        }
    }
}

/** Krom halkalı yuvarlak düğme; simge, isteğe bağlı alt yazı. active: vurgu renginde dolu. */
class ChromeButton(ctx: Context, glyph: Glyph?, private var captionText: String?, private val captionPx: Float, private val big: Boolean) : View(ctx), Ctl {
    var accent: Int = 0xFFFFB300.toInt()
        set(v) { if (v != field) { field = v; invalidate() } }
    private var glyph: Glyph? = glyph
    private var active = false
    private var tint = 0xFFF1E9DD.toInt()
    private var captionColor = 0xFFA39A8F.toInt()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun glyph(g: Glyph, filled: Boolean, tint: Int) {
        val t = if (tint == Color.WHITE) 0xFFF1E9DD.toInt() else tint   // klasik tema: ılık beyaz
        if (g == glyph && filled == active && t == this.tint) return
        glyph = g; active = filled; this.tint = t
        invalidate()
    }

    override fun caption(text: String, color: Int) {
        if (text == captionText && color == captionColor) return
        captionText = text; captionColor = color
        invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cap = captionText
        val capSpace = if (cap != null && !big) captionPx * 1.5f else 0f
        var d = min(w * (if (big) 0.96f else 0.88f), (h - capSpace) * (if (big) 0.96f else 0.92f))
        if (isPressed) d *= 0.95f
        if (d <= 2f) return
        val r = d / 2f
        val top = (h - (d + capSpace)) / 2f
        val cx = w / 2f
        val cy = top + r

        // gölge
        p.shader = null; p.style = Paint.Style.FILL
        p.color = withAlpha(0xFF000000.toInt(), 120); c.drawCircle(cx, cy + r * 0.10f, r * 1.04f, p)
        // aktifse ışıma
        if (active) {
            p.shader = RadialGradient(cx, cy, r * 1.55f, intArrayOf(withAlpha(accent, 110), withAlpha(accent, 0)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 1.55f, p)
        }
        // krom halka
        p.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, Chrome.COLORS, Chrome.POS, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        // yüz
        val fr = r * 0.86f
        p.shader = if (active)
            RadialGradient(cx - r * 0.2f, cy - r * 0.3f, r * 1.3f, intArrayOf(accent, withAlpha(accent, 215)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        else
            RadialGradient(cx - r * 0.25f, cy - r * 0.35f, r * 1.4f, intArrayOf(0xFF3A3630.toInt(), 0xFF1C1A17.toInt(), 0xFF0D0C0B.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, fr, p)
        p.shader = null
        if (isPressed) { p.color = withAlpha(0xFFFFFFFF.toInt(), 36); c.drawCircle(cx, cy, fr, p) }
        glyph?.let { GlyphPainter.drawAt(c, it, cx, cy, fr * 1.0f, if (active) 0xFF0B0A09.toInt() else tint) }
        // alt yazı
        if (cap != null && !big) {
            p.style = Paint.Style.FILL; p.color = captionColor; p.textSize = captionPx
            p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            p.textAlign = Paint.Align.CENTER
            var t = cap
            while (p.measureText(t) > w * 1.02f && p.textSize > captionPx * 0.6f) p.textSize -= 1f
            c.drawText(t, cx, top + d + captionPx * 1.1f, p)
            p.textAlign = Paint.Align.LEFT
        }
    }
}

/**
 * İstasyon simgesi: logo varsa açık renkli zeminde logo, yoksa renkli baş harf rozeti.
 */
class BadgeView(ctx: Context) : View(ctx) {
    var label: String = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    var color: Int = 0xFF2B7A8C.toInt()
        set(v) { if (v != field) { field = v; invalidate() } }
    var logo: Bitmap? = null
        set(v) { if (v !== field) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val d = RectF()

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 2f) return
        r.set((width - s) / 2f, (height - s) / 2f, (width + s) / 2f, (height + s) / 2f)
        val lg = logo
        if (lg != null && lg.width > 0 && lg.height > 0) {
            p.style = Paint.Style.FILL; p.color = 0xFFF4F2EF.toInt()
            c.drawRoundRect(r, s * 0.22f, s * 0.22f, p)
            val box = s * 0.84f
            val k = min(box / lg.width, box / lg.height)
            val w = lg.width * k
            val h = lg.height * k
            d.set(r.centerX() - w / 2f, r.centerY() - h / 2f, r.centerX() + w / 2f, r.centerY() + h / 2f)
            c.drawBitmap(lg, null, d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, s * 0.025f); p.color = withAlpha(0xFF000000.toInt(), 50)
            c.drawRoundRect(r, s * 0.22f, s * 0.22f, p)
            return
        }
        p.style = Paint.Style.FILL; p.color = color
        c.drawRoundRect(r, s * 0.26f, s * 0.26f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, s * 0.025f); p.color = withAlpha(0xFFFFFFFF.toInt(), 60)
        c.drawRoundRect(r, s * 0.26f, s * 0.26f, p)
        p.style = Paint.Style.FILL; p.color = 0xFFFFF4E6.toInt(); p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        p.textSize = s * (if (label.length > 2) 0.34f else 0.42f)
        c.drawText(label, r.centerX(), r.centerY() + p.textSize * 0.35f, p)
        p.textAlign = Paint.Align.LEFT
    }
}
