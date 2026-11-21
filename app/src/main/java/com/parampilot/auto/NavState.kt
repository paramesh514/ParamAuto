package com.parampilot.auto

import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng

/** Mirrors the JSON packet broadcast by the comma's nav_broadcast process (UDP 28941). */
data class LaneInfo(
    val directions: List<String>,
    val active: Boolean,
)

data class SpeedLimitInfo(
    val limitMs: Double = 0.0,
    val offsetMs: Double = 0.0,
    val source: String = "",
)

/** Live power state (car 12V battery voltage feed via Galaxy /api/system/monitor). */
data class PowerState(
    val voltageV: Double,
    val currentA: Double = 0.0,
    val fanRpm: Int = 0,
    val pandaType: String = "",
)

data class NavManeuver(
    val valid: Boolean = false,
    val type: String = "",
    val modifier: String = "",
    val distanceM: Double = 0.0,
    /** Comma-computed totals for the whole remaining trip (SG instruction
     *  remainingDistanceMeters / remainingDurationSeconds); 0 = not guiding. */
    val remainingDistanceM: Double = 0.0,
    val remainingDurationSec: Double = 0.0,
    val street: String = "",
    val laneCount: Int = 0,
    val activeLaneDirection: String = "",
    val activeLaneIndex: Int = -1,
    val sameSideLaneCount: Int = 0,
    val lanes: List<LaneInfo> = emptyList(),
)

data class GpsPosition(
    val latitude: Double,
    val longitude: Double,
    val bearing: Double = 0.0,
    val speed: Double = 0.0,
    val hasFix: Boolean = false,
)

data class NavDestination(
    val name: String,
    val placeName: String,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val routeId: String = "main",
    val mapboxId: String? = null,
    val googlePlaceId: String? = null,
    val sessionToken: String? = null,
    val distanceM: Double? = null,
    val source: String = "",
) {
    val hasCoordinates: Boolean get() = latitude != 0.0 && longitude != 0.0

    /**
     * Determines whether two NavDestination instances refer to the same geographical place,
     * ignoring non-essential search metadata (distanceM, mapboxId, sessionToken) and floating point jitter.
     */
    fun isSameAs(other: NavDestination?): Boolean {
        if (other == null) return false
        if (this === other) return true
        if (hasCoordinates && other.hasCoordinates) {
            val dLat = kotlin.math.abs(latitude - other.latitude)
            val dLng = kotlin.math.abs(longitude - other.longitude)
            return dLat < 0.0003 && dLng < 0.0003 &&
                (name.isBlank() || other.name.isBlank() || name.equals(other.name, ignoreCase = true))
        }
        if (hasCoordinates != other.hasCoordinates) {
            return false
        }
        return name.isNotBlank() && name.equals(other.name, ignoreCase = true) &&
            (placeName.isBlank() || other.placeName.isBlank() || placeName.equals(other.placeName, ignoreCase = true))
    }
}

data class RouteStep(
    val maneuverType: String = "turn",
    val maneuverModifier: String = "",
    val street: String = "",
    val instruction: String = "",
    val distanceM: Double = 0.0,
    val durationSec: Double = 0.0,
    val location: LatLng? = null,
)

data class RouteOption(
    val id: Int,
    val points: List<LatLng>,
    val durationMin: Int,
    val distanceMiles: Double,
    val summary: String,
    val congestions: List<String> = emptyList(),
    val geometry: String? = null,
    val steps: List<RouteStep> = emptyList(),
)

/** Build a RouteOption from comma API values (seconds + meters) —
 *  the duration/distance conversion that was copy-pasted at the 4
 *  construction sites (audit finding 14) now lives in [Units]. */
fun routeOptionFromApi(
    id: Int,
    points: List<LatLng>,
    durationSec: Double,
    distanceM: Double,
    summary: String
): RouteOption = RouteOption(
    id = id,
    points = points,
    durationMin = Units.durationMin(durationSec),
    distanceMiles = Units.milesTenths(distanceM),
    summary = summary
)

/** High-rate live telemetry plots from /api/plots/live (Section 4.3). */
data class GalaxyPlotsLive(
    val speedMps: Double?,
    val controlsActive: Boolean = false,
    val lateralControlActive: Boolean = false,
    val longitudinalControlActive: Boolean = false,
    val controlsFresh: Boolean = false,
    val poseFresh: Boolean = false,
    val lateralPid: Double = 0.0,
    val longitudinalPid: Double = 0.0,
    val desiredLongitudinalAccel: Double = 0.0,
    val actualLongitudinalAccel: Double = 0.0
)

/** Neural network driving model item from /api/models/manager (Section 6.3). */
data class GalaxyModelItem(
    val value: String,
    val label: String,
    val version: String,
    val series: String,
    val installed: Boolean,
    val selectable: Boolean,
    val downloadAvailable: Boolean,
    val unavailableReason: String? = null,
    val released: String = "",
    val builtin: Boolean = false,
    val artifactSizeBytes: Long = 0L,
    /** The comma's own size class: true unless the model requires the external GPU. */
    val small: Boolean = true
)

/** Sound pack item from /api/sounds (Section 6.3). */
data class GalaxySoundPack(
    val id: String,
    val name: String,
    val installed: Boolean
)

/** Driver Monitoring (FLM) calibration status from /api/flm/training-results (Section 6.3). */
data class GalaxyFlmStatus(
    val status: String = "unknown",
    val fresh: Boolean = false,
    val ageSeconds: Long = 0L,
    val samples: Int = 0,
    val blocks: Int = 0,
    val requiredBlocks: Int = 8
)

data class TelemetryPacket(
    val version: Int = 1,
    val timestamp: Double = System.currentTimeMillis() / 1000.0,
    val speedMs: Double = 0.0,
    val cruiseActive: Boolean = false,
    val lateralControlActive: Boolean = false,
    val longitudinalControlActive: Boolean = false,
    val lateralPid: Double = 0.0,
    val plotsLive: GalaxyPlotsLive? = null,
    val experimentalMode: Boolean = false,
    val nav: NavManeuver = NavManeuver(),
    val speedLimit: SpeedLimitInfo = SpeedLimitInfo(),
    val leadPresent: Boolean = false,
    val leadDistanceM: Double = 0.0,
    val power: PowerState? = null,
    val gps: GpsPosition? = null,
    val mapboxToken: String? = null,
    val destination: NavDestination? = null,
) {
    companion object {

        /** Last accepted speed limit (m/s) seen on a legacy params build; SG exposes none. */
        @Volatile
        private var lastKnownLimitMs: Double = 0.0

        /** SLC band lookup shared by both builders: bands are Offset1..7 (0-24, 25-34, ..., 75+ mph). */
        private fun bandOffsetMph(limitMph: Double, offsetAt: (Int) -> Double): Double = when {
            limitMph < 25 -> offsetAt(0)
            limitMph < 35 -> offsetAt(1)
            limitMph < 45 -> offsetAt(2)
            limitMph < 55 -> offsetAt(3)
            limitMph < 65 -> offsetAt(4)
            limitMph < 75 -> offsetAt(5)
            else -> offsetAt(6)
        }

        /**
         * Builds a packet from the comma's Galaxy API dump (GET /api/params/all).
         *
         * Nav data comes from the NavInstructionState parameter exactly as
         * navigationd writes it. The API publishes no live vehicle speed, so
         * [gpsSpeedMs] is the phone's GPS speed. The speed limit is the SLC's
         * last accepted limit (PreviousSpeedLimit); the offset is derived from
         * the Offset1..7 band the limit falls in, mirroring the SLC's own
         * band lookup. Params publish no per-lane directions, so [NavManeuver.lanes]
         * stays empty and only the lane cue ("lane X of N") is rendered.
         */
        fun fromParams(paramsJson: String, gpsSpeedMs: Double): TelemetryPacket? = try {
            val root = JSONObject(paramsJson)
            val nav: NavManeuver = when (val raw = root.opt("NavInstructionState")) {
                is JSONObject -> parseNav(raw)
                is String -> if (raw.isEmpty()) emptyNav() else try {
                    parseNav(JSONObject(raw))
                } catch (e: Exception) {
                    emptyNav()
                }
                else -> emptyNav()
            }
            val limitMs = root.optDouble("PreviousSpeedLimit", 0.0)
            val effectiveLimitMs = if (limitMs > 0.0) {
                limitMs
            } else if (NavStateManager.usePhoneSpeedLimit && NavStateManager.speedLimitMph > 0) {
                NavStateManager.speedLimitMph / Units.MS_TO_MPH
            } else if (NavStateManager.usePhoneSpeedLimit && lastKnownLimitMs > 0.0) {
                lastKnownLimitMs
            } else {
                0.0
            }
            if (effectiveLimitMs > 0.0) lastKnownLimitMs = effectiveLimitMs
            val limitMph = effectiveLimitMs * Units.MS_TO_MPH
            val offsetMph = when {
                limitMph < 1.0 -> 0.0
                limitMph < 25 -> root.optDouble("Offset1", 0.0)
                limitMph < 35 -> root.optDouble("Offset2", 0.0)
                limitMph < 45 -> root.optDouble("Offset3", 0.0)
                limitMph < 55 -> root.optDouble("Offset4", 0.0)
                limitMph < 65 -> root.optDouble("Offset5", 0.0)
                limitMph < 75 -> root.optDouble("Offset6", 0.0)
                else -> root.optDouble("Offset7", 0.0)
            }
            val gps: GpsPosition? = when (val raw = root.opt("LastGPSPosition")) {
                is JSONObject -> parseGps(raw)
                is String -> if (raw.isBlank()) null else try {
                    parseGps(JSONObject(raw))
                } catch (_: Exception) {
                    null
                }
                else -> null
            }
            val dest: NavDestination? = when (val raw = root.opt("NavDestination")) {
                is JSONObject -> parseDestination(raw)
                is String -> if (raw.isBlank()) null else try {
                    parseDestination(JSONObject(raw))
                } catch (_: Exception) {
                    null
                }
                else -> null
            }
            val mbToken = root.optString("MapboxPublicKey", "").trim().ifEmpty { null }

            TelemetryPacket(
                version = 3,
                timestamp = System.currentTimeMillis() / 1000.0,
                speedMs = gpsSpeedMs,
                cruiseActive = false,
                experimentalMode = false,
                nav = nav,
                speedLimit = SpeedLimitInfo(
                    limitMs = effectiveLimitMs,
                    offsetMs = offsetMph / Units.MS_TO_MPH,
                    source = if (limitMs >= 1.0) "Previous" else if (effectiveLimitMs >= 1.0) "mapbox" else "",
                ),
                leadPresent = false,
                leadDistanceM = 0.0,
                gps = gps,
                mapboxToken = mbToken,
                destination = dest,
            )
        } catch (e: Exception) {
            null
        }

        /**
         * Builds a packet from SecretGoodStarPilot's typed Galaxy APIs:
         * GET /api/navigation/status plus GET /api/plots/live.
         *
         * Field sourcing (see TYPED_API_MAP.md):
         * - maneuver: instruction {maneuverType, maneuverModifier ("none" → ""),
         *   distanceMeters, text, remaining*} plus lane guidance when the
         *   instruction carries it (lanes / laneCount / activeLaneIndex) —
         *   all parsed by the one layer-2 parser, [SgInstruction].
         * - speed: plots.speedMps (real vehicle speed), falling back to phone GPS.
         * - cruiseActive: plots.controlsActive.
         * - gps: status.location; fix = validForMs > 0 when the field is present (a lastKnown payload carries validForMs 0 and is NOT a fix).
         * - destination: status.destination (address falls back to name).
         * - speed limit: NOT exposed by any SG typed GET, so limit stays 0
         *   (the UI's no-limit path). The offset is the SLC band value for the
         *   last known legacy limit when both are available, else 0.
         */
        fun fromSg(
            navStatus: JSONObject,
            plots: JSONObject?,
            slcOffsetsMph: DoubleArray?,
            gpsSpeedMs: Double
        ): TelemetryPacket {
            val instr = navStatus.optJSONObject("instruction")
            // THE instruction parse lives in layer 2 (SgInstruction);
            // NavigationManager's snapshot writer calls the same one,
            // so the two 1-second writers can never disagree about the
            // maneuver again (the 2026-11-06 lane-blink fix).
            val nav: NavManeuver = SgInstruction.parse(instr, navStatus)

            val plotsSpeed: Double? = plots?.let {
                if (it.has("speedMps") && !it.isNull("speedMps")) {
                    it.optDouble("speedMps", Double.NaN).takeUnless { v -> v.isNaN() }
                } else null
            }
            val speedMs = plotsSpeed ?: gpsSpeedMs
            val cruiseActive = plots?.let {
                it.has("controlsActive") && !it.isNull("controlsActive") &&
                    it.optBoolean("controlsActive", false)
            } == true
            val lateralActive = plots?.optBoolean("lateralControlActive", false) == true
            val longitudinalActive = plots?.optBoolean("longitudinalControlActive", false) == true
            val controlsFresh = plots?.optBoolean("controlsFresh", false) == true
            val poseFresh = plots?.optBoolean("poseFresh", false) == true
            val lateralPid = plots?.optDouble("lateralPid", 0.0) ?: 0.0
            val longitudinalPid = plots?.optDouble("longitudinalPid", 0.0) ?: 0.0
            val desiredAccel = plots?.optDouble("desiredLongitudinalAccel", plots.optDouble("desiredAccelMps2", 0.0)) ?: 0.0
            val actualAccel = plots?.optDouble("actualLongitudinalAccel", plots.optDouble("actualAccelMps2", 0.0)) ?: 0.0

            val plotsLive = if (plots != null) {
                GalaxyPlotsLive(
                    speedMps = plotsSpeed,
                    controlsActive = cruiseActive,
                    lateralControlActive = lateralActive,
                    longitudinalControlActive = longitudinalActive,
                    controlsFresh = controlsFresh,
                    poseFresh = poseFresh,
                    lateralPid = lateralPid,
                    longitudinalPid = longitudinalPid,
                    desiredLongitudinalAccel = desiredAccel,
                    actualLongitudinalAccel = actualAccel
                )
            } else null

            val gps: GpsPosition? = navStatus.optJSONObject("location")?.let { loc ->
                val lat = loc.optDouble("latitude", Double.NaN)
                val lng = loc.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) null
                else GpsPosition(
                    latitude = lat,
                    longitude = lng,
                    bearing = loc.optDouble("bearing", 0.0),
                    speed = speedMs,
                    // A stale lastKnown location (validForMs == 0)
                    // is NOT a fix (bug fix, 2026-11-08): counting
                    // it as one kept the AA/phone map drawing the
                    // comma's stale position and defeated the
                    // phone-location drawing fallback. Payloads
                    // without the field keep the coords-are-enough
                    // rule.
                    hasFix = if (loc.has("validForMs")) loc.optDouble("validForMs", 0.0) > 0.0 else true,
                )
            }

            val dest: NavDestination? = navStatus.optJSONObject("destination")?.let { d ->
                val lat = d.optDouble("latitude", Double.NaN)
                val lng = d.optDouble("longitude", Double.NaN)
                val name = d.optString("name", "")
                if (lat.isNaN() || lng.isNaN() || name.isEmpty()) null
                else {
                    val address = d.optString("address", "")
                    NavDestination(
                        name = name,
                        placeName = address.ifEmpty { name },
                        latitude = lat,
                        longitude = lng,
                    )
                }
            }

            var limitFromPayload = false
            val parsedLimitMs: Double = run {
                // The one payload-limit parse (layer 2, SpeedLimitParse);
                // this block and NavigationManager's snapshot writer used
                // to probe the same keys with opposite unit heuristics.
                val payloadMs = SpeedLimitParse.payloadLimitMs(navStatus, plots)
                if (payloadMs > 0.0) {
                    limitFromPayload = true
                    return@run payloadMs
                }

                if (NavStateManager.usePhoneSpeedLimit && NavStateManager.speedLimitMph > 0) {
                    return@run NavStateManager.speedLimitMph / Units.MS_TO_MPH
                }

                if (NavStateManager.usePhoneSpeedLimit && lastKnownLimitMs > 0.0) {
                    return@run lastKnownLimitMs
                }

                0.0
            }
            if (parsedLimitMs > 0.0) {
                lastKnownLimitMs = parsedLimitMs
                // The speedLimitMph mirror write lives in the caller
                // (CommaClient.fetchSgTelemetry) — a parser must not
                // mutate global state (audit finding 17).
            }

            val limitMph = parsedLimitMs * Units.MS_TO_MPH
            val offsetMph = if (limitMph >= 1.0 && slcOffsetsMph != null) {
                bandOffsetMph(limitMph) { band -> slcOffsetsMph.getOrElse(band) { 0.0 } }
            } else 0.0

            return TelemetryPacket(
                version = 3,
                timestamp = System.currentTimeMillis() / 1000.0,
                speedMs = speedMs,
                cruiseActive = cruiseActive,
                lateralControlActive = lateralActive,
                longitudinalControlActive = longitudinalActive,
                lateralPid = lateralPid,
                plotsLive = plotsLive,
                experimentalMode = false,
                nav = nav,
                speedLimit = SpeedLimitInfo(
                    limitMs = parsedLimitMs,
                    offsetMs = offsetMph / Units.MS_TO_MPH,
                    source = when {
                        parsedLimitMs <= 0.0 -> ""
                        limitFromPayload -> "sg"
                        else -> "mapbox"
                    },
                ),
                leadPresent = false,
                leadDistanceM = 0.0,
                gps = gps,
                destination = dest,
                power = NavStateManager.latestPower,
            )
        }

        private fun parseDestination(o: JSONObject): NavDestination? {
            val lat = o.optDouble("latitude", Double.NaN)
            val lng = o.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) return null
            val name = o.optString("name", "").ifEmpty { o.optString("place_name", "") }
            val placeName = o.optString("place_name", name)
            if (name.isEmpty() && placeName.isEmpty()) return null
            return NavDestination(
                name = name,
                placeName = placeName,
                latitude = lat,
                longitude = lng,
                routeId = o.optString("routeId", "main")
            )
        }

        private fun parseGps(o: JSONObject): GpsPosition? {
            val lat = o.optDouble("latitude", Double.NaN)
            val lng = o.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) return null
            return GpsPosition(
                latitude = lat,
                longitude = lng,
                bearing = o.optDouble("bearing", 0.0),
                speed = o.optDouble("speed", 0.0),
                hasFix = o.optBoolean("hasFix", false),
            )
        }

        private fun parseNav(n: JSONObject): NavManeuver =
            if (!n.optBoolean("valid", false)) emptyNav()
            else NavManeuver(
                valid = true,
                type = n.optString("maneuverType", ""),
                modifier = n.optString("maneuverModifier", ""),
                distanceM = n.optDouble("maneuverDistance", 0.0),
                street = n.optString("maneuverPrimaryText", ""),
                laneCount = n.optInt("laneCount", 0),
                activeLaneDirection = n.optString("activeLaneDirection", ""),
                activeLaneIndex = n.optInt("activeLaneIndex", -1),
                sameSideLaneCount = n.optInt("sameSideLaneCount", 0),
                lanes = emptyList(),
            )

        private fun emptyNav(): NavManeuver = NavManeuver(
            valid = false, type = "", modifier = "", distanceM = 0.0, street = "",
            laneCount = 0, activeLaneDirection = "", activeLaneIndex = -1,
            sameSideLaneCount = 0, lanes = emptyList(),
        )

    }
}
