package com.parampilot.auto

/**
 * Layer-2 visual identity: ParamAuto's map surfaces styled after the
 * current Google Maps app (Param's ask, 2026-11-06 — "match ParamAuto
 * to the same look as Google Maps latest app").
 *
 * One palette object so the phone and the Android Auto surface can
 * never drift apart (the same rule as ManeuverLogic / DisplayUnits):
 * both renderers take their colors from here instead of keeping
 * private copies.
 *
 * What this covers: everything the APP draws — route polylines, the
 * maneuver banner, cards, the speed cluster, and the hand-built
 * Mapbox vector style on the phone. Raster tiles the app does not
 * paint (the comma's own tiles, OSM stitch tiles) keep their source
 * colors; the AA Mapbox static frames use Mapbox's palest stock
 * styles (see [staticStyleId]).
 */
object GoogleMapsLook {

    // ---- Route polylines -------------------------------------------------
    // Google draws the active route as a bright blue core with a
    // white casing (not the dark navy casing + glow used before).
    const val ROUTE_BLUE: Int = 0xFF1A73E8.toInt()
    const val ROUTE_CASING: Int = 0xFFFFFFFF.toInt()
    const val ROUTE_GLOW: Int = 0x331A73E8
    const val ALT_ROUTE: Int = 0xFF9AA0A6.toInt()
    const val ALT_ROUTE_CASING: Int = 0xFFFFFFFF.toInt()

    // ---- Navigation banner ------------------------------------------------
    // The darker green of the current Google Maps turn banner — the
    // same values the phone banner already uses (TurnBannerView's
    // COLOR_GMAPS_NAV_DARK / COLOR_GMAPS_LANE_DARK).
    const val NAV_GREEN: Int = 0xFF0D652D.toInt()
    const val NAV_GREEN_DEEP: Int = 0xFF094C22.toInt()

    // ---- Cards and text ----------------------------------------------------
    const val CARD_DAY: Int = 0xFFFFFFFF.toInt()
    const val CARD_NIGHT: Int = 0xFF1E1F20.toInt()
    const val ON_CARD_DAY: Int = 0xFF1F1F1F.toInt()
    const val ON_CARD_NIGHT: Int = 0xFFE3E3E3.toInt()
    const val SECONDARY_DAY: Int = 0xFF5F6368.toInt()
    const val SECONDARY_NIGHT: Int = 0xFF9AA0A6.toInt()
    const val CARD_OUTLINE_DAY: Int = 0xFFDADCE0.toInt()
    const val CARD_OUTLINE_NIGHT: Int = 0xFF3C4043.toInt()
    const val SPEEDING_RED: Int = 0xFFD93025.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()

    /** Speed card background: white by day, dark gray by night, and
     *  Google red whenever the driver is over the limit (any mode). */
    fun speedCardBg(isNight: Boolean, isSpeeding: Boolean): Int = when {
        isSpeeding -> SPEEDING_RED
        isNight -> CARD_NIGHT
        else -> CARD_DAY
    }

    fun speedCardText(isNight: Boolean, isSpeeding: Boolean): Int = when {
        isSpeeding -> WHITE
        isNight -> ON_CARD_NIGHT
        else -> ON_CARD_DAY
    }

    fun speedCardUnit(isNight: Boolean, isSpeeding: Boolean): Int = when {
        isSpeeding -> 0xEEFFFFFF.toInt()
        isNight -> SECONDARY_NIGHT
        else -> SECONDARY_DAY
    }

    // ---- Mapbox static frames (Android Auto) --------------------------------
    /**
     * The stock Mapbox styles closest to the current Google Maps
     * look: the navigation styles when the traffic toggle wants the
     * nav treatment, otherwise Mapbox's palest base styles (light /
     * dark) instead of the more saturated streets / dark styles.
     */
    fun staticStyleId(isNight: Boolean, traffic: Boolean): String = when {
        traffic -> if (isNight) "navigation-night-v1" else "navigation-day-v1"
        else -> if (isNight) "dark-v11" else "light-v11"
    }

    // ---- Phone Mapbox vector style palette ----------------------------------
    /** Hex colors for the hand-built phone style (ThemeManager). */
    data class PhonePalette(
        val bg: String,
        val landcover: String,
        val water: String,
        val building: String,
        val minorRoad: String,
        val casing: String,
        val motorway: String,
        val trunk: String,
        val primary: String,
        val secondary: String,
        val tertiary: String
    )

    /**
     * Day: pale gray-green land, muted green parks, pale blue water,
     * white roads, soft-yellow freeways — the current Google Maps
     * daytime palette. Night: Google's dark gray-green land with
     * gray roads and muted orange freeways.
     */
    fun phonePalette(isNight: Boolean): PhonePalette = if (isNight) {
        PhonePalette(
            bg = "#212624",
            landcover = "#2C332E",
            water = "#16324A",
            building = "#33373A",
            minorRoad = "#3F4448",
            casing = "#14161A",
            motorway = "#D18F26",
            trunk = "#B87A1E",
            primary = "#AEB3B8",
            secondary = "#8A9096",
            tertiary = "#6E7378"
        )
    } else {
        PhonePalette(
            bg = "#EDF0E9",
            landcover = "#C3E6BE",
            water = "#A9C8F5",
            building = "#DCD9D2",
            minorRoad = "#FFFFFF",
            casing = "#D3D6CF",
            motorway = "#F9D67A",
            trunk = "#FCE3A1",
            primary = "#FFFFFF",
            secondary = "#FFFFFF",
            tertiary = "#FFFFFF"
        )
    }
}
