package au.krishnaale.crm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Small encrypted key/value store. Holds the email the client uses to log into the
 * portal (so push can be targeted to them) and whether the biometric lock is on.
 */
object SecurePrefs {

    private const val FILE = "krishna_crm_secure_prefs"
    private const val KEY_EMAIL = "notification_email"
    private const val KEY_LOCK_ENABLED = "app_lock_enabled"
    private const val KEY_LAST_TOKEN = "last_registered_token"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getEmail(context: Context): String? =
        prefs(context).getString(KEY_EMAIL, null)

    fun setEmail(context: Context, email: String) {
        prefs(context).edit().putString(KEY_EMAIL, email.trim().lowercase()).apply()
    }

    /** App lock defaults to ON (the portal carries financial info). */
    fun isLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCK_ENABLED, true)

    fun setLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
    }

    fun getLastToken(context: Context): String? =
        prefs(context).getString(KEY_LAST_TOKEN, null)

    fun setLastToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_LAST_TOKEN, token).apply()
    }
}
