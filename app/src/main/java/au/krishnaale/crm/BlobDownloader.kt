package au.krishnaale.crm

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes a base64 payload (typically a blob: download such as an invoice PDF) to the
 * device's Downloads. Returns a content Uri that can be used to open the file.
 *
 * No storage permission required: MediaStore.Downloads on API 29+, and the app's own
 * external files dir on 26–28 (shared via FileProvider).
 */
object BlobDownloader {

    private const val MAX_BYTES = 50 * 1024 * 1024 // 50 MB guard

    fun saveBase64(
        context: Context,
        base64: String,
        mimeType: String?,
        suggestedName: String
    ): Result<Uri> {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.isEmpty()) return Result.failure(IllegalStateException("empty file"))
            if (bytes.size > MAX_BYTES) return Result.failure(IllegalStateException("file too large"))

            val mime = if (mimeType.isNullOrBlank()) "application/octet-stream" else mimeType
            val name = ensureExtension(sanitize(suggestedName), mime)

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bytes, name, mime)
            } else {
                saveToAppExternal(context, bytes, name)
            }
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(context: Context, bytes: ByteArray, name: String, mime: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val item = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(item).use { out ->
            requireNotNull(out).write(bytes)
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(item, values, null, null)
        return item
    }

    private fun saveToAppExternal(context: Context, bytes: ByteArray, name: String): Uri {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val file = uniqueFile(dir, name)
        file.outputStream().use { it.write(bytes) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base($i)$ext")
            i++
        }
        return candidate
    }

    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifBlank { "download" }
    }

    /** If the name has no extension, derive one from the MIME type. */
    private fun ensureExtension(name: String, mime: String): String {
        if (name.contains('.')) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (ext.isNullOrBlank()) name else "$name.$ext"
    }
}
