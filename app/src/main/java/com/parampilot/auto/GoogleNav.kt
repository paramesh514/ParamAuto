package com.parampilot.auto

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Layer 2: the comma's navigation provider settings — Mapbox or Google
 * for map tiles, routes and place search (Dom, Nov 2026:
 * navigation/owner.py CONFIGURABLE = enabled / token / gpsSource /
 * mapProvider / routeProvider / googleKey, written through
 * POST /api/navigation/action {"action":"configure","patch":{…}}).
 *
 * Google on the comma runs on the user's own Google Cloud key, saved
 * on the comma only — the status snapshot reports hasGoogleKey, never
 * the key itself. The comma blocks Google spend at 99% of the free
 * allowances, and whenever Google fails or its budget is spent,
 * Mapbox routes the trip instead; the snapshot's googleMap /
 * googleRoutes "failure" fields carry the comma's own explanation,
 * surfaced verbatim by the UI.
 *
 * Older commas send none of these fields: [parse] returns null and
 * the settings UI says so instead of guessing at provider state.
 */
object GoogleNav {

    const val MAPBOX = "mapbox"
    const val GOOGLE = "google"

    /** Display name for a provider value from the snapshot. */
    fun providerLabel(provider: String): String =
        if (provider == GOOGLE) "Google" else "Mapbox"

    /**
     * The map provider a Settings switch state stands for: ON means
     * the comma draws its map tiles from Google, OFF from Mapbox.
     */
    fun mapProviderForSwitch(checked: Boolean): String =
        if (checked) GOOGLE else MAPBOX

    /** True when the comma's current map provider is Google. */
    fun isGoogleMap(state: State?): Boolean = state?.mapProvider == GOOGLE

    /**
     * Mirrors owner.py valid_google_key: an empty key is valid (it
     * clears the saved key); otherwise 20–128 chars of [A-Za-z0-9_-].
     */
    fun isValidGoogleKey(key: String): Boolean =
        key.isEmpty() || (key.length in 20..128 && key.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
        })

    /** One Google budget, as google_tiles.GoogleBudget.snapshot() reports it. */
    data class Budget(
        val monthUsed: Int,
        val monthLimit: Int,
        val monthFree: Int,
        val dayUsed: Int,
        val dayLimit: Int?
    ) {
        /** "123 of 4,950 used this month (2%)" — the limit is the comma's own 99% stop. */
        fun label(): String {
            val pct = monthFraction()?.let {
                " (${(it * 100).roundToInt()}%)"
            } ?: ""
            return "$monthUsed of $monthLimit used this month$pct"
        }

        /** Fraction of the monthly free allowance used; null when unbounded. */
        fun monthFraction(): Double? =
            if (monthLimit > 0) monthUsed.toDouble() / monthLimit.toDouble() else null

        /** Fraction of the daily free allowance used; null when there is no daily cap. */
        fun dayFraction(): Double? =
            if (dayLimit != null && dayLimit > 0) {
                dayUsed.toDouble() / dayLimit.toDouble()
            } else {
                null
            }

        /** The tighter of the month/day fractions — either cap stops Google. */
        fun maxFraction(): Double? =
            listOfNotNull(monthFraction(), dayFraction()).maxOrNull()
    }

    /**
     * Param's budget rule (2026-11-18): "Get limits from Google —
     * once any limit reaches 95% switch to Mapbox on both App and
     * comma." The comma's own hard stop is 99%; this 95% switch
     * happens first, on purpose, so neither side rides the edge.
     */
    const val BUDGET_SWITCH_THRESHOLD = 0.95

    fun parseBudget(json: JSONObject?): Budget? {
        if (json == null || !json.has("monthUsed") || !json.has("monthLimit")) return null
        return Budget(
            monthUsed = json.optInt("monthUsed", 0),
            monthLimit = json.optInt("monthLimit", 0),
            monthFree = json.optInt("monthFree", 0),
            dayUsed = json.optInt("dayUsed", 0),
            dayLimit = if (json.has("dayLimit") && !json.isNull("dayLimit")) {
                json.optInt("dayLimit")
            } else {
                null
            }
        )
    }

    /**
     * One Mapbox counter, as mapbox_budget.read_usage reports it:
     * {used, limit, free} for the current UTC month — "limit" is the
     * comma's own 99% stop line, "free" the full free allowance.
     * Mapped onto [Budget] so both providers render the same way.
     */
    fun parseMapboxBudget(json: JSONObject?): Budget? {
        if (json == null || !json.has("used") || !json.has("free")) return null
        val free = json.optInt("free", 0)
        return Budget(
            monthUsed = json.optInt("used", 0),
            monthLimit = json.optInt("limit", free),
            monthFree = free,
            dayUsed = 0,
            dayLimit = null
        )
    }

    /** The comma's own Mapbox counts for this month (snapshot "mapboxUsage"). */
    data class MapboxUsage(
        val searchSessions: Budget?,
        val geocoding: Budget?,
        val staticTiles: Budget?,
        val directions: Budget?
    )

    fun parseMapboxUsage(json: JSONObject?): MapboxUsage? {
        if (json == null) return null
        val usage = MapboxUsage(
            searchSessions = parseMapboxBudget(json.optJSONObject("searchSessions")),
            geocoding = parseMapboxBudget(json.optJSONObject("geocoding")),
            staticTiles = parseMapboxBudget(json.optJSONObject("staticTiles")),
            directions = parseMapboxBudget(json.optJSONObject("directions"))
        )
        return if (usage.searchSessions == null && usage.geocoding == null &&
            usage.staticTiles == null && usage.directions == null
        ) {
            null
        } else {
            usage
        }
    }

    /** The comma's provider state, from one status snapshot. */
    data class State(
        val mapProvider: String,
        val routeProvider: String,
        val hasGoogleKey: Boolean,
        val mapUsage: Budget?,
        /** The comma's own words for why Google maps last failed; null when fine. */
        val mapFailure: String?,
        val routesUsage: Budget?,
        val routesEssentials: Budget?,
        /** The comma's own words for why Google routing last failed; null when fine. */
        val routesFailure: String?,
        val placesUsage: Budget?,
        val placesSuggestions: Budget?,
        /** The comma's Mapbox counts this month; null when not reported. */
        val mapboxUsage: MapboxUsage? = null
    ) {
        /** Google routing/search only actually run with a key saved. */
        val googleRoutingActive: Boolean get() = routeProvider == GOOGLE && hasGoogleKey

        /** Any Google budget the snapshot reported, for the usage section. */
        val hasAnyBudget: Boolean
            get() = mapUsage != null || routesUsage != null || routesEssentials != null ||
                placesUsage != null || placesSuggestions != null

        /** The highest budget fraction across every reported budget; null when none. */
        val maxBudgetFraction: Double?
            get() = listOfNotNull(
                mapUsage, routesUsage, routesEssentials, placesUsage, placesSuggestions
            ).mapNotNull { it.maxFraction() }.maxOrNull()

        /** True when any Google free limit has reached the switch threshold. */
        fun budgetSwitchDue(threshold: Double = BUDGET_SWITCH_THRESHOLD): Boolean =
            (maxBudgetFraction ?: 0.0) >= threshold
    }

    /**
     * Parses the snapshot's provider fields. Returns null when the
     * snapshot carries no mapProvider/routeProvider (an older comma) —
     * the caller keeps its last known state, exactly like mapboxUsage.
     */
    fun parse(json: JSONObject): State? {
        if (!json.has("mapProvider") || !json.has("routeProvider")) return null
        val googleMap = json.optJSONObject("googleMap")
        val googleRoutes = json.optJSONObject("googleRoutes")
        val googlePlaces = json.optJSONObject("googlePlaces")
        return State(
            mapProvider = json.optString("mapProvider", MAPBOX),
            routeProvider = json.optString("routeProvider", MAPBOX),
            hasGoogleKey = json.optBoolean("hasGoogleKey", false),
            mapUsage = parseBudget(googleMap?.optJSONObject("usage")),
            mapFailure = googleMap?.optString("failure", "")?.ifEmpty { null },
            routesUsage = parseBudget(googleRoutes?.optJSONObject("usage")),
            routesEssentials = parseBudget(googleRoutes?.optJSONObject("essentials")),
            routesFailure = googleRoutes?.optString("failure", "")?.ifEmpty { null },
            placesUsage = parseBudget(googlePlaces?.optJSONObject("usage")),
            placesSuggestions = parseBudget(googlePlaces?.optJSONObject("suggestions")),
            mapboxUsage = parseMapboxUsage(json.optJSONObject("mapboxUsage"))
        )
    }

    /** The "configure" patch for a provider change; a null side is left as-is. */
    fun providerPatch(mapProvider: String?, routeProvider: String?): JSONObject {
        val patch = JSONObject()
        if (mapProvider != null) patch.put("mapProvider", mapProvider)
        if (routeProvider != null) patch.put("routeProvider", routeProvider)
        return patch
    }

    /** The "configure" patch that saves a Google key ("" clears it). */
    fun keyPatch(key: String): JSONObject = JSONObject().put("googleKey", key)

    /** The "configure" patch that saves the comma's Mapbox token ("" clears it). */
    fun tokenPatch(token: String): JSONObject = JSONObject().put("token", token)

    /** One line of a "testGoogleKey" result: a Google API that was tried. */
    data class KeyTestLine(val label: String, val ok: Boolean, val message: String?)

    /**
     * Parses a testGoogleKey response: its googleKeyTest object is the
     * Maps tiles result ({ok, failure, message}) with "routes" and
     * "places" sub-results of the same shape (owner.test_google_key).
     */
    fun parseKeyTest(response: JSONObject?): List<KeyTestLine> {
        val test = response?.optJSONObject("googleKeyTest") ?: return emptyList()
        fun line(label: String, obj: JSONObject?): KeyTestLine? {
            if (obj == null || !obj.has("ok")) return null
            return KeyTestLine(
                label = label,
                ok = obj.optBoolean("ok", false),
                message = obj.optString("message", "").ifEmpty { null }
            )
        }
        return listOfNotNull(
            line("Map tiles", test),
            line("Routes", test.optJSONObject("routes")),
            line("Places search", test.optJSONObject("places"))
        )
    }
}
