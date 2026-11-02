package app.otoradyo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Araç ekranı açılınca (isteğe bağlı) radyo servisini başlatır; dongle takılıysa son istasyon çalar. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON") return
        if (!Prefs(context).bootStart) return
        try {
            val i = Intent(context, RadioService::class.java)
            context.startForegroundService(i)
        } catch (e: Exception) {
            // Yeni Android sürümleri arka plandan ön plan servisi başlatmayı kısıtlayabilir
            Log.w("OtoRadyo", "açılışta servis başlatılamadı: $e")
        }
    }
}
