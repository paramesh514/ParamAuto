package com.parampilot.auto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MaxspeedAnnotationsTest {

    @Test
    fun testParseDirectionsRouteMph() {
        val jsonStr = """
        {
            "routes": [
                {
                    "geometry": {
                        "coordinates": [
                            [-112.0, 33.5],
                            [-112.001, 33.501],
                            [-112.002, 33.502],
                            [-112.003, 33.503]
                        ]
                    },
                    "legs": [
                        {
                            "annotation": {
                                "maxspeed": [
                                    {"speed": 45, "unit": "mph"},
                                    {"unknown": true},
                                    {"speed": 65, "unit": "mph"}
                                ]
                            }
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val routeObj = JSONObject(jsonStr).getJSONArray("routes").getJSONObject(0)
        val series = MaxspeedAnnotations.parseDirectionsRoute(routeObj)
        assertNotNull(series)
        assertEquals(4, series!!.points.size)
        assertEquals(3, series.limitsMph.size)
        assertEquals(45, series.limitsMph[0])
        assertNull(series.limitsMph[1])
        assertEquals(65, series.limitsMph[2])
    }

    @Test
    fun testParseDirectionsRouteKmhConversion() {
        val jsonStr = """
        {
            "geometry": {
                "coordinates": [
                    [-112.0, 33.5],
                    [-112.001, 33.501]
                ]
            },
            "legs": [
                {
                    "annotations": {
                        "maxspeed": [
                            {"speed": 100, "unit": "km/h"}
                        ]
                    }
                }
            ]
        }
        """.trimIndent()

        val series = MaxspeedAnnotations.parseDirectionsRoute(JSONObject(jsonStr))
        assertNotNull(series)
        // 100 km/h = ~62.137 mph -> 62 mph
        assertEquals(62, series!!.limitsMph[0])
    }

    @Test
    fun testLimitAtWithDirectMatch() {
        val points = listOf(
            LatLng(33.500, -112.000),
            LatLng(33.501, -112.001),
            LatLng(33.502, -112.002)
        )
        val limits = listOf(35, 45)
        val series = MaxspeedAnnotations.Series(points, limits)

        val limitNearStart = MaxspeedAnnotations.limitAt(series, 33.5001, -112.0001)
        assertEquals(35, limitNearStart)

        val limitNearSecond = MaxspeedAnnotations.limitAt(series, 33.5011, -112.0011)
        assertEquals(45, limitNearSecond)
    }

    @Test
    fun testLimitAtFallbackWhenSegmentIsUnknown() {
        // Segment 0 is unknown (driveway/intersection), Segment 1 has 55 mph
        val points = listOf(
            LatLng(33.500, -112.000),
            LatLng(33.501, -112.001),
            LatLng(33.502, -112.002)
        )
        val limits = listOf<Int?>(null, 55)
        val series = MaxspeedAnnotations.Series(points, limits)

        // Car is at point 0 (where limit is null)
        val limit = MaxspeedAnnotations.limitAt(series, 33.500, -112.000)
        // Should fall back to 55 from adjacent segment ahead
        assertEquals(55, limit)
    }

    @Test
    fun testUpdateSpeedLimitFromRouteIntegration() {
        NavStateManager.speedLimitMph = 0
        NavStateManager.usePhoneSpeedLimit = true

        val points = listOf(
            LatLng(33.500, -112.000),
            LatLng(33.501, -112.001)
        )
        val limits = listOf(40)
        NavStateManager.routeLimitSeries = MaxspeedAnnotations.Series(points, limits)

        // Simulate GPS update
        NavStateManager.updateSpeedLimitFromRoute(33.5001, -112.0001)

        assertEquals(40, NavStateManager.speedLimitMph)
        val pkt = NavStateManager.latestPacket
        if (pkt != null) {
            assertEquals("mapbox", pkt.speedLimit.source)
        }
    }
}
