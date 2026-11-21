package com.parampilot.auto

/**
 * Layer-2 decisions about WHERE each display's base map comes from
 * (business-logic push, 2026-11-04): the displays execute these
 * plans; they no longer choose for themselves.
 *
 * The order is the product rule both displays share (revised by
 * Param, 2026-11-10 — "3D enriched always in phone and auto"):
 * Mapbox first whenever the phone's key is usable (it is the only
 * 3D-enriched source), then the comma's own map when we're on its
 * network, then the key-free fallback. The fallbacks still matter:
 * with no signal the comma tiles / stitch keep a (flat) map on
 * screen instead of none.
 */
object MapSourceChoice {

    enum class AaSource { COMMA_STITCH, MAPBOX_STATIC, FULL_STITCH }

    /** Ordered candidates for the Auto base map; the renderer tries
     *  each in order until one produces a frame. */
    fun aaPlan(isCommaLan: Boolean, hasToken: Boolean): List<AaSource> {
        val plan = mutableListOf<AaSource>()
        if (hasToken) plan.add(AaSource.MAPBOX_STATIC)
        if (isCommaLan) plan.add(AaSource.COMMA_STITCH)
        plan.add(AaSource.FULL_STITCH)
        return plan
    }

    enum class PhoneStyle { COMMA_RASTER, MAPBOX, GOOGLE_RASTER, OPEN_FREE_MAP, CARTO_RASTER }

    /**
     * Which style family the phone map loads. [hasUsableToken] is
     * the caller's resolved fact: key toggle on + token saved +
     * token well-formed.
     *
     * The Phone maps dropdown (Param, 2026-11-18) names the user's
     * preferred source — Google / Mapbox / OSM. The preference wins
     * when it is actually usable (Google needs a live Map Tiles
     * session, which needs the phone Google key; Mapbox needs its
     * token; OSM is key-free). An unusable preference falls back to
     * the long-standing order below, so the map never blanks while a
     * key or a session is missing. With no preference stored the
     * default is Mapbox, which reproduces the pre-dropdown behavior
     * exactly.
     */
    fun phoneStyle(
        isLocalIpConnected: Boolean,
        hasUsableToken: Boolean,
        useVectorMap: Boolean = false,
        phoneMapSource: String = PhoneGoogle.SOURCE_MAPBOX,
        hasGoogleSession: Boolean = false
    ): PhoneStyle {
        fun fallback(): PhoneStyle = when {
            hasUsableToken -> PhoneStyle.MAPBOX
            isLocalIpConnected -> PhoneStyle.COMMA_RASTER
            useVectorMap -> PhoneStyle.OPEN_FREE_MAP
            else -> PhoneStyle.CARTO_RASTER
        }
        return when (PhoneGoogle.sanitizeMapSource(phoneMapSource)) {
            PhoneGoogle.SOURCE_GOOGLE ->
                if (hasGoogleSession) PhoneStyle.GOOGLE_RASTER else fallback()
            PhoneGoogle.SOURCE_OSM -> PhoneStyle.OPEN_FREE_MAP
            else -> if (hasUsableToken) PhoneStyle.MAPBOX else fallback()
        }
    }

    /**
     * Single source of truth for resolving the effective Mapbox access token.
     * Both Phone and Android Auto must call this to ensure identical gating and precedence:
     * - If [NavStateManager.usePhoneMapboxKey] is FALSE, returns null (Mapbox completely disabled).
     * - If TRUE, returns the trimmed token from NavStateManager, falling back to disk if needed.
     */
    fun effectiveMapboxToken(savedFallback: String? = null): String? {
        if (!NavStateManager.usePhoneMapboxKey) return null
        val token = NavStateManager.mapboxToken?.trim()?.takeIf { it.isNotBlank() }
            ?: savedFallback?.trim()?.takeIf { it.isNotBlank() }
        return token
    }

    /**
     * Identity of the style [phoneStyle] resolves to, for the phone
     * display's reload decision: ONLY the inputs that change that
     * style's built content. Raw connection state (comma found/lost,
     * transport, IP) is deliberately absent unless it changes the
     * resolution — keying reloads on it made every connection flap
     * trigger a full style reload, including reloads of the
     * byte-identical Mapbox style whenever a token made the comma
     * irrelevant to the choice, and stacked reloads starved the UI
     * into "isn't responding" (Param's field report, 2026-11-10,
     * with the route-feature toggles off, which cleared the fetch
     * path). Losing the comma with NO token still changes the
     * resolved style (COMMA_RASTER -> fallback), so the roads fix
     * keeps working — via the resolution, not the raw state.
     */
    fun phoneStyleKey(
        style: PhoneStyle,
        isNight: Boolean,
        traffic: Boolean,
        commaIp: String?,
        googleSession: String? = null
    ): String = when (style) {
        PhoneStyle.COMMA_RASTER -> "COMMA_RASTER|night=$isNight|ip=$commaIp"
        PhoneStyle.MAPBOX -> "MAPBOX|night=$isNight|traffic=$traffic"
        // The session is part of the key: a refreshed session changes
        // every tile URL, so its arrival must reload the style once.
        PhoneStyle.GOOGLE_RASTER -> "GOOGLE_RASTER|night=$isNight|session=${googleSession ?: "none"}"
        PhoneStyle.OPEN_FREE_MAP -> "OPEN_FREE_MAP|night=$isNight"
        PhoneStyle.CARTO_RASTER -> "CARTO_RASTER|night=$isNight"
    }

    /**
     * Whether Auto should retry Mapbox even though nothing moved:
     * with a token in play, a non-Mapbox frame (an early fallback,
     * e.g. a session-start race) must not stick for the whole
     * session. Retries are spaced by [RETRY_INTERVAL_MS];
     * [msSinceLastAttempt] is null when Mapbox was never attempted.
     */
    const val RETRY_INTERVAL_MS = 10_000L

    fun mapboxRetryDue(
        hasToken: Boolean,
        currentIsMapbox: Boolean,
        msSinceLastAttempt: Long?
    ): Boolean {
        if (!hasToken || currentIsMapbox) return false
        return msSinceLastAttempt == null || msSinceLastAttempt >= RETRY_INTERVAL_MS
    }
}
