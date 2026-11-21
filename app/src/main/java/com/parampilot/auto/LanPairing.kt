package com.parampilot.auto

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * When the phone reaches comma over Wi-Fi/LAN, it opens comma's two-minute
 * pairing window itself (same as tapping "Pair phone" on Comma's Bluetooth page),
 * allowing the app to automatically configure itself over Bluetooth without requiring
 * manual screen taps on Comma.
 */
object LanPairing {
    private const val TAG = "LanPairing"
    private const val ACTION_PATH = "/api/bluetooth/action"
    private const val SESSION_PATH = "/api/auth/session"
    private const val ACTION_PAYLOAD = "{\"operation\":\"accept_pair\"}"
    private const val GALAXY_STATUS_PATH = "/api/galaxy/status"

    /**
     * The comma's local origin for a user-supplied host, or null
     * when the host is empty or already a Galaxy Cloud URL (cloud
     * hosts are never pairing targets). Shared by every pairing
     * entry point (extracted 2026-11-08; the normalization used to
     * be copy-pasted into all three).
     */
    private fun originFor(commaHost: String): String? {
        val host = commaHost.trim()
        if (host.isEmpty() || host.contains("galaxy.firestar.link")) return null
        return if (host.startsWith("http://") || host.startsWith("https://")) {
            host
        } else {
            "http://$host:8082"
        }
    }

    /**
     * Queries /api/galaxy/status over local Wi-Fi to auto-grab the comma's
     * device-specific Galaxy Cloud tunnel URL (e.g. https://galaxy.firestar.link/<slug>).
     */
    fun fetchGalaxyStatusUrl(commaHost: String): String? {
        val origin = originFor(commaHost) ?: return null
        val url = URL(origin.removeSuffix("/") + GALAXY_STATUS_PATH)
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                instanceFollowRedirects = false
                val token = GalaxyCloudSession.currentLocalToken()
                if (!token.isNullOrEmpty()) {
                    setRequestProperty("Cookie", "${GalaxyCloudSession.COOKIE_NAME}=$token")
                }
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(body)
                val cloudUrl = json.optString("url", "").ifEmpty { null }
                if (cloudUrl != null && cloudUrl.contains("galaxy.firestar.link")) {
                    Log.i(TAG, "Fetched Galaxy Cloud URL from comma: $cloudUrl")
                    cloudUrl
                } else null
            } else {
                Log.d(TAG, "fetchGalaxyStatusUrl returned HTTP ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchGalaxyStatusUrl error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Attempts to open the pairing window on comma via LAN HTTP.
     * Retries if comma returns 409 busy (e.g. state transitioning).
     * Returns true if successfully opened, false otherwise.
     */
    fun openPairingWindow(commaHost: String, maxAttempts: Int = 3): Boolean {
        val origin = originFor(commaHost) ?: return false
        return postActionWithRetry(
            origin, ACTION_PAYLOAD,
            "Successfully opened pairing window on $origin",
            "openPairingWindow", maxAttempts
        )
    }

    /**
     * Powers the Comma device's Bluetooth adapter on or off via LAN HTTP.
     */
    fun setBluetoothPower(commaHost: String, enabled: Boolean, maxAttempts: Int = 3): Boolean {
        val origin = originFor(commaHost) ?: return false
        val payload = "{\"operation\":\"power\",\"enabled\":$enabled}"
        return postActionWithRetry(
            origin, payload,
            "Successfully set Bluetooth power=$enabled on $origin",
            "setBluetoothPower", maxAttempts
        )
    }

    /**
     * The one LAN action retry loop (extracted 2026-11-08 —
     * openPairingWindow and setBluetoothPower used to carry a
     * copy each): session cookie + action POST, retrying only a
     * 409 busy (with a longer pause) and transport failures.
     * Log wording unified under [failTag].
     */
    private fun postActionWithRetry(
        origin: String,
        payload: String,
        successLog: String,
        failTag: String,
        maxAttempts: Int
    ): Boolean {
        for (attempt in 1..maxAttempts) {
            try {
                val cookie = fetchSessionCookie(origin)
                    ?: GalaxyCloudSession.currentLocalToken()?.let { "${GalaxyCloudSession.COOKIE_NAME}=$it" }
                    ?: ""
                val (code, body) = postAction(origin, cookie, payload)
                if (code == 200) {
                    Log.i(TAG, successLog)
                    return true
                }
                if (code == 409 && attempt < maxAttempts) {
                    Log.d(TAG, "Comma answered 409 busy ($failTag), retrying attempt $attempt/$maxAttempts...")
                    Thread.sleep(1500)
                    continue
                }
                Log.w(TAG, "$failTag failed with code $code: $body")
                return false
            } catch (e: Exception) {
                Log.d(TAG, "Attempt $attempt failed ($failTag) connecting to $origin: ${e.message}")
                if (attempt < maxAttempts) Thread.sleep(1000)
            }
        }
        return false
    }

    private fun fetchSessionCookie(origin: String): String? {
        val url = URL(origin + SESSION_PATH)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            instanceFollowRedirects = false
            setRequestProperty("Origin", origin)
        }
        return try {
            if (conn.responseCode != 200) return null
            // Parse ONLY the token value, exactly like the main Galaxy client
            // (SgGalaxyClient.parseSessionToken). The previous code forwarded
            // the raw Set-Cookie header ("galaxy_session=<token>; HttpOnly; Path=/;
            // SameSite=Strict") as the Cookie header; StarPilot's session
            // parser rejects that malformed value, so /api/bluetooth/action
            // answered 401 "Sign in to Galaxy", the pairing window never
            // armed, and every Bluetooth key fetch failed (Param, 2026-11-03).
            val token = GalaxyCloudSession.parseSessionToken(conn.headerFields, origin)
                ?: return null
            GalaxyCloudSession.saveLocalSessionToken(token)
            "${GalaxyCloudSession.COOKIE_NAME}=$token"
        } finally {
            conn.disconnect()
        }
    }

    private fun postAction(origin: String, cookie: String, payload: String): Pair<Int, String> {
        val url = URL(origin + ACTION_PATH)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", origin)
            if (cookie.isNotEmpty()) {
                setRequestProperty("Cookie", cookie)
            }
        }
        return try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            code to body
        } finally {
            conn.disconnect()
        }
    }
}
