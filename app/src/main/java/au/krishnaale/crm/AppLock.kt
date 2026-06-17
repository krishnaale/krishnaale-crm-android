package au.krishnaale.crm

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Tracks whether the app currently needs to be unlocked, and presents the system
 * biometric prompt (with PIN/pattern/password fallback on Android 11+). The portal
 * handles the actual account login; this is a device-level lock on top of it.
 */
object AppLock {

    /** How long the app can sit in the background before we ask to unlock again. */
    private const val RELOCK_AFTER_MS = 60_000L

    @Volatile private var locked = true
    @Volatile private var backgroundedAt = 0L

    /**
     * Combining a device credential (PIN/pattern/password) with biometrics is only
     * supported on API 30+. On 8.0–10 we use biometrics alone with a Cancel button.
     */
    private fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    fun isLocked(): Boolean = locked
    fun lock() { locked = true }
    fun unlock() { locked = false; backgroundedAt = 0L }

    fun markBackgrounded() { backgroundedAt = SystemClock.elapsedRealtime() }

    fun shouldRelock(): Boolean {
        if (backgroundedAt == 0L) return false
        return SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS
    }

    /** True if the device can actually perform the chosen authentication. */
    fun canAuthenticate(context: Context): Boolean {
        val result = BiometricManager.from(context).canAuthenticate(allowedAuthenticators())
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Shows the unlock prompt. Calls [onSuccess] when authenticated, or [onFail]
     * if the user cancels / it is unavailable (the caller decides what to do then).
     */
    fun prompt(
        activity: AppCompatActivity,
        onSuccess: () -> Unit,
        onFail: () -> Unit
    ) {
        // If the lock is disabled, or the device can't authenticate, let them in.
        if (!SecurePrefs.isLockEnabled(activity) || !canAuthenticate(activity)) {
            unlock()
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancelled or a hard error — stay locked and let the host decide.
                    onFail()
                }
            })

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_title))
            .setSubtitle(activity.getString(R.string.lock_subtitle))
            .setAllowedAuthenticators(allowedAuthenticators())

        // A negative button is required only when DEVICE_CREDENTIAL is NOT allowed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            builder.setNegativeButtonText(activity.getString(R.string.cancel))
        }

        prompt.authenticate(builder.build())
    }
}
