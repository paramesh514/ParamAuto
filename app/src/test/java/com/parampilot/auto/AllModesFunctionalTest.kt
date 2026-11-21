package com.parampilot.auto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AllModesFunctionalTest {

    // ==========================================
    // 1. LOCAL IP MODE TESTS
    // ==========================================

    @Test
    fun testLocalIpEndpointFormatting() {
        val bareIp = SgGalaxyClient.baseUrlFor("10.127.173.49")
        assertEquals("http://10.127.173.49:8082", bareIp)

        val hostPort = SgGalaxyClient.baseUrlFor("10.127.173.49:8082")
        assertEquals("http://10.127.173.49:8082", hostPort)

        val fullUrl = SgGalaxyClient.baseUrlFor("http://10.127.173.49:8082/")
        assertEquals("http://10.127.173.49:8082", fullUrl)
    }

    @Test
    fun testLocalIpNavStatusParsing() {
        val rawJson = """
        {
            "enabled": true,
            "status": "noDestination",
            "revision": "c9677c72d78540f58f23b3e40c164290",
            "routeKey": "643d5437104296e2",
            "location": {
                "longitude": -112.0740512,
                "latitude": 33.4484216,
                "bearing": 310.32,
                "source": "comma"
            },
            "recents": [
                {"name": "Home", "latitude": 33.44848, "longitude": -112.07407},
                {"name": "Costco Wholesale", "latitude": 33.7135, "longitude": -112.115}
            ]
        }
        """.trimIndent()

        val json = JSONObject(rawJson)
        val routeKey = json.optString("routeKey")
        assertEquals("643d5437104296e2", routeKey)

        val loc = json.getJSONObject("location")
        assertEquals(-112.0740512, loc.getDouble("longitude"), 0.0001)
        assertEquals(33.4484216, loc.getDouble("latitude"), 0.0001)
        assertEquals("comma", loc.getString("source"))

        val recents = json.getJSONArray("recents")
        assertEquals(2, recents.length())
        assertEquals("Home", recents.getJSONObject(0).getString("name"))
        assertEquals("Costco Wholesale", recents.getJSONObject(1).getString("name"))
    }

    @Test
    fun testLocalIpPlotsParsing() {
        val rawJson = """
        {
            "schemaVersion": 1,
            "state": "stale",
            "sessionId": "7ffb6d43f2fab51b",
            "sampleIndex": 1616
        }
        """.trimIndent()
        val json = JSONObject(rawJson)
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("stale", json.getString("state"))
        assertEquals(1616, json.getInt("sampleIndex"))
    }

    @Test
    fun testLocalIpMapRasterStyleGeneration() {
        val commaIp = "10.127.173.49:8082"
        val darkStyle = ThemeManager.getCommaMapStyleJson(commaIp, true)
        val darkJson = JSONObject(darkStyle)
        assertEquals(8, darkJson.getInt("version"))
        val darkSources = darkJson.getJSONObject("sources")
        val darkCommaSource = darkSources.getJSONObject("comma-raster-tiles")
        assertEquals("raster", darkCommaSource.getString("type"))
        val darkTiles = darkCommaSource.getJSONArray("tiles")
        assertEquals("http://10.127.173.49:8082/api/navigation/map/tiles/{z}/{x}/{y}.png?theme=dark", darkTiles.getString(0))

        val lightStyle = ThemeManager.getCommaMapStyleJson(commaIp, false)
        val lightJson = JSONObject(lightStyle)
        val lightTiles = lightJson.getJSONObject("sources").getJSONObject("comma-raster-tiles").getJSONArray("tiles")
        assertEquals("http://10.127.173.49:8082/api/navigation/map/tiles/{z}/{x}/{y}.png?theme=light", lightTiles.getString(0))
    }

    @Test
    fun testLocalIpParkedGateEnforcement() {
        val client = SgGalaxyClient("http://10.127.173.49:8082")
        val repo = SettingsRepository(client)

        // Vehicle in driving state must reject mutations
        assertFalse("Driving state must not be considered parked", repo.isVehicleParked("driving"))
        assertFalse("Over-speed driving must not be parked", repo.isVehicleParked("DRIVING"))

        // Parked state strictly required
        assertTrue("parked state must allow mutation", repo.isVehicleParked("parked"))
        assertFalse("standby is not strictly parked", repo.isVehicleParked("standby"))
        assertFalse("offroad is not strictly parked", repo.isVehicleParked("offroad"))
    }

    // ==========================================
    // 2. BLUETOOTH (BLE) MODE TESTS
    // ==========================================

    @Test
    fun testBleRequestBuilderAndPackets() {
        val id = "req-123"
        val session = "sess-abc"
        val counter = 42L
        val path = "/api/navigation/status"
        val method = "GET"
        val headers = mapOf("Accept" to "application/json")
        val body = ByteArray(0)

        val parsed = GalaxyBle.buildRequest(id, session, counter, path, method, headers, body)
        assertEquals(id, parsed.getString("id"))
        assertEquals(session, parsed.getString("session"))
        assertEquals(42L, parsed.getLong("counter"))
        assertEquals(path, parsed.getString("path"))
        assertEquals(method, parsed.getString("method"))
        assertEquals("application/json", parsed.getJSONObject("headers").getString("Accept"))

        // Test frame packetizer splitting over BLE MTU
        val payload = ByteArray(600) { it.toByte() }
        val dummyFrame = java.nio.ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()
        val mtu = 247 // standard BLE negotiated MTU
        val packets = GalaxyBle.packets(dummyFrame, mtu)
        assertTrue("Packets should be fragmented", packets.size >= 3)

        // Test frame reassembly
        val assembler = GalaxyBle.FrameAssembler()
        var assembled: ByteArray? = null
        for (p in packets) {
            val res = assembler.append(p)
            if (res != null) {
                assembled = res
                break
            }
        }
        assertNotNull("Reassembled frame should not be null", assembled)
        assertArrayEquals(payload, assembled)
    }

    @Test
    fun testBleResponseParsing() {
        val id = "req-1"
        val session = "sess-1"
        val counter = 10L
        val respObj = JSONObject().apply {
            put("id", id)
            put("session", session)
            put("counter", counter)
            put("status", 200)
            put("headers", JSONObject().apply { put("Content-Type", "application/json") })
            put("body", java.util.Base64.getEncoder().encodeToString("""{"enabled":true}""".toByteArray(Charsets.UTF_8)))
        }

        val bridgeResp = GalaxyBle.parseResponse(respObj, id, session, counter)
        assertEquals(200, bridgeResp.status)
        val bodyObj = bridgeResp.bodyJson()
        assertNotNull(bodyObj)
        assertTrue(bodyObj!!.getBoolean("enabled"))
    }

    // ==========================================
    // 3. GALAXY CLOUD MODE TESTS
    // ==========================================

    @Test
    fun testGalaxyCloudHostDetection() {
        val cloudUrl = "https://galaxy.firestar.link"
        val normalized = SgGalaxyClient.baseUrlFor(cloudUrl)
        assertEquals("https://galaxy.firestar.link", normalized)
        assertTrue(normalized.contains(SgGalaxyClient.CLOUD_HOST))

        // Device slug preserves full routing path
        val slugUrl = "https://galaxy.firestar.link/device/slug12345"
        val slugNormalized = SgGalaxyClient.baseUrlFor(slugUrl)
        assertEquals("https://galaxy.firestar.link/device/slug12345", slugNormalized)
        assertTrue(slugNormalized.contains(SgGalaxyClient.CLOUD_HOST))
    }

    @Test
    fun testOriginExtractionForCloudUrls() {
        val slugUrl = "https://galaxy.firestar.link/testSlug12345678"
        val origin = SgGalaxyClient.originFor(slugUrl)
        assertEquals("https://galaxy.firestar.link", origin)

        val localUrl = "http://192.168.68.14:8082/api/test"
        val localOrigin = SgGalaxyClient.originFor(localUrl)
        assertEquals("http://192.168.68.14:8082", localOrigin)
    }

    @Test
    fun testTransportTypeStateTransitions() {
        NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
        assertEquals(NavStateManager.TransportType.IP, NavStateManager.activeTransport)

        NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
        assertEquals(NavStateManager.TransportType.BLUETOOTH, NavStateManager.activeTransport)

        NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
        assertEquals(NavStateManager.TransportType.CLOUD, NavStateManager.activeTransport)
    }

    @Test
    fun testConnectionModeIsolationAndStatusDisplay() {
        // 1. AUTO mode: tries all (Local -> Bluetooth -> Cloud)
        NavStateManager.connectionMode = "auto"
        assertEquals("auto", NavStateManager.connectionMode)

        NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
        assertEquals(NavStateManager.TransportType.BLUETOOTH, NavStateManager.activeTransport)

        NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
        assertEquals(NavStateManager.TransportType.IP, NavStateManager.activeTransport)

        NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
        assertEquals(NavStateManager.TransportType.CLOUD, NavStateManager.activeTransport)

        // 2. BLUETOOTH mode: locked to bluetooth, not local, not cloud
        NavStateManager.connectionMode = "bluetooth"
        NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
        assertEquals("bluetooth", NavStateManager.connectionMode)
        assertEquals(NavStateManager.TransportType.BLUETOOTH, NavStateManager.activeTransport)
        assertNotEquals(NavStateManager.TransportType.IP, NavStateManager.activeTransport)
        assertNotEquals(NavStateManager.TransportType.CLOUD, NavStateManager.activeTransport)

        // 3. LOCAL mode: locked to local IP, not bluetooth, not cloud
        NavStateManager.connectionMode = "local"
        NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
        assertEquals("local", NavStateManager.connectionMode)
        assertEquals(NavStateManager.TransportType.IP, NavStateManager.activeTransport)
        assertNotEquals(NavStateManager.TransportType.BLUETOOTH, NavStateManager.activeTransport)
        assertNotEquals(NavStateManager.TransportType.CLOUD, NavStateManager.activeTransport)

        // 4. CLOUD mode: locked to cloud, not bluetooth, not local
        NavStateManager.connectionMode = "cloud"
        NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
        assertEquals("cloud", NavStateManager.connectionMode)
        assertEquals(NavStateManager.TransportType.CLOUD, NavStateManager.activeTransport)
        assertNotEquals(NavStateManager.TransportType.BLUETOOTH, NavStateManager.activeTransport)
        assertNotEquals(NavStateManager.TransportType.IP, NavStateManager.activeTransport)
    }

    @Test
    fun testLocalModeAutoScanAndFallbackPriority() {
        val scannedIp: String? = "192.168.68.14"
        val userProvidedIp: String? = "192.168.68.50"
        val lastScannedIp: String? = "192.168.68.9"

        val effectiveWhenFound = scannedIp ?: userProvidedIp ?: lastScannedIp
        assertEquals("192.168.68.14", effectiveWhenFound)

        val scannedNone: String? = null
        val effectiveWhenNotFound = scannedNone ?: userProvidedIp ?: lastScannedIp
        assertEquals("192.168.68.50", effectiveWhenNotFound)

        val userProvidedEmpty: String? = null
        val effectiveWhenNoUser = scannedNone ?: userProvidedEmpty ?: lastScannedIp
        assertEquals("192.168.68.9", effectiveWhenNoUser)
    }

    @Test
    fun testAutoModePriorityOrder() {
        // Priority must strictly be: Local -> Bluetooth -> Galaxy
        val allReady = TransportReadiness.autoOrder(hasPairingKey = true, hasGalaxyUrl = true, hasPassword = true)
        assertEquals(listOf(CommaClient.MODE_LOCAL, CommaClient.MODE_BLUETOOTH, CommaClient.MODE_CLOUD), allReady)
        assertEquals("Local -> Bluetooth -> Galaxy", TransportReadiness.autoOrderText(true, true, true))

        // When Bluetooth key is not configured yet
        val noBle = TransportReadiness.autoOrder(hasPairingKey = false, hasGalaxyUrl = true, hasPassword = true)
        assertEquals(listOf(CommaClient.MODE_LOCAL, CommaClient.MODE_CLOUD), noBle)
        assertEquals("Local -> Galaxy", TransportReadiness.autoOrderText(false, true, true))

        // When Cloud is not configured yet
        val noCloud = TransportReadiness.autoOrder(hasPairingKey = true, hasGalaxyUrl = false, hasPassword = false)
        assertEquals(listOf(CommaClient.MODE_LOCAL, CommaClient.MODE_BLUETOOTH), noCloud)
        assertEquals("Local -> Bluetooth", TransportReadiness.autoOrderText(true, false, false))

        // When neither is configured, Local is always first and present
        val onlyLocal = TransportReadiness.autoOrder(hasPairingKey = false, hasGalaxyUrl = false, hasPassword = false)
        assertEquals(listOf(CommaClient.MODE_LOCAL), onlyLocal)
        assertEquals("Local", TransportReadiness.autoOrderText(false, false, false))
    }

    @Test
    fun testHeartbeatTransportIdentification() {
        // Heartbeat identified transport adoption
        assertEquals(
            NavStateManager.TransportType.BLUETOOTH,
            HeartbeatTransport.identified(NavStateManager.TransportType.BLUETOOTH, NavStateManager.TransportType.IP)
        )
        assertEquals(
            NavStateManager.TransportType.IP,
            HeartbeatTransport.identified(NavStateManager.TransportType.IP, NavStateManager.TransportType.BLUETOOTH)
        )
        assertEquals(
            NavStateManager.TransportType.CLOUD,
            HeartbeatTransport.identified(NavStateManager.TransportType.CLOUD, NavStateManager.TransportType.BLUETOOTH)
        )
        // If served is null, keep current
        assertEquals(
            NavStateManager.TransportType.IP,
            HeartbeatTransport.identified(null, NavStateManager.TransportType.IP)
        )

        // useBle rules
        assertTrue(HeartbeatTransport.useBle(NavStateManager.TransportType.BLUETOOTH, CommaClient.MODE_AUTO))
        assertTrue(HeartbeatTransport.useBle(NavStateManager.TransportType.BLUETOOTH, CommaClient.MODE_BLUETOOTH))
        assertFalse(HeartbeatTransport.useBle(NavStateManager.TransportType.IP, CommaClient.MODE_AUTO))
        assertFalse(HeartbeatTransport.useBle(NavStateManager.TransportType.CLOUD, CommaClient.MODE_AUTO))

        // HTTP transport mappings
        assertEquals(NavStateManager.TransportType.IP, HeartbeatTransport.httpTransport(isCloudBase = false))
        assertEquals(NavStateManager.TransportType.CLOUD, HeartbeatTransport.httpTransport(isCloudBase = true))
    }

    @Test
    fun testBleFailoverCandidateOrdering() {
        val candidates = BleFailover.candidateOrder("192.168.68.16", "192.168.68.9")
        assertEquals(listOf("192.168.68.16", "192.168.68.9"), candidates)

        // Ignore cloud URLs in candidate order
        val withCloud = BleFailover.candidateOrder("https://galaxy.firestar.link", "192.168.68.16")
        assertEquals(listOf("192.168.68.16"), withCloud)
    }
}
