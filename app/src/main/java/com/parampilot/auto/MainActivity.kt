package com.parampilot.auto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.speech.RecognizerIntent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import android.graphics.Color
import android.graphics.Typeface
import android.app.AlertDialog
import android.content.DialogInterface
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.roundToInt

/**
 * In-Car HUD Dashboard for ParamAuto.
 *
 * Full-screen Mapbox navigation map view with minimal HUD overlays:
 * - Google Maps style floating destination search bar & live suggestion dropdown
 * - Multi-route display (primary in cyan, alternatives in gray, tap to select & dispatch)
 * - Alternate routes overview button (branching Y-shape with dual arrows)
 * - Sticky North-Up / Heading-Up toggle on compass tap
 * - Vertical speed stack (Close -> Alt Routes -> Compass -> Speed Limit -> Speed)
 * - Car 12V voltage, connection status, and settings button in bottom-left
 */
class MainActivity : Activity() {

    private var client: CommaClient? = null
    private lateinit var dialogs: SettingsDialogs
    private lateinit var hud: HudOverlay
    private lateinit var topBar: TopSearchBar
    private val navManager = NavigationManager()

    private var mapView: MapView? = null
    private var mapContainer: FrameLayout? = null
    private var startupPlaceholder: android.widget.ImageView? = null
    private var mapLibreMap: MapLibreMap? = null
    private var vehicleMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var destinationIcon: Icon? = null
    // Long-press dropped pin (Addendum 115): marker + card + the
    // destination the save dialog will start from.
    private var droppedPinMarker: Marker? = null
    private var droppedPinCard: LinearLayout? = null
    // Addendum 133: last reported bottom-dock footprint, so the
    // dropped-pin card can ride above the dock stack.
    private var bottomDockFootprintPx = 0
    private var droppedPinLabel: TextView? = null
    private var droppedPinDest: NavDestination? = null
    private val altRouteMarkers = mutableListOf<Marker>()
    private val routeFeatureMarkers = mutableListOf<Marker>()
    private var routeFeatureIconSignal: Icon? = null
    private var routeFeatureIconStop: Icon? = null
    private var lastDrawnFeatureKey: String? = null
    private val altRouteMarkersMap = mutableMapOf<Marker, RouteOption>()
    private val routePolylines = mutableListOf<Polyline>()
    private var currentGps: GpsPosition? = null
    // Phone-local nav intent (audit finding 9): these two mirror
    // NavStateManager's destination/routes for the phone UI. The
    // sync contract: NavStateManager -> here happens ONLY in the
    // guarded state listener (isSyncingNavState); a user action that
    // publishes must write BOTH (see the packet-destination adoption
    // in the listener) — and a destination counts as accepted only
    // when the comma confirms it, never from a local write alone.
    private var activeDestination: NavDestination? = null
    private var activeRoutes: List<RouteOption> = emptyList()
    private var selectedRouteId: Int = 0
    private var isNorthUp: Boolean = false
    private var isRoutesOverviewActive: Boolean = false
    private var isUserTracking: Boolean = true
    private var lastGestureBreakMs: Long = 0L
    private var currentZoom: Double = 16.0
    private var vehicleIcon: Icon? = null
    private var isNavClearedByUser: Boolean = false
    private var lastClearedDestination: NavDestination? = null
    private var lastCommaPlacesSig: String = ""
    private var isSyncingNavState: Boolean = false

    /** Activity-result request code for the voice search round-trip (F18). */
    private val REQ_VOICE_SEARCH: Int = 4211
    private lateinit var turnBanner: TurnBannerView
    private lateinit var tripInfoBar: TripInfoBar
    private lateinit var arrivalCard: ArrivalCardView
    private lateinit var driveSummaryCard: DriveSummaryView
    private lateinit var lastDriveCard: LastDriveCardView
    private var lastDriveCardShown: Boolean = false
    private var latestPacket: TelemetryPacket? = null
    private var isRealMapStyleLoaded: Boolean = false

    // 1-second ticker that keeps the status pill's data-freshness segment live.
    private val freshnessHandler = android.os.Handler(Looper.getMainLooper())
    private val freshnessTicker = object : Runnable {
        override fun run() {
            if (::hud.isInitialized) {
                hud.updateFreshness(NavStateManager.lastPacketReceivedAtMs)
            }
            updateKeepScreenOn()
            freshnessHandler.postDelayed(this, 1000L)
        }
    }

    /**
     * FLAG_KEEP_SCREEN_ON only while it earns its battery (audit
     * finding 32): navigating, driving, or actually moving. Parked
     * with the app open, the screen may sleep like any other app.
     * Re-evaluated on the 1 s ticker.
     */
    private fun updateKeepScreenOn() {
        val state = NavStateManager.deviceState?.lowercase()?.trim()
        val want = NavStateManager.activeDestination != null ||
            state == "driving" || state == "started" || state == "engaged" ||
            (NavStateManager.latestPacket?.speedMs ?: 0.0) > 0.5
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val has = (window.attributes.flags and flag) != 0
        if (want && !has) {
            window.addFlags(flag)
        } else if (!want && has) {
            window.clearFlags(flag)
        }
    }

    enum class MapPickMode {
        HOME,
        OFFICE
    }

    private var currentMapPickMode: MapPickMode? = null
    private var mapPickMarker: Marker? = null
    private lateinit var mapPickBanner: LinearLayout
    private lateinit var mapPickInstructionTv: TextView
    private lateinit var mapPickAddressTv: TextView
    private lateinit var mapPickConfirmBtn: TextView
    private lateinit var mapPickCancelBtn: TextView
    private var pendingPickedDest: NavDestination? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        UsageCounters.init(this)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // FLAG_KEEP_SCREEN_ON is managed by updateKeepScreenOn()
        // (audit finding 32) — no longer unconditional.
        super.onCreate(savedInstanceState)

        NavStateManager.appContext = applicationContext
        dialogs = SettingsDialogs(this)
        // Addendum 126: restore a pending comma software install so the
        // badge shows "Updating…" from the first frame after a restart.
        NavStateManager.softwareUpdatePending = SoftwareUpdateIntentStore.load(this) != null
        // Addendum 128: restore the last known comma channel; a first
        // install has none stored and resolves to stable until the
        // first software status is read (CommaBranch.kt).
        NavStateManager.commaBranch = CommaBranchStore.load(this)
        topBar = TopSearchBar(
            this,
            onSearchQuery = { searchDestinations(it) },
            onDestinationSelected = { handleDestinationSelected(it) },
            onConfigureHomeRequested = { startMapPickMode(MapPickMode.HOME) },
            onConfigureOfficeRequested = { startMapPickMode(MapPickMode.OFFICE) },
            onStarDestinationClicked = { promptStarDestination(it) },
            onVoiceSearchRequested = { startVoiceSearch() }
        )
        turnBanner = TurnBannerView(this, onEndRouteClick = { endRouteByUser() })
        tripInfoBar = TripInfoBar(this, onCloseClick = { endRouteByUser() })
        arrivalCard = ArrivalCardView(this, onDoneClick = { arrivalCard.hide() })
        driveSummaryCard = DriveSummaryView(this, onDoneClick = { driveSummaryCard.hide() })
        lastDriveCard = LastDriveCardView(this, onDismissClick = { lastDriveCard.hide() })
        hud = HudOverlay(this,
            onStatusClick = { dialogs.showConnectionModeDialog(client) { updateStatus() } },
            onSettingsClick = {
                dialogs.showSettingsMenu(
                    client,
                    { recenterToVehicle() },
                    { openExternalMap() },
                    { updateStatus() },
                    { hud.updateVoltage(it) },
                    { applyCurrentTheme() },
                    { toggleSimulation() }
                )
            },
            onSimClick = { toggleSimulation() },
            onAltRoutesClick = { toggleRoutesOverview() },
            onRecenterClick = { recenterToVehicle() },
            onSpeedCardClick = { recenterToVehicle() },
            onCompassClick = { toggleNorthUp() },
            onUpdateBadgeClick = { dialogs.showSoftwareUpdateDialog(client) },
            onSpeedLimitLongPress = { dialogs.showOffsetsGlanceDialog(client) },
            onGpuClick = { GpuDialog.show(this) }
        )

        // Startup split (Param, 2026-11-07): the canvas and HUD
        // draw immediately; everything heavy — the disk caches,
        // MapLibre, the MapView itself — loads after the first
        // frame in finishStartup(). onStart attaches the client
        // exactly as before.
        val root = buildContentView(savedInstanceState)
        setContentView(root)
        root.post { finishStartup(savedInstanceState) }
        freshnessHandler.post(freshnessTicker)
    }

    private var startupFinished = false

    /**
     * Phase 2 of startup: runs once, after the first frame is on
     * screen, so tapping the app icon shows the canvas immediately
     * instead of a blank wait (Param, 2026-11-07). Pure disk work
     * (the cache scans) goes to a background thread; the tile
     * interceptor self-inits its cache lazily if a request races
     * ahead of the scan.
     */
    private fun finishStartup(savedInstanceState: Bundle?) {
        if (startupFinished || isDestroyed) return
        startupFinished = true
        Thread {
            try { CommaTileDiskCache.init(this) } catch (e: Exception) { Log.w("MainActivity", "Tile cache init failed: ${e.message}") }
            try { MapFrameDiskCache.init(this) } catch (e: Exception) { Log.w("MainActivity", "Frame cache init failed: ${e.message}") }
        }.start()
        val c = client ?: CommaClient.getInstance(this).also { client = it }
        try {
            MapLibre.getInstance(this, c.mapboxToken ?: "", WellKnownTileServer.Mapbox)
        } catch (_: Exception) {
            try { MapLibre.getInstance(this) } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
        installTileHttpClient()
        vehicleIcon = try { IconFactory.getInstance(this).fromBitmap(VehicleChevron(this).createBitmap()) } catch (_: Exception) { null }
        createMapView(savedInstanceState)
        checkBleTestIntent(intent)
        handleSharedIntent(intent)
        handleViewIntent(intent)
        // First-run onboarding (Param, 2026-11-20): two steps —
        // connect to the comma, then map keys. Shown once, after the
        // map is up; the same pages live in Settings afterwards.
        if (Onboarding.shouldShow(this)) {
            freshnessHandler.postDelayed({
                if (!isDestroyed && Onboarding.shouldShow(this)) {
                    dialogs.showOnboardingDialog(
                        c, onStatusRefresh = { updateStatus() }
                    )
                }
            }, 800)
        }
    }

    /**
     * finishStartup, MapLibre HTTP stack (extracted 2026-11-08):
     * the tile-serving OkHttp client — phone disk cache first,
     * CARTO fallback caching, comma tiles with the Galaxy
     * session cookie and one 401 relogin retry.
     */
    private fun installTileHttpClient() {
        try {
            val okHttpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request()
                    val url = req.url
                    val urlStr = url.toString()
                    if (android.util.Log.isLoggable("ParamAutoMap", android.util.Log.DEBUG)) {
                        val redacted = urlStr.replace(Regex("access_token=[^&]+"), "access_token=REDACTED")
                        android.util.Log.d("ParamAutoMap", "OkHttp tile/web request: $redacted")
                    }
                    val isCommaTile = urlStr.contains("/api/navigation/map/tiles/")
                    val isCartoTile = urlStr.contains("basemaps.cartocdn.com")
                    val isTileRequest = isCommaTile || isCartoTile

                    var tileZ: Int? = null
                    var tileX: Int? = null
                    var tileY: Int? = null
                    var tileTheme = "dark"

                    if (isCommaTile) {
                        val pathSegments = url.pathSegments
                        val tilesIdx = pathSegments.indexOf("tiles")
                        if (tilesIdx in 0..pathSegments.size - 4) {
                            tileZ = pathSegments[tilesIdx + 1].toIntOrNull()
                            tileX = pathSegments[tilesIdx + 2].toIntOrNull()
                            tileY = pathSegments[tilesIdx + 3].removeSuffix(".png").toIntOrNull()
                            tileTheme = url.queryParameter("theme") ?: "dark"
                        }
                    } else if (isCartoTile) {
                        val pathSegments = url.pathSegments
                        if (pathSegments.size >= 3) {
                            tileZ = pathSegments[pathSegments.size - 3].toIntOrNull()
                            tileX = pathSegments[pathSegments.size - 2].toIntOrNull()
                            tileY = pathSegments[pathSegments.size - 1].removeSuffix(".png").toIntOrNull()
                            tileTheme = if (urlStr.contains("dark_all")) "dark" else "light"
                        }
                    }

                    if (isTileRequest && tileZ != null && tileX != null && tileY != null) {
                        val cachedBytes = CommaTileDiskCache.getTileBytes(tileZ, tileX, tileY, tileTheme)
                        if (cachedBytes != null) {
                            return@addInterceptor okhttp3.Response.Builder()
                                .request(req)
                                .protocol(okhttp3.Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK (from phone disk cache)")
                                .header("Content-Type", "image/png")
                                .header("Cache-Control", "public, max-age=31536000, immutable")
                                .header("X-ParamAuto-Cache", "HIT-PHONE-DISK")
                                .body(cachedBytes.toResponseBody("image/png".toMediaTypeOrNull()))
                                .build()
                        }
                    }

                    if (isCartoTile) {
                        val cartoReq = req.newBuilder()
                            .header("User-Agent", "ParamAuto/1.0")
                            .build()
                        val response = try {
                            chain.proceed(cartoReq)
                        } catch (e: Exception) {
                            return@addInterceptor okhttp3.Response.Builder()
                                .request(req)
                                .protocol(okhttp3.Protocol.HTTP_1_1)
                                .code(504)
                                .message("CARTO unreachable: ${e.message}")
                                .body(ByteArray(0).toResponseBody("text/plain".toMediaTypeOrNull()))
                                .build()
                        }
                        if (response.isSuccessful && tileZ != null && tileX != null && tileY != null) {
                            val body = response.body
                            if (body != null) {
                                val bytes = body.bytes()
                                if (bytes.isNotEmpty()) {
                                    CommaTileDiskCache.putTileBytes(tileZ, tileX, tileY, tileTheme, bytes)
                                    return@addInterceptor response.newBuilder()
                                        .header("Cache-Control", "public, max-age=31536000, immutable")
                                        .header("X-ParamAuto-Cache", "MISS-CARTO-SAVED")
                                        .body(bytes.toResponseBody("image/png".toMediaTypeOrNull()))
                                        .build()
                                }
                            }
                        }
                        return@addInterceptor response
                    }

                    if (isCommaTile || urlStr.contains(":8082")) {
                        var token = GalaxyCloudSession.currentLocalToken()
                            ?: GalaxyCloudSession.currentToken()
                        val newReq = if (!token.isNullOrEmpty()) {
                            req.newBuilder()
                                .header("Cookie", "${GalaxyCloudSession.COOKIE_NAME}=$token")
                                .build()
                        } else {
                            req
                        }
                        var response: okhttp3.Response? = try {
                            chain.proceed(newReq)
                        } catch (e: Exception) {
                            if (!isCommaTile) throw e
                            null
                        }

                        if (response != null && response.code == 401) {
                            response.close()
                            val commaIp = NavStateManager.commaIp
                            if (!commaIp.isNullOrBlank()) {
                                val base = SgGalaxyClient.baseUrlFor(commaIp)
                                GalaxyCloudSession.relogin(base, NavStateManager.isGalaxyCloud)
                                token = GalaxyCloudSession.currentLocalToken() ?: GalaxyCloudSession.currentToken()
                                if (!token.isNullOrEmpty()) {
                                    val retriedReq = req.newBuilder()
                                        .header("Cookie", "${GalaxyCloudSession.COOKIE_NAME}=$token")
                                        .build()
                                    response = try { chain.proceed(retriedReq) } catch (_: Exception) { null }
                                }
                            }
                        }

                        // If tile was fetched from Comma, cache it to phone disk
                        if (isCommaTile && response != null && response.isSuccessful && tileZ != null && tileX != null && tileY != null) {
                            val body = response.body
                            if (body != null) {
                                val contentType = body.contentType()
                                val bytes = body.bytes()
                                if (bytes.isNotEmpty()) {
                                    CommaTileDiskCache.putTileBytes(tileZ, tileX, tileY, tileTheme, bytes)
                                    return@addInterceptor response.newBuilder()
                                        .header("Cache-Control", "public, max-age=31536000, immutable")
                                        .header("X-ParamAuto-Cache", "MISS-COMMA-SAVED")
                                        .body(bytes.toResponseBody(contentType ?: "image/png".toMediaTypeOrNull()))
                                        .build()
                                }
                            }
                        }

                        if (isCommaTile) {
                            android.util.Log.w("ParamAutoMap", "Comma tile response: code=${response?.code} (msg=${response?.message}) for $urlStr")
                        }
                        if (isCommaTile && response == null) {
                            return@addInterceptor okhttp3.Response.Builder()
                                .request(req)
                                .protocol(okhttp3.Protocol.HTTP_1_1)
                                .code(504)
                                .message("Tile unreachable")
                                .body(ByteArray(0).toResponseBody("text/plain".toMediaTypeOrNull()))
                                .build()
                        }

                        return@addInterceptor response ?: chain.proceed(req)
                    }
                    chain.proceed(req)
                }
                .build()
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(okHttpClient)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to configure MapLibre OkHttpClient: ${e.message}")
        }
    }

    private var locationManager: LocationManager? = null
    private var lastLocationTimeMs: Long = 0L
    private var lastLocationAccuracy: Float = Float.MAX_VALUE
    // C5: chevron bitmaps are cached per 5°-quantized bearing; the camera
    // position animation is throttled; route draws are skipped when unchanged.
    private var lastRenderedBearingDeg: Int = -1
    private val chevronBitmapCache = mutableMapOf<Int, Bitmap>()
    private var lastCameraAnimMs: Long = 0L
    private var lastDrawnRoutes: List<RouteOption>? = null
    private var lastDrawnSelectedRouteId: Int = -1

    private val phoneLocationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (SimulationEngine.isSimulating) return
            // The feeder must see every real fix while a session is active —
            // including when the comma's own GPS makes the rest of this
            // listener a no-op below. It self-gates on isFeeding.
            PhoneGpsFeeder.onLocation(loc)
            // Cloud gate (Param, 2026-11-12): on Galaxy Cloud the
            // phone's location and speed are not used at all.
            if (!HeartbeatTransport.phoneDataAllowed(NavStateManager.activeTransport)) return
            if (client?.commaFound == true && NavStateManager.latestPacket?.gps?.hasFix == true) return
            val useLoc = NavStateManager.usePhoneLocation
            val useSpd = NavStateManager.usePhoneSpeed
            if (!useLoc && !useSpd) return
            val now = System.currentTimeMillis()
            if (loc.hasAccuracy() && loc.accuracy > 100f && (now - lastLocationTimeMs) < 3000 && lastLocationAccuracy < 30f) {
                return
            }
            lastLocationTimeMs = now
            if (loc.hasAccuracy()) lastLocationAccuracy = loc.accuracy

            val gps = GpsPosition(
                latitude = loc.latitude,
                longitude = loc.longitude,
                bearing = if (loc.hasBearing()) loc.bearing.toDouble() else (currentGps?.bearing ?: 0.0),
                speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                hasFix = true
            )
            if (useLoc) {
                currentGps = gps
                NavStateManager.updateGps(if (useSpd) gps else gps.copy(speed = 0.0))
            }
            runOnUiThread {
                if (useLoc && !isRoutesOverviewActive) {
                    updateMapPosition(gps, animate = true)
                }
                if (useSpd && loc.hasSpeed()) {
                    hud.updateSpeed(loc.speed.toDouble(), latestPacket?.speedLimit?.limitMs ?: 0.0)
                }
                if (useLoc && loc.hasBearing() && !isNorthUp) {
                    hud.updateBearing(loc.bearing.toDouble())
                }
                if (useLoc && activeDestination != null) {
                    turnBanner.update(latestPacket?.nav ?: NavManeuver(), activeDestination)
                }
            }
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val navStateListener: () -> Unit = {
        runOnUiThread {
            updateStatus()
            // Home-screen widget (Feature 20): persist the last-known
            // comma state for the widget; change-gated inside the updater.
            CommaWidgetUpdater.refresh(this)
            // Offline queue chip (Feature 10): cheap setter, refreshed on
            // every state change.
            if (::topBar.isInitialized) {
                topBar.setQueuedDestination(
                    NavStateManager.queuedDestination,
                    NavStateManager.queuedOutOfSync
                ) {
                    cancelQueuedDestination()
                }
                // Same queued state on the nav banner (Addendum 122):
                // the top bar is hidden while navigating, so the banner
                // row is the one queued popup on screen then.
                if (::turnBanner.isInitialized) {
                    turnBanner.setQueued(
                        NavStateManager.queuedDestination,
                        NavStateManager.queuedOutOfSync
                    ) {
                        cancelQueuedDestination()
                    }
                }
                // Comma places (Feature 8): re-render open suggestions only
                // when the comma's lists actually changed.
                val commaSig = (NavStateManager.commaFavorites + NavStateManager.commaRecents)
                    .joinToString("|") { "${it.name}@${it.latitude},${it.longitude}" }
                if (commaSig != lastCommaPlacesSig) {
                    lastCommaPlacesSig = commaSig
                    if (topBar.isShowingSuggestions()) topBar.refreshSuggestions()
                }
            }
            // Arrival card (Feature 2): consume a pending arrival exactly
            // once and show it over the (now ended) navigation UI.
            NavStateManager.pendingArrival?.let { summary ->
                NavStateManager.pendingArrival = null
                // Navigation has ended: the phone-location feed stops.
                PhoneGpsFeeder.stop()
                if (::arrivalCard.isInitialized) arrivalCard.show(summary)
            }
            // The feed also stops when navigation ends any other way
            // (comma-side clear, end route): no active destination
            // outside the send grace window means the trip is over.
            if (PhoneGpsFeeder.isNavigationSession && NavStateManager.activeDestination == null &&
                !NavStateManager.isDestinationPendingGrace()
            ) {
                PhoneGpsFeeder.stop()
            }
            // Drive summary (Day 3): consume a finished trip exactly once.
            // If the arrival card just showed for this same trip, the
            // summary joins it as a "Driven:" line instead of a second
            // stacked card; otherwise it shows as its own card.
            NavStateManager.pendingDriveSummary?.let { summary ->
                NavStateManager.pendingDriveSummary = null
                if (::driveSummaryCard.isInitialized) {
                    if (::arrivalCard.isInitialized && arrivalCard.isShowing) {
                        arrivalCard.showDriveSummary(summary)
                    } else {
                        driveSummaryCard.show(summary)
                    }
                }
            }
            if (isSyncingNavState) return@runOnUiThread
            val dest = NavStateManager.activeDestination
            val cur = activeDestination
            val destChanged = if (dest == null) {
                cur != null
            } else {
                cur == null || !cur.isSameAs(dest)
            }
            if (destChanged) {
                isSyncingNavState = true
                try {
                    if (dest != null) {
                        if (!isNavClearedByUser) {
                            applyDestination(dest, notifySharedState = false, sendToDevice = false)
                        }
                    } else if (cur != null) {
                        clearNavigation(notifySharedState = false)
                    }
                } finally {
                    isSyncingNavState = false
                }
            } else {
                // If route changed without destination changing (e.g. from comma status polling)
                val sharedRoutes = NavStateManager.activeRoutes
                val sharedSel = NavStateManager.selectedRouteId
                if (sharedRoutes != activeRoutes || sharedSel != selectedRouteId) {
                    activeRoutes = sharedRoutes
                    selectedRouteId = sharedSel
                    if (sharedRoutes.isNotEmpty()) {
                        drawRoutesOnMap(sharedRoutes, sharedSel)
                        val chosen = NavStateManager.selectedRoute
                        if (chosen != null) {
                            if (chosen.durationMin <= 0 && chosen.distanceMiles <= 0.0) {
                                // Route carries no provided duration/distance: hide, don't show 0/0.
                                tripInfoBar.hide()
                            } else {
                                tripInfoBar.update(chosen.durationMin, chosen.distanceMiles)
                            }
                        }
                    } else {
                        drawRoutesOnMap(emptyList(), -1)
                        tripInfoBar.hide()
                    }
                }
            }

            // Invariant: If there is no active destination anywhere, ensure no route polylines linger
            if (NavStateManager.activeDestination == null && activeDestination == null &&
                (routePolylines.isNotEmpty() || altRouteMarkers.isNotEmpty() || activeRoutes.isNotEmpty())
            ) {
                activeRoutes = emptyList()
                drawRoutesOnMap(emptyList(), -1)
                tripInfoBar.hide()
            }
            // End Route confidence (backlog #3): while an end state is
            // visible, a bottom card shows it (moved off the top in
            // Addendum 122 so it never covers the search bar) —
            // "Ending route…" in flight, "Route ended ✓" once the
            // comma confirms, "⚠ Comma still navigating" when the
            // device kept the destination (tap the card to retry). A
            // live destination always wins over a stale end state.
            // Driven last so a clearNavigation inside this listener
            // cannot hide the banner after it is shown.
            if (::turnBanner.isInitialized) {
                if (EndRouteConfidence.isVisible(
                        NavStateManager.endRouteState,
                        NavStateManager.activeDestination != null,
                        NavStateManager.endRouteAtMs, System.currentTimeMillis()
                    )
                ) {
                    turnBanner.showEndState(NavStateManager.endRouteState, NavStateManager.endRouteDestName)
                } else {
                    turnBanner.hideEndState()
                }
            }
            updateStatus()
        }
    }

    private val isAutoConfiguring = java.util.concurrent.atomic.AtomicBoolean(false)

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action == BluetoothDevice.ACTION_ACL_CONNECTED || action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val name = try { device.name } catch (_: SecurityException) { null }
                val addr = device.address ?: ""
                val curMode = client?.getConnectionMode() ?: CommaClient.MODE_AUTO
                if ((name?.contains("comma", ignoreCase = true) == true || addr.equals("39:90:21:64:07:00", ignoreCase = true)) &&
                    (curMode == CommaClient.MODE_AUTO || curMode == CommaClient.MODE_BLUETOOTH)) {
                    if (BleBridgeClient.hasPairingKey(context)) {
                        BleBridgeClient.startIfPaired(context)
                    } else if (isAutoConfiguring.compareAndSet(false, true)) {
                        Log.i("MainActivity", "Bluetooth connected to Comma ($name, $addr) — auto-configuring BLE key")
                        Thread({
                            try {
                                BleBridgeClient.fetchAndSaveKeyFromComma(context, client?.commaIp)
                                BleBridgeClient.startIfPaired(context)
                            } finally {
                                isAutoConfiguring.set(false)
                            }
                        }, "comma-bt-autoconfig").start()
                    }
                }
            }
        }
    }

    private var isBtReceiverRegistered = false
    private var isClientAttached = false

    private fun registerBluetoothReceiver() {
        if (isBtReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(bluetoothReceiver, filter)
        isBtReceiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!isBtReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        isBtReceiverRegistered = false
    }

    override fun onStart() {
        super.onStart(); mapView?.onStart()
        // No permission requests at startup (Param, 2026-11-02): location
        // updates start only when a Phone Data toggle is on AND the
        // permission was already granted from that toggle's own prompt.
        startLocationUpdates()
        val c = client ?: CommaClient.getInstance(this).also { client = it }
        if (!isClientAttached) {
            c.attach()
            isClientAttached = true
        }
        c.setListener { pkt -> runOnUiThread { updateUi(pkt) } }
        c.setDiagnosticListener { runOnUiThread { updateStatus() } }
        NavStateManager.addListener(navStateListener)
        updateStatus(); hud.updateVoltage(NavStateManager.latestPower)
        // BLE configure runs AFTER the first frame (2026-11-08
        // startup debug): adapter checks + bonding prefs sat on the
        // pre-first-frame path in onStart; nothing about the canvas
        // needs them, and a frame's delay here is invisible work.
        window.decorView.post {
            if (isDestroyed) return@post
            BleBridgeClient.autoConfigureIfBonded(this, c.commaIp)
            if (c.getConnectionMode() == CommaClient.MODE_AUTO || c.getConnectionMode() == CommaClient.MODE_BLUETOOTH) {
                BleBridgeClient.startIfPaired(this)
            }
        }
        registerBluetoothReceiver()
    }

    override fun onResume() {
        super.onResume(); mapView?.onResume()
        // The freshness ticker pauses with the activity (audit
        // finding 51: it used to run 1 Hz for the activity's whole
        // lifetime, visible or not).
        freshnessHandler.removeCallbacks(freshnessTicker)
        freshnessHandler.post(freshnessTicker)
        startLocationUpdates()
        checkRecentCrashAndNotify()
        AnrWatchdog.showPendingReport(this)
    }

    private fun checkRecentCrashAndNotify() {
        try {
            val prefs = getSharedPreferences(ParamAuto.PREFS_CRASH, Context.MODE_PRIVATE)
            val lastTime = prefs.getLong(ParamAuto.KEY_LAST_TIME, 0L)
            val now = System.currentTimeMillis()
            if (lastTime > 0 && (now - lastTime) < 30000L) {
                val err = prefs.getString(ParamAuto.KEY_LAST_ERROR, "Unknown issue")
                val threadName = prefs.getString(ParamAuto.KEY_THREAD_NAME, "app")
                prefs.edit().putLong(ParamAuto.KEY_LAST_TIME, 0L).apply()
                android.widget.Toast.makeText(
                    this,
                    "Notice: Recovered from issue in $threadName: $err",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
    }

    override fun onPause() {
        // Cache the map's last frame for the next cold open
        // (Param, 2026-11-07): the snapshot callback is async and
        // the write itself runs on a background thread.
        try {
            mapLibreMap?.snapshot { bmp -> if (bmp != null) StartupMapCache.saveAsync(this@MainActivity, bmp) }
        } catch (e: Exception) { Log.w("MainActivity", "Startup snapshot failed: ${e.message}") }
        freshnessHandler.removeCallbacks(freshnessTicker)
        locationManager?.removeUpdates(phoneLocationListener)
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        unregisterBluetoothReceiver()
        NavStateManager.removeListener(navStateListener)
        locationManager?.removeUpdates(phoneLocationListener)
        SimulationEngine.stop()
        mapView?.onStop()
        if (isClientAttached) {
            client?.detach()
            isClientAttached = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        freshnessHandler.removeCallbacks(freshnessTicker)
        mapView?.onDestroy()
        super.onDestroy()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ThemeManager.getTheme(this) == ThemeManager.AppTheme.SYSTEM) {
            applyCurrentTheme()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm

        val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

        lastLoc?.let { loc ->
            if (currentGps == null && !SimulationEngine.isSimulating &&
                (NavStateManager.usePhoneLocation || NavStateManager.usePhoneSpeed)
            ) {
                val gps = GpsPosition(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    bearing = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0,
                    speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                    hasFix = true
                )
                if (NavStateManager.usePhoneLocation) {
                    currentGps = if (NavStateManager.usePhoneSpeed) gps else gps.copy(speed = 0.0)
                    updateMapPosition(currentGps!!, animate = false)
                }
            }
        }

        registerPhoneLocationUpdates(lm)
    }

    /** Registers [phoneLocationListener] on GPS/NETWORK (+ FUSED on API 31+). Identical to the startup registration. */
    @SuppressLint("MissingPermission")
    private fun registerPhoneLocationUpdates(lm: LocationManager) {
        if (!NavStateManager.usePhoneLocation && !NavStateManager.usePhoneSpeed && !NavStateManager.usePhoneLocationToComma) return
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            providers.add(LocationManager.FUSED_PROVIDER)
        }
        for (provider in providers) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 500L, 0f, phoneLocationListener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to register $provider: ${e.message}")
            }
        }
    }

    internal fun refreshPhoneLocationRegistration() {
        locationManager?.removeUpdates(phoneLocationListener)
        val lm = locationManager ?: return
        registerPhoneLocationUpdates(lm)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Lazy permission flow (Param, 2026-11-02): results for prompts that
        // Settings fired after explaining why are dispatched to the feature
        // that asked; grant continuations below still apply.
        if (requestCode == PermissionFlow.REQUEST_LOCATION || requestCode == PermissionFlow.REQUEST_BLUETOOTH) {
            // Location counts as granted when either precision was approved
            // (the user may pick Approximate); Bluetooth needs both halves.
            val granted = grantResults.isNotEmpty() && if (requestCode == PermissionFlow.REQUEST_LOCATION) {
                grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            } else {
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            }
            PermissionFlow.handleResult(requestCode, granted)
            if (granted && requestCode == PermissionFlow.REQUEST_LOCATION) startLocationUpdates()
            if (granted && requestCode == PermissionFlow.REQUEST_BLUETOOTH) {
                if (NavStateManager.bleStatus.startsWith("Permission")) {
                    NavStateManager.setBleStatus("Ready to scan")
                }
                client?.triggerStartupConnectionSequence()
            }
            return
        }
        val hasLoc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasLoc) {
            startLocationUpdates()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBt = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (hasBt) {
                if (NavStateManager.bleStatus.startsWith("Permission")) {
                    NavStateManager.setBleStatus("Ready to scan")
                }
                client?.triggerStartupConnectionSequence()
            }
        }
    }

    private var lastAppliedStyleKey: String? = null
    private var pendingStyleKey: String? = null
    private var pendingStyleSinceMs: Long = 0L

    /** A genuine style transition must hold this long before it is
     *  applied, so a flapping connection can't thrash full reloads. */
    private val STYLE_SETTLE_MS = 2500L

    private fun currentStyleKey(): String {
        // The key is the RESOLVED style's identity (layer 2,
        // MapSourceChoice.phoneStyleKey), computed from exactly the
        // inputs ThemeManager.getMapStyleJson resolves with — NOT
        // raw connection state. The 2026-11-10 roads fix keyed on
        // commaFound/IP/transport directly; on a marginal link
        // (Param's "Bluetooth · STALE" at home) every flap then
        // forced a full MapLibre style reload, most of them
        // reloading the identical Mapbox style, and the stacked
        // reloads starved the main thread into "isn't responding"
        // (Param, 2026-11-10 — with both route-feature toggles off,
        // which cleared the fetch path). Losing the comma with no
        // token still changes the resolution (COMMA_RASTER ->
        // fallback), so the roads still come back.
        val commaIp = NavStateManager.commaIp
        val isLocalIpConnected = NavStateManager.activeTransport == NavStateManager.TransportType.IP &&
            NavStateManager.commaFound &&
            !commaIp.isNullOrBlank() &&
            !NavStateManager.isGalaxyCloud
        val token = MapSourceChoice.effectiveMapboxToken()
        val googleSession = NavStateManager.googleTilesSession?.let {
            GoogleTiles.Session(it, NavStateManager.googleTilesSessionExpirySec)
        }
        val style = MapSourceChoice.phoneStyle(
            isLocalIpConnected = isLocalIpConnected,
            hasUsableToken = !token.isNullOrEmpty() && ThemeManager.isMapboxTokenValid,
            useVectorMap = NavStateManager.usePhoneVectorMap,
            phoneMapSource = NavStateManager.phoneMapSource,
            hasGoogleSession = GoogleTiles.sessionUsable(
                googleSession, System.currentTimeMillis() / 1000
            ) && PhoneGoogle.effectiveGoogleKey() != null
        )
        return MapSourceChoice.phoneStyleKey(
            style = style,
            isNight = ThemeManager.isNightMode(this),
            traffic = NavStateManager.usePhoneTraffic,
            commaIp = commaIp,
            googleSession = NavStateManager.googleTilesSession
        )
    }

    @Volatile
    private var googleSessionInFlight = false

    @Volatile
    private var lastGoogleSessionAttemptMs = 0L

    /**
     * Keeps a Google Map Tiles session ready while Google is the
     * chosen phone-map source: without a session the style choice
     * falls back to the next source, and the session's arrival (the
     * style key carries it) reloads the map onto Google tiles once.
     * Attempts are spaced a minute apart so a failing key cannot
     * spin a createSession loop.
     */
    private fun maybeRefreshGoogleTilesSession() {
        if (PhoneGoogle.sanitizeMapSource(NavStateManager.phoneMapSource) !=
            PhoneGoogle.SOURCE_GOOGLE
        ) {
            return
        }
        val key = PhoneGoogle.effectiveGoogleKey() ?: return
        val session = NavStateManager.googleTilesSession?.let {
            GoogleTiles.Session(it, NavStateManager.googleTilesSessionExpirySec)
        }
        if (GoogleTiles.sessionUsable(session, System.currentTimeMillis() / 1000)) return
        val now = System.currentTimeMillis()
        if (googleSessionInFlight || now - lastGoogleSessionAttemptMs < 60_000) return
        googleSessionInFlight = true
        lastGoogleSessionAttemptMs = now
        Thread {
            try {
                NavigationManager().createGoogleTilesSession(key)
            } finally {
                googleSessionInFlight = false
            }
        }.start()
    }

    private fun checkAndApplyMapStyle() {
        if (mapLibreMap == null) return
        maybeRefreshGoogleTilesSession()
        val styleKey = currentStyleKey()
        if (styleKey == lastAppliedStyleKey) {
            pendingStyleKey = null
            return
        }
        // First application is immediate; later transitions must
        // settle first (see STYLE_SETTLE_MS).
        if (lastAppliedStyleKey != null) {
            val now = System.currentTimeMillis()
            if (pendingStyleKey != styleKey) {
                pendingStyleKey = styleKey
                pendingStyleSinceMs = now
                return
            }
            if (now - pendingStyleSinceMs < STYLE_SETTLE_MS) return
        }
        pendingStyleKey = null
        lastAppliedStyleKey = styleKey
        runOnUiThread {
            applyCurrentTheme()
        }
    }

    private fun updateStatus() {
        val currentIp = client?.commaIp ?: NavStateManager.commaIp
        hud.updateStatus(
            commaIp = currentIp,
            commaFound = client?.commaFound ?: NavStateManager.commaFound,
            isGalaxyCloud = client?.isGalaxyCloud ?: NavStateManager.isGalaxyCloud,
            isScanning = client?.isScanningNow() ?: false
        )
        hud.updateDeviceState(NavStateManager.deviceState)
        hud.updateVoltage(NavStateManager.latestPower)
        hud.updateGpu(GpuStatus.parse(NavStateManager.lastSystemMonitor))
        hud.setUpdateReady(NavStateManager.softwareUpdateReady)
        hud.setUpdatePending(NavStateManager.softwareUpdatePending)
        NavStateManager.softwareUpdateCompletionNote?.let { note ->
            NavStateManager.softwareUpdateCompletionNote = null
            Toast.makeText(this, note, Toast.LENGTH_LONG).show()
        }
        maybeShowLastDriveCard()
        checkAndApplyMapStyle()
    }

    private fun updateUi(pkt: TelemetryPacket) {
        latestPacket = pkt
        NavStateManager.updateTelemetry(
            pkt = pkt,
            ip = client?.commaIp,
            found = client?.commaFound ?: false,
            isCloud = client?.isGalaxyCloud ?: false
        )
        updateStatus()
        hud.updateSpeed(pkt.speedMs, pkt.speedLimit.limitMs)
        hud.setSpeedLimitSource(pkt.speedLimit.source, pkt.speedLimit.limitMs)
        hud.updateVoltage(pkt.power ?: NavStateManager.latestPower)
        hud.updateGpu(GpuStatus.parse(NavStateManager.lastSystemMonitor))
        turnBanner.update(pkt.nav, activeDestination)
        updateTripInfo(activeDestination, pkt)
        if (!pkt.mapboxToken.isNullOrBlank()) {
            try {
                if (MapLibre.getApiKey() != pkt.mapboxToken) {
                    MapLibre.setApiKey(pkt.mapboxToken)
                }
            } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            if (!isRealMapStyleLoaded) {
                runOnUiThread {
                    applyCurrentTheme()
                }
            }
        }
        if (SimulationEngine.isSimulating) {
            pkt.gps?.let {
                currentGps = it
                if (!isRoutesOverviewActive) updateMapPosition(it, animate = true)
            }
        } else {
            pkt.gps?.let {
                currentGps = it
                if (!isRoutesOverviewActive) updateMapPosition(it, animate = true)
            }
        }
        val pktDest = pkt.destination
        if (pktDest != null && isNavClearedByUser && lastClearedDestination != null && lastClearedDestination?.isSameAs(pktDest) != true) {
            // New destination on Comma; reset user-cancelled lock
            isNavClearedByUser = false
            NavStateManager.setNavClearedByUser(false)
            lastClearedDestination = null
        }

        if (pktDest != null && !isNavClearedByUser && (activeDestination?.isSameAs(pktDest) != true)) {
            activeDestination = pktDest
            NavStateManager.activeDestination = pktDest
            // A destination the app accepts supersedes any end state.
            NavStateManager.noteNewDestination()
            if (::arrivalCard.isInitialized) arrivalCard.hide()
            if (::driveSummaryCard.isInitialized) driveSummaryCard.hide()
            if (::lastDriveCard.isInitialized) lastDriveCard.hide()
            topBar.setDestination(pktDest); topBar.setVisible(false)
            hud.setNavActive(true)
            updateDestinationMarker(pktDest)
            updateTripInfo(pktDest, pkt)
            // The comma's route for this destination arrives via the SG
            // snapshot into NavStateManager.activeRoutes (no phone fetch).
        } else if (pktDest == null && activeDestination != null && !NavStateManager.isDestinationPendingGrace()) {
            // Navigation has ended on Comma (destination reached, canceled, or cleared)
            clearNavigation(notifySharedState = true, sendClearToComma = false)
        }
    }

    private fun updateTripInfo(dest: NavDestination?, pkt: TelemetryPacket?) {
        if (dest == null || isNavClearedByUser) {
            tripInfoBar.hide()
            return
        }
        // ETA/distance math is layer 2 (GuidanceMath) — the same
        // computation the AA template runs (audit finding 11: this
        // screen used to run its own fallback chain and could show
        // a different ETA than the car for the same trip, and it
        // never used the comma's own remaining totals). Precedence:
        // comma remaining* > live route progress > route totals >
        // straight-line distance at the REAL current speed; null
        // when nothing real backs a number — hide, don't fabricate.
        val chosenRoute = NavStateManager.selectedRoute ?: activeRoutes.firstOrNull()
        val gps = currentGps ?: pkt?.gps ?: NavStateManager.currentGps
        val progress = if (chosenRoute != null && gps != null && chosenRoute.points.isNotEmpty()) {
            RouteProgress.compute(chosenRoute.points, gps.latitude, gps.longitude)
        } else null
        val g = GuidanceMath.compute(
            GuidanceMath.Input.fromState(
                effNav = pkt?.nav,
                nav = pkt?.nav,
                route = chosenRoute,
                dest = dest,
                gps = gps,
                rawSpeedMs = pkt?.speedMs ?: NavStateManager.speedMs,
                progress = progress
            )
        )
        val remainingDistMiles = g.totalDistanceMiles
            ?: (g.totalDistanceM?.let { it * Units.METERS_TO_MILES } ?: 0.0)
        val remainingMin: Int? = g.totalEtaSeconds?.let { maxOf(1, (it / 60).toInt()) }
        val nothingProvided = (chosenRoute?.durationMin ?: 0) <= 0 && remainingDistMiles <= 0.0
        if (remainingMin != null && remainingDistMiles > 0.0 && !nothingProvided) {
            tripInfoBar.update(remainingMin, remainingDistMiles)
        } else {
            // No real duration and no real speed (or a route with no
            // provided duration/distance at all): hide, don't fabricate.
            tripInfoBar.hide()
        }
    }

    private fun toggleSimulation() {
        val willSimulate = !SimulationEngine.isSimulating
        if (willSimulate) {
            val routePoints = activeRoutes.firstOrNull { it.id == selectedRouteId }?.points
            val dest = activeDestination ?: SimulationEngine.DEFAULT_DESTINATION
            SimulationEngine.start(routePoints, dest)
            if (activeDestination == null) {
                activeDestination = dest
                topBar.setDestination(dest)
                topBar.setVisible(false)
                hud.setNavActive(true)
                updateDestinationMarker(dest)
            }
            recenterToVehicle()
            Toast.makeText(this, "Simulation started", Toast.LENGTH_SHORT).show()
        } else {
            SimulationEngine.stop()
            clearNavigation(notifySharedState = true, sendClearToComma = false)
            Toast.makeText(this, "Simulation stopped", Toast.LENGTH_SHORT).show()
        }
        updateStatus()
    }

    private fun toggleNorthUp() {
        val map = mapLibreMap ?: return
        isNorthUp = !isNorthUp
        val targetBearing = if (isNorthUp) 0.0 else (currentGps?.bearing ?: 0.0)
        val targetTilt = MapDisplayOptions.phoneTilt(isNorthUp)
        val targetPos = CameraPosition.Builder()
            .target(map.cameraPosition.target).zoom(map.cameraPosition.zoom)
            .bearing(targetBearing).tilt(targetTilt).build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(targetPos), 500)
        hud.updateBearing(targetBearing)
    }

    private fun recenterToVehicle() {
        isUserTracking = true; isRoutesOverviewActive = false; currentZoom = 16.0
        hud.setAltRouteOverviewActive(false); hud.hideRecenter(); hud.hideRoutes()
        turnBanner.setSuppressed(false)
        if (activeDestination != null) turnBanner.show()
        currentGps?.let { updateMapPosition(it, animate = true) }
    }

    private fun toggleRoutesOverview() {
        val dest = activeDestination ?: run {
            Toast.makeText(this, "Set a destination first", Toast.LENGTH_SHORT).show(); return
        }
        val map = mapLibreMap ?: return
        isRoutesOverviewActive = !isRoutesOverviewActive
        hud.setAltRouteOverviewActive(isRoutesOverviewActive)
        if (isRoutesOverviewActive) {
            turnBanner.setSuppressed(true)
            hud.showRecenter()
            val start = currentGps ?: GpsPosition(33.4484, -112.0740)
            val builder = LatLngBounds.Builder()
                .include(LatLng(start.latitude, start.longitude))
                .include(LatLng(dest.latitude, dest.longitude))
            activeRoutes.forEach { r -> r.points.forEach { builder.include(it) } }
            val padH = (48 * resources.displayMetrics.density).roundToInt()
            val padV = (110 * resources.displayMetrics.density).roundToInt()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), padH, padV, padH, padV), 700)
            if (activeRoutes.isNotEmpty()) hud.showRoutes(activeRoutes, selectedRouteId) { selectRoute(it, keepOverviewIfActive = true) }
            // No route picker popup (Param, 2026-11-21): the overview zooms
            // out to the entire route and the user selects by tapping a
            // route chip or an alternative-route marker on the map.
        } else { recenterToVehicle() }
    }

    private var latestSearchQuery: String = ""

    private fun searchDestinations(query: String) {
        val prevQuery = latestSearchQuery
        latestSearchQuery = query
        // Destination entry: while the user is typing a destination, feed
        // phone GPS to the comma so its route engine has a fresh starting
        // position (start() is a no-op if a session is already running).
        // Clearing the query ends the entry, so stop feeding.
        if (query.isNotBlank()) {
            // A fresh destination entry starts a fresh comma search session
            // (selections made under a stale searchId are rejected).
            if (prevQuery.isBlank()) NavigationManager.beginCommaSearchSession()
            val feederEndpoint = client?.commaIp ?: NavStateManager.commaIp
            if (!feederEndpoint.isNullOrBlank()) PhoneGpsFeeder.start(feederEndpoint)
        } else {
            // Entry abandoned: end the entry feed (a navigation session
            // for a trip already under way keeps going) and tell the
            // comma to drop the search session.
            PhoneGpsFeeder.stopEntrySession()
            if (NavStateManager.isSgGalaxy) {
                val target = client?.getEffectiveNavigationEndpoint()
                if (!target.isNullOrBlank()) {
                    Thread {
                        navManager.cancelCommaSearchSg(
                            SgGalaxyClient.baseUrlFor(target)
                        )
                    }.start()
                }
            }
        }
        val starredMatches = Bookmarks.getStarred(this).filter {
            it.name.contains(query, ignoreCase = true) || it.placeName.contains(query, ignoreCase = true)
        }

        // Result shaping (Param, 2026-11-20): dedupe without
        // collapsing coordinate-less suggestions into one row, and
        // sort by distance from the phone's own location when a fix
        // exists (see SearchOrdering).
        fun ordered(list: List<NavDestination>): List<NavDestination> {
            val deduped = SearchOrdering.dedupe(list)
            val gps = currentGps ?: return deduped
            return SearchOrdering.sortByDistance(
                deduped, gps.latitude, gps.longitude
            )
        }

        // 1. Cache-first: zero network on cache hit. With phone search OFF,
        // only comma-sourced cached results may be served; a hit containing
        // anything else is treated as a miss and falls to the network branch.
        val cached = SearchCache.lookup(this, query)?.takeIf { results ->
            NavStateManager.usePhoneSearch ||
                (results.isNotEmpty() && results.all { it.source == "comma" })
        }
        if (cached != null) {
            // Cache hit is final for this query — no search follows,
            // so no spinner.
            topBar.setSearchLoading(false)
            topBar.showResults(ordered(starredMatches + cached))
            return
        }

        if (starredMatches.isNotEmpty()) {
            topBar.showResults(starredMatches)
        }

        // Connectivity matrix (Addendum 143): scenario 4 (both
        // sides offline) disables network search outright — keep
        // the local rows and say why. Scenario 2 forces the comma
        // branch inside the search thread below.
        val scenario = ConnectivityMatrix.current()
        if (!ConnectivityMatrix.searchAllowed(scenario)) {
            topBar.setSearchLoading(false)
            topBar.showResults(ordered(starredMatches))
            Toast.makeText(this, CarScreenNotice.NO_INTERNET_CONNECTIVITY, Toast.LENGTH_LONG).show()
            return
        }

        // 2. Network only on explicit submission cache miss. The
        // spinner stays up until these fresh results land, so the
        // interim rows above are not mistaken for the final answer
        // (Param, 2026-11-21).
        topBar.setSearchLoading(true)
        Thread {
            val token = client?.mapboxToken ?: NavStateManager.mapboxToken
            val start = currentGps ?: GpsPosition(33.4484, -112.0740)
            var source = "photon"
            val places: List<NavDestination> = if (
                ConnectivityMatrix.useCommaSearch(scenario, NavStateManager.usePhoneSearch)
            ) {
                // Phone search OFF: skip Mapbox and Photon entirely and use
                // only the comma's own search. No comma connected -> empty
                // results (never fall back to phone sources).
                source = "comma"
                val commaEndpoint = client?.commaIp ?: NavStateManager.commaIp
                if (!commaEndpoint.isNullOrBlank()) {
                    navManager.searchPlacesCommaSg(query, SgGalaxyClient.baseUrlFor(commaEndpoint))
                } else {
                    emptyList()
                }
            } else {
                // Google first when the phone Google key may be used
                // (Param, 2026-11-18: "use Google api for search in
                // app"); Mapbox, then Photon, exactly as before.
                val googleKey = PhoneGoogle.effectiveGoogleKey()
                val googleResults = if (googleKey != null) {
                    navManager.searchPlacesGoogle(
                        query, start.latitude, start.longitude, googleKey
                    )
                } else {
                    emptyList()
                }
                if (googleResults.isNotEmpty()) {
                    source = "google"
                    googleResults
                } else if (!token.isNullOrBlank()) {
                    val r = navManager.searchPlaces(
                        query, start.latitude, start.longitude, token
                    )
                    if (r.isNotEmpty()) {
                        source = "mapbox"
                        r
                    } else {
                        navManager.searchPlacesPhoton(
                            query, start.latitude, start.longitude
                        )
                    }
                } else {
                    navManager.searchPlacesPhoton(query, start.latitude, start.longitude)
                }
            }

            if (places.isNotEmpty()) {
                SearchCache.put(this, query, places, source)
                for (d in places) {
                    if (d.hasCoordinates) {
                        SearchCache.putResolved(this, d)
                        DestinationHistory.record(this, d)
                    }
                }
            }

            runOnUiThread {
                if (query == latestSearchQuery) {
                    // Surface the comma's own search error (e.g. it has no
                    // Mapbox token configured) instead of silent emptiness.
                    if (source == "comma" && places.isEmpty()) {
                        NavigationManager.lastCommaSearchError?.let { err ->
                            Toast.makeText(this@MainActivity, "Comma search: $err", Toast.LENGTH_LONG).show()
                        }
                    }
                    // Fresh results are in — the spinner's job is done.
                    // (A superseded query leaves the spinner to the
                    // newer search that replaced it.)
                    topBar.setSearchLoading(false)
                    topBar.showResults(ordered(starredMatches + places))
                }
            }
        }.start()
    }

    private fun handleDestinationSelected(dest: NavDestination) {
        // Queue lock (Param, 2026-11-21): while a destination is
        // queued, no NEW destination is accepted — the queued one
        // must be sent or cancelled first. The name check here stops
        // the tap early (before any resolve work); the resolved
        // destination is checked again in applyDestination /
        // applyDestinationViaCommaPlace.
        val queuedNow = NavStateManager.queuedDestination
            ?: DestinationQueue.loadEntry(this)?.displayDestination()
        if (queuedNow != null && !queuedNow.name.equals(dest.name, ignoreCase = true)) {
            Toast.makeText(
                this,
                "A destination is already queued — it must be sent or cancelled before choosing another",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val resolved = SearchCache.applyResolved(dest)
        if (resolved.hasCoordinates) {
            applyDestination(resolved)
            return
        }
        val cached = SearchCache.findResolved(this, dest)
        if (cached != null && cached.hasCoordinates) {
            applyDestination(cached)
            return
        }
        // SG comma suggestion (temporary, no coordinates of its own): the
        // comma retrieves the place itself via selectPlace. The
        // source="comma" term is load-bearing — Mapbox SearchBox
        // suggestions populate the same mapboxId/sessionToken fields with
        // Mapbox ids and must keep flowing to retrievePlace below.
        if (NavStateManager.isSgGalaxy && dest.source == "comma" &&
            dest.mapboxId != null && dest.sessionToken != null
        ) {
            applyDestinationViaCommaPlace(dest)
            return
        }
        // A4: if this place was resolved before, the cached resolution is
        // used directly — no Mapbox retrievePlace call.
        val mapboxId = dest.mapboxId
        if (mapboxId != null) {
            val cached = SearchCache.findResolved(this, mapboxId)
            if (cached != null && cached.hasCoordinates) {
                applyDestination(cached)
                return
            }
        }
        Thread {
            val token = client?.mapboxToken ?: NavStateManager.mapboxToken
            val googleKey = PhoneGoogle.effectiveGoogleKey()
            val retrieved = if (dest.googlePlaceId != null && googleKey != null) {
                navManager.retrievePlaceGoogle(dest, googleKey)
            } else if (!token.isNullOrBlank()) {
                navManager.retrievePlace(dest, token)
            } else {
                null
            }
            if (retrieved != null && retrieved.hasCoordinates) {
                // Persist the resolution so the next selection of this place
                // is served locally (A4).
                SearchCache.putResolved(this, retrieved)
                runOnUiThread { applyDestination(retrieved) }
                return@Thread
            }
            // The phone cannot resolve this place: no Mapbox token is
            // set (a cached Mapbox suggestion lands here — the old code
            // stopped with "Waiting for Mapbox token from Comma
            // params..." and set nothing), or the retrieve failed.
            // Resolve through the comma instead: its own search +
            // selectPlace needs no phone token, and the destination
            // still counts only when the comma confirms it.
            val commaEndpoint = client?.commaIp ?: NavStateManager.commaIp
            if (NavStateManager.isSgGalaxy && !commaEndpoint.isNullOrBlank()) {
                val matches = navManager.searchPlacesCommaSg(
                    dest.name, SgGalaxyClient.baseUrlFor(commaEndpoint)
                )
                val match = CommaPlaces.bestMatch(matches, dest.name)
                if (match != null) {
                    runOnUiThread { applyDestinationViaCommaPlace(match) }
                    return@Thread
                }
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Could not resolve location for ${dest.name} — the comma found no match, and the phone could not resolve it either",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    /**
     * Sends a temporary comma search suggestion via selectPlace: the comma
     * retrieves the place itself and sets the real destination (with
     * coordinates) in its own state. Mirrors [applyDestination]'s state +
     * confirmation discipline, but there are no local coordinates to mark
     * or trip-compute from until the comma confirms.
     */
    private fun applyDestinationViaCommaPlace(dest: NavDestination) {
        if (queuedDestinationBlocks(dest)) return
        isNavClearedByUser = false
        NavStateManager.setNavClearedByUser(false)  // re-enable dest syncing from Comma telemetry
        DestinationHistory.record(this, dest)
        activeDestination = dest; topBar.setDestination(dest); topBar.setVisible(false)
        NavStateManager.activeDestination = dest
        // A destination the app accepts supersedes any end state.
        NavStateManager.noteNewDestination()
        NavStateManager.activeTurnCue = "Head toward ${dest.name}"
        NavStateManager.notifyChanged()
        turnBanner.update(NavManeuver(), dest)
        hud.setNavActive(true)
        NavigationManager.startNavigationRefresh { client?.getEffectiveNavigationEndpoint() }
        val target = client?.getEffectiveNavigationEndpoint() ?: "192.168.68.9"
        Thread {
            val ok = navManager.selectCommaPlaceSg(
                SgGalaxyClient.baseUrlFor(target), dest
            )
            // Destination entry is over once the send resolves. On
            // success the feed continues as a navigation session until
            // navigation ends (Param, 2026-11-03); on failure it stops.
            if (ok) PhoneGpsFeeder.startNavigationSession(target) else PhoneGpsFeeder.stop()
            runOnUiThread {
                if (ok) clearQueueIfMatches(dest)
                if (!ok) {
                    // Addendum 122: queued = keep the local navigation
                    // (map-app mode), no rollback, no toast — the queued
                    // chip / banner row is the only popup.
                    val queued = queueDestinationOnFailure(dest) || queueCommaPlaceOnFailure(dest)
                    if (!queued) {
                        rollbackUnconfirmedDestination(dest)
                        Toast.makeText(
                            this@MainActivity,
                            "Destination NOT accepted by the comma — ${NavigationManager.lastSendError ?: "no confirmation from the device"}",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        updateTripInfo(dest, latestPacket)
                    }
                } else {
                    updateTripInfo(dest, latestPacket)
                }
            }
        }.start()
    }

    private fun rollbackUnconfirmedDestination(dest: NavDestination) {
        if (NavStateManager.isDestinationPendingGrace()) return
        if (activeDestination?.isSameAs(dest) != true) return
        clearNavigation(notifySharedState = true, sendClearToComma = false)
    }

    /**
     * Offline queue (Feature 10): when a USER destination send fails
     * because the comma is offline, hold the destination in the queue
     * instead of dropping it — it flushes automatically when the comma
     * reconnects. Returns true when queued (the caller then keeps the
     * local navigation and shows no toast — Addendum 122). Coordinate-less
     * destinations are not queued here — comma suggestions go through
     * [queueCommaPlaceOnFailure] instead.
     *
     * Addendum 122: queueing now happens on ANY send failure, not only
     * while the comma is unreachable — a comma that answers but does
     * not accept the destination (typically no Internet on the comma)
     * leaves phone and comma out of sync, and the entry still flushes
     * automatically once the comma accepts it. [NavStateManager
     * .queuedOutOfSync] records which case this was, from whether the
     * comma was found at queue time.
     */
    private fun queueDestinationOnFailure(dest: NavDestination): Boolean {
        if (!dest.hasCoordinates) return false
        DestinationQueue.save(this, dest)
        NavStateManager.queuedDestination = dest
        NavStateManager.queuedOutOfSync = NavStateManager.commaFound
        NavStateManager.notifyChanged()
        if (NavStateManager.queuedOutOfSync) refreshQueuedSyncNetwork()
        return true
    }

    /**
     * Queue lock (Param, 2026-11-21): true — with the reason already
     * toasted — when [dest] is a NEW destination while another one is
     * still queued. The queued destination must be sent (the flush
     * retries every 15 s while the comma is connected) or cancelled
     * before anything else is accepted.
     */
    private fun queuedDestinationBlocks(dest: NavDestination): Boolean {
        val queued = NavStateManager.queuedDestination
            ?: DestinationQueue.loadEntry(this)?.displayDestination()
            ?: return false
        if (!DestinationQueue.blocksNewDestination(queued, dest)) return false
        Toast.makeText(
            this,
            "A destination is already queued — it must be sent or cancelled before choosing another",
            Toast.LENGTH_LONG
        ).show()
        return true
    }

    /** Clears the queue when a live send of the queued destination
     * itself succeeds — it is no longer waiting. */
    private fun clearQueueIfMatches(dest: NavDestination) {
        val queued = NavStateManager.queuedDestination ?: return
        if (!queued.isSameAs(dest)) return
        DestinationQueue.clear(this)
        NavStateManager.queuedDestination = null
        NavStateManager.queuedOutOfSync = false
        NavStateManager.notifyChanged()
    }

    /**
     * Fetches the comma's network snapshot in the background so the
     * out-of-sync message can name the Wi-Fi the comma is on (Param,
     * 2026-11-21). Best-effort: a failed fetch leaves the generic
     * message, and the flush's failure path refreshes it again.
     */
    private fun refreshQueuedSyncNetwork() {
        val target = client?.getEffectiveNavigationEndpoint() ?: return
        Thread {
            try {
                navManager.fetchNetworksSg(SgGalaxyClient.baseUrlFor(target))
                NavStateManager.notifyChanged()
            } catch (e: Exception) {
                Log.d("MainActivity", "Networks refresh for the sync message failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Offline queue for comma suggestions (Fix B): a temporary comma
     * suggestion has no coordinates to flush with, so the queue stores
     * the producing search query plus the suggestion's stable place id;
     * on reconnect the flush re-runs that search and selects the place
     * again through the normal selectPlace flow. Queued whenever the
     * send failed and the place can be re-found (an id or a query must
     * exist) — see [queueDestinationOnFailure] for the Addendum 122
     * widening to connected-but-not-accepted failures.
     */
    private fun queueCommaPlaceOnFailure(dest: NavDestination): Boolean {
        val id = dest.mapboxId?.takeIf { it.isNotBlank() }
        val query = latestSearchQuery.trim().ifEmpty { null }
        if (id == null && query == null) return false
        DestinationQueue.saveCommaPlace(this, dest.name, dest.placeName, id, query)
        NavStateManager.queuedDestination = dest
        NavStateManager.queuedOutOfSync = NavStateManager.commaFound
        NavStateManager.notifyChanged()
        if (NavStateManager.queuedOutOfSync) refreshQueuedSyncNetwork()
        return true
    }

    /** Cancels the offline queue from the queued chip (Feature 10). */
    private fun cancelQueuedDestination() {
        val queued = NavStateManager.queuedDestination
        DestinationQueue.clear(this)
        NavStateManager.queuedDestination = null
        NavStateManager.queuedOutOfSync = false
        NavStateManager.notifyChanged()
        // Addendum 122: in offline map-app mode the queued destination
        // IS the local navigation, so cancelling the queue drops it
        // too. After a flush the queue is already empty, so a
        // comma-confirmed destination is never cleared from here.
        if (queued != null && activeDestination?.isSameAs(queued) == true) {
            clearNavigation(notifySharedState = true, sendClearToComma = false)
        }
        Toast.makeText(this, "Queued destination cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun applyDestination(
        dest: NavDestination,
        notifySharedState: Boolean = true,
        sendToDevice: Boolean = true
    ) {
        // Queue lock (Param, 2026-11-21) — user sends only; the
        // telemetry-sync call (notifySharedState = false,
        // sendToDevice = false) mirrors the comma's own state and is
        // not a new destination acceptance.
        if (sendToDevice && notifySharedState && queuedDestinationBlocks(dest)) return
        isNavClearedByUser = false
        NavStateManager.setNavClearedByUser(false)  // re-enable dest syncing from Comma telemetry
        if (::arrivalCard.isInitialized) arrivalCard.hide()
        if (::driveSummaryCard.isInitialized) driveSummaryCard.hide()
        if (::lastDriveCard.isInitialized) lastDriveCard.hide()
        DestinationHistory.record(this, dest)
        activeDestination = dest; topBar.setDestination(dest); topBar.setVisible(false)
        if (notifySharedState) {
            NavStateManager.activeDestination = dest
            // A destination the app accepts supersedes any end state.
            NavStateManager.noteNewDestination()
            NavStateManager.activeTurnCue = "Head toward ${dest.name}"
            NavStateManager.notifyChanged()
        }
        turnBanner.update(NavManeuver(), dest)
        hud.setNavActive(true); updateDestinationMarker(dest)
        updateTripInfo(dest, latestPacket)
        if (!sendToDevice) return
        NavigationManager.startNavigationRefresh { client?.getEffectiveNavigationEndpoint() }
        val target = client?.getEffectiveNavigationEndpoint() ?: "192.168.68.9"
        val cookie = client?.getGalaxyCookie()
        Thread {
            val ok = if (NavStateManager.isSgGalaxy) {
                navManager.sendToCommaSg(
                    SgGalaxyClient.baseUrlFor(target), dest
                )
            } else {
                navManager.sendToComma(target, dest, cookie)
            }
            // Destination entry is over once the send resolves. On
            // success the feed continues as a navigation session until
            // navigation ends (Param, 2026-11-03); on failure it stops.
            // Surface a recorded feeder error (e.g. the comma's 403 on a
            // cloud endpoint) once.
            val feederError = PhoneGpsFeeder.lastError
            if (ok) PhoneGpsFeeder.startNavigationSession(target) else PhoneGpsFeeder.stop()
            runOnUiThread {
                if (ok) clearQueueIfMatches(dest)
                if (feederError != null) {
                    Toast.makeText(this@MainActivity, feederError, Toast.LENGTH_LONG).show()
                }
                if (!ok) {
                    // Offline queue (Feature 10, reworked in Addendum
                    // 122): when the comma is simply unreachable and the
                    // destination queued, the phone keeps navigating
                    // locally — map-app mode. No rollback, no toast: the
                    // queued chip / banner row is the only popup, and
                    // the queue flushes itself when the comma connects.
                    val queued = notifySharedState && queueDestinationOnFailure(dest)
                    if (!queued) {
                        rollbackUnconfirmedDestination(dest)
                        Toast.makeText(
                            this,
                            "Destination NOT accepted by the comma — ${NavigationManager.lastSendError ?: "no confirmation from the device"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    /**
     * User-initiated End Route (Feature 3): the same device-confirmation
     * discipline as sending a destination. The comma's clear action runs
     * FIRST; local state and UI are cleared only when the comma confirms.
     * On failure the destination stays on screen and the error is surfaced.
     * Internal clears (rollback, nav-ended mirroring, simulation) keep using
     * [clearNavigation] directly and are unchanged.
     */
    private fun endRouteByUser(onEnded: (() -> Unit)? = null) {
        // A stuck route has no local destination left to check: the retry
        // still has to reach the comma, so it bypasses the nothing-to-end
        // shortcut below.
        val retryingStuck = NavStateManager.endRouteState == EndRouteConfidence.State.STUCK
        if ((activeDestination == null && !retryingStuck) || SimulationEngine.isSimulating) {
            // Nothing on the comma to confirm (or a simulated trip): the
            // plain local clear is the whole operation.
            clearNavigation()
            onEnded?.invoke()
            return
        }
        // End Route confidence (backlog #3): the nav header shows
        // "Ending route…" from the tap until the comma answers.
        NavStateManager.noteEndRouteStarted(activeDestination?.name ?: NavStateManager.endRouteDestName)
        val target = client?.getEffectiveNavigationEndpoint() ?: "192.168.68.9"
        val cookie = client?.getGalaxyCookie()
        val sg = NavStateManager.isSgGalaxy
        Thread {
            var sgOk = false
            var legacyOk = false
            if (sg) {
                sgOk = navManager.clearCommaNavigationSg(
                    SgGalaxyClient.baseUrlFor(target)
                )
                if (!sgOk) {
                    legacyOk = navManager.clearCommaNavigation(target, cookie)
                }
            } else {
                legacyOk = navManager.clearCommaNavigation(target, cookie)
            }
            val confirmed = EndRouteFlow.confirmed(sg, sgOk, legacyOk)
            runOnUiThread {
                if (confirmed) {
                    // The comma confirmed the clear: the header shows
                    // "Route ended ✓" (a later no-destination snapshot
                    // re-confirms it; a snapshot still carrying the
                    // destination flips it to the stuck warning).
                    NavStateManager.noteEndRouteActionConfirmed()
                    clearNavigation(notifySharedState = true, sendClearToComma = false)
                    onEnded?.invoke()
                } else {
                    // The clear failed: the route is still live, so the
                    // header goes back to normal navigation.
                    NavStateManager.noteEndRouteFailed()
                    Toast.makeText(
                        this@MainActivity,
                        EndRouteFlow.failureMessage(NavigationManager.lastSendError),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun clearNavigation(notifySharedState: Boolean = true, sendClearToComma: Boolean = true) {
        isNavClearedByUser = true
        NavStateManager.setNavClearedByUser(true)   // block NSM from re-applying stale Comma dest
        lastClearedDestination = activeDestination
        activeDestination = null; activeRoutes = emptyList(); isRoutesOverviewActive = false; isUserTracking = true
        NavigationManager.resetLastSent()
        NavigationManager.stopNavigationRefresh()
        PhoneGpsFeeder.stop()
        if (notifySharedState) {
            NavStateManager.clear()
        }
        val map = mapLibreMap
        destinationMarker?.let { map?.removeMarker(it) }; destinationMarker = null
        altRouteMarkers.forEach { map?.removeMarker(it) }; altRouteMarkers.clear()
        altRouteMarkersMap.clear()
        routePolylines.forEach { map?.removePolyline(it) }; routePolylines.clear()
        lastDrawnRoutes = null; lastDrawnSelectedRouteId = -1
        // A route ending must not wipe a search the user is actively
        // typing for the next destination (Param, 2026-11-20 field
        // report: route ended while searching — the typed text
        // vanished and the search looked stuck). Preserve an
        // in-progress search; otherwise reset the search state
        // fully, including the query gate, so a late result from
        // the ended trip's search can't reopen the results card.
        if (::topBar.isInitialized && topBar.isSearchInProgress()) {
            topBar.setVisible(true)
        } else {
            latestSearchQuery = ""
            topBar.clearSearch(); topBar.setVisible(true)
        }
        turnBanner.hide(); tripInfoBar.hide()
        hud.hideRoutes(); hud.setNavActive(false); hud.setAltRouteOverviewActive(false); hud.hideRecenter()
        if (sendClearToComma) {
            val target = client?.getEffectiveNavigationEndpoint() ?: "192.168.68.9"
            val cookie = client?.getGalaxyCookie()
            Thread {
                if (NavStateManager.isSgGalaxy) {
                    val sgOk = navManager.clearCommaNavigationSg(
                        SgGalaxyClient.baseUrlFor(target)
                    )
                    if (!sgOk) {
                        navManager.clearCommaNavigation(target, cookie)
                    }
                } else {
                    navManager.clearCommaNavigation(target, cookie)
                }
            }.start()
        }
    }

    private fun selectRoute(chosen: RouteOption, keepOverviewIfActive: Boolean = false, onResult: ((Boolean) -> Unit)? = null) {
        selectedRouteId = chosen.id
        NavStateManager.selectedRouteId = chosen.id
        NavStateManager.notifyChanged()
        drawRoutesOnMap(activeRoutes, chosen.id)
        if (chosen.durationMin <= 0 && chosen.distanceMiles <= 0.0) {
            tripInfoBar.hide()
        } else {
            tripInfoBar.update(chosen.durationMin, chosen.distanceMiles)
        }
        if (isRoutesOverviewActive && keepOverviewIfActive) {
            hud.showRoutes(activeRoutes, chosen.id) { selectRoute(it, keepOverviewIfActive = true) }
        } else {
            isRoutesOverviewActive = false
            hud.setAltRouteOverviewActive(false)
            hud.hideRoutes()
            recenterToVehicle()
        }
        val dest = activeDestination ?: return
        val ip = client?.commaIp ?: "192.168.68.9"
        Thread {
            val ok = if (NavStateManager.isSgGalaxy) {
                navManager.selectRouteSg(
                    SgGalaxyClient.baseUrlFor(ip), chosen.id
                )
            } else {
                // B3: pass the Galaxy cookie, same as applyDestination does —
                // the legacy route re-send was silently unauthenticated.
                val cookie = client?.getGalaxyCookie()
                navManager.sendToComma(ip, dest.copy(routeId = "route_${chosen.id}"), cookie)
            }
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "Route sent to car: ${chosen.summary} (${chosen.durationMin}m)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to send route to comma — ${NavigationManager.lastSendError ?: "not connected"}", Toast.LENGTH_LONG).show()
                }
                onResult?.invoke(ok)
            }
        }.start()
    }

    private fun drawRoutesOnMap(routes: List<RouteOption>, selectedId: Int) {
        val map = mapLibreMap ?: return
        if (routes.isEmpty()) {
            lastDrawnRoutes = emptyList()
            lastDrawnSelectedRouteId = -1
            routePolylines.forEach { map.removePolyline(it) }; routePolylines.clear()
            altRouteMarkers.forEach { map.removeMarker(it) }; altRouteMarkers.clear()
            altRouteMarkersMap.clear()
            syncRouteFeatureMarkers()
            return
        }
        // C5: skip the remove/re-add rebuild when the routes and selection are
        // exactly what was last drawn. The last-drawn state is invalidated
        // wherever the polylines are removed by other paths (clearNavigation,
        // style reloads).
        if (routes === lastDrawnRoutes && selectedId == lastDrawnSelectedRouteId) return
        lastDrawnRoutes = routes
        lastDrawnSelectedRouteId = selectedId
        routePolylines.forEach { map.removePolyline(it) }; routePolylines.clear()
        altRouteMarkers.forEach { map.removeMarker(it) }; altRouteMarkers.clear()
        altRouteMarkersMap.clear()

        val sel = routes.firstOrNull { it.id == selectedId }

        // 1. Draw alternate routes first (underneath primary)
        routes.filter { it.id != selectedId }.forEach { alt ->
            routePolylines.add(map.addPolyline(PolylineOptions().addAll(alt.points).color(0xFF3C4043.toInt()).width(7f)))
            routePolylines.add(map.addPolyline(PolylineOptions().addAll(alt.points).color(0xFF80868B.toInt()).width(5f)))

            // Add ETA bubble at divergence point if selected route exists
            if (sel != null) {
                val divPoint = findDivergencePoint(alt, sel)
                if (divPoint != null) {
                    val diff = alt.durationMin - sel.durationMin
                    val label = when {
                        diff > 0 -> "+$diff min"
                        diff < 0 -> "$diff min"
                        else -> "${alt.durationMin} min"
                    }
                    val badgeBmp = createRouteBadgeBitmap(label, isFaster = diff < 0)
                    val icon = IconFactory.getInstance(this).fromBitmap(badgeBmp)
                    val marker = map.addMarker(
                        MarkerOptions()
                            .position(divPoint)
                            .icon(icon)
                            .title(alt.summary)
                            .snippet("${alt.durationMin} min · ${alt.distanceMiles} mi")
                    )
                    altRouteMarkers.add(marker)
                    altRouteMarkersMap[marker] = alt
                }
            }
        }

        // 2. Draw selected primary route on top (Google Maps Blue #4285F4 with #174EA6 casing)
        sel?.let {
            val pointsToDraw = ArrayList<LatLng>(sel.points.size + 2)
            val gps = currentGps
            if (gps != null && sel.points.isNotEmpty()) {
                val d = GuidanceMath.haversineM(gps.latitude, gps.longitude, sel.points.first().latitude, sel.points.first().longitude)
                if (d in 2.0..1500.0) {
                    pointsToDraw.add(LatLng(gps.latitude, gps.longitude))
                }
            }
            pointsToDraw.addAll(sel.points)
            val dest = activeDestination
            if (dest != null && pointsToDraw.isNotEmpty()) {
                val d = GuidanceMath.haversineM(dest.latitude, dest.longitude, pointsToDraw.last().latitude, pointsToDraw.last().longitude)
                if (d in 2.0..500.0) {
                    pointsToDraw.add(LatLng(dest.latitude, dest.longitude))
                }
            }

            if (sel.congestions.isEmpty() || sel.congestions.size != pointsToDraw.size - 1) {
                routePolylines.add(map.addPolyline(PolylineOptions().addAll(pointsToDraw).color(0xFF174EA6.toInt()).width(9f)))
                routePolylines.add(map.addPolyline(PolylineOptions().addAll(pointsToDraw).color(0xFF4285F4.toInt()).width(6.5f)))
            } else {
                routePolylines.add(map.addPolyline(PolylineOptions().addAll(pointsToDraw).color(0xFF174EA6.toInt()).width(9f)))
                var segStart = 0
                while (segStart < sel.congestions.size) {
                    val level = sel.congestions[segStart]
                    var segEnd = segStart
                    while (segEnd + 1 < sel.congestions.size && sel.congestions[segEnd + 1] == level) {
                        segEnd++
                    }
                    val subPoints = pointsToDraw.subList(segStart, segEnd + 2)
                    val color = when (level) {
                        "heavy" -> 0xFFE8710A.toInt()
                        "severe" -> 0xFFD93025.toInt()
                        "moderate" -> 0xFFF9AB00.toInt()
                        else -> 0xFF4285F4.toInt()
                    }
                    routePolylines.add(map.addPolyline(PolylineOptions().addAll(subPoints).color(color).width(6.5f)))
                    segStart = segEnd + 1
                }
            }
        }
        activeDestination?.let { updateDestinationMarker(it) }
        syncRouteFeatureMarkers()
    }

    /**
     * Route feature markers on the phone map (Param, 2026-11-10):
     * traffic signals (Mapbox data) and stop signs (OSM) along the
     * active route, from NavStateManager state. Rebuilt only when
     * the marker set or its toggles change (identity key), mirroring
     * the route-draw caching above. Toggle off for a kind draws none
     * of it — its state list is empty then anyway.
     */
    private fun syncRouteFeatureMarkers() {
        val map = mapLibreMap ?: return
        val signals = if (NavStateManager.usePhoneSignals) NavStateManager.routeSignals else emptyList()
        val stops = if (NavStateManager.usePhoneStops) NavStateManager.routeStops else emptyList()
        val key = "${System.identityHashCode(signals)}:${System.identityHashCode(stops)}:${NavStateManager.usePhoneSignals}:${NavStateManager.usePhoneStops}"
        if (key == lastDrawnFeatureKey) return
        lastDrawnFeatureKey = key
        routeFeatureMarkers.forEach { map.removeMarker(it) }
        routeFeatureMarkers.clear()
        if (signals.isEmpty() && stops.isEmpty()) return
        if (routeFeatureIconSignal == null) {
            routeFeatureIconSignal = try {
                IconFactory.getInstance(this).fromBitmap(TrafficSignalIcon(this).createBitmap())
            } catch (_: Exception) { null }
        }
        if (routeFeatureIconStop == null) {
            routeFeatureIconStop = try {
                IconFactory.getInstance(this).fromBitmap(StopSignIcon(this).createBitmap())
            } catch (_: Exception) { null }
        }
        for (m in signals) {
            val opt = MarkerOptions().position(m.position).title("Traffic signal")
            routeFeatureIconSignal?.let { opt.icon(it) }
            routeFeatureMarkers.add(map.addMarker(opt))
        }
        for (m in stops) {
            val opt = MarkerOptions().position(m.position).title("Stop sign")
            routeFeatureIconStop?.let { opt.icon(it) }
            routeFeatureMarkers.add(map.addMarker(opt))
        }
    }

    private fun handleMapClick(tapLatLng: LatLng): Boolean {
        val map = mapLibreMap ?: return false

        // A plain tap elsewhere dismisses a dropped pin (Addendum 115).
        if (droppedPinMarker != null) dismissDroppedPin()

        // 1. If in Map Pick Mode (configuring Home or Office)
        val pickMode = currentMapPickMode
        if (pickMode != null) {
            handleMapLocationPicked(tapLatLng, pickMode)
            return true
        }

        if (activeRoutes.size <= 1) return false

        val tapScreenPoint = safeToScreenLocation(map, tapLatLng) ?: return false
        val density = resources.displayMetrics.density
        val touchThresholdPx = 54f * density

        val altRoutes = activeRoutes.filter { it.id != selectedRouteId }
        if (altRoutes.isEmpty()) return false

        var closestAltRoute: RouteOption? = null
        var minAltDist = Float.MAX_VALUE

        for (route in altRoutes) {
            val dist = distanceToRoutePx(route, tapScreenPoint, map)
            if (dist < minAltDist) {
                minAltDist = dist
                closestAltRoute = route
            }
        }

        if (closestAltRoute != null && minAltDist <= touchThresholdPx) {
            val selRoute = activeRoutes.firstOrNull { it.id == selectedRouteId }
            val selDist = selRoute?.let { distanceToRoutePx(it, tapScreenPoint, map) } ?: Float.MAX_VALUE

            // Select alternate route if tap was closer to alternate route than selected route
            if (minAltDist < selDist - (8f * density)) {
                selectRoute(closestAltRoute, keepOverviewIfActive = isRoutesOverviewActive)
                return true
            }
        }
        return false
    }

    private fun safeToScreenLocation(map: MapLibreMap, latLng: LatLng): PointF? {
        return try {
            val pt = map.projection.toScreenLocation(latLng)
            if (pt.x.isNaN() || pt.y.isNaN()) null else pt
        } catch (_: Exception) {
            null
        }
    }

    private fun distanceToRoutePx(route: RouteOption, tapPoint: PointF, map: MapLibreMap): Float {
        val points = route.points
        if (points.isEmpty()) return Float.MAX_VALUE
        if (points.size == 1) {
            val p = safeToScreenLocation(map, points[0]) ?: return Float.MAX_VALUE
            val dx = tapPoint.x - p.x; val dy = tapPoint.y - p.y
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        var minDistSq = Float.MAX_VALUE
        var prevScreen: PointF? = null
        for (p in points) {
            val currScreen = safeToScreenLocation(map, p) ?: continue
            if (prevScreen != null) {
                val dSq = distanceToSegmentSquared(
                    tapPoint.x, tapPoint.y,
                    prevScreen.x, prevScreen.y,
                    currScreen.x, currScreen.y
                )
                if (dSq < minDistSq) minDistSq = dSq
            }
            prevScreen = currScreen
        }
        return if (minDistSq == Float.MAX_VALUE) Float.MAX_VALUE else kotlin.math.sqrt(minDistSq)
    }

    private fun distanceToSegmentSquared(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 <= 0.0001f) {
            val dpx = px - ax
            val dpy = py - ay
            return dpx * dpx + dpy * dpy
        }
        val t = ((px - ax) * dx + (py - ay) * dy) / l2
        val clampedT = t.coerceIn(0f, 1f)
        val projX = ax + clampedT * dx
        val projY = ay + clampedT * dy
        val diffX = px - projX
        val diffY = py - projY
        return diffX * diffX + diffY * diffY
    }

    private fun findDivergencePoint(altRoute: RouteOption, selRoute: RouteOption): LatLng? {
        if (altRoute.points.isEmpty()) return null
        if (selRoute.points.isEmpty()) return altRoute.points[altRoute.points.size / 2]

        var maxDistMeters = 0.0
        var bestPoint: LatLng = altRoute.points[altRoute.points.size / 2]

        val step = (altRoute.points.size / 30).coerceAtLeast(1)
        for (i in 0 until altRoute.points.size step step) {
            val p = altRoute.points[i]
            var minDistToSel = Double.MAX_VALUE
            val selStep = (selRoute.points.size / 30).coerceAtLeast(1)
            for (j in 0 until selRoute.points.size step selStep) {
                val sp = selRoute.points[j]
                val d = GuidanceMath.haversineM(p.latitude, p.longitude, sp.latitude, sp.longitude)
                if (d < minDistToSel) minDistToSel = d
            }
            if (minDistToSel > maxDistMeters) {
                maxDistMeters = minDistToSel
                bestPoint = p
            }
        }
        return bestPoint
    }


    private fun createRouteBadgeBitmap(text: String, isFaster: Boolean): Bitmap {
        val density = resources.displayMetrics.density
        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 12f * density
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textWidth = paintText.measureText(text)
        val padH = 10f * density
        val padV = 5f * density
        val width = (textWidth + padH * 2).toInt().coerceAtLeast((44 * density).toInt())
        val height = ((paintText.descent() - paintText.ascent()) + padV * 2).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val rect = RectF(2f, 2f, width - 2f, height - 2f)
        val cornerRadius = height / 2f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isFaster) 0xEE065F46.toInt() else 0xEE1E293B.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isFaster) 0xFF10B981.toInt() else 0xFF94A3B8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

        val textY = (height / 2f) - ((paintText.descent() + paintText.ascent()) / 2f)
        canvas.drawText(text, width / 2f, textY, paintText)

        return bitmap
    }

    /**
     * Long-press on the map (Param, 2026-11-20): drop a pin and
     * show the Add favorite card. Map Pick Mode (Home/Office setup)
     * owns its gestures, so the press is ignored there. A Photon
     * reverse lookup names the point in the background; if it lands
     * while the pin is still up, the card and the save dialog use
     * the real name/address instead of the coordinates.
     */
    private fun handleMapLongPress(latLng: LatLng): Boolean {
        if (currentMapPickMode != null) return false
        val lat = latLng.latitude
        val lng = latLng.longitude
        droppedPinDest = DroppedPin.destination(lat, lng, null)
        showDroppedPin(lat, lng)
        Thread {
            val reversed = navManager.reverseGeocodePhoton(lat, lng)
            if (reversed != null) {
                runOnUiThread {
                    val current = droppedPinDest
                    if (current != null &&
                        current.latitude == lat && current.longitude == lng
                    ) {
                        droppedPinDest =
                            DroppedPin.destination(lat, lng, reversed)
                        droppedPinLabel?.text = droppedPinText()
                    }
                }
            }
        }.start()
        return true
    }

    private fun droppedPinText(): String {
        val dest = droppedPinDest ?: return ""
        return if (dest.placeName.isEmpty() || dest.placeName == dest.name) {
            dest.name
        } else {
            "${dest.name}\n${dest.placeName}"
        }
    }

    private fun showDroppedPin(lat: Double, lng: Double) {
        val map = mapLibreMap ?: return
        if (destinationIcon == null) {
            destinationIcon = try {
                IconFactory.getInstance(this)
                    .fromBitmap(DestinationPin(this).createBitmap())
            } catch (_: Exception) { null }
        }
        val pos = LatLng(lat, lng)
        val existing = droppedPinMarker
        if (existing != null) {
            existing.position = pos
        } else {
            val opt = MarkerOptions().position(pos)
            destinationIcon?.let { opt.icon(it) }
            droppedPinMarker = map.addMarker(opt)
        }
        showDroppedPinCard()
    }

    private fun showDroppedPinCard() {
        val root = mapContainer?.parent as? FrameLayout ?: return
        var card = droppedPinCard
        if (card == null) {
            val density = resources.displayMetrics.density
            fun dp(v: Int) = (v * density).roundToInt()
            card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = HudOverlay.makeCardBg(
                    this@MainActivity, HudOverlay.COLOR_SURFACE_DARK,
                    16, HudOverlay.COLOR_BORDER_DARK, 1
                )
                elevation = 8f * density
                setPadding(dp(14), dp(10), dp(8), dp(10))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    // Addendum 133: ride above the dock stack when
                    // it is taller than the authored 110dp margin.
                    setMargins(
                        dp(16), 0, dp(16),
                        maxOf(dp(110), bottomDockFootprintPx + dp(8))
                    )
                }
            }
            val label = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (resources.displayMetrics.widthPixels * 0.5f)
                    .toInt()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            droppedPinLabel = label
            card.addView(label)
            val favBtn = TextView(this).apply {
                text = "★ Add favorite"
                setTextColor(0xFF1F1F1F.toInt())
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = HudOverlay.makeCardBg(
                    this@MainActivity, 0xFFF9AB00.toInt(),
                    20, 0xFFF9AB00.toInt(), 1
                )
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(10), 0, 0, 0) }
                setOnClickListener {
                    val dest = droppedPinDest
                        ?: return@setOnClickListener
                    dismissDroppedPin()
                    promptStarDestination(dest)
                }
            }
            card.addView(favBtn)
            val closeBtn = TextView(this).apply {
                text = "✕"
                setTextColor(HudOverlay.COLOR_TEXT_MUTED)
                textSize = 15f
                setPadding(dp(10), dp(8), dp(6), dp(8))
                setOnClickListener { dismissDroppedPin() }
            }
            card.addView(closeBtn)
            droppedPinCard = card
            root.addView(card)
        }
        droppedPinLabel?.text = droppedPinText()
        card.visibility = View.VISIBLE
    }

    private fun dismissDroppedPin() {
        droppedPinCard?.visibility = View.GONE
        droppedPinMarker?.let { marker ->
            try { mapLibreMap?.removeMarker(marker) } catch (_: Exception) {}
        }
        droppedPinMarker = null
        droppedPinDest = null
    }

    private fun updateDestinationMarker(dest: NavDestination) {
        val map = mapLibreMap ?: return
        val pos = LatLng(dest.latitude, dest.longitude)
        if (destinationIcon == null) {
            destinationIcon = try {
                IconFactory.getInstance(this).fromBitmap(DestinationPin(this).createBitmap())
            } catch (_: Exception) { null }
        }
        val marker = destinationMarker
        if (marker != null) {
            marker.position = pos
            marker.title = "⚑ ${dest.name}"
            marker.snippet = dest.placeName
            destinationIcon?.let { marker.icon = it }
        } else {
            val opt = MarkerOptions().position(pos).title("⚑ ${dest.name}").snippet(dest.placeName)
            destinationIcon?.let { opt.icon(it) }
            destinationMarker = map.addMarker(opt)
        }
    }
    private fun updateMapPosition(gps: GpsPosition, animate: Boolean) {
        val map = mapLibreMap ?: return
        val pos = LatLng(gps.latitude, gps.longitude)

        val arrowBearing = if (isNorthUp) gps.bearing.toFloat() else 0f
        // C5: quantize the chevron rotation to 5° steps and cache the rendered
        // bitmaps per step — a new bitmap is drawn only when the quantized
        // heading actually changes.
        val quantizedBearingDeg = ((arrowBearing % 360f) / 5f).roundToInt() % 72 * 5
        if (vehicleMarker == null || quantizedBearingDeg != lastRenderedBearingDeg) {
            lastRenderedBearingDeg = quantizedBearingDeg
            try {
                val bmp = chevronBitmapCache.getOrPut(quantizedBearingDeg) {
                    VehicleChevron(this).createBitmap(quantizedBearingDeg.toFloat())
                }
                val icon = IconFactory.getInstance(this).fromBitmap(bmp)
                vehicleIcon = icon
                vehicleMarker?.icon = icon
            } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }

        val markerOpt = MarkerOptions().position(pos).title("ParamAuto").snippet("${gps.latitude}, ${gps.longitude}")
        vehicleIcon?.let { markerOpt.icon(it) }
        vehicleMarker?.let { it.position = pos; it.snippet = "${gps.latitude}, ${gps.longitude}" }
            ?: run { vehicleMarker = map.addMarker(markerOpt) }

        // Self-heal: a gesture (or a spurious/misclassified one) breaks
        // following, but while actually driving the camera must not stay
        // detached forever. If tracking has been broken for over 5 s and
        // the vehicle is moving, resume following in this same call. The
        // routes overview is a deliberate mode and is never overridden.
        if (!isUserTracking && !isRoutesOverviewActive && gps.speed > 2.0 &&
            System.currentTimeMillis() - lastGestureBreakMs > 5000L
        ) {
            isUserTracking = true
            hud.hideRecenter()
            Log.i("ParamAutoMap", "tracking auto-restored while moving")
        }

        if (!isUserTracking || isRoutesOverviewActive) return

        val bearing = if (isNorthUp) 0.0 else gps.bearing
        val tilt = MapDisplayOptions.phoneTilt(isNorthUp)
        val cameraPos = CameraPosition.Builder().target(pos).zoom(currentZoom).bearing(bearing).tilt(tilt).build()
        if (animate) {
            // C5: at most one camera animation per second. Updates arriving
            // sooner skip the animation; the next allowed one catches up to
            // the then-current position.
            val now = System.currentTimeMillis()
            if (now - lastCameraAnimMs >= 1000L) {
                lastCameraAnimMs = now
                map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPos), 500)
            }
        } else {
            map.cameraPosition = cameraPos
        }
    }

    private fun openExternalMap() {
        val g = activeDestination?.let { GpsPosition(it.latitude, it.longitude) } ?: currentGps ?: GpsPosition(33.4484, -112.0740)
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${g.latitude},${g.longitude}?q=${g.latitude},${g.longitude}(Destination)"))) }
        catch (_: Exception) { Toast.makeText(this, "No map application available", Toast.LENGTH_SHORT).show() }
    }

    private fun applyCurrentTheme() {
        val isNight = ThemeManager.isNightMode(this)
        lastAppliedStyleKey = currentStyleKey()
        isRealMapStyleLoaded = true
        // Style reapplied: chevron bitmaps and the route-draw state belong to
        // the previous style, so both are invalidated (C5).
        chevronBitmapCache.clear(); lastRenderedBearingDeg = -1
        lastDrawnRoutes = null; lastDrawnSelectedRouteId = -1
        lastDrawnFeatureKey = null
        val styleJson = ThemeManager.getMapStyleJson(this)
        Log.i("ParamAutoMap", "applyCurrentTheme: styleKey=$lastAppliedStyleKey, styleJsonLen=${styleJson.length}")
        val styleBuilder = Style.Builder().fromJson(styleJson)
        mapLibreMap?.setStyle(styleBuilder) { loadedStyle ->
            Log.i("ParamAutoMap", "applyCurrentTheme: mapLibreMap.setStyle SUCCESS (isFullyLoaded=${loadedStyle.isFullyLoaded})")
            val initialGps = currentGps ?: GpsPosition(33.4484, -112.0740, bearing = 0.0, speed = 0.0, hasFix = true)
            updateMapPosition(initialGps, animate = false)
            activeDestination?.let { dest -> updateDestinationMarker(dest); drawRoutesOnMap(activeRoutes, selectedRouteId) }
        }
        hud.applyTheme(isNight)
        topBar.applyTheme(isNight)
        turnBanner.applyTheme(isNight)
        tripInfoBar.applyTheme(isNight)
        arrivalCard.applyTheme(isNight)
        driveSummaryCard.applyTheme(isNight)
        lastDriveCard.applyTheme(isNight)
        updateMapPickBannerTheme(isNight)
    }

    private fun updateMapPickBannerTheme(isNight: Boolean) {
        if (!::mapPickBanner.isInitialized) return
        val bg = if (isNight) HudOverlay.COLOR_SURFACE_DARK else Color.WHITE
        val border = if (isNight) HudOverlay.COLOR_BORDER_DARK else HudOverlay.COLOR_BORDER_LIGHT
        val textCol = if (isNight) 0xFFE3E3E3.toInt() else HudOverlay.COLOR_TEXT_PRIMARY_LIGHT
        val subCol = if (isNight) HudOverlay.COLOR_TEXT_MUTED else HudOverlay.COLOR_TEXT_MUTED_LIGHT
        mapPickBanner.background = HudOverlay.makeCardBg(this, bg, 20, border, 1)
        mapPickInstructionTv.setTextColor(textCol)
        mapPickAddressTv.setTextColor(subCol)
    }

    private fun createMapPickBanner(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        mapPickBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = HudOverlay.makeCardBg(this@MainActivity, HudOverlay.COLOR_SURFACE_DARK, 20, HudOverlay.COLOR_BORDER_DARK, 1)
            elevation = 12f * density
            setPadding(dp(18), dp(14), dp(18), dp(14))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP
                setMargins(dp(12), dp(42), dp(12), 0)
            }

            // Header Row: Title + Cancel button
            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            mapPickInstructionTv = TextView(this@MainActivity).apply {
                text = "Choose on map"
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            headerRow.addView(mapPickInstructionTv)

            mapPickCancelBtn = TextView(this@MainActivity).apply {
                text = "Cancel"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(HudOverlay.COLOR_TEXT_MUTED)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { exitMapPickMode() }
            }
            headerRow.addView(mapPickCancelBtn)
            addView(headerRow)

            // Address Subtitle
            mapPickAddressTv = TextView(this@MainActivity).apply {
                text = "Tap the map to place pin"
                textSize = 13f
                setTextColor(HudOverlay.COLOR_TEXT_MUTED)
                setPadding(0, dp(4), 0, dp(10))
            }
            addView(mapPickAddressTv)

            // Confirm Button
            mapPickConfirmBtn = TextView(this@MainActivity).apply {
                text = "Save"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = HudOverlay.makeCardBg(this@MainActivity, 0xFF1A73E8.toInt(), 20)
                visibility = View.GONE
                setOnClickListener {
                    val dest = pendingPickedDest ?: return@setOnClickListener
                    val mode = currentMapPickMode
                    if (mode == MapPickMode.HOME) {
                        Bookmarks.setHome(this@MainActivity, dest)
                        Toast.makeText(this@MainActivity, "Home saved", Toast.LENGTH_SHORT).show()
                    } else if (mode == MapPickMode.OFFICE) {
                        Bookmarks.setOffice(this@MainActivity, dest)
                        Toast.makeText(this@MainActivity, "Work saved", Toast.LENGTH_SHORT).show()
                    }
                    exitMapPickMode()
                    topBar.refreshSuggestions()
                    topBar.showInitialSuggestions()
                    NavStateManager.notifyChanged()
                }
            }
            addView(mapPickConfirmBtn)
        }
        return mapPickBanner
    }

    private fun startMapPickMode(mode: MapPickMode) {
        currentMapPickMode = mode
        pendingPickedDest = null
        mapPickMarker?.let { mapLibreMap?.removeMarker(it) }
        mapPickMarker = null
        topBar.setVisible(false)
        val modeName = if (mode == MapPickMode.HOME) "Home" else "Work"
        mapPickInstructionTv.text = "Set $modeName location"
        mapPickAddressTv.text = "Tap on the map to place a pin"
        mapPickConfirmBtn.visibility = View.GONE
        mapPickBanner.visibility = View.VISIBLE
    }

    private fun exitMapPickMode() {
        currentMapPickMode = null
        pendingPickedDest = null
        mapPickMarker?.let { mapLibreMap?.removeMarker(it) }
        mapPickMarker = null
        mapPickBanner.visibility = View.GONE
        topBar.setVisible(true)
    }

    private fun handleMapLocationPicked(tapLatLng: LatLng, pickMode: MapPickMode) {
        val map = mapLibreMap ?: return
        mapPickMarker?.let { map.removeMarker(it) }

        val icon = destinationIcon ?: vehicleIcon
        val title = if (pickMode == MapPickMode.HOME) "Home" else "Office"
        val markerOpt = MarkerOptions().position(tapLatLng).title(title)
        if (icon != null) markerOpt.icon(icon)
        mapPickMarker = map.addMarker(markerOpt)

        map.animateCamera(CameraUpdateFactory.newLatLng(tapLatLng), 400)

        mapPickAddressTv.text = "Resolving address..."
        mapPickConfirmBtn.visibility = View.GONE

        Thread {
            val resolvedAddress = reverseGeocode(tapLatLng)
            runOnUiThread {
                if (currentMapPickMode == pickMode) {
                    val dest = NavDestination(
                        name = title,
                        placeName = resolvedAddress,
                        latitude = tapLatLng.latitude,
                        longitude = tapLatLng.longitude
                    )
                    pendingPickedDest = dest
                    mapPickAddressTv.text = resolvedAddress
                    mapPickConfirmBtn.text = "Save $title"
                    mapPickConfirmBtn.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun reverseGeocode(latLng: LatLng): String {
        try {
            if (android.location.Geocoder.isPresent()) {
                val geocoder = android.location.Geocoder(this, java.util.Locale.getDefault())
                @Suppress("DEPRECATION")
                val list = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                if (!list.isNullOrEmpty()) {
                    val addr = list[0]
                    val line = addr.getAddressLine(0)
                    if (!line.isNullOrBlank()) return line
                    val parts = listOfNotNull(addr.featureName, addr.thoroughfare, addr.locality, addr.adminArea)
                    if (parts.isNotEmpty()) return parts.joinToString(", ")
                }
            }
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }

        try {
            val url = java.net.URL("https://photon.komoot.io/reverse?lat=${latLng.latitude}&lon=${latLng.longitude}")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "ParamAuto/1.0")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = org.json.JSONObject(body)
                val features = json.optJSONArray("features")
                if (features != null && features.length() > 0) {
                    val props = features.getJSONObject(0).optJSONObject("properties")
                    if (props != null) {
                        val name = props.optString("name", "")
                        val street = props.optString("street", "")
                        val city = props.optString("city", "")
                        val state = props.optString("state", "")
                        val parts = listOf(name, street, city, state).filter { it.isNotBlank() }.distinct()
                        if (parts.isNotEmpty()) return parts.joinToString(", ")
                    }
                }
            }
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }

        return "%.5f, %.5f".format(java.util.Locale.US, latLng.latitude, latLng.longitude)
    }

    private fun promptStarDestination(dest: NavDestination) {
        val isAlreadyStarred = Bookmarks.isStarred(this, dest)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        // Addendum 133: an M3 sheet (M3Sheet), like the Settings screens.
        val (dialog, sheetBody, pal) = M3Sheet.create(
            this,
            if (isAlreadyStarred) "Edit saved place" else "Save place",
            dest.placeName.ifEmpty {
                "%.4f, %.4f".format(dest.latitude, dest.longitude)
            }
        )
        val input = android.widget.EditText(this).apply {
            setText(dest.name)
            selectAll()
            setTextColor(pal.textPrimary)
            setHintTextColor(pal.textSecondary)
            hint = "Enter name (e.g. Gym, Mom's House)"
            textSize = 14f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = HudOverlay.makeCardBg(
                this@MainActivity, pal.inputBg, 8, pal.border, 1
            )
        }
        sheetBody.addView(input)
        val buttons = mutableListOf<TextView>()
        if (isAlreadyStarred) {
            buttons += M3Sheet.dangerButton(this, pal, "Remove") {
                Bookmarks.removeStarred(this, dest)
                Toast.makeText(
                    this, "Removed from saved places", Toast.LENGTH_SHORT
                ).show()
                topBar.refreshSuggestions()
                dialog.dismiss()
            }
        }
        buttons += M3Sheet.tonalButton(this, pal, "Cancel") { dialog.dismiss() }
        buttons += M3Sheet.primaryButton(this, pal, "Save") {
            val name = input.text.toString().trim().ifEmpty { dest.name }
            val savedDest = dest.copy(name = name)
            Bookmarks.addStarred(this, savedDest)
            Toast.makeText(this, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
            topBar.refreshSuggestions()
            dialog.dismiss()
        }
        sheetBody.addView(M3Sheet.buttonRow(this, *buttons.toTypedArray()))
        dialog.show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (::topBar.isInitialized && topBar.isShowingSuggestions() && !topBar.isTouchInside(ev.rawX, ev.rawY)) {
                topBar.collapse(activeDestination)
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (::topBar.isInitialized && topBar.isShowingSuggestions()) {
            topBar.collapse(activeDestination)
            return
        }
        if (currentMapPickMode != null) {
            exitMapPickMode()
            return
        }
        super.onBackPressed()
    }

    /**
     * Creates the MapView into the shell's container. Runs from
     * finishStartup() — after the first frame — so app open draws
     * the canvas immediately instead of waiting on the MapView's
     * native setup (Param, 2026-11-07).
     */
    private fun createMapView(savedInstanceState: Bundle?) {
        val container = mapContainer ?: return
        if (mapView != null) return
        val mv = MapView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            onCreate(savedInstanceState)
        }
        mapView = mv
        container.addView(mv, 0)
        // The activity is already started and resumed by the time
        // this runs: bring the MapView's lifecycle current.
        mv.onStart()
        mv.onResume()
        // Swap the cached startup frame out the moment the live
        // map has fully rendered its first frame.
        val renderListener = object : MapView.OnDidFinishRenderingMapListener {
            override fun onDidFinishRenderingMap(fully: Boolean) {
                if (!fully) return
                startupPlaceholder?.let { container.removeView(it) }
                startupPlaceholder = null
                mv.removeOnDidFinishRenderingMapListener(this)
                // Refresh the cached frame mid-session (2026-11-08
                // startup debug): onPause's snapshot races process
                // death — if the app is killed fast, its callback
                // may never fire and the next cold open has no
                // frame at all. A save a few seconds into a healthy
                // session makes a fresh frame much more likely.
                mv.postDelayed({
                    try {
                        mapLibreMap?.snapshot { bmp -> if (bmp != null) StartupMapCache.saveAsync(this@MainActivity, bmp) }
                    } catch (e: Exception) { Log.w("MainActivity", "Mid-session frame save failed: ${e.message}") }
                }, 6000)
            }
        }
        mv.addOnDidFinishRenderingMapListener(renderListener)
        // Timeout swap (2026-11-08 startup debug): "fully rendered"
        // waits for EVERY tile in the first viewport, which is
        // network-bound when the tile cache is cold (new area,
        // evicted cache, offline) — the app then sits on the frozen
        // cached photo (or an empty canvas) for seconds even though
        // the live map underneath is already usable. If the full
        // render hasn't landed in 3 s, swap anyway; tiles keep
        // streaming into the live map.
        mv.postDelayed({
            startupPlaceholder?.let { container.removeView(it) }
            startupPlaceholder = null
        }, 3000)
        mv.getMapAsync { map ->
            mapLibreMap = map
        lastAppliedStyleKey = currentStyleKey()
        isRealMapStyleLoaded = true
            val styleJson = ThemeManager.getMapStyleJson(this)
            Log.i("ParamAutoMap", "Applying initial style (len=${styleJson.length}, snippet=${styleJson.take(120)}...)")
            val initialStyle = Style.Builder().fromJson(styleJson)
            map.setStyle(initialStyle) { loadedStyle ->
                Log.i("ParamAutoMap", "map.setStyle SUCCESS: loadedStyle isReady=${loadedStyle.isFullyLoaded}")
                val initialGps = currentGps ?: GpsPosition(33.4484, -112.0740, bearing = 0.0, speed = 0.0, hasFix = true)
                updateMapPosition(initialGps, animate = false)
                activeDestination?.let { dest -> updateDestinationMarker(dest); drawRoutesOnMap(activeRoutes, selectedRouteId) }
            }
            mv.addOnDidFailLoadingMapListener { errorMsg ->
                Log.e("ParamAutoMap", "MapLibre addOnDidFailLoadingMapListener: errorMsg=$errorMsg")
                val fallbackStyleJson = if (NavStateManager.usePhoneVectorMap) {
                    ThemeManager.getOpenFreeMapStyleJson(this)
                } else {
                    ThemeManager.getCartoBasemapStyleJson(ThemeManager.isNightMode(this))
                }
                Log.w("ParamAutoMap", "Applying fallback style (len=${fallbackStyleJson.length})")
                val fallbackStyle = Style.Builder().fromJson(fallbackStyleJson)
                map.setStyle(fallbackStyle) {
                    Log.i("ParamAutoMap", "map.setStyle FALLBACK loaded")
                    val initialGps = currentGps ?: GpsPosition(33.4484, -112.0740, bearing = 0.0, speed = 0.0, hasFix = true)
                    updateMapPosition(initialGps, animate = false)
                    lastDrawnRoutes = null; lastDrawnSelectedRouteId = -1
                    lastDrawnFeatureKey = null
                    activeDestination?.let { dest -> updateDestinationMarker(dest); drawRoutesOnMap(activeRoutes, selectedRouteId) }
                }
            }
            mv.addOnDidFinishLoadingStyleListener {
                Log.i("ParamAutoMap", "MapLibre addOnDidFinishLoadingStyleListener fired!")
            }
            map.uiSettings.isAttributionEnabled = false; map.uiSettings.isLogoEnabled = false; map.uiSettings.isCompassEnabled = false

            map.addOnMapClickListener { latLng ->
                handleMapClick(latLng)
            }

            map.addOnMapLongClickListener { latLng ->
                handleMapLongPress(latLng)
            }

            map.setOnMarkerClickListener { marker ->
                val altRoute = altRouteMarkersMap[marker]
                if (altRoute != null) {
                    selectRoute(altRoute, keepOverviewIfActive = isRoutesOverviewActive)
                    true
                } else {
                    false
                }
            }

            map.addOnCameraMoveListener {
                hud.updateBearing(if (!isNorthUp) map.cameraPosition.bearing else 0.0)
            }
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    isUserTracking = false; hud.showRecenter()
                    lastGestureBreakMs = System.currentTimeMillis()
                    Log.i("ParamAutoMap", "tracking broken by gesture")
                }
            }
            map.addOnCameraIdleListener {
                if (!isUserTracking) {
                    currentZoom = map.cameraPosition.zoom
                    if (kotlin.math.abs(currentZoom - 16.0) > 0.1) hud.showRecenter()
                }
            }
        }
    }

    private fun buildContentView(savedInstanceState: Bundle?): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(HudOverlay.COLOR_BACKGROUND) }
        // The map canvas starts as an empty container in the
        // app's background color; finishStartup() creates the
        // MapView into it after the first frame (Param, 2026-11-07).
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        mapContainer = container
        root.addView(container)
        // Cached last frame as the canvas backdrop (Param,
        // 2026-11-07): the open paints the real map from the
        // saved frame instantly; the live MapView renders over
        // it and the placeholder is removed at its first full
        // render. First install has no frame yet — empty canvas
        // for that one launch only.
        StartupMapCache.loadBitmap(this)?.let { bmp ->
            val iv = android.widget.ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            startupPlaceholder = iv
            container.addView(iv)
        }

        hud.attachTo(root); topBar.attachTo(root)
        turnBanner.attachTo(root)
        // Addendum 127: the four bottom notification cards live in one
        // vertical dock anchored at the bottom of the screen, so cards
        // visible at the same time stack instead of overriding each
        // other. Last Drive sits lowest, directly under the bottom
        // control strip. The dock reports its laid-out height to the
        // HUD after every layout pass, and the HUD lifts the bottom
        // control clusters above the whole stack.
        val dockDensity = resources.displayMetrics.density
        fun dockDp(v: Int) = (v * dockDensity).roundToInt()
        val bottomDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(dockDp(12), 0, dockDp(12), dockDp(16))
            }
        }
        root.addView(bottomDock)
        tripInfoBar.attachTo(bottomDock); arrivalCard.attachTo(bottomDock); driveSummaryCard.attachTo(bottomDock); turnBanner.attachEndCardTo(bottomDock); lastDriveCard.attachTo(bottomDock)
        bottomDock.viewTreeObserver.addOnGlobalLayoutListener {
            val lp = bottomDock.layoutParams as? FrameLayout.LayoutParams
            val footprint = if (bottomDock.height > 0) bottomDock.height + (lp?.bottomMargin ?: 0) else 0
            bottomDockFootprintPx = footprint
            hud.setBottomStackHeight(footprint)
            // Addendum 133: keep the dropped-pin card above the dock
            // stack — its authored 110dp margin slides under a
            // two-card stack.
            droppedPinCard?.let { card ->
                val clp = card.layoutParams as? FrameLayout.LayoutParams
                if (clp != null) {
                    val target = maxOf(dockDp(110), footprint + dockDp(8))
                    if (clp.bottomMargin != target) {
                        clp.bottomMargin = target
                        card.layoutParams = clp
                    }
                }
            }
        }
        // Addendum 129: the dock itself must clear the system
        // navigation bar — at its authored 16dp margin the lowest
        // card's bottom strip slides behind the nav-bar scrim and
        // renders dimmed (Param, 2026-11-21, on vc43). The dock's
        // bottom margin follows the inset, same rule as the HUD
        // clusters' lift; the layout listener above then reports the
        // dock's true footprint to the HUD.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bottomDock) { v, insets ->
            val navInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            ).bottom
            val lp = v.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                val target = liftedBottomMargin(dockDp(16), navInset)
                if (lp.bottomMargin != target) {
                    lp.bottomMargin = target
                    v.layoutParams = lp
                }
            }
            insets
        }
        root.addView(createMapPickBanner())

        val isNight = ThemeManager.isNightMode(this)
        hud.applyTheme(isNight); topBar.applyTheme(isNight); turnBanner.applyTheme(isNight); tripInfoBar.applyTheme(isNight); arrivalCard.applyTheme(isNight); driveSummaryCard.applyTheme(isNight); lastDriveCard.applyTheme(isNight)
        return root
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        checkBleTestIntent(intent)
        handleSharedIntent(intent)
        handleViewIntent(intent)
    }

    /**
     * Share-to-comma (Feature 11): text shared from Maps/browser/messages
     * becomes a search or a direct destination send. The intent is consumed
     * (extras stripped, action cleared) so a rotation never re-sends it.
     */
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_SUBJECT)
        intent.action = null
        setIntent(intent)
        dispatchSharedTarget(SharedTextParser.parse(shared))
    }

    /**
     * Open-with-ParamAuto (2026-11-18: "accept location or trip from
     * other app"): VIEW intents — geo: URIs from other apps' location
     * actions and maps links (Google/Apple/Waze) tapped anywhere.
     * Consumed like the share intent so a rotation never re-sends.
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        intent.data = null
        intent.action = null
        setIntent(intent)
        val target = if (data.scheme == "geo" || data.scheme == "google.navigation") {
            SharedTextParser.parseNavigationUri(data.toString())
        } else {
            SharedTextParser.parse(data.toString())
        }
        dispatchSharedTarget(target)
    }

    /** Routes one parsed share/view target to its action. */
    private fun dispatchSharedTarget(target: SharedTarget) {
        when (target) {
            is SharedTarget.Query -> topBar.submitExternalQuery(target.text)
            is SharedTarget.Coords -> handleDestinationSelected(
                NavDestination(
                    name = target.label ?: "Shared location",
                    placeName = target.label ?: "",
                    latitude = target.latitude,
                    longitude = target.longitude,
                    source = "shared"
                )
            )
            is SharedTarget.Link -> resolveLinkOnPhone(target.url)
            SharedTarget.Unreadable ->
                Toast.makeText(this, "Couldn't read a place from what was shared", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * A link the parser cannot read directly (a short link) is
     * expanded ON THE PHONE (Param, 2026-11-18: "Don't want risk
     * comma side. Do in phone"): follow its redirects here, parse
     * the final URL like any other share, and only then search or
     * send. The comma is never asked to resolve links.
     */
    private fun resolveLinkOnPhone(url: String) {
        Toast.makeText(this, "Opening that link…", Toast.LENGTH_SHORT).show()
        Thread {
            when (val result = LinkExpander.expand(url)) {
                is LinkExpander.Result.Expanded -> {
                    val target = SharedTextParser.parse(result.finalUrl)
                    runOnUiThread {
                        if (target is SharedTarget.Link ||
                            target is SharedTarget.Unreadable
                        ) {
                            Toast.makeText(
                                this, "Couldn't read a place from that link",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            dispatchSharedTarget(target)
                        }
                    }
                }
                is LinkExpander.Result.Failed -> runOnUiThread {
                    Toast.makeText(
                        this, "Couldn't open that link: ${result.reason}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /**
     * Voice search (Feature 18): the system speech UI does the listening
     * (no RECORD_AUDIO in this app); the picked transcription fills the
     * search field and runs the normal debounced search, exactly like a
     * shared query (Feature 11).
     */
    private fun startVoiceSearch() {
        if (!VoiceSearch.isAvailable(this)) {
            Toast.makeText(
                this,
                "Voice search isn't available on this phone",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(VoiceSearch.createIntent(), REQ_VOICE_SEARCH)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Voice search isn't available on this phone",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Voice search round-trip predates the Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE_SEARCH || resultCode != Activity.RESULT_OK) return
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val text = VoiceQuery.pick(results) ?: return
        if (::topBar.isInitialized) topBar.submitExternalQuery(text)
    }

    /**
     * Last-drive card (Feature 13): once per app start, when the comma is
     * parked with no active destination and a last drive is known.
     */
    private fun maybeShowLastDriveCard() {
        if (lastDriveCardShown || !::lastDriveCard.isInitialized) return
        val drive = NavStateManager.lastDrive ?: return
        if (NavStateManager.deviceState?.lowercase()?.trim() != "parked") return
        if (NavStateManager.activeDestination != null || NavStateManager.isNavigating) return
        lastDriveCardShown = true
        lastDriveCard.show(drive)
    }

    private fun checkBleTestIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("test_ble", false) == true) {
            Thread({
                Log.i("MainActivity", "Starting BLE connection test...")
                val client = BleBridgeClient.getSharedClient(this)
                try {
                    val health = client.connectAndHealth(25000)
                    Log.i("MainActivity", "BLE TEST SUCCESS: $health")
                    runOnUiThread {
                        Toast.makeText(this, "BLE Test SUCCESS: $health", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "BLE TEST FAILED: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(this, "BLE Test FAILED: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }, "ble-test-runner").start()
        }
        if (intent?.getBooleanExtra("fetch_key", false) == true) {
            Thread({
                Log.i("MainActivity", "Starting BLE key fetch via fetchAndSaveKeyFromComma...")
                try {
                    val key = BleBridgeClient.fetchAndSaveKeyFromComma(this)
                    Log.i("MainActivity", "BLE KEY FETCH RESULT: $key")
                    runOnUiThread {
                        Toast.makeText(this, "BLE Key: $key", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "BLE KEY FETCH FAILED: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(this, "BLE Key FAILED: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }, "ble-fetch-runner").start()
        }
        if (intent?.hasExtra("nav_lat") == true && intent.hasExtra("nav_lng") == true) {
            val lat = try {
                intent.getDoubleExtra("nav_lat", 0.0)
            } catch (_: Exception) {
                intent.getFloatExtra("nav_lat", 0f).toDouble()
            }
            val lng = try {
                intent.getDoubleExtra("nav_lng", 0.0)
            } catch (_: Exception) {
                intent.getFloatExtra("nav_lng", 0f).toDouble()
            }
            val name = intent.getStringExtra("nav_name") ?: "Test Destination"
            Log.i("MainActivity", "Test intent triggering destination: $name ($lat, $lng)")
            val dest = NavDestination(latitude = lat, longitude = lng, name = name, placeName = name)
            runOnUiThread {
                applyDestination(dest)
            }
        }
    }

}
