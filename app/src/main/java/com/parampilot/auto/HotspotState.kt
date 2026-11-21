package com.parampilot.auto

import org.json.JSONObject

/**
 * The comma's Galaxy hotspot state (Feature 16).
 *
 * Shape pinned from Dom's openpilot/starpilot/galaxy/hotspot.py:
 * GET /api/galaxy/hotspot returns HotspotSettings.snapshot():
 *   {"version": 1,
 *    "config": {"enabled": bool, "ssid": "TheGalaxy-<last4 of dongle id>",
 *               "password": "..."},
 *    "revision": "<sha256 of config>", "editable": <parked>,
 *    "url": "http://172.31.254.1:8082/",
 *    "active": bool, "state": str, "reason": str   // runtime keys, only
 *                                                 // when the hotspot
 *                                                 // service has reported
 *   }
 * POST /api/galaxy/hotspot accepts EXACTLY {"revision", "config"} where
 * config is the full {"enabled", "ssid", "password"} document; an empty
 * password means "keep the current one" (the server substitutes it, or
 * generates one when none exists). The app therefore never needs to know
 * or display the hotspot password — it round-trips "" only.
 *
 * Toggling is revision-checked: a stale revision is a 409
 * ("Hotspot settings changed; refresh and try again."), and the server
 * refuses changes unless the car is parked (409 "Park before changing
 * the Galaxy hotspot."). Callers surface those errors verbatim.
 */
data class HotspotState(
    val enabled: Boolean,
    val ssid: String,
    val revision: String?,
    val editable: Boolean,
    /** Runtime radio state from the hotspot service; null when unreported. */
    val active: Boolean?
) {
    /** "Hotspot: On · TheGalaxy-XXXX" / "Hotspot: Off". */
    fun label(): String =
        if (enabled) "Hotspot: On · $ssid" else "Hotspot: Off"

    /**
     * The exact POST body that flips [enabled], or null when the snapshot
     * carried no revision (a toggle must never be sent unrevisioned — the
     * server would reject it anyway, and guessing helps no one).
     */
    fun togglePayload(): JSONObject? {
        val rev = revision?.takeIf { it.isNotEmpty() } ?: return null
        val config = JSONObject().apply {
            put("enabled", !enabled)
            put("ssid", ssid)
            put("password", "")
        }
        return JSONObject().apply {
            put("revision", rev)
            put("config", config)
        }
    }

    companion object {
        /**
         * Parses a GET /api/galaxy/hotspot snapshot. Returns null when the
         * config document or its enabled flag / SSID is absent — a state
         * the toggle cannot safely act on is treated as unknown.
         */
        fun parse(json: JSONObject?): HotspotState? {
            val config = json?.optJSONObject("config") ?: return null
            val enabledRaw = config.opt("enabled")
            if (enabledRaw !is Boolean) return null
            val ssid = config.optString("ssid", "").trim()
            if (ssid.isEmpty()) return null
            return HotspotState(
                enabled = enabledRaw,
                ssid = ssid,
                revision = json.optString("revision", "").ifEmpty { null },
                editable = json.optBoolean("editable", false),
                active = if (json.has("active") && !json.isNull("active")) {
                    json.optBoolean("active", false)
                } else {
                    null
                }
            )
        }
    }
}
