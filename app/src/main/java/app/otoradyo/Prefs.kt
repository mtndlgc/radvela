package app.otoradyo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** FM istasyonu */
data class FmStation(val freqHz: Int, var name: String = "", var strength: Float = 0f) {
    val key: String get() = "fm:$freqHz"
    fun title(): String = if (name.isNotEmpty()) name else formatMhz(freqHz)
}

/** DAB/DAB+ servisi */
data class DabService(val channel: String, val sid: Long, val scids: Int, val label: String, val aac: Boolean = true) {
    val key: String get() = "dab:$channel:$sid:$scids"
}

fun formatMhz(hz: Int): String = String.format(java.util.Locale.US, "%.1f MHz", hz / 1e6)

/** Basit kalıcı depo: son tarama sonuçları, favoriler ve ayarlar. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("otoradyo", Context.MODE_PRIVATE)

    /** İstasyon logolarını internetten getir (radio-browser.info). */
    var onlineLogos: Boolean
        get() = sp.getBoolean("onlineLogos", false)
        set(v) = sp.edit().putBoolean("onlineLogos", v).apply()

    /** Gündüz/gece görünümü: 0 = sistemi izle, 1 = gündüz, 2 = gece. */
    var appearance: Int
        get() = sp.getInt("appearance", 0)
        set(v) = sp.edit().putInt("appearance", v).apply()

    /** Arayüz dili: "" = sistem dili, aksi halde L10n.languages kodlarından biri. */
    var language: String
        get() = sp.getString("language", "") ?: ""
        set(v) = sp.edit().putString("language", v).apply()

    var deemphasis75: Boolean
        get() = sp.getBoolean("deemph75", false)
        set(v) = sp.edit().putBoolean("deemph75", v).apply()

    var forceMono: Boolean
        get() = sp.getBoolean("mono", false)
        set(v) = sp.edit().putBoolean("mono", v).apply()

    var agc: Boolean
        get() = sp.getBoolean("agc", true)
        set(v) = sp.edit().putBoolean("agc", v).apply()

    var gainIndex: Int
        get() = sp.getInt("gainIndex", 14)
        set(v) = sp.edit().putInt("gainIndex", v).apply()

    /** Dongle bağlanınca son istasyonu otomatik çal */
    var autoPlay: Boolean
        get() = sp.getBoolean("autoPlay", true)
        set(v) = sp.edit().putBoolean("autoPlay", v).apply()

    /** Araç/cihaz açılınca servisi otomatik başlat (varsayılan kapalı) */
    var bootStart: Boolean
        get() = sp.getBoolean("bootStart", false)
        set(v) = sp.edit().putBoolean("bootStart", v).apply()

    /** İlk açılış yardımı gösterildi mi */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** Ekolayzer ön ayarı: 0 düz, 1 bas, 2 konuşma, 3 tiz, 4 gürlük (Pro) */
    var eqPreset: Int
        get() = sp.getInt("eqPreset", 0)
        set(v) = sp.edit().putInt("eqPreset", v).apply()

    /** Basit animasyonlar: spektrum, ibre hareketi */
    var animations: Boolean
        get() = sp.getBoolean("animations", true)
        set(v) = sp.edit().putBoolean("animations", v).apply()

    /** İstasyon duvarı görünümü: 0 kart, 1 sıkı kart, 2 satır */
    var listStyle: Int
        get() = sp.getInt("listStyle", 0)
        set(v) = sp.edit().putInt("listStyle", v).apply()

    /** Classic Analog zemini: true ahşap, false koyu metal */
    var classicWood: Boolean
        get() = sp.getBoolean("classicWood", false)
        set(v) = sp.edit().putBoolean("classicWood", v).apply()

    /** Arayüz teması: ThemeManager sabitleri */
    var theme: Int
        get() = sp.getInt("theme", 1)
        set(v) = sp.edit().putInt("theme", v).apply()

    /** 0 kırmızı, 1 amber (varsayılan), 2 yeşil, 3 mavi, 4 camgöbeği */
    var colorScheme: Int
        get() = sp.getInt("colorScheme", 1)
        set(v) = sp.edit().putInt("colorScheme", v).apply()

    /** RTL-SDR kristal düzeltmesi (ppm) */
    var ppm: Int
        get() = sp.getInt("ppm", 0)
        set(v) = sp.edit().putInt("ppm", v).apply()

    /** Yazılımsal ses seviyesi (% 0-200) */
    var audioGainPct: Int
        get() = sp.getInt("audioGainPct", 100)
        set(v) = sp.edit().putInt("audioGainPct", v).apply()

    var favoritesOnly: Boolean
        get() = sp.getBoolean("favOnly", false)
        set(v) = sp.edit().putBoolean("favOnly", v).apply()

    var lastMode: String
        get() = sp.getString("lastMode", "fm") ?: "fm"
        set(v) = sp.edit().putString("lastMode", v).apply()

    var lastFmHz: Int
        get() = sp.getInt("lastFmHz", 100000000)
        set(v) = sp.edit().putInt("lastFmHz", v).apply()

    var lastDabKey: String
        get() = sp.getString("lastDabKey", "") ?: ""
        set(v) = sp.edit().putString("lastDabKey", v).apply()

    var favorites: Set<String>
        get() = sp.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("favorites", v).apply()

    fun saveFm(list: List<FmStation>) {
        val arr = JSONArray()
        for (s in list) arr.put(JSONObject().put("f", s.freqHz).put("n", s.name).put("s", s.strength.toDouble()))
        sp.edit().putString("fmList", arr.toString()).apply()
    }

    fun loadFm(): MutableList<FmStation> {
        val out = mutableListOf<FmStation>()
        try {
            val arr = JSONArray(sp.getString("fmList", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(FmStation(o.getInt("f"), o.optString("n", ""), o.optDouble("s", 0.0).toFloat()))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveDab(list: List<DabService>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("c", s.channel).put("i", s.sid).put("k", s.scids).put("l", s.label).put("a", s.aac))
        }
        sp.edit().putString("dabList", arr.toString()).apply()
    }

    fun loadDab(): MutableList<DabService> {
        val out = mutableListOf<DabService>()
        try {
            val arr = JSONArray(sp.getString("dabList", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(DabService(o.getString("c"), o.getLong("i"), o.optInt("k", 0), o.getString("l"), o.optBoolean("a", true)))
            }
        } catch (_: Exception) {
        }
        return out
    }
}
