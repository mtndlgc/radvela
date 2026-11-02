package app.otoradyo

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * Uygulama içi dil seçimi. Seçim Prefs.language içinde saklanır ("" = sistem dili; desteklenmiyorsa İngilizce).
 * Etkinlik ve servis, attachBaseContext içinde bağlamlarını wrap() ile sarar; böylece getString() seçilen dili kullanır.
 */
object L10n {
    /** (kod, kendi dilindeki ad) */
    val languages = listOf(
        "tr" to "Türkçe",
        "en" to "English",
        "ru" to "Русский",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "ar" to "العربية"
    )
    private val codes = languages.map { it.first }.toSet()

    /** Kullanılacak dil kodu ("tr", "en", ...). */
    fun effective(pref: String): String {
        if (pref in codes) return pref
        val sys = Resources.getSystem().configuration.locales.get(0)?.language ?: "en"
        return if (sys in codes) sys else "en"
    }

    fun nameOf(code: String): String = languages.firstOrNull { it.first == code }?.second ?: code

    fun wrap(base: Context): Context {
        val loc = Locale(effective(Prefs(base).language))
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        return base.createConfigurationContext(cfg)
    }
}
