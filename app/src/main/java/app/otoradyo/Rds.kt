package app.otoradyo

import android.content.Context

object Rds {
    /** RDS program türü (PTY) adları, EN 50067 (Avrupa) tablosu; metinler string-array "rds_pty" içindedir. */
    fun ptyName(ctx: Context, pty: Int): String {
        val names = ctx.resources.getStringArray(R.array.rds_pty)
        return if (pty in 1 until names.size) names[pty] else ""
    }
}
