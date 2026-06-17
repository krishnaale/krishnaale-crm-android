package au.krishnaale.crm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * App-wide setup:
 *  - registers notification channels (tasks / invoices / general)
 *  - watches the process lifecycle so [AppLock] knows when the app went to the
 *    background, and can require re-authentication after a short timeout.
 */
class CrmApplication : Application(), DefaultLifecycleObserver {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** Called when the whole app comes to the foreground. */
    override fun onStart(owner: LifecycleOwner) {
        // If we were backgrounded longer than the grace period, require unlock again.
        if (AppLock.shouldRelock()) {
            AppLock.lock()
        }
    }

    /** Called when the whole app goes to the background. */
    override fun onStop(owner: LifecycleOwner) {
        AppLock.markBackgrounded()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        val tasks = NotificationChannel(
            getString(R.string.channel_tasks_id),
            getString(R.string.channel_tasks_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = getString(R.string.channel_tasks_desc) }

        val invoices = NotificationChannel(
            getString(R.string.channel_invoices_id),
            getString(R.string.channel_invoices_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = getString(R.string.channel_invoices_desc) }

        val general = NotificationChannel(
            getString(R.string.channel_general_id),
            getString(R.string.channel_general_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = getString(R.string.channel_general_desc) }

        nm.createNotificationChannels(listOf(tasks, invoices, general))
    }
}
