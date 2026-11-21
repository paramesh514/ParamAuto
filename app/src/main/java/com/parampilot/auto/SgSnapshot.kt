package com.parampilot.auto

import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng

/**
 * Layer 2: THE parse of an SG navigation status snapshot's
 * destination + routes — the one and only place that JSON becomes
 * a [Parsed].
 *
 * Why this exists (audit findings 1 + 10, 2026-11-06): this parse
 * used to exist twice, near-verbatim — CommaClient.publishSgRoute
 * and NavigationManager.applySgSnapshotInternal — and the copies
 * had already drifted (the poll's copy derived phone steps, the
 * snapshot copy added arrival/trip bookkeeping), the same failure
 * shape as the lane-flicker bug one level down. Both consumers now
 * parse through here and apply only their own side-effects to the
 * shared result: CommaClient adds derived steps and publishes;
 * NavigationManager adds arrival / End-Route / trip tracking.
 *
 * Routes carry NO steps here: step derivation is a phone-side
 * invention gated by the phone-route toggle, applied by the
 * consumer that wants it.
 */
object SgSnapshot {

    /** Snapshot statuses that mean the trip is over. */
    val ENDED_STATUSES = setOf(
        "nodestination", "disabled", "arrived", "idle",
        "routeunavailable", "cancelled"
    )

    data class Parsed(
        /** The snapshot's destination, or null when it carries none
         *  (or none with valid coordinates). */
        val destination: NavDestination?,
        val hasValidDest: Boolean,
        /** Lowercased snapshot status string ("" when absent). */
        val status: String,
        /** True when there is no valid destination or the status is
         *  one of [ENDED_STATUSES]. */
        val navEnded: Boolean,
        /** `selectedRoute` from the snapshot (0 when absent). */
        val selectedRouteIndex: Int,
        /** Alternatives, or the primary-route fallback; empty when
         *  the snapshot carries no route geometry. */
        val routes: List<RouteOption>,
        /** True when a route or alternatives array is present and
         *  non-empty (regardless of whether points parsed). */
        val hasRouteGeometry: Boolean,
        val routeUnchanged: Boolean,
        val revision: String?,
        val routeKey: String?
    )

    fun revisionOf(json: JSONObject): String? =
        json.optString("revision", "").ifEmpty { null }

    fun routeKeyOf(json: JSONObject): String? =
        json.optString("routeKey", "").ifEmpty { null }

    /**
     * The valid coordinate points of a snapshot geometry array:
     * entries without numeric latitude/longitude are skipped.
     * Shared by the alternatives, selected-route fallback and
     * primary geometry reads (extracted 2026-11-08; the loop was
     * written out three times in [parse]).
     */
    private fun pointsFrom(arr: JSONArray?): ArrayList<LatLng> {
        val points = ArrayList<LatLng>()
        if (arr == null) return points
        for (j in 0 until arr.length()) {
            val pt = arr.optJSONObject(j) ?: continue
            val lat = pt.optDouble("latitude", Double.NaN)
            val lng = pt.optDouble("longitude", Double.NaN)
            if (!lat.isNaN() && !lng.isNaN()) points.add(LatLng(lat, lng))
        }
        return points
    }

    fun parse(json: JSONObject): Parsed {
        val destObj = json.optJSONObject("destination")
        val dLat = destObj?.optDouble(
            "latitude", destObj.optDouble("lat", Double.NaN)
        ) ?: Double.NaN
        val dLng = destObj?.optDouble(
            "longitude", destObj.optDouble("lng", Double.NaN)
        ) ?: Double.NaN
        val hasValidDest = destObj != null && !dLat.isNaN() && !dLng.isNaN()
        val statusStr = json.optString("status", "").lowercase()
        val navEnded = !hasValidDest || statusStr in ENDED_STATUSES

        val destination = if (hasValidDest && destObj != null) {
            val dName = destObj.optString("name", "")
                .ifEmpty { destObj.optString("title", "Destination") }
            val dAddr = destObj.optString("address", "")
                .ifEmpty { destObj.optString("placeName", dName) }
            NavDestination(dName, dAddr, dLat, dLng)
        } else {
            null
        }

        val routeArr = json.optJSONArray("route")
        val alts = json.optJSONArray("alternatives")
        val selectedIdx = json.optInt("selectedRoute", 0)
        val routesList = mutableListOf<RouteOption>()

        if (alts != null && alts.length() > 0) {
            for (i in 0 until alts.length()) {
                val a = alts.optJSONObject(i) ?: continue
                val idx = a.optInt("index", i)
                val durSec = a.optDouble("durationSeconds", 0.0)
                val distM = a.optDouble("distanceMeters", 0.0)
                val geomArr = a.optJSONArray("geometry") ?: a.optJSONArray("route")
                val altPoints = if (geomArr != null && geomArr.length() > 0) {
                    pointsFrom(geomArr)
                } else if (idx == selectedIdx && routeArr != null) {
                    pointsFrom(routeArr)
                } else {
                    ArrayList()
                }
                if (altPoints.isNotEmpty()) {
                    routesList.add(
                        routeOptionFromApi(
                            idx, altPoints, durSec, distM,
                            if (idx == 0) "Fastest route" else "Alternative $idx"
                        )
                    )
                }
            }
        }

        if (routesList.isEmpty() && routeArr != null && routeArr.length() > 0) {
            val points = pointsFrom(routeArr)
            if (points.isNotEmpty()) {
                // Totals for the selected route are UNKNOWN here:
                // RouteOption.durationMin/distanceMiles are trip
                // TOTALS everywhere else (the alternatives above
                // carry the comma's full-route durationSeconds /
                // distanceMeters), but this fallback used to fill
                // them with the instruction's REMAINING values
                // (bug fix, 2026-11-09) — the route row then shrank
                // as the car drove, and trip-total readers
                // (noteTripRouteTotals) recorded a mid-trip
                // remainder as the trip's totals. Guidance reads
                // the remaining* fields from the instruction
                // directly (SgInstruction), never from this entry,
                // so 0/0 = "device provided none" is the honest
                // value — never fabricate a 1-minute trip.
                routesList.add(
                    routeOptionFromApi(
                        selectedIdx, points, 0.0, 0.0, "Comma route"
                    )
                )
            }
        }

        return Parsed(
            destination = destination,
            hasValidDest = hasValidDest,
            status = statusStr,
            navEnded = navEnded,
            selectedRouteIndex = selectedIdx,
            routes = routesList,
            hasRouteGeometry = (routeArr != null && routeArr.length() > 0) ||
                (alts != null && alts.length() > 0),
            routeUnchanged = json.optBoolean("routeUnchanged", false),
            revision = revisionOf(json),
            routeKey = routeKeyOf(json)
        )
    }
}
