package au.krishnaale.crm

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DeviceRegistration {

    private const val TAG = "DeviceRegistration"
    private const val PREF_EMAIL = "client_email"
    private const val PREF_TOKEN = "fcm_token"
    private const val BACKEND_URL = "https://us-central1-YOUR-PROJECT.cloudfunctions.net"

    fun register(context: Context, email: String) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_EMAIL, email).apply()
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            prefs.edit().putString(PREF_TOKEN, token).apply()
            sendRegistration(email, token)
        }
    }

    fun updateToken(context: Context, newToken: String) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_TOKEN, newToken).apply()
        val email = prefs.getString(PREF_EMAIL, null) ?: return
        sendRegistration(email, newToken)
    }

    private fun sendRegistration(email: String, token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(BACKEND_URL + "/registerDevice")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val body = JSONObject().put("email", email).put("token", token).put("platform", "android").toString()
                connection.outputStream.use { stream -> stream.write(body.toByteArray()) }
                val code = connection.responseCode
                Log.d(TAG, "Registration response: " + code)
                connection.disconnect()
            } catch (ex: Exception) {
                Log.e(TAG, "Registration failed: " + ex.message)
            }
        }
    }

    fun getStoredEmail(context: Context): String? {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString(PREF_EMAIL, null)
    }
}
