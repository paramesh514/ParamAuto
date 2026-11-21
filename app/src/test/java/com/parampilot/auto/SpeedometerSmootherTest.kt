package com.parampilot.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpeedometerSmootherTest {

    @Before
    fun setUp() {
        SpeedometerSmoother.reset()
    }

    @Test
    fun testInitialSpeed() {
        // Initial reading initializes directly to raw speed without lag
        val speed = SpeedometerSmoother.filter(65.0)
        assertEquals(65, speed)
    }

    @Test
    fun testEmaProgression() {
        SpeedometerSmoother.filter(60.0)
        // With ALPHA = 0.4:
        // Next reading 70.0 -> smoothed = 60.0 * 0.6 + 70.0 * 0.4 = 36.0 + 28.0 = 64.0
        val step1 = SpeedometerSmoother.filter(70.0)
        assertEquals(64, step1)

        // Next reading 70.0 again -> 64.0 * 0.6 + 70.0 * 0.4 = 38.4 + 28.0 = 66.4 -> rounds to 66
        val step2 = SpeedometerSmoother.filter(70.0)
        assertEquals(66, step2)

        // Monotonic progression towards target
        assertTrue(step2 > step1)
    }

    @Test
    fun testReset() {
        SpeedometerSmoother.filter(75.0)
        SpeedometerSmoother.reset()
        // After reset, first reading immediately resets to new speed
        val speed = SpeedometerSmoother.filter(25.0)
        assertEquals(25, speed)
    }
}
