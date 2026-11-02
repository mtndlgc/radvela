package app.otoradyo

/**
 * C++ çekirdeğine (libotoradio.so) JNI köprüsü.
 * Olaylar çekirdeğin iş parçacıklarından gelir; arayüze aktarırken ana iş parçacığına geçin.
 */
class NativeRadio(private val listener: Listener) {

    interface Listener {
        /** PCM: 16-bit, her zaman stereo ve aralıklı (L R L R ...). Çekirdek iş parçacığından çağrılır. */
        fun onAudio(pcm: ShortArray, sampleRate: Int)

        fun onEvent(type: Int, text: String, a: Double, b: Double)

        /** DAB MOT slaytı: JPEG/PNG baytları. Çekirdek iş parçacığından çağrılır. */
        fun onSlide(image: ByteArray, subtype: Int)
    }

    companion object {
        init {
            // libusb, librtlsdr ve DAB çekirdeği libotoradio.so içine statik olarak bağlıdır.
            System.loadLibrary("otoradio")
        }

        // Olay türleri: core/jni_bridge.cpp ile aynı olmalı
        const val EV_CONNECTION = 1          // a = 1 bağlı / 0 kopuk, text = cihaz açıklaması
        const val EV_FM_STATUS = 2           // a = göreli RSSI (dB), b = bit0 stereo | bit1 RDS senkron
        const val EV_FM_PI = 3               // a = PI
        const val EV_FM_PS = 4               // text = PS
        const val EV_FM_RT = 5               // text = RadioText
        const val EV_FM_PTY = 6              // a = PTY (-1: yok)
        const val EV_FM_FOUND = 7            // a = frekans (Hz), b = güç (dB)
        const val EV_FM_NAME = 8             // a = frekans (Hz), text = PS
        const val EV_DAB_ENSEMBLE = 9        // text = "kanal\u0001etiket", a = EId
        const val EV_DAB_SERVICE = 10        // text = "kanal\u0001etiket", a = SId, b = SCIdS | 0x100 (DAB+ AAC ise)
        const val EV_DAB_SERVICES_COMPLETE = 11 // text = kanal
        const val EV_DAB_SELECTED = 12       // text = servis etiketi, a = SId, b = SCIdS
        const val EV_DAB_STATUS = 13         // a = SNR (dB), b = 0 yok / 1 null / 2 tam senkron
        const val EV_DAB_LABEL = 14          // text = dinamik etiket (DLS)
        const val EV_DAB_AUDIO = 15          // text = kodek, a = örnekleme hızı, b = 1 stereo
        const val EV_DAB_QUALITY = 20        // text = "fibHata,sesCrcTamam,sesCrcHata,rsHata"
        const val EV_SCAN_PROGRESS = 16      // a = yüzde, text = ne taranıyor
        const val EV_SCAN_DONE = 17          // a = 1 ise iptal
        const val EV_MESSAGE = 18            // a = 1 ise hata, text = mesaj
        const val EV_GAIN = 19               // a = dB, b = 1 otomatik
        const val EV_SCAN_FREQ = 21          // a = FM taramasında bakılan frekans (Hz)

        const val SEP = '\u0001'
    }

    private var handle: Long = nativeCreate()

    // --- Çekirdekten çağrılanlar (JNI GetMethodID ile bulunur; adlarını/imzalarını değiştirmeyin) ---
    @Suppress("unused")
    fun onNativeAudio(pcm: ShortArray, rate: Int) = listener.onAudio(pcm, rate)

    @Suppress("unused")
    fun onNativeEvent(type: Int, text: String, a: Double, b: Double) = listener.onEvent(type, text, a, b)

    @Suppress("unused")
    fun onNativeSlide(image: ByteArray, subtype: Int) = listener.onSlide(image, subtype)

    // --- Komutlar ---
    fun destroy() {
        val h = handle
        handle = 0
        if (h != 0L) nativeDestroy(h)
    }

    /** USB dosya tanıtıcısıyla RTL-SDR'yi açar. Başarılıysa null, değilse hata iletisi döner. */
    fun openUsb(fd: Int): String? = if (handle != 0L) nativeOpenUsb(handle, fd) else "@nat_core_not_ready"

    /** Geliştirme/test için rtl_tcp. Başarılıysa null, değilse hata iletisi döner. */
    fun openTcp(host: String, port: Int, timeoutMs: Int): String? =
        if (handle != 0L) nativeOpenTcp(handle, host, port, timeoutMs) else "@nat_core_not_ready"

    fun closeSource() { if (handle != 0L) nativeCloseSource(handle) }
    fun startFm(freqHz: Int): Boolean = handle != 0L && nativeStartFm(handle, freqHz)
    fun startDab(channel: String, sid: Long, scids: Int): Boolean = handle != 0L && nativeStartDab(handle, channel, sid, scids)
    fun playDab(sid: Long, scids: Int) { if (handle != 0L) nativePlayDab(handle, sid, scids) }
    fun stop() { if (handle != 0L) nativeStop(handle) }
    fun scanFm(resolveNames: Boolean) { if (handle != 0L) nativeScanFm(handle, resolveNames) }
    fun scanDab() { if (handle != 0L) nativeScanDab(handle) }
    fun cancelScan() { if (handle != 0L) nativeCancelScan(handle) }
    fun setAgc(on: Boolean) { if (handle != 0L) nativeSetAgc(handle, on) }
    fun setGainIndex(index: Int) { if (handle != 0L) nativeSetGainIndex(handle, index) }
    /** IQ tamponunda taşıp atılan örnek sayısı (0 değilse işlem hattı gerçek zamanlıya yetişemiyor demektir). */
    fun droppedSamples(): Long = if (handle != 0L) nativeDropped(handle) else 0L
    fun setPpm(ppm: Int) { if (handle != 0L) nativeSetPpm(handle, ppm) }
    fun gainCount(): Int = if (handle != 0L) nativeGainCount(handle) else 0
    fun gainDb(): Float = if (handle != 0L) nativeGainDb(handle) else 0f
    fun signalLevel(): Float = if (handle != 0L) nativeLevel(handle) else 0f
    fun setForceMono(on: Boolean) { if (handle != 0L) nativeSetForceMono(handle, on) }
    fun setDeemphasisMicros(us: Float) { if (handle != 0L) nativeSetDeemphasis(handle, us) }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeOpenUsb(handle: Long, fd: Int): String?
    private external fun nativeOpenTcp(handle: Long, host: String, port: Int, timeoutMs: Int): String?
    private external fun nativeCloseSource(handle: Long)
    private external fun nativeStartFm(handle: Long, freqHz: Int): Boolean
    private external fun nativeStartDab(handle: Long, channel: String, sid: Long, scids: Int): Boolean
    private external fun nativePlayDab(handle: Long, sid: Long, scids: Int)
    private external fun nativeStop(handle: Long)
    private external fun nativeScanFm(handle: Long, resolveNames: Boolean)
    private external fun nativeScanDab(handle: Long)
    private external fun nativeCancelScan(handle: Long)
    private external fun nativeSetAgc(handle: Long, on: Boolean)
    private external fun nativeSetGainIndex(handle: Long, index: Int)
    private external fun nativeDropped(handle: Long): Long
    private external fun nativeSetPpm(handle: Long, ppm: Int)
    private external fun nativeGainCount(handle: Long): Int
    private external fun nativeGainDb(handle: Long): Float
    private external fun nativeLevel(handle: Long): Float
    private external fun nativeSetForceMono(handle: Long, on: Boolean)
    private external fun nativeSetDeemphasis(handle: Long, us: Float)
}
