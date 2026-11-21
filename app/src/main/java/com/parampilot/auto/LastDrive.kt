package com.parampilot.auto

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The comma's most recent drive (Feature 13), from GET /api/drives/stats.
 *
 * Real payload shape (source-verified against the Dom branch:
 * openpilot/starpilot/galaxy/drive_stats.py, DriveStatsOwner.snapshot):
 * the snapshot carries a top-level "lastDrive" record — the most recent
 * non-ignored drive — plus "recentDrives" (up to 5). Each record holds
 * routeId, complete, segmentCount, startTime / endTime (epoch SECONDS,
 * float), distanceMeters, durationSeconds, engagedSeconds, model.
 * Parsing stays defensive: alternate containers and missing fields yield
 * null rather than a guessed drive.
 */
data class LastDrive(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val startEpochMs: Long,
    val endEpochMs: Long
) {
    /** "12.4 mi · 26 min · 8:42 AM" — with the date when not today. */
    fun formatDetail(nowMs: Long): String {
        val miles = distanceMeters * Units.METERS_TO_MILES
        val totalMin = Math.round(durationSeconds / 60.0).toInt()
        val duration = when {
            totalMin >= 60 -> "${totalMin / 60} h ${totalMin % 60} min"
            totalMin > 0 -> "$totalMin min"
            else -> "<1 min"
        }
        val whenText = formatWhen(endEpochMs, nowMs)
        return String.format(Locale.US, "%.1f mi · %s · %s", miles, duration, whenText)
    }

    companion object {
        fun parse(json: JSONObject?): LastDrive? {
            if (json == null) return null
            val candidates = mutableListOf<JSONObject>()
            json.optJSONObject("lastDrive")?.let { candidates.add(it) }
            val recent = json.optJSONArray("recentDrives")
            if (recent != null) {
                for (i in 0 until recent.length()) {
                    recent.optJSONObject(i)?.let { candidates.add(it) }
                }
            }
            // Defensive: a bare record at the top level.
            if (candidates.isEmpty() && json.has("distanceMeters")) candidates.add(json)
            for (record in candidates) {
                parseRecord(record)?.let { return it }
            }
            return null
        }

        private fun parseRecord(record: JSONObject): LastDrive? {
            if (record.optBoolean("ignored", false)) return null
            val distance = record.optDouble("distanceMeters", Double.NaN)
            val duration = record.optDouble("durationSeconds", Double.NaN)
            if (distance.isNaN() || duration.isNaN()) return null
            val startSec = record.optDouble("startTime", Double.NaN)
            val endSec = record.optDouble("endTime", Double.NaN)
            if (startSec.isNaN() && endSec.isNaN()) return null
            val startMs = if (startSec.isNaN()) (endSec * 1000).toLong() else (startSec * 1000).toLong()
            val endMs = if (endSec.isNaN()) startMs else (endSec * 1000).toLong()
            return LastDrive(distance, duration, startMs, endMs)
        }

        private fun formatWhen(epochMs: Long, nowMs: Long): String {
            val then = Calendar.getInstance().apply { timeInMillis = epochMs }
            val now = Calendar.getInstance().apply { timeInMillis = nowMs }
            val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
            val pattern = if (sameDay) "h:mm a" else "MMM d, h:mm a"
            return SimpleDateFormat(pattern, Locale.US).format(then.time)
        }
    }
}
