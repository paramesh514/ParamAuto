package com.parampilot.auto

/**
 * The ONE notice the car screens show when navigation cannot work
 * right now (Addendum 142, matrix-aware in 143) — decided here
 * (layer 2) from shared state; NavScreen and SearchScreen only
 * render the words.
 *
 * Why: with the comma connected but no internet, a destination
 * send was queued (or a search came back empty) and the car
 * screen said nothing — a transient toast, then silence on the
 * main nav screen (Param's field report, 2026-11-23). The queue
 * words are [DestinationQueue.label]'s, the same text the phone
 * chip, the nav banner and the search list already show; the
 * scenario words follow Param's connectivity matrix (2026-11-23).
 */
object CarScreenNotice {

    /** Scenario 4, in Param's words. */
    const val NO_INTERNET_CONNECTIVITY = "No internet connectivity"

    const val NO_INTERNET_SEARCH =
        "No internet connection — search needs internet"

    const val COMMA_OUT_OF_SYNC_NOTICE =
        "Comma out of sync — the comma has no internet connection"

    const val COMMA_ONLY_NOTICE =
        "Phone has no internet — using the comma for search and routing"

    /**
     * The persistent card for the main car nav screen, or null when
     * nothing is wrong. An active route owns the slot (RoutingInfo)
     * and never gets a notice; a queued destination outranks the
     * scenario notices because it names the actual destination
     * waiting.
     */
    fun navNotice(
        queued: NavDestination?,
        outOfSync: Boolean,
        networks: GalaxyNetworks?,
        hasActiveRoute: Boolean,
        scenario: ConnectivityMatrix.Scenario
    ): String? = when {
        hasActiveRoute -> null
        queued != null -> DestinationQueue.label(queued, outOfSync, networks)
        scenario == ConnectivityMatrix.Scenario.NO_INTERNET ->
            NO_INTERNET_CONNECTIVITY
        scenario == ConnectivityMatrix.Scenario.COMMA_OUT_OF_SYNC ->
            COMMA_OUT_OF_SYNC_NOTICE
        scenario == ConnectivityMatrix.Scenario.COMMA_ONLY ->
            COMMA_ONLY_NOTICE
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
