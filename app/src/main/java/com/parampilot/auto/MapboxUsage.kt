package com.parampilot.auto

import org.json.JSONObject

/**
 * The comma's Mapbox search budget for the current month (Feature 6).
 *
 * Parsed from the nav status snapshot's "mapboxUsage" object, whose
 * "searchSessions" entry is {used, limit, free} (Dom mapbox_budget.py
 * read_usage): the limit is 99% of the free tier (500 → 495) and comma
 * search stops at the limit, so the remaining count is worth surfacing
 * before it runs out — the same quota blindness that once burned the
 * phone-side search budget.
 */
data class MapboxUsage(
    val searchUsed: Int,
    val searchLimit: Int
) {
    /** Searches left this month; never negative even if the server over-reports use. */
    fun searchesRemaining(): Int = (searchLimit - searchUsed).coerceAtLeast(0)

    /** True when the remaining count is low enough to warn about. */
    val isLow: Boolean get() = searchesRemaining() <= LOW_REMAINING_THRESHOLD

    /** "Comma searches: 123 left this month", with a warning form when low. */
    fun label(): String {
        val remaining = searchesRemaining()
        return if (isLow) {
            "Comma searches: only $remaining left this month"
        } else {
            "Comma searches: $remaining left this month"
        }
    }

    companion object {
        const val LOW_REMAINING_THRESHOLD = 25

        /**
         * Parses the snapshot's mapboxUsage object. Returns null when the
         * object or its searchSessions used/limit fields are absent — the
         * budget is then unknown and the UI shows nothing.
         */
        fun parse(json: JSONObject?): MapboxUsage? {
            val sessions = json?.optJSONObject("searchSessions") ?: return null
            if (!sessions.has("used") || !sessions.has("limit")) return null
            return MapboxUsage(
                searchUsed = sessions.optInt("used", 0),
                searchLimit = sessions.optInt("limit", 0)
            )
        }

        /** The label for a possibly-unknown usage; null when unknown. */
        fun labelFor(usage: MapboxUsage?): String? = usage?.label()
    }
}
