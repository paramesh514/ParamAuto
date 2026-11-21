package com.parampilot.auto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent cache for destination search results.
 *
 * Exists to protect the Mapbox SearchBox session quota: identical queries are
 * answered locally instead of burning another suggest/retrieve session.
 *
 * Storage: a single JSON object in SharedPreferences ("search_cache"),
 * keyed by normalized query (trim, lowercase, collapsed spaces). Each entry
 * holds the result list (name, address, lat, lng, source, plus the Mapbox id
 * and distance when known so cached rows render and resolve exactly like
 * fresh ones) and a timestamp. Suggestions from Mapbox carry lat/lng 0.0;
 * once a place is resolved via retrieve, [putResolved] writes the real
 * coordinates back (both into matching cached results and into a dedicated
 * "resolved:<mapboxId>" record) so repeat selections skip Mapbox entirely.
 *
 * Performance: the parsed JSON is kept in memory after the first read
 * (review C3) — lookups and while-typing filters never re-parse the blob.
 *
 * Bounds: at most [MAX_ENTRIES] queries (oldest evicted first); entries older
 * than [MAX_AGE_MS] (90 days) count as misses and are dropped.
 */
object SearchCache {

    private const val PREFS = "search_cache"
    private const val KEY_CACHE = "cache_json"
    private const val MAX_ENTRIES = 200
    private const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
    private const val RESOLVED_PREFIX = "resolved:"

    /** In-memory parsed copy of the cache JSON; lazily loaded, kept current by [writeRoot]. */
    @Volatile
    private var memoryRoot: JSONObject? = null

    fun normalize(query: String): String =
        query.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Exact-query lookup. Returns null on a miss or a stale entry. */
    @Synchronized
    fun lookup(context: Context, query: String): List<NavDestination>? {
        val key = normalize(query)
        if (key.isEmpty()) return null
        val root = readRoot(context)
        val entry = root.optJSONObject(key) ?: return null
        val ts = entry.optLong("ts", 0L)
        if (System.currentTimeMillis() - ts > MAX_AGE_MS) {
            root.remove(key)
            writeRoot(context, root)
            return null
        }
        return parseResults(entry.optJSONArray("results"))?.map { applyResolved(root, it) }
    }

    /** Stores a successful network search under its normalized query. */
    @Synchronized
    fun put(context: Context, query: String, results: List<NavDestination>, source: String = "comma") {
        val key = normalize(query)
        if (key.isEmpty() || results.isEmpty()) return
        val root = readRoot(context)
        val arr = JSONArray()
        for (d in results) {
            arr.put(JSONObject().apply {
                put("name", d.name)
                put("placeName", d.placeName)
                put("latitude", d.latitude)
                put("longitude", d.longitude)
                put("source", source)
                if (d.mapboxId != null) put("mapboxId", d.mapboxId)
                if (d.sessionToken != null) put("sessionToken", d.sessionToken)
                if (d.distanceM != null) put("distanceM", d.distanceM)
            })
        }
        root.put(key, JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("results", arr)
        })
        prune(root)
        writeRoot(context, root)
    }

    /**
     * Local-only filtering over everything already cached, for while-typing
     * display. Performs NO network work. Results from the entry whose key
     * matches the query exactly come first, then cached places whose
     * name/address contains the query; duplicates removed, capped at [limit].
     */
    @Synchronized
    fun filterCached(context: Context, query: String, limit: Int = 6): List<NavDestination> {
        val key = normalize(query)
        if (key.length < 2) return emptyList()
        val root = readRoot(context)
        val now = System.currentTimeMillis()
        val exact = mutableListOf<NavDestination>()
        val partial = mutableListOf<NavDestination>()
        val seen = mutableSetOf<String>()

        fun consider(dest: NavDestination, bucket: MutableList<NavDestination>) {
            val id = "${dest.name.lowercase()}|${dest.latitude}|${dest.longitude}"
            if (seen.add(id)) bucket.add(dest)
        }

        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith(RESOLVED_PREFIX)) continue
            val entry = root.optJSONObject(k) ?: continue
            if (now - entry.optLong("ts", 0L) > MAX_AGE_MS) continue
            val results = parseResults(entry.optJSONArray("results"))
                ?.map { applyResolved(root, it) } ?: continue
            when {
                k == key -> results.forEach { consider(it, exact) }
                k.contains(key) -> results.forEach { consider(it, partial) }
                else -> results.forEach { d ->
                    if (d.name.lowercase().contains(key) || d.placeName.lowercase().contains(key)) {
                        consider(d, partial)
                    }
                }
            }
        }
        return (exact + partial).take(limit)
    }

    /**
     * Persists a place whose coordinates were resolved (e.g. via Mapbox
     * retrieve) so future lookups/selections of the same Mapbox id carry real
     * coordinates and never pay for another retrieve. No-op when [dest] has no
     * Mapbox id or no coordinates.
     *
     * Stored two ways: a dedicated "resolved:<mapboxId>" record carrying the
     * resolved name/placeName/latitude/longitude (survives eviction of the
     * originating query entry), and an in-place coordinate fill of any cached
     * query results with the same Mapbox id.
     */
    @Synchronized
    fun putResolved(context: Context, dest: NavDestination) {
        if (!dest.hasCoordinates) return
        val id = dest.mapboxId?.takeIf { it.isNotBlank() } ?: normalize(dest.name)
        if (id.isBlank()) return
        val root = readRoot(context)
        val recordObj = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("name", dest.name)
            put("placeName", dest.placeName)
            put("latitude", dest.latitude)
            put("longitude", dest.longitude)
            if (!dest.mapboxId.isNullOrBlank()) put("mapboxId", dest.mapboxId)
        }
        root.put(RESOLVED_PREFIX + id, recordObj)
        val nameKey = RESOLVED_PREFIX + normalize(dest.name)
        if (nameKey != RESOLVED_PREFIX + id) {
            root.put(nameKey, recordObj)
        }

        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith(RESOLVED_PREFIX)) continue
            val entry = root.optJSONObject(k) ?: continue
            val arr = entry.optJSONArray("results") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val matchId = o.optString("mapboxId", "").ifEmpty { null }
                val matchName = normalize(o.optString("name", ""))
                if ((matchId != null && matchId == dest.mapboxId) || matchName == normalize(dest.name)) {
                    o.put("latitude", dest.latitude)
                    o.put("longitude", dest.longitude)
                }
            }
        }
        prune(root)
        writeRoot(context, root)
    }

    /**
     * Number of distinct resolved places stored (Feature 4 health screen).
     * A place is stored under both its id key and its normalized-name key,
     * so records are deduplicated by name + coordinates.
     */
    @Synchronized
    fun resolvedPlaceCount(context: Context): Int = countDistinctResolved(readRoot(context))

    /** Pure core of [resolvedPlaceCount], split out for unit tests. */
    fun countDistinctResolved(root: JSONObject): Int {
        val seen = HashSet<String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!k.startsWith(RESOLVED_PREFIX)) continue
            val rec = root.optJSONObject(k) ?: continue
            val lat = rec.optDouble("latitude", 0.0)
            val lng = rec.optDouble("longitude", 0.0)
            if (lat == 0.0 && lng == 0.0) continue
            seen.add("${rec.optString("name", "")}|$lat,$lng")
        }
        return seen.size
    }

    /**
     * Returns the resolved destination for [idOrName] with real coordinates,
     * or null when this place was never resolved (or its record is gone).
     */
    @Synchronized
    fun findResolved(context: Context, idOrName: String): NavDestination? {
        if (idOrName.isBlank()) return null
        val root = readRoot(context)
        val rec = root.optJSONObject(RESOLVED_PREFIX + idOrName)
            ?: root.optJSONObject(RESOLVED_PREFIX + normalize(idOrName))
        if (rec != null) {
            val lat = rec.optDouble("latitude", 0.0)
            val lng = rec.optDouble("longitude", 0.0)
            if (lat != 0.0 || lng != 0.0) {
                return NavDestination(
                    name = rec.optString("name", ""),
                    placeName = rec.optString("placeName", ""),
                    latitude = lat,
                    longitude = lng,
                    mapboxId = rec.optString("mapboxId", "").takeIf { it.isNotEmpty() }
                )
            }
        }
        // Fall back to scanning cached query results (covers places whose
        // coordinates were filled in place, or stored by put() with coords).
        val norm = normalize(idOrName)
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith(RESOLVED_PREFIX)) continue
            val entry = root.optJSONObject(k) ?: continue
            val results = parseResults(entry.optJSONArray("results")) ?: continue
            for (d in results) {
                if ((d.mapboxId == idOrName || normalize(d.name) == norm) && d.hasCoordinates) return d
            }
        }
        return null
    }

    fun findResolved(context: Context, dest: NavDestination): NavDestination? {
        val byId = dest.mapboxId?.takeIf { it.isNotBlank() }?.let { findResolved(context, it) }
        if (byId != null && byId.hasCoordinates) return byId
        return findResolved(context, dest.name)
    }

    /**
     * Fills real coordinates into a coordinate-less suggestion from its
     * resolved record, using the in-memory or provided cache root.
     */
    fun applyResolved(dest: NavDestination): NavDestination {
        if (dest.hasCoordinates) return dest
        val root = memoryRoot ?: return dest
        return applyResolved(root, dest)
    }

    fun applyResolved(context: Context, dest: NavDestination): NavDestination {
        if (dest.hasCoordinates) return dest
        val root = readRoot(context)
        return applyResolved(root, dest)
    }

    /**
     * Fills real coordinates into a coordinate-less cached suggestion from its
     * resolved record, when one exists.
     */
    fun applyResolved(root: JSONObject, dest: NavDestination): NavDestination {
        if (dest.hasCoordinates) return dest
        val id = dest.mapboxId?.takeIf { it.isNotBlank() }
        val rec = (if (id != null) root.optJSONObject(RESOLVED_PREFIX + id) else null)
            ?: root.optJSONObject(RESOLVED_PREFIX + normalize(dest.name))
            ?: return dest
        val lat = rec.optDouble("latitude", 0.0)
        val lng = rec.optDouble("longitude", 0.0)
        if (lat == 0.0 && lng == 0.0) return dest
        val addr = rec.optString("placeName", "")
        return dest.copy(
            latitude = lat,
            longitude = lng,
            placeName = if (dest.placeName.isNotBlank() && dest.placeName != dest.name) dest.placeName else addr.ifEmpty { dest.placeName }
        )
    }

    private fun parseResults(arr: JSONArray?): List<NavDestination>? {
        if (arr == null) return null
        val out = ArrayList<NavDestination>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                NavDestination(
                    name = o.optString("name", ""),
                    placeName = o.optString("placeName", ""),
                    // Backward compatible: entries lacking lat/lng read as 0.0.
                    latitude = o.optDouble("latitude", 0.0),
                    longitude = o.optDouble("longitude", 0.0),
                    mapboxId = if (o.has("mapboxId")) o.optString("mapboxId") else null,
                    sessionToken = if (o.has("sessionToken")) o.optString("sessionToken") else null,
                    distanceM = if (o.has("distanceM")) o.optDouble("distanceM") else null,
                    source = o.optString("source", "")
                )
            )
        }
        return out
    }

    private fun prune(root: JSONObject) {
        val now = System.currentTimeMillis()
        val entries = mutableListOf<Pair<String, Long>>()
        val keysList = mutableListOf<String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            keysList.add(keys.next())
        }
        for (k in keysList) {
            // Resolved-place records share this root but are not
            // query entries: they are managed by use, not by the
            // entry cap (bug fix, 2026-11-09 — prune used to treat
            // them as ts-less entries, evicting the oldest ones as
            // "age 0" whenever the cache outgrew MAX_ENTRIES, so
            // learned place coordinates silently vanished).
            if (k.startsWith(RESOLVED_PREFIX)) continue
            val ts = root.optJSONObject(k)?.optLong("ts", 0L) ?: 0L
            if (now - ts > MAX_AGE_MS) {
                root.remove(k)
            } else {
                entries.add(k to ts)
            }
        }
        if (entries.size > MAX_ENTRIES) {
            entries.sortBy { it.second }
            val excess = entries.size - MAX_ENTRIES
            for (i in 0 until excess) root.remove(entries[i].first)
        }
    }

    private fun readRoot(context: Context): JSONObject {
        memoryRoot?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CACHE, null)
        val parsed = if (raw.isNullOrEmpty()) {
            JSONObject()
        } else {
            try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        }
        memoryRoot = parsed
        return parsed
    }

    private fun writeRoot(context: Context, root: JSONObject) {
        memoryRoot = root
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CACHE, root.toString()).apply()
    }
}
