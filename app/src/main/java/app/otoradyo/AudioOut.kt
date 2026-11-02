package app.otoradyo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.os.Build

/**
 * Akışlı stereo PCM çıkışı.
 *
 * Radyo gerçek zamanlı bir kaynaktır ve kaynağın saati ses donanımınınkiyle birebir tutmaz. Bu yüzden:
 *  - Çalmaya, ~250 ms ön tampon dolunca başlanır (açılışta ve her kesintiden sonra), böylece tek tek
 *    çıtırtı/kesik yerine düzgün ve kısa bir duraklama olur.
 *  - Yazma engellemesizdir; tampon taşarsa (kaynak hızlıysa) fazlası atılır.
 *  - Tampon ~1 sn'dir: saat kaymasını dakikalarca emer.
 */
class AudioOut {
    companion object {
        /** Ön ayarlar: düşükten yükseğe 5 noktada dB. Sıra menüdeki adlarla aynıdır. */
        private val EQ_CURVES = listOf(
            intArrayOf(0, 0, 0, 0, 0),     // düz
            intArrayOf(6, 4, 0, 0, 0),     // bas
            intArrayOf(-2, 0, 3, 3, 0),    // konuşma
            intArrayOf(0, 0, 0, 3, 6),     // tiz
            intArrayOf(5, 2, 0, 2, 5),     // gürlük
        )
    }

    private var track: AudioTrack? = null
    private var rate = 0
    private var written = 0L          // sürücüye yazılan çerçeve sayısı
    private var started = false
    private var bufferFrames = 0

    @Volatile
    var muted = false

    // Tanılama
    @Volatile var underruns = 0
        private set
    @Volatile var overflows = 0L
        private set
    @Volatile var rebuffers = 0
        private set
    @Volatile var currentRate = 0
        private set

    private var eq: Equalizer? = null
    private var eqPreset = 0

    /** Ekolayzer ön ayarını seçer (0 = düz, kapalı); ses izi yeniden kurulunca da geçerli kalır. */
    @Synchronized
    fun setEqPreset(preset: Int) {
        eqPreset = preset
        applyEq()
    }

    private fun applyEq() {
        eq?.release()
        eq = null
        val t = track ?: return
        val curve = EQ_CURVES.getOrNull(eqPreset) ?: return
        if (curve.all { it == 0 }) return
        try {
            val e = Equalizer(0, t.audioSessionId)
            val bands = e.numberOfBands.toInt()
            val range = e.bandLevelRange
            for (b in 0 until bands) {
                // Eğri 5 noktalıdır; cihazın bant sayısı farklı olabilir
                val pos = if (bands > 1) b * (curve.size - 1f) / (bands - 1) else 0f
                val i = pos.toInt().coerceAtMost(curve.size - 2)
                val db = curve[i] + (curve[i + 1] - curve[i]) * (pos - i)
                e.setBandLevel(b.toShort(), (db * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            }
            e.enabled = true
            eq = e
        } catch (ex: RuntimeException) {
            android.util.Log.w("OtoRadyo", "ekolayzer kullanilamadi: $ex")
        }
    }

    @Synchronized
    fun write(pcm: ShortArray, sampleRate: Int) {
        if (muted) return
        if (track == null || sampleRate != rate) rebuild(sampleRate)
        val t = track ?: return

        val n = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
        if (n > 0) written += n / 2
        if (n < pcm.size) {
            overflows += (pcm.size - maxOf(n, 0)) / 2
            android.util.Log.w("OtoRadyo", "ses tamponu tasti: atilan toplam=$overflows dolu=${(written - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)) * 1000 / rate} ms")
        }

        val fill = written - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
        if (!started) {
            if (fill >= rate / 4) {          // 250 ms
                t.play()
                started = true
                armForStart(t, false)
            }
        } else if (fill <= 0) {
            // Veri bitti: ön tamponu yeniden doldurana kadar duraklat
            t.pause()
            started = false
            armForStart(t, true)
            underruns++
            rebuffers++
        }
    }

    /** Eşik API'si olmayan sürümlerde etkin tampon küçültülerek aynı sonuç alınır: çalma başlayınca tam boyuta açılır. */
    private fun armForStart(t: AudioTrack, waiting: Boolean) {
        if (Build.VERSION.SDK_INT < 31) t.bufferSizeInFrames = if (waiting) rate / 4 else bufferFrames
    }

    private fun rebuild(sampleRate: Int) {
        release()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        // ~2 sn (çerçeve başına 4 bayt). AudioTrack varsayılan olarak tampon tamamen dolunca başlar; tampon sürekli
        // dolu kalırsa her yazmada ses parçası atılır (çıtırtı). Bu yüzden başlangıç eşiği ön tampona çekilir.
        val size = maxOf(minBuf * 2, sampleRate * 8)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
        if (Build.VERSION.SDK_INT >= 31) t.setStartThresholdInFrames(sampleRate / 4)  // write() içindeki 250 ms ön tamponla aynı
        else t.bufferSizeInFrames = sampleRate / 4  // Android 11 ve altı (DiLink 3): çalma başlayınca armForStart açar
        track = t
        rate = sampleRate
        currentRate = sampleRate
        bufferFrames = size / 4
        written = 0
        started = false
        applyEq()
    }

    /** İstasyon değişirken eski sesi at. Çalma, ön tampon dolunca kendiliğinden başlar. */
    @Synchronized
    fun flush() {
        track?.let {
            try {
                it.pause()
                it.flush()
                armForStart(it, true)
            } catch (_: IllegalStateException) {
            }
        }
        written = 0
        started = false
    }

    @Synchronized
    fun release() {
        eq?.release()
        eq = null
        track?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
        rate = 0
        written = 0
        started = false
    }

    @Synchronized
    fun statsText(ctx: android.content.Context): String {
        val t = track ?: return ctx.getString(R.string.audio_off)
        val fill = written - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
        val ms = if (rate > 0) (fill * 1000 / rate) else 0
        val sys = if (android.os.Build.VERSION.SDK_INT >= 24) t.underrunCount else -1
        return String.format(java.util.Locale.US, ctx.getString(R.string.audio_stats), rate, ms, bufferFrames * 1000L / maxOf(rate, 1), underruns, overflows, sys)
    }
}
