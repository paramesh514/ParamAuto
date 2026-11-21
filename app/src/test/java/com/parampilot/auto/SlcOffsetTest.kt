package com.parampilot.auto

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SlcOffsetTest {

    @Before
    fun setUp() {
        NavStateManager.sgOffsetsMph = null
    }

    @Test
    fun testDefaultFallbackWhenOffsetsNull() {
        NavStateManager.sgOffsetsMph = null
        assertEquals(0, NavStateManager.getSlcOffsetMph(45))
        assertEquals(0, NavStateManager.getSlcOffsetMph(65))
    }

    @Test
    fun testAllSevenSpeedBands() {
        // Configure custom SLC offsets for 7 bands:
        // Band 0 (<25 mph): 0 mph offset
        // Band 1 (25-34 mph): +3 mph offset
        // Band 2 (35-44 mph): +5 mph offset
        // Band 3 (45-54 mph): +5 mph offset
        // Band 4 (55-64 mph): +7 mph offset
        // Band 5 (65-74 mph): +8 mph offset
        // Band 6 (75+ mph): +10 mph offset
        NavStateManager.sgOffsetsMph = doubleArrayOf(0.0, 3.0, 5.0, 5.0, 7.0, 8.0, 10.0)

        assertEquals(0, NavStateManager.getSlcOffsetMph(20))
        assertEquals(3, NavStateManager.getSlcOffsetMph(30))
        assertEquals(5, NavStateManager.getSlcOffsetMph(40))
        assertEquals(5, NavStateManager.getSlcOffsetMph(50))
        assertEquals(7, NavStateManager.getSlcOffsetMph(60))
        assertEquals(8, NavStateManager.getSlcOffsetMph(70))
        assertEquals(10, NavStateManager.getSlcOffsetMph(80))
    }
    @Test
    fun testParseSlcOffsetsFromGalaxyPayload() {
        val jsonStr = """{
            "rows": [
                {"label": "0–<25 mph offset", "value": "0"},
                {"label": "25–<34 mph offset", "value": "2"},
                {"label": "34–<44 mph offset", "value": "3"},
                {"label": "44–<54 mph offset", "value": "3"},
                {"label": "54–<64 mph offset", "value": "4"},
                {"label": "64–<74 mph offset", "value": "4"},
                {"label": "74–<99 mph offset", "value": "5"}
            ]
        }"""
        val root = org.json.JSONObject(jsonStr)
        val parsed = SgGalaxyClient.parseSlcOffsets(root)
        org.junit.Assert.assertNotNull(parsed)
        org.junit.Assert.assertArrayEquals(
            doubleArrayOf(0.0, 2.0, 3.0, 3.0, 4.0, 4.0, 5.0),
            parsed,
            0.001
        )

        NavStateManager.sgOffsetsMph = parsed
        assertEquals(0, NavStateManager.getSlcOffsetMph(20))
        assertEquals(2, NavStateManager.getSlcOffsetMph(30))
        assertEquals(3, NavStateManager.getSlcOffsetMph(40))
        assertEquals(3, NavStateManager.getSlcOffsetMph(50))
        assertEquals(4, NavStateManager.getSlcOffsetMph(60))
        assertEquals(4, NavStateManager.getSlcOffsetMph(70))
        assertEquals(5, NavStateManager.getSlcOffsetMph(80))
    }
}
