package com.parampilot.auto

import kotlin.math.roundToInt

/**
 * Layer-2 math behind the Android Auto navigation template
 * (3-layer refactor, 2026-11-04): which distance and ETA the
 * template may honestly show. NavScreen (layer 3) gathers the
 * state, calls this, and translates the result into car-app
 * Distance / TravelEstimate objects; it no longer decides.
 *
 * Every value is nullable by design: when nothing real backs a
 * number there is no number — no phantom 5000 m when GPS is
 * missing, no 1.0 mi default, no assumed 13.4 m/s speed, no 300 s
 * ETA default.
 *
 * Pure: primitives in, primitives out. Distances come back in one
 * of two representations, mirroring how the template has always
 * built them: meters (rendered through the shared display units)
 * or miles (handed to the host as-is).
 */
object GuidanceMath {

    data class Input(
        /** The effective maneuver (layer-2 derived): drives the step distance. */
        val effNavValid: Boolean,
        val effNavDistanceM: Double,
        /** The packet's raw maneuver: drives the ETA fallbacks. */
        val navValid: Boolean,
        val navDistanceM: Double,
        val routeDistanceMiles: Double?,
        val routeDurationMin: Int?,
        val destDistanceM: Double?,
        val destHasCoordinates: Boolean,
        val destLat: Double,
        val destLng: Double,
        val gpsLat: Double?,
        val gpsLng: Double?,
        val rawSpeedMs: Double,
        /** Live progress along the route (RouteProgress), when computable. */
        val progressRemainingM: Double?,
        val progressFractionRemaining: Double?,
        /** The comma's own live remaining totals for the whole trip
         *  (instruction remaining* fields); 0 when not guiding. These
         *  outrank every derived estimate: they ARE the trip totals. */
        val commaRemainingM: Double = 0.0,
        val commaRemainingSec: Double = 0.0
    ) {
        companion object {
            /**
             * Assembles an [Input] from the domain objects every
             * ETA surface already has (factory added 2026-11-08:
             * the 17-field assembly was hand-written at all three
             * surfaces — the drift shape of audit finding 11).
             * [effNav] and [nav] differ only where a surface
             * derives an effective maneuver (NavScreen); the
             * comma's remaining totals ride on [nav] itself.
             */
            fun fromState(
                effNav: NavManeuver?,
                nav: NavManeuver?,
                route: RouteOption?,
                dest: NavDestination?,
                gps: GpsPosition?,
                rawSpeedMs: Double,
                progress: RouteProgress.Result?
            ): Input = Input(
                effNavValid = effNav?.valid ?: false,
                effNavDistanceM = effNav?.distanceM ?: 0.0,
                navValid = nav?.valid ?: false,
                navDistanceM = nav?.distanceM ?: 0.0,
                routeDistanceMiles = route?.distanceMiles,
                routeDurationMin = route?.durationMin,
                destDistanceM = dest?.distanceM,
                destHasCoordinates = dest?.hasCoordinates == true,
                destLat = dest?.latitude ?: 0.0,
                destLng = dest?.longitude ?: 0.0,
                gpsLat = gps?.latitude,
                gpsLng = gps?.longitude,
                rawSpeedMs = rawSpeedMs,
                progressRemainingM = progress?.remainingM,
                progressFractionRemaining = progress?.fractionRemaining,
                commaRemainingM = nav?.remainingDistanceM ?: 0.0,
                commaRemainingSec = nav?.remainingDurationSec ?: 0.0
            )
        }
    }

    data class Result(
        /** Step distance in meters (effective-maneuver branch), or null. */
        val stepDistanceM: Double?,
        /** Step distance in miles (route/destination branches), or null. */
        val stepDistanceMiles: Double?,
        val etaSeconds: Long?,
        /** Destination-total distance, same representation split as the step. */
        val totalDistanceM: Double?,
        val totalDistanceMiles: Double?,
        val totalEtaSeconds: Long?,
        val stepDurationSeconds: Long?
    )

    private const val METERS_PER_MILE = Units.METERS_PER_MILE

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    fun compute(i: Input): Result {
        // Step distance — directly to the upcoming maneuver turn.
        var stepM: Double? = null
        var stepMiles: Double? = null
        when {
            i.effNavValid && i.effNavDistanceM > 0 -> stepM = i.effNavDistanceM
            i.routeDistanceMiles != null && i.routeDistanceMiles > 0 -> stepMiles = i.routeDistanceMiles
            i.destDistanceM != null && i.destDistanceM > 0 -> stepMiles = i.destDistanceM / METERS_PER_MILE
            i.destHasCoordinates && i.gpsLat != null && i.gpsLng != null ->
                stepMiles = haversineM(i.gpsLat, i.gpsLng, i.destLat, i.destLng) / METERS_PER_MILE
        }

        // Distance the speed-based ETA runs on: the raw maneuver's
        // when it carries one, else the effective maneuver's. A
        // pass-through raw step can report 0 while the effective
        // next turn is real — the AA ETA card vanished in exactly
        // that state (Param's photo, 2026-11-08: maneuver card
        // showing 16 mi, no ETA card) because every ETA branch
        // needed the raw distance, a route object, or the comma's
        // remaining totals, and none was present.
        val etaDistanceM: Double? = when {
            i.navValid && i.navDistanceM > 0 -> i.navDistanceM
            i.effNavValid && i.effNavDistanceM > 0 -> i.effNavDistanceM
            else -> null
        }

        // ETA — route duration when stopped or slow; distance at the
        // REAL current speed otherwise.
        val eta: Long? = when {
            i.routeDurationMin != null && i.routeDurationMin > 0 &&
                (i.rawSpeedMs < 2.0 || !i.navValid || i.navDistanceM <= 0) ->
                (i.routeDurationMin * 60).toLong()
            etaDistanceM != null && i.rawSpeedMs >= 2.0 ->
                (etaDistanceM / i.rawSpeedMs).roundToInt().toLong()
            i.routeDurationMin != null && i.routeDurationMin > 0 ->
                (i.routeDurationMin * 60).toLong()
            i.destHasCoordinates && i.rawSpeedMs >= 2.0 && i.gpsLat != null && i.gpsLng != null ->
                (haversineM(i.gpsLat, i.gpsLng, i.destLat, i.destLng) / i.rawSpeedMs)
                    .roundToInt().toLong()
            else -> null
        }
        val safeEta: Long? = eta?.let { maxOf(it, 0L) }

        // Destination totals: the comma's own remaining totals first
        // (they exist whenever the comma is guiding — Param, 2026-11-05:
        // the ETA card used to vanish whenever the route object or the
        // speed-based fallbacks had a gap, e.g. stopped at a light);
        // then live progress along the route; then the step values.
        val totalM: Double?
        val totalMiles: Double?
        if (i.commaRemainingM > 0.0) {
            totalM = i.commaRemainingM
            totalMiles = null
        } else if (i.progressRemainingM != null && i.progressRemainingM > 0.0) {
            totalM = i.progressRemainingM
            totalMiles = null
        } else {
            totalM = stepM
            totalMiles = stepMiles
        }
        val totalEta: Long? = if (i.commaRemainingSec > 0.0) {
            i.commaRemainingSec.roundToInt().toLong()
        } else if (
            i.progressRemainingM != null && i.progressFractionRemaining != null &&
            i.routeDurationMin != null && i.routeDurationMin > 0
        ) {
            val scaled = (i.routeDurationMin * 60.0 * i.progressFractionRemaining)
                .roundToInt().toLong()
            maxOf(scaled, if (i.progressRemainingM > 50.0) 30L else 0L)
        } else {
            safeEta
        }

        val stepDuration: Long? = when {
            i.rawSpeedMs > 1.0 && i.effNavDistanceM > 0 ->
                (i.effNavDistanceM / i.rawSpeedMs).roundToInt().toLong()
            totalEta != null -> totalEta
            else -> null
        }

        return Result(
            stepDistanceM = stepM,
            stepDistanceMiles = stepMiles,
            etaSeconds = safeEta,
            totalDistanceM = totalM,
            totalDistanceMiles = totalMiles,
            totalEtaSeconds = totalEta,
            stepDurationSeconds = stepDuration
        )
    }
}
