package com.parampilot.auto

import org.json.JSONObject
import java.net.URI

/**
 * Pure decision for the AUTO-mode Bluetooth-offline failover.
 *
 * In AUTO the poll loop promotes the active transport to BLUETOOTH as
 * soon as the BLE bridge connects, but a dropped bridge used to leave
 * the label (and the user's view) stuck on "Bluetooth (Offline)" until
 * three telemetry polls failed and the startup sequence retried
 * Bluetooth first. When the transport says BLUETOOTH while the bridge
 * is actually down, the right move is to demote to a reachable LAN
 * comma immediately — the background BLE maintainer keeps retrying and
 * the poll loop promotes Bluetooth again when it revives.
 *
 * Rate-limited by [COOLDOWN_MS] so a flapping bridge cannot trigger a
 * LAN scan on every poll. A [lastAttemptMs] of 0 (never attempted)
 * always passes the cooldown, so the first observed drop fails over
 * right away.
 *
 * While Bluetooth IS healthy, the comma itself is the best source of
 * its LAN address: GET /api/local-access (reachable over the BLE
 * bridge) reports the addresses it is actually listening on. The
 * client learns and persists that address (see
 * [parseLocalAccessIps]) so the failover above can probe it directly
 * first — a full network scan is the last resort, only for when the
 * learned address and the quick known-address check both fail.
 */
object BleFailover {

    /** Minimum gap between two failover attempts. */
    const val COOLDOWN_MS = 15_000L

    fun shouldFailover(
        modeIsAuto: Boolean,
        transportIsBluetooth: Boolean,
        bleConnected: Boolean,
        nowMs: Long,
        lastAttemptMs: Long
    ): Boolean {
        if (!modeIsAuto) return false
        if (!transportIsBluetooth) return false
        if (bleConnected) return false
        return nowMs - lastAttemptMs >= COOLDOWN_MS
    }

    /** Minimum gap between two /api/local-access learns over BLE. */
    const val LEARN_INTERVAL_MS = 60_000L

    /**
     * Cadence gate for learning the comma's LAN address over the BLE
     * bridge while Bluetooth is healthy. A [lastFetchMs] of 0 (never
     * fetched — e.g. the pass where the transport just flipped to
     * BLUETOOTH) always passes, so the first learn happens immediately.
     */
    fun shouldFetchLocalAccess(
        bleConnected: Boolean,
        nowMs: Long,
        lastFetchMs: Long
    ): Boolean {
        if (!bleConnected) return false
        return nowMs - lastFetchMs >= LEARN_INTERVAL_MS
    }

    /**
     * Parses a GET /api/local-access payload (Dom galaxy/local_access.py)
     * into the comma's LAN hosts, in payload order:
     *
     *   {"available": true,
     *    "addresses": [{"interface": "wlan0", "label": "192.168.68.14 (wlan0)",
     *                   "url": "http://192.168.68.14:8082/"}],
     *    "reason": ""}
     *
     * Only well-formed http URLs on the Galaxy port count — a host on
     * any other port is not a Galaxy endpoint. An unavailable payload,
     * a missing/!array addresses field, and malformed entries are all
     * skipped silently; this never throws.
     */
    fun parseLocalAccessIps(json: JSONObject): List<String> {
        if (!json.optBoolean("available", false)) return emptyList()
        val addresses = json.optJSONArray("addresses") ?: return emptyList()
        val ips = ArrayList<String>()
        for (i in 0 until addresses.length()) {
            val entry = addresses.optJSONObject(i) ?: continue
            val url = entry.optString("url", "")
            if (url.isBlank()) continue
            val host = try {
                val uri = URI(url)
                if (!uri.scheme.equals("http", ignoreCase = true)) null
                else if (uri.port != NetworkScanner.PORT) null
                else uri.host?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            if (host != null) ips.add(host)
        }
        return ips
    }

    /**
     * Ordered, distinct direct-probe candidates for the failover
     * thread: the address learned from the comma itself over Bluetooth
     * first (it is the freshest fact available — if it answers, no
     * scan is needed at all), then the last saved LAN address. Blank
     * values and Galaxy Cloud URLs are not LAN candidates.
     */
    fun candidateOrder(learnedIp: String?, savedIp: String?): List<String> {
        val ordered = LinkedHashSet<String>()
        for (raw in listOf(learnedIp, savedIp)) {
            val ip = raw?.trim().orEmpty()
            if (ip.isNotEmpty() && !ip.contains("galaxy.firestar.link")) {
                ordered.add(ip)
            }
        }
        return ordered.toList()
    }
}
