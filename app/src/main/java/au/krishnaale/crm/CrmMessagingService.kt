package au.krishnaale.crm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class CrmMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Re-register device with new token
        DeviceRegistration.updateToken(applicationContext, token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "Krishna Ale CRM"
        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: "You have a new notification"
        val type = remoteMessage.data["type"] ?: "general"

        showNotification(title, body, type)
    }

    private fun showNotification(title: String, body: String, type: String) {
        val channelId = when (type) {
            "task_created", "task_assigned", "task_completed", "task_updated" ->
                CrmApplication.CHANNEL_TASKS
            "invoice_created", "invoice_updated", "payment_received" ->
                CrmApplication.CHANNEL_INVOICES
            else -> CrmApplication.CHANNEL_GENERAL
        }

        val intent = Intent(this, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setLights(Color.parseColor("#90CAF9"), 300, 300)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
