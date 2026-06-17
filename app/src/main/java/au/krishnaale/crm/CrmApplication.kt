package au.krishnaale.crm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CrmApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val taskChannel = NotificationChannel(
                CHANNEL_TASKS,
                "Tasks & Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for task assignments and completions"
            }

            val invoiceChannel = NotificationChannel(
                CHANNEL_INVOICES,
                "Invoices & Payments",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for invoices and payment updates"
            }

            val generalChannel = NotificationChannel(
                CHANNEL_GENERAL,
                "General",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General notifications"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannels(listOf(taskChannel, invoiceChannel, generalChannel))
        }
    }

    companion object {
        const val CHANNEL_TASKS = "tasks"
        const val CHANNEL_INVOICES = "invoices"
        const val CHANNEL_GENERAL = "general"
    }
}
