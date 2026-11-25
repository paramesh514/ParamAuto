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
     * The car text ceiling (Car App Quality / car writing
     * principles, from NHTSA/JAMA guidance): one chunk of text on
     * a car screen stays within 120 characters.
     */
    const val CAR_TEXT_LIMIT = 120

    /**
     * Caps one car-displayed text at [CAR_TEXT_LIMIT], cutting
     * with an ellipsis. Only the car copy is capped — the phone
     * shows the same words in full (a queued destination's label
     * carries the place name plus the comma's Wi-Fi detail and
     * can run long).
     */
    fun capForCar(text: String): String =
        if (text.length <= CAR_TEXT_LIMIT) text
        else text.take(CAR_TEXT_LIMIT - 1).trimEnd() + "…"

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
    ): String? {
        val text = when {
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
        return text?.let { capForCar(it) }
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
    ): String = capForCar(
        when {
            !phoneHasInternet -> NO_INTERNET_SEARCH
            commaError != null -> "Comma search failed — $commaError"
            else -> "No results found for \"$query\""
        }
    )
}
