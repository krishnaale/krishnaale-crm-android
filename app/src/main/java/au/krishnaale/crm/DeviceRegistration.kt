package au.krishnaale.crm

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Registers {email, fcmToken} with the backend Cloud Function. The backend keeps a
 * map of email -> device tokens so that a webhook about a given client's task/invoice
 * is pushed only to that client's phone(s).
 *
 * Lightweight on purpose: a single background thread + HttpURLConnection, no extra deps.
 */
object DeviceRegistration {

    private const val TAG = "DeviceRegistration"

    /** Call after we have both an email and a token (and on token refresh). */
    fun register(context: Context) {
        val email = SecurePrefs.getEmail(context) ?: return
        val token = SecurePrefs.getLastToken(context) ?: return
        post(context, email, token)
    }

    fun onNewToken(context: Context, token: String) {
        SecurePrefs.setLastToken(context, token)
        val email = SecurePrefs.getEmail(context) ?: return
        post(context, email, token)
    }

    private fun post(context: Context, email: String, token: String) {
        val base = BuildConfig.BACKEND_URL.trimEnd('/')
        if (base.contains("REGION-PROJECT")) {
            Log.w(TAG, "BACKEND_URL not configured yet — skipping registration.")
            return
        }
        thread(name = "device-register") {
            try {
                val url = URL("$base/registerDevice")
                val body = JSONObject()
                    .put("email", email)
                    .put("token", token)
                    .put("platform", "android")
                    .toString()

                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }

                    val code = responseCode
                    if (code in 200..299) {
                        Log.i(TAG, "Device registered for $email")
                    } else {
                        Log.w(TAG, "Registration failed: HTTP $code")
                    }
                    disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Registration error: ${e.message}")
            }
        }
    }
}
