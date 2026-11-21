package com.parampilot.auto

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CommaTileDiskCacheTest {

    private lateinit var tempDir: File

    private fun fakePng(label: String = "test"): ByteArray {
        val header = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val body = label.toByteArray()
        val padding = ByteArray(64) { 0x20 }
        return header + body + padding
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("comma_tile_test").toFile()
        CommaTileDiskCache.initForTesting(tempDir)
    }

    @After
    fun tearDown() {
        CommaTileDiskCache.clearCache()
        tempDir.deleteRecursively()
    }

    @Test
    fun testPutAndGetTileBytes() {
        val testBytes = fakePng("tile_sample_data")
        val z = 14
        val x = 3086
        val y = 6579
        val theme = "dark"

        assertFalse(CommaTileDiskCache.hasTile(z, x, y, theme))
        assertNull(CommaTileDiskCache.getTileBytes(z, x, y, theme))

        CommaTileDiskCache.putTileBytes(z, x, y, theme, testBytes)

        // Wait up to 1.5 seconds for async file write executor
        val deadline = System.currentTimeMillis() + 1500L
        var loaded: ByteArray? = null
        while (System.currentTimeMillis() < deadline) {
            loaded = CommaTileDiskCache.getTileBytes(z, x, y, theme)
            if (loaded != null) break
            Thread.sleep(20)
        }

        assertNotNull("Tile should be present in cache", loaded)
        assertArrayEquals(testBytes, loaded)
        assertTrue(CommaTileDiskCache.hasTile(z, x, y, theme))
    }

    @Test
    fun testLightAndDarkThemesSeparated() {
        val darkBytes = fakePng("dark_data")
        val lightBytes = fakePng("light_data")
        val z = 12
        val x = 100
        val y = 200

        CommaTileDiskCache.putTileBytes(z, x, y, "dark", darkBytes)
        CommaTileDiskCache.putTileBytes(z, x, y, "light", lightBytes)

        val deadline = System.currentTimeMillis() + 1500L
        while (System.currentTimeMillis() < deadline) {
            val d = CommaTileDiskCache.getTileBytes(z, x, y, "dark")
            val l = CommaTileDiskCache.getTileBytes(z, x, y, "light")
            if (d != null && l != null) break
            Thread.sleep(20)
        }

        val cachedDark = CommaTileDiskCache.getTileBytes(z, x, y, "dark")
        val cachedLight = CommaTileDiskCache.getTileBytes(z, x, y, "light")

        assertNotNull(cachedDark)
        assertNotNull(cachedLight)
        assertArrayEquals(darkBytes, cachedDark)
        assertArrayEquals(lightBytes, cachedLight)
    }

    @Test
    fun testClearCache() {
        val testBytes = fakePng("sample_clear_test")
        CommaTileDiskCache.putTileBytes(10, 10, 10, "dark", testBytes)

        val deadline = System.currentTimeMillis() + 1500L
        while (System.currentTimeMillis() < deadline) {
            if (CommaTileDiskCache.getTileBytes(10, 10, 10, "dark") != null) break
            Thread.sleep(20)
        }

        assertTrue(CommaTileDiskCache.hasTile(10, 10, 10, "dark"))

        val cleared = CommaTileDiskCache.clearCache()
        assertTrue(cleared)
        assertFalse(CommaTileDiskCache.hasTile(10, 10, 10, "dark"))
        assertNull(CommaTileDiskCache.getTileBytes(10, 10, 10, "dark"))
    }
}
