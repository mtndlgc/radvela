package app.otoradyo

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Çalan sesi 16-bit stereo WAV dosyasına kaydeder.
 * Android 10+ ta Müzik/Radvela klasörüne (MediaStore), daha eskilerde uygulamaya özel Müzik klasörüne yazar.
 */
class Recorder(private val ctx: Context) {
    private var out: FileOutputStream? = null
    private var pfd: android.os.ParcelFileDescriptor? = null
    private var uri: android.net.Uri? = null
    private var dataBytes = 0L
    private var rate = 0

    @Volatile
    var active = false
        private set

    var displayName = ""
        private set

    @Synchronized
    fun start(sampleRate: Int, label: String): Boolean {
        if (active) return true
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = ("Radvela_" + label + "_" + stamp).replace(Regex("[^A-Za-z0-9_.-]"), "_")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.wav")
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/Radvela")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val u = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
                val p = ctx.contentResolver.openFileDescriptor(u, "rw")
                if (p == null) {
                    ctx.contentResolver.delete(u, null, null)
                    return false
                }
                uri = u
                pfd = p
                out = FileOutputStream(p.fileDescriptor)
            } else {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return false
                dir.mkdirs()
                out = FileOutputStream(File(dir, "$base.wav"))
            }
            rate = sampleRate
            dataBytes = 0
            out!!.write(header(rate, 0))
            displayName = "$base.wav"
            active = true
            return true
        } catch (e: Exception) {
            val u = uri
            cleanup()
            // Yarım kalan MediaStore kaydını sil (Müzik'te boş, "beklemede" dosya kalmasın)
            if (u != null) try { ctx.contentResolver.delete(u, null, null) } catch (_: Exception) {}
            return false
        }
    }

    @Synchronized
    fun write(pcm: ShortArray, sampleRate: Int) {
        if (!active || sampleRate != rate) return
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pcm)
        try {
            out!!.write(bb.array())
            dataBytes += bb.capacity()
        } catch (e: IOException) {
            stop()
            return
        }
        // WAV başlığı 32 bit uzunluk taşır: ~2 GB'ta kaydı güvenle bitir
        if (dataBytes > 2_000_000_000L) stop()
    }

    /** Kaydı bitirir; dosya adını döndürür (başlamamışsa null). */
    @Synchronized
    fun stop(): String? {
        if (!active) return null
        active = false
        try {
            out?.channel?.position(0)
            out?.write(header(rate, dataBytes))
            out?.flush()
        } catch (_: Exception) {
        }
        val name = displayName
        val u = uri
        cleanup()
        if (u != null && Build.VERSION.SDK_INT >= 29) {
            try {
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(u, cv, null, null)
            } catch (_: Exception) {
            }
        }
        return name
    }

    private fun cleanup() {
        try { out?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        out = null
        pfd = null
        uri = null
    }

    private fun header(sampleRate: Int, dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt((36 + dataLen).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1)                    // PCM
        b.putShort(2)                    // stereo
        b.putInt(sampleRate)
        b.putInt(sampleRate * 4)
        b.putShort(4)
        b.putShort(16)
        b.put("data".toByteArray())
        b.putInt(dataLen.toInt())
        return b.array()
    }
}
