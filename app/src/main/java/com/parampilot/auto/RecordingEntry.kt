package com.parampilot.auto

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One route from GET /api/recordings/local (Feature 17).
 *
 * Shape pinned from Dom's drive_history.py (DriveHistory.snapshot +
 * recording_details) and recording_library.py (RecordingLibrary.describe):
 *   {"schemaVersion": 1, "source": "local", "scanIncomplete": bool,
 *    "routes": [{"routeId": "<dongle>|<route name>",
 *                "segmentCount": int, "provider": "comma"|null,
 *                "fileTime": <epoch seconds float>|null,
 *                "segments": [{"number": int, "segmentName": str,
 *                              "files": {...presence bools...},
 *                              "logBytes": {"rlog.zst": bytes, ...}}],
 *                "displayName": str, "preserved": bool,
 *                "startTime": ..., "connectUrl": str|null}]}
 *
 * Honesty notes:
 * - The list payload carries NO video sizes — the only byte counts are
 *   the segments' logBytes (rlog/qlog files). [logBytes] here is
 *   therefore the LOG size only, and every label says so; presenting it
 *   as the route's total size would invent numbers the comma never sent.
 *   Real video sizes come from /api/recordings/detail (Fix C,
 *   [RecordingDetail]) and are layered onto the labels separately.
 * - The route's date is fileTime (earliest segment file mtime, epoch
 *   seconds — read straight from drive_history.py). The payload's
 *   "startTime" comes from drive statistics with an unpinned type, so
 *   it is deliberately not used.
 * - The endpoint is LOCAL-ONLY: over the Galaxy tunnel it answers
 *   403 {"code": "local_only"} before any of this is emitted.
 */
data class RecordingEntry(
    val routeId: String,
    val displayName: String,
    val segmentCount: Int,
    /** Earliest segment file mtime, epoch seconds; null when unreported. */
    val fileTimeSec: Double?,
    /** Summed rlog/qlog bytes across segments; null when no log sizes were reported. */
    val logBytes: Long?,
    val preserved: Boolean
) {
    /** "Name · Nov 1, 9:12 AM · 3 segments · logs 12.4 MB" — unknown parts omitted. */
    fun rowLabel(): String = rowLabel(null)

    /**
     * Row label including the route's video size once its detail
     * record has been loaded (Fix C): the size parts then read
     * "Video 1.2 GB · Logs 3.4 MB". A null [videoBytes] (detail not
     * loaded yet, or the comma reported none) keeps the logs-only
     * label exactly as [rowLabel] renders it.
     */
    fun rowLabel(videoBytes: Long?): String {
        val parts = mutableListOf(displayName)
        fileTimeSec?.let { parts.add(formatDate(it)) }
        if (segmentCount > 0) {
            parts.add(if (segmentCount == 1) "1 segment" else "$segmentCount segments")
        }
        if (videoBytes != null) {
            parts.add("Video ${formatBytes(videoBytes)}")
            logBytes?.let { parts.add("Logs ${formatBytes(it)}") }
        } else {
            logBytes?.let { parts.add("logs ${formatBytes(it)}") }
        }
        if (preserved) parts.add("preserved")
        return parts.joinToString(" · ")
    }

    companion object {
        /**
         * Parses the /api/recordings/local payload into entries sorted
         * newest-first by fileTime (routes without a time sort last).
         * Null or malformed payloads yield an empty list.
         */
        fun parseList(json: JSONObject?): List<RecordingEntry> {
            val routes = json?.optJSONArray("routes") ?: return emptyList()
            val out = mutableListOf<RecordingEntry>()
            for (i in 0 until routes.length()) {
                val route = routes.optJSONObject(i) ?: continue
                val routeId = route.optString("routeId", "").trim()
                if (routeId.isEmpty()) continue
                val segments = route.optJSONArray("segments")
                var logTotal = 0L
                var anyLog = false
                if (segments != null) {
                    for (s in 0 until segments.length()) {
                        val logBytes = segments.optJSONObject(s)?.optJSONObject("logBytes")
                            ?: continue
                        val keys = logBytes.keys()
                        while (keys.hasNext()) {
                            val v = logBytes.optLong(keys.next(), -1L)
                            if (v >= 0) {
                                logTotal += v
                                anyLog = true
                            }
                        }
                    }
                }
                val fileTime = route.opt("fileTime")
                out.add(
                    RecordingEntry(
                        routeId = routeId,
                        displayName = route.optString("displayName", "").trim()
                            .ifEmpty { routeId },
                        segmentCount = if (route.has("segmentCount")) {
                            route.optInt("segmentCount", 0)
                        } else {
                            segments?.length() ?: 0
                        },
                        fileTimeSec = (fileTime as? Number)?.toDouble()
                            ?.takeIf { it > 0.0 },
                        logBytes = if (anyLog) logTotal else null,
                        preserved = route.optBoolean("preserved", false)
                    )
                )
            }
            return out.sortedWith(
                compareByDescending<RecordingEntry> { it.fileTimeSec ?: Double.NEGATIVE_INFINITY }
            )
        }

        /** "12 routes" or "12 routes · logs 1.2 GB" when any log size is known. */
        fun totalsLine(entries: List<RecordingEntry>): String {
            val base = if (entries.size == 1) "1 route" else "${entries.size} routes"
            val sized = entries.mapNotNull { it.logBytes }
            if (sized.isEmpty()) return base
            return "$base · logs ${formatBytes(sized.sum())}"
        }

        /**
         * Totals including per-route video sizes (Fix C). Until the
         * detail fetches settle ([detailsSettled] false) this is
         * exactly [totalsLine] — a partial video sum is never shown.
         * Once settled, the summed video bytes of the routes whose
         * details loaded are appended, and every route still missing a
         * video size is called out, so the video figure never silently
         * passes as complete.
         */
        fun totalsLine(
            entries: List<RecordingEntry>,
            videoBytesByRoute: Map<String, Long>,
            detailsSettled: Boolean
        ): String {
            var line = totalsLine(entries)
            if (!detailsSettled || entries.isEmpty()) return line
            if (videoBytesByRoute.isNotEmpty()) {
                line += " · video ${formatBytes(videoBytesByRoute.values.sum())}"
            }
            val missing = entries.count { it.routeId !in videoBytesByRoute }
            if (missing > 0) {
                line += " · video sizes unavailable for $missing " +
                    if (missing == 1) "route" else "routes"
            }
            return line
        }

        /**
         * The exact POST /api/recordings/action body that deletes one
         * whole route (RecordingLibrary.action: key set is exactly
         * {action, routeId, confirmed}). The server re-checks the parked
         * state itself and answers 409 when the car is not off.
         */
        fun deletePayload(routeId: String): JSONObject = JSONObject().apply {
            put("action", "delete")
            put("routeId", routeId)
            put("confirmed", true)
        }

        /** Epoch-seconds → "Nov 1, 9:12 AM" (same pattern as LastDrive). */
        fun formatDate(epochSec: Double): String =
            SimpleDateFormat("MMM d, h:mm a", Locale.US)
                .format(Date((epochSec * 1000).toLong()))

        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 ->
                String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 ->
                String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
