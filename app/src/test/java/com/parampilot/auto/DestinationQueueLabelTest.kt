package com.parampilot.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Addendum 122: the ONE queued-indicator text, shared by the top bar
 * chip and the nav banner row — offline-queued vs out-of-sync wording.
 */
class DestinationQueueLabelTest {

    private val dest = NavDestination(
        name = "Costco",
        placeName = "Costco, Phoenix, AZ",
        latitude = 33.6,
        longitude = -112.1,
        source = "comma"
    )

    @Test
    fun offlineLabelPromisesSendOnConnect() {
        assertEquals(
            "Queued: Costco — will send when the comma connects",
            DestinationQueue.label(dest, outOfSync = false)
        )
    }

    @Test
    fun outOfSyncLabelAsksForCommaInternet() {
        val label = DestinationQueue.label(dest, outOfSync = true)
        assertTrue(label.startsWith("Out of sync:"))
        assertTrue(label.contains("Costco"))
        assertTrue(label.contains("connect the comma to the Internet"))
    }

    @Test
    fun outOfSyncLabelNamesTheCommasWifi() {
        val networks = GalaxyNetworks(
            editable = true,
            prefer = "wifi",
            pauseHotspot = true,
            cellularEnabled = false,
            cellularConnected = false,
            cellularType = "",
            cellularOperator = "",
            link = "wifi",
            linkChangedAt = 0.0,
            networks = listOf(
                GalaxyNetworks.SavedNetwork(
                    uuid = "u1", name = "FlyByWire", autoJoin = true,
                    whileDriving = true, connected = true,
                    metered = "Auto", signal = 80
                )
            ),
            available = emptyList(),
            lastJoin = null
        )
        val label = DestinationQueue.label(dest, outOfSync = true, networks = networks)
        assertTrue(label.contains("comma is on Wi-Fi 'FlyByWire'"))
        assertTrue(label.contains("connect the comma to the Internet"))
    }
}
