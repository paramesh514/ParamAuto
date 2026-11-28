package com.parampilot.auto

import org.json.JSONObject
import org.maplibre.android.geometry.LatLng

/**
 * Layer 2: parsing of phone-side routing provider responses into
 * the app's route model (Addendum 162, Param 2026-11-28: the
 * 2026-10-31 removal of phone routing is reversed — it was only a
 * comma-testing constraint). One parser per provider in the shared
 * [PhoneSourceOrder]: Google Directions, Mapbox Directions, OSRM.
 *
 * Each parser is total: a malformed or error response yields an
 * empty list, never a partial or invented route — the caller walks
 * the plan to the next provider, and if every provider fails the
 * guidance stays in its degraded straight-line mode with the
 * failure journaled. The step vocabulary produced here is the one
 * the guidance derivation already speaks (comma snapshot steps):
 * types turn / ramp / fork / merge / roundabout / arrive / depart /
 * continue, modifiers left / right / slight left / sharp right /
 * uturn / straight.
 *
 * Pure: JSON in, routes out. The HTTP fetches live in
 * NavigationManager.
 */
object PhoneRoutes {

    // ---- Google Directions ----

    /**
     * Google Directions API JSON: status must be "OK" (anything
     * else — ZERO_RESULTS, REQUEST_DENIED when the Directions API
     * is not enabled on the key, quota errors — yields no route and
     * the plan falls through). Route geometry is the encoded
     * overview polyline; steps carry html_instructions and an
     * optional maneuver string ("turn-left", "ramp-right", ...).
     */
    fun parseGoogleDirections(json: JSONObject): List<RouteOption> {
        if (json.optString("status") != "OK") return emptyList()
        val routesArr = json.optJSONArray("routes") ?: return emptyList()
        val out = ArrayList<RouteOption>()
        for (r in 0 until routesArr.length()) {
            val route = routesArr.optJSONObject(r) ?: continue
            val polyline = route.optJSONObject("overview_polyline")?.optString("points").orEmpty()
            val points = NavigationManager.decodePolyline(polyline)
            if (points.size < 2) continue
            var distanceM = 0.0
            var durationSec = 0.0
            val steps = ArrayList<RouteStep>()
            val legs = route.optJSONArray("legs")
            if (legs != null) {
                for (l in 0 until legs.length()) {
                    val leg = legs.optJSONObject(l) ?: continue
                    distanceM += leg.optJSONObject("distance")?.optDouble("value") ?: 0.0
                    durationSec += leg.optJSONObject("duration")?.optDouble("value") ?: 0.0
                    val legSteps = leg.optJSONArray("steps") ?: continue
                    for (s in 0 until legSteps.length()) {
                        val step = legSteps.optJSONObject(s) ?: continue
                        val instruction = stripHtml(step.optString("html_instructions"))
                        val (type, modifier) = googleManeuver(step.optString("maneuver"))
                        val start = step.optJSONObject("start_location")
                        steps.add(
                            RouteStep(
                                maneuverType = type,
                                maneuverModifier = modifier,
                                street = instruction.substringAfter(" onto ", "").trim(),
                                instruction = instruction,
                                distanceM = step.optJSONObject("distance")?.optDouble("value") ?: 0.0,
                                durationSec = step.optJSONObject("duration")?.optDouble("value") ?: 0.0,
                                location = start?.let { LatLng(it.optDouble("lat"), it.optDouble("lng")) }
                            )
                        )
                    }
                }
            }
            if (steps.none { it.maneuverType == "arrive" }) {
                steps.add(
                    RouteStep(
                        maneuverType = "arrive",
                        maneuverModifier = "arrive",
                        instruction = "Arrive at your destination",
                        location = points.last()
                    )
                )
            }
            out.add(
                routeOptionFromApi(out.size, points, durationSec, distanceM, route.optString("summary"))
                    .copy(steps = steps)
            )
        }
        return out
    }

    /**
     * Google's maneuver strings ("turn-slight-left", "keep-right",
     * "roundabout-left", "uturn-right", "merge", "straight", absent
     * on depart steps) mapped onto the app's type/modifier pair.
     */
    fun googleManeuver(raw: String?): Pair<String, String> {
        val m = raw?.trim()?.lowercase().orEmpty()
        if (m.isEmpty()) return "continue" to "straight"
        val modifier = when {
            m.contains("uturn") -> "uturn"
            m.endsWith("sharp-left") -> "sharp left"
            m.endsWith("slight-left") -> "slight left"
            m.endsWith("left") -> "left"
            m.endsWith("sharp-right") -> "sharp right"
            m.endsWith("slight-right") -> "slight right"
            m.endsWith("right") -> "right"
            else -> ""
        }
        val type = when {
            m.startsWith("ramp") -> "ramp"
            m.startsWith("roundabout") -> "roundabout"
            m.startsWith("merge") -> "merge"
            m.startsWith("keep") -> "fork"
            m.startsWith("ferry") -> "continue"
            m == "straight" -> "continue"
            else -> "turn"
        }
        return type to modifier
    }

    /** Google instructions arrive as HTML ("Turn <b>left</b> onto
     *  <b>Main St</b>"); the banner shows plain text. */
    fun stripHtml(html: String): String {
        val noTags = html.replace(Regex("<[^>]*>"), " ")
        return noTags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ---- Mapbox Directions ----

    /**
     * Mapbox Directions v5 JSON (geometries=geojson, steps=true,
     * banner_instructions=true): geometry coordinates are
     * [lng, lat]; steps carry maneuver {type, modifier, location}
     * and the road name; the spoken instruction is the first banner
     * instruction's primary text when present.
     */
    fun parseMapboxDirections(json: JSONObject): List<RouteOption> {
        val routesArr = json.optJSONArray("routes") ?: return emptyList()
        val out = ArrayList<RouteOption>()
        for (r in 0 until routesArr.length()) {
            val route = routesArr.optJSONObject(r) ?: continue
            val points = geojsonPoints(route.optJSONObject("geometry")) ?: continue
            if (points.size < 2) continue
            val steps = ArrayList<RouteStep>()
            var summary = ""
            val legs = route.optJSONArray("legs")
            if (legs != null) {
                for (l in 0 until legs.length()) {
                    val leg = legs.optJSONObject(l) ?: continue
                    val legSummary = leg.optString("summary")
                    if (summary.isEmpty()) summary = legSummary
                    val legSteps = leg.optJSONArray("steps") ?: continue
                    for (s in 0 until legSteps.length()) {
                        val step = legSteps.optJSONObject(s) ?: continue
                        steps.add(mapboxStyleStep(step, bannerText = true))
                    }
                }
            }
            out.add(
                routeOptionFromApi(
                    out.size, points,
                    route.optDouble("duration"), route.optDouble("distance"),
                    summary
                ).copy(steps = steps)
            )
        }
        return out
    }

    // ---- OSRM ----

    /**
     * OSRM route response (router.project-osrm.org, steps=true,
     * geometries=geojson): same step shape as Mapbox minus banner
     * instructions; code must be "Ok".
     */
    fun parseOsrmRoutes(json: JSONObject): List<RouteOption> {
        if (json.optString("code") != "Ok") return emptyList()
        val routesArr = json.optJSONArray("routes") ?: return emptyList()
        val out = ArrayList<RouteOption>()
        for (r in 0 until routesArr.length()) {
            val route = routesArr.optJSONObject(r) ?: continue
            val points = geojsonPoints(route.optJSONObject("geometry")) ?: continue
            if (points.size < 2) continue
            val steps = ArrayList<RouteStep>()
            var summary = ""
            val legs = route.optJSONArray("legs")
            if (legs != null) {
                for (l in 0 until legs.length()) {
                    val leg = legs.optJSONObject(l) ?: continue
                    val legSummary = leg.optString("summary")
                    if (summary.isEmpty()) summary = legSummary
                    val legSteps = leg.optJSONArray("steps") ?: continue
                    for (s in 0 until legSteps.length()) {
                        val step = legSteps.optJSONObject(s) ?: continue
                        steps.add(mapboxStyleStep(step, bannerText = false))
                    }
                }
            }
            out.add(
                routeOptionFromApi(
                    out.size, points,
                    route.optDouble("duration"), route.optDouble("distance"),
                    summary
                ).copy(steps = steps)
            )
        }
        return out
    }

    /** One Mapbox/OSRM step: both providers share the maneuver
     *  object shape ({type, modifier, location: [lng, lat]}). */
    private fun mapboxStyleStep(step: JSONObject, bannerText: Boolean): RouteStep {
        val maneuver = step.optJSONObject("maneuver")
        val type = maneuver?.optString("type")?.ifEmpty { "turn" } ?: "turn"
        val modifier = maneuver?.optString("modifier") ?: ""
        val name = step.optString("name").takeIf { it != "-" } ?: ""
        val banner = if (bannerText) {
            step.optJSONArray("banner_instructions")?.optJSONObject(0)
                ?.optJSONObject("primary")?.optString("text")?.ifEmpty { null }
        } else {
            null
        }
        val locArr = maneuver?.optJSONArray("location")
        return RouteStep(
            maneuverType = type,
            maneuverModifier = modifier,
            street = name,
            instruction = banner ?: instructionFor(type, modifier, name),
            distanceM = step.optDouble("distance"),
            durationSec = step.optDouble("duration"),
            location = locArr?.let { LatLng(it.optDouble(1), it.optDouble(0)) }
        )
    }

    /** GeoJSON LineString coordinates ([lng, lat] pairs) → points. */
    private fun geojsonPoints(geometry: JSONObject?): List<LatLng>? {
        val coords = geometry?.optJSONArray("coordinates") ?: return null
        val points = ArrayList<LatLng>(coords.length())
        for (i in 0 until coords.length()) {
            val pair = coords.optJSONArray(i) ?: continue
            points.add(LatLng(pair.optDouble(1), pair.optDouble(0)))
        }
        return points
    }

    /**
     * A readable instruction for a provider step that carries no
     * text of its own (OSRM, or Mapbox without banner data): the
     * banner primarily renders type + modifier + street, so this
     * only has to read naturally as the step's caption.
     */
    fun instructionFor(type: String, modifier: String, name: String): String {
        val t = type.lowercase().trim()
        val mod = modifier.lowercase().trim()
        val onto = if (name.isNotBlank()) " onto $name" else ""
        return when {
            t == "arrive" -> "Arrive at your destination"
            t == "depart" -> if (name.isNotBlank()) "Head out on $name" else "Depart"
            mod == "uturn" -> "Make a U-turn$onto"
            mod.isNotEmpty() && mod != "straight" -> {
                val verb = when (t) {
                    "roundabout", "rotary" -> "At the roundabout, turn"
                    "fork" -> "Keep"
                    "merge" -> "Merge"
                    "on ramp" -> "Take the ramp"
                    "off ramp" -> "Take the exit"
                    else -> "Turn"
                }
                "$verb $mod$onto"
            }
            t == "merge" -> "Merge$onto"
            name.isNotBlank() -> "Continue on $name"
            else -> "Continue"
        }
    }
}

/**
 * Layer 2: the phone-route reroute decision (Addendum 162). While
 * phone guidance follows a phone-fetched route, a fix that lands
 * far from the route's polyline means the driver left it — fetch a
 * fresh route from here. The thresholds are deliberately coarse:
 * GPS noise and parallel frontage roads stay under the deviation
 * bound, the cooldown keeps a genuinely lost driver from hammering
 * the providers, and inside the arrival zone the arrival flow —
 * not a reroute — ends the trip.
 */
object PhoneReroute {

    /** Deviation from the route polyline that counts as off-route. */
    const val OFF_ROUTE_THRESHOLD_M = 60.0

    /** Minimum spacing between two reroute fetches. */
    const val REROUTE_COOLDOWN_MS = 30_000L

    /** Inside this distance to the destination, never reroute. */
    const val ARRIVAL_ZONE_M = 100.0

    /** After a failed fetch attempt, wait this long before another. */
    const val FETCH_RETRY_BACKOFF_MS = 20_000L

    /**
     * Distance from a position to the nearest point on the route
     * polyline, in meters — point-to-segment in the GPS-local
     * equirectangular projection (the same approximation
     * [RouteProgress] uses). MAX_VALUE for an empty polyline.
     */
    fun distanceToRouteM(points: List<LatLng>, lat: Double, lng: Double): Double {
        if (points.isEmpty()) return Double.MAX_VALUE
        if (points.size == 1) {
            return GuidanceMath.haversineM(lat, lng, points[0].latitude, points[0].longitude)
        }
        val earthR = 6371000.0
        val cosLat = kotlin.math.cos(Math.toRadians(lat))
        fun x(lon: Double) = earthR * cosLat * Math.toRadians(lon - lng)
        fun y(la: Double) = earthR * Math.toRadians(la - lat)
        var best = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val ax = x(points[i].longitude)
            val ay = y(points[i].latitude)
            val dx = x(points[i + 1].longitude) - ax
            val dy = y(points[i + 1].latitude) - ay
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / lenSq
            val tc = t.coerceIn(0.0, 1.0)
            val px = ax + tc * dx
            val py = ay + tc * dy
            val d = kotlin.math.hypot(px, py)
            if (d < best) best = d
        }
        return best
    }

    /** Pure reroute decision from the measured state. */
    fun shouldReroute(
        hasRoute: Boolean,
        deviationM: Double,
        distanceToDestM: Double,
        nowMs: Long,
        lastRerouteAtMs: Long
    ): Boolean {
        if (!hasRoute) return false
        if (deviationM <= OFF_ROUTE_THRESHOLD_M) return false
        if (distanceToDestM <= ARRIVAL_ZONE_M) return false
        if (lastRerouteAtMs > 0L && nowMs - lastRerouteAtMs < REROUTE_COOLDOWN_MS) return false
        return true
    }
}
