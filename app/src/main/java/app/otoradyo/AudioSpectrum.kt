package app.otoradyo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Çıkan sesin spektrumu (gösterge için). Radyo sinyalinin değil, sesin frekans dağılımıdır.
 * feed() ses iş parçacığında çalışır, levels ve rms arayüzden yarış önemsiz biçimde okunur.
 */
class AudioSpectrum {
    companion object {
        const val BANDS = 40
        private const val N = 1024
    }

    val levels = FloatArray(BANDS)
    @Volatile var rms = 0f
        private set

    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val input = FloatArray(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val cosT = FloatArray(N / 2) { cos(2 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { sin(2 * PI * it / N).toFloat() }
    private var fill = 0
    private var skip = false

    fun feed(pcm: ShortArray, sampleRate: Int) {
        var i = 0
        while (i + 1 < pcm.size) {
            input[fill++] = (pcm[i] + pcm[i + 1]) / 65536f
            i += 2
            if (fill == N) {
                fill = 0
                skip = !skip
                if (!skip) analyse(sampleRate)   // saniyede ~25 kare yeter
            }
        }
    }

    private fun analyse(sampleRate: Int) {
        var sum = 0f
        for (k in 0 until N) {
            sum += input[k] * input[k]
            re[k] = input[k] * window[k]
            im[k] = 0f
        }
        rms = sqrt(sum / N)
        fft()
        val fMin = 60.0
        val fMax = sampleRate / 2.0
        val binHz = sampleRate.toDouble() / N
        for (b in 0 until BANDS) {
            val lo = fMin * Math.exp(ln(fMax / fMin) * b / BANDS)
            val hi = fMin * Math.exp(ln(fMax / fMin) * (b + 1) / BANDS)
            val k0 = max(1, (lo / binHz).toInt())
            val k1 = min(N / 2 - 1, max(k0, (hi / binHz).toInt()))
            var peak = 0f
            for (k in k0..k1) peak = max(peak, re[k] * re[k] + im[k] * im[k])
            val v = ((10f * log10(peak + 1e-9f) + 30f) / 60f).coerceIn(0f, 1f)
            levels[b] = if (v > levels[b]) v else levels[b] * 0.82f + v * 0.18f   // hızlı yükselir, yavaş iner
        }
    }

    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= N) {
            val half = len / 2
            val step = N / len
            var i = 0
            while (i < N) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = -sinT[k * step]
                    val a = i + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                i += len
            }
            len = len shl 1
        }
    }
}
