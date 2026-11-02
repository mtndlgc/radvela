package app.otoradyo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * RTL-SDR dongle bulma, izin isteme ve açma. "RTL-SDR driver" gibi harici bir uygulama gerekmez:
 * açılan bağlantının dosya tanıtıcısı doğrudan yerel kitaplığa (librtlsdr) verilir.
 */
class UsbHelper(private val ctx: Context) {

    companion object {
        private const val ACTION_PERMISSION = "app.otoradyo.USB_PERMISSION"

        /** RTL2832U tabanlı cihazlar (res/xml/device_filter.xml ile aynı liste). */
        private val ids: Set<Long> = setOf(
            0x0bda2832, 0x0bda2838, 0x185b0620, 0x185b0650, 0x185b0680, 0x0ccd00a9, 0x0ccd00b3, 0x0ccd00b4,
            0x0ccd00b5, 0x0ccd00b7, 0x0ccd00b8, 0x0ccd00b9, 0x0ccd00c0, 0x0ccd00c6, 0x1d191101, 0x1d191102,
            0x1d191103, 0x1d191104, 0x1f4da803, 0x1f4db803, 0x1f4dc803, 0x1f4dd286, 0x1f4dd803, 0x1b80d393,
            0x1b80d394, 0x1b80d395, 0x1b80d397, 0x1b80d398, 0x1b80d39d, 0x1b80d3a4, 0x1b80d3a8, 0x1b80d3af,
            0x1b80d3b0, 0x0458707f, 0x1b80179d
        )

        fun isRtl(d: UsbDevice): Boolean = ((d.vendorId.toLong() shl 16) or d.productId.toLong()) in ids
    }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private var receiver: BroadcastReceiver? = null

    fun findDevice(): UsbDevice? = usb.deviceList.values.firstOrNull { isRtl(it) }

    fun hasPermission(d: UsbDevice): Boolean = usb.hasPermission(d)

    /** Sistem izin penceresini gösterir; sonuç ana iş parçacığında bildirilir. */
    fun requestPermission(d: UsbDevice, onResult: (Boolean) -> Unit) {
        unregister()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_PERMISSION) return
                unregister()
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        receiver = r
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(r, filter)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
        usb.requestPermission(d, pi)
    }

    fun open(d: UsbDevice): UsbDeviceConnection? = usb.openDevice(d)

    fun unregister() {
        receiver?.let {
            try {
                ctx.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        receiver = null
    }
}
