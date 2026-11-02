package app.otoradyo

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Radvela arayüzü (araç ekranı odaklı): siyah zemin, koyu kartlar, büyük frekans, hazır istasyon düğmeleri,
 * ayar şeridi, büyük dokunma hedefleri. Yatay, dikey ve yarım ekrana (bölünmüş ekran) uyum sağlar.
 * Tüm iş RadioService'tedir; burası yalnız gösterim ve komuttur.
 */
class MainActivity : Activity(), RadioService.Ui {

    private enum class Page { MAIN, LIST, MENU }

    private val schemes = Pal.SCHEMES
    private val schemeIds = intArrayOf(R.string.color_red, R.string.color_amber, R.string.color_green, R.string.color_blue, R.string.color_cyan)
    private fun schemeName(i: Int): String = getString(schemeIds[i.coerceIn(0, schemeIds.size - 1)])

    /** Biçimli metin kaynağı; sayılar dilden bağımsız (Latin rakamlar) yazılır. */
    private fun fmt(id: Int, vararg a: Any): String = String.format(Locale.US, getString(id), *a)

    /** Sistem gece modunda mı (uiMode); etkinlik yeniden kurulmadan değişir, onConfigurationChanged izler. */
    private var sysNight = false
    private fun readNight(cfg: Configuration) = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Kullanıcı seçimi: 0 sistemi izle, 1 gündüz, 2 gece. */
    private fun nightNow(): Boolean = if (!(classic && prefs.classicWood)) true else when (prefs.appearance) { 1 -> false; 2 -> true; else -> sysNight }

    /** Açık zeminde okunur vurgu rengi (yazı için); koyu zeminde vurgu aynen. */
    internal val accentText: Int get() = Pal.readable(accent)

    private val rtl: Boolean get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase))
    }
    internal var accent = schemes[0]

    internal val prefs by lazy { Prefs(this) }

    private companion object {
        /** Dil değişince etkinlik yeniden kurulur; menüye geri dönülsün. */
        @Volatile var reopenMenu = false
        const val REQ_BACKUP = 11
        const val REQ_RESTORE = 12
    }
    internal var service: RadioService? = null
    private var bound = false
    private var page = Page.MAIN
    private var listKind = "fm"
    private val handler = Handler(Looper.getMainLooper())
    private var burnTick = 0
    private val ticker = object : Runnable {
        override fun run() {
            val s = service
            if (s != null && (s.state.recording || s.state.scanning)) renderAll()
            sheetRefresh?.invoke()
            // Ekran yanmasını önlemek için sabit öğeler dakikada bir birkaç piksel kayar
            if (++burnTick % 60 == 0 && ::mainPage.isInitialized) {
                val d = density * 2f
                mainPage.translationX = d * ((burnTick / 60) % 3 - 1)
                mainPage.translationY = d * ((burnTick / 180) % 3 - 1)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val dabChannelNames = arrayOf(
        "5A", "5B", "5C", "5D", "6A", "6B", "6C", "6D", "7A", "7B", "7C", "7D", "8A", "8B", "8C", "8D", "9A", "9B", "9C", "9D",
        "10A", "10B", "10C", "10D", "11A", "11B", "11C", "11D", "12A", "12B", "12C", "12D", "13A", "13B", "13C", "13D", "13E", "13F",
        "16A", "16B", "16C", "16D", "17A", "17B", "17C", "17D"
    )

    // ------------------------------------------------------------------ Ölçüler
    // Tüm boyutlar pencerenin kısa kenarının yüzdesi olarak verilir (m): 1920x1080 ekranda 1 birim ≈ 10,8 px.
    internal var winW = 0
    internal var winH = 0
    internal var land = true
    private var unit = 10f
    private var density = 1f

    internal fun m(x: Float): Int = max(1, (x * unit).roundToInt())
    internal fun mf(x: Float): Float = x * unit
    internal fun wPct(x: Float): Int = (x * winW / 100f).roundToInt()
    internal fun hPct(x: Float): Int = (x * winH / 100f).roundToInt()

    /** Metin boyutu (px): en az 13 sp okunabilirlik sınırı */
    internal fun tx(x: Float): Float = max(x * unit, 13f * density * 0.95f)

    /** Son yapılandırma: dil için sarmalanan bağlamın kaynakları dönmeden sonra eski ekran boyutunu verir. */
    private var liveConfig: Configuration? = null

    private fun measureWindow() {
        val cfg = liveConfig ?: resources.configuration
        val dm = resources.displayMetrics
        density = dm.density
        var wDp = cfg.screenWidthDp
        var hDp = cfg.screenHeightDp
        if (wDp <= 0 || hDp <= 0) {
            wDp = (dm.widthPixels / dm.density).roundToInt()
            hDp = (dm.heightPixels / dm.density).roundToInt()
        }
        winW = (wDp * density).roundToInt()
        winH = (hDp * density).roundToInt()
        land = winW >= winH
        unit = min(winW, winH) / 100f
    }

    // ------------------------------------------------------------------ Görünüm nesneleri
    private lateinit var root: FrameLayout
    private lateinit var mainPage: LinearLayout
    private lateinit var listPage: LinearLayout
    private lateinit var menuPage: LinearLayout
    private var sheetView: View? = null
    private var sheetRefresh: (() -> Unit)? = null

    // Ana sayfa
    private lateinit var segFm: TextView
    private lateinit var segDab: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var signal: SignalBars
    private lateinit var freqText: TextView
    private lateinit var nameText: TextView
    private lateinit var nowLogo: BadgeView
    private lateinit var subText: TextView
    private lateinit var rtText: TextView
    private lateinit var tuner: TunerView
    private lateinit var dabInfo: TextView
    private lateinit var msgText: TextView
    private lateinit var ctlInner: View
    private var dial: ClassicDial? = null
    private val classic: Boolean get() = ThemeManager.current(prefs) == ThemeManager.CLASSIC
    /** Klasik dışındaki temalar: görünüm RadioUi sınıflarındadır, burası yalnız kabuktur. */
    private var customUi: RadioUi? = null
    private lateinit var scanBox: LinearLayout
    private lateinit var scanText: TextView
    private lateinit var scanBar: ProgressBar
    private lateinit var btnPlay: Ctl
    private lateinit var btnFav: Ctl
    private lateinit var btnRec: Ctl
    private lateinit var rowFm: LinearLayout
    private lateinit var rowDab: LinearLayout
    private lateinit var presetScroll: HorizontalScrollView
    private lateinit var presetRow: LinearLayout
    private var lastPresetSig = ""
    private var lastChipSig = ""

    // Liste sayfası
    private lateinit var listFmBtn: TextView
    private lateinit var listDabBtn: TextView
    private lateinit var listFavBtn: TextView
    private lateinit var listView: ListView
    private lateinit var listEmpty: TextView
    private lateinit var listAdapter: RowAdapter
    private var lastListSig = ""

    // Menü
    private lateinit var menuGrid: LinearLayout
    private var lastMenuSig = ""

    internal class Row(
        val key: String,
        val title: String,
        val subtitle: String,
        val badge: String,
        val fav: Boolean,
        val current: Boolean,
        val onClick: () -> Unit,
        val onLong: (() -> Unit)? = null,
        /** Logo aranacak istasyon adı ("" ise yalnız rozet) */
        val logoName: String = ""
    )

    // ------------------------------------------------------------------ Çizim yardımcıları

    internal fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    internal fun rr(fill: Int, radius: Int, stroke: Int = 0, strokePx: Int = max(1, m(0.14f))): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != 0) setStroke(strokePx, stroke)
        }

    internal fun oval(fill: Int, stroke: Int = 0, strokePx: Int = max(1, m(0.14f))): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != 0) setStroke(strokePx, stroke)
        }

    internal fun pressable(normal: Drawable, pressed: Drawable): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

    internal fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    internal fun lpm(w: Int, h: Int, weight: Float, l: Int, t: Int, r: Int, b: Int) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b); marginStart = l; marginEnd = r }

    internal fun txt(text: String, size: Float, color: Int = Pal.FG, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        includeFontPadding = false
        textDirection = View.TEXT_DIRECTION_ANY_RTL   // Arapça metin, Latin harfle başlasa da sağdan sola dizilir
    }

    internal fun autoSize(t: TextView, minPx: Float, maxPx: Float) {
        t.setAutoSizeTextTypeUniformWithConfiguration(max(1, minPx.toInt()), max(2, maxPx.toInt()), 1, TypedValue.COMPLEX_UNIT_PX)
    }

    /** Düğme: üstte simge, altta yazı. round: daire, filled: vurgu renginde dolu. */
    private inner class Btn(
        private val round: Boolean,
        glyph: Glyph?,
        labelText: String?,
        iconPx: Int,
        textPx: Float,
        inset: Float = 0.3f,
        click: () -> Unit
    ) : Ctl {
        val view = LinearLayout(this@MainActivity)
        val icon: GlyphView? = glyph?.let { GlyphView(this@MainActivity, it, Pal.FG) }
        val label: TextView? = labelText?.let { txt(it, textPx, Pal.FG, true).apply { gravity = Gravity.CENTER; maxLines = 1 } }

        init {
            view.orientation = LinearLayout.VERTICAL
            view.gravity = Gravity.CENTER
            view.isClickable = true
            view.isFocusable = true
            icon?.let {
                when {
                    iconPx > 0 -> view.addView(it, lp(iconPx, iconPx))
                    label == null -> { it.inset = inset; view.addView(it, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
                    else -> { it.inset = 0.3f; view.addView(it, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }
                }
            }
            label?.let {
                autoSize(it, textPx * 0.6f, textPx)
                view.addView(it, lpm(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, m(0.6f), 0, m(0.6f), if (icon != null) m(0.9f) else 0))
            }
            view.setOnClickListener { click() }
            style(false)
        }

        private var styleKey: Triple<Boolean, Int, Int>? = null

        fun style(filled: Boolean, tint: Int = Pal.FG) {
            // Çok sık çağrılır (her durum olayında): değişmediyse yeni çizim nesneleri üretme
            val key = Triple(filled, tint, accent)
            if (key == styleKey) return
            styleKey = key
            val normal: Drawable
            val pressed: Drawable
            if (filled) {
                normal = if (round) oval(accent) else rr(accent, m(2.6f))
                pressed = if (round) oval(alpha(accent, 0xCC)) else rr(alpha(accent, 0xCC), m(2.6f))
            } else {
                normal = if (round) oval(Pal.SURFACE, Pal.STROKE) else rr(Pal.SURFACE, m(2.6f), Pal.STROKE)
                pressed = if (round) oval(Pal.SURFACE_PRESSED, Pal.STROKE) else rr(Pal.SURFACE_PRESSED, m(2.6f), Pal.STROKE)
            }
            view.background = pressable(normal, pressed)
            val fg = if (filled) Color.BLACK else tint
            icon?.set(icon.glyph, fg)
            label?.setTextColor(fg)
        }

        override fun glyph(g: Glyph, filled: Boolean, tint: Int) {
            icon?.set(g, if (filled) Color.BLACK else tint)
            style(filled, tint)
        }

        override fun caption(text: String, color: Int) {
            label?.put(text)
            label?.setTextColor(color)
        }
    }

    private inner class RowAdapter : ArrayAdapter<Row>(this@MainActivity, 0) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = getItem(position)!!
            val v = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(BadgeView(context).apply { tag = "logo" }, lpm(m(6.6f), m(6.6f), 0f, 0, 0, m(1.8f), 0))
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "col" }
                addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(txt("", tx(2.4f), Pal.DIM, true).apply { tag = "badge" }, lpm(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, m(1.5f), 0, m(1.5f), 0))
                addView(GlyphView(context, Glyph.STAR, accent).apply { tag = "star" }, lp(m(4.2f), m(4.2f)))
            }
            val pad = m(2.2f)
            v.setPadding(pad, m(1.6f), pad, m(1.6f))
            v.minimumHeight = m(9.5f)
            v.background = if (row.current) rr(alpha(accent, 0x2E), m(2.4f), alpha(accent, 0x99)) else rr(Pal.CARD, m(2.4f), Pal.STROKE)
            v.findViewWithTag<BadgeView>("logo").apply {
                label = initialsOf(row.title); color = badgeColor(row.title); logo = logoFor(row.logoName)
            }
            val col = v.findViewWithTag<LinearLayout>("col")
            col.removeAllViews()
            col.addView(txt(row.title, tx(3.2f), if (row.current) accentText else Pal.FG, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (row.subtitle.isNotEmpty()) {
                col.addView(txt(row.subtitle, tx(2.2f), Pal.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lpm(-2, -2, 0f, 0, m(0.4f), 0, 0))
            }
            v.findViewWithTag<TextView>("badge").text = row.badge
            val star = v.findViewWithTag<GlyphView>("star")
            star.set(if (row.fav) Glyph.STAR else Glyph.STAR_O, if (row.fav) accent else Pal.OFF)
            v.layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return v
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as RadioService.LocalBinder).service()
            service = s
            bound = true
            s.ui = this@MainActivity
            renderAll()
            // Açılıştaki yapılandırma değişimleri paneli silmesin diye arayüz oturduktan sonra göster
            if (!prefs.onboarded) handler.postDelayed({ if (sheetView == null && !prefs.onboarded) showHelp() }, 800)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    // ------------------------------------------------------------------ Yaşam döngüsü

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sysNight = readNight(applicationContext.resources.configuration)
        Logos.init(applicationContext)
        Logos.addListener(logoListener)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        buildUi()
        if (reopenMenu) { reopenMenu = false; showPage(Page.MENU) }
        renderAll()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startRadioService()
        bindService(Intent(this, RadioService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    // Dongle takılınca (USB_DEVICE_ATTACHED) etkinlik yeniden öne gelir: servis bağlanmayı yeniden dener.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        startRadioService()
    }

    private fun startRadioService() {
        val i = Intent(this, RadioService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    // Ekran döndürme, bölünmüş ekran, yazı tipi değişimi: etkinlik yeniden başlamaz, arayüz yeni ölçüye göre kurulur
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        liveConfig = newConfig
        sysNight = readNight(newConfig)   // gündüz/gece (sistem teması) değişimi de burada yakalanır
        buildUi()
        renderAll()
    }

    override fun onResume() {
        super.onResume()
        service?.ui = this
        renderAll()
        handler.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    /** Yeni logo geldi: hazır düğmeleri, listeyi ve çalan alanını bir kerede (art arda gelenleri birleştirerek) yenile. */
    private var logoRefreshPending = false
    private val logoListener: () -> Unit = {
        if (!logoRefreshPending) {
            logoRefreshPending = true
            handler.postDelayed({
                logoRefreshPending = false
                lastPresetSig = ""; lastListSig = ""
                renderAll()
            }, 300)
        }
    }

    override fun onDestroy() {
        Logos.removeListener(logoListener)
        service?.ui = null
        if (bound) unbindService(conn)
        bound = false
        super.onDestroy()
    }

    /** Sistem geri hareketi (API 33+ OnBackInvokedCallback, altı onBackPressed) ve ekrandaki geri düğmesi. */
    private fun handleBack() {
        when {
            sheetView != null -> closeSheet()
            page != Page.MAIN -> showPage(Page.MAIN)
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    // Direksiyon / ekran altı medya tuşları (uygulama öndeyken doğrudan; arka planda MediaSession alır)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val s = service
            if (s != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> { s.seek(+1); return true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> { s.seek(-1); return true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { s.togglePlay(); return true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { if (!s.state.playing) s.resume(); return true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> { if (s.state.playing) s.stopPlayback(); return true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (uiModeNow() == "fm") s.stepFm(+1) else s.seek(+1); return true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { if (uiModeNow() == "fm") s.stepFm(-1) else s.seek(-1); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onEvent() = renderAll()

    // ------------------------------------------------------------------ Arayüz kurulumu

    private fun applyWindowColors() {
        val bar = if (Pal.light) Pal.BG else 0xFF050607.toInt()
        window.setBackgroundDrawable(ColorDrawable(bar))
        window.statusBarColor = bar
        window.navigationBarColor = bar
        @Suppress("DEPRECATION")
        run {
            var f = window.decorView.systemUiVisibility
            val lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            f = if (Pal.light) f or lightBars else f and lightBars.inv()
            window.decorView.systemUiVisibility = f
        }
    }

    private fun buildUi() {
        Pal.apply(nightNow(), classic)
        applyWindowColors()
        measureWindow()
        accent = schemes[prefs.colorScheme.coerceIn(0, schemes.size - 1)]
        sheetView = null
        sheetRefresh = null
        lastPresetSig = ""; lastChipSig = ""; lastListSig = ""; lastMenuSig = ""
        root = FrameLayout(this).apply {
            setBackgroundColor(Pal.BG)
            // API 35+ hedefte pencere kenardan kenaradır: sistem çubukları ve çentik içeriğin üstüne binmesin
            setOnApplyWindowInsetsListener { v, ins ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    v.setPadding(b.left, b.top, b.right, b.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                }
                ins
            }
        }
        buildMain()
        buildList()
        buildMenu()
        mainPage.layoutDirection = View.LAYOUT_DIRECTION_LTR   // radyo ekranı (kadran, frekans) Arapça'da da aynı kalır
        root.addView(mainPage, FrameLayout.LayoutParams(-1, -1))
        root.addView(listPage, FrameLayout.LayoutParams(-1, -1))
        root.addView(menuPage, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        showPage(page)
    }

    private fun showPage(p: Page) {
        page = p
        mainPage.visibility = if (p == Page.MAIN) View.VISIBLE else View.GONE
        listPage.visibility = if (p == Page.LIST) View.VISIBLE else View.GONE
        menuPage.visibility = if (p == Page.MENU) View.VISIBLE else View.GONE
        renderAll()
    }

    private fun styleSeg(t: TextView, on: Boolean) {
        val key = "$on|$accent"
        if (t.tag == key) return
        t.tag = key
        t.background = if (on) rr(accent, m(4.2f)) else null
        t.setTextColor(if (on) Color.BLACK else Pal.DIM)
    }

    /** Aynı metinse yeniden ölçüm/çizim yaptırma (otomatik boyutlandırma pahalıdır, olaylar saniyede çok gelir). */
    private fun TextView.put(cs: CharSequence) {
        if (text.toString() != cs.toString()) text = cs
    }

    private fun segment(label: String, click: () -> Unit): TextView = txt(label, tx(3.1f), Pal.DIM, true).apply {
        gravity = Gravity.CENTER
        setPadding(m(3.2f), 0, m(3.2f), 0)
        setOnClickListener { click() }
    }

    /** Çalan istasyonun adı: solunda (varsa) istasyon logosu. nameText önce oluşturulmuş olmalı. */
    private fun makeNameRow(): LinearLayout {
        nowLogo = BadgeView(this).apply {
            visibility = View.GONE
            setOnClickListener { showSlide() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nowLogo, lpm(m(8.2f), ViewGroup.LayoutParams.MATCH_PARENT, 0f, 0, 0, m(1.6f), 0))
            addView(nameText, lp(0, -1, 1f))
        }
    }

    private fun backRow(title: String, vararg extra: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val back = Btn(true, Glyph.BACK, null, m(3.6f), 0f) { handleBack() }
        if (rtl) back.view.scaleX = -1f
        addView(back.view, lp(m(7f), m(7f)))
        addView(txt(title, tx(3.6f), if (classic && !Pal.night) Pal.WOOD_TEXT else accentText, true).apply { maxLines = 1; autoSize(this, tx(2.2f), tx(3.6f)) }, lpm(0, -2, 1f, m(2f), 0, m(1f), 0))
        extra.forEach { addView(it, lpm(-2, m(6.2f), 0f, m(0.6f), 0, m(0.6f), 0)) }
    }

    private fun buildMain() {
        dial = null
        customUi = when (ThemeManager.current(prefs)) {
            ThemeManager.OEM -> OemPremiumUi(this)
            ThemeManager.MODERN -> ModernDigitalUi(this)
            ThemeManager.MINIMAL -> MinimalDrivingUi(this)
            ThemeManager.WALL -> StationWallUi(this)
            else -> null
        }
        val ui = customUi
        if (ui != null) mainPage = ui.root else buildMainClassic()
    }

    // ------------------------------------------------------------------ Klasik Analog tema

    private fun chromeBtn(glyph: Glyph?, caption: String?, big: Boolean = false, click: () -> Unit): ChromeButton {
        val capPx = max(m(1.7f).toFloat(), 11f * density)
        return ChromeButton(this, glyph, caption, capPx, big).also {
            it.accent = accent
            it.setOnClickListener { click() }
            if (glyph != null) it.glyph(glyph, false, Pal.FG)
            if (caption != null) it.caption(caption, Pal.WOOD_DIM)
        }
    }

    private fun woodBackground(): Drawable =
        if (prefs.classicWood) WoodDrawable.create(this, Pal.night)
        else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF34312E.toInt(), 0xFF1B1A18.toInt(), 0xFF121110.toInt()))

    private fun buildMainClassic() {
        mainPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = woodBackground()
            clipChildren = false
            setPadding(m(2.2f), m(1.6f), m(2.2f), m(1.8f))
        }

        // ---- Üst çubuk (ahşap üstünde): mod seçici, durum, başlık, saat, menü
        segFm = segment("FM") { selectMode("fm") }
        segDab = segment("DAB+") { selectMode("dab") }
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rr(0x96000000.toInt(), m(4.6f), 0x28FFFFFF)
            setPadding(m(0.5f), m(0.5f), m(0.5f), m(0.5f))
            addView(segFm, lp(-2, m(6.0f)))
            addView(segDab, lp(-2, m(6.0f)))
        }
        statusDot = View(this)
        statusText = txt("", tx(2.2f), Pal.WOOD_DIM).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (winW * (if (land) 0.28f else 0.30f)).toInt()   // ortadaki başlıkla çakışmasın
        }
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, tx(3.2f))
            setTextColor(Pal.WOOD_TEXT)
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        }
        val menuBtn = chromeBtn(Glyph.MENU, null) { showPage(Page.MENU) }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(seg, lp(-2, -2))
            addView(statusDot, lpm(m(1.5f), m(1.5f), 0f, m(2f), 0, m(1f), 0))
            addView(statusText, lp(-2, -2))
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(clock, lpm(-2, -2, 0f, 0, 0, m(1.6f), 0))
            addView(menuBtn, lp(m(7f), m(7f)))
        }
        val top = FrameLayout(this).apply {
            addView(left, FrameLayout.LayoutParams(-2, -1, Gravity.START or Gravity.CENTER_VERTICAL))
            if (land) addView(txt("RADVELA", tx(3.1f), alpha(Pal.WOOD_TEXT, 0xEB), true).apply { letterSpacing = 0.12f },
                FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(right, FrameLayout.LayoutParams(-2, -1, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        mainPage.addView(top, lpm(-1, m(7.4f), 0f, 0, 0, 0, m(1.2f)))

        // ---- Panel içeriği: metin alanları + kadran
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        signal = SignalBars(this)
        val digits = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        freqText = txt("", tx(14f), Pal.WARM_TEXT).apply { typeface = digits; maxLines = 1; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        autoSize(freqText, mf(5f), mf(if (land) 15.5f else 17f))
        nameText = txt("", tx(7f), accent, true).apply { maxLines = 1; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        autoSize(nameText, mf(2.6f), mf(if (land) 8.2f else 8f))
        subText = txt("", tx(2.4f), Pal.WARM_DIM).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        rtText = txt("", tx(2.9f), Pal.WARM_TEXT).apply { maxLines = if (land) 3 else 2; ellipsize = TextUtils.TruncateAt.END }
        msgText = txt("", tx(2.3f), Pal.WARN).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        dabInfo = txt("", tx(2.3f), Pal.WARM_DIM).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END }
        tuner = TunerView(this)   // klasik temada kullanılmaz (kadran yerine geçer); alanlar ortak olduğu için tutuluyor
        val d = ClassicDial(this).apply {
            accent = this@MainActivity.accent
            onPreview = { hz -> freqText.text = freqSpan(String.format(Locale.US, "%.2f", hz / 1e6), "MHz") }
            onCommit = { hz ->
                val sv = service
                if (sv != null && sv.state.connected) sv.playFm(hz) else renderAll()
            }
        }
        dial = d

        val bezel = m(1.15f).toFloat()
        val panel = ChromeFrame(this, bezel, m(3.2f).toFloat())
        if (land) {
            val leftCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(m(1.2f), m(1.0f), m(0.4f), m(1.0f))
                addView(chipRow, lp(-1, -2))
                addView(freqText, lpm(-1, 0, 1f, 0, m(1.4f), 0, 0))
                addView(subText, lp(-1, -2))
                addView(msgText, lpm(-1, -2, 0f, 0, m(0.8f), 0, 0))
            }
            val rightCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(m(0.4f), m(1.0f), m(1.2f), m(1.0f))
                addView(makeNameRow(), lp(-1, m(9f)))
                addView(rtText, lpm(-1, -2, 0f, 0, m(1.2f), 0, 0))
                addView(dabInfo, lpm(-1, -2, 0f, 0, m(1.2f), 0, 0))
                addView(View(this@MainActivity), lp(-1, 0, 1f))
                addView(signal, lp(m(11f), m(4.4f)))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                addView(leftCol, lp(0, -1, 0.255f))
                addView(d, lpm(0, -1, 0.49f, 0, m(2.4f), 0, 0))
                addView(rightCol, lp(0, -1, 0.255f))
            }
            panel.addView(row, FrameLayout.LayoutParams(-1, -1))
        } else {
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(m(1.2f), m(0.8f), m(1.2f), m(0.4f))
                val chipLine = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(chipRow, lp(0, -2, 1f))
                    addView(signal, lp(m(11f), m(4.4f)))
                }
                addView(chipLine, lp(-1, -2))
                addView(freqText, lpm(-1, m(17f), 0f, 0, m(0.8f), 0, 0))
                addView(makeNameRow(), lp(-1, m(8f)))
                addView(subText, lp(-1, -2))
                addView(rtText, lpm(-1, -2, 0f, 0, m(1.4f), 0, 0))
                addView(dabInfo, lpm(-1, -2, 0f, 0, m(1f), 0, 0))
                addView(msgText, lpm(-1, -2, 0f, 0, m(0.6f), 0, 0))
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                addView(textCol, lp(-1, 0, 1f))
                addView(d, lp(-1, -2))
            }
            panel.addView(col, FrameLayout.LayoutParams(-1, -1))
        }
        mainPage.addView(panel, lp(-1, 0, 1f))

        // ---- Alt bölüm: hazır istasyonlar + krom kontrol çubuğu (tarama sırasında ilerleme gösterilir)
        presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(presetRow, FrameLayout.LayoutParams(-2, -1))
        }

        val prev = chromeBtn(Glyph.PREV, getString(R.string.cap_prev)) { service?.seek(-1) }
        val play = chromeBtn(Glyph.PLAY, null, true) { service?.togglePlay() }
        play.glyph(Glyph.PLAY, true, Pal.FG)
        val next = chromeBtn(Glyph.NEXT, getString(R.string.cap_next)) { service?.seek(+1) }
        val fav = chromeBtn(Glyph.STAR_O, getString(R.string.btn_fav)) { service?.let { it.toggleFavorite(it.currentKey()) } }
        val list = chromeBtn(Glyph.LIST, getString(R.string.btn_list)) { listKind = uiModeNow(); showPage(Page.LIST) }
        val rec = chromeBtn(Glyph.REC, getString(R.string.btn_rec)) { toggleRec() }
        btnPlay = play; btnFav = fav; btnRec = rec

        fun cell(v: View, weight: Float = 1f) = lpm(0, -1, weight, m(0.2f), m(0.4f), m(0.2f), m(0.4f)).also { v.layoutParams = it }
        fun group(vararg bs: View): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (b in bs) addView(b, lpm(0, -1, 1f, m(0.2f), m(0.4f), m(0.2f), m(0.4f)))
        }
        rowFm = group(
            chromeBtn(Glyph.MINUS, "-0,1") { service?.stepFm(-1) },
            chromeBtn(Glyph.SLIDERS, getString(R.string.cap_manual)) { showManualTune() },
            chromeBtn(Glyph.PLUS, "+0,1") { service?.stepFm(+1) }
        )
        rowDab = group(
            chromeBtn(Glyph.SLIDERS, getString(R.string.cap_manual)) { showManualTune() },
            chromeBtn(Glyph.SEARCH, getString(R.string.cap_scan)) { service?.scanDab() },
            chromeBtn(Glyph.PULSE, getString(R.string.cap_gain)) { service?.optimizeGain() }
        )
        fun divider(): View = View(this).apply { setBackgroundColor(0x28FFFFFF) }
        val barH = m(if (land) 11.2f else 13.6f)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xEB0E0B09.toInt(), 0xF5050405.toInt())).apply {
                cornerRadius = barH / 2f
                setStroke(max(1, m(0.12f)), 0x2DFFFFFF)
            }
            setPadding(barH / 3, 0, barH / 3, 0)
            clipChildren = false
            addView(prev, cell(prev))
            addView(play, cell(play, 1.3f))
            addView(next, cell(next))
            addView(divider(), lpm(max(1, m(0.12f)), m(5f), 0f, m(0.6f), 0, m(0.6f), 0))
            addView(fav, cell(fav))
            addView(list, cell(list))
            addView(rec, cell(rec))
            addView(divider(), lpm(max(1, m(0.12f)), m(5f), 0f, m(0.6f), 0, m(0.6f), 0))
            addView(rowFm, lp(0, -1, 3f))
            addView(rowDab, lp(0, -1, 3f))
        }

        scanText = txt("", tx(3f), Pal.WOOD_WARN, true)
        scanBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(Pal.OFF)
        }
        val cancel = Btn(false, Glyph.CLOSE, getString(R.string.btn_stop_scan), m(3.4f), tx(2.5f)) { service?.cancelScan() }
        scanBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = rr(0xE6050405.toInt(), m(3f), 0x33FFFFFF)
            setPadding(m(3f), m(2f), m(3f), m(2f))
            addView(scanText, lp(-1, -2))
            addView(scanBar, lpm(-1, m(1.6f), 0f, 0, m(1.6f), 0, m(2.4f)))
            addView(cancel.view, lp(-1, m(9f)))
        }
        val presetH = m(if (land) 13f else 14f)
        val gap = m(1.5f)
        val bottomGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(presetScroll, lp(-1, presetH))
            addView(bar, lpm(-1, barH, 0f, 0, gap, 0, 0))
        }
        ctlInner = bottomGroup
        val bottom = FrameLayout(this).apply {
            clipChildren = false
            addView(bottomGroup, FrameLayout.LayoutParams(-1, -2))
            addView(scanBox, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        mainPage.addView(bottom, lpm(-1, -2, 0f, 0, gap, 0, 0))
    }

    private fun buildList() {
        listFmBtn = tab("FM") { listKind = "fm"; renderAll() }
        listDabBtn = tab("DAB+") { listKind = "dab"; renderAll() }
        listFavBtn = tab(getString(R.string.btn_fav)) { service?.let { it.prefs.favoritesOnly = !it.prefs.favoritesOnly; renderAll() } }
        listPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (classic) background = woodBackground() else setBackgroundColor(Pal.BG)
            setPadding(m(2.2f), m(1.8f), m(2.2f), m(2f))
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(backRow(getString(R.string.list_title), listFmBtn, listDabBtn, listFavBtn), lpm(-1, m(7.6f), 0f, 0, 0, 0, m(1.8f)))
        listAdapter = RowAdapter()
        listView = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = m(1.1f)
            selector = ColorDrawable(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = listAdapter
            setOnItemClickListener { _, _, pos, _ -> listAdapter.getItem(pos)?.onClick?.invoke() }
            setOnItemLongClickListener { _, _, pos, _ -> listAdapter.getItem(pos)?.onLong?.invoke(); true }
        }
        listEmpty = txt("", tx(2.8f), if (classic) Pal.WOOD_DIM else Pal.DIM).apply { gravity = Gravity.CENTER }
        val frame = FrameLayout(this)
        frame.addView(listView, FrameLayout.LayoutParams(-1, -1))
        frame.addView(listEmpty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        inner.addView(frame, lp(-1, 0, 1f))
        val scan = Btn(false, Glyph.SEARCH, getString(R.string.list_scan), m(3.6f), tx(2.6f)) { startScanFromList() }
        val clear = Btn(false, Glyph.TRASH, getString(R.string.list_clear), m(3.6f), tx(2.6f)) { confirmClear(listKind) }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scan.view, lpm(0, -1, 1f, 0, 0, m(0.8f), 0))
            addView(clear.view, lpm(0, -1, 1f, m(0.8f), 0, 0, 0))
        }
        inner.addView(bottom, lpm(-1, m(10f), 0f, 0, m(1.6f), 0, 0))
        listPage.addView(inner, LinearLayout.LayoutParams(if (land) wPct(72f) else -1, -1).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun tab(label: String, click: () -> Unit): TextView = txt(label, tx(2.5f), Pal.DIM, true).apply {
        gravity = Gravity.CENTER
        setPadding(m(2.2f), 0, m(2.2f), 0)
        setOnClickListener { click() }
    }

    private fun styleTab(t: TextView, on: Boolean) {
        val key = "$on|$accent"
        if (t.tag == key) return
        t.tag = key
        t.background = if (on) rr(accent, m(3.1f)) else rr(Pal.CARD, m(3.1f), Pal.STROKE)
        t.setTextColor(if (on) Color.BLACK else Pal.DIM)
    }

    private fun buildMenu() {
        menuPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (classic) background = woodBackground() else setBackgroundColor(Pal.BG)
            setPadding(m(2.2f), m(1.8f), m(2.2f), m(2f))
        }
        menuPage.addView(backRow(getString(R.string.menu_title)), lpm(-1, m(7.6f), 0f, 0, 0, 0, m(1.8f)))
        menuGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuPage.addView(ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(menuGrid, FrameLayout.LayoutParams(-1, -2))
        }, lp(-1, 0, 1f))
    }

    private class Tile(val glyph: Glyph, val title: String, val sub: String, val onClick: () -> Unit)

    private fun menuTiles(): List<Tile> {
        val s = service
        val rec = s?.state?.recording == true
        return listOf(
            Tile(Glyph.LIST, getString(R.string.menu_list_t), getString(R.string.menu_list_s)) { listKind = uiModeNow(); showPage(Page.LIST) },
            Tile(Glyph.SEARCH, getString(R.string.menu_scan_fm_t), getString(R.string.menu_scan_fm_s)) { showPage(Page.MAIN); service?.scanFm() },
            Tile(Glyph.SEARCH, getString(R.string.menu_scan_dab_t), getString(R.string.menu_scan_dab_s)) { showPage(Page.MAIN); service?.scanDab() },
            Tile(Glyph.SLIDERS, getString(R.string.menu_manual_t), getString(R.string.menu_manual_s)) { showManualTune() },
            Tile(Glyph.PLUS, getString(R.string.menu_add_t), getString(R.string.menu_add_s)) { service?.addCurrentFm(); showPage(Page.MAIN) },
            Tile(Glyph.TRASH, getString(R.string.menu_clear_t), getString(R.string.menu_clear_s)) { confirmClear(uiModeNow()) },
            Tile(if (rec) Glyph.STOP else Glyph.REC, getString(if (rec) R.string.menu_rec_stop_t else R.string.menu_rec_start_t), getString(R.string.menu_rec_s)) { toggleRec(); showPage(Page.MAIN) },
            Tile(Glyph.PALETTE, getString(R.string.gal_t), ThemeManager.info(ThemeManager.current(prefs)).name) { showGallery() },
            Tile(Glyph.VOLUME, getString(R.string.vol_t), getString(R.string.vol_s)) { showVolume() },
            Tile(Glyph.CONTRAST, getString(R.string.menu_look_t), fmt(R.string.menu_theme_s, getString(when (prefs.appearance) { 1 -> R.string.look_day; 2 -> R.string.look_night; else -> R.string.lang_auto }))) {
                prefs.appearance = (prefs.appearance + 1) % 3
                val keep = page
                buildUi()
                showPage(keep)
            },
            Tile(Glyph.PALETTE, getString(R.string.menu_color_t), fmt(R.string.menu_color_s, schemeName(prefs.colorScheme))) {
                prefs.colorScheme = (prefs.colorScheme + 1) % schemes.size
                val keep = page
                buildUi()
                showPage(keep)
            },
            Tile(Glyph.GLOBE, getString(R.string.menu_lang_t), fmt(R.string.menu_lang_s, if (prefs.language.isEmpty()) getString(R.string.lang_auto) else L10n.nameOf(prefs.language))) { showLanguage() },
            Tile(Glyph.PULSE, getString(R.string.menu_gain_t), getString(R.string.menu_gain_s)) { showPage(Page.MAIN); service?.optimizeGain() },
            Tile(Glyph.SLIDERS, getString(R.string.menu_settings_t), getString(R.string.menu_settings_s)) { showSettings() },
            Tile(Glyph.PULSE, getString(R.string.menu_diag_t), getString(R.string.menu_diag_s)) { showDiagnostics() },
            Tile(Glyph.STOP, getString(R.string.sleep_t), sleepSub()) { showSleep() },
            Tile(Glyph.SLIDERS, getString(R.string.eq_t), getString(eqNames[prefs.eqPreset.coerceIn(0, eqNames.size - 1)])) { showEq() },
            Tile(Glyph.LIST, getString(R.string.backup_t), getString(R.string.backup_s)) { startBackup() },
            Tile(Glyph.LIST, getString(R.string.restore_t), getString(R.string.restore_s)) { startRestore() },
            Tile(Glyph.INFO, getString(R.string.help_t), getString(R.string.help_s)) { showHelp() },
            Tile(Glyph.INFO, getString(R.string.privacy_t), getString(R.string.privacy_s)) { showPrivacy() },
            Tile(Glyph.INFO, getString(R.string.menu_about_t), getString(R.string.menu_about_s)) { showAbout() }
        )
    }

    private fun fillMenu() {
        val tiles = menuTiles()
        val sig = tiles.joinToString("|") { it.title + it.sub } + accent
        if (sig == lastMenuSig) return
        lastMenuSig = sig
        menuGrid.removeAllViews()
        val cols = if (land) 3 else 2
        val gapPx = m(1.6f)
        var i = 0
        while (i < tiles.size) {
            val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val t = tiles.getOrNull(i + c)
                if (t == null) {
                    rowL.addView(View(this), lpm(0, m(15.5f), 1f, gapPx / 2, 0, gapPx / 2, 0))
                    continue
                }
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    setPadding(m(2.4f), m(1.6f), m(2.4f), m(1.6f))
                    minimumHeight = m(15.5f)
                    background = pressable(rr(Pal.CARD, m(2.8f), Pal.STROKE), rr(Pal.SURFACE_PRESSED, m(2.8f), Pal.STROKE))
                    setOnClickListener { t.onClick() }
                    addView(GlyphView(this@MainActivity, t.glyph, accentText), lp(m(5f), m(5f)))
                    addView(txt(t.title, tx(2.8f), Pal.FG, true).apply { maxLines = 3 }, lpm(-1, -2, 0f, 0, m(1.2f), 0, 0))
                    addView(txt(t.sub, tx(2.1f), Pal.DIM).apply { maxLines = 5 }, lpm(-1, -2, 0f, 0, m(0.5f), 0, 0))
                }
                rowL.addView(tile, lpm(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f, gapPx / 2, 0, gapPx / 2, 0))
            }
            menuGrid.addView(rowL, lpm(-1, -2, 0f, 0, gapPx / 2, 0, gapPx / 2))
            i += cols
        }
    }

    // ------------------------------------------------------------------ Açılır paneller (sheet)

    internal class Act(val label: String, val primary: Boolean = false, val keepOpen: Boolean = false, val run: () -> Unit)

    internal fun closeSheet() {
        sheetView?.let { root.removeView(it) }
        sheetView = null
        sheetRefresh = null
    }

    internal fun openSheet(title: String, content: View, vararg acts: Act) {
        closeSheet()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            setOnClickListener { closeSheet() }
        }
        val maxH = (winH * 0.86f).roundToInt()
        val cardW = if (land) wPct(54f) else wPct(92f)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rr(Pal.CARD, m(3f), Pal.STROKE)
            setPadding(m(2.6f), m(2f), m(2.6f), m(2.2f))
            isClickable = true
        }
        val closeBtn = Btn(true, Glyph.CLOSE, null, m(3f), 0f) { closeSheet() }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(txt(title, tx(3.4f), accentText, true).apply { maxLines = 1; autoSize(this, tx(2.2f), tx(3.4f)) }, lp(0, -2, 1f))
            addView(closeBtn.view, lp(m(6f), m(6f)))
        }, lp(-1, -2))
        card.addView(MaxScroll(this, maxH - m(24f)).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }, lpm(-1, -2, 0f, 0, m(1.6f), 0, 0))
        if (acts.isNotEmpty()) {
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (a in acts) {
                val b = Btn(false, null, a.label, 0, tx(2.8f)) {
                    a.run()
                    if (!a.keepOpen) closeSheet()
                }
                b.style(a.primary)
                bar.addView(b.view, lpm(0, m(8.2f), 1f, m(0.6f), 0, m(0.6f), 0))
            }
            card.addView(bar, lpm(-1, -2, 0f, 0, m(2f), 0, 0))
        }
        scrim.addView(card, FrameLayout.LayoutParams(cardW, -2, Gravity.CENTER))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        sheetView = scrim
    }

    private fun confirmClear(kind: String) {
        val label = if (kind == "dab") "DAB+" else "FM"
        openSheet(
            fmt(R.string.confirm_clear_title, label),
            txt(fmt(R.string.confirm_clear_msg, label), tx(2.9f), Pal.TEXT),
            Act(getString(R.string.act_cancel)) {},
            Act(getString(R.string.act_delete), primary = true) { service?.clearList(kind) }
        )
    }

    internal fun showManualTune() {
        val s = service ?: return
        if (uiModeNow() == "dab") {
            val have = s.dabList.map { it.channel }.toSet()
            val cur = s.state.dabChannel
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val cols = if (land) 6 else 5
            var i = 0
            while (i < dabChannelNames.size) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (c in 0 until cols) {
                    val name = dabChannelNames.getOrNull(i + c)
                    if (name == null) { r.addView(View(this), lpm(0, m(7f), 1f, m(0.4f), 0, m(0.4f), 0)); continue }
                    val b = Btn(false, null, name, 0, tx(2.6f)) { closeSheet(); showPage(Page.MAIN); s.tuneDabChannel(name) }
                    b.style(name == cur)
                    if (name != cur && name in have) b.view.background = pressable(rr(Pal.SURFACE, m(2f), accent), rr(Pal.SURFACE_PRESSED, m(2f), accent))
                    r.addView(b.view, lpm(0, m(7f), 1f, m(0.4f), 0, m(0.4f), 0))
                }
                grid.addView(r, lpm(-1, -2, 0f, 0, m(0.4f), 0, m(0.4f)))
                i += cols
            }
            grid.layoutDirection = View.LAYOUT_DIRECTION_LTR
            openSheet(getString(R.string.sheet_dab_channel), grid)
            return
        }

        var hz = if (s.state.mode == "fm") s.state.fmHz else s.prefs.lastFmHz
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = txt("", tx(9f), Pal.FG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        autoSize(label, mf(4f), mf(9f))
        val strip = TunerView(this).apply {
            accent = this@MainActivity.accent
            markers = s.fmList.map { it.freqHz }
            favs = s.fmList.filter { it.key in s.favorites }.map { it.freqHz }.toSet()
        }
        fun update() {
            label.text = freqSpan(String.format(Locale.US, "%.1f", hz / 1e6), "MHz")
            strip.freqHz = hz
        }
        strip.onPreview = { v -> hz = v; label.text = freqSpan(String.format(Locale.US, "%.1f", v / 1e6), "MHz") }
        strip.onCommit = { v -> hz = v; update() }
        fun nudge(delta: Int) { hz = (hz + delta).coerceIn(TunerView.MIN_HZ, TunerView.MAX_HZ); update() }
        update()
        val fine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val defs = listOf(getString(R.string.nudge_m1) to -1_000_000, getString(R.string.nudge_m01) to -100_000, getString(R.string.nudge_p01) to 100_000, getString(R.string.nudge_p1) to 1_000_000)
            for ((t, d) in defs) addView(Btn(false, null, t, 0, tx(3f)) { nudge(d) }.view, lpm(0, m(8f), 1f, m(0.5f), 0, m(0.5f), 0))
        }
        box.addView(label, lp(-1, m(11f)))
        box.addView(strip, lpm(-1, m(15f), 0f, 0, m(1f), 0, m(1.6f)))
        box.addView(fine, lp(-1, -2))
        box.layoutDirection = View.LAYOUT_DIRECTION_LTR
        openSheet(getString(R.string.sheet_fm_freq), box, Act(getString(R.string.act_cancel)) {}, Act(getString(R.string.act_listen), primary = true) { showPage(Page.MAIN); s.playFm(hz) })
    }

    private fun showSettings() {
        val s = service ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rowH = m(9f)

        fun rowShell(title: String): Pair<LinearLayout, TextView> {
            val t = txt(title, tx(2.7f), Pal.FG, true).apply { maxLines = 2 }
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, m(0.6f), 0, m(0.6f))
            }
            r.addView(t, lp(0, -2, 1f))
            box.addView(r, lp(-1, rowH))
            box.addView(View(this).apply { setBackgroundColor(Pal.STROKE) }, lp(-1, 1))
            return r to t
        }

        fun toggle(title: String, on: Boolean, change: (Boolean) -> Unit): ToggleView {
            val (r, _) = rowShell(title)
            val tv = ToggleView(this).apply { accent = this@MainActivity.accent; this.on = on }
            r.addView(tv, lp(m(8.4f), m(4.6f)))
            r.isClickable = true
            r.setOnClickListener { tv.on = !tv.on; change(tv.on) }
            return tv
        }

        /** [-] değer [+] satırı; basılı tutunca tekrarlar */
        fun stepper(title: String, valueText: () -> String, enabled: () -> Boolean, change: (Int) -> Unit): () -> Unit {
            val (r, t) = rowShell(title)
            val value = txt(valueText(), tx(2.7f), accentText, true).apply { gravity = Gravity.CENTER }
            fun refresh() { value.text = valueText(); r.alpha = if (enabled()) 1f else 0.35f; t.text = title }
            fun hold(b: Btn, d: Int) {
                var rep: Runnable? = null
                b.view.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            if (enabled()) { change(d); refresh() }
                            val rn = object : Runnable {
                                override fun run() { if (enabled()) { change(d); refresh() }; handler.postDelayed(this, 90) }
                            }
                            rep = rn
                            handler.postDelayed(rn, 450)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            rep?.let { handler.removeCallbacks(it) }
                            rep = null
                        }
                    }
                    true
                }
            }
            val minus = Btn(true, Glyph.MINUS, null, m(3.2f), 0f) {}
            val plus = Btn(true, Glyph.PLUS, null, m(3.2f), 0f) {}
            hold(minus, -1)
            hold(plus, +1)
            // [-] değer [+] her dilde soldan sağa kalır
            val ctl = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                addView(minus.view, lp(m(6.4f), m(6.4f)))
                addView(value, lp(m(12.5f), -2))
                addView(plus.view, lp(m(6.4f), m(6.4f)))
            }
            r.addView(ctl, lp(-2, -2))
            return ::refresh
        }

        val count = s.gainCount()
        lateinit var refreshGain: () -> Unit
        var gainIdx = s.prefs.gainIndex.coerceIn(0, max(0, count - 1))
        val agcToggle = toggle(getString(R.string.set_agc), s.prefs.agc) { v -> s.setAgc(v); refreshGain() }
        refreshGain = stepper(getString(R.string.set_gain), { if (count > 0) "${gainIdx + 1} / $count" else "-" }, { !agcToggle.on && count > 0 }) { d ->
            gainIdx = (gainIdx + d).coerceIn(0, max(0, count - 1))
            s.setGainIndex(gainIdx)
        }
        refreshGain()
        var ppm = s.prefs.ppm.coerceIn(-100, 100)
        stepper(getString(R.string.set_ppm), { "$ppm" }, { true }) { d -> ppm = (ppm + d).coerceIn(-100, 100); s.setPpm(ppm) }
        var vol = s.prefs.audioGainPct.coerceIn(0, 200)
        stepper(getString(R.string.set_volume), { fmt(R.string.fmt_percent, vol) }, { true }) { d -> vol = (vol + d * 5).coerceIn(0, 200); s.setAudioGain(vol) }
        toggle(getString(R.string.set_mono), s.prefs.forceMono) { s.setForceMono(it) }
        toggle(getString(R.string.set_deemph), s.prefs.deemphasis75) { s.setDeemphasis75(it) }
        toggle(getString(R.string.set_autoplay), s.prefs.autoPlay) { s.prefs.autoPlay = it }
        if (BuildConfig.FLAVOR == "sideload") toggle(getString(R.string.set_boot), s.prefs.bootStart) { s.prefs.bootStart = it }
        toggle(getString(R.string.set_logos), s.prefs.onlineLogos) { s.prefs.onlineLogos = it; Logos.enabled = it; lastPresetSig = ""; lastListSig = ""; renderAll() }

        // Renk şeması
        val sw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sw.addView(txt(getString(R.string.set_colors), tx(2.7f), Pal.FG, true), lp(0, -2, 1f))
        val cur = prefs.colorScheme.coerceIn(0, schemes.size - 1)
        for (i in schemes.indices) {
            val dot = View(this).apply {
                background = oval(schemes[i], if (i == cur) Pal.FG else 0, max(2, m(0.5f)))
                setOnClickListener {
                    prefs.colorScheme = i
                    val keep = page
                    buildUi()
                    showPage(keep)
                    showSettings()
                }
            }
            sw.addView(dot, lpm(m(5.6f), m(5.6f), 0f, m(0.7f), 0, m(0.7f), 0))
        }
        box.addView(sw, lp(-1, rowH))

        openSheet(getString(R.string.menu_settings_t), box, Act(getString(R.string.act_ok), primary = true) {})
    }

    private fun showDiagnostics() {
        val s = service ?: return
        val body = txt("", tx(2.4f), Pal.TEXT).apply { typeface = Typeface.MONOSPACE }
        fun refresh() {
            body.text = fmt(R.string.diag_audio, s.diagnostics()) + "\n\n" + fmt(R.string.diag_usb, s.state.usb) + "\n" + fmt(R.string.diag_mode, s.state.mode) +
                (if (s.state.mode == "fm") "\n" + fmt(R.string.diag_fm, s.state.rssi, s.state.stereo, s.state.rdsSync) else "") +
                (if (s.state.mode == "dab") "\n" + fmt(R.string.diag_dab, s.state.dabSnr, s.state.dabSync, s.state.dabCodec, s.state.dabFibErr, s.state.dabMscOk, s.state.dabMscErr, s.state.dabRsErr) else "")
        }
        refresh()
        openSheet(getString(R.string.menu_diag_t), body, Act(getString(R.string.act_close), primary = true) {})
        sheetRefresh = ::refresh
    }

    private fun showAbout() {
        val url = getString(R.string.source_url)
        val faad = getString(R.string.faad_notice)
        openSheet(
            "Radvela " + versionName(),
            txt(getString(R.string.about_text) + (if (faad.isNotEmpty()) "\n\n$faad" else "") + (if (url.isNotEmpty()) "\n\n" + fmt(R.string.about_source, url) else ""), tx(2.6f), Pal.TEXT),
            Act(getString(R.string.act_ok), primary = true) {}
        )
    }

    // ------------------------------------------------------------------ Kayıt, yardım, gizlilik

    private val eqNames = intArrayOf(R.string.eq_flat, R.string.eq_bass, R.string.eq_vocal, R.string.eq_treble, R.string.eq_loud)

    internal fun toggleRec() {
        service?.toggleRecording()
    }

    private fun sleepSub(): String {
        val at = service?.state?.sleepAtMs ?: 0L
        if (at == 0L) return getString(R.string.sleep_s)
        val min = ((at - System.currentTimeMillis()) / 60_000L + 1).toInt()
        return fmt(R.string.sleep_left, max(1, min))
    }

    /** Seçenek listesi paneli (ekolayzer, uyku). */
    private fun choiceSheet(title: String, options: List<Pair<String, Boolean>>, pick: (Int) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { i, (label, on) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(m(2.2f), 0, m(2.2f), 0)
                isClickable = true
                background = pressable(
                    rr(if (on) alpha(accent, 0x2E) else Pal.SURFACE, m(2.4f), if (on) accent else Pal.STROKE),
                    rr(Pal.SURFACE_PRESSED, m(2.4f), Pal.STROKE)
                )
                setOnClickListener { pick(i); closeSheet(); lastMenuSig = ""; renderAll() }
            }
            row.addView(txt(label, tx(2.9f), if (on) accentText else Pal.FG, true).apply { maxLines = 1 }, lp(0, -2, 1f))
            if (on) row.addView(GlyphView(this, Glyph.CHECK, accent), lp(m(4f), m(4f)))
            box.addView(row, lpm(-1, m(8.2f), 0f, 0, m(0.5f), 0, m(0.5f)))
        }
        openSheet(title, box, Act(getString(R.string.act_close), primary = true) {})
    }

    internal fun refresh() = renderAll()

    internal fun openMenu() = showPage(Page.MENU)

    /** Temalar için istasyon satırları (favori önceliği olmadan tüm liste). */
    internal fun presetRows(s: RadioService, mode: String): List<Row> {
        val cur = s.currentKey()
        return if (mode == "dab") s.dabList.map { sv ->
            Row(sv.key, sv.label, "DAB" + (if (sv.aac) "+" else "") + " " + sv.channel, "", sv.key in s.favorites, sv.key == cur, { s.playDab(sv) }, { s.toggleFavorite(sv.key) }, sv.label)
        } else s.fmList.sortedBy { it.freqHz }.map { st ->
            Row(st.key, if (st.name.isNotEmpty()) st.name else String.format(Locale.US, "%.1f", st.freqHz / 1e6),
                if (st.name.isNotEmpty()) String.format(Locale.US, "%.1f MHz", st.freqHz / 1e6) else "FM", "", st.key in s.favorites, st.key == cur,
                { s.playFm(st.freqHz) }, { s.toggleFavorite(st.key) }, st.name)
        }
    }

    /** Radyo durumunu temaların gördüğü tek veri nesnesine çevirir; desteklenmeyen bilgi boş kalır. */
    private fun nowOf(s: RadioService?, mode: String): Now {
        val st = s?.state
        val scanFm = st != null && st.scanning && st.scanKind == "fm" && st.scanHz > 0
        val scanDab = st != null && st.scanning && st.scanKind == "dab" && st.scanText.isNotEmpty()
        val fmHz = when { scanFm -> st!!.scanHz; st?.mode == "fm" -> st.fmHz; else -> prefs.lastFmHz }
        val fmLabel = String.format(Locale.US, "%.2f", fmHz / 1e6)
        val dabCh = when { scanDab -> st!!.scanText; st?.mode == "dab" && st.dabChannel.isNotEmpty() -> st.dabChannel; else -> prefs.lastDabKey.split(':').getOrNull(1) ?: "--" }
        val main = if (mode == "dab") dabCh else fmLabel
        val unit = if (mode == "dab") "DAB+" else "MHz"
        var title = main + " " + unit
        var sub = ""
        var text = ""
        var logoName = ""
        var signal = 0f
        val chips = ArrayList<Pair<String, Boolean>>()
        chips.add((if (mode == "dab") "DAB+" else "FM") to false)
        if (st != null && !st.scanning) when (st.mode) {
            "fm" -> {
                if (st.ps.isNotEmpty()) title = st.ps
                sub = Rds.ptyName(this, st.pty)
                text = st.rt
                logoName = s.fmList.firstOrNull { it.freqHz == st.fmHz }?.name ?: ""
                signal = (st.rssi + 90f) / 60f
                chips.add(getString(if (st.stereo) R.string.chip_stereo else R.string.chip_mono) to st.stereo)
                if (st.rdsSync) chips.add("RDS" to true)
            }
            "dab" -> {
                if (st.dabLabel.isNotEmpty()) title = st.dabLabel
                sub = st.dabEnsemble
                text = st.dls
                logoName = st.dabLabel
                signal = st.dabSnr / 20f
                chips.add(getString(if (st.dabSync == 2) R.string.chip_sync else if (st.dabSync == 1) R.string.chip_weak else R.string.chip_nosignal) to (st.dabSync == 2))
                if (st.dabSync > 0) chips.add(fmt(R.string.chip_snr, st.dabSnr) to false)
                if (st.dabCodec.isNotEmpty()) chips.add(st.dabCodec.substringBefore(' ') to false)
            }
            else -> sub = getString(if (st.connected) R.string.sub_idle_ready else R.string.sub_idle_noconn)
        }
        val key = s?.currentKey() ?: ""
        val scanLine = if (st?.scanning == true) getString(if (st.scanKind == "fm") R.string.scan_fm_running else R.string.scan_dab_running) + " " + main + " " + unit else ""
        return Now(mode, title, main, unit, sub, text, chips, signal, st?.playing == true, key.isNotEmpty() && s?.favorites?.contains(key) == true, key,
            logoName, st?.slide, st?.recording == true, st?.muted == true, st?.scanning == true, scanLine, st?.scanProgress ?: 0,
            st?.message ?: "", st?.usb ?: getString(R.string.status_starting), st?.connected == true)
    }

    /** Tema seçimi: küçük önizlemeler ve görünüm ayarları. */
    private fun showGallery() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cur = ThemeManager.current(prefs)
        val cols = if (land) 3 else 2
        val rows = ThemeManager.ALL.chunked(cols)
        for (chunk in rows) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (t in chunk) {
                val on = t.id == cur
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = true
                    setPadding(m(0.8f), m(0.8f), m(0.8f), m(0.8f))
                    background = pressable(rr(Pal.SURFACE, m(1.6f), if (on) accent else Pal.STROKE, m(0.3f)), rr(Pal.SURFACE_PRESSED, m(1.6f), Pal.STROKE))
                    setOnClickListener {
                        if (t.id != cur) {
                            prefs.theme = t.id
                            prefs.colorScheme = t.accent
                        }
                        closeSheet()
                        buildUi()
                        showPage(Page.MAIN)
                    }
                    addView(ThemePreview(this@MainActivity, t.id), lp(-1, m(11f)))
                    addView(txt(t.name, tx(2.4f), if (on) accentText else Pal.FG, true).apply { maxLines = 1 }, lpm(-1, -2, 0f, 0, m(0.6f), 0, 0))
                    addView(txt(getString(t.descId), tx(1.9f), Pal.DIM).apply { maxLines = 2 }, lp(-1, -2))
                }
                r.addView(card, lpm(0, -2, 1f, m(0.4f), m(0.4f), m(0.4f), m(0.4f)))
            }
            for (i in chunk.size until cols) r.addView(View(this), lpm(0, 1, 1f, m(0.4f), 0, m(0.4f), 0))
            box.addView(r, lp(-1, -2))
        }
        fun toggleRow(title: String, on: Boolean, change: (Boolean) -> Unit) {
            val tv = ToggleView(this).apply { accent = this@MainActivity.accent; this.on = on }
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { tv.on = !tv.on; change(tv.on) }
                addView(txt(title, tx(2.6f), Pal.FG, true).apply { maxLines = 2 }, lp(0, -2, 1f))
                addView(tv, lp(m(8.4f), m(4.6f)))
            }, lpm(-1, m(7.5f), 0f, m(0.4f), m(0.6f), m(0.4f), 0))
        }
        toggleRow(getString(R.string.set_anim), prefs.animations) { prefs.animations = it }
        toggleRow(getString(R.string.set_wood), prefs.classicWood) {
            prefs.classicWood = it
            closeSheet()
            buildUi()
            showPage(Page.MENU)
        }
        val styles = intArrayOf(R.string.list_cards, R.string.list_compact, R.string.list_rows)
        val styleBtn = Btn(false, null, getString(R.string.set_list) + ": " + getString(styles[prefs.listStyle.coerceIn(0, 2)]), 0, tx(2.5f)) {
            prefs.listStyle = (prefs.listStyle + 1) % styles.size
            closeSheet()
            showGallery()
        }
        box.addView(styleBtn.view, lpm(-1, m(7.5f), 0f, m(0.4f), m(0.8f), m(0.4f), 0))
        openSheet(getString(R.string.gal_t), box, Act(getString(R.string.act_close), primary = true) {})
    }

    /** Ses seviyesi ve sessize alma. */
    internal fun showVolume() {
        val s = service ?: return
        var vol = s.prefs.audioGainPct.coerceIn(0, 200)
        val value = txt(fmt(R.string.fmt_percent, vol), tx(6f), accentText, true).apply { gravity = Gravity.CENTER }
        fun step(d: Int) {
            vol = (vol + d).coerceIn(0, 200)
            s.setAudioGain(vol)
            value.text = fmt(R.string.fmt_percent, vol)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(Btn(true, Glyph.MINUS, null, m(4f), 0f) { step(-10) }.view, lp(m(11f), m(11f)))
            addView(value, lp(0, -2, 1f))
            addView(Btn(true, Glyph.PLUS, null, m(4f), 0f) { step(+10) }.view, lp(m(11f), m(11f)))
        }
        openSheet(getString(R.string.vol_t), row, Act(getString(if (s.state.muted) R.string.act_unmute else R.string.act_mute)) { s.toggleMute() }, Act(getString(R.string.act_ok), primary = true) {})
    }

    private fun showSleep() {
        val s = service ?: return
        val mins = listOf(0, 15, 30, 60, 90)
        val active = s.state.sleepAtMs != 0L
        choiceSheet(getString(R.string.sleep_t), mins.map { m ->
            (if (m == 0) getString(R.string.sleep_off) else fmt(R.string.sleep_min, m)) to (m == 0 && !active)
        }) { i -> s.setSleepTimer(mins[i]) }
    }

    private fun showEq() {
        val s = service ?: return
        choiceSheet(getString(R.string.eq_t), eqNames.mapIndexed { i, id -> getString(id) to (i == prefs.eqPreset) }) { i -> s.setEqPreset(i) }
    }

    private fun startBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "radvela-stations.json")
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_BACKUP)
    }

    private fun startRestore() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_RESTORE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        val s = service
        if (resultCode != RESULT_OK || uri == null || s == null) return
        try {
            when (requestCode) {
                REQ_BACKUP -> {
                    contentResolver.openOutputStream(uri)!!.use { it.write(s.exportStations().toByteArray()) }
                    s.showMessage(getString(R.string.msg_backup_ok))
                }
                REQ_RESTORE -> {
                    val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    s.showMessage(getString(if (s.importStations(text)) R.string.msg_restore_ok else R.string.msg_file_fail))
                }
            }
        } catch (e: java.io.IOException) {
            s.showMessage(getString(R.string.msg_file_fail))
        }
    }

    private fun showHelp() {
        openSheet(getString(R.string.help_t), txt(getString(R.string.help_body), tx(2.7f), Pal.TEXT), Act(getString(R.string.act_ok), primary = true) { prefs.onboarded = true })
    }

    private fun showPrivacy() {
        openSheet(getString(R.string.privacy_t), txt(getString(R.string.privacy_body), tx(2.7f), Pal.TEXT), Act(getString(R.string.act_ok), primary = true) {})
    }

    /** Yayıncının gönderdiği DAB slaytını büyük gösterir. */
    private fun showSlide() {
        val bmp = service?.state?.slide ?: return
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        openSheet(service?.state?.dabLabel ?: "", iv, Act(getString(R.string.act_close), primary = true) {})
    }

    // ------------------------------------------------------------------ Dil

    private fun showLanguage() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cur = prefs.language
        val options = listOf("" to getString(R.string.lang_auto)) + L10n.languages
        for ((code, name) in options) {
            val on = code == cur
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(m(2.2f), 0, m(2.2f), 0)
                isClickable = true
                background = pressable(
                    rr(if (on) alpha(accent, 0x2E) else Pal.SURFACE, m(2.4f), if (on) accent else Pal.STROKE),
                    rr(Pal.SURFACE_PRESSED, m(2.4f), Pal.STROKE)
                )
                setOnClickListener { setLanguage(code) }
            }
            row.addView(txt(name, tx(2.9f), if (on) accentText else Pal.FG, true).apply { maxLines = 1 }, lp(0, -2, 1f))
            if (on) row.addView(GlyphView(this, Glyph.CHECK, accent), lp(m(4f), m(4f)))
            box.addView(row, lpm(-1, m(8.2f), 0f, 0, m(0.5f), 0, m(0.5f)))
        }
        openSheet(getString(R.string.lang_title), box, Act(getString(R.string.act_close), primary = true) {})
    }

    private fun setLanguage(code: String) {
        closeSheet()
        if (code == prefs.language) return
        prefs.language = code
        service?.reloadLanguage()
        reopenMenu = true
        recreate()
    }

    // ------------------------------------------------------------------ Eylemler

    internal fun uiModeNow(): String {
        val s = service
        return if (s != null && s.state.mode != "idle") s.state.mode else prefs.lastMode
    }

    internal fun selectMode(mode: String) {
        val s = service ?: return
        if (s.state.connected && !s.state.scanning && s.state.mode != mode) {
            if (mode == "fm") s.playFm(s.prefs.lastFmHz)
            else (s.dabList.firstOrNull { it.key == s.prefs.lastDabKey } ?: s.dabList.firstOrNull())?.let { s.playDab(it) }
                ?: s.showMessage(getString(R.string.msg_dab_list_empty))
        } else if (!s.state.connected) {
            s.prefs.lastMode = mode
        }
        renderAll()
    }

    private fun startScanFromList() {
        val s = service ?: return
        showPage(Page.MAIN)
        if (listKind == "fm") s.scanFm() else s.scanDab()
    }

    // ------------------------------------------------------------------ Gösterim

    /** "98.10" + küçük, vurgu renkli " MHz" */
    private fun freqSpan(main: String, unit: String): CharSequence {
        val sb = SpannableStringBuilder(main).append(" ").append(unit)
        val start = main.length
        sb.setSpan(RelativeSizeSpan(0.27f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(accentText), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun chip(text: String, hot: Boolean): TextView = txt(text, tx(2.2f), if (hot) accentText else Pal.DIM, true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(m(1.3f), m(0.8f), m(1.3f), m(0.8f))
        background = rr(if (hot) alpha(accent, 0x22) else Pal.SURFACE, m(2.2f), if (hot) alpha(accent, 0x88) else Pal.STROKE)
    }

    private fun setChips(items: List<Pair<String, Boolean>>) {
        val sig = items.joinToString("|") { it.first + it.second } + accent
        if (sig == lastChipSig) return
        lastChipSig = sig
        chipRow.removeAllViews()
        for ((t, hot) in items) chipRow.addView(chip(t, hot), lpm(-2, -2, 0f, 0, 0, m(1f), 0))
    }

    internal fun presetItems(s: RadioService, mode: String): List<Row> {
        val cur = s.currentKey()
        return if (mode == "dab") {
            val fav = s.dabList.filter { it.key in s.favorites }
            (if (fav.isNotEmpty()) fav else s.dabList).map { sv ->
                Row(sv.key, sv.label, "DAB" + (if (sv.aac) "+" else "") + " " + sv.channel, "", sv.key in s.favorites, sv.key == cur, { s.playDab(sv) }, { s.toggleFavorite(sv.key) }, sv.label)
            }
        } else {
            val sorted = s.fmList.sortedBy { it.freqHz }
            val fav = sorted.filter { it.key in s.favorites }
            (if (fav.isNotEmpty()) fav else sorted).map { st ->
                Row(st.key, if (st.name.isNotEmpty()) st.name else String.format(Locale.US, "%.1f", st.freqHz / 1e6),
                    if (st.name.isNotEmpty()) String.format(Locale.US, "%.1f MHz", st.freqHz / 1e6) else "FM", "", st.key in s.favorites, st.key == cur,
                    { s.playFm(st.freqHz) }, { s.toggleFavorite(st.key) }, st.name)
            }
        }
    }

    private val badgePalette = intArrayOf(0xFFC0562B.toInt(), 0xFF2B7A8C.toInt(), 0xFF7B4EA3.toInt(), 0xFF3E8E5A.toInt(), 0xFFB8892B.toInt(), 0xFFB04557.toInt(), 0xFF4F6FB5.toInt())

    /** Logo adı doluysa önbellekten logoyu verir ve yoksa arka planda aranmasını ister. */
    internal fun logoFor(name: String): android.graphics.Bitmap? {
        if (name.isBlank()) return null
        Logos.request(name)
        return Logos.get(name)
    }

    internal fun badgeColor(title: String): Int = badgePalette[(title.hashCode() and 0x7FFFFFFF) % badgePalette.size]

    internal fun initialsOf(name: String): String {
        val parts = name.replace('.', ' ').split(' ').filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "FM"
            // Yalnız frekans olan istasyonlarda ("98.1") rozete tam sayı kısmı yazılır
            parts[0][0].isDigit() -> parts[0].substringBefore('.').take(3)
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> ("" + parts[0][0] + parts[1][0]).uppercase()
        }
    }

    /** Klasik tema hazır istasyon düğmesi: renkli baş harf rozeti (logo yerine), altında istasyon adı. */
    private fun classicTile(row: Row): View {
        val radius = m(2.4f)
        fun bg(pressed: Boolean): Drawable {
            val colors = if (row.current) intArrayOf(alpha(accent, if (pressed) 0x90 else 0x66), 0xFF2A1C12.toInt())
            else intArrayOf(if (pressed) 0xFF5A4030.toInt() else 0xFF4A3322.toInt(), 0xFF2A1C12.toInt())
            return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
                cornerRadius = radius.toFloat()
                setStroke(if (row.current) max(2, m(0.28f)) else max(1, m(0.12f)), if (row.current) accent else 0x32FFFFFF)
            }
        }
        val badge = BadgeView(this).apply {
            label = initialsOf(row.title)
            color = badgePalette[(row.title.hashCode() and 0x7FFFFFFF) % badgePalette.size]
            logo = logoFor(row.logoName)
        }
        val name = txt(row.title, tx(2.8f), if (row.current) accent else Pal.WARM_TEXT, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(m(1.0f), m(1.0f), m(1.0f), m(1.0f))
            addView(badge, lp(-1, 0, 1f))
            addView(name, lpm(-1, -2, 0f, 0, m(0.5f), 0, 0))
        }
        return FrameLayout(this).apply {
            isClickable = true
            background = pressable(bg(false), bg(true))
            setOnClickListener { row.onClick() }
            setOnLongClickListener { row.onLong?.invoke(); true }
            addView(col, FrameLayout.LayoutParams(-1, -1))
            if (row.fav) addView(GlyphView(context, Glyph.STAR, accent),
                FrameLayout.LayoutParams(m(2.6f), m(2.6f), Gravity.TOP or Gravity.END).apply { setMargins(0, m(0.8f), m(0.8f), 0) })
        }
    }

    private fun renderPresets(s: RadioService, mode: String) {
        val items = presetItems(s, mode)
        val sig = mode + "|" + accent + "|" + Logos.version + "|" + items.joinToString(";") { it.key + it.title + it.current + it.fav }
        if (sig == lastPresetSig) return
        lastPresetSig = sig
        presetRow.removeAllViews()
        val chipW = if (land) wPct(15.4f) else wPct(29f)
        if (items.isEmpty()) {
            val hint = txt(
                getString(if (mode == "dab") R.string.empty_dab else R.string.empty_fm),
                tx(2.5f), if (classic) Pal.WOOD_DIM else Pal.DIM
            ).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(m(2f), 0, m(2f), 0); maxLines = 2 }
            presetRow.addView(hint, lp(winW - m(4.4f), -1))
            return
        }
        var curView: View? = null
        for (row in items) {
            val v: View = if (classic) classicTile(row) else LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(m(1.8f), m(1f), m(1.8f), m(1f))
                background = if (row.current) pressable(rr(accent, m(2.6f)), rr(alpha(accent, 0xCC), m(2.6f)))
                else pressable(rr(Pal.CARD, m(2.6f), Pal.STROKE), rr(Pal.SURFACE_PRESSED, m(2.6f), Pal.STROKE))
                setOnClickListener { _ -> row.onClick() }
                setOnLongClickListener { _ -> row.onLong?.invoke(); true }
                val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(BadgeView(context).apply {
                    label = initialsOf(row.title); color = badgeColor(row.title); logo = logoFor(row.logoName)
                }, lpm(m(5.4f), m(5.4f), 0f, 0, 0, m(1.2f), 0))
                head.addView(txt(row.title, tx(3.1f), if (row.current) Color.BLACK else Pal.FG, true).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, lp(0, -2, 1f))
                if (row.fav) head.addView(GlyphView(context, Glyph.STAR, if (row.current) Color.BLACK else accentText), lpm(m(2.6f), m(2.6f), 0f, m(0.6f), 0, 0, 0))
                addView(head, lp(-1, -2))
                addView(txt(row.subtitle, tx(2.2f), if (row.current) alpha(Color.BLACK, 0xA6) else Pal.DIM).apply { maxLines = 1 }, lpm(-1, -2, 0f, 0, m(0.5f), 0, 0))
            }
            presetRow.addView(v, lpm(chipW, -1, 0f, 0, 0, m(1.2f), 0))
            if (row.current) curView = v
        }
        curView?.let { cv -> presetScroll.post { presetScroll.smoothScrollTo(max(0, cv.left - (winW - chipW) / 2), 0) } }
    }

    private fun rows(s: RadioService): List<Row> {
        val fav = s.favorites
        val cur = s.currentKey()
        return if (listKind == "fm") {
            s.visibleFm().map { st ->
                Row(st.key, st.title(), if (st.name.isNotEmpty()) formatMhz(st.freqHz) else "", if (st.name.isNotEmpty()) "" else "FM", st.key in fav, st.key == cur,
                    { showPage(Page.MAIN); s.playFm(st.freqHz) }, { s.toggleFavorite(st.key) }, st.name)
            }
        } else {
            s.visibleDab().map { sv ->
                Row(sv.key, sv.label, fmt(R.string.row_dab_channel, if (sv.aac) "+" else "", sv.channel), sv.channel, sv.key in fav, sv.key == cur,
                    { showPage(Page.MAIN); s.playDab(sv) }, { s.toggleFavorite(sv.key) }, sv.label)
            }
        }
    }

    private fun renderList(s: RadioService) {
        styleTab(listFmBtn, listKind == "fm")
        styleTab(listDabBtn, listKind == "dab")
        styleTab(listFavBtn, s.prefs.favoritesOnly)
        val rowList = rows(s)
        val sig = listKind + "|" + s.prefs.favoritesOnly + "|" + accent + "|" + Logos.version + "|" + rowList.joinToString(";") { it.key + it.title + it.fav + it.current }
        if (sig != lastListSig) {
            lastListSig = sig
            listAdapter.clear()
            listAdapter.addAll(rowList)
            listAdapter.notifyDataSetChanged()
        }
        listEmpty.visibility = if (rowList.isEmpty()) View.VISIBLE else View.GONE
        listEmpty.text = getString(if (s.prefs.favoritesOnly) R.string.empty_fav else R.string.empty_list)
    }

    /** DAB+ kalite satırı ve (varsa) sorunun nedeni: sinyal mi zayıf, yoksa çözücü/servis mi sorunlu? */
    private fun dabQualityText(st: RadioState): CharSequence {
        val head = if (st.dabSync == 0) getString(R.string.dabq_nosignal)
        else fmt(R.string.dabq_head, st.dabSnr, st.dabFibErr, st.dabMscOk, st.dabMscErr)
        val total = st.dabMscOk + st.dabMscErr
        val problem: String? = when {
            st.dabSync == 0 -> getString(R.string.dabq_nosync)
            st.dabSync == 1 -> getString(R.string.dabq_weak)
            total == 0 -> if (st.playing) getString(R.string.dabq_waiting) else null
            st.dabMscErr * 3 > total -> fmt(R.string.dabq_errors, 100 * st.dabMscErr / total)
            st.dabMscOk > 0 && st.dabCodec.isEmpty() -> getString(R.string.dabq_nodecoder)
            else -> null
        }
        val sb = SpannableStringBuilder(head)
        if (problem != null) {
            val start = sb.length + 1
            sb.append("\n").append(problem)
            sb.setSpan(ForegroundColorSpan(Pal.WARN), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (st.dabSync == 2 && total > 0) {
            sb.append("\n").append(st.dabCodec)
        }
        return sb
    }

    private fun setNowLogo(name: String) {
        val lg = service?.state?.slide ?: logoFor(name.trim())
        nowLogo.logo = lg
        val vis = if (lg != null) View.VISIBLE else View.GONE
        if (nowLogo.visibility != vis) nowLogo.visibility = vis
    }

    private fun renderAll() {
        if (!::root.isInitialized) return
        val s = service
        // Tarama sürerken göstergeler taranan banda geçer (FM'deyken DAB+ taransa da kadran DAB kanallarını gösterir)
        val mode = s?.state?.takeIf { it.scanning }?.scanKind ?: uiModeNow()
        s?.spectrumOn = customUi?.wantsSpectrum == true && prefs.animations && page == Page.MAIN
        customUi?.let { ui ->
            if (page == Page.LIST && s != null) renderList(s)
            if (page == Page.MENU) fillMenu()
            ui.render(s, nowOf(s, mode))
            return
        }
        styleSeg(segFm, mode == "fm")
        styleSeg(segDab, mode == "dab")
        rowFm.visibility = if (mode == "fm") View.VISIBLE else View.GONE
        rowDab.visibility = if (mode == "dab") View.VISIBLE else View.GONE
        tuner.visibility = if (mode == "fm") View.VISIBLE else View.GONE
        dabInfo.visibility = if (mode == "dab") View.VISIBLE else View.GONE

        if (page == Page.LIST && s != null) renderList(s)
        if (page == Page.MENU) fillMenu()
        sheetView?.let { if (service == null) closeSheet() }

        if (s == null) {
            statusText.put(getString(R.string.status_starting))
            return
        }
        val st = s.state
        statusText.put(st.usb)
        val dotColor = if (st.connected) accent else Pal.WARN
        if (statusDot.tag != dotColor) { statusDot.tag = dotColor; statusDot.background = oval(dotColor) }
        tuner.accent = accent
        signal.accent = accent
        scanBar.progressTintList = ColorStateList.valueOf(accent)

        // Tarama
        scanBox.visibility = if (st.scanning) View.VISIBLE else View.GONE
        ctlInner.visibility = if (st.scanning) View.INVISIBLE else View.VISIBLE
        if (st.scanning) {
            scanText.put(getString(if (st.scanKind == "fm") R.string.scan_fm_running else R.string.scan_dab_running) + " " + st.scanText)
            scanBar.progress = st.scanProgress
        }
        msgText.put(st.message)
        msgText.visibility = if (st.message.isEmpty()) View.GONE else View.VISIBLE

        // Düğmeler
        btnPlay.glyph(if (st.playing) Glyph.PAUSE else Glyph.PLAY, true, Pal.FG)
        val key = s.currentKey()
        val isFav = key.isNotEmpty() && key in s.favorites
        btnFav.glyph(if (isFav) Glyph.STAR else Glyph.STAR_O, isFav, Pal.FG)
        val capColor = if (classic) Pal.WOOD_DIM else Pal.FG
        if (st.recording) {
            val sec = ((System.currentTimeMillis() - st.recordStartMs) / 1000).toInt()
            btnRec.glyph(Glyph.STOP, false, Pal.DANGER)
            btnRec.caption(String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60), Pal.DANGER)
        } else {
            btnRec.glyph(Glyph.REC, false, Pal.DANGER)
            btnRec.caption(getString(R.string.btn_rec), capColor)
        }

        renderPresets(s, mode)

        when (st.mode) {
            "fm" -> {
                if (!(dial?.dragging ?: tuner.dragging)) freqText.put(freqSpan(String.format(Locale.US, "%.2f", st.fmHz / 1e6), "MHz"))
                nameText.put(if (st.ps.isNotEmpty()) st.ps else " ")
                val pty = Rds.ptyName(this, st.pty)
                val list = s.fmList.sortedBy { it.freqHz }
                val idx = list.indexOfFirst { it.freqHz == st.fmHz }
                setNowLogo(if (idx >= 0) list[idx].name else "")
                subText.put((if (pty.isNotEmpty()) "$pty  ·  " else "") + (if (idx >= 0) fmt(R.string.list_pos, idx + 1, list.size) else getString(R.string.list_none)))
                rtText.put(st.rt)
                signal.level = (st.rssi + 90f) / 60f
                tuner.freqHz = st.fmHz
                tuner.markers = list.map { it.freqHz }
                tuner.favs = list.filter { it.key in s.favorites }.map { it.freqHz }.toSet()
                setChips(listOf("FM" to false, getString(if (st.stereo) R.string.chip_stereo else R.string.chip_mono) to st.stereo) + (if (st.rdsSync) listOf("RDS" to true) else emptyList()))
            }
            "dab" -> {
                freqText.put(freqSpan(if (st.dabChannel.isNotEmpty()) st.dabChannel else "--", "DAB+"))
                nameText.put(if (st.dabLabel.isNotEmpty()) st.dabLabel else " ")
                setNowLogo(st.dabLabel)
                subText.put(if (st.dabEnsemble.isNotEmpty()) st.dabEnsemble else fmt(R.string.sub_channel, st.dabChannel))
                rtText.put(st.dls)
                signal.level = st.dabSnr / 20f
                dabInfo.put(dabQualityText(st))
                setChips(listOf("DAB+" to false, getString(if (st.dabSync == 2) R.string.chip_sync else if (st.dabSync == 1) R.string.chip_weak else R.string.chip_nosignal) to (st.dabSync == 2)) +
                    (if (st.dabSync > 0) listOf(fmt(R.string.chip_snr, st.dabSnr) to false) else emptyList()) +
                    (if (st.dabCodec.isNotEmpty()) listOf(st.dabCodec.substringBefore(' ') to false) else emptyList()))
            }
            else -> {
                if (mode == "dab") {
                    freqText.put(freqSpan("--", "DAB+"))
                    dabInfo.put("")
                } else {
                    freqText.put(freqSpan(String.format(Locale.US, "%.2f", prefs.lastFmHz / 1e6), "MHz"))
                    tuner.freqHz = prefs.lastFmHz
                    val list = s.fmList.sortedBy { it.freqHz }
                    tuner.markers = list.map { it.freqHz }
                    tuner.favs = list.filter { it.key in s.favorites }.map { it.freqHz }.toSet()
                }
                nameText.put("Radvela")
                setNowLogo("")
                subText.put(getString(if (st.connected) R.string.sub_idle_ready else R.string.sub_idle_noconn))
                rtText.put("")
                signal.level = 0f
                setChips(listOf((if (mode == "dab") "DAB+" else "FM") to false, getString(R.string.chip_off) to false))
            }
        }

        // Taranan frekans/kanal dijital göstergede
        val scanFmHz = if (st.scanning && st.scanKind == "fm") st.scanHz else 0
        val scanChannel = if (st.scanning && st.scanKind == "dab") st.scanText else ""
        if (scanFmHz > 0) {
            freqText.put(freqSpan(String.format(Locale.US, "%.2f", scanFmHz / 1e6), "MHz"))
            tuner.freqHz = scanFmHz
        } else if (scanChannel.isNotEmpty()) {
            freqText.put(freqSpan(scanChannel, "DAB+"))
        }

        // Klasik tema: kadran
        dial?.let { dl ->
            val fmList = s.fmList.sortedBy { it.freqHz }
            dl.accent = accent
            dl.animate = prefs.animations
            dl.dabMode = mode == "dab"
            dl.markers = fmList.map { it.freqHz }
            dl.favs = fmList.filter { it.key in s.favorites }.map { it.freqHz }.toSet()
            dl.freqHz = if (scanFmHz > 0) scanFmHz else if (st.mode == "fm") st.fmHz else prefs.lastFmHz
            dl.dabChannel = if (scanChannel.isNotEmpty()) scanChannel
            else if (st.mode == "dab" && st.dabChannel.isNotEmpty()) st.dabChannel
            else prefs.lastDabKey.split(':').getOrNull(1) ?: ""
            dl.dabHave = s.dabList.map { it.channel }.toSet()
        }
        for (v in listOf(btnPlay, btnFav, btnRec)) (v as? ChromeButton)?.accent = accent
    }
}
