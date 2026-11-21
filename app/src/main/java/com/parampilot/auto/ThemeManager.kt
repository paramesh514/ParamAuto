package com.parampilot.auto

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * Manages app display themes:
 * - DARK: Mapbox Traffic Night v2 + dark HUD overlays
 * - LIGHT: Mapbox Traffic Day v2 + crisp white HUD overlays
 * - SYSTEM: Automatically switches between Dark and Light based on Android system mode
 */
object ThemeManager {

    // =========================================================================
    // Google Material 3, Google Maps, Gemini & Drive Design Tokens (Section 2.2)
    // =========================================================================
    const val COLOR_MD_BG = 0xFF131314.toInt()            // Night Canvas Background (OLED dark slate)
    const val COLOR_MD_SURFACE = 0xFF1E1F20.toInt()       // Floating Cards / Containers
    const val COLOR_MD_SURFACE_HI = 0xFF2B2B2C.toInt()    // High-priority Containers (Turn Banner / Dialogs)
    const val COLOR_MD_PRIMARY = 0xFFA8C7FA.toInt()       // Google M3 Accent Blue (Buttons, Active Tabs)
    const val COLOR_MD_OUTLINE = 0xFF444746.toInt()       // Subtle 1dp Borders / Card Dividers
    const val COLOR_MD_ON_SURF = 0xFFE3E3E3.toInt()       // Primary High-Contrast Typography
    const val COLOR_MD_ON_SUB = 0xFFC4C7C5.toInt()        // Secondary Subtitle Typography (60% opacity)

    const val COLOR_GMAPS_GREEN = 0xFF0F9D58.toInt()      // Google Maps Primary Turn Direction Header (#0F9D58)
    const val COLOR_GMAPS_ROUTE_BLUE = 0xFF1A73E8.toInt() // Active Route Polyline Primary (#1A73E8, current Google Maps)
    const val COLOR_GMAPS_ROUTE_ALT = 0xFF9AA0A6.toInt()  // Alternative Route Polyline De-emphasized (#9AA0A6)

    const val COLOR_GEMINI_START = 0xFF4285F4.toInt()     // Gemini AI Blue (#4285F4)
    const val COLOR_GEMINI_MID = 0xFF7C4DFF.toInt()       // Gemini AI Violet (#7C4DFF)
    const val COLOR_GEMINI_END = 0xFF00E5FF.toInt()       // Gemini AI Cyan (#00E5FF)
    const val COLOR_GEMINI_GLOW = 0x334285F4.toInt()      // 12dp Soft Gaussian Glow (#334285F4)

    private const val PREFS_NAME = "parampilot_theme_prefs"
    private const val KEY_THEME = "app_theme_mode"

    const val BLANK_DARK_STYLE_JSON =
        """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#0B0F19"}}]}"""

    enum class AppTheme(val key: String, val title: String) {
        DARK("dark", "Dark"),
        LIGHT("light", "Light / White"),
        SYSTEM("system", "System Default")
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTheme(context: Context): AppTheme {
        val key = getPrefs(context).getString(KEY_THEME, AppTheme.DARK.key)
        return AppTheme.values().firstOrNull { it.key == key } ?: AppTheme.DARK
    }

    fun setTheme(context: Context, theme: AppTheme) {
        getPrefs(context).edit().putString(KEY_THEME, theme.key).apply()
    }

    fun isNightMode(context: Context): Boolean {
        return when (getTheme(context)) {
            AppTheme.DARK -> true
            AppTheme.LIGHT -> false
            AppTheme.SYSTEM -> {
                val flags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                flags == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    /**
     * Hardware Headlight & Ambient Tunnel Synchronization (Section 7.1).
     * Hooks directly into vehicle physical headlight status reported by
     * androidx.car.app.CarContext.isDarkMode when in SYSTEM mode.
     */
    fun isCarNightMode(carContext: androidx.car.app.CarContext): Boolean {
        return try {
            when (getTheme(carContext)) {
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
                AppTheme.SYSTEM -> carContext.isDarkMode || isNightMode(carContext)
            }
        } catch (_: Exception) {
            isNightMode(carContext)
        }
    }

    @Volatile
    var isMapboxTokenValid: Boolean = true

    @Volatile
    private var cachedDarkJson: String? = null
    @Volatile
    private var cachedLibertyJson: String? = null
    @Volatile
    private var cachedCartoDarkJson: String? = null
    @Volatile
    private var cachedCartoLightJson: String? = null
    @Volatile
    private var cachedMapboxNightJson: String? = null
    @Volatile
    private var cachedMapboxDayJson: String? = null
    @Volatile
    private var cachedMapboxToken: String? = null
    @Volatile
    private var cachedMapboxFlagsKey: String? = null


    /** Day (Liberty) OpenFreeMap style URL — used for the offline tile pack (D1). */
    fun getDayStyleUrl(): String = "https://tiles.openfreemap.org/styles/liberty"

    /**
     * Primary phone-map style (order revised by Param, 2026-11-10 —
     * "3D enriched always"): Mapbox vector tiles first whenever a
     * usable token is present (the only 3D-enriched source), then
     * the comma-served local raster tiles (/api/navigation/map/tiles)
     * over local Wi-Fi / hotspot / LAN, then OpenFreeMap vector
     * styles or CARTO raster basemaps. The comma tiles remain the
     * zero-cellular fallback when Mapbox can't be reached.
     */
    fun getMapStyleJson(context: Context): String {
        val isNight = isNightMode(context)
        val commaIp = NavStateManager.commaIp
        val isLocalIpConnected = NavStateManager.activeTransport == NavStateManager.TransportType.IP &&
            NavStateManager.commaFound &&
            !commaIp.isNullOrBlank() &&
            !NavStateManager.isGalaxyCloud
        // Which style family loads is a layer-2 decision
        // (MapSourceChoice.phoneStyle); this layer only builds it.
        val token = MapSourceChoice.effectiveMapboxToken()
        val rawToken = NavStateManager.mapboxToken?.trim()
        // Never log token fragments (audit finding 41): presence + length only.
        val tokenPreview = if (rawToken.isNullOrEmpty()) "absent" else "present(len=${rawToken.length})"
        val googleKey = PhoneGoogle.effectiveGoogleKey()
        val googleSession = NavStateManager.googleTilesSession?.let {
            GoogleTiles.Session(it, NavStateManager.googleTilesSessionExpirySec)
        }
        val googleSessionUsable = GoogleTiles.sessionUsable(
            googleSession, System.currentTimeMillis() / 1000
        )
        val style = MapSourceChoice.phoneStyle(
            isLocalIpConnected = isLocalIpConnected,
            hasUsableToken = !token.isNullOrEmpty() && isMapboxTokenValid,
            useVectorMap = NavStateManager.usePhoneVectorMap,
            phoneMapSource = NavStateManager.phoneMapSource,
            hasGoogleSession = googleSessionUsable && googleKey != null
        )
        android.util.Log.i("ThemeManager", "getMapStyleJson: activeTransport=${NavStateManager.activeTransport}, commaFound=${NavStateManager.commaFound}, commaIp=$commaIp, isGalaxyCloud=${NavStateManager.isGalaxyCloud}, isLocalIpConnected=$isLocalIpConnected, usePhoneMapboxKey=${NavStateManager.usePhoneMapboxKey}, rawToken=$tokenPreview, tokenIsNull=${token == null}, isMapboxTokenValid=$isMapboxTokenValid, useVectorMap=${NavStateManager.usePhoneVectorMap} => resolved PhoneStyle=$style")
        return when (style) {
            MapSourceChoice.PhoneStyle.COMMA_RASTER -> {
                android.util.Log.i("ThemeManager", "Building COMMA_RASTER style JSON (ip=$commaIp)")
                getCommaMapStyleJson(commaIp!!, isNight)
            }
            MapSourceChoice.PhoneStyle.MAPBOX -> try {
                android.util.Log.i("ThemeManager", "Building MAPBOX vector style JSON with token=$tokenPreview")
                getMapboxStyleJson(context, token)
            } catch (e: Exception) {
                android.util.Log.e("ThemeManager", "Failed to build Mapbox style: ${e.message}", e)
                if (NavStateManager.usePhoneVectorMap) getOpenFreeMapStyleJson(context) else getCartoBasemapStyleJson(isNight)
            }
            MapSourceChoice.PhoneStyle.GOOGLE_RASTER -> {
                android.util.Log.i("ThemeManager", "Building GOOGLE_RASTER style JSON")
                val session = NavStateManager.googleTilesSession
                val key = PhoneGoogle.effectiveGoogleKey()
                if (session != null && key != null) {
                    GoogleTiles.styleJson(session, key, isNight)
                } else if (NavStateManager.usePhoneVectorMap) {
                    getOpenFreeMapStyleJson(context)
                } else {
                    getCartoBasemapStyleJson(isNight)
                }
            }
            MapSourceChoice.PhoneStyle.OPEN_FREE_MAP -> {
                android.util.Log.i("ThemeManager", "Building OPEN_FREE_MAP style JSON")
                getOpenFreeMapStyleJson(context)
            }
            MapSourceChoice.PhoneStyle.CARTO_RASTER -> {
                android.util.Log.i("ThemeManager", "Building CARTO_RASTER fallback style JSON")
                getCartoBasemapStyleJson(isNight)
            }
        }
    }

    /**
     * Authentic Comma-Primary MapLibre raster style JSON:
     * Loads tiles directly from comma local storage via GET /api/navigation/map/tiles/{z}/{x}/{y}.png?theme={theme}.
     */
    fun getCommaMapStyleJson(commaIp: String, isNight: Boolean): String {
        val baseUrl = SgGalaxyClient.baseUrlFor(commaIp)
        val theme = if (isNight) "dark" else "light"
        val tileUrl = "$baseUrl/api/navigation/map/tiles/{z}/{x}/{y}.png?theme=$theme"
        val bg = if (isNight) "#131314" else "#F8F9FA"
        return """
        {
          "version": 8,
          "sources": {
            "comma-raster-tiles": {
              "type": "raster",
              "tiles": [
                "$tileUrl"
              ],
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 19
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "$bg" }
            },
            {
              "id": "comma-raster-layer",
              "type": "raster",
              "source": "comma-raster-tiles",
              "minzoom": 0,
              "maxzoom": 19,
              "paint": { "raster-opacity": 1.0 }
            }
          ]
        }
        """.trimIndent()
    }

    /**
     * Mapbox vector style JSON (MapLibre style spec v8), built in code: one
     * vector source over mapbox-streets-v8 + mapbox-terrain-v2 with a minimal
     * navigation layer set (background, landcover, water, buildings, roads by
     * class — no text layers, so no glyphs dependency). Day/night follows the
     * app theme. When [token] is null or blank this returns the OpenFreeMap
     * vector style instead: a token-less Mapbox style would 401 every tile
     * request. CARTO is deliberately NOT used as a fallback (its CDN errors
     * on this app's requests, Param 2026-10-30); it remains only as the
     * Android Auto stitch fallback.
     */
    fun getMapboxStyleJson(context: Context, token: String?): String {
        val cleanToken = token?.trim().orEmpty()
        if (cleanToken.isEmpty()) return getOpenFreeMapStyleJson(context)
        val traffic = NavStateManager.usePhoneTraffic
        val flagsKey = MapDisplayOptions.styleCacheKey(isNightMode(context), traffic)
        if (cachedMapboxToken != cleanToken || cachedMapboxFlagsKey != flagsKey) {
            cachedMapboxToken = cleanToken
            cachedMapboxFlagsKey = flagsKey
            cachedMapboxNightJson = null
            cachedMapboxDayJson = null
        }
        return if (isNightMode(context)) {
            cachedMapboxNightJson ?: buildMapboxStyleJson(isNight = true, cleanToken, traffic).also { cachedMapboxNightJson = it }
        } else {
            cachedMapboxDayJson ?: buildMapboxStyleJson(isNight = false, cleanToken, traffic).also { cachedMapboxDayJson = it }
        }
    }

    private fun buildMapboxStyleJson(isNight: Boolean, token: String, traffic: Boolean): String {
        // Google-Maps-look palette (layer 2, GoogleMapsLook) — the
        // current Google Maps app's pale day colors / dark grays.
        val palette = GoogleMapsLook.phonePalette(isNight)
        val bg = palette.bg
        val landcover = palette.landcover
        val water = palette.water
        val building = palette.building
        val minorRoad = palette.minorRoad
        val casing = palette.casing
        val motorway = palette.motorway
        val trunk = palette.trunk
        val primary = palette.primary
        val secondary = palette.secondary
        val tertiary = palette.tertiary
        // 3D / traffic segments: buildings are ALWAYS height
        // extrusions from streets-v8 (Param, 2026-11-10 — 3D
        // enriched always; the 2026-11-04 toggle is retired);
        // traffic needs its own Mapbox source and stays a toggle.
        val trafficSource = if (traffic) """,
            "traffic": {
              "type": "vector",
              "tiles": [
                "https://a.tiles.mapbox.com/v4/mapbox.mapbox-traffic-v1/{z}/{x}/{y}.vector.pbf?access_token=$token",
                "https://b.tiles.mapbox.com/v4/mapbox.mapbox-traffic-v1/{z}/{x}/{y}.vector.pbf?access_token=$token",
                "https://c.tiles.mapbox.com/v4/mapbox.mapbox-traffic-v1/{z}/{x}/{y}.vector.pbf?access_token=$token",
                "https://d.tiles.mapbox.com/v4/mapbox.mapbox-traffic-v1/{z}/{x}/{y}.vector.pbf?access_token=$token"
              ],
              "minzoom": 6,
              "maxzoom": 16
            }""" else ""
        val buildingLayer = """{
              "id": "building",
              "type": "fill-extrusion",
              "source": "mapbox",
              "source-layer": "building",
              "minzoom": 14,
              "paint": {
                "fill-extrusion-color": "$building",
                "fill-extrusion-height": ["get", "height"],
                "fill-extrusion-base": ["get", "min_height"],
                "fill-extrusion-opacity": 0.9
              }
            }"""
        val trafficLayer = if (traffic) """,
            {
              "id": "traffic",
              "type": "line",
              "source": "traffic",
              "source-layer": "traffic",
              "paint": {
                "line-color": ["match", ["get", "congestion"],
                  "low", "#34A853",
                  "moderate", "#FBBC04",
                  "heavy", "#F2994A",
                  "severe", "#EA4335",
                  "#34A853"],
                "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.0, 16, 5],
                "line-opacity": 0.85
              },
              "layout": { "line-cap": "round", "line-join": "round" }
            }""" else ""
        return """
        {
          "version": 8,
          "sources": {
            "mapbox": {
              "type": "vector",
              "tiles": [
                "https://a.tiles.mapbox.com/v4/mapbox.mapbox-streets-v8,mapbox.mapbox-terrain-v2/{z}/{x}/{y}.vector.pbf?access_token=$token",
                "https://b.tiles.mapbox.com/v4/mapbox.mapbox-streets-v8,mapbox.mapbox-terrain-v2/{z}/{x}/{y}.vector.pbf?access_token=$token",
                "https://c.tiles.mapbox.com/v4/mapbox.mapbox-streets-v8,mapbox.mapbox-terrain-v2/{z}/{x}/{y}.vector.pbf?access_token=$token",
                "https://d.tiles.mapbox.com/v4/mapbox.mapbox-streets-v8,mapbox.mapbox-terrain-v2/{z}/{x}/{y}.vector.pbf?access_token=$token"
              ],
              "minzoom": 0,
              "maxzoom": 16,
              "attribution": "© Mapbox © OpenStreetMap contributors"
            }$trafficSource
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "$bg" }
            },
            {
              "id": "landcover",
              "type": "fill",
              "source": "mapbox",
              "source-layer": "landcover",
              "paint": { "fill-color": "$landcover" }
            },
            {
              "id": "water",
              "type": "fill",
              "source": "mapbox",
              "source-layer": "water",
              "paint": { "fill-color": "$water" }
            },
            $buildingLayer,
            {
              "id": "roads-minor",
              "type": "line",
              "source": "mapbox",
              "source-layer": "road",
              "paint": {
                "line-color": "$minorRoad",
                "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 16, 3]
              },
              "layout": { "line-cap": "round", "line-join": "round" }
            },
            {
              "id": "roads-major-casing",
              "type": "line",
              "source": "mapbox",
              "source-layer": "road",
              "filter": ["in", "class", "motorway", "trunk", "primary", "secondary", "tertiary"],
              "paint": {
                "line-color": "$casing",
                "line-width": ["interpolate", ["linear"], ["zoom"], 10, 2, 16, 10]
              },
              "layout": { "line-cap": "round", "line-join": "round" }
            },
            {
              "id": "roads-major",
              "type": "line",
              "source": "mapbox",
              "source-layer": "road",
              "filter": ["in", "class", "motorway", "trunk", "primary", "secondary", "tertiary"],
              "paint": {
                "line-color": ["match", ["get", "class"],
                  "motorway", "$motorway",
                  "trunk", "$trunk",
                  "primary", "$primary",
                  "secondary", "$secondary",
                  "tertiary", "$tertiary",
                  "$primary"],
                "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.2, 16, 7]
              },
              "layout": { "line-cap": "round", "line-join": "round" }
            }$trafficLayer
          ]
        }
        """.trimIndent()
    }

    /**
     * CARTO basemap raster style JSON (MapLibre style spec v8), built in code:
     * one raster source listing all four CARTO subdomains, tileSize 256, and a
     * single raster layer. Night -> dark_all, day -> light_all. Free and
     * token-less; the same tile URLs the Android Auto screen uses.
     */
    fun getCartoBasemapStyleJson(isNight: Boolean): String {
        return if (isNight) {
            cachedCartoDarkJson ?: buildCartoBasemapStyleJson(isNight = true).also { cachedCartoDarkJson = it }
        } else {
            cachedCartoLightJson ?: buildCartoBasemapStyleJson(isNight = false).also { cachedCartoLightJson = it }
        }
    }

    private fun buildCartoBasemapStyleJson(isNight: Boolean): String {
        val stylePath = if (isNight) "dark_all" else "rastertiles/voyager"
        val bg = if (isNight) "#131314" else "#F8F9FA"
        return """
        {
          "version": 8,
          "sources": {
            "carto": {
              "type": "raster",
              "tiles": [
                "https://a.basemaps.cartocdn.com/$stylePath/{z}/{x}/{y}.png",
                "https://b.basemaps.cartocdn.com/$stylePath/{z}/{x}/{y}.png",
                "https://c.basemaps.cartocdn.com/$stylePath/{z}/{x}/{y}.png",
                "https://d.basemaps.cartocdn.com/$stylePath/{z}/{x}/{y}.png"
              ],
              "tileSize": 256,
              "attribution": "© OpenStreetMap contributors © CARTO"
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "$bg" }
            },
            {
              "id": "carto-layer",
              "type": "raster",
              "source": "carto",
              "minzoom": 0,
              "maxzoom": 19,
              "paint": { "raster-opacity": 1.0 }
            }
          ]
        }
        """.trimIndent()
    }

    /**
     * Fallback style: loads the authentic 3D MapLibre style JSON with extruded
     * 3D buildings (fill-extrusion) from local assets, referencing OpenFreeMap
     * vector tile servers. Used only when the primary CARTO style fails to
     * load (MainActivity's DidFailLoadingMap path), so a CARTO outage still
     * leaves a working map.
     */
    fun getOpenFreeMapStyleJson(context: Context): String {
        return try {
            val night = isNightMode(context)
            val assetName = if (night) "dark_3d.json" else "liberty_3d.json"
            if (night) {
                cachedDarkJson ?: context.assets.open(assetName).bufferedReader().use { it.readText() }.also { cachedDarkJson = it }
            } else {
                cachedLibertyJson ?: context.assets.open(assetName).bufferedReader().use { it.readText() }.also { cachedLibertyJson = it }
            }
        } catch (_: Exception) {
            // Callers parse the result as style JSON (Style.Builder().fromJson),
            // so the fallback must be JSON too — returning a style URL here was
            // a latent bug (review B6).
            BLANK_DARK_STYLE_JSON
        }
    }


}
