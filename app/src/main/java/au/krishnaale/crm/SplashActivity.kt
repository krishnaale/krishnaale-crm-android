package au.krishnaale.crm

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Short delay to show splash, then check biometric lock
        Handler(Looper.getMainLooper()).postDelayed({
            checkBiometricLock()
        }, 1000)
    }

    private fun checkBiometricLock() {
        val appLock = AppLock(this)
        if (appLock.isLockEnabled() && appLock.isBiometricAvailable()) {
            showBiometricPrompt()
        } else {
            launchMain()
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    launchMain()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // If cancelled or error, finish app
                    finish()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Stay on splash - user can retry
                }
            })

        val promptInfo = AppLock(this).buildPromptInfo()
        biometricPrompt.authenticate(promptInfo)
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
