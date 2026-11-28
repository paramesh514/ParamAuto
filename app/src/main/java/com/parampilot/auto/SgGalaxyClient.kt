package com.parampilot.auto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for SecretGoodStarPilot's typed Galaxy APIs.
 *
 * Replaces the legacy GET /api/params/all firehose, which no longer exists on
 * current StarPilot builds. Direct local connections (LAN / comma hotspot /
 * BLE bridge) are granted a session automatically by Galaxy, so no auth
 * headers are needed there. Galaxy Cloud (galaxy.firestar.link) answers 401
 * until the client signs in: when the base URL is the cloud host, requests
 * carry the session cookie managed by [GalaxyCloudSession], which signs in
 * with the saved Galaxy password.
 *
 * Endpoint shapes follow ~/workspace/sg_galaxy/TYPED_API_MAP.md exactly.
 */
class SgGalaxyClient(baseUrl: String) {

    val baseUrl: String = baseUrl.removeSuffix("/")

    /** True when this client talks to Galaxy Cloud (session cookie required). */
    private val isCloudBase: Boolean = baseUrl.contains(CLOUD_HOST)
    private val timeoutMs: Int get() = if (isCloudBase) TIMEOUT_CLOUD_MS else TIMEOUT_LOCAL_MS

    /**
     * The transport the most recent successful request was actually
     * served over (BLE bridge, or this client's HTTP base). Recorded
     * only: identifying the app's transport is the heartbeat's job
     * (see [HeartbeatTransport]) — this client never sets it, and it
     * chooses its channel from the heartbeat-identified transport.
     */
    @Volatile
    var lastServedTransport: NavStateManager.TransportType? = null
        private set

    /** Navigation status snapshot plus the routeKey echoed by the server. */
    data class NavStatusResponse(
        val json: JSONObject,
        val routeKey: String?
    )

    /**
     * Probes the typed navigation status API (GET /api/navigation/status).
     * Returns the parsed status snapshot on HTTP 200, or null when the
     * endpoint does not serve the SG typed APIs. Callers should reuse the
     * returned snapshot as their first nav status instead of refetching:
     * the probe already paid for the full GET.
     */
    fun probe(): JSONObject? {
        val (code, body) = getRaw(PATH_NAV_STATUS)
        if (code != 200 || body == null) return null
        return try { JSONObject(body) } catch (_: Exception) { null }
    }

    /**
     * GET /api/navigation/status. When [routeKey] is provided and the route has
     * not changed, the server omits route geometry and flags routeUnchanged.
     */
    fun fetchNavStatus(routeKey: String?): NavStatusResponse? {
        val path = if (routeKey.isNullOrEmpty()) {
            PATH_NAV_STATUS
        } else {
            "$PATH_NAV_STATUS?routeKey=${URLEncoder.encode(routeKey, "UTF-8")}"
        }
        val json = getJson(path) ?: return null
        val key = json.optString("routeKey", "").ifEmpty { null }
        return NavStatusResponse(json, key)
    }

    /** GET /api/plots/live ΓÇö real vehicle speed + controls state. */
    fun fetchPlots(): JSONObject? = getJson(PATH_PLOTS_LIVE)

    /**
     * The transport the most recent HEARTBEAT (device-state fetch) was
     * served over, or null when that fetch failed and identified
     * nothing. Only the heartbeat's own fetch writes this — see
     * [HeartbeatTransport].
     */
    @Volatile
    var lastHeartbeatTransport: NavStateManager.TransportType? = null
        private set

    /** GET /api/device/state ΓÇö replaces the legacy /api/device/status. */
    fun fetchDeviceState(): JSONObject? {
        lastHeartbeatTransport = null
        val json = getJson(PATH_DEVICE_STATE)
        if (json != null) {
            lastHeartbeatTransport = lastServedTransport
        }
        return json
    }

    /** GET /api/system/monitor — batteryVoltage, cpuPercent, uptime, memory, storage. */
    fun fetchSystemMonitor(): JSONObject? {
        val json = getJson(PATH_SYSTEM_MONITOR) ?: return null
        UsageCounters.count(UsageCounters.SYSTEM_MONITOR)
        return json
    }

    /**
     * GET /api/settings/pages/slc ΓåÆ Offset1..7 (mph) as a 7-element array,
     * or null when the page cannot be parsed. Never guesses.
     */
    fun fetchSlcOffsets(): DoubleArray? {
        val json = getJson(PATH_SETTINGS_SLC) ?: return null
        return parseSlcOffsets(json)
    }

    /**
     * GET /api/settings/pages/slc → Dom's seven offset rows as
     * (device range label, offset) pairs — see [parseSlcOffsetRows].
     * Null under exactly the conditions that make [fetchSlcOffsets] null.
     */
    fun fetchSlcOffsetRows(): List<Pair<String, Double>>? {
        val json = getJson(PATH_SETTINGS_SLC) ?: return null
        return parseSlcOffsetRows(json)
    }

    /**
     * GET /api/recordings/detail?routeId=<id> — the route's full detail
     * record (including per-camera videoBytes; see [RecordingDetail]).
     * Mirrors [fetchRecordingsLocal]'s handling: returns the parsed body
     * only on a 2xx answer, null on any failure (the endpoint is
     * local-only, so through the tunnel this is simply unavailable).
     */
    fun fetchRecordingDetail(routeId: String): JSONObject? {
        val path = PATH_RECORDINGS_DETAIL + "?routeId=" +
            URLEncoder.encode(routeId, "UTF-8")
        val (code, body) = getRaw(path)
        if (code !in 200..299 || body == null) return null
        return try { JSONObject(body) } catch (_: Exception) { null }
    }

    /**
     * POST /api/navigation/action. Returns (httpCode, responseJson?).
     * 200 carries a fresh status snapshot; 409 means the revision is stale.
     * On Cloud, a 401 triggers one re-login and one retry (see [getRaw]).
     */
    fun postNavAction(body: JSONObject): Pair<Int, JSONObject?> = postJson(PATH_NAV_ACTION, body)

    /** GET /api/galaxy/hotspot — the hotspot snapshot (see [HotspotState]). */
    fun fetchHotspot(): JSONObject? = getJson(PATH_GALAXY_HOTSPOT)

    /**
     * POST /api/galaxy/hotspot with the exact {revision, config} body
     * [HotspotState.togglePayload] builds. 200 returns the new snapshot;
     * 409 means stale revision or not parked — the caller surfaces the
     * server's error text and never assumes the flip landed.
     */
    fun postHotspot(body: JSONObject): Pair<Int, JSONObject?> =
        postJson(PATH_GALAXY_HOTSPOT, body)

    /** GET /api/galaxy/networks — the Wi-Fi manager snapshot
     * (see [GalaxyNetworks]). */
    fun fetchNetworks(): JSONObject? = getJson(PATH_GALAXY_NETWORKS)

    /**
     * GET /api/settings/pages/<page> with the HTTP code preserved
     * (see [GalaxySettings]): settings need a local/Bluetooth Galaxy
     * session, so a Cloud read answers 401 {"error": "Sign in to
     * Galaxy"} — the caller shows that text instead of a flat null.
     * Returns (httpCode, parsedJson?).
     */
    fun fetchSettingsPage(pageId: String): Pair<Int, JSONObject?> {
        val (code, body) = getRaw(PATH_SETTINGS_PAGES + pageId)
        val json = body?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        }
        return code to json
    }

    /** POST /api/settings/preview — phase one of a settings edit. */
    fun postSettingsPreview(body: JSONObject): Pair<Int, JSONObject?> =
        postJson(PATH_SETTINGS_PREVIEW, body)

    /** POST /api/settings/confirm — phase two; 200 carries the fresh page. */
    fun postSettingsConfirm(body: JSONObject): Pair<Int, JSONObject?> =
        postJson(PATH_SETTINGS_CONFIRM, body)

    /** POST /api/settings/reset-default — starts a reset-to-default. */
    fun postSettingsResetDefault(body: JSONObject): Pair<Int, JSONObject?> =
        postJson(PATH_SETTINGS_RESET_DEFAULT, body)

    /**
     * POST /api/galaxy/networks with one exact action body built by
     * [GalaxyNetworks]. Scan/join/connect run nmcli on the comma and
     * can take tens of seconds (the Galaxy web page allows 60 s), so
     * this POST carries a 60 s timeout instead of the usual 3 s.
     * 200 returns the fresh snapshot; errors carry the server's text.
     */
    fun postNetworks(body: JSONObject): Pair<Int, JSONObject?> =
        postJson(PATH_GALAXY_NETWORKS, body, timeoutOverrideMs = TIMEOUT_NETWORKS_MS)

    /**
     * GET /api/recordings/local with the HTTP code preserved: the
     * endpoint is LOCAL-ONLY and answers 403 {"code": "local_only"}
     * through the Galaxy tunnel, which getJson would flatten to null.
     * Returns (httpCode, parsedJson?).
     */
    fun fetchRecordingsLocal(): Pair<Int, JSONObject?> {
        val (code, body) = getRaw(PATH_RECORDINGS_LOCAL)
        val json = body?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        return code to json
    }

    /**
     * POST /api/recordings/action (see [RecordingEntry.deletePayload]).
     * 200 carries {"saved": true}; failures carry the server's error.
     */
    fun postRecordingsAction(body: JSONObject): Pair<Int, JSONObject?> =
        postJson(PATH_RECORDINGS_ACTION, body)

    /** The absolute URL a media player loads for [path] on this base. */
    fun mediaUrl(path: String): String = baseUrl + path

    /**
     * The Cookie header value a media player must send for this
     * client's Galaxy session ("galaxy_session=…"), refreshing the
     * local session first when none is held. Call on a background
     * thread (the refresh is a network call). Null when no session
     * could be established — local media may still answer, since
     * direct local connections are granted a session by Galaxy.
     */
    fun mediaCookieHeader(): String? {
        if (isCloudBase) {
            if (GalaxyCloudSession.currentToken() == null &&
                GalaxyCloudSession.hasPassword()
            ) {
                GalaxyCloudSession.login(baseUrl)
            }
            return GalaxyCloudSession.currentToken()
                ?.let { "${GalaxyCloudSession.COOKIE_NAME}=$it" }
        }
        if (GalaxyCloudSession.currentLocalToken() == null) {
            GalaxyCloudSession.refreshLocalSession(baseUrl)
        }
        return GalaxyCloudSession.currentLocalToken()
            ?.let { "${GalaxyCloudSession.COOKIE_NAME}=$it" }
    }

    /**
     * Streams a media GET (a recording video — see [RecordingMedia])
     * into [dest] with progress callbacks, over plain HTTP with this
     * client's session cookie. Deliberately NOT routed through the
     * BLE bridge: videos are far too large for it. Returns
     * (httpCode, serverError?): 200 + null on success; -2 + "Cancelled"
     * when [isCancelled] trips; -1 + the failure message when the
     * connection itself failed. A first request can take a while —
     * the comma prepares the route video before streaming it.
     */
    fun downloadMedia(
        path: String,
        dest: java.io.OutputStream,
        isCancelled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Pair<Int, String?> {
        if (isCloudBase && GalaxyCloudSession.currentToken() == null &&
            GalaxyCloudSession.hasPassword()
        ) {
            GalaxyCloudSession.login(baseUrl)
        } else if (!isCloudBase &&
            GalaxyCloudSession.currentLocalToken() == null
        ) {
            GalaxyCloudSession.refreshLocalSession(baseUrl)
        } else if (!isCloudBase) {
            GalaxyCloudSession.ensurePaired(baseUrl)
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl + path).openConnection()
                as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                useCaches = false
                setRequestProperty("Accept", "video/mp4")
                applySessionCookie(this)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errText = conn.errorStream?.bufferedReader()
                    ?.use { it.readText() }
                val msg = errText?.let {
                    try {
                        JSONObject(it).optString("error", "")
                    } catch (_: Exception) {
                        ""
                    }
                }?.ifEmpty { null }
                ConnectionJournal.record(
                    ConnectionJournal.CAT_HTTP,
                    "GET $path (download) -> HTTP $code"
                )
                code to (msg ?: "HTTP $code")
            } else {
                val total = conn.contentLengthLong
                var downloaded = 0L
                var lastReport = 0L
                var cancelled = false
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) {
                            cancelled = true
                            break
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        dest.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastReport >= 262_144) {
                            lastReport = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (cancelled) {
                    -2 to "Cancelled"
                } else {
                    onProgress(downloaded, total)
                    200 to null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download $path error: ${e.message}")
            ConnectionJournal.record(
                ConnectionJournal.CAT_HTTP,
                "GET $path (download) -> no answer"
            )
            -1 to (e.message ?: "Download failed")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * POST /api/cameras/snapshot — one parked snapshot as raw JPEG
     * bytes (Comma cameras, Day 6 2026-11-05; contract in
     * [CommaCameras]). Returns (httpCode, jpegBytes?, serverError?):
     * on 2xx the bytes are the JPEG; on failure the server's own
     * error text rides along for CommaCameras.errorMessage. The read
     * timeout is 30 s because a capture can take ~12 s (24 s cabin).
     * HTTP only — a ~1 MB frame does not ride the BLE bridge.
     */
    fun fetchCameraSnapshot(cameraId: String): Triple<Int, ByteArray?, String?> {
        val body = CommaCameras.requestBody(cameraId)
        val result = withReloginRetry { doHttpPostBytes(PATH_CAMERAS_SNAPSHOT, body, 30_000) }
        val (code, bytes) = result
        if (code in 200..299) {
            lastServedTransport = HeartbeatTransport.httpTransport(isCloudBase)
            return Triple(code, bytes, null)
        }
        val err = bytes?.let {
            try {
                JSONObject(it.toString(Charsets.UTF_8)).optString("error", "").ifEmpty { null }
            } catch (_: Exception) {
                null
            }
        }
        return Triple(code, null, err)
    }

    /**
     * General POST method supporting BLE bridge, local/cloud session cookies,
     * and automatic 401 re-authentication retry.
     */
    fun postJson(
        path: String,
        body: JSONObject,
        timeoutOverrideMs: Int? = null
    ): Pair<Int, JSONObject?> {
        // Channel follows the heartbeat-identified transport only
        // (HeartbeatTransport): BLE when the heartbeat identified
        // Bluetooth, this client's HTTP base otherwise.
        val ble = BleBridgeClient.getConnectedClient()
        if (ble != null && !isCloudBase &&
            HeartbeatTransport.useBle(NavStateManager.activeTransport, NavStateManager.connectionMode)
        ) {
            try {
                val resp = ble.request(
                    method = "POST",
                    path = path,
                    body = body.toString().toByteArray(Charsets.UTF_8),
                    headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                    timeoutMs = timeoutOverrideMs?.toLong() ?: 15000L
                )
                val json = resp.bodyJson()
                if (resp.status in 200..299) {
                    lastServedTransport = NavStateManager.TransportType.BLUETOOTH
                }
                Log.i(TAG, "POST $path via BLE status=${resp.status}")
                if (resp.status !in 200..299) {
                    ConnectionJournal.record(
                        ConnectionJournal.CAT_HTTP,
                        "POST $path via BLE -> HTTP ${resp.status}" +
                            journalReasonSuffix(path, json)
                    )
                }
                return Pair(resp.status, json)
            } catch (e: Exception) {
                // A BLE timeout does not prove the POST never ran:
                // the bridge may have executed it and only the
                // response was lost (bug fix, 2026-11-09). Every
                // postJson caller is state-changing (nav actions,
                // settings confirm, hotspot, models, updates,
                // recordings), so re-sending over HTTP could execute
                // the action twice. If the comma still answers, the
                // outcome is ambiguous — report it instead of
                // re-sending. Only a comma that no longer answers at
                // all provably never executed the POST.
                if (bestEffortExecConfirmed(baseUrl)) {
                    Log.w(TAG, "POST $path via BLE failed: ${e.message}; comma still reachable, NOT re-sending over HTTP")
                    ConnectionJournal.record(
                        ConnectionJournal.CAT_HTTP,
                        "POST $path via BLE -> outcome unknown " +
                            "(comma still reachable; not re-sent)"
                    )
                    return Pair(CODE_AMBIGUOUS, null)
                }
                Log.w(TAG, "POST $path via BLE failed: ${e.message}, falling back to network")
            }
        }
        var result = withReloginRetry { doHttpPost(path, body, timeoutOverrideMs) }
        if (result.first in 200..299) {
            lastServedTransport = HeartbeatTransport.httpTransport(isCloudBase)
        } else if (result.first != -1 && !isCloudBase && NavStateManager.connectionMode == CommaClient.MODE_AUTO) {
            // result -1 = the HTTP POST never completed, so the
            // comma's execution state is unknown — retrying the same
            // state-changing POST over BLE could execute it twice
            // (bug fix, 2026-11-09, same class as the BLE-first
            // gate above). A completed HTTP error (4xx/5xx) is a
            // definitive answer and may still fall back.
            val fallbackBle = BleBridgeClient.getConnectedClient()
            if (fallbackBle != null) {
                try {
                    val resp = fallbackBle.request(
                        method = "POST",
                        path = path,
                        body = body.toString().toByteArray(Charsets.UTF_8),
                        headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                        timeoutMs = 5000
                    )
                    val json = resp.bodyJson()
                    if (resp.status in 200..299) {
                        lastServedTransport = NavStateManager.TransportType.BLUETOOTH
                        // No identity flip here (audit finding 19):
                        // the heartbeat adopts lastServedTransport on
                        // its next device-state fetch — it is the ONE
                        // writer of activeTransport (Param, 2026-11-04).
                    }
                    if (resp.status !in 200..299) {
                        ConnectionJournal.record(
                            ConnectionJournal.CAT_HTTP,
                            "POST $path via BLE -> HTTP ${resp.status}" +
                                journalReasonSuffix(path, json)
                        )
                    }
                    return Pair(resp.status, json)
                } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        if (result.first !in 200..299) {
            ConnectionJournal.record(
                ConnectionJournal.CAT_HTTP,
                "POST $path -> ${describeCode(result.first)}" +
                    journalReasonSuffix(path, result.second)
            )
        }
        return result
    }

    private fun doHttpPost(
        path: String,
        body: JSONObject,
        timeoutOverrideMs: Int? = null
    ): Pair<Int, JSONObject?> {
        if (isCloudBase && GalaxyCloudSession.currentToken() == null && GalaxyCloudSession.hasPassword()) {
            GalaxyCloudSession.login(baseUrl)
        } else if (!isCloudBase && GalaxyCloudSession.currentLocalToken() == null) {
            GalaxyCloudSession.refreshLocalSession(baseUrl)
        } else if (!isCloudBase) {
            GalaxyCloudSession.ensurePaired(baseUrl)
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutOverrideMs ?: timeoutMs
                readTimeout = timeoutOverrideMs ?: timeoutMs
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Origin", originFor(baseUrl))
                setRequestProperty("Referer", "$baseUrl/")
                applySessionCookie(this)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            val json = text?.let { try { JSONObject(it) } catch (_: Exception) { null } }
            code to json
        } catch (e: Exception) {
            Log.w(TAG, "POST $path error: ${e.message}")
            -1 to null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * [doHttpPost] with a raw-bytes response and a caller-chosen read
     * timeout — for the camera snapshot's JPEG. Same session bootstrap
     * and cookie handling; error bodies come back as bytes too, so the
     * caller can read the server's JSON error.
     */
    private fun doHttpPostBytes(
        path: String,
        body: JSONObject,
        readTimeoutOverrideMs: Int
    ): Pair<Int, ByteArray?> {
        if (isCloudBase && GalaxyCloudSession.currentToken() == null && GalaxyCloudSession.hasPassword()) {
            GalaxyCloudSession.login(baseUrl)
        } else if (!isCloudBase && GalaxyCloudSession.currentLocalToken() == null) {
            GalaxyCloudSession.refreshLocalSession(baseUrl)
        } else if (!isCloudBase) {
            GalaxyCloudSession.ensurePaired(baseUrl)
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = readTimeoutOverrideMs
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "image/jpeg, application/json")
                setRequestProperty("Origin", originFor(baseUrl))
                setRequestProperty("Referer", "$baseUrl/")
                applySessionCookie(this)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() }
            code to bytes
        } catch (e: Exception) {
            Log.w(TAG, "POST $path (bytes) error: ${e.message}")
            -1 to null
        } finally {
            conn?.disconnect()
        }
    }

    fun getJson(path: String): JSONObject? {
        val (code, body) = getRaw(path)
        if (code != 200 || body == null) return null
        return try { JSONObject(body) } catch (_: Exception) { null }
    }

    /**
     * The one 401 recovery (audit finding 16): Cloud sessions are
     * short-lived (~30 min) — on a 401, sign in once with the saved
     * password and retry the request exactly once. Every transport
     * method in this client routes through here instead of carrying
     * its own copy of the dance.
     */
    private fun <T> withReloginRetry(call: () -> Pair<Int, T>): Pair<Int, T> {
        var result = call()
        if (result.first == 401) {
            if (GalaxyCloudSession.relogin(baseUrl, isCloudBase)) {
                ConnectionJournal.record(
                    ConnectionJournal.CAT_CLOUD,
                    "session expired (HTTP 401); re-login succeeded, request retried"
                )
                result = call()
            } else {
                ConnectionJournal.record(
                    ConnectionJournal.CAT_CLOUD,
                    "session expired (HTTP 401); re-login not possible"
                )
            }
        }
        return result
    }

    /** Journal phrasing for a transport result code. */
    private fun describeCode(code: Int): String = when (code) {
        -1 -> "no answer"
        CODE_AMBIGUOUS -> "outcome unknown (no answer; may have executed)"
        else -> "HTTP $code"
    }

    /**
     * Addendum 160: for software actions the journal line also carries
     * the comma's own refusal reason (the error body) — a bare
     * "HTTP 409" hid for days WHY the updater kept refusing (busy,
     * reboot pending, not ready…). Other paths keep their terse form.
     */
    private fun journalReasonSuffix(path: String, body: JSONObject?): String {
        if (path != "/api/software/action") return ""
        val error = body?.optString("error", "").orEmpty()
        return if (error.isEmpty()) "" else ": $error"
    }

    private fun getRaw(path: String): Pair<Int, String?> {
        val result = withReloginRetry { doGetRaw(path) }
        // The device-state GET is the heartbeat — it keeps its own
        // lost/recovered journal lines, so it is excluded here.
        if (result.first != 200 && path != PATH_DEVICE_STATE) {
            ConnectionJournal.record(
                ConnectionJournal.CAT_HTTP,
                "GET $path -> ${describeCode(result.first)}"
            )
        }
        return result
    }

    private fun doGetRaw(path: String): Pair<Int, String?> {
        // Channel follows the heartbeat-identified transport only
        // (HeartbeatTransport): BLE when the heartbeat identified
        // Bluetooth, this client's HTTP base otherwise.
        val ble = BleBridgeClient.getConnectedClient()
        if (ble != null && !isCloudBase &&
            HeartbeatTransport.useBle(NavStateManager.activeTransport, NavStateManager.connectionMode)
        ) {
            try {
                val resp = ble.request(
                    method = "GET",
                    path = path,
                    headers = mapOf("Accept" to "application/json"),
                    timeoutMs = 10000
                )
                if (resp.status in 200..299) {
                    lastServedTransport = NavStateManager.TransportType.BLUETOOTH
                    return Pair(resp.status, resp.bodyText())
                }
            } catch (e: Exception) {
                // fall through to network
            }
        }
        if (isCloudBase && GalaxyCloudSession.currentToken() == null && GalaxyCloudSession.hasPassword()) {
            GalaxyCloudSession.login(baseUrl)
        } else if (!isCloudBase && GalaxyCloudSession.currentLocalToken() == null) {
            GalaxyCloudSession.refreshLocalSession(baseUrl)
        } else if (!isCloudBase) {
            GalaxyCloudSession.ensurePaired(baseUrl)
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                setRequestProperty("Accept", "application/json")
                applySessionCookie(this)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            if (code in 200..299) {
                // Record only — the heartbeat identifies the transport.
                lastServedTransport = HeartbeatTransport.httpTransport(isCloudBase)
            }
            code to body
        } catch (e: Exception) {
            Log.w(TAG, "GET $path error: ${e.message}")
            if (!isCloudBase && NavStateManager.connectionMode == CommaClient.MODE_AUTO) {
                val fallbackBle = BleBridgeClient.getConnectedClient()
                if (fallbackBle != null) {
                    try {
                        val resp = fallbackBle.request(
                            method = "GET",
                            path = path,
                            headers = mapOf("Accept" to "application/json"),
                            timeoutMs = 4000
                        )
                        if (resp.status in 200..299) {
                            lastServedTransport = NavStateManager.TransportType.BLUETOOTH
                            // No identity flip here (audit finding 19):
                            // the heartbeat adopts lastServedTransport.
                            return Pair(resp.status, resp.bodyText())
                        }
                    } catch (be: Exception) {
                        Log.w(TAG, "Fallback GET $path via BLE failed: ${be.message}")
                    }
                }
            }
            -1 to null
        } finally {
            conn?.disconnect()
        }
    }

    /** Attaches the Galaxy session cookie (cloud token on cloud host, local token on LAN/hotspot/BLE). */
    private fun applySessionCookie(conn: HttpURLConnection) {
        val token = if (isCloudBase) {
            GalaxyCloudSession.currentToken()
        } else {
            GalaxyCloudSession.currentLocalToken()
        } ?: return
        conn.setRequestProperty("Cookie", "${GalaxyCloudSession.COOKIE_NAME}=$token")
    }

    companion object {
        private const val TAG = "SgGalaxyClient"

        /**
         * Returned when a POST's execution state is unknowable
         * (bug fix, 2026-11-09): the transport failed after the
         * request may already have reached the comma, and the comma
         * still answers — so the action was NOT re-sent on another
         * transport. Callers surface this as an error, never as
         * success.
         */
        const val CODE_AMBIGUOUS = -3

        /**
         * Best-effort execution probe: true when the comma at
         * [target] (a base URL, a host, or a host:port) still
         * answers its device-state endpoint — meaning a POST sent
         * moments ago may well have executed. False only when
         * nothing answers, i.e. the POST provably never landed.
         * A 401 still counts as an answer.
         */
        fun bestEffortExecConfirmed(target: String): Boolean {
            val base = baseUrlFor(target)
            return try {
                SgGalaxyClient(base).fetchDeviceState() != null
            } catch (e: Exception) {
                // fetchDeviceState already swallows transport
                // errors; anything escaping it counts as "no answer".
                false
            }
        }
        private const val TIMEOUT_LOCAL_MS = 3000
        private const val TIMEOUT_CLOUD_MS = 8000
        private const val TIMEOUT_NETWORKS_MS = 60000
        private const val TIMEOUT_MS = 2500
        const val CLOUD_HOST = "galaxy.firestar.link"
        private const val PATH_NAV_STATUS = "/api/navigation/status"
        private const val PATH_NAV_ACTION = "/api/navigation/action"
        private const val PATH_CAMERAS_SNAPSHOT = "/api/cameras/snapshot"
        private const val PATH_PLOTS_LIVE = "/api/plots/live"
        private const val PATH_DEVICE_STATE = "/api/device/state"
        private const val PATH_SYSTEM_MONITOR = "/api/system/monitor"
        private const val PATH_SETTINGS_SLC = "/api/settings/pages/slc"
        const val PATH_MODELS_MANAGER = "/api/models/manager"
        const val PATH_MODELS_STATUS = "/api/models/status"
        const val PATH_MODELS_ACTIVE = "/api/models/active"
        const val PATH_SOUNDS = "/api/sounds"
        const val PATH_SOUNDS_DOWNLOAD = "/api/sounds/download"
        const val PATH_FLM = "/api/flm/training-results"
        const val PATH_FAVORITES_SLOTS = "/api/favorites/slots"
        const val PATH_NAV_SEARCH = "/api/navigation/search"
        const val PATH_GALAXY_HOTSPOT = "/api/galaxy/hotspot"
        const val PATH_GALAXY_NETWORKS = "/api/galaxy/networks"
        const val PATH_SETTINGS_PAGES = "/api/settings/pages/"
        const val PATH_SETTINGS_PREVIEW = "/api/settings/preview"
        const val PATH_SETTINGS_CONFIRM = "/api/settings/confirm"
        const val PATH_SETTINGS_RESET_DEFAULT = "/api/settings/reset-default"
        const val PATH_RECORDINGS_LOCAL = "/api/recordings/local"
        const val PATH_RECORDINGS_ACTION = "/api/recordings/action"
        const val PATH_RECORDINGS_DETAIL = "/api/recordings/detail"

        /** Returns standard RFC 6454 scheme://host[:port] Origin without path slugs. */
        fun originFor(endpointUrl: String): String {
            return try {
                val u = URL(endpointUrl)
                val portSuffix = if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else ""
                "${u.protocol}://${u.host}$portSuffix"
            } catch (_: Exception) {
                endpointUrl
            }
        }

        /** Builds an SG base URL from a bare IP, host:port, or http(s) endpoint string. */
        fun baseUrlFor(endpoint: String): String {
            if (endpoint.contains(CLOUD_HOST)) {
                // Device-specific Galaxy links carry a slug path that routes
                // to one comma — preserve the full URL, never collapse it to
                // the bare host (the generic host cannot log in to a device).
                val withScheme = if (endpoint.startsWith("http")) endpoint else "https://$endpoint"
                return withScheme.removeSuffix("/")
            }
            val host = endpoint
                .removePrefix("http://")
                .removePrefix("https://")
                .removeSuffix("/")
                .substringBefore(":")
                .substringBefore("/")
            return "http://$host:8082"
        }

        /**
         * Parses Offset1..7 out of an SLC settings page payload.
         *
         * Accepts either a flat object ({"Offset1": 5, ...}) or a "rows"/"items"
         * array of objects carrying a key field ("key"/"id"/"name"/"param")
         * plus a "value". Offsets are returned only when all 7 bands were
         * actually read from the device; a partial or invented curve must
         * never be presented as real, so any missing band makes the whole
         * result null. Returns null unless all seven offsets are found ΓÇö
         * partial data is never padded with guesses.
         *
         * Note: Dom's page projection emits rows with label+value only
         * (no key) — the pinned label shape below (Shape 0) is what makes
         * those rows parseable: Dom's SlcOffsetOwner emits exactly seven
         * "{lo}–<{hi} {unit} offset" rows in band order.
         */
        fun parseSlcOffsets(root: JSONObject): DoubleArray? {
            // Shape 0 (Dom pinned): when the page carries exactly seven
            // pinned range rows, they decide the page outright. A km/h
            // table or an unreadable value means the device reports no
            // usable mph curve — return null rather than letting the
            // legacy shapes guess one out of the same rows.
            if (slcRowMatches(root).size == 7) {
                return parseSlcOffsetRows(root)?.let { rows ->
                    DoubleArray(7) { i -> rows[i].second }
                }
            }

            val out = DoubleArray(7)
            val found = BooleanArray(7)

            fun record(index: Int, raw: Any?) {
                val v = numericValue(raw) ?: return
                out[index] = v
                found[index] = true
            }

            // Shape 1: flat object.
            for (i in 1..7) {
                if (root.has("Offset$i")) record(i - 1, root.opt("Offset$i"))
            }

            // Shape 2: rows/items array of key/label + value objects.
            if (found.any { !it }) {
                val rows = root.optJSONArray("rows") ?: root.optJSONArray("items")
                if (rows != null) {
                    for (r in 0 until rows.length()) {
                        val row = rows.optJSONObject(r) ?: continue
                        val key = row.optString("key", "")
                            .ifEmpty { row.optString("id", "") }
                            .ifEmpty { row.optString("name", "") }
                            .ifEmpty { row.optString("param", "") }
                        val label = row.optString("label", "")
                            .ifEmpty { row.optString("title", "") }

                        val idx = bandFromKeyOrLabel(key, label)
                        val rawVal = when {
                            row.has("value") -> row.opt("value")
                            row.has("currentValue") -> row.opt("currentValue")
                            row.has("val") -> row.opt("val")
                            else -> null
                        }
                        if (idx != null && idx in 0..6 && !found[idx]) {
                            record(idx, rawVal)
                        } else if (idx == null && rows.length() == 7 && r in 0..6 && !found[r]) {
                            record(r, rawVal)
                        }
                    }
                }
            }

            // Honesty rule: return the curve only when all 7 bands were
            // actually read from the device. A partial result returns null —
            // missing bands are never padded with invented defaults, because a
            // fabricated band would be presented to the user as their real
            // offset and used as the base for edits.
            return if (found.all { it }) out else null
        }

        // ---- Dom's pinned SLC row shape (slc_offset_feature.py) ----
        //
        // SlcOffsetOwner.rows emits, in order: an "Offset policy" row,
        // exactly seven offset rows, a "{top} {unit} and above" row
        // whose value is display text, and optionally a reset row. The
        // offset-row label format changed with Dom 419d32286 (2026-11-11):
        // older Dom labelled them "{lo}–<{hi} {unit} offset" (EN DASH
        // U+2013; upper boundary excluded), current Dom labels them with
        // inclusive whole-number posted limits, "{lower}–{upper} {unit}
        // offset" (e.g. "0–25 mph offset", "26–34 mph offset"), or
        // "{limit} {unit} offset" when a band holds exactly one whole
        // limit. Both formats parse: rows are matched by ORDER, never
        // by hardcoded ranges. In the invalid-document state the seven
        // rows are labelled "Band N offset" instead, which the pattern
        // below deliberately does not match.

        private val SLC_RANGE_LABEL_REGEX =
            Regex("^\\d+(?:-<\\d+|-\\d+)?\\s+(mph|km/h) offset$", RegexOption.IGNORE_CASE)

        /** The unit of a pinned range-row label, or null when [label] is not one. */
        private fun slcRangeUnit(label: String): String? {
            val normalized = label.trim()
                .replace('\u2013', '-')
                .replace('\u2014', '-')
            return SLC_RANGE_LABEL_REGEX.matchEntire(normalized)
                ?.groupValues?.get(1)?.lowercase()
        }

        private class SlcRowMatch(
            /** Device label minus the trailing " offset" (e.g. "0–25 mph"). */
            val rangeLabel: String,
            val unit: String,
            val rawValue: Any?
        )

        /** All pinned range rows on the page, in page order. */
        private fun slcRowMatches(root: JSONObject): List<SlcRowMatch> {
            val rows = root.optJSONArray("rows") ?: root.optJSONArray("items")
                ?: return emptyList()
            val out = ArrayList<SlcRowMatch>()
            for (r in 0 until rows.length()) {
                val row = rows.optJSONObject(r) ?: continue
                val label = row.optString("label", "")
                    .ifEmpty { row.optString("title", "") }
                    .trim()
                val unit = slcRangeUnit(label) ?: continue
                val rawVal = when {
                    row.has("value") -> row.opt("value")
                    row.has("currentValue") -> row.opt("currentValue")
                    row.has("val") -> row.opt("val")
                    else -> null
                }
                out.add(SlcRowMatch(label.removeSuffix(" offset"), unit, rawVal))
            }
            return out
        }

        /**
         * Indices into [rows] of the pinned SLC range rows, in page
         * order — band N is the Nth entry. Used by the settings write
         * path to resolve Offset1..7 by order (see SettingsRepository).
         */
        fun slcOffsetRowIndices(rows: JSONArray): List<Int> {
            val out = ArrayList<Int>()
            for (r in 0 until rows.length()) {
                val row = rows.optJSONObject(r) ?: continue
                val label = row.optString("label", "")
                    .ifEmpty { row.optString("title", "") }
                if (slcRangeUnit(label) != null) out.add(r)
            }
            return out
        }

        /**
         * Dom's SLC offset rows as (device range label, offset) pairs —
         * the labels are the device's own (e.g. "0–25 mph" on current
         * Dom, "0–<25 mph" on older Dom), in band order. Returns null
         * unless exactly seven pinned rows are present, every value
         * parses as a number, and the unit is mph: a km/h table is
         * never silently converted into the app's mph table, and an
         * invalid-document page reports no table at all.
         */
        fun parseSlcOffsetRows(root: JSONObject): List<Pair<String, Double>>? {
            val matches = slcRowMatches(root)
            if (matches.size != 7) return null
            if (matches.any { it.unit != "mph" }) return null
            val out = ArrayList<Pair<String, Double>>(7)
            for (m in matches) {
                val v = numericValue(m.rawValue) ?: return null
                out.add(m.rangeLabel to v)
            }
            return out
        }

        private fun bandFromKeyOrLabel(key: String, label: String): Int? {
            OFFSET_KEY_REGEX.matchEntire(key.trim())?.let {
                return it.groupValues[1].toInt() - 1
            }
            val text = "$key $label".lowercase()
                .replace('\u2013', '-')
                .replace('\u2014', '-')

            val m = Regex("offset\\s*([1-7])").find(text)
            if (m != null) return m.groupValues[1].toInt() - 1

            // Band boundaries in both Dom label formats. Current Dom
            // (419d32286) uses inclusive whole-number ranges — mph bands
            // 0–25, 26–34, 35–43, 44–53, 54–63, 64–74, 75–98; older Dom
            // used exclusive upper bounds — 0–<25, 25–<35, 35–<45,
            // 45–<55, 55–<65, 65–<75, 75–<99. Checked in band order so a
            // shared edge number (e.g. "74" in 64–74) resolves to the
            // lower band first. The device's own row order is
            // authoritative wherever the caller has it; these substrings
            // are only the fallback when a row must be identified from
            // its label alone.
            if (text.contains("0") && text.contains("25")) return 0
            if ((text.contains("25") || text.contains("26")) && (text.contains("34") || text.contains("35"))) return 1
            if (text.contains("35") && (text.contains("43") || text.contains("44") || text.contains("45"))) return 2
            if ((text.contains("44") || text.contains("45")) && (text.contains("53") || text.contains("54") || text.contains("55"))) return 3
            if ((text.contains("54") || text.contains("55")) && (text.contains("63") || text.contains("64") || text.contains("65"))) return 4
            if ((text.contains("64") || text.contains("65")) && (text.contains("74") || text.contains("75"))) return 5
            if (text.contains("74") || text.contains("75") || text.contains("99") || text.contains("75+")) return 6
            return null
        }

        private val OFFSET_KEY_REGEX = Regex("(?i)offset([1-7])")

        private fun numericValue(raw: Any?): Double? = when (raw) {
            is Number -> raw.toDouble()
            is String -> {
                val t = raw.trim()
                t.toDoubleOrNull()
                    ?: Regex("^-?\\d+(\\.\\d+)?").find(t)?.value?.toDoubleOrNull()
            }
            else -> null
        }
    }
}

/**
 * Galaxy Cloud session auth for SecretGoodStarPilot - App Key pairing.
 *
 * No password is ever entered. While the phone is on a direct local connection
 * (LAN / comma hotspot) Galaxy grants a local session (GET /api/auth/session);
 * the app then calls GET /api/galaxy/session, which returns
 * {paired, slug, url, cookieName, sessionToken}. The sessionToken (the "App Key")
 * is used verbatim as the `galaxy_session` cookie for the Galaxy Cloud tunnel
 * (https://galaxy.firestar.link/<slug>) and the tunnel URL is saved automatically.
 * Whenever the comma is reachable locally the pairing is refreshed, so a 401
 * on Cloud is recovered the same way.
 *
 * The App Key and local token live only in the app's private SharedPreferences
 * ("comma_client" file) and are never logged.
 */
object GalaxyCloudSession {

    private const val TAG = "GalaxyCloudSession"
    private const val PREFS = "comma_client"
    private const val KEY_SESSION_TOKEN = "galaxy_session_token"
    private const val KEY_LOCAL_SESSION_TOKEN = "galaxy_local_session_token"
    const val COOKIE_NAME = "galaxy_session"
    private const val PATH_AUTH_SESSION = "/api/auth/session"
    private const val PATH_GALAXY_SESSION = "/api/galaxy/session"
    private const val KEY_GALAXY_URL = "galaxy_url"
    private const val KEY_LOCAL_BASE = "galaxy_paired_local_base"
    private const val LOGIN_TIMEOUT_MS = 8000
    private const val LOGIN_FAILURE_COOLDOWN_MS = 60_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sessionToken: String? = null

    @Volatile
    private var localSessionToken: String? = null

    /** True once Cloud rejected the App Key. Cleared by a successful re-pair. */
    @Volatile
    var authFailed: Boolean = false
        private set
    fun init(context: Context) {
        appContext = context.applicationContext
        if (sessionToken == null) {
            sessionToken = prefs()?.getString(KEY_SESSION_TOKEN, null)
        }
        if (localSessionToken == null) {
            localSessionToken = prefs()?.getString(KEY_LOCAL_SESSION_TOKEN, null)
        }
    }

    /** True when an App Key has been paired (name kept for existing call sites). */
    fun hasPassword(): Boolean = !currentToken().isNullOrEmpty()

    fun hasAppKey(): Boolean = hasPassword()

    @Volatile
    private var pairingInFlight = false

    /**
     * Pairs the App Key in the background when a local comma is reachable but no
     * key is stored yet. Safe to call repeatedly; a no-op once paired.
     */
    fun ensurePaired(localBase: String) {
        if (hasAppKey() || pairingInFlight) return
        pairingInFlight = true
        Thread({
            try {
                refreshLocalSession(localBase)
            } finally {
                pairingInFlight = false
            }
        }, "galaxy-app-key-pair").start()
    }

    /** Forgets the App Key (Unpair). */
    fun clearPairing() {
        sessionToken = null
        authFailed = false
        prefs()?.edit()?.remove(KEY_SESSION_TOKEN)?.apply()
    }
    fun currentToken(): String? {
        sessionToken?.let { return it }
        val stored = prefs()?.getString(KEY_SESSION_TOKEN, null)
        sessionToken = stored
        return stored
    }

    fun currentLocalToken(): String? {
        localSessionToken?.let { return it }
        val stored = prefs()?.getString(KEY_LOCAL_SESSION_TOKEN, null)
        localSessionToken = stored
        return stored
    }

    fun saveLocalSessionToken(token: String?) {
        localSessionToken = token
        if (token.isNullOrEmpty()) {
            prefs()?.edit()?.remove(KEY_LOCAL_SESSION_TOKEN)?.apply()
        } else {
            prefs()?.edit()?.putString(KEY_LOCAL_SESSION_TOKEN, token)?.apply()
        }
    }

    /**
     * Re-login entry point used on a 401. Cloud recovers by re-pairing over the
     * last known local connection; LAN/hotspot refreshes its local session.
     */
    fun relogin(baseUrl: String, isCloudBase: Boolean): Boolean {
        return if (isCloudBase) {
            login(baseUrl)
        } else {
            refreshLocalSession(baseUrl)
        }
    }
    /**
     * Refreshes the local Galaxy session by calling GET /api/auth/session.
     * Local LAN / hotspot connections get a valid session automatically without a password.
     */
    @Synchronized
    fun refreshLocalSession(baseUrl: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(baseUrl.removeSuffix("/") + PATH_AUTH_SESSION)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = LOGIN_TIMEOUT_MS
                readTimeout = LOGIN_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val token = if (code == 200) parseSessionToken(conn.headerFields, baseUrl) else null
            if (token != null) {
                saveLocalSessionToken(token)
                Log.i(TAG, "Local Galaxy session refreshed successfully")
                pairFromLocal(baseUrl)
                true
            } else {
                Log.w(TAG, "Local Galaxy session refresh returned code $code")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local Galaxy session refresh error: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Cloud "login": no password exists. Re-pairs through the last local base the
     * comma was reached on. Returns false when the comma is not reachable locally.
     */
    @Synchronized
    fun login(@Suppress("UNUSED_PARAMETER") cloudBaseUrl: String): Boolean {
        val localBase = prefs()?.getString(KEY_LOCAL_BASE, null) ?: return false
        val ok = refreshLocalSession(localBase) && hasAppKey()
        if (!ok && hasAppKey()) authFailed = true
        return ok
    }

    /**
     * Fetches the App Key from the local Galaxy (GET /api/galaxy/session) using the
     * local session, stores it as the Cloud session token and saves the tunnel URL.
     */
    private fun pairFromLocal(baseUrl: String): Boolean {
        val local = currentLocalToken() ?: return false
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl.removeSuffix("/") + PATH_GALAXY_SESSION).openConnection() as HttpURLConnection).apply {
                connectTimeout = LOGIN_TIMEOUT_MS
                readTimeout = LOGIN_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cookie", "$COOKIE_NAME=$local")
            }
            if (conn.responseCode != 200) return false
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val key = json.optString("sessionToken", "")
            if (!json.optBoolean("paired", false) || key.isEmpty()) return false
            sessionToken = key
            authFailed = false
            val edit = prefs()?.edit()
            edit?.putString(KEY_SESSION_TOKEN, key)
            edit?.putString(KEY_LOCAL_BASE, baseUrl.removeSuffix("/"))
            val url = json.optString("url", "")
            if (url.startsWith("http")) edit?.putString(KEY_GALAXY_URL, url.removeSuffix("/"))
            edit?.apply()
            Log.i(TAG, "Galaxy App Key paired from local connection")
            true
        } catch (e: Exception) {
            Log.w(TAG, "App Key pairing error: ${e.javaClass.simpleName}")
            false
        } finally {
            conn?.disconnect()
        }
    }
    /** The token in one "name=value" cookie pair, or null when the pair is another cookie or the value is empty. */
    private fun tokenFromPair(pair: String): String? {
        val trimmed = pair.trim()
        if (!trimmed.startsWith("$COOKIE_NAME=")) return null
        return trimmed.removePrefix("$COOKIE_NAME=").ifEmpty { null }
    }

    /** Extracts the galaxy_session token out of Set-Cookie headers, CookieManager, or CookieHandler. */
    fun parseSessionToken(headerFields: Map<String?, List<String>>, urlStr: String? = null): String? {
        for ((name, values) in headerFields) {
            if (name == null || !name.equals("Set-Cookie", ignoreCase = true)) continue
            for (value in values) {
                val firstPair = value.substringBefore(";").trim()
                if (firstPair.startsWith("$COOKIE_NAME=")) {
                    // A header cookie with an empty value means the
                    // session was cleared: report null at once
                    // rather than falling through to older stores.
                    return tokenFromPair(firstPair)
                }
            }
        }
        if (!urlStr.isNullOrEmpty()) {
            try {
                val cookieStr = android.webkit.CookieManager.getInstance().getCookie(urlStr)
                if (!cookieStr.isNullOrEmpty()) {
                    for (part in cookieStr.split(";")) {
                        val token = tokenFromPair(part)
                        if (token != null) {
                            Log.i(TAG, "Extracted session token from android.webkit.CookieManager")
                            return token
                        }
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }

            try {
                val handler = java.net.CookieHandler.getDefault()
                val cookies = handler?.get(java.net.URI(urlStr), emptyMap())?.get("Cookie")
                if (cookies != null) {
                    for (cookie in cookies) {
                        for (part in cookie.split(";")) {
                            val token = tokenFromPair(part)
                            if (token != null) {
                                Log.i(TAG, "Extracted session token from java.net.CookieHandler")
                                return token
                            }
                        }
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
        return null
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
