package app.otoradyo

/** Arayüz seçimi: hangi tema var, kalıcı seçim ne. Radyo motorundan bağımsızdır. */
object ThemeManager {
    const val MODERN = 0
    const val CLASSIC = 1
    const val OEM = 2
    const val MINIMAL = 3
    const val WALL = 4

    /** @param accent tema seçilince önerilen vurgu (Pal.SCHEMES sırası); kullanıcı sonradan değiştirebilir */
    class Info(val id: Int, val name: String, val descId: Int, val accent: Int)

    val ALL = listOf(
        Info(OEM, "OEM Premium", R.string.td_oem, 0),
        Info(MODERN, "Modern Digital", R.string.td_modern, 4),
        Info(CLASSIC, "Classic Analog", R.string.td_classic, 1),
        Info(MINIMAL, "Minimal Driving", R.string.td_minimal, 1),
        Info(WALL, "Station Wall", R.string.td_wall, 3),
    )

    fun info(id: Int): Info = ALL.firstOrNull { it.id == id } ?: ALL[2]

    /** Kayıtlı tema. */
    fun current(prefs: Prefs): Int = prefs.theme
}
