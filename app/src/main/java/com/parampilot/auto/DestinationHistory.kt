package com.parampilot.auto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent "most visited" destination history.
 *
 * Storage: a JSON array in SharedPreferences ("destination_history"). Each
 * entry holds name, address, coordinates, a visit count, and the last-used
 * timestamp. Recorded whenever the user actually starts navigating to a
 * destination (search result, favorite, or Home/Work shortcut), via
 * NavScreen.startRoute and SearchScreen's selection path.
 *
 * Bounds: at most [MAX_ENTRIES] places; the least-visited (then oldest)
 * entries are evicted first.
 */
object DestinationHistory {

    private const val PREFS = "destination_history"
    private const val KEY_HISTORY = "history_json"
    private const val MAX_ENTRIES = 50

    /** One recorded navigation start. Destinations without coordinates are skipped. */
    @Synchronized
    fun record(context: Context, dest: NavDestination) {
        if (!dest.hasCoordinates) return
        val entries = readEntries(context)
        val existing = entries.firstOrNull { stored ->
            NavDestination(
                name = stored.optString("name", ""),
                placeName = stored.optString("placeName", ""),
                latitude = stored.optDouble("latitude", 0.0),
                longitude = stored.optDouble("longitude", 0.0)
            ).isSameAs(dest)
        }
        if (existing != null) {
            existing.put("name", dest.name)
            existing.put("placeName", dest.placeName)
            existing.put("latitude", dest.latitude)
            existing.put("longitude", dest.longitude)
            dest.mapboxId?.let { existing.put("mapboxId", it) }
            existing.put("visitCount", existing.optInt("visitCount", 0) + 1)
            existing.put("lastUsedEpochMs", System.currentTimeMillis())
        } else {
            entries.add(JSONObject().apply {
                put("name", dest.name)
                put("placeName", dest.placeName)
                put("latitude", dest.latitude)
                put("longitude", dest.longitude)
                dest.mapboxId?.let { put("mapboxId", it) }
                put("visitCount", 1)
                put("lastUsedEpochMs", System.currentTimeMillis())
            })
        }
        if (entries.size > MAX_ENTRIES) {
            entries.sortWith(
                compareByDescending<JSONObject> { it.optInt("visitCount", 0) }
                    .thenByDescending { it.optLong("lastUsedEpochMs", 0L) }
            )
            while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        }
        writeEntries(context, entries)
    }

    /** Last searched / recent first: most recently used timestamp descending. */
    @Synchronized
    fun recent(context: Context, limit: Int = 10): List<NavDestination> {
        val entries = readEntries(context)
        entries.sortByDescending { it.optLong("lastUsedEpochMs", 0L) }
        return entries.take(limit).map { o ->
            NavDestination(
                name = o.optString("name", ""),
                placeName = o.optString("placeName", ""),
                latitude = o.optDouble("latitude", 0.0),
                longitude = o.optDouble("longitude", 0.0),
                mapboxId = o.optString("mapboxId", "").takeIf { it.isNotEmpty() }
            )
        }
    }

    /** Most visited first: visit count descending, then most recently used. */
    @Synchronized
    fun top(context: Context, limit: Int = 8): List<NavDestination> {
        val entries = readEntries(context)
        entries.sortWith(
            compareByDescending<JSONObject> { it.optInt("visitCount", 0) }
                .thenByDescending { it.optLong("lastUsedEpochMs", 0L) }
        )
        return entries.take(limit).map { o ->
            NavDestination(
                name = o.optString("name", ""),
                placeName = o.optString("placeName", ""),
                latitude = o.optDouble("latitude", 0.0),
                longitude = o.optDouble("longitude", 0.0),
                mapboxId = o.optString("mapboxId", "").takeIf { it.isNotEmpty() }
            )
        }
    }

    private fun readEntries(context: Context): MutableList<JSONObject> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null)
        val out = mutableListOf<JSONObject>()
        if (raw.isNullOrEmpty()) return out
        val arr = try { JSONArray(raw) } catch (_: Exception) { return out }
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    private fun writeEntries(context: Context, entries: List<JSONObject>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, arr.toString()).apply()
    }
}
