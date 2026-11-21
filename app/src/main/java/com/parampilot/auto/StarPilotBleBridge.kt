package com.parampilot.auto

import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Native side of the StarPilot Companion app's WebView fetch bridge.
 *
 * The Galaxy web UI runs in a WebView; in Bluetooth mode the page's `fetch`
 * is overridden by assets/bluetooth-fetch.js (copied from the official
 * companion app) so same-origin /api/... calls land here instead of on the
 * network. The page calls:
 *
 *   StarPilotBLE.request(id, JSON.stringify({method, path, headers, body}))
 *   StarPilotBLE.cancel(id)
 *
 * and expects the reply as a JS call:
 *
 *   window.__galaxyBleReply(id, {status, headers, body: "<base64>"})
 *   window.__galaxyBleReply(id, {error: "..."})
 *
 * Requests execute on a single-thread executor: BleBridgeClient supports
 * exactly one request in flight, and this also keeps @JavascriptInterface
 * (which calls in on a private background thread) away from blocking GATT
 * work on any UI path.
 *
 * Cancellation is best-effort by design: the bridge protocol has no GATT
 * abort, so cancel(id) only marks the id — a reply that completes anyway is
 * dropped instead of delivered. (The page also rejects the fetch promise
 * itself on abort, so a dropped reply is never missed.) The page restarts
 * its id sequence at 1 on every load, so GalaxyWebActivity clears the marks
 * on each page start via [resetCancellations].
 */
class StarPilotBleBridge(
    private val client: BleBridgeClient,
    private val deliverScript: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "starpilot-ble-bridge").apply { isDaemon = true }
    }
    private val cancelledIds: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    var isOriginAllowed: () -> Boolean = { true }

    @JavascriptInterface
    fun request(id: String, json: String) {
        if (!isOriginAllowed()) {
            val errPayload = org.json.JSONObject().put("error", "Origin not permitted to use BLE bridge")
            deliverScript("window.__galaxyBleReply(${org.json.JSONObject.quote(id)}, $errPayload)")
            return
        }
        executor.execute {
            if (cancelledIds.remove(id)) return@execute
            val payload: JSONObject = try {
                val req = JSONObject(json)
                val method = req.optString("method", "GET").ifEmpty { "GET" }
                val path = req.optString("path", "")
                require(path.isNotEmpty()) { "Missing request path" }
                val headers = mutableMapOf<String, String>()
                val headersJson = req.optJSONObject("headers")
                if (headersJson != null) {
                    val keys = headersJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        headers[key] = headersJson.optString(key, "")
                    }
                }
                val bodyBase64 = req.optString("body", "")
                val body = if (bodyBase64.isEmpty()) {
                    ByteArray(0)
                } else {
                    Base64.decode(bodyBase64, Base64.DEFAULT)
                }
                val response = client.request(method, path, body, headers)
                JSONObject().apply {
                    put("status", response.status)
                    put("headers", response.headers)
                    put("body", Base64.encodeToString(response.body, Base64.NO_WRAP))
                }
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "Bluetooth request failed")
            }
            // Cancelled while in flight: drop the reply (see class doc).
            if (cancelledIds.remove(id)) return@execute
            val script = "window.__galaxyBleReply(${JSONObject.quote(id)}, $payload)"
            try {
                deliverScript(script)
            } catch (_: Exception) {
                // WebView may already be gone; a lost reply just times out in JS.
            }
        }
    }

    @JavascriptInterface
    fun cancel(id: String) {
        cancelledIds.add(id)
    }

    /** Clears stale cancel marks; the page's id sequence restarts per load. */
    fun resetCancellations() {
        cancelledIds.clear()
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
