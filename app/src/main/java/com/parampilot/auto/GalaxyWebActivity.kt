package com.parampilot.auto

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Hosts the comma's Galaxy web UI in a WebView — the official StarPilot
 * Companion app's transport pattern, minus its on-device asset caching:
 * the page shell loads from the comma over IP, and when Bluetooth mode is
 * active the page's /api/... traffic is bridged over BLE instead.
 *
 * Bluetooth activates when the launching intent carries [EXTRA_BLE_MODE]
 * or a pairing key is already saved (the Settings -> Galaxy Bluetooth
 * dialog) — matching the companion app, which simply tries its transports
 * and reports which one is live in the status bar. On connect,
 * assets/bluetooth-fetch.js (the official app's own script) is injected
 * so the Galaxy UI's fetch calls route through [StarPilotBleBridge].
 * If Bluetooth is unavailable the page keeps working over LAN untouched.
 *
 * Static web assets (JS/CSS/images/fonts) are disk-cached under
 * cacheDir/galaxy_web and served back through shouldInterceptRequest, so
 * repeat opens don't re-download the shell from the comma; document and
 * /api/ requests are never intercepted. The page itself still needs an IP
 * path to the comma (comma hotspot or LAN) on a cold cache even in
 * Bluetooth mode; only the API calls after load are BLE-tunneled.
 */
class GalaxyWebActivity : Activity() {

    companion object {
        private const val TAG = "GalaxyWeb"
        const val EXTRA_BLE_MODE = "galaxy_ble_mode"
        const val EXTRA_URL = "galaxy_url"
        private const val REQUEST_BLE_PERMISSIONS = 9418

        fun start(context: Context, bleMode: Boolean) {
            val intent = Intent(context, GalaxyWebActivity::class.java)
            intent.putExtra(EXTRA_BLE_MODE, bleMode)
            context.startActivity(intent)
        }

        /**
         * Opens Galaxy at one explicit address (Addendum 119's
         * per-transport buttons): [url] is loaded as-is and Bluetooth
         * bridging applies only when [bleMode] is set — an explicit
         * Local or Cloud open never silently rides the bridge.
         */
        fun start(context: Context, bleMode: Boolean, url: String) {
            val intent = Intent(context, GalaxyWebActivity::class.java)
            intent.putExtra(EXTRA_BLE_MODE, bleMode)
            intent.putExtra(EXTRA_URL, url)
            context.startActivity(intent)
        }

        /** Galaxy base URL: the discovered comma on :8082, else the hotspot gateway. */
        fun galaxyBaseUrl(): String {
            val ip = NavStateManager.commaIp?.trim().orEmpty()
            return when {
                ip.isEmpty() -> "http://172.31.254.1:8082/"
                ip.startsWith("http://") || ip.startsWith("https://") ->
                    if (ip.endsWith("/")) ip else "$ip/"
                else -> "http://$ip:8082/"
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private var bleClient: BleBridgeClient? = null
    private var bleBridge: StarPilotBleBridge? = null

    @Volatile
    private var bleActive: Boolean = false
    private var fetchJs: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFCBD5E1.toInt())
            setBackgroundColor(0xFF0B1220.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        webView = WebView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1220.toInt())
            addView(statusText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        fetchJs = try {
            assets.open("bluetooth-fetch.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "bluetooth-fetch.js asset missing: ${e.message}")
            null
        }

        val explicitUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
        val baseUrl = explicitUrl ?: galaxyBaseUrl()
        // An explicit address (Local / Cloud button) honors only the
        // caller's Bluetooth choice; the implicit open keeps the
        // historical "bridge whenever a pairing key exists" behavior.
        val bleWanted = if (explicitUrl != null) {
            intent.getBooleanExtra(EXTRA_BLE_MODE, false)
        } else {
            intent.getBooleanExtra(EXTRA_BLE_MODE, false) ||
                BleBridgeClient.hasPairingKey(this)
        }
        setStatus("Galaxy · $baseUrl" + if (bleWanted) " · Bluetooth connecting…" else "")

        // Bluetooth first, so the bridge object exists before any page load.
        if (bleWanted) {
            val missing = BleBridgeClient.missingPermissions(this)
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), REQUEST_BLE_PERMISSIONS)
                setStatus("Galaxy · $baseUrl · Bluetooth permission is needed to reach your comma over Bluetooth when Wi-Fi is not available - grant it, then reopen for Bluetooth mode")
            } else {
                startBluetooth(baseUrl)
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        bleBridge?.let { bridge ->
            bridge.isOriginAllowed = {
                val curUrl = webView.url
                if (curUrl == null) false
                else {
                    val host = android.net.Uri.parse(curUrl).host?.lowercase() ?: ""
                    host == "galaxy.firestar.link" || host == "127.0.0.1" || host == "localhost" ||
                    host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")
                }
            }
            webView.addJavascriptInterface(bridge, "StarPilotBLE")
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // The page's fetch-id sequence restarts on each load.
                bleBridge?.resetCancellations()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (bleActive) injectFetchBridge()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                return try {
                    interceptStaticAsset(request)
                } catch (e: Exception) {
                    Log.w(TAG, "static asset intercept failed: ${e.message}")
                    null
                }
            }
        }
        webView.loadUrl(baseUrl)
    }

    // ---------------------------------------------------------------------
    // Static asset disk cache: a pure accelerator for the Galaxy shell.
    // Any failure returns null and the WebView loads the request exactly
    // as it did before this cache existed.
    // ---------------------------------------------------------------------

    /**
     * Serves cacheable static assets (JS/CSS/images/fonts) from
     * cacheDir/galaxy_web, downloading and persisting them on a miss.
     * Document (HTML) requests and anything under /api/ are never
     * intercepted — API traffic keeps its normal path (including the BLE
     * bridge when Bluetooth mode is active).
     */
    private fun interceptStaticAsset(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET") return null
        if (request.isForMainFrame) return null
        val url = request.url ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        val path = url.path ?: return null
        val lowerPath = path.lowercase()
        if (lowerPath.contains("/api/")) return null
        if (lowerPath.endsWith(".html") || lowerPath.endsWith(".htm")) return null
        val extensionMime = staticAssetMime(lowerPath)
        if (extensionMime == null && !lowerPath.contains("/assets/")) return null

        val urlString = url.toString()
        val cacheFile = galaxyAssetCacheFile(urlString)
        val mimeFile = File(cacheFile.path + ".mime")

        if (cacheFile.isFile && cacheFile.length() > 0L) {
            val mime = extensionMime ?: readSidecarMime(mimeFile)
            if (mime != null) {
                return WebResourceResponse(mime, encodingForMime(mime), FileInputStream(cacheFile))
            }
            // Cached bytes without a known MIME type: fall through and
            // re-download so the type can be recorded.
        }

        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            try {
                CookieManager.getInstance().getCookie(urlString)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { conn.setRequestProperty("Cookie", it) }
            } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            if (conn.responseCode !in 200..299) return null
            val mime = extensionMime
                ?: conn.contentType?.substringBefore(";")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(bytes)
            if (extensionMime == null) {
                try { mimeFile.writeText(mime) } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            }
            return WebResourceResponse(mime, encodingForMime(mime), FileInputStream(cacheFile))
        } finally {
            conn.disconnect()
        }
    }

    /** MIME type for a static-asset path, derived from its extension. */
    private fun staticAssetMime(lowerPath: String): String? = when {
        lowerPath.endsWith(".js") -> "text/javascript"
        lowerPath.endsWith(".css") -> "text/css"
        lowerPath.endsWith(".png") -> "image/png"
        lowerPath.endsWith(".jpg") -> "image/jpeg"
        lowerPath.endsWith(".svg") -> "image/svg+xml"
        lowerPath.endsWith(".woff") -> "font/woff"
        lowerPath.endsWith(".woff2") -> "font/woff2"
        lowerPath.endsWith(".ico") -> "image/x-icon"
        else -> null
    }

    private fun encodingForMime(mime: String): String? =
        if (mime.startsWith("text/") || mime.contains("javascript") || mime == "image/svg+xml") {
            "UTF-8"
        } else {
            null
        }

    private fun readSidecarMime(mimeFile: File): String? = try {
        if (mimeFile.isFile) mimeFile.readText().trim().ifEmpty { null } else null
    } catch (_: Exception) {
        null
    }

    /** Cache file for a URL: cacheDir/galaxy_web/<sha1-hex of the full URL>. */
    private fun galaxyAssetCacheFile(urlString: String): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(urlString.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(File(cacheDir, "galaxy_web"), hex)
    }

    private fun startBluetooth(baseUrl: String) {
        val client = BleBridgeClient.getSharedClient(this)
        bleClient = client
        bleBridge = StarPilotBleBridge(client) { script ->
            webView.post {
                try {
                    webView.evaluateJavascript(script, null)
                } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        Thread {
            try {
                client.connectAndHealth()
                bleActive = true
                runOnUiThread {
                    setStatus("Galaxy · $baseUrl · Bluetooth connected (API traffic over BLE)")
                    injectFetchBridge()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth bridge unavailable: ${e.message}")
                runOnUiThread {
                    setStatus("Galaxy · $baseUrl · Bluetooth unavailable (${e.message}) — using LAN")
                    Toast.makeText(
                        this,
                        "Bluetooth bridge unavailable — Galaxy is on LAN",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    /**
     * Injects the official fetch override exactly once per page. The asset
     * itself is bundled verbatim; the install flag lives in this wrapper so
     * a second injection (page-started + page-finished, SPA navigations)
     * can never double-wrap window.fetch.
     */
    private fun injectFetchBridge() {
        val js = fetchJs ?: return
        val guarded = "if (!window.__ppaBleFetchInstalled) " +
            "{ window.__ppaBleFetchInstalled = true;\n$js\n}"
        try {
            webView.evaluateJavascript(guarded, null)
        } catch (e: Exception) {
            Log.w(TAG, "fetch bridge injection failed: ${e.message}")
        }
    }

    private fun setStatus(text: String) {
        statusText.text = text
        title = "Galaxy"
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        bleBridge?.shutdown()
        bleBridge = null
        // Shared client is maintained for the app's HUD and Android Auto
        bleClient = null
        if (this::webView.isInitialized) {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }
}
