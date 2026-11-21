package com.parampilot.auto

import org.json.JSONArray
import org.json.JSONObject

/**
 * The comma's Galaxy Networks state (Wi-Fi manager, Param 2026-11-19).
 *
 * Shape pinned from Dom's openpilot/starpilot/galaxy/network_prefs.py
 * (NetworkSettings.snapshot / apply) and server.py's routing:
 *
 * GET /api/galaxy/networks returns:
 *   {"version": 1, "editable": <parked>, "prefer": "wifi"|"cellular",
 *    "pauseHotspot": bool,
 *    "cellular": {"enabled": bool, "connected": bool, "type": str,
 *                 "operator": str},
 *    "link": {"link": "wifi"|"cellular"|"none"|"other"|"unknown",
 *             "changedAt": <epoch seconds>, "changes": int},
 *    "networks": [{"uuid", "name", "autoJoin": bool,
 *                  "whileDriving": bool, "connected": bool,
 *                  "metered": "Auto"|"Metered"|"Unmetered",
 *                  "signal": int|null} ...],   // priority order
 *    "available": [{"ssid", "signal": int,
 *                   "security": "open"|"wpa"|"unsupported"} ...],
 *    "lastJoin": {"ssid", "state": "joining"|"joined"|"failed",
 *                 "error": str} | null}
 * "available" only carries rows after a scan action, and never lists a
 * network that is already saved. A saved network's "signal" is null when
 * it is out of range.
 *
 * POST /api/galaxy/networks takes ONE action per call with an EXACT key
 * set (the server rejects extra keys) — the builders below produce
 * exactly those sets:
 *   {"action": "scan"}
 *   {"action": "join", "ssid", "password", "hidden"}
 *   {"action": "connect", "uuid"}        {"action": "forget", "uuid"}
 *   {"action": "order", "uuids": [...]}  // the full saved set, reordered
 *   {"action": "network", "uuid", "setting", "value"}
 *       setting: "autoJoin" (bool) | "whileDriving" (bool) |
 *                "metered" ("Auto"|"Metered"|"Unmetered")
 *   {"action": "prefer", "value": "wifi"|"cellular"}
 *   {"action": "cellular", "value": bool}
 *   {"action": "pauseHotspot", "value": bool}
 * A 200 response is the fresh snapshot. Errors are the server's own
 * text: 401 "Sign in to Galaxy", 409 "Park before changing network
 * settings." (and other 409s for a stale view), 400 for an invalid
 * request or a failed join/connect, 503 when unavailable. Changes are
 * only permitted while parked (the snapshot's "editable").
 *
 * Joining can run in the background when the caller arrives over the
 * Galaxy hotspot: the response then shows lastJoin.state == "joining"
 * and the outcome lands in a later snapshot (kept for 10 minutes).
 */
data class GalaxyNetworks(
    val editable: Boolean,
    val prefer: String,
    val pauseHotspot: Boolean,
    val cellularEnabled: Boolean,
    val cellularConnected: Boolean,
    val cellularType: String,
    val cellularOperator: String,
    val link: String,
    val linkChangedAt: Double,
    val networks: List<SavedNetwork>,
    val available: List<AvailableNetwork>,
    val lastJoin: LastJoin?
) {
    data class SavedNetwork(
        val uuid: String,
        val name: String,
        val autoJoin: Boolean,
        val whileDriving: Boolean,
        val connected: Boolean,
        val metered: String,
        /** Percent, or null when the network is out of range. */
        val signal: Int?
    )

    data class AvailableNetwork(
        val ssid: String,
        val signal: Int,
        /** "open" | "wpa" | "unsupported" (enterprise). */
        val security: String
    )

    data class LastJoin(
        val ssid: String,
        val state: String,
        val error: String
    )

    /** Result of one POST action: HTTP code, the fresh state on 200,
     * and the comma's own error text on failure. */
    data class ActionResult(
        val code: Int,
        val state: GalaxyNetworks?,
        val error: String?
    )

    /** "Connected to T-Mobile (LTE)." / "Not connected right now." /
     * "Off · comma goes online over Wi-Fi only." — the Galaxy page's
     * own wording. */
    fun cellularDetail(): String {
        if (!cellularEnabled) return "Off · comma goes online over Wi-Fi only."
        if (!cellularConnected) return "Not connected right now."
        val op = if (cellularOperator.isNotEmpty()) " to $cellularOperator" else ""
        val type = if (cellularType.isNotEmpty()) " (${cellularType.uppercase()})" else ""
        return "Connected$op$type."
    }

    companion object {
        const val PREFER_WIFI = "wifi"
        const val PREFER_CELLULAR = "cellular"

        val METERED_OPTIONS = listOf("Auto", "Metered", "Unmetered")
        val SECURITY_OPTIONS = listOf("open", "wpa", "unsupported")

        fun linkLabel(link: String): String = when (link) {
            "wifi" -> "Wi-Fi"
            "cellular" -> "Cellular"
            "none" -> "Offline"
            "other" -> "Other"
            else -> "Unknown"
        }

        fun preferLabel(prefer: String): String =
            if (prefer == PREFER_CELLULAR) "Cellular" else "Wi-Fi"

        /** The Galaxy page's one-line summary for a saved network. */
        fun networkSummary(network: SavedNetwork): String {
            val parts = mutableListOf<String>()
            parts += when {
                network.connected -> "Connected"
                network.signal == null -> "Not in range"
                else -> "In range, signal ${network.signal}%"
            }
            parts += if (!network.autoJoin) {
                "Manual"
            } else if (network.whileDriving) {
                "Auto-join"
            } else {
                "Parked only"
            }
            if (network.metered != "Auto") parts += network.metered
            return parts.joinToString(" · ")
        }

        /** Mirrors the server's valid_ssid: 1–64 chars once trimmed,
         * no control characters. */
        fun isValidSsid(value: String): Boolean =
            value.trim().length in 1..64 &&
                value.none { it.code < 32 || it.code == 127 }

        /** Mirrors the server's valid_password: blank (open network)
         * or 8–64 chars, no control characters. */
        fun isValidPassword(value: String): Boolean =
            value.isEmpty() ||
                (value.length in 8..64 && value.none { it.code < 32 || it.code == 127 })

        fun parse(json: JSONObject?): GalaxyNetworks? {
            if (json == null) return null
            if (json.optInt("version", -1) != 1) return null
            if (!json.has("networks") || !json.has("cellular") || !json.has("link")) {
                return null
            }
            val cellular = json.optJSONObject("cellular") ?: return null
            val linkObj = json.optJSONObject("link") ?: return null
            val prefer = json.optString("prefer", PREFER_WIFI)
            if (prefer != PREFER_WIFI && prefer != PREFER_CELLULAR) return null

            val networks = mutableListOf<SavedNetwork>()
            val rows = json.optJSONArray("networks") ?: return null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: return null
                val uuid = row.optString("uuid", "")
                val metered = row.optString("metered", "Auto")
                if (uuid.isEmpty() || metered !in METERED_OPTIONS) return null
                networks += SavedNetwork(
                    uuid = uuid,
                    name = row.optString("name", ""),
                    autoJoin = row.optBoolean("autoJoin", false),
                    whileDriving = row.optBoolean("whileDriving", true),
                    connected = row.optBoolean("connected", false),
                    metered = metered,
                    signal = if (row.isNull("signal")) null else row.optInt("signal")
                )
            }

            val available = mutableListOf<AvailableNetwork>()
            val found = json.optJSONArray("available")
            if (found != null) {
                for (i in 0 until found.length()) {
                    val row = found.optJSONObject(i) ?: continue
                    val security = row.optString("security", "")
                    if (security !in SECURITY_OPTIONS) continue
                    available += AvailableNetwork(
                        ssid = row.optString("ssid", ""),
                        signal = row.optInt("signal", 0),
                        security = security
                    )
                }
            }

            val lastJoin = json.optJSONObject("lastJoin")?.let { lj ->
                val state = lj.optString("state", "")
                if (state in listOf("joining", "joined", "failed")) {
                    LastJoin(
                        ssid = lj.optString("ssid", ""),
                        state = state,
                        error = lj.optString("error", "")
                    )
                } else {
                    null
                }
            }

            return GalaxyNetworks(
                editable = json.optBoolean("editable", false),
                prefer = prefer,
                pauseHotspot = json.optBoolean("pauseHotspot", true),
                cellularEnabled = cellular.optBoolean("enabled", false),
                cellularConnected = cellular.optBoolean("connected", false),
                cellularType = cellular.optString("type", ""),
                cellularOperator = cellular.optString("operator", ""),
                link = linkObj.optString("link", "unknown"),
                linkChangedAt = linkObj.optDouble("changedAt", 0.0),
                networks = networks,
                available = available,
                lastJoin = lastJoin
            )
        }

        // ---- POST action bodies (exact key sets, see class doc) ----

        fun scanAction(): JSONObject = JSONObject().apply {
            put("action", "scan")
        }

        fun joinAction(ssid: String, password: String, hidden: Boolean): JSONObject =
            JSONObject().apply {
                put("action", "join")
                put("ssid", ssid)
                put("password", password)
                put("hidden", hidden)
            }

        fun connectAction(uuid: String): JSONObject = JSONObject().apply {
            put("action", "connect")
            put("uuid", uuid)
        }

        fun forgetAction(uuid: String): JSONObject = JSONObject().apply {
            put("action", "forget")
            put("uuid", uuid)
        }

        fun orderAction(uuids: List<String>): JSONObject = JSONObject().apply {
            put("action", "order")
            put("uuids", JSONArray(uuids))
        }

        fun networkBoolAction(uuid: String, setting: String, value: Boolean): JSONObject =
            JSONObject().apply {
                put("action", "network")
                put("uuid", uuid)
                put("setting", setting)
                put("value", value)
            }

        fun networkMeteredAction(uuid: String, value: String): JSONObject =
            JSONObject().apply {
                put("action", "network")
                put("uuid", uuid)
                put("setting", "metered")
                put("value", value)
            }

        fun preferAction(value: String): JSONObject = JSONObject().apply {
            put("action", "prefer")
            put("value", value)
        }

        fun cellularAction(value: Boolean): JSONObject = JSONObject().apply {
            put("action", "cellular")
            put("value", value)
        }

        fun pauseHotspotAction(value: Boolean): JSONObject = JSONObject().apply {
            put("action", "pauseHotspot")
            put("value", value)
        }

        /** The uuid order after moving the entry at [index] by [step]
         * (-1 up / +1 down), or null when the move leaves the list. */
        fun reorderedUuids(networks: List<SavedNetwork>, index: Int, step: Int): List<String>? {
            val target = index + step
            if (index !in networks.indices || target !in networks.indices) return null
            val uuids = networks.map { it.uuid }.toMutableList()
            val moved = uuids.removeAt(index)
            uuids.add(target, moved)
            return uuids
        }
    }
}
