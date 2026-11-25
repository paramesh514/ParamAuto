package com.parampilot.auto

import android.content.Context
import android.graphics.Rect
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Trip
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Authentic Android Auto Navigation Screen:
 * - Direct Mapbox vector/traffic map projection via [CarMapRenderer]
 * - Cockpit HUD on car display: Digital Speedometer, US MUTCD Speed Limit badge, Rotating Compass, 12V Power
 * - Native Android Auto [SearchScreen] accessible directly from car screen
 * - Full [NavigationTemplate] with active turn cues, remaining distance, ETA, and speed/limit
 * - 100% synchronized with phone app via [NavStateManager]
 */
class NavScreen(carContext: CarContext, private val client: CommaClient) :
    Screen(carContext), SurfaceCallback, DefaultLifecycleObserver {

    @Volatile
    private var latest: TelemetryPacket? = null

    @Volatile
    private var lastPacketAtMs: Long = 0

    private var surfaceContainer: SurfaceContainer? = null
    private val mapRenderer = CarMapRenderer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isDrawing = java.util.concurrent.atomic.AtomicBoolean(false)
    private var redrawPending = false

    private var currentZoom: Double = DEFAULT_ZOOM
    private var isNorthUp: Boolean = false
    private var locationManager: LocationManager? = null

    /** True while this screen holds a phone location subscription (see B7 handling below). */
    @Volatile
    private var phoneLocationSubscribed: Boolean = false

    /** One-shot guard for UsageCounters.init in onGetTemplate. */
    private var usageCountersInitialized: Boolean = false
    private var lastGeocodeAtMs: Long = 0
    private var isCarNavActive: Boolean = false
    private var lastTemplateInvalidateMs: Long = 0
    private var lastKnownCue: String? = null

    /** Last notice text computed for the car nav screen (Addendum
     * 142); a change forces a template rebuild so the card appears
     * and clears without waiting for a navigation event. */
    private var lastNoticeText: String? = ""

    @Volatile
    private var interpolatedGps: GpsPosition? = null

    private val markerAnimator = VehicleMarkerAnimator { pos, bearing ->
        val raw = NavStateManager.currentGps
        interpolatedGps = GpsPosition(
            latitude = pos.latitude,
            longitude = pos.longitude,
            bearing = bearing.toDouble(),
            speed = raw?.speed ?: 0.0,
            hasFix = raw?.hasFix ?: true
        )
        drawMapOnSurface()
    }

    // Pre-cached CarIcon instances to prevent allocation and binding dropouts under IPC load
    private val zoomInIcon by lazy { CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_zoom_in)).build() }
    private val zoomOutIcon by lazy { CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_zoom_out)).build() }
    private val compassIcon by lazy { CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_compass_needle)).build() }
    private val recenterIcon by lazy { CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_recenter)).build() }
    private val searchIcon by lazy { CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_search)).build() }
    private val homeIcon by lazy { CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_home)).build() }
    private val workIcon by lazy { CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_work)).build() }
    private val bookmarkIcon by lazy { CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_bookmark)).build() }
    private val endIcon by lazy { CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_close)).build() }

    private fun isRecenterActive(): Boolean {
        return kotlin.math.abs(currentZoom - DEFAULT_ZOOM) > 0.1 || isNorthUp
    }

    private fun recenter() {
        currentZoom = DEFAULT_ZOOM
        isNorthUp = false
        drawMapOnSurface()
        CarToast.makeText(carContext, "Re-centered", CarToast.LENGTH_SHORT).show()
    }

    private val carNavCallback = object : androidx.car.app.navigation.NavigationManagerCallback {
        override fun onStopNavigation() {
            // Host-initiated (another app took turn-by-turn): NOT
            // the user's End — the comma route must survive it.
            mainHandler.post {
                stopNavigationFromHost()
            }
        }

        override fun onAutoDriveEnabled() {
            // NF-7 test drive (Car App Quality): the host (DHU
            // AUTO_DRIVE, Play reviewers) asks the app to simulate
            // drives to chosen destinations. The mode has no
            // disable callback — it stays for the session; the
            // simulation starts when a destination is chosen, or
            // immediately when one is already active.
            mainHandler.post {
                autoDriveEnabled = true
                maybeStartAutoDriveSimulation()
            }
        }
    }

    private val navListener: () -> Unit = {
        // Telemetry/GPS state changed: keep the phone GPS subscription in
        // sync with comma GPS freshness (unsubscribe while it's fresh).
        updatePhoneLocationSubscription()

        val hasNav = NavStateManager.activeDestination != null
        var forceInvalidate = false

        if (!hasNav && hostStopped) {
            // The comma route ended while the host had us parked:
            // the park is over, the next route claims normally.
            hostStopped = false
        }

        if (hasNav != isCarNavActive && !(hasNav && hostStopped)) {
            isCarNavActive = hasNav
            forceInvalidate = true
            mainHandler.post {
                try {
                    val carNavManager = carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
                    if (hasNav) {
                        carNavManager.navigationStarted()
                    } else {
                        carNavManager.navigationEnded()
                    }
                } catch (e: Exception) {
                    Log.w("NavScreen", "Car NavigationManager state sync failed: ${e.message}")
                }
            }
        }

        // NF-7: in test-drive mode, a destination becoming active
        // starts its simulated drive.
        if (autoDriveEnabled && hasNav && !SimulationEngine.isSimulating) {
            mainHandler.post { maybeStartAutoDriveSimulation() }
        }

        val currentCue = NavStateManager.activeTurnCue
        if (currentCue != lastKnownCue) {
            lastKnownCue = currentCue
            forceInvalidate = true
        }

        // Addendum 142: a notice change (queued destination set or
        // cleared, internet lost or regained) rebuilds the template
        // so the MessageInfo card tracks reality on its own.
        val noticeText = currentNoticeText()
        if (noticeText != lastNoticeText) {
            lastNoticeText = noticeText
            forceInvalidate = true
        }

        // Vehicle marker: animate toward the layer-2 drawing
        // position (DrawingPosition) — the comma fix while it is
        // available, the phone fix when it is not (Param,
        // 2026-11-06). Re-evaluated on every state change and on
        // every phone fix (see the location listener).
        updateDrawingPosition()

        val now = System.currentTimeMillis()
        val shouldInvalidate = forceInvalidate || (hasNav && (now - lastTemplateInvalidateMs >= 1000L))

        mainHandler.post {
            drawMapOnSurface()
            if (shouldInvalidate) {
                lastTemplateInvalidateMs = now
                try { invalidate() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    /** Latest phone fix + its wall-clock time, for [DrawingPosition]. */
    private var lastPhoneFix: GpsPosition? = null
    private var lastPhoneFixAtMs = 0L

    /**
     * The one drawing-position evaluation (Param, 2026-11-06):
     * comma fix while available, phone fix when the comma fix is
     * missing or older than DrawingPosition.COMMA_AVAILABLE_MS —
     * phone only while the "Use phone location" toggle allows it.
     * Called from the state listener (comma updates) and from the
     * phone location listener (phone updates), so whichever source
     * is fresener drives the marker. Simulation keeps its own feed.
     */
    private fun updateDrawingPosition() {
        if (SimulationEngine.isSimulating) {
            NavStateManager.currentGps?.let { g ->
                if (g.hasFix) {
                    markerAnimator.animateTo(
                        org.maplibre.android.geometry.LatLng(g.latitude, g.longitude),
                        g.bearing.toFloat(),
                        NavStateManager.speedMs
                    )
                }
            }
            return
        }
        val now = System.currentTimeMillis()
        val pkt = NavStateManager.latestPacket
        val commaAgeMs = when {
            pkt == null -> null
            pkt.timestamp > 0.0 -> now - (pkt.timestamp * 1000.0).toLong()
            NavStateManager.lastPacketReceivedAtMs > 0L -> now - NavStateManager.lastPacketReceivedAtMs
            else -> null
        }
        val phoneAgeMs = if (lastPhoneFixAtMs > 0L) now - lastPhoneFixAtMs else null
        val choice = DrawingPosition.choose(
            comma = pkt?.gps,
            commaAgeMs = commaAgeMs,
            phone = lastPhoneFix,
            phoneAgeMs = phoneAgeMs,
            phoneAllowed = NavStateManager.usePhoneLocation &&
                HeartbeatTransport.phoneDataAllowed(NavStateManager.activeTransport)
        ) ?: return
        val speed = if (choice.gps.speed > 0.0) choice.gps.speed else NavStateManager.speedMs
        markerAnimator.animateTo(
            org.maplibre.android.geometry.LatLng(choice.gps.latitude, choice.gps.longitude),
            choice.gps.bearing.toFloat(),
            speed
        )
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (SimulationEngine.isSimulating) return
            // Record for the drawing choice before any early return:
            // the drawing fallback needs the freshest phone fix even
            // while the comma state writes below take another path.
            lastPhoneFix = GpsPosition(
                latitude = loc.latitude,
                longitude = loc.longitude,
                bearing = if (loc.hasBearing()) loc.bearing.toDouble() else (lastPhoneFix?.bearing ?: 0.0),
                speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                hasFix = true
            )
            lastPhoneFixAtMs = if (loc.time > 0L) loc.time else System.currentTimeMillis()
            updateDrawingPosition()
            PhoneGpsFeeder.onLocation(loc)
            val pktNow = NavStateManager.latestPacket
            val commaFresh = client.commaFound && pktNow?.gps?.hasFix == true &&
                NavStateManager.lastPacketReceivedAtMs > 0 &&
                (System.currentTimeMillis() - NavStateManager.lastPacketReceivedAtMs) < STALE_MS
            if (commaFresh) {
                return
            }
            val useLoc = NavStateManager.usePhoneLocation
            val useSpd = NavStateManager.usePhoneSpeed
            if (!useLoc && !useSpd && NavStateManager.currentGps != null) return

            val gps = GpsPosition(
                latitude = loc.latitude,
                longitude = loc.longitude,
                bearing = if (loc.hasBearing()) loc.bearing.toDouble() else (NavStateManager.currentGps?.bearing ?: 0.0),
                speed = if (useSpd && loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                hasFix = true
            )
            if (useLoc || NavStateManager.currentGps == null) {
                NavStateManager.updateGps(if (useSpd) gps else gps.copy(speed = 0.0))
                reverseGeocodeStreet(loc.latitude, loc.longitude)
            } else if (useSpd) {
                NavStateManager.speedMs = gps.speed
                NavStateManager.notifyChanged()
            }
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private fun reverseGeocodeStreet(lat: Double, lng: Double) {
        // Phone routing OFF: the current-street pill comes from the comma
        // maneuver street or stays blank — never from the phone Geocoder.
        if (!NavStateManager.usePhoneRoute) return
        val now = System.currentTimeMillis()
        if (now - lastGeocodeAtMs < 8000L) return
        lastGeocodeAtMs = now
        geocodeExecutor.execute {
            try {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(carContext, java.util.Locale.getDefault())
                    val list = geocoder.getFromLocation(lat, lng, 1)
                    if (!list.isNullOrEmpty()) {
                        val street = list[0].thoroughfare ?: list[0].subLocality ?: list[0].featureName
                        if (!street.isNullOrBlank()) {
                            // Fill only a blank: the comma's own street
                            // (written by the snapshot path) always wins
                            // over the phone's geocode (audit finding 18
                            // — the two used to overwrite each other).
                            if (NavStateManager.currentStreet.isNullOrBlank()) {
                                NavStateManager.currentStreet = street
                                mainHandler.post { drawMapOnSurface() }
                            }
                        }
                    }
                }
            } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private var lastIdleInvalidateMs = 0L

    private val freshnessTicker = object : Runnable {
        override fun run() {
            drawMapOnSurface()
            // Idle keep-alive (Param, 2026-11-06: "keep search zoom in
            // zoom out button always visible when navigation is not
            // active"): the Search action strip and the zoom map strip
            // are drawn by the HEAD UNIT, which fades them when the
            // template is not refreshed — and template refreshes are
            // packet-driven, so they stall exactly when the link is
            // slow (Bluetooth). While no navigation is active, refresh
            // the template every 3 s so the car's buttons stay up.
            if (NavStateManager.activeDestination == null) {
                val now = System.currentTimeMillis()
                if (now - lastIdleInvalidateMs >= 3000L) {
                    lastIdleInvalidateMs = now
                    invalidate()
                }
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    init {
        lifecycle.addObserver(this)
        NavStateManager.addListener(navListener)
    }

    override fun onCreate(owner: LifecycleOwner) {
        NavStateManager.appContext = carContext.applicationContext
        CommaTileDiskCache.init(carContext.applicationContext)
        MapFrameDiskCache.init(carContext.applicationContext)
        try {
            org.maplibre.android.MapLibre.getInstance(carContext.applicationContext)
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        try {
            val carNavManager = carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
            carNavManager.setNavigationManagerCallback(carNavCallback)
            val hasNav = NavStateManager.activeDestination != null
            if (hasNav) {
                isCarNavActive = true
                carNavManager.navigationStarted()
            }
        } catch (e: Exception) {
            Log.w("NavScreen", "Failed to register NavigationManagerCallback: ${e.message}")
        }
        startLocationUpdates()
        mainHandler.post(freshnessTicker)
        screenReady = true
        pendingExternalQuery?.let { query ->
            pendingExternalQuery = null
            pushSearchForQuery(query)
        }
    }

    private fun startLocationUpdates() {
        try {
            val lm = carContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            locationManager = lm

            // Seed immediately from last known location
            val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

            lastLoc?.let { loc ->
                if (NavStateManager.currentGps == null && !SimulationEngine.isSimulating) {
                    val gps = GpsPosition(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        bearing = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0,
                        speed = if (NavStateManager.usePhoneSpeed && loc.hasSpeed()) loc.speed.toDouble() else 0.0,
                        hasFix = true
                    )
                    NavStateManager.updateGps(gps)
                    reverseGeocodeStreet(loc.latitude, loc.longitude)
                }
            }

            updatePhoneLocationSubscription()
        } catch (e: Exception) {
            Log.w("NavScreen", "Location updates init failed: ${e.message}")
        }
    }

    private fun locationProviders(): List<String> {
        val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            providers.add(LocationManager.FUSED_PROVIDER)
        }
        return providers
    }

    /**
     * Phone GPS subscription management (review B7): while the comma's own
     * GPS fix is fresh — the latest telemetry packet is younger than
     * [STALE_MS] and carries a fix — the phone stays unsubscribed to save
     * battery. When the comma fix goes stale or disappears, the phone
     * subscription resumes. EXCEPTION (Param, 2026-11-06): while the
     * "Use phone location" toggle is on, the phone is the drawing
     * fallback and must already be live the moment the comma fix
     * ages out (DrawingPosition's bar is 2.5 s, tighter than
     * [STALE_MS]) — so the subscription then stays on regardless
     * of comma freshness. Evaluated on every NavStateManager change via
     * [navListener]; request/remove calls happen only on fresh<->stale
     * transitions. The listener itself is unchanged — it still
     * early-returns while comma GPS is fresh.
     *
     * Freshness is judged from latestPacket only: currentGps cannot be
     * used, because phone GPS writes also land there.
     */
    private fun updatePhoneLocationSubscription() {
        val needPhoneGps = NavStateManager.usePhoneLocation || NavStateManager.usePhoneSpeed ||
            NavStateManager.usePhoneLocationToComma || NavStateManager.currentGps == null
        if (!needPhoneGps) {
            removePhoneLocationUpdates()
            return
        }
        val pkt = NavStateManager.latestPacket
        val gps = pkt?.gps
        val commaGpsFresh = pkt != null && gps != null && gps.hasFix && pkt.timestamp > 0.0 &&
            (System.currentTimeMillis() - (pkt.timestamp * 1000.0).toLong()) < STALE_MS
        if (commaGpsFresh && NavStateManager.currentGps != null && !NavStateManager.usePhoneLocationToComma &&
            !NavStateManager.usePhoneLocation
        ) {
            removePhoneLocationUpdates()
        } else {
            requestPhoneLocationUpdates()
        }
    }

    private fun requestPhoneLocationUpdates() {
        if (phoneLocationSubscribed) return
        val lm = locationManager ?: return
        var anySubscribed = false
        for (p in locationProviders()) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 1f, locationListener, Looper.getMainLooper())
                    anySubscribed = true
                }
            } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
        if (anySubscribed) {
            phoneLocationSubscribed = true
        }
    }

    private fun removePhoneLocationUpdates() {
        if (!phoneLocationSubscribed) return
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        phoneLocationSubscribed = false
    }

    private val renderExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onDestroy(owner: LifecycleOwner) {
        // A test-drive simulation belongs to this session's screen;
        // it does not outlive it.
        if (autoDriveEnabled && SimulationEngine.isSimulating) {
            SimulationEngine.stop()
        }
        try {
            val carNavManager = carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
            carNavManager.clearNavigationManagerCallback()
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        mainHandler.removeCallbacks(freshnessTicker)
        phoneLocationSubscribed = false
        locationManager = null
        NavStateManager.removeListener(navListener)
        surfaceContainer = null
        try { renderExecutor.shutdownNow() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        markerAnimator.cancel()
    }

    // ------------------------------------------------------------------------
    // SurfaceCallback (Mapbox Map projection on car display surface)
    // ------------------------------------------------------------------------

    private val surfaceLock = Any()

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        synchronized(surfaceLock) {
            surfaceContainer = container
        }
        drawMapOnSurface()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        drawMapOnSurface()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        drawMapOnSurface()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        synchronized(surfaceLock) {
            surfaceContainer = null
            isDrawing.set(false)
            redrawPending = false
        }
    }

    override fun onClick(x: Float, y: Float) {
        if (isRecenterActive()) {
            val container = surfaceContainer
            val w = (container?.width ?: 800).toFloat()
            val h = (container?.height ?: 480).toFloat()
            val isNavRunning = NavStateManager.activeDestination != null
            val hitRect = mapRenderer.getRecenterRect(w, h, isNavRunning)
            if (hitRect.contains(x, y) || (x in (w * 0.35f)..(w * 0.65f) && y > (h - 130f))) {
                recenter()
            }
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val newZoom = if (scaleFactor > 1f) currentZoom + 0.5 else currentZoom - 0.5
        currentZoom = newZoom.coerceIn(10.0, 18.0)
        drawMapOnSurface()
    }

    private fun drawMapOnSurface() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        if (!surface.isValid) return

        if (!isDrawing.compareAndSet(false, true)) {
            redrawPending = true
            return
        }

        renderExecutor.execute {
            try {
                var currentSurface: android.view.Surface? = null
                var canvas: android.graphics.Canvas? = null

                synchronized(surfaceLock) {
                    val currentContainer = surfaceContainer ?: return@execute
                    val s = currentContainer.surface ?: return@execute
                    if (!s.isValid) return@execute
                    currentSurface = s

                    try {
                        canvas = try {
                            s.lockHardwareCanvas()
                        } catch (hwEx: Exception) {
                            s.lockCanvas(null)
                        }
                    } catch (e: Exception) {
                        Log.w("NavScreen", "surface lock failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }

                if (canvas == null || currentSurface == null) {
                    redrawPending = false
                    return@execute
                }
                val targetCanvas: android.graphics.Canvas = canvas!!
                val activeSurface: android.view.Surface = currentSurface!!

                try {
                    val w = targetCanvas.width.toFloat()
                    val h = targetCanvas.height.toFloat()

                    // Interpolated GPS for smooth 60 FPS motion (Section 7.3)
                    val gps = interpolatedGps ?: NavStateManager.currentGps ?: latest?.gps
                    val dest = NavStateManager.activeDestination ?: latest?.destination
                    val route = NavStateManager.selectedRoute
                    val speedMs = if (NavStateManager.speedMs > 0.0) NavStateManager.speedMs else (latest?.speedMs ?: 0.0)
                    val speedLimitMph = if (NavStateManager.speedLimitMph > 0) NavStateManager.speedLimitMph else (latest?.let { (it.speedLimit.limitMs * MS_TO_MPH).roundToInt() } ?: 0)
                    val power = NavStateManager.latestPower ?: latest?.power
                    val commaIp = NavStateManager.commaIp ?: client.commaIp
                    val token = MapSourceChoice.effectiveMapboxToken(client.mapboxToken ?: getSavedMapboxToken())
                    // Reactive ambient day/night state from vehicle CAN bus / ThemeManager (Section 7.1)
                    val isNight = ThemeManager.isCarNightMode(carContext)

                    mapRenderer.render(
                        canvas = targetCanvas,
                        w = w,
                        h = h,
                        gps = gps,
                        dest = dest,
                        route = route,
                        speedMs = speedMs,
                        speedLimitMph = speedLimitMph,
                        power = power,
                        commaIp = commaIp,
                        token = token,
                        isNight = isNight,
                        zoom = currentZoom,
                        isNorthUp = isNorthUp,
                        onNeedRedraw = { drawMapOnSurface() }
                    )

                    // Only draw bottom RouteStrip if no native destination card is occluding it
                    if (route != null && dest == null) {
                        RouteStrip.draw(targetCanvas, w, h, route, gps, isNight, carContext.resources.displayMetrics.density)
                    }
                } finally {
                    synchronized(surfaceLock) {
                        if (activeSurface.isValid) {
                            try {
                                activeSurface.unlockCanvasAndPost(targetCanvas)
                            } catch (e: Exception) {
                                Log.w("NavScreen", "surface.unlockCanvasAndPost failed: ${e.message}")
                            }
                        }
                    }
                }
            } finally {
                isDrawing.set(false)
                if (redrawPending) {
                    redrawPending = false
                    mainHandler.postDelayed({ drawMapOnSurface() }, 80)
                }
            }
        }
    }

    private fun getSavedMapboxToken(): String? {
        return carContext.getSharedPreferences("comma_client", Context.MODE_PRIVATE)
            .getString("mapbox_token", null)
    }

    // ------------------------------------------------------------------------
    // Template generation
    // ------------------------------------------------------------------------

    /**
     * Builds a car-app Distance with the SAME unit rule as the phone
     * (DisplayUnits): feet under 528 ft, miles above — never raw meters,
     * which made the head unit show "1505.9 m" for a 0.9 mi leg.
     */
    private fun carDistance(meters: Double): Distance {
        val td = DisplayUnits.templateDistance(meters)
        return Distance.create(
            td.value,
            if (td.unit == DisplayUnits.TemplateUnit.FEET) Distance.UNIT_FEET else Distance.UNIT_MILES
        )
    }

    /**
     * True while the host has parked our navigation claim (another
     * app owns turn-by-turn). The comma route is untouched in this
     * state; see [stopNavigationFromHost].
     */
    private var hostStopped = false

    /**
     * NF-7 test-drive mode (Car App Quality): true once the host
     * enables auto drive for this session. While set, a chosen
     * destination is driven by [SimulationEngine] — the same
     * engine the phone's demo trips use — instead of waiting on
     * the comma. The callback has no disable counterpart; the
     * mode lasts for the session and the simulation stops with
     * this screen.
     */
    private var autoDriveEnabled = false

    /** An external place query waiting for this screen to be ready to push its search screen (NF-6). */
    private var pendingExternalQuery: String? = null

    /** True once this screen's lifecycle onCreate has run (the screen is attached and can push). */
    private var screenReady = false

    /**
     * Host-initiated stop (Param's fix, 2026-11-06): Android Auto
     * gives turn-by-turn to ONE app — when Google Maps starts
     * navigating, the host orders us to stop. That order used to
     * land in [stopNavigation], which clears the destination ON
     * THE COMMA: starting Maps killed the comma's route. The host
     * only owns the presentation claim, so this path releases
     * exactly that — navigationEnded() once, no comma clear, no
     * shared-state teardown, no feeder stop — and parks the claim
     * ([hostStopped]) so the state listener doesn't re-claim while
     * the other app navigates. The comma keeps its route; the
     * user's own End action still runs the confirmed clear.
     */
    private fun stopNavigationFromHost() {
        hostStopped = true
        if (isCarNavActive) {
            isCarNavActive = false
            try {
                val carNavManager = carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
                carNavManager.navigationEnded()
            } catch (e: Exception) {
                Log.w("NavScreen", "Failed to end car navigation on host stop: ${e.message}")
            }
        }
        drawMapOnSurface()
        try { invalidate() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
    }

    /**
     * NF-7: starts the test-drive simulation toward the active
     * destination, when auto drive is on and no simulation is
     * already running. The selected route's points drive it when
     * a route is on screen; otherwise the engine's default drive.
     */
    private fun maybeStartAutoDriveSimulation() {
        if (!autoDriveEnabled || SimulationEngine.isSimulating) return
        val dest = NavStateManager.activeDestination ?: return
        SimulationEngine.start(NavStateManager.selectedRoute?.points, dest)
    }

    /**
     * NF-6 (Car App Quality): navigation requests from other apps
     * and the Assistant reach the car session as intents, handed
     * here by the session. A coordinate destination is sent on
     * through the same send + queue path a search selection uses;
     * a place query opens the search screen with the search
     * already run — the treatment the phone gives an external
     * query. Links stay the phone's to resolve; the car never
     * expands them.
     */
    fun handleNavigationIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val target = when {
            intent.dataString != null ->
                SharedTextParser.parseNavigationUri(intent.dataString)
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT) != null ->
                SharedTextParser.parse(intent.getStringExtra(android.content.Intent.EXTRA_TEXT))
            else -> SharedTarget.Unreadable
        }
        when (target) {
            is SharedTarget.Coords -> sendExternalDestination(
                NavDestination(
                    name = target.label ?: "Shared location",
                    placeName = target.label ?: "",
                    latitude = target.latitude,
                    longitude = target.longitude,
                    source = "assistant"
                )
            )
            is SharedTarget.Query ->
                if (screenReady) pushSearchForQuery(target.text)
                else pendingExternalQuery = target.text
            else -> Unit
        }
    }

    /** Opens the car search with [query] already submitted. */
    private fun pushSearchForQuery(query: String) {
        val screen = SearchScreen(carContext, client)
        try {
            screenManager.push(screen)
            screen.onSearchSubmitted(query)
        } catch (e: Exception) {
            Log.w("NavScreen", "Failed to open search for external query: ${e.message}")
        }
    }

    /**
     * Sends an externally supplied coordinate destination (NF-6)
     * with the same bookkeeping as a search selection: the queued
     * destination exclusivity rule, the send itself, history,
     * the phone-GPS feeder session, and queue-on-failure.
     */
    private fun sendExternalDestination(dest: NavDestination) {
        val queuedNow = NavStateManager.queuedDestination
            ?: DestinationQueue.loadEntry(carContext)?.displayDestination()
        if (queuedNow != null && !queuedNow.name.equals(dest.name, ignoreCase = true)) {
            CarToast.makeText(
                carContext,
                "A destination is already queued — it must be sent or cancelled before choosing another",
                CarToast.LENGTH_LONG
            ).show()
            return
        }
        NavStateManager.markDestinationSelected(dest)
        val feederTarget = client.getEffectiveNavigationEndpoint()
        if (NavStateManager.usePhoneLocationToComma && !feederTarget.isNullOrBlank()) {
            PhoneGpsFeeder.start(feederTarget)
        }
        Thread {
            val target = client.getEffectiveNavigationEndpoint()
            val ok = if (NavStateManager.isSgGalaxy) {
                NavigationManager().sendToCommaSg(
                    SgGalaxyClient.baseUrlFor(target), dest, NavStateManager.sgRevision
                )
            } else {
                NavigationManager().sendToComma(target, dest, client.getGalaxyCookie())
            }
            mainHandler.post {
                if (ok) {
                    if (!target.isNullOrBlank()) {
                        PhoneGpsFeeder.startNavigationSession(target)
                    }
                    if (NavStateManager.queuedDestination?.isSameAs(dest) == true) {
                        DestinationQueue.clear(carContext)
                        NavStateManager.queuedDestination = null
                        NavStateManager.queuedOutOfSync = false
                    }
                    DestinationHistory.record(carContext, dest)
                    NavStateManager.activeDestination = dest
                    NavStateManager.setNavClearedByUser(false)
                    NavStateManager.activeTurnCue = "Head toward ${dest.name}"
                    NavStateManager.notifyChanged()
                    NavigationManager.startNavigationRefresh { client.getEffectiveNavigationEndpoint() }
                    try {
                        val carNavManager = carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
                        carNavManager.navigationStarted()
                    } catch (e: Exception) {
                        Log.w("NavScreen", "Failed to start car navigation: ${e.message}")
                    }
                    CarToast.makeText(carContext, "Navigating to ${dest.name}", CarToast.LENGTH_SHORT).show()
                } else {
                    PhoneGpsFeeder.stop()
                    val outOfSync = NavStateManager.commaFound
                    DestinationQueue.save(carContext, dest)
                    NavStateManager.queuedDestination = dest
                    NavStateManager.queuedOutOfSync = outOfSync
                    NavStateManager.notifyChanged()
                    CarToast.makeText(
                        carContext,
                        CarScreenNotice.capForCar(
                            DestinationQueue.label(dest, outOfSync, NavStateManager.commaNetworks)
                        ),
                        CarToast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun stopNavigation() {
        // Simulated trips have no comma-side navigation to confirm against:
        // keep the immediate local teardown for them.
        if (SimulationEngine.isSimulating) {
            teardownNavigationUi()
            return
        }
        // Device-confirmed End Route (Feature 3): the comma's clear runs
        // FIRST; local state, the car navigation session and the "ended"
        // toast happen only when the comma confirms. On failure the route
        // stays up and the error is surfaced instead.
        // End Route confidence (backlog #3): the car surface shows
        // "Ending route…" from the tap until the comma answers.
        NavStateManager.noteEndRouteStarted(NavStateManager.activeDestination?.name)
        val endpoint = client.commaIp ?: "192.168.68.9"
        val sg = NavStateManager.isSgGalaxy
        Thread {
            var sgOk = false
            var legacyOk = false
            if (sg) {
                sgOk = NavigationManager().clearCommaNavigationSg(
                    SgGalaxyClient.baseUrlFor(endpoint), NavStateManager.sgRevision
                )
                if (!sgOk) {
                    legacyOk = NavigationManager().clearCommaNavigation(endpoint, client.getGalaxyCookie())
                }
            } else {
                legacyOk = NavigationManager().clearCommaNavigation(endpoint, client.getGalaxyCookie())
            }
            val confirmed = EndRouteFlow.confirmed(sg, sgOk, legacyOk)
            mainHandler.post {
                if (confirmed) {
                    // The comma confirmed the clear: the surface shows
                    // "Route ended ✓" (a later no-destination snapshot
                    // re-confirms it; a snapshot still carrying the
                    // destination flips it to the stuck warning).
                    NavStateManager.noteEndRouteActionConfirmed()
                    teardownNavigationUi()
                } else {
                    // The clear failed: the route is still live, so the
                    // surface goes back to normal navigation.
                    NavStateManager.noteEndRouteFailed()
                    CarToast.makeText(
                        carContext,
                        EndRouteFlow.failureMessage(NavigationManager.lastSendError),
                        CarToast.LENGTH_LONG
                    ).show()
                    drawMapOnSurface()
                    try { invalidate() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                }
            }
        }.start()
    }

    /** Local teardown once the comma has confirmed the end of navigation. */
    private fun teardownNavigationUi() {
        // Navigation has ended: the phone-location feed stops with it.
        PhoneGpsFeeder.stop()
        NavigationManager.stopNavigationRefresh()
        NavStateManager.clear()
        try {
            val carNavManager = carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
            carNavManager.navigationEnded()
            isCarNavActive = false
            hostStopped = false
        } catch (e: Exception) {
            Log.w("NavScreen", "Failed to end car navigation: ${e.message}")
        }
        CarToast.makeText(carContext, "Navigation ended", CarToast.LENGTH_SHORT).show()
        mainHandler.post {
            drawMapOnSurface()
            try { invalidate() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private fun bookmarkHere() {
        val pkt = NavStateManager.latestPacket ?: latest
        val dest = NavStateManager.activeDestination
        val label = when {
            pkt?.nav?.valid == true && pkt.nav.street.isNotEmpty() -> pkt.nav.street
            dest != null -> "En route to ${dest.name}"
            NavStateManager.currentStreet?.isNotEmpty() == true -> NavStateManager.currentStreet!!
            else -> "Driving"
        }
        Bookmarks.add(carContext, label, NavStateManager.currentGps ?: pkt?.gps)
        CarToast.makeText(carContext, "Bookmarked: $label", CarToast.LENGTH_SHORT).show()
    }

    private fun startRoute(dest: NavDestination) {
        // Queue lock (Param, 2026-11-21): while a destination is
        // queued, no NEW destination is accepted — the queued one
        // must be sent or cancelled first.
        val queuedNow = NavStateManager.queuedDestination
            ?: DestinationQueue.loadEntry(carContext)?.displayDestination()
        if (DestinationQueue.blocksNewDestination(queuedNow, dest)) {
            CarToast.makeText(
                carContext,
                "A destination is already queued — it must be sent or cancelled before choosing another",
                CarToast.LENGTH_LONG
            ).show()
            return
        }
        NavStateManager.markDestinationSelected(dest)
        val feederTarget = client.getEffectiveNavigationEndpoint()
        if (NavStateManager.usePhoneLocationToComma && !feederTarget.isNullOrBlank()) {
            PhoneGpsFeeder.start(feederTarget)
        }
        Thread {
            val target = client.getEffectiveNavigationEndpoint()
            val sendOk = if (NavStateManager.isSgGalaxy) {
                NavigationManager().sendToCommaSg(
                    SgGalaxyClient.baseUrlFor(target), dest, NavStateManager.sgRevision
                )
            } else {
                val cookie = client.getGalaxyCookie()
                NavigationManager().sendToComma(target, dest, cookie)
            }
            mainHandler.post {
                if (sendOk) {
                    // A live send of the queued destination itself
                    // clears the queue — it is no longer waiting.
                    if (NavStateManager.queuedDestination?.isSameAs(dest) == true) {
                        DestinationQueue.clear(carContext)
                        NavStateManager.queuedDestination = null
                        NavStateManager.queuedOutOfSync = false
                    }
                    // Confirmed: the entry feed becomes a navigation
                    // session and runs until navigation ends (Param,
                    // 2026-11-03).
                    if (!feederTarget.isNullOrBlank()) PhoneGpsFeeder.startNavigationSession(feederTarget)
                    DestinationHistory.record(carContext, dest)
                    NavStateManager.activeDestination = dest
                    NavStateManager.setNavClearedByUser(false)
                    NavStateManager.activeTurnCue = "Head toward ${dest.name}"
                    NavigationManager.startNavigationRefresh { client.getEffectiveNavigationEndpoint() }
                    try {
                        val carNavManager = carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
                        carNavManager.navigationStarted()
                        isCarNavActive = true
                        hostStopped = false
                    } catch (e: Exception) {
                        Log.w("NavScreen", "Failed to start car navigation: ${e.message}")
                    }
                    NavStateManager.notifyChanged()
                    CarToast.makeText(carContext, "Navigating to ${dest.name}", CarToast.LENGTH_SHORT).show()
                    drawMapOnSurface()
                    try { invalidate() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                } else {
                    PhoneGpsFeeder.stop()
                    // Addendum 122/123: queue on ANY send failure —
                    // offline, or connected but not accepted (out of
                    // sync). The flush retries every 15 s while the
                    // comma is connected.
                    val outOfSync = NavStateManager.commaFound
                    val queued = dest.hasCoordinates && run {
                        DestinationQueue.save(carContext, dest)
                        NavStateManager.queuedDestination = dest
                        NavStateManager.queuedOutOfSync = outOfSync
                        NavStateManager.notifyChanged()
                        true
                    }
                    if (queued) {
                        CarToast.makeText(
                            carContext,
                            DestinationQueue.label(dest, outOfSync, NavStateManager.commaNetworks),
                            CarToast.LENGTH_LONG
                        ).show()
                    } else {
                        CarToast.makeText(
                            carContext,
                            "Destination NOT accepted by the comma — ${NavigationManager.lastSendError ?: "not connected"}",
                            CarToast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            // Phone-side routing removed: AA displays the comma's own route
            // from the SG snapshot (NavStateManager.activeRoutes).
        }.start()
    }

    override fun onGetTemplate(): Template {
        if (!usageCountersInitialized) {
            usageCountersInitialized = true
            try {
                UsageCounters.init(carContext)
            } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
        return try {
            buildNavTemplate()
        } catch (e: Exception) {
            Log.e("NavScreen", "Failed to build navigation template: ${e.message}", e)
            val fallbackStrip = ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(searchIcon)
                        .setTitle("Search")
                        .setOnClickListener {
                            screenManager.push(SearchScreen(carContext, client))
                        }
                        .build()
                )
                .build()
            val fallbackMapStrip = ActionStrip.Builder()
                .addAction(Action.Builder().setIcon(zoomInIcon).setOnClickListener { currentZoom = (currentZoom + 1.0).coerceAtMost(18.0); drawMapOnSurface() }.build())
                .addAction(Action.Builder().setIcon(zoomOutIcon).setOnClickListener { currentZoom = (currentZoom - 1.0).coerceAtLeast(10.0); drawMapOnSurface() }.build())
                .addAction(Action.Builder().setIcon(recenterIcon).setOnClickListener { recenter() }.build())
                .addAction(Action.Builder().setIcon(compassIcon).setOnClickListener { isNorthUp = !isNorthUp; drawMapOnSurface() }.build())
                .build()
            NavigationTemplate.Builder()
                .setActionStrip(fallbackStrip)
                .setMapActionStrip(fallbackMapStrip)
                .build()
        }
    }

    /** The current car-screen notice text (Addendum 142/143),
     * decided in layer 2 ([CarScreenNotice]) from shared state. */
    private fun currentNoticeText(): String? = CarScreenNotice.navNotice(
        queued = NavStateManager.queuedDestination,
        outOfSync = NavStateManager.queuedOutOfSync,
        networks = NavStateManager.commaNetworks,
        hasActiveRoute = NavStateManager.activeDestination != null,
        scenario = ConnectivityMatrix.current()
    )

    private fun buildNavTemplate(): Template {
        val pkt = NavStateManager.latestPacket ?: latest
        val dest = NavStateManager.activeDestination
        val route = NavStateManager.selectedRoute
        val nav = pkt?.nav ?: NavManeuver()

        val mapActionStrip = buildMapActionStrip()
        val searchAction = buildSearchAction()
        val homeAction = buildHomeAction()
        val workAction = buildWorkAction()

        val hasNav = dest != null
        val templateBuilder = NavigationTemplate.Builder()
            .setMapActionStrip(mapActionStrip)

        if (!hasNav) {
            val actionStrip = ActionStrip.Builder()
                .addAction(searchAction)
                .addAction(homeAction)
                .addAction(workAction)
                .build()

            // Addendum 142: when navigation cannot work right now
            // (a destination is queued, or the phone has no
            // internet), the idle template carries a MessageInfo
            // card saying so — until now this screen said nothing
            // and a failed send looked like nothing had happened.
            val notice = currentNoticeText()
            return templateBuilder
                .apply {
                    if (notice != null) {
                        setNavigationInfo(MessageInfo.Builder(CarText.create(notice)).build())
                    }
                }
                .setActionStrip(actionStrip)
                .build()
        }

        // ==========================================
        // Active Navigation Mode: RoutingInfo & ETA
        // ==========================================

        val effectiveNav = NavStateManager.getEffectiveManeuver(pkt)

        val targetRoad = effectiveNav.street.ifEmpty { dest?.name ?: "Route" }
        val step = buildCurrentStep(effectiveNav, nav, dest, targetRoad)

        // Distance/ETA decisions live in GuidanceMath (layer 2,
        // 3-layer refactor): this layer only gathers the state,
        // translates its result into car-app objects, and builds.
        val rawSpeedMs = pkt?.speedMs ?: NavStateManager.speedMs
        val progressGps = NavStateManager.currentGps ?: pkt?.gps
        val progress = if (route != null && progressGps != null) {
            RouteProgress.compute(route.points, progressGps.latitude, progressGps.longitude)
        } else {
            null
        }
        val guidance = GuidanceMath.compute(
            GuidanceMath.Input.fromState(
                effNav = effectiveNav,
                nav = nav,
                route = route,
                dest = dest,
                gps = progressGps,
                rawSpeedMs = rawSpeedMs,
                progress = progress
            )
        )
        fun guidanceDistance(meters: Double?, miles: Double?): Distance? = when {
            meters != null -> carDistance(meters)
            miles != null -> Distance.create(miles, Distance.UNIT_MILES)
            else -> null
        }
        val distance: Distance? =
            guidanceDistance(guidance.stepDistanceM, guidance.stepDistanceMiles)
        val totalDistance: Distance? =
            guidanceDistance(guidance.totalDistanceM, guidance.totalDistanceMiles)
        val totalEtaSeconds: Long? = guidance.totalEtaSeconds
        // The destination TravelEstimate exists only when a real distance
        // AND a real ETA back it; otherwise the template carries none.
        val estimate: TravelEstimate? = if (totalDistance != null && totalEtaSeconds != null) {
            TravelEstimate.Builder(totalDistance, ZonedDateTime.now().plusSeconds(totalEtaSeconds))
                .setRemainingTimeSeconds(totalEtaSeconds)
                .setTripText(CarText.create(dest?.name ?: ""))
                .build()
        } else {
            null
        }

        // RoutingInfo exists only when a real step distance backs it.
        // The loading state is never used: the head unit draws a black
        // loading card over our own surface maneuver card whenever the
        // template reports loading (Param's photo, 2026-11-04), while
        // the surface card already shows the real instruction. With no
        // distance, the template simply carries no navigation info.
        val routingInfo = if (distance != null) {
            RoutingInfo.Builder().setCurrentStep(step, distance).build()
        } else {
            null
        }

        val bookmarkAction = Action.Builder()
            .setIcon(bookmarkIcon)
            .setTitle("Bookmark")
            .setOnClickListener { bookmarkHere() }
            .build()

        val endAction = Action.Builder()
            .setIcon(endIcon)
            .setOnClickListener { stopNavigation() }
            .build()

        val actionStripBuilder = ActionStrip.Builder()
            .addAction(searchAction)

        val altRoutes = NavStateManager.activeRoutes
        if (altRoutes.size > 1) {
            val currentSelId = NavStateManager.selectedRouteId
            val nextRoute = altRoutes.firstOrNull { it.id != currentSelId } ?: altRoutes[0]
            val diff = nextRoute.durationMin - (route?.durationMin ?: nextRoute.durationMin)
            val diffStr = when {
                diff > 0 -> "+$diff m"
                diff < 0 -> "$diff m"
                else -> "${nextRoute.durationMin}m"
            }
            actionStripBuilder.addAction(
                Action.Builder()
                    .setTitle("Alt ($diffStr)")
                    .setOnClickListener {
                        NavStateManager.selectedRouteId = nextRoute.id
                        NavStateManager.notifyChanged()
                        val ip = NavStateManager.commaIp ?: client.commaIp
                        if (!ip.isNullOrBlank()) {
                            Thread {
                                NavigationManager().selectRouteSg(
                                    SgGalaxyClient.baseUrlFor(ip),
                                    nextRoute.id,
                                    NavStateManager.sgRevision
                                )
                            }.start()
                        }
                        invalidate()
                    }
                    .build()
            )
        }

        actionStripBuilder.addAction(bookmarkAction)
        actionStripBuilder.addAction(endAction)

        val actionStrip = actionStripBuilder.build()

        // Push active trip guidance to Android Auto's native navigation HUD and instrument cluster.
        // While the host has parked our claim (NF-5: another app is
        // guiding), trip updates stop too — the callback contract
        // requires it, and this screen's own invalidate() on host
        // stop must not re-push the trip it just ended.
        if (dest != null && !hostStopped) {
            pushTripToCarNavManager(dest, targetRoad, estimate, distance, step, guidance.stepDurationSeconds)
        }

        return templateBuilder
            .apply {
                if (routingInfo != null) setNavigationInfo(routingInfo)
                // No destination travel estimate when nothing real backs it.
                if (estimate != null) setDestinationTravelEstimate(estimate)
            }
            .setActionStrip(actionStrip)
            .build()
    }


    /**
     * buildNavTemplate, map action strip (extracted 2026-11-08):
     * the always-present zoom / recenter / compass controls.
     */
    private fun buildMapActionStrip(): ActionStrip {
        val zoomInAction = Action.Builder()
            .setIcon(zoomInIcon)
            .setOnClickListener {
                currentZoom = (currentZoom + 1.0).coerceAtMost(18.0)
                drawMapOnSurface()
            }
            .build()

        val zoomOutAction = Action.Builder()
            .setIcon(zoomOutIcon)
            .setOnClickListener {
                currentZoom = (currentZoom - 1.0).coerceAtLeast(10.0)
                drawMapOnSurface()
            }
            .build()

        val recenterAction = Action.Builder()
            .setIcon(recenterIcon)
            .setOnClickListener {
                recenter()
            }
            .build()

        val compassAction = Action.Builder()
            .setIcon(compassIcon)
            .setOnClickListener {
                isNorthUp = !isNorthUp
                drawMapOnSurface()
                CarToast.makeText(carContext, if (isNorthUp) "Map: North Up" else "Map: Heading Up", CarToast.LENGTH_SHORT).show()
            }
            .build()

        return ActionStrip.Builder()
            .addAction(zoomInAction)
            .addAction(zoomOutAction)
            .addAction(recenterAction)
            .addAction(compassAction)
            .build()
    }

    /** buildNavTemplate, Search action (extracted 2026-11-08). */
    private fun buildSearchAction(): Action = Action.Builder()
        .setIcon(searchIcon)
        .setTitle("Search")
        .setOnClickListener {
            screenManager.push(SearchScreen(carContext, client))
        }
        .build()

    /** buildNavTemplate, Home action (extracted 2026-11-08). User-configured Home lives in Bookmarks. */
    private fun buildHomeAction(): Action = Action.Builder()
        .setIcon(homeIcon)
        .setTitle("Home")
        .setOnClickListener {
            val currentHome = Bookmarks.getHome(carContext)
            if (currentHome != null) {
                startRoute(currentHome)
            } else {
                CarToast.makeText(carContext, "Set Home in ParamAuto on your phone", CarToast.LENGTH_SHORT).show()
            }
        }
        .build()

    /** buildNavTemplate, Work action (extracted 2026-11-08). User-configured Office lives in Bookmarks. */
    private fun buildWorkAction(): Action = Action.Builder()
        .setIcon(workIcon)
        .setTitle("Work")
        .setOnClickListener {
            val currentOffice = Bookmarks.getOffice(carContext)
            if (currentOffice != null) {
                startRoute(currentOffice)
            } else {
                CarToast.makeText(carContext, "Set Work in ParamAuto on your phone", CarToast.LENGTH_SHORT).show()
            }
        }
        .build()

    /**
     * buildNavTemplate, current step (extracted 2026-11-08): the
     * maneuver + cue + lane strip for the RoutingInfo, from the
     * effective maneuver (lanes prefer the raw packet's when it
     * carries them).
     */
    private fun buildCurrentStep(effectiveNav: NavManeuver, nav: NavManeuver, dest: NavDestination?, targetRoad: String): Step {
        val stepManeuver = Maneuver.Builder(maneuverTypeFor(effectiveNav.modifier, effectiveNav.type)).build()
        val stepRoad = dest?.placeName?.takeIf { it.isNotEmpty() && it != targetRoad } ?: ""
        return Step.Builder()
            .setManeuver(stepManeuver)
            .setCue(targetRoad)
            .apply {
                if (stepRoad.isNotEmpty()) setRoad(stepRoad)
                val lanesToDraw = if (nav.valid && nav.lanes.isNotEmpty()) nav.lanes else effectiveNav.lanes
                for (lane in lanesToDraw) {
                    val builder = Lane.Builder()
                    for (dir in lane.directions) {
                        builder.addDirection(
                            LaneDirection.create(
                                laneShapeFor(dir),
                                isRecommended(lane.directions, effectiveNav.modifier)
                            )
                        )
                    }
                    addLane(builder.build())
                }
            }
            .build()
    }

    /**
     * buildNavTemplate, trip push (extracted 2026-11-08): mirrors
     * the active trip into Android Auto's native navigation HUD
     * and instrument cluster. Only estimates backed by real data
     * are attached — no invented destination estimate (the choice
     * itself is GuidanceMath's, layer 2).
     */
    private fun pushTripToCarNavManager(
        dest: NavDestination,
        targetRoad: String,
        estimate: TravelEstimate?,
        distance: Distance?,
        step: Step,
        stepDurationSec: Long?
    ) {
        try {
            val carNavManager = carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
            val tripDest = Destination.Builder()
                .setName(dest.name)
                .setAddress(dest.placeName.ifEmpty { dest.name })
                .build()

            val tripBuilder = Trip.Builder()
                .setCurrentRoad(targetRoad)
            if (estimate != null) {
                tripBuilder.addDestination(tripDest, estimate)
            }
            if (distance != null && stepDurationSec != null) {
                val stepEstimate = TravelEstimate.Builder(distance, ZonedDateTime.now().plusSeconds(stepDurationSec))
                    .setRemainingTimeSeconds(stepDurationSec)
                    .build()
                tripBuilder.addStep(step, stepEstimate)
            }
            val trip = tripBuilder.build()

            carNavManager.updateTrip(trip)
        } catch (e: Exception) {
            Log.w("NavScreen", "Failed to updateTrip on Car NavigationManager: ${e.message}")
        }
    }

    private fun laneCue(nav: NavManeuver): String {
        if (nav.laneCount <= 0) return ""
        val mine = if (nav.activeLaneIndex in 0 until nav.laneCount) {
            "Lane ${nav.activeLaneIndex + 1} of ${nav.laneCount}"
        } else {
            "${nav.laneCount} lanes"
        }
        val need = when (nav.modifier) {
            "left", "slightLeft", "sharpLeft" -> " · keep left"
            "right", "slightRight", "sharpRight" -> " · keep right"
            else -> ""
        }
        return mine + need
    }

    private fun isRecommended(directions: List<String>, modifier: String): Boolean {
        if (modifier.isEmpty()) return false
        val family = when (modifier) {
            "left", "slightLeft", "sharpLeft" -> setOf("slightLeft", "left", "sharpLeft")
            "right", "slightRight", "sharpRight" -> setOf("slightRight", "right", "sharpRight")
            "straight" -> setOf("straight")
            else -> return false
        }
        return directions.any { it in family }
    }

    private fun maneuverTypeFor(modifier: String, type: String = ""): Int {
        val mod = modifier.lowercase().trim()
        val t = type.lowercase().trim()

        if (t == "arrive" || mod == "arrive") return Maneuver.TYPE_DESTINATION
        if (t == "roundabout" || t == "rotary") {
            return if (mod.contains("left")) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE
            else Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE
        }
        if (t == "fork") {
            return if (mod.contains("left")) Maneuver.TYPE_FORK_LEFT else Maneuver.TYPE_FORK_RIGHT
        }
        if (t == "merge") {
            return if (mod.contains("left")) Maneuver.TYPE_MERGE_LEFT else if (mod.contains("right")) Maneuver.TYPE_MERGE_RIGHT else Maneuver.TYPE_STRAIGHT
        }
        if (t == "on ramp" || t == "onramp") {
            return if (mod.contains("left")) Maneuver.TYPE_ON_RAMP_NORMAL_LEFT else Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
        }
        if (t == "off ramp" || t == "offramp") {
            return if (mod.contains("left")) Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT else Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        }

        val norm = (mod + " " + t).replace("-", " ").replace("_", " ")
        return when {
            norm.contains("sharp right") || norm.contains("sharpright") -> Maneuver.TYPE_TURN_SHARP_RIGHT
            norm.contains("slight right") || norm.contains("slightright") -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            norm.contains("right") -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            norm.contains("sharp left") || norm.contains("sharpleft") -> Maneuver.TYPE_TURN_SHARP_LEFT
            norm.contains("slight left") || norm.contains("slightleft") -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            norm.contains("left") -> Maneuver.TYPE_TURN_NORMAL_LEFT
            norm.contains("u turn") || norm.contains("uturn") -> Maneuver.TYPE_U_TURN_LEFT
            norm.contains("straight") -> Maneuver.TYPE_STRAIGHT
            else -> Maneuver.TYPE_STRAIGHT
        }
    }

    private fun laneShapeFor(direction: String): Int = when (direction) {
        "straight" -> LaneDirection.SHAPE_STRAIGHT
        "slightLeft" -> LaneDirection.SHAPE_SLIGHT_LEFT
        "left" -> LaneDirection.SHAPE_NORMAL_LEFT
        "sharpLeft" -> LaneDirection.SHAPE_SHARP_LEFT
        "slightRight" -> LaneDirection.SHAPE_SLIGHT_RIGHT
        "right" -> LaneDirection.SHAPE_NORMAL_RIGHT
        "sharpRight" -> LaneDirection.SHAPE_SHARP_RIGHT
        "uturn", "uTurn" -> LaneDirection.SHAPE_U_TURN_LEFT
        else -> LaneDirection.SHAPE_UNKNOWN
    }


    companion object {
        private const val DEFAULT_ZOOM = 16.0
        private const val MS_TO_MPH = Units.MS_TO_MPH
        private const val STALE_MS = 6000L
        private val geocodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "car-geocoder").apply { isDaemon = true }
        }
    }
}
