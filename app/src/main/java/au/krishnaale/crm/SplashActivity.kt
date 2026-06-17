package au.krishnaale.crm

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Launcher activity. Shows the branded splash theme, then requires the app lock
 * (if enabled) before forwarding to [MainActivity]. Any deep-link URL that arrived
 * with a tapped notification is passed straight through.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mark locked on a cold start so we always authenticate once.
        AppLock.lock()
        gate()
    }

    private fun gate() {
        AppLock.prompt(
            activity = this,
            onSuccess = { goToMain() },
            onFail = { finish() } // user cancelled the unlock — close the app
        )
    }

    private fun goToMain() {
        val intent = Intent(this, MainActivity::class.java)
        // Forward an optional deep link from a notification tap.
        intent.putExtra(MainActivity.EXTRA_TARGET_URL, getDeepLinkUrl())
        startActivity(intent)
        overridePendingTransition(0, 0)
        finish()
    }

    private fun getDeepLinkUrl(): String? {
        // FCM "notification" taps deliver extras on the launch intent.
        return intent?.extras?.getString(MainActivity.EXTRA_TARGET_URL)
            ?: intent?.getStringExtra("url")
    }
}
