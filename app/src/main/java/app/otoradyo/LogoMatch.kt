package app.otoradyo

import java.text.Normalizer
import kotlin.math.ln

/** Logo aramasında bir aday istasyon (radio-browser kaydı). */
data class LogoCandidate(val name: String, val country: String, val votes: Int, val favicon: String, val homepage: String = "")

/**
 * İstasyon adı eşleştirme (Android'e bağımlı değil; ana makinede test edilir).
 * Yanlış logo göstermektense logosuz bırakmak tercih edilir: yalnız adı güçlü biçimde eşleşen adaylar kabul edilir.
 */
object LogoMatch {
    /** Karşılaştırmada anlamsız sayılan genel sözcükler */
    private val generic = setOf("radyo", "radio", "radiо", "fm", "dab", "plus", "hd", "the", "online", "live", "canli", "tv", "am",
        "azerbaycan", "azerbaijan", "turkiye", "turkey", "turk", "music", "muzik", "news", "haber")

    private val translit = mapOf(
        'ı' to "i", 'ə' to "e", 'ß' to "ss", 'ł' to "l", 'ø' to "o", 'æ' to "ae", 'đ' to "d",
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "j", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "c", 'ш' to "s", 'щ' to "s",
        'ъ' to "", 'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "u", 'я' to "a"
    )

    /** Küçük harf, aksan/Kiril/Azerice harfleri düz Latin'e indirgenmiş metin (yalnız karşılaştırma için). */
    fun fold(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) sb.append(translit[ch] ?: ch.toString())
        val n = Normalizer.normalize(sb.toString(), Normalizer.Form.NFD)
        val out = StringBuilder()
        for (ch in n) if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) out.append(ch)
        // "ch/sh/zh/kh" gibi çift harfli yazımlar tek harfe indirilir (ChM ≈ ÇM, Shams ≈ Şams)
        return out.toString().replace("ch", "c").replace("sh", "s").replace("zh", "j").replace("kh", "h")
    }

    fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private val freqLike = Regex("\\b(8[7-9]|9\\d|10[0-8])([.,]\\d)?\\b")

    /** Anlamlı sözcükler: katlanmış, genel sözcükler ve frekans benzeri sayılar atılmış. */
    fun tokens(s: String): List<String> =
        fold(s).replace(freqLike, " ").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in generic }

    /** Genel sözcükleri atılmış çekirdek ad (boşluksuz, katlanmış). */
    fun core(s: String): String = tokens(s).joinToString("")

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Ad uyumu (yüksek = iyi); uygun değilse null. */
    fun nameScore(query: String, candidate: String): Int? {
        val qn = normalize(fold(query))
        val cn = normalize(fold(candidate))
        if (qn.isEmpty() || cn.isEmpty()) return null
        if (qn == cn) return 100
        val qt = tokens(query)
        val ct = tokens(candidate)
        if (qt.isEmpty() || ct.isEmpty()) return null
        val qc = qt.joinToString("")
        val cc = ct.joinToString("")
        if (qc == cc) return 90
        if (qt.sorted() == ct.sorted()) return 85
        if (qc.length >= 4 && ct.containsAll(qt) && ct.size - qt.size <= 2) return 70
        if (cc.length >= 5 && qt.containsAll(ct) && qt.size - ct.size <= 2) return 60
        if (qc.length >= 6 && cc.length >= 6 && lev(qc, cc) <= 1) return 65
        if (qc.length >= 10 && cc.length >= 10 && lev(qc, cc) <= 2) return 55
        if (qc.length >= 6 && cc.length >= 6 && (cc.contains(qc) || qc.contains(cc))) return 50
        return null
    }

    fun validIconUrl(u: String): Boolean {
        val t = u.trim()
        return t.startsWith("https://") || t.startsWith("http://")
    }

    /** Bu sitelerin simgesi istasyonun değil platformun logosudur (kayıtlarda ana sayfa çoğu kez bir sosyal medya sayfasıdır). */
    private val platformHosts = listOf(
        "facebook.com", "fb.com", "instagram.com", "twitter.com", "x.com", "t.co", "youtube.com", "youtu.be", "tiktok.com",
        "linkedin.com", "vk.com", "ok.ru", "t.me", "telegram.org", "telegram.me", "whatsapp.com", "wordpress.com", "wordpress.org",
        "w.org", "blogspot.com", "blogger.com", "wix.com", "weebly.com", "google.com", "gstatic.com", "apple.com", "spotify.com",
    )

    /** Platform varsayılan simgeleri: kendi alan adındaki WordPress sitesi de bunları sunabilir. */
    private val platformIconPaths = listOf("/wp-includes/", "w-logo", "/wp-admin/")

    private fun hostOf(u: String): String? = try { java.net.URL(u.trim()).host?.lowercase()?.removePrefix("www.") } catch (_: Exception) { null }

    /** Adres bir platforma (istasyona değil) ait mi. */
    fun isPlatform(u: String): Boolean {
        val h = hostOf(u) ?: return false
        return platformHosts.any { h == it || h.endsWith(".$it") }
    }

    /** Logo olarak kullanılabilir simge adresi. */
    fun usableIcon(u: String): Boolean =
        validIconUrl(u) && !isPlatform(u) && platformIconPaths.none { u.contains(it, ignoreCase = true) }

    /** Simge aranabilecek ana sayfa. */
    fun usableHome(u: String): Boolean = validIconUrl(u) && !isPlatform(u)

    /**
     * Uygun adayları puanına göre sıralar (en iyi ilk). [preferred]: tercih edilen ülke kodları (büyük harf).
     * Çok kısa adlar (ör. "Pi FM") yalnız tercih edilen ülkedeki adayla eşleşir; böylece başka ülkedeki aynı adlı istasyon seçilmez.
     */
    fun rank(query: String, list: List<LogoCandidate>, preferred: Set<String>): List<Pair<LogoCandidate, Int>> {
        val short = core(query).length < 4
        val out = ArrayList<Pair<LogoCandidate, Int>>()
        for (c in list) {
            val hasFav = usableIcon(c.favicon)
            if (!hasFav && !usableHome(c.homepage)) continue   // ne simge adresi ne ana sayfa: logo çıkarılamaz
            val n = nameScore(query, c.name) ?: continue
            val inPref = c.country.uppercase() in preferred
            if (short && (!inPref || n < 100)) continue   // "AZ Radio" gibi adlar başka istasyonla kolayca çakışır: yalnız birebir ad
            // Aynı adlı başka ülke istasyonu yanlış logo verir: bulunulan ülke biliniyorsa yalnız oradaki kayıtlar geçerlidir
            if (!inPref && (preferred.isNotEmpty() || n < 100)) continue
            var s = n
            if (inPref) s += 25
            if (hasFav) s += 10
            s += (ln((c.votes.coerceAtLeast(0) + 1).toDouble()) * 2.0).toInt().coerceAtMost(20)
            out.add(c to s)
        }
        out.sortByDescending { it.second }
        return out
    }

    private fun attr(tag: String, name: String): String? {
        val m = Regex("\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }.trim().ifEmpty { null }
    }

    /**
     * Ana sayfa HTML'inden simge adreslerini (büyükten küçüğe) çıkarır: apple-touch-icon, icon, shortcut icon.
     * SVG ve data: adresleri atılır; sonuna sitenin kök dizinindeki olağan yollar eklenir.
     */
    fun iconLinks(html: String, baseUrl: String): List<String> {
        val base = try { java.net.URL(baseUrl) } catch (_: Exception) { return emptyList() }
        val found = ArrayList<Pair<String, Int>>()
        for (m in Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html.take(300_000))) {
            val t = m.value
            val rel = attr(t, "rel")?.lowercase() ?: continue
            if (!rel.contains("icon")) continue
            val href = attr(t, "href") ?: continue
            if (href.startsWith("data:") || href.substringBefore('?').endsWith(".svg", ignoreCase = true)) continue
            val abs = try { java.net.URL(base, href).toString() } catch (_: Exception) { continue }
            if (!usableIcon(abs)) continue
            val size = attr(t, "sizes")?.let { Regex("(\\d+)x(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: if (rel.contains("apple-touch")) 180 else 32
            found.add(abs to (size + if (rel.contains("apple-touch")) 40 else 0))
        }
        val origin = base.protocol + "://" + base.host
        val ordered = found.sortedByDescending { it.second }.map { it.first }.toMutableList()
        ordered.add("$origin/apple-touch-icon.png")
        ordered.add("$origin/favicon.ico")
        return ordered.distinct()
    }

    fun pickBest(query: String, list: List<LogoCandidate>, preferred: Set<String>): LogoCandidate? =
        rank(query, list, preferred).firstOrNull()?.first
}
