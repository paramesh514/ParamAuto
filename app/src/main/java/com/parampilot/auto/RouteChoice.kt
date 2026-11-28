package com.parampilot.auto

import kotlin.math.roundToInt

/**
 * Layer-2 text and cycling rules for alternate-route choice on
 * the car (Addendum 153, diagnosis C3/C4).
 *
 * Route choice itself stays where it has always lived —
 * [NavStateManager.selectedRouteId] + [NavigationManager.selectRouteSg],
 * the same owner the phone's route taps use. This object only
 * decides WHICH route the car's Alt action offers next and how
 * routes are labelled, so the car screens never re-derive it.
 *
 * The pre-153 Alt button targeted the first route that was not
 * selected, so with three or more routes it toggled between two
 * of them forever and the rest were unreachable from the car;
 * its title showed only the time DIFFERENCE ("Alt (+3 m)"),
 * which reads as metres as easily as minutes.
 */
object RouteChoice {

    /**
     * The route the Alt action offers: the one after the current
     * selection in list order, wrapping at the end — repeated
     * taps cycle through EVERY route. Null when there is no
     * alternate to offer. An unknown [currentId] (not in the
     * list) offers the first route.
     */
    fun next(routes: List<RouteOption>, currentId: Int): RouteOption? {
        if (routes.size < 2) return null
        val idx = routes.indexOfFirst { it.id == currentId }
        return routes[(idx + 1) % routes.size]
    }

    /** Alt action title: the offered route's own total time —
     *  "Alt · 24 min", never a bare difference. */
    fun altButtonTitle(next: RouteOption): String = "Alt · ${next.durationMin} min"

    /** One-line route label for map overlays and chooser rows:
     *  "24 min · 3.2 mi". */
    fun labelText(route: RouteOption): String =
        "${route.durationMin} min · ${formatMiles(route.distanceMiles)}"

    fun formatMiles(miles: Double): String =
        if (miles < 10.0) "%.1f mi".format(miles) else "${miles.roundToInt()} mi"
}
