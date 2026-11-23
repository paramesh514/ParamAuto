package com.parampilot.auto

/**
 * The ONE notice the car screens show when navigation cannot work
 * right now (Addendum 142) — decided here (layer 2) from shared
 * state; NavScreen and SearchScreen only render the words.
 *
 * Why: with the comma connected but no internet, a destination
 * send was queued (or a search came back empty) and the car
 * screen said nothing — a transient toast, then silence on the
 * main nav screen (Param's field report, 2026-11-23). The queue
 * words are [DestinationQueue.label]'s, the same text the phone
 * chip, the nav banner and the search list already show.
 */
object CarScreenNotice {

    const val NO_INTERNET_NAV =
        "No internet connection — search and new routes need internet"
    const val NO_INTERNET_SEARCH =
        "No internet connection — search needs internet"

    /**
     * The persistent card for the main car nav screen, or null when
     * nothing is wrong. An active route owns the slot (RoutingInfo)
     * and never gets a notice; a queued destination outranks the
     * connectivity notice because it names the actual destination
     * waiting.
     */
    fun navNotice(
        queued: NavDestination?,
        outOfSync: Boolean,
        networks: GalaxyNetworks?,
        hasActiveRoute: Boolean,
        phoneHasInternet: Boolean
    ): String? = when {
        hasActiveRoute -> null
        queued != null -> DestinationQueue.label(queued, outOfSync, networks)
        !phoneHasInternet -> NO_INTERNET_NAV
        else -> null
    }

    /**
     * The empty-search row's words: a failed search is never
     * reported as "no results" — the row names the blocker (the
     * phone's connection first, then the comma's own search error).
     */
    fun searchEmptyText(
        query: String,
        phoneHasInternet: Boolean,
        commaError: String?
    ): String = when {
        !phoneHasInternet -> NO_INTERNET_SEARCH
        commaError != null -> "Comma search failed — $commaError"
        else -> "No results found for \"$query\""
    }
}
