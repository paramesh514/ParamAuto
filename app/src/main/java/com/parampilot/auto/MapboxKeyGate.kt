package com.parampilot.auto

/**
 * Param's rule (2026-11-04): "If Mapbox function toggles are on and
 * the Mapbox key is not there — throw error and switch back."
 *
 * A Phone Data toggle whose function needs the app's Mapbox token
 * ("Use phone Mapbox key", "Use phone speed limit") must never sit
 * ON while no token is saved: turning it on without a token shows
 * the error and the switch goes back off, and a saved ON state found
 * without a token is switched back off when Settings opens.
 *
 * Pure: the dialog supplies the facts and applies the outcome.
 */
object MapboxKeyGate {

    const val ERROR_MESSAGE =
        "No Mapbox key saved — paste your Mapbox token first (Phone Data & Tools → Maps & offline data). The toggle was switched back off."

    /** True when an ON (or turning-on) Mapbox function has no key and must be forced back off. */
    fun mustSwitchBack(toggleOn: Boolean, hasToken: Boolean): Boolean = toggleOn && !hasToken
}
