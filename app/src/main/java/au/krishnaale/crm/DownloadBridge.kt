package au.krishnaale.crm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

/**
 * Exposed to the WebView as `AndroidDownloader`. Only used to save blob: downloads
 * (e.g. invoice PDFs the portal builds client-side) that DownloadManager can't fetch.
 * Kept deliberately minimal — it writes a user-initiated file to Downloads and nothing else.
 */
class DownloadBridge(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun processBase64Data(base64: String?, mimeType: String?, fileName: String?) {
        if (base64.isNullOrBlank()) return
        val name = if (fileName.isNullOrBlank()) "download" else fileName

        thread(name = "blob-save") {
            val result = BlobDownloader.saveBase64(appContext, base64, mimeType, name)
            main.post {
                result
                    .onSuccess { uri -> notifySaved(uri, mimeType, name) }
                    .onFailure {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.download_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        }
    }

    private fun notifySaved(uri: Uri, mimeType: String?, fileName: String) {
        Toast.makeText(appContext, appContext.getString(R.string.download_complete), Toast.LENGTH_SHORT).show()

        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: "*/*")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        val pending = PendingIntent.getActivity(
            appContext,
            System.currentTimeMillis().toInt(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, appContext.getString(R.string.channel_general_id))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.download_complete))
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
