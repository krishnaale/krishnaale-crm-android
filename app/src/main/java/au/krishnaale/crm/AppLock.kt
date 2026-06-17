package au.krishnaale.crm

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt

class AppLock(private val context: Context) {

    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun isLockEnabled(): Boolean = prefs.getBoolean("lock_enabled", true)

    fun setLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("lock_enabled", enabled).apply()
    }

    fun isBiometricAvailable(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(getAuthenticators()) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun buildPromptInfo(): BiometricPrompt.PromptInfo {
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Krishna Ale CRM")
            .setSubtitle("Verify your identity to access the app")
            .setAllowedAuthenticators(getAuthenticators())
            .build()
    }

    private fun getAuthenticators(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
    }
}
