package app.otoradyo

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.Collator

/**
 * Çalınabilir bir ses dosyası. source: depolama alanının görünen adı; kind: device | sd | usb | folder;
 * folder: dosyanın bulunduğu klasörün yolu.
 */
class Track(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val source: String,
    val key: String,
    val kind: String,
    val folder: String,
)

/**
 * Müzik dosyalarını bulur. Önce sistemin dizini (MediaStore) taranır: dahili hafıza, SD kart ve Android'in
 * dizinlediği USB bellekler buradan gelir. Dizinlenmeyen bellekler için kullanıcının seçtiği klasörler
 * (Storage Access Framework) dosya dosya gezilir. Tarama uzun sürebilir, ana iş parçacığında çağrılmaz.
 */
object MusicLibrary {
    private const val TAG = "OtoRadyo"
    private const val MAX_FILES = 50_000
    private const val MAX_DEPTH = 16
    private val EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mid", "amr", "3gp")

    fun scan(ctx: Context, folders: Collection<String>, hasPermission: Boolean): List<Track> {
        val out = LinkedHashMap<String, Track>()
        if (hasPermission) scanMediaStore(ctx, out)
        for (f in folders) {
            try {
                val tree = Uri.parse(f)
                val name = folderName(ctx, f)
                walk(ctx, tree, DocumentsContract.getTreeDocumentId(tree), name, name, 0, out)
            } catch (e: RuntimeException) {
                Log.w(TAG, "muzik klasoru okunamadi: $e")
            }
        }
        val c = Collator.getInstance()
        return out.values.sortedWith { a, b -> c.compare(a.title, b.title) }
    }

    /** Seçilen klasörün görünen adı. */
    fun folderName(ctx: Context, tree: String): String {
        val fallback = Uri.decode(tree).substringAfterLast('/').substringAfterLast(':').ifEmpty { tree }
        return try {
            val t = Uri.parse(tree)
            val doc = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
            ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }?.ifEmpty { null } ?: fallback
        } catch (e: RuntimeException) {
            fallback
        }
    }

    /** MediaStore birim adı -> (tür, görünen ad). Android SD/USB ayrımını vermez, ayrım birimin adından yapılır. */
    private fun volumeLabels(ctx: Context): Map<String, Pair<String, String>> {
        val map = HashMap<String, Pair<String, String>>()
        if (Build.VERSION.SDK_INT < 29) return map
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (v in sm.storageVolumes) {
            val name = when {
                Build.VERSION.SDK_INT >= 30 -> v.mediaStoreVolumeName
                v.isPrimary -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                else -> v.uuid?.lowercase(java.util.Locale.ROOT)
            } ?: continue
            if (v.isPrimary) continue
            val label = v.getDescription(ctx)
            map[name] = (if (label.contains("usb", ignoreCase = true)) "usb" else "sd") to label
        }
        return map
    }

    private fun scanMediaStore(ctx: Context, out: MutableMap<String, Track>) {
        val device = L10n.wrap(ctx).getString(R.string.music_src_device)
        val labels = volumeLabels(ctx)
        val volumes = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(ctx) else setOf("external")
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME, "_data",
        )
        for (vol in volumes) {
            // external_primary ile aynı dosyaları yineleyen genel "external" adı atlanır
            if (vol == "external" && volumes.size > 1) continue
            val base = MediaStore.Audio.Media.getContentUri(vol)
            val (kind, source) = labels[vol] ?: ("device" to device)
            try {
                ctx.contentResolver.query(base, cols, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)?.use { c ->
                    while (c.moveToNext() && out.size < MAX_FILES) {
                        val name = c.getString(6) ?: ""
                        val key = name + "|" + c.getLong(5)
                        if (key in out) continue
                        val artist = (c.getString(2) ?: "").takeIf { it != MediaStore.UNKNOWN_STRING } ?: ""
                        val album = (c.getString(3) ?: "").takeIf { it != MediaStore.UNKNOWN_STRING } ?: ""
                        out[key] = Track(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1) ?: File(name).nameWithoutExtension,
                            artist, album, c.getLong(4), source, key, kind, c.getString(7)?.substringBeforeLast('/', "") ?: "")
                    }
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "muzik dizini okunamadi ($vol): $e")
            }
        }
    }

    private fun walk(ctx: Context, tree: Uri, docId: String, source: String, path: String, depth: Int, out: MutableMap<String, Track>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        val dirs = ArrayList<Pair<String, String>>()
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < MAX_FILES) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { dirs.add(id to name); continue }
                if (name.startsWith(".")) continue
                if (!mime.startsWith("audio/") && name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) !in EXT) continue
                val key = name + "|" + c.getLong(3)
                if (key in out) continue
                out[key] = Track(DocumentsContract.buildDocumentUriUsingTree(tree, id), name.substringBeforeLast('.'), "", "", 0L, source, key, "folder", path)
            }
        }
        for ((id, name) in dirs) walk(ctx, tree, id, source, "$path/$name", depth + 1, out)
    }
}
