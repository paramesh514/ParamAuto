package com.parampilot.auto

/**
 * The connectivity matrix (Param, 2026-11-23) — how the app
 * behaves for each combination of comma internet and phone
 * internet:
 *
 * 1. Both have internet — normal operation, comma first.
 * 2. Comma has internet, phone does not — use all comma things;
 *    no phone things (comma search replaces phone search, phone
 *    data fetches stop).
 * 3. Comma has no internet, phone does — all features stay
 *    available, with the comma shown out of sync.
 * 4. Neither has internet — everything that needs internet is
 *    disabled and the screens show "No internet connectivity".
 *
 * The comma's internet state comes from its Galaxy Networks link
 * (wifi/cellular = it has a link; "none" = no link, hence no
 * internet). An unreported or unrecognized link classifies as
 * UNKNOWN and gates nothing: behavior stays exactly as before
 * the matrix existed. The phone's state is Android's own
 * validated-internet verdict, kept in
 * [NavStateManager.phoneInternet].
 */
object ConnectivityMatrix {

    enum class Scenario {
        /** No comma connection at all — the pre-existing offline
         * world (the app acts as a map app; destinations queue). */
        COMMA_OFFLINE,

        /** Scenario 1: both online. */
        NORMAL,

        /** Scenario 2: comma online, phone offline. */
        COMMA_ONLY,

        /** Scenario 3: comma offline, phone online. */
        COMMA_OUT_OF_SYNC,

        /** Scenario 4: both offline. */
        NO_INTERNET,

        /** Comma link unreported/unrecognized — gate nothing. */
        UNKNOWN
    }

    /** The comma's internet state from its Galaxy link word:
     * true/false when the link settles it, null when unknown. */
    fun commaHasInternet(commaLink: String?): Boolean? =
        when (commaLink?.lowercase()) {
            "wifi", "cellular" -> true
            "none" -> false
            else -> null
        }

    fun scenario(
        commaFound: Boolean,
        commaLink: String?,
        phoneInternet: Boolean
    ): Scenario {
        if (!commaFound) return Scenario.COMMA_OFFLINE
        return when (commaHasInternet(commaLink)) {
            true -> if (phoneInternet) Scenario.NORMAL else Scenario.COMMA_ONLY
            false -> if (phoneInternet) Scenario.COMMA_OUT_OF_SYNC else Scenario.NO_INTERNET
            null -> Scenario.UNKNOWN
        }
    }

    /** The scenario for the app's current shared state. */
    fun current(): Scenario = scenario(
        commaFound = NavStateManager.commaFound,
        commaLink = NavStateManager.commaNetworks?.link,
        phoneInternet = NavStateManager.phoneInternet
    )

    /**
     * Search runs against the comma (over the comma's own
     * internet) rather than any phone source. The user's
     * phone-search toggle still rules in every other scenario.
     */
    fun useCommaSearch(scenario: Scenario, usePhoneSearch: Boolean): Boolean =
        !usePhoneSearch || scenario == Scenario.COMMA_ONLY

    /**
     * Any network search at all (phone or comma): only scenario 4
     * disables it — everywhere else at least one side has internet
     * to search with. Comma-offline keeps the phone-search
     * map-app mode.
     */
    fun searchAllowed(scenario: Scenario): Boolean =
        scenario != Scenario.NO_INTERNET
}
