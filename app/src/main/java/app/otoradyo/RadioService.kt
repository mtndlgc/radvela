package app.otoradyo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors

/** Alıcının anlık durumu (arayüz yeniden bağlanınca buradan okunur). Yalnız ana iş parçacığında değişir. */
class RadioState {
    var usb = ""                      // yerelleştirilmiş durum metni (RadioService.setUsb doldurur)
    var usbId = 0                     // usb metninin string kaynağı (dil değişince yeniden üretilir)
    var usbArg = ""
    var connected = false
    var mode = "idle"                 // idle | fm | dab
    var playing = false
    var fmHz = 0
    var ps = ""
    var rt = ""
    var pty = -1
    var rssi = -120f
    var stereo = false
    var rdsSync = false
    var dabChannel = ""
    var dabSid = 0L
    var dabScids = 0
    var dabLabel = ""
    var dabEnsemble = ""
    var dls = ""
    var dabCodec = ""
    var dabSnr = 0f
    var dabSync = 0                   // 0 yok, 1 null, 2 tam
    var dabFibErr = 0                 // son aralıkta FIC (topluluk bilgisi) hata sayısı
    var dabMscOk = 0                  // son aralıkta çözülen ses çerçevesi (CRC tamam)
    var dabMscErr = 0                 // son aralıkta bozuk ses çerçevesi
    var dabRsErr = 0
    var slide: android.graphics.Bitmap? = null  // DAB MOT slayt gösterisi (son görüntü)
    var scanning = false
    var scanKind = ""                 // fm | dab
    var scanProgress = 0
    var scanText = ""
    var scanHz = 0                    // FM taramasında bakılan frekans
    var message = ""
    var recording = false
    var recordStartMs = 0L
    var sleepAtMs = 0L                // uyku zamanlayıcısı bitiş anı (0 = kapalı)
    var muted = false                 // kullanıcı sessize aldı (alım sürer)
}

class RadioService : Service(), NativeRadio.Listener {

    interface Ui {
        fun onEvent()
    }

    inner class LocalBinder : Binder() {
        fun service(): RadioService = this@RadioService
    }

    companion object {
        const val ACTION_STOP = "app.otoradyo.STOP"
        const val ACTION_NEXT = "app.otoradyo.NEXT"
        const val ACTION_PREV = "app.otoradyo.PREV"
        const val ACTION_CONNECT = "app.otoradyo.CONNECT"
        private const val CHANNEL_ID = "radio"
        private const val NOTIF_ID = 1
        private const val USB_TIMEOUT_MS = 8000L
    }

    val state = RadioState()
    var ui: Ui? = null
    lateinit var prefs: Prefs
        private set

    // Liste ve favoriler (yalnız ana iş parçacığında değiştirilir)
    var fmList = mutableListOf<FmStation>()
        private set
    var dabList = mutableListOf<DabService>()
        private set
    var favorites = mutableSetOf<String>()
        private set

    /** Seçili dile göre sarılmış bağlam; getString() bunun üzerinden yapılır (dil değişince reloadLanguage yeniler). */
    private var lctx: Context? = null
    private fun lc(): Context = lctx ?: this
    fun str(id: Int, vararg a: Any): String =
        if (a.isEmpty()) lc().getString(id) else String.format(java.util.Locale.US, lc().getString(id), *a)

    /** Yerel çekirdekten gelen "@kod" iletilerini yerelleştirir; diğer metinler olduğu gibi döner. */
    fun loc(t: String): String {
        if (!t.startsWith("@")) return t
        val id = when (t) {
            "@nat_core_not_ready" -> R.string.nat_core_not_ready
            "@nat_fm_tune" -> R.string.nat_fm_tune
            "@nat_tcp_connect" -> R.string.nat_tcp_connect
            "@nat_tcp_header" -> R.string.nat_tcp_header
            "@nat_tcp_lost" -> R.string.nat_tcp_lost
            "@nat_tcp_fail" -> R.string.nat_tcp_fail
            "@nat_usb_open" -> R.string.nat_usb_open
            "@nat_sample_rate" -> R.string.nat_sample_rate
            "@nat_usb_read" -> R.string.nat_usb_read
            "@nat_dab_core" -> R.string.nat_dab_core
            "@nat_no_aac" -> R.string.nat_no_aac
            "@nat_no_dongle" -> R.string.nat_no_dongle
            "@nat_open_fail" -> R.string.nat_open_fail
            else -> return t.substring(1)
        }
        return str(id)
    }

    private fun setUsb(id: Int, arg: String = "") {
        state.usbId = id
        state.usbArg = arg
        state.usb = if (arg.isEmpty()) str(id) else str(id, arg)
    }

    /** Arayüz dili değişti: servis metinlerini (durum, bildirim) yeni dilde yeniden üretir. */
    fun reloadLanguage() {
        lctx = L10n.wrap(applicationContext)
        if (state.usbId != 0) setUsb(state.usbId, state.usbArg)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, str(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        lastNotif = ""
        updateNotification()
        changed()
    }

    /** Yeni logo geldi: medya kartındaki kapak görselini yenile. */
    private val logoListener: () -> Unit = { if (!destroyed) { updateSession() } }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private var exec = Executors.newSingleThreadExecutor()
    private lateinit var radio: NativeRadio
    private var usbToken = 0                    // süren USB işinin kimliği; bekçi ve eski işler bununla ayrılır
    private var usbRetries = 0
    private var opening: UsbDeviceConnection? = null
    private lateinit var usbHelper: UsbHelper
    private val audio = AudioOut()
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var session: MediaSession? = null
    private var conn: UsbDeviceConnection? = null
    private var connDevice: UsbDevice? = null
    private var permissionAsked = false
    private var detachReceiver: BroadcastReceiver? = null
    private var lastNotif = ""
    private var destroyed = false

    // Tarama sonuçları (tarama sırasında toplanır)
    private val scanFm = mutableListOf<FmStation>()
    private val scanDab = mutableListOf<DabService>()
    private var scanCancelRequested = false

    // Kayıt ve ses seviyesi (çekirdek iş parçacığından okunur)
    private val recorder by lazy { Recorder(this) }
    lateinit var music: MusicPlayer
        private set
    var musicTracks: List<Track> = emptyList()
        private set
    var musicVersion = 0
        private set
    var musicLoading = false
        private set
    private var musicFolderNames = emptyMap<String, String>()   // görünen ad -> belge ağacı adresi
    private var mountReceiver: BroadcastReceiver? = null
    private var musicScanToken = 0
    @Volatile private var audioGain = 1.0f
    @Volatile private var duck = false
    @Volatile private var recordRequested = false
    @Volatile private var recordLabel = "radyo"
    private var msgToken = 0
    private var manualChannel = ""

    /** Çekirdek işlerini tek iş parçacığında çalıştırır; hizmet kapandıktan sonra gelen çağrılar sessizce yok sayılır. */
    private fun bg(block: () -> Unit) {
        if (destroyed) return
        try {
            exec.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Logos.init(applicationContext)
        Logos.addListener(logoListener)
        lctx = L10n.wrap(applicationContext)
        setUsb(R.string.usb_searching)
        fmList = prefs.loadFm()
        dabList = prefs.loadDab()
        favorites = prefs.favorites.toMutableSet()
        state.fmHz = prefs.lastFmHz
        state.mode = "idle"
        audioGain = prefs.audioGainPct / 100f
        audio.setEqPreset(prefs.eqPreset)
        music = MusicPlayer(this, { onMusicChange() }, { main.post { showMessage(str(R.string.music_failed)) } })
        music.setEqPreset(prefs.eqPreset)
        applyMusicVolume()

        radio = NativeRadio(this)
        usbHelper = UsbHelper(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, str(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))

        setupSession()
        registerDetach()
        registerMounts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownAndStop()
                return START_NOT_STICKY
            }
            ACTION_NEXT -> seek(+1)
            ACTION_PREV -> seek(-1)
        }
        startForegroundNow()
        if (!state.connected) connectUsb()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Logos.removeListener(logoListener)
        destroyed = true
        optToken++
        main.removeCallbacksAndMessages(null)
        detachReceiver?.let {
            try { unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
        }
        mountReceiver?.let {
            try { unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
        }
        music.release()
        usbHelper.unregister()
        recorder.stop()
        releaseWakeLock()
        abandonFocus()
        session?.release()
        exec.shutdownNow()
        radio.stop()
        radio.closeSource()
        radio.destroy()
        conn?.close()
        audio.release()
        super.onDestroy()
    }

    private fun changed() {
        main.post { if (!destroyed) ui?.onEvent() }
    }

    // ------------------------------------------------------------------ USB

    private fun registerDetach() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                @Suppress("DEPRECATION")
                val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (d != null && connDevice != null && d.deviceName == connDevice?.deviceName) onUsbLost()
            }
        }
        detachReceiver = r
        val f = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
    }

    /**
     * USB işini bekçiyle çalıştırır. librtlsdr yanıt vermezse iş parçacığı sonsuza dek takılı kalır ve sıradaki
     * tüm işler durur (dongle görünmez olur): süre dolunca takılan iş bırakılır, yeni çekirdek açılıp yeniden denenir.
     */
    private fun usbJob(block: (Int) -> Unit) {
        val token = ++usbToken
        main.postDelayed({ if (token == usbToken && !destroyed) recoverUsb() }, USB_TIMEOUT_MS)
        bg {
            block(token)
            main.post { if (token == usbToken) usbToken++ }
        }
    }

    private fun recoverUsb() {
        Log.w("OtoRadyo", "USB isi yanit vermedi, cekirdek yenileniyor (deneme ${usbRetries + 1})")
        usbToken++
        try { opening?.close() } catch (_: RuntimeException) {}
        try { conn?.close() } catch (_: RuntimeException) {}
        opening = null
        conn = null
        connDevice = null
        exec.shutdownNow()   // takılı iş parçacığı kendi kaderine bırakılır
        exec = Executors.newSingleThreadExecutor()
        radio = NativeRadio(this)
        state.connected = false
        state.playing = false
        state.mode = "idle"
        state.scanning = false
        if (++usbRetries <= 2) {
            setUsb(R.string.usb_opening)
            main.postDelayed({ if (!destroyed) connectUsb() }, 1000)
        } else {
            setUsb(R.string.usb_stuck)
        }
        updateSession(); updateNotification(); changed()
    }

    private fun onUsbLost() {
        usbJob {
            radio.stop()
            radio.closeSource()
            conn?.close()
            conn = null
            connDevice = null
            main.post {
                state.connected = false
                state.playing = false
                state.mode = "idle"
                setUsb(R.string.usb_removed)
                state.scanning = false
                audio.flush()
                abandonFocus()
                releaseWakeLock()
                updateSession()
                updateNotification()
                changed()
            }
        }
    }

    /** Dongle'ı bulup açar; izin yoksa sistem penceresiyle ister. Ana iş parçacığından çağrılabilir. */
    fun connectUsb() {
        if (state.connected) return
        val dev = usbHelper.findDevice()
        if (dev == null) {
            setUsb(R.string.usb_notfound)
            changed()
            return
        }
        if (!usbHelper.hasPermission(dev)) {
            setUsb(R.string.usb_perm_wait)
            changed()
            if (!permissionAsked) {
                permissionAsked = true
                usbHelper.requestPermission(dev) { granted ->
                    permissionAsked = false
                    if (granted) connectUsb() else {
                        setUsb(R.string.usb_perm_denied)
                        changed()
                    }
                }
            }
            return
        }
        setUsb(R.string.usb_opening)
        changed()
        usbJob { token ->
            val c = usbHelper.open(dev)
            if (c == null) {
                main.post { setUsb(R.string.usb_open_fail); changed() }
                return@usbJob
            }
            opening = c
            val err = radio.openUsb(c.fileDescriptor)
            Log.i("OtoRadyo", "openUsb fd=" + c.fileDescriptor + " -> " + (err ?: "tamam"))
            if (token != usbToken) return@usbJob   // bekçi bu işi bıraktı, sonucu geçersiz
            opening = null
            if (err != null) {
                c.close()
                main.post { setUsb(R.string.usb_rtl_fail, loc(err)); changed() }
                return@usbJob
            }
            usbRetries = 0
            conn = c
            connDevice = dev
            radio.setForceMono(prefs.forceMono)
            radio.setDeemphasisMicros(if (prefs.deemphasis75) 75f else 50f)
            radio.setAgc(prefs.agc)
            if (!prefs.agc) radio.setGainIndex(prefs.gainIndex)
            radio.setPpm(prefs.ppm)
            main.post {
                state.connected = true
                setUsb(R.string.usb_connected)
                changed()
                if (prefs.autoPlay && !music.active) resume()
            }
        }
    }

    // ------------------------------------------------------------------ Komutlar (ana iş parçacığından)

    fun playFm(freqHz: Int) {
        abortOptimize()
        Log.i("OtoRadyo", "playFm $freqHz bagli=${state.connected}")
        if (!state.connected) { connectUsb(); return }
        music.stop()
        startForegroundNow()
        requestFocus()
        state.mode = "fm"
        state.playing = true
        state.fmHz = freqHz
        state.ps = fmList.firstOrNull { it.freqHz == freqHz }?.name ?: ""
        state.rt = ""; state.pty = -1; state.stereo = false; state.rdsSync = false; state.slide = null
        prefs.lastMode = "fm"
        prefs.lastFmHz = freqHz
        updateSession(); updateNotification(); changed()
        bg {
            audio.flush()
            if (!radio.startFm(freqHz)) main.post { showMessage(str(R.string.msg_fm_fail)) }
        }
    }

    fun playDab(s: DabService) {
        abortOptimize()
        Log.i("OtoRadyo", "playDab ${s.channel} ${s.label} sid=${s.sid} bagli=${state.connected}")
        if (!state.connected) { connectUsb(); return }
        music.stop()
        startForegroundNow()
        requestFocus()
        state.mode = "dab"
        state.playing = true
        state.dabChannel = s.channel
        state.dabSid = s.sid
        state.dabScids = s.scids
        state.dabLabel = s.label
        state.dls = ""; state.dabCodec = ""; state.dabSync = 0; state.slide = null
        state.dabFibErr = 0; state.dabMscOk = 0; state.dabMscErr = 0; state.dabRsErr = 0
        prefs.lastMode = "dab"
        prefs.lastDabKey = s.key
        updateSession(); updateNotification(); changed()
        bg {
            audio.flush()
            if (!radio.startDab(s.channel, s.sid, s.scids)) main.post { showMessage(str(R.string.msg_dab_fail)) }
        }
    }

    fun stopPlayback() {
        abortOptimize()
        state.playing = false
        updateSession(); updateNotification(); changed()
        bg {
            radio.stop()
            audio.flush()
            main.post {
                state.mode = "idle"
                if (!music.playing) {
                    abandonFocus()
                    releaseWakeLock()
                }
                updateSession(); updateNotification(); changed()
            }
        }
    }

    /** Son dinlenen istasyonu (yoksa listedeki ilkini) çalar. */
    fun resume() {
        if (prefs.lastMode == "dab") {
            val s = dabList.firstOrNull { it.key == prefs.lastDabKey } ?: dabList.firstOrNull()
            if (s != null) { playDab(s); return }
        }
        playFm(prefs.lastFmHz)
    }

    fun togglePlay() {
        if (music.active) { if (music.playing) music.pause() else resumeMusic() }
        else if (state.playing) stopPlayback() else resume()
    }

    /** Medya tuşu "çal": müzik seçiliyse müziği, değilse son istasyonu sürdürür. */
    fun playAny() {
        if (music.active) { if (!music.playing) resumeMusic() } else if (!state.playing) resume()
    }

    fun pauseAny() {
        if (music.active) music.pause() else if (state.playing) stopPlayback()
    }

    // ------------------------------------------------------------------ Müzik

    private val musicTick = object : Runnable {
        override fun run() {
            if (destroyed || !music.playing) return
            changed()
            main.postDelayed(this, 1000)
        }
    }

    private fun onMusicChange() {
        if (destroyed) return
        main.removeCallbacks(musicTick)
        if (music.playing) main.postDelayed(musicTick, 1000)
        else if (state.mode == "idle") { abandonFocus(); releaseWakeLock() }
        updateSession(); updateNotification(); changed()
    }

    /** Listeden seçilen parçayı çalar; dongle gerekmez, çalan radyo durur. */
    fun playMusic(list: List<Track>, index: Int) {
        if (state.scanning) cancelScan()
        if (state.recording) toggleRecording()
        if (state.mode != "idle" || state.playing) stopPlayback()
        startForegroundNow()
        requestFocus()
        music.play(list, index)
    }

    private fun resumeMusic() {
        startForegroundNow()
        requestFocus()
        music.resume()
    }

    private fun applyMusicVolume() {
        music.setVolume(if (mutedNow) 0f else if (duck) audioGain * 0.3f else audioGain)
    }

    private fun musicPermitted(): Boolean = checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Dahili hafıza, SD kart, USB bellek ve seçilen klasörleri yeniden tarar. */
    fun reloadMusic() {
        val token = ++musicScanToken
        musicLoading = true
        changed()
        val folders = prefs.musicFolders.toList()
        val permitted = musicPermitted()
        Thread {
            val found = MusicLibrary.scan(applicationContext, folders, permitted)
            val names = folders.associateBy { MusicLibrary.folderName(applicationContext, it) }
            main.post {
                if (!destroyed && token == musicScanToken) {
                    musicTracks = found
                    musicFolderNames = names
                    musicLoading = false
                    musicVersion++
                    Log.i("OtoRadyo", "muzik tarandi: ${found.size} dosya, kaynaklar ${found.map { it.source }.distinct()}")
                    changed()
                }
            }
        }.start()
    }

    fun isMusicFolder(name: String) = name in musicFolderNames

    fun addMusicFolder(uri: String) {
        prefs.musicFolders = prefs.musicFolders + uri
        reloadMusic()
    }

    fun removeMusicFolder(name: String) {
        val uri = musicFolderNames[name] ?: return
        prefs.musicFolders = prefs.musicFolders - uri
        try {
            contentResolver.releasePersistableUriPermission(android.net.Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: RuntimeException) {
        }
        reloadMusic()
    }

    /** SD kart ya da USB bellek takılınca / çıkarılınca liste kendini yeniler. */
    private fun registerMounts() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (musicVersion > 0) reloadMusic()
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED)
            addDataScheme("file")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
        mountReceiver = r
    }

    fun currentKey(): String = when (state.mode) {
        "fm" -> "fm:${state.fmHz}"
        "dab" -> "dab:${state.dabChannel}:${state.dabSid}:${state.dabScids}"
        else -> ""
    }

    fun toggleFavorite(key: String) {
        if (key.isEmpty()) return
        if (!favorites.remove(key)) favorites.add(key)
        prefs.favorites = favorites.toSet()
        changed()
    }

    /** Listede sonraki (+1) veya önceki (-1) istasyona geç. FM'de liste boşsa 0,1 MHz adım. */
    fun seek(dir: Int) {
        if (music.active) {
            if (dir > 0) music.next() else music.prev()
            return
        }
        val mode = if (state.mode == "idle") prefs.lastMode else state.mode
        if (mode == "dab") {
            val list = visibleDab()
            if (list.isEmpty()) return
            val i = list.indexOfFirst { it.key == currentKey() }
            val n = if (i < 0) (if (dir > 0) 0 else list.size - 1) else (i + dir + list.size) % list.size
            playDab(list[n])
        } else {
            val list = visibleFm()
            if (list.isEmpty()) { stepFm(dir); return }
            val cur = if (state.mode == "fm") state.fmHz else prefs.lastFmHz
            val next = if (dir > 0) list.firstOrNull { it.freqHz > cur } ?: list.first()
            else list.lastOrNull { it.freqHz < cur } ?: list.last()
            playFm(next.freqHz)
        }
    }

    fun stepFm(dir: Int) {
        var hz = (if (state.mode == "fm") state.fmHz else prefs.lastFmHz) + dir * 100000
        if (hz > 108000000) hz = 87500000
        if (hz < 87500000) hz = 108000000
        playFm(hz)
    }

    fun visibleFm(): List<FmStation> {
        val l = fmList.sortedBy { it.freqHz }
        return if (prefs.favoritesOnly) l.filter { it.key in favorites } else l
    }

    fun visibleDab(): List<DabService> =
        if (prefs.favoritesOnly) dabList.filter { it.key in favorites } else dabList

    fun scanFm() = startScan("fm")
    fun scanDab() = startScan("dab")

    private fun startScan(kind: String) {
        Log.i("OtoRadyo", "tarama $kind bagli=${state.connected}")
        if (!state.connected) { connectUsb(); return }
        scanFm.clear(); scanDab.clear()
        scanCancelRequested = false
        state.scanning = true
        state.scanKind = kind
        state.scanProgress = 0
        state.scanText = ""
        state.scanHz = 0
        state.playing = false
        state.mode = "idle"
        updateSession(); updateNotification(); changed()
        bg {
            audio.flush()
            if (kind == "fm") radio.scanFm(true) else radio.scanDab()
        }
    }

    fun cancelScan() {
        scanCancelRequested = true
        bg { radio.cancelScan() }
    }

    fun setPpm(ppm: Int) { prefs.ppm = ppm; bg { radio.setPpm(ppm) } }

    fun setAudioGain(pct: Int) {
        prefs.audioGainPct = pct
        audioGain = pct / 100f
        applyMusicVolume()
    }

    /** Çalan istasyonun sesini WAV dosyasına kaydet / kaydı bitir. */
    fun toggleRecording() {
        if (state.recording) {
            val name = recorder.stop()
            recordRequested = false
            state.recording = false
            showMessage(if (name != null) str(R.string.msg_rec_saved, name) else str(R.string.msg_rec_stopped))
            changed()
            return
        }
        if (!state.playing || music.active) { showMessage(str(R.string.msg_rec_need_play)); return }
        recordLabel = if (state.mode == "fm") String.format(java.util.Locale.US, "FM%.1f", state.fmHz / 1e6)
        else state.dabLabel.ifEmpty { "DAB" }
        state.recording = true
        state.recordStartMs = System.currentTimeMillis()
        recordRequested = true
        changed()
    }

    // ------------------------------------------------------------------ Pro: uyku, ekolayzer, yedekleme

    private var sleepToken = 0

    /** Gösterge verisi: yalnız spektrum gösteren tema görünürken hesaplanır. */
    val spectrum = AudioSpectrum()
    @Volatile var spectrumOn = false
    @Volatile private var mutedNow = false

    fun toggleMute() {
        state.muted = !state.muted
        mutedNow = state.muted
        applyMusicVolume()
        changed()
    }

    /** Verilen dakika sonra çalmayı durdurur; 0 zamanlayıcıyı kapatır. */
    fun setSleepTimer(minutes: Int) {
        val token = ++sleepToken
        if (minutes <= 0) {
            state.sleepAtMs = 0
            changed()
            return
        }
        state.sleepAtMs = System.currentTimeMillis() + minutes * 60_000L
        main.postDelayed({
            if (token == sleepToken && !destroyed) {
                state.sleepAtMs = 0
                pauseAny()
                showMessage(str(R.string.msg_sleep_end))
            }
        }, minutes * 60_000L)
        showMessage(str(R.string.msg_sleep_set, minutes))
    }

    fun setEqPreset(preset: Int) {
        prefs.eqPreset = preset
        audio.setEqPreset(preset)
        music.setEqPreset(preset)
    }

    /** İstasyon listeleri ve favoriler, taşınabilir JSON olarak. */
    fun exportStations(): String {
        val root = org.json.JSONObject().put("app", "radvela").put("version", 1)
        root.put("fm", org.json.JSONArray().apply { for (s in fmList) put(org.json.JSONObject().put("f", s.freqHz).put("n", s.name)) })
        root.put("dab", org.json.JSONArray().apply {
            for (s in dabList) put(org.json.JSONObject().put("c", s.channel).put("i", s.sid).put("k", s.scids).put("l", s.label).put("a", s.aac))
        })
        root.put("fav", org.json.JSONArray(favorites.toList()))
        return root.toString(2)
    }

    /** exportStations çıktısını geri yükler; biçim bozuksa hiçbir şeyi değiştirmeden false döner. */
    fun importStations(json: String): Boolean {
        try {
            val root = org.json.JSONObject(json)
            if (root.optString("app") !in setOf("radvela", "aethera")) return false   // eski ad: önceki yedekler
            val fm = mutableListOf<FmStation>()
            val dab = mutableListOf<DabService>()
            val fav = mutableSetOf<String>()
            root.getJSONArray("fm").let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { fm.add(FmStation(it.getInt("f"), it.optString("n", ""))) }
            }
            root.getJSONArray("dab").let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    dab.add(DabService(it.getString("c"), it.getLong("i"), it.optInt("k", 0), it.getString("l"), it.optBoolean("a", true)))
                }
            }
            root.getJSONArray("fav").let { a -> for (i in 0 until a.length()) fav.add(a.getString(i)) }
            fmList = fm.sortedBy { it.freqHz }.toMutableList()
            dabList = dab
            favorites = fav
            prefs.saveFm(fmList)
            prefs.saveDab(dabList)
            prefs.favorites = favorites.toSet()
            changed()
            return true
        } catch (e: org.json.JSONException) {
            Log.w("OtoRadyo", "yedek okunamadi: $e")
            return false
        }
    }

    private fun stopRecordingState(msg: String) {
        state.recording = false
        recordRequested = false
        showMessage(msg)
    }

    /** O an çalan FM frekansını istasyon listesine ekler. */
    fun addCurrentFm() {
        if (state.mode != "fm") { showMessage(str(R.string.msg_add_need_fm)); return }
        if (fmList.any { it.freqHz == state.fmHz }) { showMessage(str(R.string.msg_add_dup)); return }
        fmList.add(FmStation(state.fmHz, state.ps, 0f))
        fmList = fmList.sortedBy { it.freqHz }.toMutableList()
        prefs.saveFm(fmList)
        showMessage(str(R.string.msg_added, formatMhz(state.fmHz)))
        changed()
    }

    fun clearList(kind: String) {
        if (kind == "fm") { fmList = mutableListOf(); prefs.saveFm(fmList) }
        else { dabList = mutableListOf(); prefs.saveDab(dabList) }
        showMessage(str(R.string.msg_cleared))
        changed()
    }

    /**
     * Elle seçilen DAB kanalına ayarlanır. Kanalda kayıtlı servis varsa ilki çalınır; yoksa kanal dinlenir,
     * bulunan servisler listeye eklenir ve ilki otomatik çalınır.
     */
    fun tuneDabChannel(channel: String) {
        val known = dabList.firstOrNull { it.channel == channel }
        if (known != null) { playDab(known); return }
        if (!state.connected) { connectUsb(); return }
        music.stop()
        manualChannel = channel
        startForegroundNow()
        requestFocus()
        state.mode = "dab"
        state.playing = true
        state.dabChannel = channel
        state.dabSid = 0
        state.dabScids = 0
        state.dabLabel = ""
        state.dabEnsemble = ""
        state.dls = ""; state.dabCodec = ""; state.dabSync = 0; state.slide = null
        prefs.lastMode = "dab"
        showMessage(str(R.string.msg_tuning_dab, channel))
        updateSession(); updateNotification(); changed()
        bg {
            audio.flush()
            if (!radio.startDab(channel, 0, 0)) main.post { showMessage(str(R.string.msg_dab_fail)) }
        }
    }

    fun showMessage(text: String) {
        Log.i("OtoRadyo", "mesaj: $text")
        state.message = text
        val token = ++msgToken
        main.postDelayed({ if (token == msgToken && !destroyed) { state.message = ""; changed() } }, 7000)
        changed()
    }

    /** Tanılama satırları (ses çıkışı + IQ tamponu). Ana iş parçacığından çağrılır. */
    // ------------------------------------------------------------------ DAB+ kazanç optimizasyonu

    private var optToken = 0
    private var optPrevAgc: Boolean? = null   // tarama sürerken AGC kapalıdır; yarıda kesilirse eski durum geri yüklenir

    private fun abortOptimize() {
        optToken++
        optPrevAgc?.let { prev ->
            optPrevAgc = null
            prefs.agc = prev
            bg { radio.setAgc(prev) }
        }
    }

    /**
     * Çalan DAB+ kanalında kazancı tablo boyunca tarar, her adımda SNR'yi ölçer ve en iyisini seçer.
     * Güçlü FM/TV vericilerinin bulunduğu şehirlerde AGC (tepe seviyesine göre) çoğu zaman SNR açısından
     * en iyi kazancı bulamaz: fazla kazanç ara modülasyon yapar, az kazanç gürültü sayısını bozar.
     */
    fun optimizeGain() {
        if (state.mode != "dab" || !state.playing || state.dabSync < 1) {
            showMessage(str(R.string.msg_gain_need_dab))
            return
        }
        val count = radio.gainCount()
        if (count < 3) return
        abortOptimize()
        val token = ++optToken
        optPrevAgc = prefs.agc
        val results = HashMap<Int, Float>()
        prefs.agc = false

        fun finish() {
            optPrevAgc = null   // sonuç bilerek elle kazanç olarak kalır
            val best = results.maxByOrNull { it.value } ?: return
            prefs.gainIndex = best.key
            bg { radio.setGainIndex(best.key) }
            showMessage(str(R.string.msg_gain_best, best.key + 1, count, best.value))
            changed()
        }

        fun step(plan: List<Int>, i: Int, refine: Boolean) {
            if (token != optToken) return
            if (i >= plan.size) {
                if (!refine) {
                    val best = results.maxByOrNull { it.value }?.key ?: return
                    val extra = listOf(best - 1, best + 1).filter { it in 0 until count && it !in results }
                    if (extra.isNotEmpty()) { step(extra, 0, true); return }
                }
                finish()
                return
            }
            val idx = plan[i]
            bg { radio.setGainIndex(idx) }
            showMessage(str(if (refine) R.string.msg_gain_refine else R.string.msg_gain_search, i + 1, plan.size))
            main.postDelayed({
                if (token != optToken) return@postDelayed
                results[idx] = if (state.dabSync >= 1) state.dabSnr else 0f
                step(plan, i + 1, refine)
            }, 2300)
        }
        step((0 until count step 2).toList(), 0, false)
    }

    fun diagnostics(): String {
        val dropped = radio.droppedSamples()
        return audio.statsText(lc()) + " · " + str(R.string.diag_gain, String.format(java.util.Locale.US, "%.1f", radio.gainDb()), radio.signalLevel().toInt(), dropped) +
            (if (dropped > 0) " " + str(R.string.diag_overrun) else "")
    }

    fun setAgc(on: Boolean) { prefs.agc = on; bg { radio.setAgc(on) } }
    fun setGainIndex(index: Int) { prefs.agc = false; prefs.gainIndex = index; bg { radio.setGainIndex(index) } }
    fun setForceMono(on: Boolean) { prefs.forceMono = on; bg { radio.setForceMono(on) } }
    fun setDeemphasis75(on: Boolean) { prefs.deemphasis75 = on; bg { radio.setDeemphasisMicros(if (on) 75f else 50f) } }
    fun gainCount(): Int = radio.gainCount()

    fun shutdownAndStop() {
        bg {
            radio.stop()
            radio.closeSource()
            conn?.close()
            conn = null
            main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }

    // ------------------------------------------------------------------ NativeRadio.Listener

    override fun onAudio(pcm: ShortArray, sampleRate: Int) {
        if (recordRequested && !recorder.active) {
            recordRequested = false
            if (!recorder.start(sampleRate, recordLabel)) main.post { stopRecordingState(str(R.string.msg_rec_fail)) }
        }
        if (recorder.active) recorder.write(pcm, sampleRate)  // ses seviyesinden bağımsız (ham) kayıt
        if (spectrumOn && !mutedNow) spectrum.feed(pcm, sampleRate)
        val g = if (mutedNow) 0f else if (duck) audioGain * 0.3f else audioGain
        if (g != 1.0f) {
            for (i in pcm.indices) {
                val v = (pcm[i] * g).toInt()
                pcm[i] = if (v > 32767) 32767 else if (v < -32768) -32768 else v.toShort()
            }
        }
        audio.write(pcm, sampleRate)
    }

    override fun onEvent(type: Int, text: String, a: Double, b: Double) {
        main.post { if (!destroyed) handleEvent(type, text, a, b) }
    }

    override fun onSlide(image: ByteArray, subtype: Int) {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size) ?: return
        main.post {
            if (!destroyed && state.mode == "dab") {
                state.slide = bmp
                updateSession()
                changed()
            }
        }
    }

    private fun handleEvent(type: Int, text: String, a: Double, b: Double) {
        when (type) {
            NativeRadio.EV_CONNECTION -> {
                if (a == 0.0 && state.connected && conn != null && !destroyed) {
                    // Çekirdek bağlantıyı yitirdi (ör. USB hatası)
                    showMessage(str(R.string.msg_conn_lost))
                }
            }
            NativeRadio.EV_FM_STATUS -> {
                state.rssi = a.toFloat()
                val flags = b.toInt()
                state.stereo = (flags and 1) != 0
                state.rdsSync = (flags and 2) != 0
            }
            NativeRadio.EV_FM_PS -> {
                state.ps = text
                if (text.isNotEmpty() && state.mode == "fm") {
                    fmList.firstOrNull { it.freqHz == state.fmHz }?.let { if (it.name != text) { it.name = text; prefs.saveFm(fmList) } }
                }
                updateSession(); updateNotification()
            }
            NativeRadio.EV_FM_RT -> { state.rt = text; updateSession() }
            NativeRadio.EV_FM_PTY -> state.pty = a.toInt()
            NativeRadio.EV_FM_FOUND -> if (state.scanning) scanFm.add(FmStation(a.toInt(), "", b.toFloat()))
            NativeRadio.EV_FM_NAME -> if (state.scanning) scanFm.firstOrNull { it.freqHz == a.toInt() }?.name = text
            NativeRadio.EV_DAB_ENSEMBLE -> {
                val p = text.split(NativeRadio.SEP)
                if (p.size == 2 && p[0] == state.dabChannel) state.dabEnsemble = p[1]
            }
            NativeRadio.EV_DAB_SERVICE -> {
                val p = text.split(NativeRadio.SEP)
                if (p.size == 2) {
                    val flags = b.toInt()
                    val s = DabService(p[0], a.toLong(), flags and 0xFF, p[1], (flags and 0x100) != 0)
                    if (state.scanning) {
                        if (scanDab.none { it.key == s.key }) scanDab.add(s)
                    } else if (manualChannel.isNotEmpty() && s.channel == manualChannel) {
                        if (dabList.none { it.key == s.key }) dabList.add(s)
                        if (state.dabSid == 0L) playDab(s)    // ilk bulunan servisi çal
                    } else if (s.channel == state.dabChannel && s.sid == state.dabSid && s.scids == state.dabScids) {
                        state.dabLabel = s.label
                        updateSession(); updateNotification()
                    }
                }
            }
            NativeRadio.EV_DAB_SELECTED -> {
                if (a.toLong() == state.dabSid && text.isNotEmpty()) {
                    state.dabLabel = text
                    updateSession(); updateNotification()
                }
            }
            NativeRadio.EV_DAB_STATUS -> {
                state.dabSnr = a.toFloat()
                state.dabSync = b.toInt()
            }
            NativeRadio.EV_DAB_QUALITY -> {
                val p = text.split(',')
                if (p.size >= 4) {
                    state.dabFibErr = p[0].toIntOrNull() ?: 0
                    state.dabMscOk = p[1].toIntOrNull() ?: 0
                    state.dabMscErr = p[2].toIntOrNull() ?: 0
                    state.dabRsErr = p[3].toIntOrNull() ?: 0
                }
            }
            NativeRadio.EV_DAB_LABEL -> { state.dls = text; updateSession() }
            NativeRadio.EV_DAB_AUDIO -> state.dabCodec = text + " " + (a.toInt() / 1000) + " kHz " + str(if (b != 0.0) R.string.lc_stereo else R.string.lc_mono)
            NativeRadio.EV_DAB_SERVICES_COMPLETE -> {
                if (manualChannel.isNotEmpty() && text == manualChannel) {
                    manualChannel = ""
                    prefs.saveDab(dabList)
                    showMessage(str(R.string.msg_dab_saved, dabList.size))
                }
            }
            NativeRadio.EV_SCAN_PROGRESS -> {
                state.scanning = true
                state.scanProgress = a.toInt()
                state.scanText = text
            }
            NativeRadio.EV_SCAN_FREQ -> state.scanHz = a.toInt()
            NativeRadio.EV_SCAN_DONE -> finishScan(cancelled = a != 0.0)
            NativeRadio.EV_MESSAGE -> showMessage(loc(text))
        }
        changed()
    }

    private fun finishScan(cancelled: Boolean) {
        if (!state.scanning) return
        val kind = state.scanKind
        state.scanning = false
        if (!cancelled) {
            if (kind == "fm") {
                if (scanFm.isEmpty()) showMessage(str(R.string.msg_fm_none))
                else {
                    fmList = scanFm.sortedBy { it.freqHz }.toMutableList()
                    prefs.saveFm(fmList)
                    showMessage(str(R.string.msg_fm_found, fmList.size))
                }
            } else {
                if (scanDab.isEmpty()) showMessage(str(R.string.msg_dab_none))
                else {
                    dabList = scanDab.toMutableList()
                    prefs.saveDab(dabList)
                    showMessage(str(R.string.msg_dab_found, dabList.size))
                }
            }
            // Taramadan sonra dinlemeye geri dön
            if (kind == "fm" && fmList.isNotEmpty()) {
                val s = fmList.firstOrNull { it.freqHz == prefs.lastFmHz } ?: fmList.first()
                playFm(s.freqHz)
            } else if (kind == "dab" && dabList.isNotEmpty()) {
                val s = dabList.firstOrNull { it.key == prefs.lastDabKey } ?: dabList.first()
                playDab(s)
            }
        }
        updateSession(); updateNotification()
    }

    // ------------------------------------------------------------------ Bildirim / oturum / ses odağı

    private fun setupSession() {
        val s = MediaSession(this, "Radvela")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { main.post { playAny() } }
            override fun onPause() { main.post { pauseAny() } }
            override fun onStop() { main.post { pauseAny() } }
            override fun onSeekTo(pos: Long) { main.post { if (music.active) music.seekTo(pos.toInt()) } }
            override fun onSkipToNext() { main.post { seek(+1) } }
            override fun onSkipToPrevious() { main.post { seek(-1) } }
            // Basılı tutma / ileri-geri sarma tuşları: FM'de 0,1 MHz adım, DAB+'ta sonraki/önceki servis
            override fun onFastForward() { main.post { if (music.active) music.seekBy(+15000) else if (modeNow() == "fm") stepFm(+1) else seek(+1) } }
            override fun onRewind() { main.post { if (music.active) music.seekBy(-15000) else if (modeNow() == "fm") stepFm(-1) else seek(-1) } }
        })
        s.isActive = true
        session = s
        updateSession()
    }

    private fun title(): String = if (music.active) music.title else when (state.mode) {
        "fm" -> if (state.ps.isNotEmpty()) state.ps else formatMhz(state.fmHz)
        "dab" -> if (state.dabLabel.isNotEmpty()) state.dabLabel else "DAB+ " + state.dabChannel
        else -> "Radvela"
    }

    private fun subtitle(): String = if (music.active) music.artist.ifEmpty { str(R.string.music_title) } else when (state.mode) {
        "fm" -> if (state.rt.isNotEmpty()) state.rt else "FM " + formatMhz(state.fmHz)
        "dab" -> if (state.dls.isNotEmpty()) state.dls else "DAB+ " + state.dabChannel + if (state.dabEnsemble.isNotEmpty()) " · " + state.dabEnsemble else ""
        else -> if (state.connected) str(R.string.notif_ready) else state.usb
    }

    private fun modeNow(): String = if (state.mode == "idle") prefs.lastMode else state.mode

    private var artKey = ""
    private var artBmp: android.graphics.Bitmap? = null

    /** Araç göstergesi / medya kartı için küçük kapak görseli: çalan frekans ya da kanal, vurgu renginde halka. */
    private fun art(): android.graphics.Bitmap {
        if (music.active) music.art?.let { return it }
        state.slide?.let { return it }
        val label = when {
            music.active -> "MP3"
            state.mode == "fm" -> String.format(java.util.Locale.US, "%.1f", state.fmHz / 1e6)
            state.mode == "dab" -> state.dabChannel.ifEmpty { "DAB" }
            else -> "FM"
        }
        val unit = if (music.active) "" else if (state.mode == "dab") "DAB+" else "MHz"
        val logoName = when (state.mode) {
            "fm" -> fmList.firstOrNull { it.freqHz == state.fmHz }?.name ?: ""
            "dab" -> state.dabLabel
            else -> ""
        }
        val logo = if (logoName.isNotEmpty()) { Logos.request(logoName); Logos.get(logoName) } else null
        val key = "$label|$unit|${prefs.colorScheme}|$logoName|" + (if (logo != null) Logos.version else 0)
        artBmp?.let { if (key == artKey) return it }
        val sz = 320
        val bmp = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val acc = Pal.SCHEMES[prefs.colorScheme.coerceIn(0, Pal.SCHEMES.size - 1)]
        c.drawColor(0xFF050607.toInt())   // kapak görseli her modda koyu
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        if (logo != null) {
            // İstasyon logosu: açık zeminli yuvarlak köşeli karo, çevresinde vurgu renkli ince çerçeve
            val tile = android.graphics.RectF(sz * 0.12f, sz * 0.12f, sz * 0.88f, sz * 0.88f)
            p.style = android.graphics.Paint.Style.FILL
            p.color = 0xFFF4F2EF.toInt()
            c.drawRoundRect(tile, sz * 0.12f, sz * 0.12f, p)
            val box = tile.width() * 0.84f
            val k = minOf(box / logo.width, box / logo.height)
            val w = logo.width * k
            val h = logo.height * k
            c.drawBitmap(logo, null, android.graphics.RectF(sz / 2f - w / 2f, sz / 2f - h / 2f, sz / 2f + w / 2f, sz / 2f + h / 2f), p)
            p.style = android.graphics.Paint.Style.STROKE
            p.strokeWidth = 8f
            p.color = acc
            c.drawRoundRect(tile, sz * 0.12f, sz * 0.12f, p)
            artKey = key
            artBmp = bmp
            return bmp
        }
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = 10f
        p.color = acc
        c.drawCircle(sz / 2f, sz / 2f, sz * 0.44f, p)
        p.style = android.graphics.Paint.Style.FILL
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        p.color = android.graphics.Color.WHITE
        p.textSize = sz * (if (label.length > 4) 0.2f else 0.27f)
        c.drawText(label, sz / 2f, sz * 0.57f, p)
        p.color = acc
        p.textSize = sz * 0.1f
        c.drawText(unit, sz / 2f, sz * 0.73f, p)
        artKey = key
        artBmp = bmp
        return bmp
    }

    private fun updateSession() {
        val s = session ?: return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_SEEK_TO
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (state.playing || music.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    if (music.active) music.positionMs.toLong() else PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        val album = if (music.active) music.album else if (state.mode == "dab") "DAB+" else if (state.mode == "fm") "FM" else "Radyo"
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title())
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle())
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, if (music.active) music.durationMs.toLong() else 0L)
                .build()
        )
    }

    private fun startForegroundNow() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun action(act: String, label: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req, Intent(this, RadioService::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_radio), label, pi).build()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(title())
            .setContentText(subtitle())
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(ACTION_PREV, str(R.string.notif_prev), 2))
            .addAction(action(ACTION_NEXT, str(R.string.notif_next), 3))
            .addAction(action(ACTION_STOP, str(R.string.notif_close), 1))
        session?.let { b.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1)) }
        return b.build()
    }

    private fun updateNotification() {
        val key = title() + "|" + subtitle()
        if (key == lastNotif || destroyed) return
        lastNotif = key
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun requestFocus() {
        acquireWakeLock()
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        // Başka bir kaynak (aracın kendi radyosu/müziği) kalıcı olarak devraldı: DSP'yi ve USB'yi boşa harcama
                        audio.muted = true
                        main.post { pauseAny() }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { audio.muted = true; main.post { music.focusLost() } }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { duck = true; main.post { applyMusicVolume() } }   // navigasyon uyarısı: kıs
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        audio.muted = false
                        duck = false
                        main.post { applyMusicVolume(); music.focusGained() }
                    }
                }
            }
            .build()
        focusRequest = req
        // Her çalma isteğinde odak yeniden istenir; sonuç, susturma durumunu belirler (önceden kalıcı kayıp
        // sonrası yeni istasyonda ses açılmıyordu).
        val r = audioManager.requestAudioFocus(req)
        duck = false
        audio.muted = (r == AudioManager.AUDIOFOCUS_REQUEST_FAILED)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "otoradyo:dsp").apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
