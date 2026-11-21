package com.parampilot.auto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsAndParkedTest {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var modelRepo: ModelManagerRepository

    @Before
    fun setUp() {
        val client = SgGalaxyClient("http://127.0.0.1:8082")
        settingsRepo = SettingsRepository(client)
        modelRepo = ModelManagerRepository(client)
        NavStateManager.setDeviceState(null)
    }

    @Test
    fun testSettingsBlockedWhenInMotion() {
        val res = settingsRepo.updatePersonalityProfile(
            profileName = "Aggressive",
            currentDeviceState = "driving"
        )
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalStateException)
        assertEquals("Vehicle must be parked to commit settings.", res.exceptionOrNull()?.message)
    }

    @Test
    fun testSlcOffsetsBlockedWhenStandbyOrInMotion() {
        val offsets = doubleArrayOf(0.0, 3.0, 5.0, 5.0, 7.0, 8.0, 10.0)
        val res = settingsRepo.updateSlcOffsets(
            offsets = offsets,
            currentDeviceState = "standby"
        )
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun testModelActivationBlockedWhenInMotion() {
        val res = modelRepo.activateModel(
            modelId = "tr14223",
            currentDeviceState = "driving"
        )
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalStateException)
        assertEquals("Vehicle must be parked to change driving models.", res.exceptionOrNull()?.message)
    }

    @Test
    fun testSoundPackDownloadBlockedWhenInMotion() {
        val res = modelRepo.downloadSoundPack(
            packId = "duck",
            currentDeviceState = "driving"
        )
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalStateException)
        assertEquals("Vehicle must be parked to download sound packs.", res.exceptionOrNull()?.message)
    }

    @Test
    fun testIsVehicleParkedEvaluation() {
        assertFalse(settingsRepo.isVehicleParked("driving"))
        assertFalse(settingsRepo.isVehicleParked("standby"))
        assertTrue(settingsRepo.isVehicleParked("parked"))
        assertTrue(settingsRepo.isVehicleParked("  PARKED  "))

        // When explicit state is null, falls back to NavStateManager.deviceState
        NavStateManager.setDeviceState("parked")
        assertTrue(settingsRepo.isVehicleParked(null))

        NavStateManager.setDeviceState("driving")
        assertFalse(settingsRepo.isVehicleParked(null))
    }

    private fun assertEquals(expected: String, actual: String?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
