package com.parampilot.auto

/**
 * The Map keys & usage page's content rules (Param, 2026-11-20).
 *
 * One page, two tabs — Mapbox and Google. A tab shows the provider's
 * usage when a key is configured, and plain how-to-get-a-key
 * instructions when it is not: a first-time user should never land on
 * empty metrics and have to guess what a key even is. The step texts
 * are written for a non-technical reader: where to go, what to click,
 * what the key looks like, and what it costs (the free tiers).
 */
object ProviderGuide {

    const val TAB_MAPBOX = "mapbox"
    const val TAB_GOOGLE = "google"

    enum class Panel { INSTRUCTIONS, USAGE }

    /** Usage when a key exists; instructions when it does not. */
    fun panelFor(hasKey: Boolean): Panel =
        if (hasKey) Panel.USAGE else Panel.INSTRUCTIONS

    const val MAPBOX_STEPS: String =
        "How to get a free Mapbox token:\n" +
            "1. In any browser, go to mapbox.com and sign up — it's free.\n" +
            "2. Your account page shows a public token (it starts with pk.). " +
            "For a new one: Tokens → Create a token.\n" +
            "3. Copy the token and paste it below, then tap Save.\n" +
            "The free tier is generous, and this page shows the usage " +
            "once a token is saved."

    const val GOOGLE_STEPS: String =
        "How to get a free Google Maps key:\n" +
            "1. Go to console.cloud.google.com and create a project — it's free.\n" +
            "2. Under APIs & Services → Library, enable: Map Tiles API, " +
            "Places API (New), Roads API and Routes API.\n" +
            "3. Under APIs & Services → Credentials, create an API key " +
            "(it starts with AIza).\n" +
            "4. Copy the key and paste it below, then tap Save.\n" +
            "Each API has a free monthly allowance. This page shows the " +
            "usage once a key is saved, and at 95% of a free limit the " +
            "app switches to Mapbox so nothing is billed by surprise."
}
