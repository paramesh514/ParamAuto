package com.parampilot.auto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Automotive display renderer for Android Auto matching the official Google Maps Auto layout:
 * - 3D perspective driving map: Mapbox Static Images (navigation-night-v1 / navigation-day-v1,
 *   route baked in as an overlay), with a flat OpenStreetMap tile stitch as fallback
 * - Google Maps 3D vehicle chevron (cyan glow, vibrant blue circle, white 3D triangle arrow)
 * - Google Maps current street name pill directly below chevron ([ NW 17th Ave ] / [ W Yorkshire Dr ])
 * - Google Maps left control rail (Settings gear, Audio speaker, rotating Compass, Zoom +/- pill)
 * - Google Maps floating Search & Destinations card on top-left (Home 5 min · 1.3 mi, Work 10 min · 2.5 mi)
 * - Active turn-by-turn maneuver banner when navigating
 * - Digital speedometer & US MUTCD Speed Limit sign on right edge
 * - Comma 12V voltage & Comma IP / Cloud connection pills
 */
class CarMapRenderer {

    companion object {
        private val tileStitchExecutor = java.util.concurrent.Executors.newFixedThreadPool(8) { r ->
            Thread(r, "car-tile-stitch").apply { isDaemon = true }
        }
        private val mapFetchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "car-map-fetch").apply { isDaemon = true }
        }
    }

    /** Which tile source produced map bitmap; drives route drawing + watermark. */
    enum class BitmapSource { NONE, COMMA_LOCAL, MAPBOX, OSM }

    data class MapFrame(val bitmap: Bitmap, val source: BitmapSource)

    private val currentFrame = java.util.concurrent.atomic.AtomicReference<MapFrame?>(null)

    val cachedMapBitmap: Bitmap? get() = currentFrame.get()?.bitmap
    val cachedBitmapSource: BitmapSource get() = currentFrame.get()?.source ?: BitmapSource.NONE

    /**
     * Overscan factor for stitch bitmaps (field fix, 2026-11-05): while
     * heading-up, render() records how much larger than the base
     * 800x480 viewport the next stitch must be so rotation is covered
     * by real tiles instead of a canvas scale-up — the scale-up made
     * every road and route line look bulky (Param's photo, 2026-11-05).
     * 1f in north-up / before the first rotated frame.
     */
    @Volatile private var stitchCoverTarget: Float = 1f

    /** Comma-primary tile provider with cellular fallback (Section 3.1 & 3.4). */
    private val tileProvider = MapTileProvider()

    /** Automotive HUD Drawer with Gemini Aurora engagement pill (Section 4.4 & 7.2). */
    private val hudDrawer = CarSurfaceHudDrawer()

    /** Recently shown Mapbox static images, reused when the view returns to a recent state. */
    // Byte-capped (audit finding 49): 6 entries of ~1.5 MB frames
    // was an unbounded-in-practice count cap; now 12 MB by byteCount.
    // Evicted frames are recycled unless one is on screen right now.
    private val staticImageCache = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(
            evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?
        ) {
            if (evicted && oldValue !== cachedMapBitmap) {
                try { oldValue.recycle() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    /** Last Mapbox static attempt (any outcome), for MapSourceChoice's retry rule. */
    @Volatile
    private var lastMapboxAttemptMs: Long? = null

    @Volatile
    private var lastFetchLat: Double = 0.0

    @Volatile
    private var lastFetchLng: Double = 0.0

    @Volatile
    private var lastFetchZoom: Double = 0.0

    @Volatile
    private var lastFetchBearing: Double = 0.0

    @Volatile
    private var lastFetchTimeMs: Long = 0L

    @Volatile
    private var lastFetchPolyline: String? = null

    /**
     * Records the camera a map fetch was made for, in ONE place
     * (extracted 2026-11-08; the five assignments were copy-pasted
     * at every fetch site). The gate logic reads these to decide
     * whether the next frame needs a refetch.
     */
    private fun recordFetch(lat: Double, lng: Double, zoom: Double, bearing: Double, routePolyline: String?) {
        lastFetchLat = lat
        lastFetchLng = lng
        lastFetchZoom = zoom
        lastFetchBearing = bearing
        lastFetchPolyline = routePolyline
    }

    private val isFetching = AtomicBoolean(false)

    // Byte-capped at 16 MB (audit finding 49; was 64 tiles by count).

    // Hoisted paints (review C6): configurations that are identical on every
    // frame, created once instead of per render() call. Paints whose color or
    // width is chosen per frame (speeding state, status pills) stay local.
    private val paintDropShadow by lazy { Paint().apply { color = 0x66000000; style = Paint.Style.FILL; isAntiAlias = true } }
    private val paintDropShadowSoft by lazy { Paint().apply { color = 0x55000000; style = Paint.Style.FILL; isAntiAlias = true } }
    private val paintRecenterBg by lazy { Paint().apply { color = GoogleMapsLook.CARD_DAY; style = Paint.Style.FILL; isAntiAlias = true } }
    private val paintRecenterBorder by lazy { Paint().apply { color = GoogleMapsLook.CARD_OUTLINE_DAY; strokeWidth = 1.6f; style = Paint.Style.STROKE; isAntiAlias = true } }
    private val paintRecenterText by lazy { Paint().apply { color = 0xFF3C4043.toInt(); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; isAntiAlias = true } }
    private val paintGrid by lazy { Paint().apply { color = 0x2238BDF8.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true } }
    private val paintChevronGlow by lazy { Paint().apply { style = Paint.Style.FILL; isAntiAlias = true } }
    private val paintChevronDisc by lazy { Paint().apply { color = 0xFF1A73E8.toInt(); style = Paint.Style.FILL; isAntiAlias = true } }
    private val paintChevronRim by lazy { Paint().apply { color = Color.WHITE; strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true } }
    private val paintChevronLeft by lazy { Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true } }
    private val paintChevronRight by lazy { Paint().apply { color = 0xFFD8E2EC.toInt(); style = Paint.Style.FILL; isAntiAlias = true } }
    private val paintStreetText by lazy { Paint().apply { color = Color.WHITE; textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; isAntiAlias = true } }
    private val paintStreetBg by lazy { Paint().apply { color = 0xFF1A73E8.toInt(); style = Paint.Style.FILL; isAntiAlias = true } }
    private val paintStreetBorder by lazy { Paint().apply { color = 0x33FFFFFF; strokeWidth = 1f; style = Paint.Style.STROKE; isAntiAlias = true } }
    private val paintManeuverBg by lazy { Paint().apply { color = GoogleMapsLook.NAV_GREEN; style = Paint.Style.FILL; isAntiAlias = true } }
    private val paintManeuverArrow by lazy { Paint().apply { color = Color.WHITE; strokeWidth = 4.5f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true } }
    private val paintManeuverArrowFill by lazy { Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true } }
    private val paintManeuverDist by lazy { Paint().apply { color = Color.WHITE; textSize = 25f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true } }
    private val paintManeuverAction by lazy { Paint().apply { color = 0xEEFFFFFF.toInt(); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true } }
    private val paintManeuverStreet by lazy { Paint().apply { color = Color.WHITE; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true } }
    private val paintRouteCasing by lazy { Paint().apply { color = GoogleMapsLook.ROUTE_CASING; strokeWidth = 12f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true } }
    private val paintRouteGlow by lazy { Paint().apply { color = GoogleMapsLook.ROUTE_GLOW; strokeWidth = 16f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true } }
    private val paintRouteLine by lazy { Paint().apply { color = GoogleMapsLook.ROUTE_BLUE; strokeWidth = 8f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true } }
    private val paintAltRouteCasing by lazy { Paint().apply { color = GoogleMapsLook.ALT_ROUTE_CASING; strokeWidth = 8.5f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true } }
    private val paintAltRouteLine by lazy { Paint().apply { color = GoogleMapsLook.ALT_ROUTE; strokeWidth = 5.5f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true } }
    private val paintWatermark by lazy { Paint().apply { color = 0x66FFFFFF; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT; isAntiAlias = true } }
    private val paintStaleBg by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9B3261E.toInt(); style = Paint.Style.FILL } }

    /** Reused route-path scratch (render-thread confined). */
    private val scratchRoutePath = Path()
    // Route feature markers (Param, 2026-11-18): emoji text paint —
    // signals 🚦 and stop signs 🛑, matching the static-frame emoji.
    private val paintEmoji by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    }
    private val paintStaleText by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textAlign = Paint.Align.CENTER } }

    /**
     * Makes [bmp] the displayed map bitmap and records which source produced
     * it. The previous bitmap is recycled unless it came from Mapbox — those
     * are still referenced by [staticImageCache] and may be displayed again.
     */
    private fun setCachedMapBitmap(bmp: Bitmap, source: BitmapSource) {
        val old = currentFrame.getAndSet(MapFrame(bmp, source)) ?: return
        // Recycle the replaced frame (audit finding 23 — the KDoc
        // above always promised this; the code never did it). Safe
        // here: every render and every call to this function runs on
        // the one render executor, so the old frame's last draw has
        // finished. Exempt: Mapbox frames (the static-image cache
        // owns them and may display them again) and stitch-pool
        // buffers (the pool reuses them, finding 24).
        val pooled = stitchBuffers.any { it === old.bitmap }
        if (old.bitmap !== bmp && old.source != BitmapSource.MAPBOX && !pooled) {
            try { old.bitmap.recycle() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    // Double-buffered stitch targets (audit finding 24): a stitch
    // used to allocate a fresh overscan bitmap on every view change.
    // Two buffers alternate; the one not on screen is overwritten.
    private val stitchBuffers = arrayOfNulls<Bitmap>(2)

    private fun acquireStitchBitmap(w: Int, h: Int): Bitmap {
        val current = currentFrame.get()?.bitmap
        for (b in stitchBuffers) {
            if (b != null && b !== current && !b.isRecycled && b.width == w && b.height == h) {
                return b
            }
        }
        val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val slot = if (stitchBuffers[0] === current) 1 else 0
        val evicted = stitchBuffers[slot]
        if (evicted != null && evicted !== current) {
            try { evicted.recycle() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
        stitchBuffers[slot] = fresh
        return fresh
    }

    fun requestMapUpdate(
        lat: Double,
        lng: Double,
        zoom: Double,
        bearing: Double,
        token: String?,
        isNight: Boolean,
        routePolyline: String? = null,
        onBitmapReady: () -> Unit
    ) {
        if (isFetching.get()) return

        val polylineChanged = routePolyline != lastFetchPolyline

        val mapboxToken = token?.takeIf { it.isNotBlank() }
        val isCommaLan = NavStateManager.activeTransport != NavStateManager.TransportType.BLUETOOTH &&
            NavStateManager.commaFound && NavStateManager.commaIp != null

        val now = System.currentTimeMillis()
        // Layer-2 retry rule (MapSourceChoice): with a token in play,
        // a non-Mapbox frame must not stick just because nothing
        // moved — a session-start fallback used to hold all session.
        val retryDue = MapSourceChoice.mapboxRetryDue(
            hasToken = mapboxToken != null,
            currentIsMapbox = cachedBitmapSource == BitmapSource.MAPBOX,
            msSinceLastAttempt = lastMapboxAttemptMs?.let { now - it }
        )
        // Overscan upgrade: a stitch bitmap fetched before heading-up
        // rotation demanded overscan (or fetched in north-up) is too
        // small for the current target — refetch at the target size
        // instead of holding it behind the gates below.
        val neededStitchW = (800f * stitchCoverTarget).toInt()
        val stitchUpgrade = cachedMapBitmap != null &&
            cachedBitmapSource != BitmapSource.MAPBOX &&
            (cachedMapBitmap?.width ?: 0) < neededStitchW - 4
        if (!retryDue && !stitchUpgrade && cachedMapBitmap != null && !polylineChanged && (now - lastFetchTimeMs) < 2500L) {
            return
        }

        val distMoved = GuidanceMath.haversineM(lat, lng, lastFetchLat, lastFetchLng)
        val zoomDiff = kotlin.math.abs(zoom - lastFetchZoom)
        val bearingDiff = kotlin.math.abs(bearing - lastFetchBearing)

        // Zoom-scaled refetch distance (review A3): refetch after moving ~25%
        // of the visible viewport height instead of a flat 40 m, clamped to
        // [40, 400] m so city zooms still refresh promptly.
        val metersPerPixel = 156543.03392 * cos(Math.toRadians(lat)) / Math.pow(2.0, zoom)
        val viewportHeightM = metersPerPixel * 480.0
        val moveThresholdM = (0.25 * viewportHeightM).coerceIn(40.0, 400.0)

        if (!retryDue && !stitchUpgrade && cachedMapBitmap != null && !polylineChanged && distMoved < moveThresholdM && zoomDiff < 0.3 && bearingDiff < 12.0) {
            return
        }

        if (!isFetching.compareAndSet(false, true)) return
        lastFetchTimeMs = now

        mapFetchExecutor.execute {
            try {
                // The source ORDER is a layer-2 decision
                // (MapSourceChoice.aaPlan): comma-local tiles on the
                // comma's network, then Mapbox Static whenever a token
                // is available (until 2026-11-03 the online stitch ran
                // first and its key-free tiles always "succeeded", so
                // Mapbox never appeared even with a token saved), then
                // the full stitch (disk cache + OpenStreetMap).
                var mapLoaded = false
                for (source in MapSourceChoice.aaPlan(isCommaLan, mapboxToken != null)) {
                    if (mapLoaded) break
                    mapLoaded = when (source) {
                        MapSourceChoice.AaSource.COMMA_STITCH ->
                            fetchStitchedTiles(lat, lng, zoom, bearing, isNight, routePolyline, onBitmapReady, commaOnly = true)
                        MapSourceChoice.AaSource.MAPBOX_STATIC ->
                            fetchMapboxStaticImage(lat, lng, zoom, bearing, mapboxToken!!, isNight, routePolyline, onBitmapReady)
                        MapSourceChoice.AaSource.FULL_STITCH ->
                            fetchStitchedTiles(lat, lng, zoom, bearing, isNight, routePolyline, onBitmapReady)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CarMapRenderer", "Map fetch error: ${e.message}")
                try {
                    fetchStitchedTiles(lat, lng, zoom, bearing, isNight, routePolyline, onBitmapReady)
                } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                isFetching.set(false)
            }
        }
    }

    /** Key for [staticImageCache]: view state rounded so near-identical views share an image. */
    private fun staticImageCacheKey(
        lat: Double,
        lng: Double,
        zoom: Double,
        bearing: Double,
        isNight: Boolean,
        routePolyline: String?,
        markerKey: String
    ): String {
        val latR = (lat * 10000).roundToInt() / 10000.0
        val lngR = (lng * 10000).roundToInt() / 10000.0
        val zoomR = (zoom * 10).roundToInt() / 10.0
        val bearingBucket = Math.floorMod((bearing / 10.0).roundToInt(), 36) * 10
        // The key names the actual frame: style follows the Traffic
        // toggle, pitch follows the 3D toggle (MapDisplayOptions).
        val style = GoogleMapsLook.staticStyleId(isNight, NavStateManager.usePhoneTraffic)
        val pitch = MapDisplayOptions.staticPitch(
            if (bearing != 0.0) bearing.roundToInt() else 0
        )
        val routeHash = routePolyline?.hashCode() ?: 0
        return "$latR,$lngR,$zoomR,$bearingBucket,$style,$pitch,$routeHash,$markerKey"
    }

    /**
     * Loads a Mapbox Static Images bitmap (navigation-day/night style, route
     * baked in as a path overlay) for the given view. Checks [staticImageCache]
     * first — only an actual HTTP fetch counts against
     * [UsageCounters.MAPBOX_STATIC]. Returns true when a Mapbox bitmap is now
     * the displayed base map.
     */
    private fun fetchMapboxStaticImage(
        lat: Double,
        lng: Double,
        zoom: Double,
        bearing: Double,
        token: String,
        isNight: Boolean,
        routePolyline: String?,
        onBitmapReady: () -> Unit
    ): Boolean {
        lastMapboxAttemptMs = System.currentTimeMillis()
        // Route feature markers ride the static request as emoji
        // overlays (RouteFeatures.staticEmojiOverlay): the canvas
        // cannot project onto a pitched static frame, so Mapbox
        // places them. The marker set is part of the frame's cache
        // key, so a changed set fetches a fresh frame instead of
        // serving one with another route's markers baked in.
        val markerSignals = if (NavStateManager.usePhoneSignals) NavStateManager.routeSignals else emptyList()
        val markerStops = if (NavStateManager.usePhoneStops) NavStateManager.routeStops else emptyList()
        val emojiOverlay = RouteFeatures.staticEmojiOverlay(markerSignals, markerStops)
        val markerKey = RouteFeatures.markerSetKey(markerSignals, markerStops)
        val key = staticImageCacheKey(lat, lng, zoom, bearing, isNight, routePolyline, markerKey)
        val hit = staticImageCache.get(key)
        if (hit != null && !hit.isRecycled) {
            setCachedMapBitmap(hit, BitmapSource.MAPBOX)
            recordFetch(lat, lng, zoom, bearing, routePolyline)
            onBitmapReady()
            return true
        }

        // Shared disk cache (MapFrameDiskCache): a frame fetched in an
        // earlier session serves immediately — no network race at
        // session start, which is where the stitch used to win.
        val diskHit = MapFrameDiskCache.getBitmap(key)
        if (diskHit != null && !diskHit.isRecycled) {
            staticImageCache.put(key, diskHit)
            setCachedMapBitmap(diskHit, BitmapSource.MAPBOX)
            recordFetch(lat, lng, zoom, bearing, routePolyline)
            onBitmapReady()
            return true
        }

        val styleId = GoogleMapsLook.staticStyleId(isNight, NavStateManager.usePhoneTraffic)
        val b = if (bearing != 0.0) bearing.roundToInt() else 0
        val z = zoom.roundToInt().coerceIn(10, 18)
        val pitch = MapDisplayOptions.staticPitch(b)

        val pathOnlyPart = if (!routePolyline.isNullOrBlank()) {
            val enc = URLEncoder.encode(routePolyline, "UTF-8")
            "path-6+00e5ff-0.9($enc)/"
        } else ""
        // Emoji markers join the route path as further overlays —
        // unless the combined URL would run past a safe length, in
        // which case the frame ships without markers rather than
        // failing outright.
        val pathPart = when {
            emojiOverlay.isEmpty() -> pathOnlyPart
            pathOnlyPart.length + emojiOverlay.length > 6000 -> pathOnlyPart
            pathOnlyPart.isEmpty() -> "$emojiOverlay/"
            else -> pathOnlyPart.removeSuffix("/") + "," + emojiOverlay + "/"
        }

        // Shift center coordinate ahead by 48px along bearing so vehicle chevron at 0.60*h aligns with Mapbox center (H7)
        val metersPerPx = 156543.03392 * kotlin.math.cos(Math.toRadians(lat)) / Math.pow(2.0, z.toDouble())
        val shiftAheadM = 48.0 * metersPerPx
        val bRad = Math.toRadians(b.toDouble())
        val centerLat = lat + (shiftAheadM * kotlin.math.cos(bRad)) / 111139.0
        val centerLng = lng + (shiftAheadM * kotlin.math.sin(bRad)) / (111139.0 * kotlin.math.cos(Math.toRadians(lat)))

        val urlStr = String.format(
            java.util.Locale.US,
            "https://api.mapbox.com/styles/v1/mapbox/%s/static/%s%.5f,%.5f,%d,%d,%d/800x480?access_token=%s",
            styleId, pathPart, centerLng, centerLat, z, b, pitch, token
        )
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 5000
            useCaches = true
            setRequestProperty("User-Agent", "ParamAuto/1.0")
        }
        try {
            if (conn.responseCode != 200) {
                val errBody = try { conn.errorStream?.reader()?.readText() } catch (_: Exception) { null }
                android.util.Log.w("CarMapRenderer", "Mapbox HTTP ${conn.responseCode}: $errBody (url=${urlStr.replace(token, token.take(6) + "...")})")
                return false
            }
            UsageCounters.count(UsageCounters.MAPBOX_STATIC)
            val bytes = conn.inputStream.use { it.readBytes() }
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
            MapFrameDiskCache.putBytes(key, bytes)
            staticImageCache.put(key, bmp)
            setCachedMapBitmap(bmp, BitmapSource.MAPBOX)
            recordFetch(lat, lng, zoom, bearing, routePolyline)
            android.util.Log.i("CarMapRenderer", "Mapbox tile loaded: ${bmp.width}x${bmp.height}")
            onBitmapReady()
            return true
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Stitches 256x256 map tiles via [MapTileProvider] (Section 3.1 & 3.4).
     * Primary: Comma-served local raster tiles (/api/navigation/map/tiles/...)
     * Fallback: cellular OpenStreetMap raster tiles (tile.openstreetmap.org)
     */
    private fun fetchStitchedTiles(
        lat: Double,
        lng: Double,
        zoom: Double,
        bearing: Double,
        isNight: Boolean,
        routePolyline: String? = null,
        onBitmapReady: () -> Unit,
        commaOnly: Boolean = false
    ): Boolean {
        val z = zoom.roundToInt().coerceIn(10, 18)
        val n = 1 shl z
        val xExact = (lng + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat)
        val yExact = (1.0 - kotlin.math.asinh(kotlin.math.tan(latRad)) / Math.PI) / 2.0 * n

        val xCenterPx = xExact * 256.0
        val yCenterPx = yExact * 256.0

        // Overscan (field fix, 2026-11-05): the stitch covers more
        // than the visible 800x480 viewport — by stitchCoverTarget —
        // at the SAME meters-per-pixel, vehicle anchored at the same
        // (0.5, 0.60) fractions, so heading-up rotation is covered by
        // real tiles instead of scaling the bitmap up.
        val bmpW = (800f * stitchCoverTarget).roundToInt()
        val bmpH = (480f * stitchCoverTarget).roundToInt()
        val leftPx = xCenterPx - bmpW * 0.5
        val topPx = yCenterPx - (bmpH * 0.60) // Align center with vehicle chevron at h * 0.60f

        val minTx = kotlin.math.floor(leftPx / 256.0).toInt()
        val maxTx = kotlin.math.floor((leftPx + bmpW) / 256.0).toInt()
        val minTy = kotlin.math.floor(topPx / 256.0).toInt()
        val maxTy = kotlin.math.floor((topPx + bmpH) / 256.0).toInt()

        val stitched = acquireStitchBitmap(bmpW, bmpH)
        val c = Canvas(stitched)
        c.drawColor(if (isNight) ThemeManager.COLOR_MD_BG else 0xFFF8F9FA.toInt())

        val fetchedTiles = java.util.concurrent.ConcurrentHashMap<String, MapTileProvider.TileResult>()
        val missingTiles = ArrayList<Pair<Int, Int>>()
        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                missingTiles.add(tx to ty)
            }
        }
        if (missingTiles.isNotEmpty()) {
            val futures = missingTiles.map { (tx, ty) ->
                tileStitchExecutor.submit {
                    val key = "$z/$tx/$ty"
                    val res = tileProvider.getTileResult(z, tx, ty, isNight, commaOnly)
                    if (res != null) {
                        fetchedTiles[key] = res
                    }
                }
            }
            val deadline = System.currentTimeMillis() + 3000L
            futures.forEach {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(100L)
                try { it.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }

        var tilesLoaded = 0
        var commaTileCount = 0
        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                val key = "$z/$tx/$ty"
                val res = fetchedTiles[key]
                if (res != null && !res.bitmap.isRecycled) {
                    val dstX = (tx * 256.0 - leftPx).toFloat()
                    val dstY = (ty * 256.0 - topPx).toFloat()
                    c.drawBitmap(res.bitmap, dstX, dstY, null)
                    tilesLoaded++
                    if (res.isCommaLocal) commaTileCount++
                }
            }
        }

        val totalExpected = (maxTx - minTx + 1) * (maxTy - minTy + 1)
        if (tilesLoaded >= (totalExpected / 2).coerceAtLeast(1)) {
            val src = if (commaTileCount == tilesLoaded && commaTileCount > 0) BitmapSource.COMMA_LOCAL else BitmapSource.OSM
            setCachedMapBitmap(stitched, src)
            recordFetch(lat, lng, zoom, bearing, routePolyline)
            android.util.Log.i("CarMapRenderer", "Map stitched ($tilesLoaded tiles, comma=$commaTileCount): ${bmpW}x${bmpH}")
            onBitmapReady()
            return true
        } else {
            try { stitched.recycle() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
        return false
    }

    fun render(
        canvas: Canvas,
        w: Float,
        h: Float,
        gps: GpsPosition?,
        dest: NavDestination?,
        route: RouteOption?,
        speedMs: Double,
        speedLimitMph: Int,
        power: PowerState?,
        commaIp: String?,
        token: String?,
        isNight: Boolean,
        zoom: Double,
        isNorthUp: Boolean,
        onNeedRedraw: () -> Unit
    ) {
        val lat = gps?.latitude ?: dest?.latitude ?: 33.4484
        val lng = gps?.longitude ?: dest?.longitude ?: -112.0740
        val bearing = if (isNorthUp) 0.0 else (gps?.bearing ?: 0.0)

        // Request a background refresh of the base map: a Mapbox static image
        // with the route baked in, or the OSM tile stitch when Mapbox is
        // unavailable. Route changes invalidate the Mapbox bitmap because its
        // overlay is part of the image.
        val polyline = route?.geometry ?: (route?.points?.takeIf { it.size >= 2 }?.let { NavigationManager.encodePolyline(it) })
        requestMapUpdate(lat, lng, zoom, bearing, token, isNight, polyline, onNeedRedraw)

        // ------------------------------------------------------------------------
        // LAYER 1 (Z=1): Base Canvas Background Surface (Section 7.2)
        // Solid Slate (#131314 night / #F8F9FA day) + 3D Grid
        // ------------------------------------------------------------------------
        val frame = currentFrame.get()
        val bmp = frame?.bitmap
        val bmpSource = frame?.source ?: BitmapSource.OSM
        val hasBitmap = bmp != null && !bmp.isRecycled
        canvas.drawColor(if (isNight) ThemeManager.COLOR_MD_BG else 0xFFF8F9FA.toInt())
        if (!hasBitmap) {
            drawPerspectiveGrid(canvas, w, h)
        }

        // ------------------------------------------------------------------------
        // LAYER 2 (Z=2): Map Base Layer (Section 7.2)
        // Primary: Comma-served local raster tiles (/api/navigation/map/tiles/...)
        // Fallback: Cellular / online tiles (Mapbox Static / OpenStreetMap)
        // ------------------------------------------------------------------------
        // Vehicle anchor: shared by the map layers and the chevron.
        val carX = w * 0.5f
        val carY = h * 0.60f

        // Heading-up orientation (Param, 2026-11-03: "North up just changing
        // arrow. It should orient map. Not arrow"): in heading-up mode the
        // MAP rotates by -bearing around the vehicle anchor and the chevron
        // stays pointing up; north-up mode leaves the map alone and rotates
        // the chevron instead. Stitch bitmaps only - Mapbox static images
        // are fetched already oriented (bearing + pitch). A slight scale
        // covers the corners the rotation exposes; the bitmap and the route
        // geometry are drawn inside the same transform so they stay aligned.
        val mapRotation = if (!isNorthUp && hasBitmap && bmpSource != BitmapSource.MAPBOX && gps?.hasFix == true) {
            -gps.bearing.toFloat()
        } else 0f
        val mapRotated = mapRotation != 0f

        // Record the overscan the next stitch fetch needs (see
        // stitchCoverTarget): the cover the rotation would need
        // against the plain surface rect, plus headroom for bearing
        // drift between gated refetches.
        stitchCoverTarget = if (mapRotated) {
            val baseCover = RotationCover.coverScale(
                w, h, carX, carY, mapRotation, 0f, 0f, w, h
            )
            (baseCover * 1.12f + 0.04f).coerceIn(1f, 1.85f)
        } else 1f

        // Bitmap destination first: the rotation's cover scale is
        // computed against it (RotationCover, layer 2). A fixed 1.25x
        // scale ran out at wider bearings and left black wedges
        // (Param's photo, 2026-11-04).
        // Pan stitch bitmaps with live GPS so the map tracks the car
        // between gated refetches (field report, 2026-11-03: "Car
        // location not moving on Auto screen"). Mapbox static images
        // are excluded: pitched/bearing views whose baked route would
        // smear.
        val bmpDst: RectF? = if (bmp != null && !bmp.isRecycled) {
            if (bmpSource == BitmapSource.MAPBOX) {
                // Mapbox frames arrive pre-oriented at the base viewport.
                RectF(0f, 0f, w, h)
            } else {
                // Stitch bitmaps are world-pixel images anchored at the
                // vehicle (0.5, 0.60 of the bitmap). Draw at the base
                // geo scale (surface / 800x480) around that anchor: an
                // overscan bitmap then extends past the surface on
                // every side, so rotation needs no cover scale-up and
                // tile lines keep their native weight. At the base
                // 800x480 size this is exactly RectF(0, 0, w, h).
                // Pan stitch bitmaps with live GPS so the map tracks
                // the car between gated refetches (field report,
                // 2026-11-03: "Car location not moving on Auto screen").
                val geoScaleX = w / 800f
                val geoScaleY = h / 480f
                var dstLeft = carX - bmp.width * 0.5f * geoScaleX
                var dstTop = carY - bmp.height * 0.60f * geoScaleY
                val canPan = (lastFetchLat != 0.0 || lastFetchLng != 0.0) &&
                    kotlin.math.abs(zoom - lastFetchZoom) < 0.3 &&
                    gps?.hasFix == true
                if (canPan) {
                    val (tx, ty) = BitmapPan.translation(
                        lastFetchLat, lastFetchLng, lat, lng, lastFetchZoom,
                        geoScaleX, geoScaleY
                    )
                    dstLeft += tx
                    dstTop += ty
                }
                RectF(dstLeft, dstTop, dstLeft + bmp.width * geoScaleX, dstTop + bmp.height * geoScaleY)
            }
        } else {
            null
        }

        if (mapRotated) {
            val d = bmpDst ?: RectF(0f, 0f, w, h)
            val cover = RotationCover.coverScale(
                w, h, carX, carY, mapRotation, d.left, d.top, d.right, d.bottom
            )
            canvas.save()
            canvas.translate(carX, carY)
            canvas.rotate(mapRotation)
            canvas.scale(cover, cover)
            canvas.translate(-carX, -carY)
        }

        if (bmp != null && !bmp.isRecycled && bmpDst != null) {
            val src = android.graphics.Rect(0, 0, bmp.width, bmp.height)
            canvas.drawBitmap(bmp, src, bmpDst, null)
        }


        // ------------------------------------------------------------------------
        // LAYER 3 (Z=3): Route Geometry Layer (Section 7.2)
        // Alternative Route Polylines (De-emphasized slate #5F6368)
        // Active Route Polyline (#4285F4 / #00E5FF) with subtle glow
        // ------------------------------------------------------------------------
        val baseIsMapbox = hasBitmap && bmpSource == BitmapSource.MAPBOX
        if (!baseIsMapbox) {
            drawRouteGeometry(canvas, w, h, route, dest, lat, lng, zoom)
            drawRouteFeatureMarkers(canvas, w, h, lat, lng, zoom)
        }

        if (mapRotated) canvas.restore()

        drawMapWatermark(canvas, w, h, bmpSource, hasBitmap)

        // ------------------------------------------------------------------------
        // LAYER 4 (Z=4): Vehicle Position & Orientation Layer (Section 7.2)
        // LERP-interpolated 3D Car Chevron & Street Name Pill
        // ------------------------------------------------------------------------
        val chevronRotation = if (isNorthUp) (gps?.bearing ?: 0.0).toFloat() else 0f
        drawGoogleVehicleChevron(canvas, carX, carY, chevronRotation)

        // Street Pill directly below vehicle chevron (only if verified street is known)
        val street = NavStateManager.currentStreet
        if (!street.isNullOrBlank()) {
            drawCurrentStreetPill(canvas, carX, carY + 36f, street)
        }

        // ------------------------------------------------------------------------
        // LAYER 5 (Z=5): HUD & Overlay Layer (Always on top, never clipped)
        // Surface draws the turn banner (drawActiveManeuverCard below),
        // Speedometer, Speed Limit Sign, and compact Status Pill.
        // ------------------------------------------------------------------------
        val isNavRunning = dest != null

        val slcOffset = NavStateManager.getSlcOffsetMph(speedLimitMph)
        // Placement is a layer-2 decision (SpeedHudPlacement): while
        // navigating, the AA host's destination/ETA card owns the
        // bottom-left and was burying the sign + hiding the speed box
        // (Param's photo, 2026-11-05), so the cluster sits bottom-right.
        val hudAnchor = SpeedHudPlacement.anchor(w, h, isNavRunning)
        hudDrawer.draw(
            canvas = canvas,
            startX = hudAnchor.startX,
            bottomY = hudAnchor.bottomY,
            speedMps = speedMs,
            slcLimitMph = speedLimitMph,
            slcOffsetMph = slcOffset,
            controlsActive = NavStateManager.controlsActive,
            lateralActive = NavStateManager.lateralControlActive,
            longitudinalActive = NavStateManager.longitudinalControlActive,
            lateralPid = NavStateManager.lateralPid,
            isNight = isNight,
            speedLimitSourceLabel = SpeedLimitSource.label(
                NavStateManager.latestPacket?.speedLimit?.source, speedLimitMph
            )
        )

        drawStatusPills(canvas, w, power, commaIp)

        if (kotlin.math.abs(zoom - 16.0) > 0.1 || isNorthUp) {
            drawRecenterPill(canvas, w, h, isNavRunning)
        }

        drawStaleDataRibbon(canvas, w)

        // Turn-by-turn banner on the surface itself: the native
        // NavigationTemplate banner never rendered on Param's head unit
        // (field reports, 2026-11-03) and this card - built for exactly
        // that job - was never wired into render(). Draw it whenever a
        // destination is active.
        if (dest != null) {
            drawActiveManeuverCard(canvas, dest, route)
        }

        // Same transient states the phone shows (Param, 2026-11-03:
        // everything except Settings also belongs on the car screen).
        drawReroutingPill(canvas, w, isNavRunning)
        drawEndRoutePill(canvas, w, isNavRunning)
        drawGpsFeedPill(canvas, w)
        if (dest == null) {
            // Drive summary on the car screen (Day 5, display parity):
            // with a fresh arrival it rides as a "Driven:" line on the
            // arrival card; with no fresh arrival (e.g. user End Route)
            // it gets its own card; stale summaries show nothing.
            val now = System.currentTimeMillis()
            val arrivalAgeMs = NavStateManager.lastArrival?.let { now - NavStateManager.lastArrivalAtMs }
            val driveAgeMs = NavStateManager.lastDriveSummary?.let { now - NavStateManager.lastDriveSummaryAtMs }
            val arrivalFresh = arrivalAgeMs != null && AaDriveSummary.isFresh(arrivalAgeMs)
            val driveFresh = driveAgeMs != null && AaDriveSummary.isFresh(driveAgeMs)
            if (arrivalFresh) {
                drawArrivalCard(canvas, w)
            } else if (AaDriveSummary.showStandalone(arrivalAgeMs, driveAgeMs)) {
                drawDriveSummaryCard(canvas, w)
            }
            // End Route confidence (backlog #3): the surface shows the
            // comma-confirmed end — "Route ended ✓" for 20 s, or the
            // stuck warning until it resolves. Stacked below the
            // arrival / drive-summary card when one is showing, so the
            // trip numbers stay visible too.
            val endState = NavStateManager.endRouteState
            if (EndRouteConfidence.isVisible(endState, false, NavStateManager.endRouteAtMs, now)) {
                val arrivalDriven = driveFresh
                val endTop = when {
                    arrivalFresh -> topBand + (if (arrivalDriven) 118f else 96f) + 8f
                    driveFresh -> topBand + 96f + 8f
                    else -> topBand
                }
                drawEndRouteCard(canvas, w, endTop)
            }
        }
    }

    fun getRecenterRect(w: Float, h: Float, isNavRunning: Boolean = false): RectF {
        val cx = w * 0.5f
        val cy = if (isNavRunning) (h - 110f) else (h - 65f)
        val pillW = 140f
        val pillH = 46f
        return RectF(cx - pillW * 0.5f, cy - pillH * 0.5f, cx + pillW * 0.5f, cy + pillH * 0.5f)
    }

    private fun drawRecenterPill(canvas: Canvas, w: Float, h: Float, isNavRunning: Boolean) {
        val cx = w * 0.5f
        val cy = if (isNavRunning) (h - 110f) else (h - 65f)
        val pillW = 116f
        val pillH = 34f
        val rect = RectF(cx - pillW * 0.5f, cy - pillH * 0.5f, cx + pillW * 0.5f, cy + pillH * 0.5f)

        canvas.drawRoundRect(RectF(rect.left, rect.top + 2f, rect.right, rect.bottom + 2f), 17f, 17f, paintDropShadow)
        paintRecenterBg.color = 0xFF1E1F20.toInt()
        paintRecenterBorder.color = 0xFF3C4043.toInt()
        paintRecenterText.color = 0xFF8AB4F8.toInt()
        canvas.drawRoundRect(rect, 17f, 17f, paintRecenterBg)
        canvas.drawRoundRect(rect, 17f, 17f, paintRecenterBorder)
        canvas.drawText("Re-center", cx, cy + 5f, paintRecenterText)
    }

    private fun drawPerspectiveGrid(canvas: Canvas, w: Float, h: Float) {
        val horizonY = h * 0.15f
        val vpX = w * 0.5f

        // Perspective fan lines
        var x = -w * 0.5f
        while (x <= w * 1.5f) {
            canvas.drawLine(vpX, horizonY, x, h, paintGrid)
            x += 80f
        }
        // Horizontal depth lines
        var y = horizonY + 20f
        var step = 15f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, paintGrid)
            y += step
            step *= 1.25f
        }
    }

    /**
     * Replicates the Google Maps 3D vehicle chevron:
     * - Outer soft cyan/blue pulsing glow
     * - Vibrant Google Blue disc with white rim
     * - Crisp white 3D triangle arrow with realistic depth bevel
     */
    private fun drawGoogleVehicleChevron(canvas: Canvas, cx: Float, cy: Float, rotationDeg: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotationDeg)

        // Outer soft glow halo (color set explicitly per circle: the hoisted
        // paint keeps the second color between frames)
        paintChevronGlow.color = 0x3338BDF8.toInt()
        canvas.drawCircle(0f, 0f, 34f, paintChevronGlow)

        paintChevronGlow.color = 0x551A73E8.toInt()
        canvas.drawCircle(0f, 0f, 25f, paintChevronGlow)

        // Core Google Blue circular disc
        canvas.drawCircle(0f, 0f, 20f, paintChevronDisc)

        // White rim
        canvas.drawCircle(0f, 0f, 20f, paintChevronRim)

        // 3D White Triangle Arrow
        // Left facet (bright white)
        val leftFacet = Path().apply {
            moveTo(0f, -14f)
            lineTo(-10f, 10f)
            lineTo(0f, 4f)
            close()
        }
        canvas.drawPath(leftFacet, paintChevronLeft)

        // Right facet (soft metallic gray for authentic 3D depth)
        val rightFacet = Path().apply {
            moveTo(0f, -14f)
            lineTo(10f, 10f)
            lineTo(0f, 4f)
            close()
        }
        canvas.drawPath(rightFacet, paintChevronRight)

        canvas.restore()
    }

    /**
     * Renders the signature Google Maps rounded blue pill with current street name
     * floating directly beneath the vehicle chevron (e.g. "[ NW 17th Ave ]").
     */
    private fun drawCurrentStreetPill(canvas: Canvas, cx: Float, cy: Float, street: String) {
        val textWidth = paintStreetText.measureText(street)
        val pillW = textWidth + 26f
        val pillH = 26f
        val halfW = pillW / 2f
        val halfH = pillH / 2f

        val pillRect = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)

        // Subtle drop shadow
        canvas.drawRoundRect(RectF(pillRect.left, pillRect.top + 2f, pillRect.right, pillRect.bottom + 2f), 13f, 13f, paintDropShadowSoft)

        // Pill background: Vibrant Google Maps Blue
        canvas.drawRoundRect(pillRect, 13f, 13f, paintStreetBg)

        // Thin subtle highlight border
        canvas.drawRoundRect(pillRect, 13f, 13f, paintStreetBorder)

        // Street name text
        canvas.drawText(street, cx, cy + 4.5f, paintStreetText)
    }


    /**
     * Active Turn Maneuver Card (Google Maps Green Navigation Banner at top-left)
     */
    private fun drawActiveManeuverCard(canvas: Canvas, dest: NavDestination, route: RouteOption?) {
        val left = 28f
        val top = 22f
        val cardW = 380f

        val effectiveNav = NavStateManager.getEffectiveManeuver()
        // Lane strip (the phone banner has always drawn one; the car
        // card gains it here — same lanes, same source).
        val laneItems = laneItemsFor(effectiveNav)
        val cardH = if (laneItems.isNotEmpty()) 140f else 96f
        val rect = RectF(left, top, left + cardW, top + cardH)

        // Drop shadow
        canvas.drawRoundRect(RectF(rect.left, rect.top + 3f, rect.right, rect.bottom + 3f), 20f, 20f, paintDropShadow)

        // Google Navigation Green background
        canvas.drawRoundRect(rect, 20f, 20f, paintManeuverBg)
        val mod = effectiveNav.modifier.lowercase().trim()
        val t = effectiveNav.type.lowercase().trim()
        val isLeft = mod.contains("left")
        val isRight = mod.contains("right")
        val isSharp = mod.contains("sharp")
        val isSlight = mod.contains("slight")
        val isUturn = mod.contains("uturn") || mod.contains("u-turn")
        val isArrive = t == "arrive" || mod == "arrive"

        // Turn Arrow Icon (hoisted paints, review C6)
        val arrowPaint = paintManeuverArrow
        val arrowFillPaint = paintManeuverArrowFill

        val ax = left + 40f
        val ay = top + 48f

        if (isArrive) {
            val pinPath = Path().apply {
                moveTo(ax, ay + 14f)
                lineTo(ax, ay - 4f)
            }
            canvas.drawPath(pinPath, arrowPaint)
            canvas.drawCircle(ax, ay - 10f, 8f, arrowPaint)
            canvas.drawCircle(ax, ay - 10f, 3f, arrowFillPaint)
        } else if (isUturn) {
            val uPath = Path().apply {
                moveTo(ax + 10f, ay + 14f)
                lineTo(ax + 10f, ay - 4f)
                quadTo(ax + 10f, ay - 16f, ax, ay - 16f)
                quadTo(ax - 10f, ay - 16f, ax - 10f, ay - 4f)
                lineTo(ax - 10f, ay + 8f)
                lineTo(ax - 16f, ay + 2f)
                moveTo(ax - 10f, ay + 8f)
                lineTo(ax - 4f, ay + 2f)
            }
            canvas.drawPath(uPath, arrowPaint)
        } else if (isLeft) {
            val arrPath = Path().apply {
                moveTo(ax, ay + 16f)
                if (isSlight) {
                    lineTo(ax, ay + 2f)
                    lineTo(ax - 14f, ay - 12f)
                    lineTo(ax - 14f, ay - 4f)
                    moveTo(ax - 14f, ay - 12f)
                    lineTo(ax - 6f, ay - 12f)
                } else if (isSharp) {
                    lineTo(ax, ay - 4f)
                    quadTo(ax, ay - 14f, ax - 8f, ay - 10f)
                    lineTo(ax - 14f, ay + 2f)
                    lineTo(ax - 14f, ay - 6f)
                    moveTo(ax - 14f, ay + 2f)
                    lineTo(ax - 6f, ay + 2f)
                } else {
                    lineTo(ax, ay - 4f)
                    lineTo(ax - 16f, ay - 4f)
                    lineTo(ax - 10f, ay - 10f)
                    moveTo(ax - 16f, ay - 4f)
                    lineTo(ax - 10f, ay + 2f)
                }
            }
            canvas.drawPath(arrPath, arrowPaint)
        } else if (isRight) {
            val arrPath = Path().apply {
                moveTo(ax, ay + 16f)
                if (isSlight) {
                    lineTo(ax, ay + 2f)
                    lineTo(ax + 14f, ay - 12f)
                    lineTo(ax + 14f, ay - 4f)
                    moveTo(ax + 14f, ay - 12f)
                    lineTo(ax + 6f, ay - 12f)
                } else if (isSharp) {
                    lineTo(ax, ay - 4f)
                    quadTo(ax, ay - 14f, ax + 8f, ay - 10f)
                    lineTo(ax + 14f, ay + 2f)
                    lineTo(ax + 14f, ay - 6f)
                    moveTo(ax + 14f, ay + 2f)
                    lineTo(ax + 6f, ay + 2f)
                } else {
                    lineTo(ax, ay - 4f)
                    lineTo(ax + 16f, ay - 4f)
                    lineTo(ax + 10f, ay - 10f)
                    moveTo(ax + 16f, ay - 4f)
                    lineTo(ax + 10f, ay + 2f)
                }
            }
            canvas.drawPath(arrPath, arrowPaint)
        } else {
            val arrPath = Path().apply {
                moveTo(ax, ay + 16f)
                lineTo(ax, ay - 12f)
                lineTo(ax - 6f, ay - 6f)
                moveTo(ax, ay - 12f)
                lineTo(ax + 6f, ay - 6f)
            }
            canvas.drawPath(arrPath, arrowPaint)
        }

        // Countdown distance text — the shared DisplayUnits rule, the
        // same text the phone banner shows for the same maneuver.
        val distText = DisplayUnits.bannerDistance(effectiveNav.distanceM)

        val actionText = when {
            isArrive -> "Arriving at"
            isUturn -> "Make a U-turn"
            isSharp && isRight -> "Sharp right onto"
            isSharp && isLeft -> "Sharp left onto"
            isSlight && isRight -> "Slight right onto"
            isSlight && isLeft -> "Slight left onto"
            isRight -> "Turn right onto"
            isLeft -> "Turn left onto"
            t.contains("ramp") -> "Take ramp onto"
            t.contains("merge") -> "Merge onto"
            else -> "Continue onto"
        }

        canvas.drawText(distText, left + 76f, top + 42f, paintManeuverDist)

        val shortAction = actionText.substringBefore(" onto")
        val distWidth = paintManeuverDist.measureText(distText)
        canvas.drawText("· $shortAction", left + 86f + distWidth, top + 42f, paintManeuverAction)

        val targetName = effectiveNav.street.ifEmpty {
            route?.summary?.ifEmpty { null } ?: dest.placeName.ifEmpty { dest.name }
        }.take(32)
        canvas.drawText(targetName, left + 76f, top + 72f, paintManeuverStreet)

        if (laneItems.isNotEmpty()) {
            drawLaneStrip(canvas, left + 76f, top + 114f, laneItems)
        }
    }

    /**
     * The lanes to draw for a maneuver, mirroring the phone banner's
     * rule: real lane data when present, otherwise straight lanes
     * synthesized from the lane count with the active index lit.
     */
    private fun laneItemsFor(nav: NavManeuver): List<LaneInfo> {
        if (nav.lanes.isNotEmpty()) return nav.lanes
        if (nav.laneCount <= 0) return emptyList()
        return (0 until nav.laneCount).map { LaneInfo(listOf("straight"), it == nav.activeLaneIndex) }
    }

    /**
     * Draws the lane-guidance strip inside the maneuver card: one
     * arrow glyph per lane (active lanes bright, others dim) plus the
     * cue text ("Use left lane" / "Stay in lane" / …), mirroring the
     * phone banner's lane row.
     */
    private fun drawLaneStrip(canvas: Canvas, startX: Float, centerY: Float, lanes: List<LaneInfo>) {
        val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x59FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        val slotW = 30f
        lanes.forEachIndexed { i, lane ->
            val cx = startX + i * slotW + 12f
            val dir = when {
                lane.directions.any { it.contains("left") } -> "left"
                lane.directions.any { it.contains("right") } -> "right"
                lane.directions.any { it.contains("uturn") || it.contains("u-turn") } -> "left"
                else -> "straight"
            }
            drawLaneGlyph(canvas, cx, centerY, dir, if (lane.active) activePaint else dimPaint)
        }
    }

    /** One lane arrow: a shaft with a head, bent for turns. */
    private fun drawLaneGlyph(canvas: Canvas, cx: Float, cy: Float, dir: String, paint: Paint) {
        val path = Path()
        when (dir) {
            "left" -> {
                path.moveTo(cx, cy + 9f)
                path.lineTo(cx, cy - 1f)
                path.lineTo(cx - 7f, cy - 1f)
                canvas.drawPath(path, paint)
                canvas.drawLine(cx - 7f, cy - 1f, cx - 3f, cy - 5f, paint)
                canvas.drawLine(cx - 7f, cy - 1f, cx - 3f, cy + 3f, paint)
            }
            "right" -> {
                path.moveTo(cx, cy + 9f)
                path.lineTo(cx, cy - 1f)
                path.lineTo(cx + 7f, cy - 1f)
                canvas.drawPath(path, paint)
                canvas.drawLine(cx + 7f, cy - 1f, cx + 3f, cy - 5f, paint)
                canvas.drawLine(cx + 7f, cy - 1f, cx + 3f, cy + 3f, paint)
            }
            else -> {
                canvas.drawLine(cx, cy + 9f, cx, cy - 7f, paint)
                canvas.drawLine(cx, cy - 7f, cx - 4f, cy - 2f, paint)
                canvas.drawLine(cx, cy - 7f, cx + 4f, cy - 2f, paint)
            }
        }
    }

    /**
     * "Rerouting…" pill, top-center, shown for the same 3 s window the
     * phone banner uses after the route changes under an unchanged
     * destination.
     */
    private fun drawReroutingPill(canvas: Canvas, w: Float, isNavRunning: Boolean) {
        if (!isNavRunning) return
        val at = NavStateManager.reroutingAtMs
        if (at <= 0L || System.currentTimeMillis() - at >= 3000L) return
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val text = "Rerouting…"
        val pillW = textPaint.measureText(text) + 32f
        val rect = RectF(w / 2f - pillW / 2f, topBand, w / 2f + pillW / 2f, topBand + 34f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE61E1F20.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, 17f, 17f, bgPaint)
        canvas.drawText(text, rect.left + 16f, topBand + 23f, textPaint)
    }


    /**
     * Phone-GPS feed pill — a pulsing location pin, no text: shown only while
     * the phone is actively feeding its location to the comma
     * (Param, 2026-11-04 — a status icon with animation). Sits in the
     * left-end stack under the status pill (Param, 2026-11-06), clear of the head
     * unit's button band; the pulse phase comes from
     * [GpsFeedIndicator] and the surface's regular redraws animate it.
     */
    private fun drawGpsFeedPill(canvas: Canvas, w: Float) {
        if (!GpsFeedIndicator.shouldShow(PhoneGpsFeeder.isFeeding)) return
        val now = System.currentTimeMillis()
        // Icon only (Param, 2026-11-05: "Convert texts into icons") — the
        // pulsing location pin alone says the phone is feeding GPS.
        // Left-end stack under the transport chip (Param, 2026-11-06;
        // StatusPillPlacement decides, drawStatusPills ran first).
        val pillW = 38f
        val top = StatusPillPlacement.gpsTop(statusStackTop)
        val rect = RectF(
            StatusPillPlacement.LEFT, top,
            StatusPillPlacement.LEFT + pillW, top + 26f
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E1F20.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, 13f, 13f, bgPaint)
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8AB4F8.toInt()
            style = Paint.Style.FILL
            alpha = GpsFeedIndicator.alpha(now)
        }
        val cx = rect.centerX()
        val cy = rect.centerY() - 2f
        canvas.drawCircle(cx, cy, 5f, pinPaint)
        val pin = Path().apply {
            moveTo(cx - 4.5f, cy + 3f)
            lineTo(cx + 4.5f, cy + 3f)
            lineTo(cx, cy + 10f)
            close()
        }
        canvas.drawPath(pin, pinPaint)
    }

    /**
     * "Ending route…" pill while the End Route clear is in flight
     * (backlog #3) — the car-screen counterpart of the phone banner's
     * ENDING state. Sits below the rerouting pill's slot so the two
     * never overlap.
     */
    private fun drawEndRoutePill(canvas: Canvas, w: Float, isNavRunning: Boolean) {
        if (!isNavRunning) return
        if (NavStateManager.endRouteState != EndRouteConfidence.State.ENDING) return
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val text = "Ending route…"
        val pillW = textPaint.measureText(text) + 32f
        val top = topBand + 38f
        val rect = RectF(w / 2f - pillW / 2f, top, w / 2f + pillW / 2f, top + 34f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE61E1F20.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, 17f, 17f, bgPaint)
        canvas.drawText(text, rect.left + 16f, top + 23f, textPaint)
    }

    /**
     * End Route confidence card on the surface (backlog #3): "Route
     * ended ✓" for 20 s after the comma confirms the clear, or the
     * stuck warning until it resolves. Same card style as the arrival
     * card; the caller stacks it below the arrival / drive-summary
     * card when one is showing.
     */
    private fun drawEndRouteCard(canvas: Canvas, w: Float, top: Float) {
        val state = NavStateManager.endRouteState
        val title = EndRouteConfidence.title(state) ?: return
        val destName = NavStateManager.endRouteDestName ?: return
        val detail = EndRouteConfidence.cardDetail(state) ?: return
        val cardW = 380f
        val cardH = 96f
        val left = (w - cardW) / 2f
        val rect = RectF(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(RectF(rect.left, rect.top + 3f, rect.right, rect.bottom + 3f), 20f, 20f, paintDropShadow)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (state == EndRouteConfidence.State.STUCK) 0xF28C1D18.toInt() else 0xF21E1F20.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, 20f, 20f, bgPaint)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFDADCE0.toInt()
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9AA0A6.toInt()
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.drawText(title, left + 24f, top + 32f, titlePaint)
        canvas.drawText(destName.take(34), left + 24f, top + 58f, subPaint)
        canvas.drawText(detail, left + 24f, top + 80f, detailPaint)
    }

    /**
     * Arrival card on the surface, from the retained arrival summary:
     * shown for 20 s after the comma reports arrival when no new
     * destination is active — the car-screen counterpart of the
     * phone's arrival card (Feature 2).
     */
    private fun drawArrivalCard(canvas: Canvas, w: Float) {
        val summary = NavStateManager.lastArrival ?: return
        if (System.currentTimeMillis() - NavStateManager.lastArrivalAtMs >= 20_000L) return
        // Day 5: when this same trip produced a drive summary, append it
        // as a "Driven:" line — exactly what the phone's arrival card does.
        val driven = if (NavStateManager.lastDriveSummary != null &&
            AaDriveSummary.isFresh(
                System.currentTimeMillis() - NavStateManager.lastDriveSummaryAtMs
            )
        ) AaDriveSummary.drivenLine(NavStateManager.lastDriveSummary) else null
        val cardW = 380f
        val cardH = if (driven != null) 118f else 96f
        val left = (w - cardW) / 2f
        val top = topBand
        val rect = RectF(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(RectF(rect.left, rect.top + 3f, rect.right, rect.bottom + 3f), 20f, 20f, paintDropShadow)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF21E1F20.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, 20f, 20f, bgPaint)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFDADCE0.toInt()
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9AA0A6.toInt()
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.drawText("Arrived", left + 24f, top + 32f, titlePaint)
        canvas.drawText(summary.destinationName.take(34), left + 24f, top + 58f, subPaint)
        canvas.drawText(summary.detailText(), left + 24f, top + 80f, detailPaint)
        if (driven != null) {
            canvas.drawText(driven, left + 24f, top + 102f, detailPaint)
        }
    }

    /**
     * Standalone drive-summary card on the surface (Day 5): shown for
     * 20 s after a trip ends with no fresh arrival — the End Route case,
     * where the phone shows its own "Drive summary" card and the head
     * unit previously showed nothing. Same card style as the arrival
     * card; every number comes from the tracker's real fixes.
     */
    private fun drawDriveSummaryCard(canvas: Canvas, w: Float) {
        val summary = NavStateManager.lastDriveSummary ?: return
        if (!AaDriveSummary.isFresh(
                System.currentTimeMillis() - NavStateManager.lastDriveSummaryAtMs
            )
        ) return
        val cardW = 380f
        val cardH = 96f
        val left = (w - cardW) / 2f
        val top = topBand
        val rect = RectF(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(RectF(rect.left, rect.top + 3f, rect.right, rect.bottom + 3f), 20f, 20f, paintDropShadow)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF21E1F20.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, 20f, 20f, bgPaint)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFDADCE0.toInt()
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9AA0A6.toInt()
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.drawText("Drive summary", left + 24f, top + 32f, titlePaint)
        canvas.drawText(summary.destinationName.take(34), left + 24f, top + 58f, subPaint)
        canvas.drawText(summary.detailText(), left + 24f, top + 80f, detailPaint)
    }

    /**
     * Renders the connection icon chip at top-right: a transport glyph
     * (house / Bluetooth rune / cloud) plus the status dot. No text,
     * no voltage (Param, 2026-11-05).
     */
    /**
     * Top edge reserved for the head unit's own template buttons
     * (Search / Home / Work and the map controls): surface elements
     * that used to start at y=20 were overlapped by them whenever the
     * buttons were on screen (Param's photo, 2026-11-04: "When buttons
     * are there status is overlapped"). Everything the surface draws
     * along the top starts below this band.
     */
    private val topBand = 120f

    /** Top of the left-end status stack for the current frame, set by
     *  drawStatusPills and consumed by drawGpsFeedPill (same pass). */
    private var statusStackTop: Float = StatusPillPlacement.TOP_BAND

    private fun drawStatusPills(canvas: Canvas, w: Float, power: PowerState?, commaIp: String?) {
        // Left-end stack (Param, 2026-11-06: "Move Bluetooth and gps
        // icons move left end. Instead of middle"). Placement is a
        // layer-2 decision (StatusPillPlacement): below the maneuver
        // card while navigating, at the top band while idle.
        // drawGpsFeedPill stacks under this chip in the same frame.
        val navigating = NavStateManager.activeDestination != null
        val pkt = NavStateManager.latestPacket
        val hasLanes = pkt != null &&
            laneItemsFor(NavStateManager.getEffectiveManeuver(pkt)).isNotEmpty()
        val topY = StatusPillPlacement.stackTop(
            navigating, StatusPillPlacement.maneuverCardHeight(hasLanes)
        )
        statusStackTop = topY
        val pillH = 26f

        val shadowPaint = paintDropShadowSoft
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF1E1F20.toInt()
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = 0xFF3C4043.toInt()
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        // The connection STATE is decided once, in layer 2
        // (ConnectionStatus — the same decision the phone pill
        // renders); this layer only maps the state to Auto's short
        // label and dot color.
        val ip = commaIp ?: NavStateManager.commaIp
        val status = ConnectionStatus.resolve(
            ConnectionStatus.Input(
                simulating = SimulationEngine.isSimulating,
                mode = NavStateManager.connectionMode,
                commaFound = NavStateManager.commaFound,
                commaIp = ip,
                isGalaxyCloud = NavStateManager.isGalaxyCloud,
                isScanning = false,
                bleConnected = BleBridgeClient.isSharedClientConnected(),
                cloudHasPassword = GalaxyCloudSession.hasPassword(),
                cloudAuthFailed = GalaxyCloudSession.authFailed,
                transportKey = when (NavStateManager.activeTransport) {
                    NavStateManager.TransportType.BLUETOOTH -> "bluetooth"
                    NavStateManager.TransportType.IP -> "ip"
                    NavStateManager.TransportType.CLOUD -> "cloud"
                    NavStateManager.TransportType.NONE -> "none"
                },
                bleLinkUp = NavStateManager.isBleConnected
            )
        )
        val dotColor = when (status.dot) {
            ConnectionStatus.Dot.GREEN -> 0xFF34A853.toInt()
            ConnectionStatus.Dot.BLUE -> 0xFF4285F4.toInt()
            ConnectionStatus.Dot.RED -> 0xFFEA4335.toInt()
            ConnectionStatus.Dot.AMBER -> 0xFFFBBC04.toInt()
            ConnectionStatus.Dot.MUTED -> 0xFF9AA0A6.toInt()
        }

        // Icon pill (Param, 2026-11-05: "Convert texts into icons" and
        // "Remove 13.6V as it is not relevant while driving"). The
        // transport is a colorful glyph now — house = Local, Bluetooth
        // rune = Bluetooth, cloud = Cloud — and the voltage suffix is
        // gone from the car screen. The layer-2 status dot keeps its
        // color, in the pill's top-right corner.
        // Update-ready rides THIS pill (Param, 2026-11-20: no
        // separate update pill): the pill widens and an amber
        // download glyph joins the transport glyph. The transport
        // glyph keeps its usual spot; the status dot keeps its
        // corner.
        val updateReady = NavStateManager.softwareUpdateReady
        val pillW = if (updateReady) 70f else 46f
        val pillBox = RectF(
            StatusPillPlacement.LEFT, topY,
            StatusPillPlacement.LEFT + pillW, topY + pillH
        )

        canvas.drawRoundRect(RectF(pillBox.left, pillBox.top + 2f, pillBox.right, pillBox.bottom + 2f), 13f, 13f, shadowPaint)
        canvas.drawRoundRect(pillBox, 13f, 13f, bgPaint)
        canvas.drawRoundRect(pillBox, 13f, 13f, borderPaint)

        val glyphCx = if (updateReady) pillBox.left + 23f else pillBox.centerX()
        drawTransportGlyph(canvas, status.kind, glyphCx, pillBox.centerY())
        if (updateReady) {
            drawUpdateGlyph(canvas, pillBox.left + 52f, pillBox.centerY())
        }
        dotPaint.color = dotColor
        canvas.drawCircle(pillBox.right - 8f, pillBox.top + 7f, 3.2f, dotPaint)
    }

    /** The amber download glyph the state pill gains while a comma
     * software update is staged (Feature 14 state). */
    private fun drawUpdateGlyph(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFFF9AB00.toInt()
        }
        val arrow = Path().apply {
            moveTo(cx, cy - 6f)
            lineTo(cx, cy + 2.5f)
            moveTo(cx - 3.8f, cy - 1f)
            lineTo(cx, cy + 2.8f)
            lineTo(cx + 3.8f, cy - 1f)
            moveTo(cx - 5.5f, cy + 6f)
            lineTo(cx + 5.5f, cy + 6f)
        }
        canvas.drawPath(arrow, stroke)
    }

    /**
     * The transport glyphs for the icon pill, drawn in a ~16px box at
     * (cx, cy). Connected transports get their brand color; offline
     * variants draw the same glyph muted, with the red corner dot
     * carrying the state.
     */
    private fun drawTransportGlyph(canvas: Canvas, kind: ConnectionStatus.Kind, cx: Float, cy: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val muted = 0xFF9AA0A6.toInt()
        when (kind) {
            ConnectionStatus.Kind.LOCAL_CONNECTED,
            ConnectionStatus.Kind.LOCAL_OFFLINE -> {
                fill.color = if (kind == ConnectionStatus.Kind.LOCAL_CONNECTED) 0xFF34A853.toInt() else muted
                val house = Path().apply {
                    moveTo(cx - 8f, cy + 1f)
                    lineTo(cx, cy - 7f)
                    lineTo(cx + 8f, cy + 1f)
                    lineTo(cx + 5.5f, cy + 1f)
                    lineTo(cx + 5.5f, cy + 7f)
                    lineTo(cx - 5.5f, cy + 7f)
                    lineTo(cx - 5.5f, cy + 1f)
                    close()
                }
                canvas.drawPath(house, fill)
            }
            ConnectionStatus.Kind.BLUETOOTH_CONNECTED,
            ConnectionStatus.Kind.BLUETOOTH_OFFLINE -> {
                stroke.color = if (kind == ConnectionStatus.Kind.BLUETOOTH_CONNECTED) 0xFF8AB4F8.toInt() else muted
                val rune = Path().apply {
                    moveTo(cx - 4f, cy - 4.5f)
                    lineTo(cx + 4.5f, cy + 4f)
                    lineTo(cx, cy + 8f)
                    lineTo(cx, cy - 8f)
                    lineTo(cx + 4.5f, cy - 4f)
                    lineTo(cx - 4f, cy + 4.5f)
                }
                canvas.drawPath(rune, stroke)
            }
            ConnectionStatus.Kind.CLOUD_CONNECTED,
            ConnectionStatus.Kind.CLOUD_OFFLINE,
            ConnectionStatus.Kind.CLOUD_CONFIG_ERROR -> {
                fill.color = when (kind) {
                    ConnectionStatus.Kind.CLOUD_CONNECTED -> 0xFFA8C7FA.toInt()
                    ConnectionStatus.Kind.CLOUD_CONFIG_ERROR -> 0xFFFBBC04.toInt()
                    else -> muted
                }
                canvas.drawCircle(cx - 4.5f, cy + 1.5f, 3.6f, fill)
                canvas.drawCircle(cx + 1f, cy - 2.2f, 4.8f, fill)
                canvas.drawCircle(cx + 5.2f, cy + 1.5f, 3.3f, fill)
                canvas.drawRect(cx - 8f, cy + 1.5f, cx + 8.4f, cy + 5f, fill)
            }
            ConnectionStatus.Kind.SIMULATING -> {
                fill.color = 0xFFF9AB00.toInt()
                val tri = Path().apply {
                    moveTo(cx - 4f, cy - 6f)
                    lineTo(cx - 4f, cy + 6f)
                    lineTo(cx + 6f, cy)
                    close()
                }
                canvas.drawPath(tri, fill)
            }
            ConnectionStatus.Kind.SCANNING -> {
                fill.color = muted
                canvas.drawCircle(cx - 6f, cy, 1.8f, fill)
                canvas.drawCircle(cx, cy, 1.8f, fill)
                canvas.drawCircle(cx + 6f, cy, 1.8f, fill)
            }
            ConnectionStatus.Kind.OFFLINE -> {
                stroke.color = 0xFFEA4335.toInt()
                val x = Path().apply {
                    moveTo(cx - 5f, cy - 5f)
                    lineTo(cx + 5f, cy + 5f)
                    moveTo(cx + 5f, cy - 5f)
                    lineTo(cx - 5f, cy + 5f)
                }
                canvas.drawPath(x, stroke)
            }
        }
    }

    private fun drawStaleDataRibbon(canvas: Canvas, w: Float) {
        val ageMs = NavStateManager.dataAgeMs() ?: return
        // Staleness is graded per transport (StaleDataPolicy, layer 2):
        // a healthy Bluetooth bridge cycle takes seconds, so the flat
        // 5 s rule cried wolf on a working Bluetooth link (Param's
        // photo, 2026-11-06: "last update 5s ago" while connected).
        val isBluetooth = NavStateManager.activeTransport ==
            NavStateManager.TransportType.BLUETOOTH
        if (!StaleDataPolicy.isStale(ageMs, isBluetooth)) return
        val ageText = StaleDataPolicy.ageText(ageMs)
        canvas.drawRect(0f, 0f, w, 34f, paintStaleBg)
        canvas.drawText("⚠ STALE DATA — last update $ageText ago", w / 2f, 22f, paintStaleText)
    }

    private fun drawMapWatermark(canvas: Canvas, w: Float, h: Float, source: BitmapSource, hasBitmap: Boolean) {
        // Attribution follows the tile source actually on screen (review B4);
        // the no-bitmap grid fallback shows none.
        if (!hasBitmap) return
        val text = when (source) {
            BitmapSource.COMMA_LOCAL -> "⚡ Comma Local Storage"
            BitmapSource.MAPBOX -> "© Mapbox © OpenStreetMap"
            BitmapSource.OSM -> "© OpenStreetMap contributors"
            BitmapSource.NONE -> return
        }
        canvas.drawText(text, w - 24f, h - 22f, paintWatermark)
    }

    private fun drawRouteGeometry(
        canvas: Canvas,
        w: Float,
        h: Float,
        route: RouteOption?,
        dest: NavDestination?,
        centerLat: Double,
        centerLng: Double,
        zoom: Double
    ) {
        val allRoutes = NavStateManager.activeRoutes
        val selectedId = NavStateManager.selectedRouteId
        // Draw alternative routes first (in de-emphasized slate)
        if (allRoutes.size > 1) {
            for (alt in allRoutes) {
                if (alt.id != selectedId && alt.points.size >= 2) {
                    drawRoutePath(canvas, w, h, alt.points, centerLat, centerLng, zoom, isAlt = true)
                }
            }
        }
        // Draw selected/active route
        if (route != null && route.points.size >= 2) {
            drawRoutePath(canvas, w, h, route.points, centerLat, centerLng, zoom, isAlt = false)
        } else if (dest != null && dest.hasCoordinates && route == null) {
            drawRoutePath(
                canvas, w, h,
                listOf(
                    org.maplibre.android.geometry.LatLng(centerLat, centerLng),
                    org.maplibre.android.geometry.LatLng(dest.latitude, dest.longitude)
                ),
                centerLat, centerLng, zoom, isAlt = false
            )
        }
    }


    /**
     * Route feature markers on the Auto map (Param, 2026-11-10):
     * traffic signals + stop signs along the route, projected with
     * the same Web-Mercator math as drawRoutePath and drawn inside
     * the same map transform, so heading-up rotation stays aligned.
     * Called only when the base is NOT a Mapbox static image (there
     * the route is baked into the image and this projection would
     * not align). Each kind follows its own Phone Data toggle;
     * state lists are empty when a toggle is off.
     */
    private fun drawRouteFeatureMarkers(
        canvas: Canvas, w: Float, h: Float,
        centerLat: Double, centerLng: Double, zoom: Double
    ) {
        val signals = if (NavStateManager.usePhoneSignals) NavStateManager.routeSignals else emptyList()
        val stops = if (NavStateManager.usePhoneStops) NavStateManager.routeStops else emptyList()
        if (signals.isEmpty() && stops.isEmpty()) return
        val z = zoom.roundToInt().coerceIn(10, 18)
        val n = 1 shl z
        val xCenterExact = (centerLng + 180.0) / 360.0 * n
        val latRad = Math.toRadians(centerLat)
        val yCenterExact = (1.0 - kotlin.math.asinh(kotlin.math.tan(latRad)) / Math.PI) / 2.0 * n
        val leftPx = (xCenterExact * 256.0) - (800.0 * 0.5)
        val topPx = (yCenterExact * 256.0) - (480.0 * 0.60)
        val scaleX = w / 800.0
        val scaleY = h / 480.0
        fun project(p: org.maplibre.android.geometry.LatLng): Pair<Float, Float> {
            val pxExact = (p.longitude + 180.0) / 360.0 * n
            val pLatRad = Math.toRadians(p.latitude)
            val pyExact = (1.0 - kotlin.math.asinh(kotlin.math.tan(pLatRad)) / Math.PI) / 2.0 * n
            return Pair(
                ((pxExact * 256.0 - leftPx) * scaleX).toFloat(),
                ((pyExact * 256.0 - topPx) * scaleY).toFloat()
            )
        }
        for (m in signals) {
            val (x, y) = project(m.position)
            if (x < -30f || x > w + 30f || y < -30f || y > h + 30f) continue
            drawEmojiMarker(canvas, "🚦", x, y, scaleX.toFloat())
        }
        for (m in stops) {
            val (x, y) = project(m.position)
            if (x < -30f || x > w + 30f || y < -30f || y > h + 30f) continue
            drawEmojiMarker(canvas, "🛑", x, y, scaleX.toFloat())
        }
    }

    /**
     * One route-feature marker as an emoji glyph (Param, 2026-11-18:
     * "Convert pins to traffic 🚦 on auto") — the same emoji the
     * Mapbox static frames carry, drawn here by the system emoji
     * font on the canvas-projected frames.
     */
    private fun drawEmojiMarker(canvas: Canvas, emoji: String, cx: Float, cy: Float, s: Float) {
        paintEmoji.textSize = 30f * s
        val fm = paintEmoji.fontMetrics
        canvas.drawText(emoji, cx, cy - (fm.ascent + fm.descent) / 2f, paintEmoji)
    }

    private fun drawRoutePath(
        canvas: Canvas,
        w: Float,
        h: Float,
        pts: List<org.maplibre.android.geometry.LatLng>,
        centerLat: Double,
        centerLng: Double,
        zoom: Double,
        isAlt: Boolean
    ) {
        if (pts.isEmpty()) return
        val z = zoom.roundToInt().coerceIn(10, 18)
        val n = 1 shl z

        val xCenterExact = (centerLng + 180.0) / 360.0 * n
        val latRad = Math.toRadians(centerLat)
        val yCenterExact = (1.0 - kotlin.math.asinh(kotlin.math.tan(latRad)) / Math.PI) / 2.0 * n

        val leftPx = (xCenterExact * 256.0) - (800.0 * 0.5)
        val topPx = (yCenterExact * 256.0) - (480.0 * 0.60)

        // Projection inlined into floats + a scratch Path (audit
        // finding 26): this used to allocate a PointF per route point
        // and a Path per route on every frame.
        val path = scratchRoutePath
        path.reset()
        for (i in pts.indices) {
            val pxExact = (pts[i].longitude + 180.0) / 360.0 * n
            val pLatRad = Math.toRadians(pts[i].latitude)
            val pyExact = (1.0 - kotlin.math.asinh(kotlin.math.tan(pLatRad)) / Math.PI) / 2.0 * n
            val px = ((pxExact * 256.0 - leftPx) * (w / 800.0)).toFloat()
            val py = ((pyExact * 256.0 - topPx) * (h / 480.0)).toFloat()
            if (i == 0) {
                path.moveTo(px, py)
            } else {
                path.lineTo(px, py)
            }
        }

        if (isAlt) {
            canvas.drawPath(path, paintAltRouteCasing)
            canvas.drawPath(path, paintAltRouteLine)
        } else {
            // Google Maps treatment: white casing + a soft halo in
            // the same blue, then the #1A73E8 core (GoogleMapsLook).
            canvas.drawPath(path, paintRouteCasing)
            canvas.drawPath(path, paintRouteGlow)
            canvas.drawPath(path, paintRouteLine)
        }
    }


}
