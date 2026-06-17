package au.krishnaale.crm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Handles pushes sent by the backend Cloud Function.
 *
 * Expected data payload (set by the backend):
 *   title    -> notification title
 *   body     -> notification text
 *   type     -> "task" | "invoice" | "general"  (chooses the channel)
 *   url      -> optional deep link to open in the portal (e.g. an invoice page)
 *
 * Data messages are used (not "notification" messages) so the app controls the
 * channel and tap behaviour even when backgrounded.
 */
class CrmMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        DeviceRegistration.onNewToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: getString(R.string.app_name)
        val body = data["body"] ?: message.notification?.body ?: ""
        val type = data["type"] ?: "general"
        val url = data["url"]

        showNotification(this, title, body, type, url)
    }

    private fun showNotification(
        context: Context,
        title: String,
        body: String,
        type: String,
        url: String?
    ) {
        val channelId = when (type) {
            "task" -> getString(R.string.channel_tasks_id)
            "invoice" -> getString(R.string.channel_invoices_id)
            else -> getString(R.string.channel_general_id)
        }

        // Tapping routes through Splash (so the lock applies) then on to MainActivity.
        val tapIntent = Intent(context, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!url.isNullOrBlank()) putExtra(MainActivity.EXTRA_TARGET_URL, url)
        }
        val pending = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pending)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
