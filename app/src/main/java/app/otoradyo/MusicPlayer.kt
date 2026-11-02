package app.otoradyo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Yerel dosya çalar. Çözümü cihaz yapar (MediaPlayer): uygulamaya çözücü eklenmez. Yalnız ana iş parçacığından
 * çağrılır; ses odağı ve radyoyla geçiş RadioService'tedir.
 */
class MusicPlayer(private val ctx: Context, private val onChange: () -> Unit, private val onFail: () -> Unit) {
    companion object {
        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2
        private const val TAG = "OtoRadyo"
    }

    private var queue: List<Track> = emptyList()
    private var order = IntArray(0)
    private var pos = 0
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var eq: Equalizer? = null
    private var eqPreset = 0
    private var volume = 1f
    private var focusPaused = false
    private var errors = 0
    private var artToken = 0
    private val main = Handler(Looper.getMainLooper())
    private val meta = Executors.newSingleThreadExecutor()

    /** Radyo yerine müzik seçili (duraklatılmış da olabilir). */
    var active = false
        private set
    var playing = false
        private set
    var shuffle = false
        private set
    var repeat = REPEAT_OFF
    var art: Bitmap? = null
        private set
    private var tagTitle = ""
    private var tagArtist = ""

    val current: Track? get() = order.getOrNull(pos)?.let { queue.getOrNull(it) }
    val title: String get() = tagTitle.ifEmpty { current?.title ?: "" }
    val artist: String get() = tagArtist.ifEmpty { current?.artist ?: "" }
    val album: String get() = current?.album ?: ""

    val positionMs: Int
        get() = try { if (prepared) mp?.currentPosition ?: 0 else 0 } catch (_: IllegalStateException) { 0 }

    val durationMs: Int
        get() = try { if (prepared) mp?.duration?.coerceAtLeast(0) ?: 0 else 0 } catch (_: IllegalStateException) { 0 }

    fun play(list: List<Track>, index: Int) {
        queue = list
        errors = 0
        buildOrder(index)
        active = true
        playing = true
        focusPaused = false
        open()
    }

    fun pause() {
        playing = false
        focusPaused = false
        try { if (prepared && mp?.isPlaying == true) mp?.pause() } catch (_: IllegalStateException) {}
        onChange()
    }

    fun resume() {
        if (current == null) return
        active = true
        playing = true
        focusPaused = false
        if (mp == null) { open(); return }
        if (prepared) try { mp?.start() } catch (_: IllegalStateException) {}
        onChange()
    }

    fun stop() {
        active = false
        playing = false
        focusPaused = false
        artToken++
        art = null
        releasePlayer()
        onChange()
    }

    fun next() = step(+1)

    fun prev() {
        if (positionMs > 3000) seekTo(0) else step(-1)
    }

    fun seekTo(ms: Int) {
        try { if (prepared) mp?.seekTo(ms) } catch (_: IllegalStateException) {}
        onChange()
    }

    fun seekBy(ms: Int) = seekTo((positionMs + ms).coerceIn(0, durationMs))

    fun toggleShuffle() {
        shuffle = !shuffle
        buildOrder(order.getOrNull(pos) ?: 0)
        onChange()
    }

    fun cycleRepeat() {
        repeat = (repeat + 1) % 3
        onChange()
    }

    /** Başka bir ses kaynağı geçici olarak aldı (arama, navigasyon): dönüşte kaldığı yerden sürer. */
    fun focusLost() {
        if (!playing) return
        focusPaused = true
        try { if (prepared && mp?.isPlaying == true) mp?.pause() } catch (_: IllegalStateException) {}
    }

    fun focusGained() {
        if (!focusPaused) return
        focusPaused = false
        if (playing && prepared) try { mp?.start() } catch (_: IllegalStateException) {}
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        try { mp?.setVolume(volume, volume) } catch (_: IllegalStateException) {}
    }

    fun setEqPreset(preset: Int) {
        eqPreset = preset
        applyEq()
    }

    fun release() {
        artToken++
        releasePlayer()
        meta.shutdownNow()
    }

    /** Karışık çalmada sıra yeniden dizilir, seçilen parça başa gelir. */
    private fun buildOrder(first: Int) {
        val n = queue.size
        if (!shuffle) {
            order = IntArray(n) { it }
            pos = first.coerceIn(0, (n - 1).coerceAtLeast(0))
            return
        }
        val rest = (0 until n).filter { it != first }.shuffled()
        order = (listOf(first) + rest).toIntArray()
        pos = 0
    }

    private fun step(dir: Int) {
        if (order.isEmpty()) return
        pos = (pos + dir + order.size) % order.size
        open()
    }

    private fun applyEq() {
        eq?.release()
        eq = null
        val p = mp ?: return
        eq = AudioOut.makeEq(p.audioSessionId, eqPreset)
    }

    private fun releasePlayer() {
        eq?.release()
        eq = null
        prepared = false
        mp?.let {
            it.setOnPreparedListener(null)
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            try { it.reset() } catch (_: IllegalStateException) {}
            it.release()
        }
        mp = null
    }

    private fun open() {
        releasePlayer()
        val t = current ?: return
        tagTitle = ""
        tagArtist = ""
        art = null
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
            p.setOnPreparedListener {
                prepared = true
                errors = 0
                it.setVolume(volume, volume)
                applyEq()
                if (playing && !focusPaused) it.start()
                onChange()
            }
            p.setOnCompletionListener { finished() }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "muzik hatasi $what/$extra: ${t.uri}")
                failed()
                true
            }
            p.setDataSource(ctx, t.uri)
            p.prepareAsync()
        } catch (e: IOException) {
            Log.w(TAG, "muzik acilamadi: $e")
            failed()
        } catch (e: RuntimeException) {
            Log.w(TAG, "muzik acilamadi: $e")
            failed()
        }
        loadTags(t.uri)
        onChange()
    }

    private fun finished() {
        when {
            repeat == REPEAT_ONE -> { seekTo(0); try { mp?.start() } catch (_: IllegalStateException) {} }
            pos == order.size - 1 && repeat == REPEAT_OFF -> {
                pos = 0
                playing = false
                open()
            }
            else -> step(+1)
        }
    }

    /** Bellek çıkarılmış ya da dosya bozuk: birkaç deneme sonra durur, sonsuza dek hata üretmez. */
    private fun failed() {
        errors++
        if (errors >= minOf(queue.size, 5)) {
            stop()
            onFail()
        } else {
            main.post { if (active) step(+1) }
        }
    }

    private fun loadTags(uri: Uri) {
        val token = ++artToken
        try {
            meta.execute {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    val t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                    val a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                    val pic = r.embeddedPicture?.let { decode(it) }
                    main.post {
                        if (token == artToken) {
                            tagTitle = t
                            tagArtist = a
                            art = pic
                            onChange()
                        }
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "muzik etiketi okunamadi: $e")
                } finally {
                    try { r.release() } catch (_: Exception) {}
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun decode(data: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        var s = 1
        while (o.outWidth / s > 1024 || o.outHeight / s > 1024) s *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
