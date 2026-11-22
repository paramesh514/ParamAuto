package com.parampilot.auto

import android.content.Context
import org.json.JSONObject

/**
 * Offline destination queue (Feature 10): ONE destination the user chose
 * while the comma was unreachable, persisted as JSON in the
 * "comma_client" SharedPreferences so it survives restarts. When the
 * comma reconnects, the entry flushes through the normal confirmed send
 * flows; the queue never presents the destination as accepted before
 * that confirmation arrives.
 *
 * Two entry kinds:
 * - COORDS — a destination with real coordinates; flushed through the
 *   SG coordinate select flow.
 * - COMMA_PLACE — a temporary comma search suggestion (coordinate-less;
 *   Round-2 Fix B). Flushed by re-running the recorded comma search on
 *   reconnect, matching the suggestion again (by its stable place id,
 *   else by normalized name), and running the same selectPlace flow a
 *   live tap uses. Records written before kinds existed carry no
 *   "kind" field and are read as COORDS.
 *
 * While the comma is connected, a queued entry that the comma has
 * not accepted is retried every [FLUSH_RETRY_INTERVAL_MS] (Param,
 * 2026-11-21) until it is accepted or the user cancels it. While a
 * queue entry exists, no NEW destination is accepted anywhere in the
 * app — the queued one must be sent or cancelled first (Param,
 * 2026-11-21).
 */
object DestinationQueue {

    private const val PREFS_NAME = "comma_client"
    private const val KEY_QUEUED_DESTINATION = "queued_destination_json"

    /** A not-accepted queued destination is retried on this interval
     * while the comma is connected (Param, 2026-11-21). */
    const val FLUSH_RETRY_INTERVAL_MS = 15_000L

    enum class Kind { COORDS, COMMA_PLACE }

    /** One queued entry, exactly as persisted. */
    data class Entry(
        val kind: Kind,
        val name: String,
        val placeName: String,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val mapboxId: String? = null,
        val query: String? = null,
        val queuedAtMs: Long = 0L
    ) {
        /** The queued destination as displayed (the chip) and restored. */
        fun displayDestination(): NavDestination = when (kind) {
            Kind.COORDS -> NavDestination(
                name = name, placeName = placeName,
                latitude = latitude, longitude = longitude, source = "comma"
            )
            Kind.COMMA_PLACE -> NavDestination(
                name = name, placeName = placeName,
                mapboxId = mapboxId, source = "comma"
            )
        }

        /** The flushable coordinate destination, for COORDS entries only. */
        fun coordsDestination(): NavDestination? =
            if (kind == Kind.COORDS) displayDestination() else null
    }

    /** When the most recent flush failed (epoch ms); drives the
     * [FLUSH_RETRY_INTERVAL_MS] retry cadence. Reset on save/clear/success. */
    @Volatile
    var lastFlushFailureAtMs: Long = 0L
        private set

    // ---- Pure (de)serialization + decision (unit-tested) ----

    fun toJson(dest: NavDestination, atMs: Long): JSONObject = toJson(
        Entry(
            kind = Kind.COORDS,
            name = dest.name,
            placeName = dest.placeName,
            latitude = dest.latitude,
            longitude = dest.longitude,
            queuedAtMs = atMs
        )
    )

    fun toJson(entry: Entry): JSONObject = JSONObject().apply {
        put("name", entry.name)
        put("placeName", entry.placeName)
        put("queuedAtMs", entry.queuedAtMs)
        when (entry.kind) {
            Kind.COORDS -> {
                put("kind", "coords")
                put("latitude", entry.latitude)
                put("longitude", entry.longitude)
            }
            Kind.COMMA_PLACE -> {
                put("kind", "commaPlace")
                entry.mapboxId?.let { put("mapboxId", it) }
                entry.query?.let { put("query", it) }
            }
        }
    }

    /**
     * Parses a stored record into a typed [Entry]; null when the record
     * is missing or unusable. A missing (or unrecognized) "kind" reads
     * as COORDS, so records written before kinds existed keep working.
     * COORDS validation is the original rule set (real, in-range,
     * non-0,0 coordinates and a name); COMMA_PLACE requires a non-blank
     * name and at least one of mapboxId / query to re-find the place by.
     */
    fun parseEntry(json: JSONObject?): Entry? {
        if (json == null) return null
        val atMs = json.optLong("queuedAtMs", 0L)
        val name = json.optString("name", "").ifEmpty { json.optString("placeName", "") }
        val placeName = json.optString("placeName", "").ifEmpty { name }
        return when (json.optString("kind", "coords")) {
            "commaPlace" -> {
                if (name.isBlank()) return null
                val id = json.optString("mapboxId", "").ifBlank { null }
                val query = json.optString("query", "").ifBlank { null }
                if (id == null && query == null) return null
                Entry(
                    kind = Kind.COMMA_PLACE,
                    name = name,
                    placeName = placeName,
                    mapboxId = id,
                    query = query,
                    queuedAtMs = atMs
                )
            }
            else -> {
                val lat = json.optDouble("latitude", Double.NaN)
                val lng = json.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) return null
                if (lat == 0.0 && lng == 0.0) return null
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
                if (name.isEmpty()) return null
                Entry(
                    kind = Kind.COORDS,
                    name = name,
                    placeName = placeName,
                    latitude = lat,
                    longitude = lng,
                    queuedAtMs = atMs
                )
            }
        }
    }

    /** Rebuilds the queued COORDS destination; null for COMMA_PLACE records and unusable ones. */
    fun fromJson(json: JSONObject?): NavDestination? = parseEntry(json)?.coordsDestination()

    /** When the entry was queued (epoch ms), or 0 when unknown. */
    fun queuedAtMs(json: JSONObject?): Long = json?.optLong("queuedAtMs", 0L) ?: 0L

    /**
     * Flush only when the comma is connected and an entry exists. The
     * first attempt is immediate; after a failure the next attempt
     * waits [FLUSH_RETRY_INTERVAL_MS] (Param, 2026-11-21: "Once it is
     * not accepted try every 15 seconds").
     */
    fun shouldFlush(
        commaFound: Boolean,
        hasQueued: Boolean,
        lastFailureAtMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean =
        commaFound && hasQueued &&
            (lastFailureAtMs == 0L || nowMs - lastFailureAtMs >= FLUSH_RETRY_INTERVAL_MS)

    /**
     * True when [dest] is a NEW destination while [queued] is still
     * waiting: new destinations are not accepted until the queue is
     * cleared (Param, 2026-11-21). Re-selecting the queued
     * destination itself is not blocked.
     */
    fun blocksNewDestination(queued: NavDestination?, dest: NavDestination): Boolean =
        queued != null && !queued.isSameAs(dest)

    /**
     * Finds a COMMA_PLACE entry's place among fresh comma search
     * results: by the stored place id first (stable across sessions),
     * else by normalized-name equality. Null when nothing matches.
     */
    fun matchCommaPlace(entry: Entry, results: List<NavDestination>): NavDestination? {
        if (entry.kind != Kind.COMMA_PLACE) return null
        entry.mapboxId?.takeIf { it.isNotBlank() }?.let { id ->
            results.firstOrNull { it.mapboxId == id }?.let { return it }
        }
        val wanted = normalizePlaceName(entry.name)
        if (wanted.isEmpty()) return null
        return results.firstOrNull { normalizePlaceName(it.name) == wanted }
    }

    /**
     * The ONE queued-indicator text (Addendum 122), shared by the top
     * bar chip and the nav banner row so both surfaces say the same
     * thing: queued-offline vs out-of-sync (comma reached but the
     * destination not accepted — connect the comma to the Internet
     * so it gets navigation details).
     */
    fun label(
        dest: NavDestination,
        outOfSync: Boolean,
        networks: GalaxyNetworks? = null
    ): String =
        if (outOfSync) {
            val wifiDetail = when {
                networks?.connectedWifiName() != null ->
                    "comma is on Wi-Fi '${networks.connectedWifiName()}' — "
                networks?.link == "none" ->
                    "comma has no Wi-Fi connection — "
                networks?.link == "cellular" ->
                    "comma is on cellular, not Wi-Fi — "
                else -> ""
            }
            "Out of sync: ${dest.name} not accepted — ${wifiDetail}connect the comma to the Internet"
        } else {
            "Queued: ${dest.name} — will send when the comma connects"
        }

    /** Lowercase, punctuation-collapsed form for name matching. */
    fun normalizePlaceName(raw: String): String =
        raw.lowercase()
            .map { ch -> if (ch.isLetterOrDigit()) ch else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    // ---- Persistence ----

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveEntry(context: Context, entry: Entry) {
        val record = toJson(entry.copy(queuedAtMs = System.currentTimeMillis()))
        prefs(context).edit().putString(KEY_QUEUED_DESTINATION, record.toString()).apply()
        lastFlushFailureAtMs = 0L
    }

    fun save(context: Context, dest: NavDestination) {
        saveEntry(
            context,
            Entry(
                kind = Kind.COORDS,
                name = dest.name,
                placeName = dest.placeName,
                latitude = dest.latitude,
                longitude = dest.longitude
            )
        )
    }

    /** Queues a temporary comma suggestion for a reconnect flush (Fix B). */
    fun saveCommaPlace(
        context: Context,
        name: String,
        placeName: String,
        mapboxId: String?,
        query: String?
    ) {
        saveEntry(
            context,
            Entry(
                kind = Kind.COMMA_PLACE,
                name = name,
                placeName = placeName,
                mapboxId = mapboxId?.ifBlank { null },
                query = query?.ifBlank { null }
            )
        )
    }

    fun loadEntry(context: Context): Entry? {
        val raw = prefs(context).getString(KEY_QUEUED_DESTINATION, null) ?: return null
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
        return parseEntry(json)
    }

    fun load(context: Context): NavDestination? = loadEntry(context)?.coordsDestination()

    /** True while any entry is persisted in the queue. */
    fun hasPending(context: Context): Boolean = loadEntry(context) != null

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_QUEUED_DESTINATION).apply()
        lastFlushFailureAtMs = 0L
    }

    fun noteFlushFailure() {
        lastFlushFailureAtMs = System.currentTimeMillis()
    }

    fun noteFlushSuccess() {
        lastFlushFailureAtMs = 0L
    }
}
