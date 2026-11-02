package app.otoradyo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.telephony.TelephonyManager
import android.util.LruCache
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * İstasyon logoları. Adı verilen istasyon için topluluk veri tabanından (radio-browser.info) logo bulunur,
 * küçültülüp önbelleğe (cacheDir/logos) yazılır. İnternet yoksa ya da logo bulunamazsa arayüz renkli rozete döner.
 * Ana iş parçacığından çağrılır; ağ işleri tek bir arka plan iş parçacığında yapılır.
 */
object Logos {
    /** Yeni logo eklendikçe artar; arayüz imzalarına katılarak yeniden çizimi tetikler. */
    @Volatile var version = 0
        private set
    /** Ayar: internetten logo getirme. Kapalıyken yalnız önbellekteki logolar kullanılır. */
    @Volatile var enabled = true
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** Yeni logo geldiğinde ana iş parçacığında çağrılır. */
    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private const val TAG = "OtoRadyo"
    private const val UA = "Radvela/0.5 (Android)"
    private val HOSTS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info", "nl1.api.radio-browser.info", "at1.api.radio-browser.info")
    private const val RETRY_FAIL_MS = 10 * 60 * 1000L           // ağ hatasında tekrar deneme aralığı
    private const val RETRY_NONE_MS = 14L * 24 * 3600 * 1000    // "logo yok" kaydı bu kadar süre geçerli
    private const val MAX_PENDING = 60
    /** Eşleştirme algoritması sürümü: arttırılınca eski "logo yok" kayıtları geçersiz olur ve istasyonlar yeniden denenir. */
    private const val ALGO = 10

    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val mem = LruCache<String, Bitmap>(64)
    private val pending = LinkedHashSet<String>()
    private val failedAt = HashMap<String, Long>()
    /** Diskte logosu olmadığı bilinenler (ana iş parçacığında her satır için dosya sorgusu yapılmasın). */
    private val absent: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Bu oturumda "logo yok" sonucu bilinenler (tekrar sorgulanmaz). */
    private val noLogo: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // GEÇİCİ tanılama: sonuç sayaçları (Logcat'te "logo durum" satırı); doğrulandıktan sonra kaldırılır
    @Volatile private var dOk = 0
    @Volatile private var dNone = 0
    @Volatile private var dFail = 0
    @Volatile private var dLast = ""
    private val failNames = ArrayDeque<String>()
    private fun noteFail(label: String) { synchronized(failNames) { failNames.addLast(label.take(14)); while (failNames.size > 8) failNames.removeFirst() } }
    private val noneNames = ArrayDeque<String>()
    private fun noteNone(label: String) { synchronized(noneNames) { noneNames.addLast(label.take(14)); while (noneNames.size > 8) noneNames.removeFirst() } }
    private var diagTicks = 0
    private val diagRun = object : Runnable {
        override fun run() {
            Log.i(TAG, "logo durum: bulunan=$dOk yok=$dNone hata=$dFail bekleyen=${synchronized(pending) { pending.size }} eşleşme_yok=${synchronized(noneNames) { noneNames.joinToString("|") }} görsel_yok=${synchronized(failNames) { failNames.joinToString("|") }} son=[$dLast]")
            if (++diagTicks < 150) main.postDelayed(this, 1500)
        }
    }

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        enabled = Prefs(ctx.applicationContext).onlineLogos
        purgeOldAlgo()
        main.postDelayed(diagRun, 1500)
    }

    /** Eşleştirme kuralları değişince eski sürümün seçtiği (yanlış olabilecek) logolar bir kez silinir. */
    private fun purgeOldAlgo() {
        val d = dir() ?: return
        val mark = File(d, "algo$ALGO")
        if (mark.isFile) return
        d.listFiles()?.forEach { it.delete() }
        try { mark.writeText("1") } catch (_: IOException) {}
    }

    private fun dir(): File? = app?.let { File(it.cacheDir, "logos").also { d -> d.mkdirs() } }

    /** Önbellekteki logo (bellek ya da disk); yoksa null. Hızlıdır, ağa gitmez. */
    fun get(name: String): Bitmap? {
        val key = LogoMatch.normalize(name)
        if (key.isEmpty()) return null
        mem.get(key)?.let { return it }
        if (key in absent) return null
        val f = File(dir() ?: return null, "$key.png")
        if (!f.isFile) { absent.add(key); return null }
        val b = try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } ?: return null
        mem.put(key, b)
        return b
    }

    /** Logo yoksa arka planda aranmasını ister (kopya istekler yok sayılır). */
    fun request(name: String) {
        if (!enabled || app == null) return
        val key = LogoMatch.normalize(name)
        if (key.length < 2 || key in noLogo) return
        if (mem.get(key) != null) return
        val d = dir() ?: return
        if (key !in absent && File(d, "$key.png").isFile) return
        val none = File(d, "$key.n$ALGO")
        if (none.isFile && System.currentTimeMillis() - none.lastModified() < RETRY_NONE_MS) { noLogo.add(key); return }
        synchronized(pending) {
            val t = failedAt[key]
            if (t != null && System.currentTimeMillis() - t < RETRY_FAIL_MS) return
            if (key in pending || pending.size >= MAX_PENDING) return
            pending.add(key)
        }
        val label = name.trim()
        try {
            exec.execute {
                try { lookup(label, key) } catch (_: Throwable) { synchronized(pending) { failedAt[key] = System.currentTimeMillis() } }
                finally { synchronized(pending) { pending.remove(key) } }
            }
        } catch (_: Exception) {
            synchronized(pending) { pending.remove(key) }
        }
    }

    // ------------------------------------------------------------------ ağ

    private fun online(): Boolean {
        val c = app ?: return false
        return try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork != null
        } catch (_: Throwable) {
            true
        }
    }

    private fun preferredCountries(): Set<String> {
        val out = linkedSetOf<String>()
        // Radyo yerel yayındır: saat dilimi, cihaz dilinden daha güvenilir ülke ipucudur (Türkçe cihaz Bakü'de olabilir)
        try {
            android.icu.util.TimeZone.getRegion(java.util.TimeZone.getDefault().id)?.takeIf { it.length == 2 }?.let { out.add(it.uppercase(Locale.US)) }
        } catch (_: Throwable) {
        }
        try {
            val loc = android.content.res.Resources.getSystem().configuration.locales.get(0)
            if (!loc.country.isNullOrEmpty()) out.add(loc.country.uppercase(Locale.US))
        } catch (_: Throwable) {
        }
        try {
            val tm = app?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.networkCountryIso?.takeIf { it.isNotEmpty() }?.let { out.add(it.uppercase(Locale.US)) }
            tm?.simCountryIso?.takeIf { it.isNotEmpty() }?.let { out.add(it.uppercase(Locale.US)) }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun lookup(label: String, key: String) {
        if (!enabled) return
        if (!online()) { dFail++; dLast = "ağ yok"; Log.i(TAG, "logo: ağ yok ($label)"); synchronized(pending) { failedAt[key] = System.currentTimeMillis() }; return }
        val prefs = preferredCountries()
        Log.i(TAG, "logo ara: $label (tercih edilen ülkeler: $prefs)")

        // Sorgu çeşitleri: tam ad, genel sözcükleri atılmış ad, en uzun sözcük
        val toks = LogoMatch.tokens(label)
        val queries = LinkedHashSet<String>()
        queries.add(label)
        if (toks.isNotEmpty() && toks.joinToString(" ") != label.lowercase()) queries.add(toks.joinToString(" "))
        toks.maxByOrNull { it.length }?.takeIf { it.length >= 4 && toks.size > 1 }?.let { queries.add(it) }

        val scopes = ArrayList<String?>()
        prefs.take(2).forEach { scopes.add(it) }
        scopes.add(null)

        val all = LinkedHashMap<String, LogoCandidate>()
        var searched = false
        var ranked: List<Pair<LogoCandidate, Int>> = emptyList()
        outer@ for (q in queries) {
            for (cc in scopes) {
                val list = search(q, cc) ?: run {
                    dFail++; dLast = "arama başarısız: $q"; Log.i(TAG, "logo: arama başarısız ($q)")
                    synchronized(pending) { failedAt[key] = System.currentTimeMillis() }
                    return
                }
                searched = true
                for (c in list) all[c.name + "|" + c.country + "|" + c.favicon + "|" + c.homepage] = c
                ranked = LogoMatch.rank(label, all.values.toList(), prefs)
                Log.i(TAG, "logo: '$q' ${cc ?: "dünya"} -> ${list.size} sonuç, uygun ${ranked.size}")
                // Güçlü (tam ad + tercih edilen ülke) eşleşme bulunduysa daha fazla arama yapma
                if (ranked.isNotEmpty() && ranked[0].second >= 125) break@outer
            }
        }

        if (ranked.isEmpty()) {
            Log.i(TAG, "logo: $label -> uygun eşleşme yok")
            dNone++; dLast = "$label: eşleşme yok"; noteNone(label)
            if (searched) { File(dir() ?: return, "$key.n$ALGO").writeText("1"); noLogo.add(key) }
            return
        }
        // En iyi adaydan başlayıp görseli alınabilen ilk adayı kullan (en çok 3)
        for ((cand, score) in ranked.take(3)) {
            val bmp = iconFor(cand)
            Log.i(TAG, "logo: $label -> ${cand.name} [${cand.country}] puan=$score fav=${cand.favicon.isNotEmpty()} ana=${cand.homepage.isNotEmpty()} " + (if (bmp != null) "tamam ${bmp.width}x${bmp.height}" else "görsel alınamadı"))
            if (bmp == null) continue
            val f = File(dir() ?: return, "$key.png")
            try {
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (_: IOException) {
                f.delete(); return
            }
            File(dir() ?: return, "$key.n$ALGO").delete()
            mem.put(key, bmp)
            absent.remove(key)
            dOk++; dLast = "$label ${bmp.width}x${bmp.height}"
            version++
            main.post { for (l in listeners) l() }
            return
        }
        // Görsel sitesi o an açılmamış olabilir: "logo yok" sayma, bir süre sonra yeniden dene
        dFail++; dLast = "$label: görsel alınamadı"; noteFail(label)
        synchronized(pending) { failedAt[key] = System.currentTimeMillis() }
    }

    /** radio-browser araması; ağ hatasında null, sonuç yoksa boş liste. */
    private fun search(q: String, country: String?): List<LogoCandidate>? {
        val enc = URLEncoder.encode(q, "UTF-8")
        val cc = if (country != null) "&countrycode=" + URLEncoder.encode(country, "UTF-8") else ""
        val path = "/json/stations/search?name=$enc$cc&hidebroken=true&order=votes&reverse=true&limit=40"
        for (h in HOSTS) {
            val body = httpGet("https://$h$path", 1_500_000) ?: continue
            return try {
                val arr = JSONArray(String(body, Charsets.UTF_8))
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    LogoCandidate(o.optString("name"), o.optString("countrycode"), o.optInt("votes"), o.optString("favicon"), o.optString("homepage"))
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /** Adayın görseli: önce kayıtlı simge adresi, olmazsa ana sayfadaki simgeler. */
    private fun iconFor(c: LogoCandidate): Bitmap? {
        if (LogoMatch.usableIcon(c.favicon)) downloadIcon(c.favicon)?.let { return it }
        if (!LogoMatch.usableHome(c.homepage)) return null
        val home = c.homepage.trim()
        val tries = if (home.startsWith("http://")) listOf("https://" + home.removePrefix("http://"), home) else listOf(home)
        var page: Resp? = null
        for (t in tries) { page = httpGetResp(t, 400_000, true); if (page != null) break }
        if (page == null) return null
        if (!LogoMatch.usableHome(page.url)) return null   // ana sayfa bir sosyal medya sayfasına yönlendiriyor
        val html = String(page.bytes, Charsets.UTF_8)
        for (u in LogoMatch.iconLinks(html, page.url).take(5)) {
            downloadIcon(u)?.let { Log.i(TAG, "logo: ana sayfa simgesi $u"); return it }
        }
        return null
    }

    private fun downloadIcon(url: String, minSide: Int = 32): Bitmap? {
        val u = url.trim()
        val tries = if (u.startsWith("http://")) listOf("https://" + u.removePrefix("http://"), u) else listOf(u)
        var data: ByteArray? = null
        for (t in tries) {
            val r = httpGetResp(t, 1_500_000, true) ?: continue
            if (!LogoMatch.usableIcon(r.url)) return null   // yönlendirme platform simgesine gitti
            data = r.bytes
            break
        }
        if (data == null) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null                 // SVG vb. çözülemez
        if (minOf(o.outWidth, o.outHeight) < minSide) return null            // çok küçük simge bulanık görünür
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 512) sample *= 2
        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeByteArray(data, 0, data.size, opt) ?: return null
        val side = maxOf(raw.width, raw.height)
        if (side <= 256) return raw
        val sc = 256f / side
        return Bitmap.createScaledBitmap(raw, maxOf(1, (raw.width * sc).toInt()), maxOf(1, (raw.height * sc).toInt()), true)
    }

    private class Resp(val bytes: ByteArray, val url: String)

    private fun httpGet(start: String, maxBytes: Int): ByteArray? = httpGetResp(start, maxBytes, false)?.bytes

    /**
     * En çok 4 yönlendirme. [allowHttp] kapalıysa yalnız https (API için); açıkken düz http de kabul edilir
     * (küçük istasyon sitelerinin çoğu https sunmaz; yalnız herkese açık görsel indirilir). Son adresi de verir.
     */
    private fun httpGetResp(start: String, maxBytes: Int, allowHttp: Boolean): Resp? {
        var url = start
        for (hop in 0..4) {
            if (!url.startsWith("https://") && !(allowHttp && url.startsWith("http://"))) return null
            val c = try { URL(url).openConnection() as HttpURLConnection } catch (_: Exception) { return null }
            try {
                c.connectTimeout = 6000
                c.readTimeout = 8000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept", "*/*")
                val code = c.responseCode
                if (code in 301..308 && code != 304) {
                    val loc = c.getHeaderField("Location") ?: return null
                    url = URL(URL(url), loc).toString()
                    continue
                }
                if (code != 200) { dLast = "HTTP $code ${url.substringAfter("://").substringBefore('/')}"; Log.i(TAG, "logo: HTTP $code ${url.substringBefore('?')}"); return null }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                c.inputStream.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) return null
                    }
                }
                return Resp(out.toByteArray(), url)
            } catch (e: Exception) {
                dLast = "${e.javaClass.simpleName} ${url.substringAfter("://").substringBefore('/')}"
                Log.i(TAG, "logo: ağ hatası ${e.javaClass.simpleName} ${url.substringBefore('?')}")
                return null
            } finally {
                c.disconnect()
            }
        }
        return null
    }
}
