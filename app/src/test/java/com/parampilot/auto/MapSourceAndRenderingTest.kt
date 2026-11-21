package com.parampilot.auto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression and invariant tests for map source resolution, Mapbox style JSON generation,
 * Android Auto plan ordering, and key gating.
 *
 * Ensures that setting a Mapbox key or Vector Map will NEVER be silently ignored,
 * that Mapbox styles never use the corrupted v4.json TileJSON pattern, and that
 * fallback cascades operate cleanly under all transport and network states.
 */
class MapSourceAndRenderingTest {

    // =========================================================================
    // 1. PHONE MAP STYLE SELECTION PRIORITY TESTS
    // =========================================================================

    @Test
    fun testMapboxKeyHasAbsolutePriorityOverLocalIp() {
        // Even if connected to Comma on local IP/LAN, if user set a usable Mapbox key,
        // it MUST resolve to MAPBOX (preventing 404 empty canvas from Comma local tiles).
        val style = MapSourceChoice.phoneStyle(
            isLocalIpConnected = true,
            hasUsableToken = true,
            useVectorMap = false
        )
        assertEquals("Usable Mapbox token must take precedence over Local IP", MapSourceChoice.PhoneStyle.MAPBOX, style)
    }

    @Test
    fun testMapboxKeyHasPriorityOverVectorMapToggle() {
        // If both Mapbox key and Vector Map toggle are active, Mapbox is the primary vector source
        val style = MapSourceChoice.phoneStyle(
            isLocalIpConnected = true,
            hasUsableToken = true,
            useVectorMap = true
        )
        assertEquals("Mapbox must take precedence when both key and vector toggle are set", MapSourceChoice.PhoneStyle.MAPBOX, style)
    }

    @Test
    fun testEffectiveMapboxTokenSharedBusinessLogic() {
        // Toggle OFF: always null, regardless of saved token
        NavStateManager.usePhoneMapboxKey = false
        NavStateManager.mapboxToken = "pk.test_token_123"
        assertNull("When toggle is off, effectiveMapboxToken must return null", MapSourceChoice.effectiveMapboxToken())
        assertNull("Fallback token must also be ignored when toggle is off", MapSourceChoice.effectiveMapboxToken("pk.disk_fallback_token"))

        // Toggle ON: returns token from NavStateManager
        NavStateManager.usePhoneMapboxKey = true
        NavStateManager.mapboxToken = "pk.test_token_123"
        assertEquals("pk.test_token_123", MapSourceChoice.effectiveMapboxToken())

        // Toggle ON with whitespace: trimmed
        NavStateManager.mapboxToken = "  pk.test_token_trimmed   "
        assertEquals("pk.test_token_trimmed", MapSourceChoice.effectiveMapboxToken())

        // Toggle ON with blank memory token: falls back to disk token
        NavStateManager.mapboxToken = ""
        assertEquals("pk.fallback_disk_key", MapSourceChoice.effectiveMapboxToken("pk.fallback_disk_key"))

        // Reset
        NavStateManager.usePhoneMapboxKey = false
        NavStateManager.mapboxToken = null
    }

    @Test
    fun testBluetoothWithMapboxKeyResolvesToMapbox() {
        // Over Bluetooth (not local IP), with a Mapbox key, must resolve to MAPBOX
        val style = MapSourceChoice.phoneStyle(
            isLocalIpConnected = false,
            hasUsableToken = true,
            useVectorMap = false
        )
        assertEquals(MapSourceChoice.PhoneStyle.MAPBOX, style)
    }

    @Test
    fun testVectorMapToggleUsedWhenNoMapboxKey() {
        // When no Mapbox key is saved, but Vector Map toggle is enabled, use OPEN_FREE_MAP
        val styleOverIp = MapSourceChoice.phoneStyle(
            isLocalIpConnected = true,
            hasUsableToken = false,
            useVectorMap = true
        )
        assertEquals("Vector Map toggle must override Comma raster when no key is set", MapSourceChoice.PhoneStyle.OPEN_FREE_MAP, styleOverIp)

        val styleOverBle = MapSourceChoice.phoneStyle(
            isLocalIpConnected = false,
            hasUsableToken = false,
            useVectorMap = true
        )
        assertEquals("Vector Map toggle must override Carto when over Bluetooth", MapSourceChoice.PhoneStyle.OPEN_FREE_MAP, styleOverBle)
    }

    @Test
    fun testFallbackToCommaRasterWhenNoKeyAndNoVectorToggle() {
        // When no key and no vector toggle, but on Comma LAN: use COMMA_RASTER
        val style = MapSourceChoice.phoneStyle(
            isLocalIpConnected = true,
            hasUsableToken = false,
            useVectorMap = false
        )
        assertEquals(MapSourceChoice.PhoneStyle.COMMA_RASTER, style)
    }

    @Test
    fun testFallbackToCartoRasterWhenOfflineAndNoKey() {
        // When over Bluetooth/cellular and no key and no vector toggle: use CARTO_RASTER
        val style = MapSourceChoice.phoneStyle(
            isLocalIpConnected = false,
            hasUsableToken = false,
            useVectorMap = false
        )
        assertEquals(MapSourceChoice.PhoneStyle.CARTO_RASTER, style)
    }

    // =========================================================================
    // 2. MAPBOX STYLE JSON STRUCTURE & REGRESSION TESTS
    // =========================================================================

    @Test
    fun testMapboxStyleJsonUsesDirectPbfTilesAndNoTileJsonUrl() {
        // Regression test: MapLibre Native's URL normalizer corrupts composite TileJSON URLs
        // (https://api.mapbox.com/v4/streets,terrain.json) into /v4/v4.json (404 Not Found).
        // The style builder MUST supply direct vector PBF tiles.
        val dummyToken = "pk.test_dummy_token.test_signature"

        // Reflective or direct invocation of buildMapboxStyleJson logic
        val method = ThemeManager::class.java.getDeclaredMethod(
            "buildMapboxStyleJson",
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val styleJsonStr = method.invoke(ThemeManager, false, dummyToken, false, false) as String
        val json = JSONObject(styleJsonStr)

        assertEquals(8, json.getInt("version"))
        assertTrue("Sources object must exist", json.has("sources"))

        val sources = json.getJSONObject("sources")
        assertTrue("mapbox source must exist", sources.has("mapbox"))

        val mapboxSource = sources.getJSONObject("mapbox")
        assertEquals("vector", mapboxSource.getString("type"))

        // CRITICAL CHECK: Must NOT contain "url" pointing to TileJSON
        assertFalse(
            "Mapbox source must NEVER have a 'url' field that causes MapLibre v4.json 404 corruption!",
            mapboxSource.has("url")
        )

        // CRITICAL CHECK: Must have "tiles" array with direct .vector.pbf templates
        assertTrue("Mapbox source must have 'tiles' array", mapboxSource.has("tiles"))
        val tilesArray = mapboxSource.getJSONArray("tiles")
        assertTrue("tiles array must not be empty", tilesArray.length() >= 2)

        for (i in 0 until tilesArray.length()) {
            val tileUrl = tilesArray.getString(i)
            assertTrue("Tile URL must be secure HTTPS", tileUrl.startsWith("https://"))
            assertTrue("Tile URL must point to vector.pbf", tileUrl.contains("{z}/{x}/{y}.vector.pbf"))
            assertTrue("Tile URL must carry the access token", tileUrl.contains("access_token=$dummyToken"))
            assertFalse("Tile URL must not contain .json endpoint", tileUrl.contains(".json"))
        }

        assertEquals(0, mapboxSource.getInt("minzoom"))
        assertEquals(16, mapboxSource.getInt("maxzoom"))

        // Check required navigation layers
        val layers = json.getJSONArray("layers")
        val layerIds = (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
        assertTrue(layerIds.contains("background"))
        assertTrue(layerIds.contains("landcover"))
        assertTrue(layerIds.contains("water"))
        assertTrue(layerIds.contains("building"))
        assertTrue(layerIds.contains("roads-minor"))
        assertTrue(layerIds.contains("roads-major"))
    }

    @Test
    fun testMapboxStyleJsonWithTrafficUsesDirectPbfTiles() {
        val dummyToken = "pk.test_dummy_token.test_signature"

        val method = ThemeManager::class.java.getDeclaredMethod(
            "buildMapboxStyleJson",
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }

        // Test with traffic = true
        val styleJsonStr = method.invoke(ThemeManager, true, dummyToken, true, true) as String
        val json = JSONObject(styleJsonStr)
        val sources = json.getJSONObject("sources")

        assertTrue("traffic source must be present when traffic=true", sources.has("traffic"))
        val trafficSource = sources.getJSONObject("traffic")
        assertEquals("vector", trafficSource.getString("type"))
        assertFalse("traffic source must not have 'url' field", trafficSource.has("url"))
        assertTrue("traffic source must have 'tiles' array", trafficSource.has("tiles"))

        val trafficTiles = trafficSource.getJSONArray("tiles")
        for (i in 0 until trafficTiles.length()) {
            val tUrl = trafficTiles.getString(i)
            assertTrue(tUrl.contains("mapbox.mapbox-traffic-v1/{z}/{x}/{y}.vector.pbf"))
            assertTrue(tUrl.contains("access_token=$dummyToken"))
        }

        // Verify 3D building layer is fill-extrusion
        val layers = json.getJSONArray("layers")
        var foundBuildingExtrusion = false
        var foundTrafficLine = false
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            if (l.getString("id") == "building" && l.getString("type") == "fill-extrusion") {
                foundBuildingExtrusion = true
            }
            if (l.getString("id") == "traffic" && l.getString("type") == "line") {
                foundTrafficLine = true
            }
        }
        assertTrue("3D building extrusion must be active when 3D=true", foundBuildingExtrusion)
        assertTrue("Traffic line layer must be present when traffic=true", foundTrafficLine)
    }

    // =========================================================================
    // 3. ANDROID AUTO RENDERING PLAN TESTS
    // =========================================================================

    @Test
    fun testAaPlanPrioritizesMapboxStaticWhenTokenAvailable() {
        val planWithToken = MapSourceChoice.aaPlan(isCommaLan = true, hasToken = true)
        assertEquals("Mapbox Static must be first when token is present", MapSourceChoice.AaSource.MAPBOX_STATIC, planWithToken.first())
        assertTrue("Comma stitch must be available as fallback", planWithToken.contains(MapSourceChoice.AaSource.COMMA_STITCH))
        assertTrue("Full stitch must be present as final fallback", planWithToken.contains(MapSourceChoice.AaSource.FULL_STITCH))

        val planBleWithToken = MapSourceChoice.aaPlan(isCommaLan = false, hasToken = true)
        assertEquals(listOf(MapSourceChoice.AaSource.MAPBOX_STATIC, MapSourceChoice.AaSource.FULL_STITCH), planBleWithToken)
    }

    @Test
    fun testAaPlanWithoutToken() {
        val planLan = MapSourceChoice.aaPlan(isCommaLan = true, hasToken = false)
        assertFalse("Mapbox static must not be planned without token", planLan.contains(MapSourceChoice.AaSource.MAPBOX_STATIC))
        assertEquals(listOf(MapSourceChoice.AaSource.COMMA_STITCH, MapSourceChoice.AaSource.FULL_STITCH), planLan)

        val planBle = MapSourceChoice.aaPlan(isCommaLan = false, hasToken = false)
        assertEquals(listOf(MapSourceChoice.AaSource.FULL_STITCH), planBle)
    }

    @Test
    fun testMapboxRetryLogic() {
        // Without token: never retry
        assertFalse(MapSourceChoice.mapboxRetryDue(hasToken = false, currentIsMapbox = false, msSinceLastAttempt = null))
        assertFalse(MapSourceChoice.mapboxRetryDue(hasToken = false, currentIsMapbox = false, msSinceLastAttempt = 20_000L))

        // Already displaying Mapbox: no retry needed
        assertFalse(MapSourceChoice.mapboxRetryDue(hasToken = true, currentIsMapbox = true, msSinceLastAttempt = 20_000L))

        // Token present and not currently on Mapbox: retry immediately if never attempted
        assertTrue(MapSourceChoice.mapboxRetryDue(hasToken = true, currentIsMapbox = false, msSinceLastAttempt = null))

        // Token present, not on Mapbox, but recently attempted: don't retry yet
        assertFalse(MapSourceChoice.mapboxRetryDue(hasToken = true, currentIsMapbox = false, msSinceLastAttempt = 5_000L))

        // Token present, not on Mapbox, retry interval elapsed: retry!
        assertTrue(MapSourceChoice.mapboxRetryDue(hasToken = true, currentIsMapbox = false, msSinceLastAttempt = 10_000L))
        assertTrue(MapSourceChoice.mapboxRetryDue(hasToken = true, currentIsMapbox = false, msSinceLastAttempt = 15_000L))
    }

    // =========================================================================
    // 4. MAPBOX KEY GATE INVARIANTS
    // =========================================================================

    @Test
    fun testMapboxKeyGatePreventsOrphanedToggles() {
        // Turning on toggle without a token MUST force switch back
        assertTrue(MapboxKeyGate.mustSwitchBack(toggleOn = true, hasToken = false))

        // Turning on toggle with a token is valid
        assertFalse(MapboxKeyGate.mustSwitchBack(toggleOn = true, hasToken = true))

        // Toggle is off: no action needed
        assertFalse(MapboxKeyGate.mustSwitchBack(toggleOn = false, hasToken = false))
        assertFalse(MapboxKeyGate.mustSwitchBack(toggleOn = false, hasToken = true))
    }

    // =========================================================================
    // 5. DISPLAY OPTIONS & PITCH
    // =========================================================================

    @Test
    fun testMapDisplayOptionsStyles() {
        assertEquals("streets-v11", MapDisplayOptions.staticStyleId(isNight = false, traffic = false))
        assertEquals("dark-v10", MapDisplayOptions.staticStyleId(isNight = true, traffic = false))
        assertEquals("navigation-day-v1", MapDisplayOptions.staticStyleId(isNight = false, traffic = true))
        assertEquals("navigation-night-v1", MapDisplayOptions.staticStyleId(isNight = true, traffic = true))
    }

    @Test
    fun testMapDisplayOptionsPitch() {
        // Static 3D pitch only applies when bearing != 0
        assertEquals(35, MapDisplayOptions.staticPitch(bearingDeg = 45, threeD = true))
        assertEquals(0, MapDisplayOptions.staticPitch(bearingDeg = 0, threeD = true))
        assertEquals(0, MapDisplayOptions.staticPitch(bearingDeg = 90, threeD = false))
    }
}

