package dev.nostrobe

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object LanServerConfig {

    private const val PREFS_NAME = "nostrobe"
    const val KEY_URL = "post_url"
    const val KEY_TOKEN = "post_token"

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServerUrl(context: Context): String =
        getPrefs(context).getString(KEY_URL, "")?.trim() ?: ""

    fun getServerToken(context: Context): String =
        getPrefs(context).getString(KEY_TOKEN, "")?.trim() ?: ""

    /**
     * Test connection to the LAN server asynchronously.
     * Tries a quick HTTP connection to /health or /fix.
     */
    fun testConnection(
        baseUrl: String,
        token: String,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        thread {
            var urlStr = baseUrl.trim().trimEnd('/')
            if (!urlStr.contains("://")) {
                urlStr = "http://$urlStr"
            }
            try {
                // First try GET /health, fall back to /fix
                val testUrl = URL("$urlStr/health")
                val conn = testUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                if (token.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }

                var code = try {
                    conn.responseCode
                } catch (_: Exception) {
                    -1
                } finally {
                    conn.disconnect()
                }

                if (code == -1 || code == 404) {
                    // Try connecting to /fix with OPTIONS or HEAD
                    val fixUrl = URL("$urlStr/fix")
                    val fixConn = fixUrl.openConnection() as HttpURLConnection
                    fixConn.connectTimeout = 3000
                    fixConn.readTimeout = 3000
                    fixConn.requestMethod = "HEAD"
                    if (token.isNotEmpty()) {
                        fixConn.setRequestProperty("Authorization", "Bearer $token")
                    }
                    code = try {
                        fixConn.responseCode
                    } finally {
                        fixConn.disconnect()
                    }
                }

                if (code in 200..499 && code != 404) {
                    onResult(true, "Connecté avec succès (HTTP $code)")
                } else if (code == 404) {
                    onResult(true, "Hôte joignable (HTTP 404 sur endpoint de test)")
                } else {
                    onResult(false, "Code de réponse inattendu: $code")
                }
            } catch (e: Exception) {
                val msg = when (e) {
                    is java.net.ConnectException -> "Connexion refusée : vérifiez que no-strobe-post tourne sur le port"
                    is java.net.SocketTimeoutException -> "Délai dépassé : l'hôte ne répond pas"
                    is java.net.UnknownHostException -> "Hôte inconnu : vérifiez l'adresse IP"
                    else -> "Erreur : ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
                onResult(false, msg)
            }
        }
    }

    /**
     * Guess a default LAN server URL based on the device's current Wi-Fi subnet.
     */
    fun suggestDefaultUrl(context: Context): String {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifi?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                @Suppress("DEPRECATION")
                val ipStr = Formatter.formatIpAddress(ipInt)
                val parts = ipStr.split(".")
                if (parts.size == 4) {
                    return "http://${parts[0]}.${parts[1]}.${parts[2]}.1:8765"
                }
            }
        } catch (_: Exception) {}
        return "http://192.168.1.20:8765"
    }

    /**
     * Shows the LAN Server settings and connection test dialog.
     */
    fun showDialog(
        activity: Activity,
        onSaved: ((url: String, token: String) -> Unit)? = null
    ) {
        val dp = activity.resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val prefs = getPrefs(activity)

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        val desc = TextView(activity).apply {
            text = "Connectez-vous au serveur no-strobe de votre PC / serveur local pour déporter le calcul lourd ou économiser le stockage du téléphone.\n\nCommande sur PC :\nno-strobe-post serve --listen 0.0.0.0:8765 --token ..."
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        layout.addView(desc)

        val savedUrl = getServerUrl(activity)
        val initialUrl = if (savedUrl.isNotBlank()) savedUrl else suggestDefaultUrl(activity)

        val urlInput = EditText(activity).apply {
            hint = "http://192.168.1.20:8765"
            setText(initialUrl)
            setSingleLine()
        }
        layout.addView(urlInput)

        val tokenInput = EditText(activity).apply {
            hint = "Token optionnel (NO_STROBE_TOKEN)"
            setText(getServerToken(activity))
            setSingleLine()
        }
        layout.addView(tokenInput)

        val statusText = TextView(activity).apply {
            textSize = 12f
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            visibility = View.GONE
        }

        val testBtn = Button(activity).apply {
            text = "Tester la connexion"
            setBackgroundResource(R.drawable.bg_tool)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                val urlToTest = urlInput.text.toString().trim()
                if (urlToTest.isBlank()) {
                    statusText.visibility = View.VISIBLE
                    statusText.setTextColor(Color.RED)
                    statusText.text = "Veuillez entrer une adresse URL valide."
                    return@setOnClickListener
                }
                statusText.visibility = View.VISIBLE
                statusText.setTextColor(Color.YELLOW)
                statusText.text = "Test en cours..."
                isEnabled = false

                testConnection(urlToTest, tokenInput.text.toString().trim()) { success, msg ->
                    activity.runOnUiThread {
                        isEnabled = true
                        statusText.visibility = View.VISIBLE
                        if (success) {
                            statusText.setTextColor(0xFF4CAF50.toInt()) // Green
                            statusText.text = "✓ $msg"
                        } else {
                            statusText.setTextColor(0xFFE53935.toInt()) // Red
                            statusText.text = "✗ $msg"
                        }
                    }
                }
            }
        }
        layout.addView(testBtn)
        layout.addView(statusText)

        AlertDialog.Builder(activity)
            .setTitle("Serveur LAN (Déport & Stockage)")
            .setView(layout)
            .setPositiveButton("Enregistrer") { _, _ ->
                val newUrl = urlInput.text.toString().trim()
                val newToken = tokenInput.text.toString().trim()
                prefs.edit().putString(KEY_URL, newUrl).putString(KEY_TOKEN, newToken).apply()
                onSaved?.invoke(newUrl, newToken)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
