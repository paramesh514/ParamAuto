package com.parampilot.auto

/**
 * Layer 2: THE order in which phone-sourced road data tries its
 * providers (Addendum 161, Param 2026-11-28: "Speed limit also
 * follow same order as maps. Google -> Mapbox -> OpenStreetMap";
 * "Non comma navigation also same order").
 *
 * One ordering policy, shared by every phone-side fetch that picks
 * a provider — today the speed-limit series fetch and the
 * phone-side routing selection — so the chains can never drift
 * apart. A provider is only in the plan when it is USABLE under its
 * existing gate, resolved by the caller through the existing
 * helpers (Google: PhoneGoogle.effectiveGoogleKey — "Use phone
 * Google key" toggle + saved, well-formed key; Mapbox:
 * MapSourceChoice.effectiveMapboxToken — "Use phone Mapbox key"
 * toggle + saved token). OSM is key-free: it is always the last
 * resort. And the whole plan is empty when phone data is not
 * allowed at all (HeartbeatTransport.phoneDataAllowed — on Galaxy
 * Cloud transport no phone-sourced value is used anywhere).
 *
 * Pure: booleans in, ordered plan out; the fetches live in
 * NavigationManager, the callers in CommaClient.
 */
object PhoneSourceOrder {

    enum class Source { GOOGLE, MAPBOX, OSM }

    /** The order itself, most preferred first. */
    val ORDER: List<Source> = listOf(Source.GOOGLE, Source.MAPBOX, Source.OSM)

    /**
     * The ordered providers a phone-side fetch may try: each gated
     * provider only when usable, OSM always last, nothing at all
     * when phone data is not allowed on the current transport.
     */
    fun plan(
        googleUsable: Boolean,
        mapboxUsable: Boolean,
        phoneDataAllowed: Boolean
    ): List<Source> {
        if (!phoneDataAllowed) return emptyList()
        val out = ArrayList<Source>(ORDER.size)
        if (googleUsable) out.add(Source.GOOGLE)
        if (mapboxUsable) out.add(Source.MAPBOX)
        out.add(Source.OSM)
        return out
    }

    /**
     * The phone-side ROUTING selection: while the comma is guiding,
     * the comma's route is the route and no phone provider is
     * consulted at all (comma-led navigation is untouched by this
     * order). Only when the phone itself is the navigator does the
     * shared plan apply.
     */
    fun routingPlan(
        commaGuiding: Boolean,
        googleUsable: Boolean,
        mapboxUsable: Boolean,
        phoneDataAllowed: Boolean
    ): List<Source> {
        if (commaGuiding) return emptyList()
        return plan(googleUsable, mapboxUsable, phoneDataAllowed)
    }

    /** The provenance string NavStateManager.routeLimitSource carries for a series from [source]. */
    fun limitSourceLabel(source: Source): String = when (source) {
        Source.GOOGLE -> "google"
        Source.MAPBOX -> "mapbox"
        Source.OSM -> "osm"
    }
}
