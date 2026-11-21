package com.parampilot.auto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL

/**
 * Comma-primary map tile provider with cellular fallback (Section 3.1 & 3.4).
 *
 * Primary (Comma Local Storage):
 * - Fetches raster tiles directly from comma via GET /api/navigation/map/tiles/{z}/{x}/{y}.png?theme={theme}
 * - Zero cellular data cost, ultra-low latency (<15ms), works offline
 * - Skipped on BLE (TransportType.BLUETOOTH) because BLE returns 501 Not Implemented.
 *
 * Fallback (Cellular Network):
 * - If on BLE, comma disconnected, or comma returns 404/503/timeout (>600ms),
 *   falls back to OpenStreetMap standard raster tiles (tile.openstreetmap.org).
 *   CARTO basemaps used to serve here, but they now demand an API key and
 *   return watermarked "API KEY REQUIRED" tiles with HTTP 200 (field report,
 *   2026-11-03; re-verified live 2026-11-20: the voyager and dark_all tiles
 *   are byte-identical for every tile — 2,049 B / 2,513 B static watermark
 *   PNGs, not maps). A 200 that is not a map must never be accepted as one,
 *   so CARTO is gone from this path entirely: the fallback is OSM only.
 *   OSM needs no key, so the Android Auto map loads with no comma and no
 *   saved Mapbox/Google key (Google Play compliance, 2026-11-20: vc37 was
 *   flagged because the Auto map failed to load for a keyless reviewer).
 */
class MapTileProvider(
    private val getBaseUrl: () -> String? = {
        NavStateManager.commaIp?.let { SgGalaxyClient.baseUrlFor(it) }
    },
    // Byte-capped at 24 MB (audit finding 49; was 128 tiles by count).
    private val memoryCache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
) {
    private val commaLocalKeys = java.util.Collections.synchronizedSet(HashSet<String>())
    private val comma404Tiles = java.util.Collections.synchronizedSet(HashSet<String>())

    companion object {
        private const val TAG = "MapTileProvider"

        /**
         * Key-free tile URL for the cellular fallback (OpenStreetMap
         * standard raster tiles). No key, no account, no session: this
         * is what the Android Auto map loads on when there is no comma
         * and no saved Mapbox/Google key (Google Play compliance,
         * 2026-11-20). Pure function so the no-key fallback is
         * unit-testable without network (OsmAutoMapTest).
         */
        fun osmTileUrl(z: Int, x: Int, y: Int): String =
            "https://tile.openstreetmap.org/$z/$x/$y.png"
    }

    data class TileResult(val bitmap: Bitmap, val isCommaLocal: Boolean)


    fun getTileResult(z: Int, x: Int, y: Int, isNight: Boolean, commaOnly: Boolean = false): TileResult? {
        // 1. PRIMARY: Check memory cache first, then phone disk cache
        val theme = if (isNight) "dark" else "light"
        val key = "$z/$x/$y-$theme"
        memoryCache.get(key)?.takeIf { !it.isRecycled }?.let {
            return TileResult(it, isCommaLocal = commaLocalKeys.contains(key))
        }

        // 1a. PHONE DISK CACHE: If tile was previously saved from comma to phone, use it directly!
        val diskBitmap = CommaTileDiskCache.getTileBitmap(z, x, y, theme)
        if (diskBitmap != null && !diskBitmap.isRecycled) {
            memoryCache.put(key, diskBitmap)
            Log.d(TAG, "Tile loaded from phone disk cache: $z/$x/$y ($theme)")
            return TileResult(diskBitmap, isCommaLocal = true)
        }

        // 1b. FETCH FROM COMMA: If connected to comma on LAN/hotspot, fetch and cache to phone
        val commaBaseUrl = getBaseUrl()
        val isCommaLan = NavStateManager.activeTransport != NavStateManager.TransportType.BLUETOOTH &&
            NavStateManager.commaFound && !commaBaseUrl.isNullOrBlank() && !comma404Tiles.contains(key)
        if (isCommaLan) {
            try {
                val tilePath = "$commaBaseUrl/api/navigation/map/tiles/$z/$x/$y.png?theme=$theme"
                val url = URL(tilePath)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1000
                    readTimeout = 1000
                    GalaxyCloudSession.currentLocalToken()?.let { token ->
                        setRequestProperty("Cookie", "${GalaxyCloudSession.COOKIE_NAME}=$token")
                    }
                }
                try {
                    val code = conn.responseCode
                    if (code == 200) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        // Save tile bytes to phone internal storage
                        CommaTileDiskCache.putTileBytes(z, x, y, theme, bytes)

                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (bitmap != null) {
                            commaLocalKeys.add(key)
                            memoryCache.put(key, bitmap)
                            Log.d(TAG, "Comma local tile loaded and cached to phone: $z/$x/$y")
                            return TileResult(bitmap, isCommaLocal = true)
                        }
                    } else {
                        if (code == 404) { if (comma404Tiles.size < 2048) comma404Tiles.add(key) }
                        Log.w(TAG, "Comma tile HTTP $code for $z/$x/$y")
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Comma tile fetch error ($z/$x/$y): ${e.message}")
                // Comma unavailable or tile not cached locally; fall through to cellular fallback
            }
        }

        // 2. FALLBACK: Fetch over cellular (OpenStreetMap standard tiles).
        // Skipped in commaOnly mode: the caller is probing whether the
        // comma's own tiles can serve this view before trying Mapbox.
        if (commaOnly) return null
        val fallback = fetchCellularFallbackTile(z, x, y, isNight) ?: return null
        commaLocalKeys.remove(key)
        memoryCache.put(key, fallback)
        return TileResult(fallback, isCommaLocal = false)
    }

    private fun fetchCellularFallbackTile(z: Int, x: Int, y: Int, isNight: Boolean): Bitmap? {
        // OSM standard tiles ONLY. CARTO was tried first here until
        // 2026-11-20 and poisoned the fallback: its basemaps now demand
        // an API key and answer every tile with HTTP 200 carrying the
        // same static "API KEY REQUIRED" watermark PNG, which this code
        // accepted as a valid tile — the stitch "succeeded" with blank
        // watermark tiles and OSM was never tried. That is the map a
        // keyless Play reviewer saw (vc37 compliance flag, 2026-11-20).
        // OSM is key-free, so it is the whole fallback now; there is no
        // second source to try when it fails.
        return fetchTileFromUrl(osmTileUrl(z, x, y), z, x, y, isNight)
    }

    private fun fetchTileFromUrl(urlStr: String, z: Int, x: Int, y: Int, isNight: Boolean): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            UsageCounters.count(UsageCounters.OSM_TILES)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "ParamAuto/1.0")
            }
            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                val theme = if (isNight) "dark" else "light"
                // Cache tile permanently to phone disk so future loads are instantaneous (<1ms)
                CommaTileDiskCache.putTileBytes(z, x, y, theme, bytes)
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun clearCache() {
        memoryCache.evictAll()
        comma404Tiles.clear()
    }
}

