package com.parampilot.auto

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-service monthly usage counters (review item D2).
 *
 * Counts actual outbound calls per external service, bucketed by calendar
 * month, so quota questions can be answered from real numbers instead of
 * guesswork. Storage: SharedPreferences "usage_counters", one long per
 * "<key>_<yyyy-MM>". Counting is best-effort: if [init] was never called,
 * every operation is a silent no-op.
 */
object UsageCounters {

    const val MAPBOX_SUGGEST = "mapbox_suggest"
    const val MAPBOX_RETRIEVE = "mapbox_retrieve"
    const val MAPBOX_GEOCODE = "mapbox_geocode"
    const val MAPBOX_DIRECTIONS = "mapbox_directions"
    const val MAPBOX_STATIC = "mapbox_static"
    const val MAPBOX_TILEQUERY = "mapbox_tilequery"
    const val GOOGLE_PLACES_AUTOCOMPLETE = "google_places_autocomplete"
    const val GOOGLE_PLACES_DETAILS = "google_places_details"
    const val GOOGLE_ROADS = "google_roads"
    const val GOOGLE_DIRECTIONS = "google_directions"
    const val GOOGLE_TILES_SESSION = "google_tiles_session"
    const val OSM_OVERPASS = "osm_overpass"
    const val PHOTON_SEARCH = "photon_search"
    const val OSRM_DIRECTIONS = "osrm_directions"
    const val OSM_TILES = "osm_tiles"
    const val SYSTEM_MONITOR = "system_monitor"
    const val GALAXY_POLL = "galaxy_poll"

    private val ALL_KEYS = listOf(
        MAPBOX_SUGGEST,
        MAPBOX_RETRIEVE,
        MAPBOX_GEOCODE,
        MAPBOX_DIRECTIONS,
        MAPBOX_STATIC,
        MAPBOX_TILEQUERY,
        GOOGLE_PLACES_AUTOCOMPLETE,
        GOOGLE_PLACES_DETAILS,
        GOOGLE_ROADS,
        GOOGLE_DIRECTIONS,
        GOOGLE_TILES_SESSION,
        OSM_OVERPASS,
        PHOTON_SEARCH,
        OSRM_DIRECTIONS,
        OSM_TILES,
        SYSTEM_MONITOR,
        GALAXY_POLL
    )

    private const val PREFS = "usage_counters"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Stores the applicationContext SharedPreferences. Idempotent. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                prefs = context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            }
        }
    }

    private fun monthKey(key: String): String =
        "${key}_${SimpleDateFormat("yyyy-MM", Locale.US).format(Date())}"

    // Batched writes (audit finding 51): count() used to hit
    // SharedPreferences on every call — the poll alone counts 2x/s.
    // Deltas accumulate in memory and flush at most every 30 s; a
    // process death can lose the last <30 s of statistics (they are
    // diagnostics, not state).
    private val pendingDeltas = HashMap<String, Long>()
    private var lastFlushMs = 0L
    private const val FLUSH_INTERVAL_MS = 30_000L

    /** Increments the counter for [key] in the current month. Silent no-op if uninitialized. */
    fun count(key: String) {
        try {
            val p = prefs ?: return
            val k = monthKey(key)
            synchronized(pendingDeltas) {
                pendingDeltas[k] = (pendingDeltas[k] ?: 0L) + 1L
                val now = System.currentTimeMillis()
                if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                    lastFlushMs = now
                    val e = p.edit()
                    for ((kk, delta) in pendingDeltas) {
                        e.putLong(kk, p.getLong(kk, 0L) + delta)
                    }
                    pendingDeltas.clear()
                    e.apply()
                }
            }
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
    }

    /** Current month's count for [key]; 0 if uninitialized. */
    fun get(key: String): Long {
        return try {
            val k = monthKey(key)
            val stored = prefs?.getLong(k, 0L) ?: 0L
            val pending = synchronized(pendingDeltas) { pendingDeltas[k] ?: 0L }
            stored + pending
        } catch (_: Exception) {
            0L
        }
    }

    /** All ten counters for the current month. */
    fun snapshot(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>(ALL_KEYS.size)
        for (key in ALL_KEYS) out[key] = get(key)
        return out
    }

    /** Zeroes all ten counters for the current month. */
    fun reset() {
        try {
            val p = prefs ?: return
            // Drop the in-memory deltas too (bug fix, 2026-11-09):
            // counts batched since the last flush were merged back
            // into the zeroed stored values at the next flush (and
            // get() added them on top), so a reset silently
            // resurrected up to 30 s of counts.
            synchronized(pendingDeltas) { pendingDeltas.clear() }
            val editor = p.edit()
            for (key in ALL_KEYS) editor.putLong(monthKey(key), 0L)
            editor.apply()
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
    }
}
