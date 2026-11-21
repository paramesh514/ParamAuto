package com.parampilot.auto

import org.json.JSONObject

/**
 * GET /api/recordings/detail?routeId=<id> (Round-2 Fix C).
 *
 * The recordings LIST payload carries no video sizes — only segment
 * log bytes (see [RecordingEntry]). Per-route video sizes live in the
 * detail record instead: its "route" object holds "videoBytes", a
 * {camera: bytes} map (shape pinned from Dom's server.py
 * /api/recordings/detail handler, which answers
 * {"schemaVersion": 1, "source": "local", "route": {...}}).
 *
 * This object only extracts and sums what the comma reported; it
 * never estimates a size for a route the detail call didn't cover.
 */
object RecordingDetail {

    /** The "route" object of a detail payload; null when absent. */
    fun routeOf(detailRoot: JSONObject?): JSONObject? =
        detailRoot?.optJSONObject("route")

    /**
     * Total video bytes across cameras for one detail route object.
     * Null when the route carries no "videoBytes" object at all (the
     * comma reported no video sizes for it); an empty map sums to 0.
     * Non-numeric and negative entries are skipped, never coerced.
     */
    fun totalVideoBytes(routeJson: JSONObject): Long? {
        val video = routeJson.optJSONObject("videoBytes") ?: return null
        var total = 0L
        val keys = video.keys()
        while (keys.hasNext()) {
            val v = video.opt(keys.next()) as? Number ?: continue
            val n = v.toLong()
            if (n >= 0) total += n
        }
        return total
    }
}
