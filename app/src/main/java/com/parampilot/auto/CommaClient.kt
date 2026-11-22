package com.parampilot.auto

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Communicates with the Comma 3 / 3X / StarPilot Galaxy HTTP API on port 8082.
 * Uses NetworkScanner for IP discovery and local Galaxy endpoints for telemetry.
 */
class CommaClient(private val context: Context) {

    fun interface PacketListener {
        fun onPacket(packet: TelemetryPacket)
    }

    fun interface DiagnosticListener {
        fun onDiagnostic(message: String)
    }

    private val packetListeners = java.util.concurrent.CopyOnWriteArrayList<PacketListener>()
    private var diagnosticListener: DiagnosticListener? = null

    /** Most recent connection diagnostic, kept for the health screen (Feature 4). */
    @Volatile
    var lastDiagnostic: String? = null
        private set

    @Volatile
    var lastDiagnosticAtMs: Long = 0L
        private set
    private val running = AtomicBoolean(false)

    /**
     * Poll-loop generation (bug fix, 2026-11-08): stop() can only
     * join the poll thread for 1 s, but one SG fetch cycle can run
     * several seconds, so a stopped thread can outlive the join.
     * Without a generation check, a later start() flipping
     * [running] back to true made the stale thread loop FOREVER
     * alongside the new one — two poll loops dueling over the
     * transport. start() and stop() both bump the generation; a
     * thread whose generation is stale exits at its loop head.
     */
    @Volatile private var loopGeneration = 0
    private var thread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeClients: Int = 0
    @Volatile private var failures = 0

    // Recovery-attempt backoff (audit finding 6, FailureBackoff):
    // failover / startup-sequence attempts from the poll's failure
    // path space out to a 60 s cap instead of re-firing every cycle
    // for hours while the comma sleeps.
    @Volatile private var recoveryAttempts = 0
    @Volatile private var lastRecoveryAttemptMs = 0L

    private fun recoveryBackoffAllows(): Boolean {
        val now = System.currentTimeMillis()
        return if (FailureBackoff.shouldAttempt(now, lastRecoveryAttemptMs, recoveryAttempts)) {
            recoveryAttempts++
            lastRecoveryAttemptMs = now
            true
        } else {
            false
        }
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun attach(): CommaClient {
        synchronized(this) {
            activeClients++
            start()
        }
        return this
    }

    fun detach() {
        synchronized(this) {
            activeClients = (activeClients - 1).coerceAtLeast(0)
            if (activeClients == 0) {
                stop()
            }
        }
    }

    private val scanner = NetworkScanner(
        context = context,
        isRunning = { running.get() },
        onDiagnostic = { notifyDiagnostic(it) }
    )

    private val simListener = SimulationEngine.SimListener { packet ->
        if (SimulationEngine.isSimulating) {
            commaFound = true
            mainHandler.post {
                for (l in packetListeners) {
                    try { l.onPacket(packet) } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                }
            }
        }
    }

    @Volatile
    var commaFound: Boolean = false
        private set

    @Volatile
    var commaIp: String? = null
        private set

    @Volatile
    var isGalaxyCloud: Boolean = false
        private set

    @Volatile
    var mapboxToken: String? = null
        private set

    /** The phone's own Google API key (Param, 2026-11-18), mirroring [mapboxToken]. */
    var googleApiKey: String? = null
        private set

    // ---- SecretGoodStarPilot typed-API mode ----
    // Mode is probed per endpoint (GET /api/navigation/status â†’ 200 means SG)
    // and cached; the cache entry is dropped when the connection is lost so
    // rediscovery re-probes. Galaxy Cloud endpoints count as SG only when a
    // Galaxy password is saved (session sign-in via GalaxyCloudSession).
    private val galaxyModeCache = mutableMapOf<String, Boolean>()
    private var sgClient: SgGalaxyClient? = null
    private var sgClientBase: String? = null
    private var sgPollCount = 0
    private var lastSgDeviceStateFetchMs = 0L

    // Connection journal: heartbeat outage tracking (transitions
    // only — a lost beat and the recovery, never per-beat lines).
    private var heartbeatLostSinceMs = 0L
    private var heartbeatMissedBeats = 0
    private var lastSlcFetchMs = 0L
    private var lastSlowPollMs = 0L
    private var slcNullStreak = 0
    private var lastSgDeviceState: String? = null
    private var pendingProbeNavStatus: SgGalaxyClient.NavStatusResponse? = null
    private var pendingProbeEndpoint: String? = null
    private var sgRoutePublished = false
    private var cloudSgNoticeShown = false
    private var cloudAuthNoticeShown = false

    init {
        GalaxyCloudSession.init(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mapboxToken = prefs.getString(KEY_MAPBOX_TOKEN, null)
        googleApiKey = prefs.getString(KEY_GOOGLE_API_KEY, null)
        NavStateManager.googleApiKey = googleApiKey
        NavStateManager.usePhoneGoogleKey = prefs.getBoolean(KEY_USE_PHONE_GOOGLE_KEY, false)
        NavStateManager.phoneMapSource = PhoneGoogle.sanitizeMapSource(
            prefs.getString(KEY_PHONE_MAP_SOURCE, null)
        )
        NavStateManager.usePhoneSpeed = prefs.getBoolean(KEY_USE_PHONE_SPEED, false)
        NavStateManager.usePhoneLocation = prefs.getBoolean(KEY_USE_PHONE_LOCATION, false)
        NavStateManager.usePhoneLocationToComma = prefs.getBoolean(KEY_USE_PHONE_LOCATION_TO_COMMA, false)
        NavStateManager.usePhoneMapboxKey = prefs.getBoolean(KEY_USE_PHONE_MAPBOX_KEY, false)
        NavStateManager.usePhoneSpeedLimit = prefs.getBoolean(KEY_USE_PHONE_SPEED_LIMIT, false)
        NavStateManager.usePhoneRoute = prefs.getBoolean(KEY_USE_PHONE_ROUTE, false)
        NavStateManager.usePhoneSearch = prefs.getBoolean(KEY_USE_PHONE_SEARCH, false)
        NavStateManager.usePhone3d = prefs.getBoolean(KEY_USE_PHONE_3D, false)
        NavStateManager.usePhoneTraffic = prefs.getBoolean(KEY_USE_PHONE_TRAFFIC, false)
        NavStateManager.usePhoneSignals = prefs.getBoolean(KEY_USE_PHONE_SIGNALS, false)
        NavStateManager.usePhoneStops = prefs.getBoolean(KEY_USE_PHONE_STOPS, false)
        NavStateManager.usePhoneVectorMap = prefs.getBoolean(KEY_USE_PHONE_VECTOR_MAP, false)
        NavStateManager.connectionMode = getConnectionMode()
        SimulationEngine.addListener(simListener)
    }

    @Volatile
    private var gpsSpeedMs: Double = 0.0

    @Volatile
    private var lastPhoneGps: GpsPosition? = null

    private var locationManager: LocationManager? = null

    @Volatile
    private var gpsRunning = false

    /**
     * Phone GPS is Phone Data (audit finding 7): this client's
     * listener may run ONLY while a phone-location toggle is on —
     * "Use phone location" (map position) or "Phone location ->
     * Comma" (the feed). Before the fix it registered 500 ms
     * updates on GPS+NETWORK+FUSED at every start, toggles or not,
     * and wrote every fix into shared state.
     */
    private fun phoneGpsAllowed(): Boolean =
        (NavStateManager.usePhoneLocation || NavStateManager.usePhoneLocationToComma) &&
            HeartbeatTransport.phoneDataAllowed(NavStateManager.activeTransport)

    private fun ensureGpsGate() {
        val want = phoneGpsAllowed()
        if (want && (!gpsRunning || locationManager == null)) {
            startGps()
            gpsRunning = true
        } else if (!want && gpsRunning) {
            try {
                locationManager?.removeUpdates(locationListener)
            } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            gpsRunning = false
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!phoneGpsAllowed()) return
            if (location.hasSpeed()) gpsSpeedMs = location.speed.toDouble()
            val gps = GpsPosition(
                latitude = location.latitude,
                longitude = location.longitude,
                bearing = if (location.hasBearing()) location.bearing.toDouble() else (lastPhoneGps?.bearing ?: 0.0),
                speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
                hasFix = true
            )
            lastPhoneGps = gps
            NavStateManager.updateGps(gps)
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun setListener(listener: PacketListener?) {
        packetListeners.clear()
        if (listener != null) packetListeners.add(listener)
    }

    fun addListener(listener: PacketListener) {
        if (!packetListeners.contains(listener)) packetListeners.add(listener)
    }

    fun removeListener(listener: PacketListener) {
        packetListeners.remove(listener)
    }

    fun setDiagnosticListener(listener: DiagnosticListener?) {
        this.diagnosticListener = listener
    }

    fun setManualIp(ip: String) {
        val trimmed = ip.trim()
        val clean = if (trimmed.contains("galaxy.firestar.link")) {
            // Galaxy links are device-specific: the slug path routes to one
            // comma. Keep the FULL URL — collapsing to the bare host loses
            // the device and the generic host cannot log in to it.
            val withScheme = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
            withScheme.removeSuffix("/")
        } else {
            trimmed.removePrefix("http://").removePrefix("https://").removeSuffix("/").split(":")[0]
        }
        Log.i(TAG, "Manual endpoint set to: $clean")
        setCloudPinnedByUser(clean.contains("galaxy.firestar.link"))
        if (clean.contains("galaxy.firestar.link")) saveGalaxyCloudBase(clean)
        if (clean.isNotEmpty()) {
            saveIp(clean)
            if (clean.contains("galaxy.firestar.link")) {
                commaFound = true
                NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
            } else {
                NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
            }
        } else {
            commaIp = null
            setCloudPinnedByUser(false)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_IP).apply()
            NavStateManager.setActiveTransport(NavStateManager.TransportType.NONE)
            triggerStartupConnectionSequence()
        }
        thread?.interrupt()
    }

    fun saveIp(ip: String) {
        commaIp = ip
        isGalaxyCloud = ip.contains("galaxy.firestar.link")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_IP, ip).apply()
        NavStateManager.updateConnection(ip, true, isGalaxyCloud)
        if (isGalaxyCloud) {
            NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
        } else {
            NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
        }
    }

    fun getSavedIp(): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_IP, null)
    }

    fun getUserLocalIp(): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_USER_LOCAL_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
    }

    fun saveUserLocalIp(ip: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (ip.isNullOrBlank() || ip.contains("galaxy.firestar.link")) {
            prefs.edit().remove(KEY_USER_LOCAL_IP).apply()
        } else {
            prefs.edit().putString(KEY_USER_LOCAL_IP, ip.trim()).apply()
        }
    }

    fun getLastScannedIp(): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_SCANNED_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
            ?: prefs.getString(KEY_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
    }

    fun saveLastScannedIp(ip: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!ip.isNullOrBlank() && !ip.contains("galaxy.firestar.link")) {
            prefs.edit().putString(KEY_LAST_SCANNED_IP, ip.trim()).apply()
        }
    }

    /**
     * The Galaxy Cloud base URL for THIS comma. Firestar links are
     * device-specific (the slug path routes to a single comma), so this
     * returns the full stored URL — from the dedicated pref first, then a
     * firestar value saved as the endpoint — and only falls back to the
     * bare generic host when no device URL was ever entered.
     */
    fun getGalaxyCloudBase(): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dedicated = prefs.getString(KEY_GALAXY_URL, null)
        if (!dedicated.isNullOrBlank()) return dedicated.removeSuffix("/")
        val saved = prefs.getString(KEY_IP, null)
        if (saved != null && saved.contains("galaxy.firestar.link")) return saved.removeSuffix("/")
        return DEFAULT_GALAXY_URL
    }

    fun saveGalaxyCloudBase(fullUrl: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GALAXY_URL, fullUrl.removeSuffix("/")).apply()
    }

    /**
     * True when a device-specific Galaxy link is stored — fetched from
     * the comma over a Local connection or entered by the user — as
     * opposed to the bare generic host [getGalaxyCloudBase] falls back
     * to when no link was ever configured.
     */
    fun hasDedicatedGalaxyUrl(): Boolean = getGalaxyCloudBase() != DEFAULT_GALAXY_URL

    /**
     * True when Galaxy Cloud may be attempted at all (Param,
     * 2026-11-04): the device link is configured AND the Galaxy
     * password is saved. Trying Cloud before that is no use.
     */
    fun isCloudConfigured(): Boolean =
        TransportReadiness.cloudReady(hasDedicatedGalaxyUrl(), GalaxyCloudSession.hasPassword())

    /**
     * "User can use scan to configure Galaxy" (Param, 2026-11-04):
     * scans the local network on a background thread; when a comma
     * answers, fetches its Galaxy link from it and saves it, so Cloud
     * becomes configured (the password is still the user's to add).
     * The callback fires on the main thread with (configured, message)
     * — the message states exactly what happened, success or not.
     */
    fun configureGalaxyFromScan(callback: (Boolean, String) -> Unit) {
        Thread({
            val savedIp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
            val found = scanner.discover(savedIp)
            val result: Pair<Boolean, String> = when {
                found == null -> false to
                    "No comma found on the local network — Galaxy could not be configured. " +
                    "Check the comma is awake and on this Wi-Fi, then scan again."
                else -> {
                    val url = try {
                        LanPairing.fetchGalaxyStatusUrl(found)
                    } catch (_: Exception) {
                        null
                    }
                    if (!url.isNullOrBlank()) {
                        saveGalaxyCloudBase(url)
                        true to "Galaxy configured from your comma at $found — link saved." +
                            if (!GalaxyCloudSession.hasPassword()) {
                                " Cloud pairs automatically the next time your phone is on the comma's local network."
                            } else {
                                ""
                            }
                    } else {
                        false to "Found your comma at $found, but it did not report a Galaxy link — Cloud stays unconfigured."
                    }
                }
            }
            mainHandler.post { callback(result.first, result.second) }
        }, "comma-galaxy-scan").start()
    }

    /**
     * True only when the user explicitly pinned the Galaxy Cloud endpoint
     * (Settings endpoint field or setManualIp). An auto-fallback save of the
     * cloud URL does NOT set this: Galaxy Cloud is a fallback, so a saved
     * cloud URL must never suppress LAN discovery on later launches.
     */
    fun isCloudPinnedByUser(): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLOUD_MANUAL, false)
    }

    private fun setCloudPinnedByUser(pinned: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CLOUD_MANUAL, pinned).apply()
    }

    fun getConnectionMode(): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONNECTION_MODE, null)
        return saved ?: MODE_AUTO
    }

    fun setConnectionMode(mode: String, customLocalIp: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONNECTION_MODE, mode).apply()
        NavStateManager.connectionMode = mode

        when (mode) {
            MODE_AUTO -> {
                setCloudPinnedByUser(false)
                setManualIp("")
                if (BleBridgeClient.hasPairingKey(context)) {
                    BleBridgeClient.startIfPaired(context)
                }
                triggerStartupConnectionSequence()
                thread?.interrupt()
            }
            MODE_BLUETOOTH -> {
                setCloudPinnedByUser(false)
                isGalaxyCloud = false
                commaIp = "127.0.0.1"
                NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
                if (BleBridgeClient.hasPairingKey(context)) {
                    BleBridgeClient.startIfPaired(context)
                    Thread {
                        try {
                            val ble = BleBridgeClient.getSharedClient(context)
                            ble.connect(timeoutMs = 10000)
                            commaFound = ble.isConnected()
                        } catch (e: Exception) {
                            Log.w(TAG, "Bluetooth connect error: ${e.message}")
                            commaFound = false
                        }
                    }.start()
                } else {
                    commaFound = false
                }
                thread?.interrupt()
            }
            MODE_LOCAL -> {
                setCloudPinnedByUser(false)
                isGalaxyCloud = false
                BleBridgeClient.stopMaintainer()
                if (!customLocalIp.isNullOrBlank()) {
                    saveUserLocalIp(customLocalIp.trim())
                }
                val fallbackIp = customLocalIp?.trim()?.takeIf { it.isNotEmpty() }
                    ?: getUserLocalIp()
                    ?: getLastScannedIp()
                    ?: "192.168.68.9"
                commaIp = fallbackIp
                saveIp(fallbackIp)
                NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
                triggerStartupConnectionSequence()
                thread?.interrupt()
            }
            MODE_CLOUD -> {
                BleBridgeClient.stopMaintainer()
                val cloudUrl = getGalaxyCloudBase()
                commaIp = cloudUrl
                isGalaxyCloud = true
                saveIp(cloudUrl)
                setCloudPinnedByUser(true)
                NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
                thread?.interrupt()
            }
        }
    }

    fun getGalaxyCookie(): String {
        return GalaxyCloudSession.currentToken() ?: ""
    }


    /**
     * Saves (or clears) the user's Mapbox public token. On SecretGoodStarPilot
     * the typed API never returns the token (navigation status exposes only
     * hasKey), so after the legacy-era cached token is lost (e.g. an
     * uninstall wipes app prefs) the token can only come from the user
     * pasting it here once. Mirrors the packet-arrival persistence exactly:
     * prefs + NavStateManager + MapLibre API key, then notify so the phone
     * style can rebuild with it.
     */
    fun setMapboxToken(token: String?) {
        val clean = token?.trim()?.takeIf { it.isNotEmpty() }
        mapboxToken = clean
        NavStateManager.mapboxToken = clean
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (clean != null) {
            prefs.edit().putString(KEY_MAPBOX_TOKEN, clean).apply()
            try {
                org.maplibre.android.MapLibre.setApiKey(clean)
            } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        } else {
            prefs.edit().remove(KEY_MAPBOX_TOKEN).apply()
        }
        NavStateManager.notifyChanged()
    }

    fun setUsePhoneSpeed(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_SPEED, enabled).apply()
        NavStateManager.usePhoneSpeed = enabled
    }

    fun setUsePhoneLocation(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_LOCATION, enabled).apply()
        NavStateManager.usePhoneLocation = enabled
    }

    fun setUsePhoneLocationToComma(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_LOCATION_TO_COMMA, enabled).apply()
        NavStateManager.usePhoneLocationToComma = enabled
    }

    fun setUsePhoneMapboxKey(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_MAPBOX_KEY, enabled).apply()
        NavStateManager.usePhoneMapboxKey = enabled
    }

    /** Saves (or clears) the phone's own Google API key, mirroring [setMapboxToken]. */
    fun setGoogleApiKey(key: String?) {
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        googleApiKey = clean
        NavStateManager.googleApiKey = clean
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (clean != null) {
            prefs.edit().putString(KEY_GOOGLE_API_KEY, clean).apply()
        } else {
            prefs.edit().remove(KEY_GOOGLE_API_KEY).apply()
        }
        // A new key invalidates any tiles session minted under the old one.
        NavStateManager.googleTilesSession = null
        NavStateManager.googleTilesSessionExpirySec = 0
        NavStateManager.notifyChanged()
    }

    fun setUsePhoneGoogleKey(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_GOOGLE_KEY, enabled).apply()
        NavStateManager.usePhoneGoogleKey = enabled
        NavStateManager.notifyChanged()
    }

    /** Stores the Phone maps dropdown choice (Google / Mapbox / OSM). */
    fun setPhoneMapSource(source: String) {
        val clean = PhoneGoogle.sanitizeMapSource(source)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PHONE_MAP_SOURCE, clean).apply()
        NavStateManager.phoneMapSource = clean
        NavStateManager.notifyChanged()
    }

    fun setUsePhoneSpeedLimit(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_SPEED_LIMIT, enabled).apply()
        NavStateManager.usePhoneSpeedLimit = enabled
        if (!enabled) {
            NavStateManager.speedLimitMph = 0
            NavStateManager.routeLimitSeries = null
            lastRouteLimitKey = null
        } else {
            val route = NavStateManager.selectedRoute ?: NavStateManager.activeRoutes.firstOrNull()
            if (route != null) {
                maybeFetchRouteLimits(route)
            }
        }
        NavStateManager.notifyChanged()
    }


    fun setUsePhoneSignals(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_SIGNALS, enabled).apply()
        NavStateManager.usePhoneSignals = enabled
        if (!enabled) {
            NavStateManager.routeSignals = emptyList()
            lastSignalKey = null
        } else {
            val route = NavStateManager.selectedRoute ?: NavStateManager.activeRoutes.firstOrNull()
            if (route != null) {
                maybeFetchRouteFeatures(route)
            }
        }
        NavStateManager.notifyChanged()
    }

    fun setUsePhoneStops(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_STOPS, enabled).apply()
        NavStateManager.usePhoneStops = enabled
        if (!enabled) {
            NavStateManager.routeStops = emptyList()
            lastStopKey = null
        } else {
            val route = NavStateManager.selectedRoute ?: NavStateManager.activeRoutes.firstOrNull()
            if (route != null) {
                maybeFetchRouteFeatures(route)
            }
        }
        NavStateManager.notifyChanged()
    }

    fun setUsePhoneRoute(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_ROUTE, enabled).apply()
        NavStateManager.usePhoneRoute = enabled
    }

    fun setUsePhoneSearch(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_SEARCH, enabled).apply()
        NavStateManager.usePhoneSearch = enabled
    }

    fun setUsePhoneTraffic(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_TRAFFIC, enabled).apply()
        NavStateManager.usePhoneTraffic = enabled
    }

    fun setUsePhoneVectorMap(enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_PHONE_VECTOR_MAP, enabled).apply()
        NavStateManager.usePhoneVectorMap = enabled
        NavStateManager.notifyChanged()
    }

    fun isGalaxyPaired(): Boolean = GalaxyCloudSession.hasAppKey()

    fun unpairGalaxy() {
        GalaxyCloudSession.clearPairing()
        // Unpairing re-opens the Cloud SG question: drop the cached
        // probe result and the one-shot diagnostics, then wake the poll loop.
        galaxyModeCache.remove(NetworkScanner.GALAXY_URL)
        galaxyModeCache.remove(getGalaxyCloudBase())
        cloudSgNoticeShown = false
        cloudAuthNoticeShown = false
        thread?.interrupt()
    }

    fun getEffectiveNavigationEndpoint(): String {
        val current = commaIp
        return if (!current.isNullOrEmpty()) {
            if (current.contains("galaxy.firestar.link")) getGalaxyCloudBase() else current
        } else {
            if (isGalaxyCloud) getGalaxyCloudBase() else "192.168.68.9"
        }
    }

    private fun notifyDiagnostic(msg: String) {
        Log.i(TAG, "[Discovery] $msg")
        lastDiagnostic = msg
        lastDiagnosticAtMs = System.currentTimeMillis()
        val dl = diagnosticListener ?: return
        mainHandler.post { dl.onDiagnostic(msg) }
    }

    private val autoFailoverInFlight = AtomicBoolean(false)

    fun triggerImmediateAutoFailover() {
        if (getConnectionMode() != MODE_AUTO) return
        if (!autoFailoverInFlight.compareAndSet(false, true)) return
        Thread({
            try {
                // Priority 1: Check if Local is reachable
                val quickLan = scanner.quickCheckLan()
                if (quickLan != null) {
                    commaIp = quickLan
                    isGalaxyCloud = false
                    saveIp(quickLan)
                    saveLastScannedIp(quickLan)
                    commaFound = true
                    failures = 0
                    NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
                    notifyDiagnostic("Switched to Local comma ($quickLan:8082)")
                    thread?.interrupt()
                    return@Thread
                }

                // Priority 2: Check Bluetooth
                if (BleBridgeClient.isSharedClientConnected()) {
                    val savedLocal = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
                        ?: "127.0.0.1"
                    commaIp = savedLocal
                    isGalaxyCloud = false
                    commaFound = true
                    failures = 0
                    NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
                    notifyDiagnostic("Switched to Bluetooth")
                    thread?.interrupt()
                    return@Thread
                }

                // Priority 3: Fallback to Galaxy Cloud
                if (isCloudConfigured()) {
                    val cloudUrl = getGalaxyCloudBase()
                    commaIp = cloudUrl
                    isGalaxyCloud = true
                    saveIp(cloudUrl)
                    commaFound = true
                    failures = 0
                    NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
                    notifyDiagnostic("Switched to Galaxy Cloud")
                    thread?.interrupt()
                    return@Thread
                }

                notifyDiagnostic("Auto failover: no configured fallback transport available")
            } catch (e: Exception) {
                Log.w(TAG, "Immediate auto failover error: ${e.message}")
            } finally {
                autoFailoverInFlight.set(false)
            }
        }, "auto-failover-thread").start()
    }

    private fun registerNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    if (getConnectionMode() == MODE_AUTO && NavStateManager.activeTransport == NavStateManager.TransportType.IP) {
                        Log.i(TAG, "Network lost on IP transport — triggering immediate failover")
                        triggerImmediateAutoFailover()
                    }
                }

                override fun onAvailable(network: Network) {
                    if (getConnectionMode() == MODE_AUTO && NavStateManager.activeTransport != NavStateManager.TransportType.IP) {
                        Log.i(TAG, "Network available — checking for Local comma")
                        Thread({
                            try {
                                Thread.sleep(300)
                                val found = scanner.quickCheckLan()
                                if (found != null && getConnectionMode() == MODE_AUTO) {
                                    commaIp = found
                                    isGalaxyCloud = false
                                    saveIp(found)
                                    saveLastScannedIp(found)
                                    commaFound = true
                                    failures = 0
                                    NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
                                    notifyDiagnostic("Network available — switched immediately to Local ($found:8082)")
                                    thread?.interrupt()
                                }
                            } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                        }, "network-available-check").start()
                    }
                }
            }
            networkCallback = callback
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        networkCallback = null
    }

    fun start() {
        if (running.getAndSet(true)) return
        // Simulation listener (bug fix, 2026-11-09): stop() removes
        // simListener from SimulationEngine, but nothing ever added
        // it back — after one stop/start cycle (the client is
        // refcounted; AA sessions and the phone UI attach/detach
        // freely) simulated packets silently stopped reaching this
        // client for the rest of the process. addListener dedupes,
        // so re-adding on every start is safe.
        SimulationEngine.addListener(simListener)
        ensureGpsGate()

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val initialMode = getConnectionMode()
        NavStateManager.connectionMode = initialMode
        val saved = prefs.getString(KEY_IP, null)
        val isCloud = saved != null && saved.contains("galaxy.firestar.link")
        if (saved != null && (!isCloud || isCloudPinnedByUser())) {
            commaIp = saved
            isGalaxyCloud = isCloud
            NavStateManager.updateConnection(saved, false, isGalaxyCloud)
            when (initialMode) {
                MODE_BLUETOOTH -> NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
                MODE_LOCAL -> NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
                MODE_CLOUD -> NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
                else -> {
                    if (isGalaxyCloud) {
                        NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
                    } else {
                        NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
                    }
                }
            }
        }
        val savedToken = prefs.getString(KEY_MAPBOX_TOKEN, null)
        if (!savedToken.isNullOrBlank()) {
            mapboxToken = savedToken
            NavStateManager.mapboxToken = savedToken
        }
        // Offline destination queue (Feature 10): restore an entry queued
        // by a previous run so its chip — and the reconnect flush — work
        // across restarts.
        DestinationQueue.loadEntry(context)?.let {
            NavStateManager.queuedDestination = it.displayDestination()
        }
        Log.i(TAG, "Starting CommaClient. Initial target: $commaIp (saved: $saved)")
        if (initialMode == MODE_AUTO || initialMode == MODE_BLUETOOTH) {
            if (BleBridgeClient.hasPairingKey(context)) {
                BleBridgeClient.startIfPaired(context)
            }
        }
        BleBridgeClient.onConnectionStateChanged = { isConnected ->
            if (getConnectionMode() == MODE_AUTO) {
                if (isConnected) {
                    val curTransport = NavStateManager.activeTransport
                    if (curTransport == NavStateManager.TransportType.CLOUD ||
                        (curTransport == NavStateManager.TransportType.IP && (!commaFound || failures > 0))
                    ) {
                        NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
                        commaFound = true
                        failures = 0
                        if (isGalaxyCloud) {
                            isGalaxyCloud = false
                            commaIp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .getString(KEY_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
                                ?: "127.0.0.1"
                        }
                        lastLocalAccessLearnMs = 0L
                        notifyDiagnostic("Bluetooth connected — switched immediately to Bluetooth")
                        thread?.interrupt()
                    }
                } else {
                    if (NavStateManager.activeTransport == NavStateManager.TransportType.BLUETOOTH) {
                        notifyDiagnostic("Bluetooth disconnected — triggering immediate failover")
                        triggerImmediateAutoFailover()
                    }
                }
            }
        }
        registerNetworkCallback()
        val gen = ++loopGeneration
        thread = Thread({ runLoop(gen) }, "comma-client").apply {
            isDaemon = true
            start()
        }
        triggerStartupConnectionSequence()
    }

    fun stop() {
        running.set(false)
        loopGeneration++
        unregisterNetworkCallback()
        BleBridgeClient.onConnectionStateChanged = null
        BleBridgeClient.stopMaintainer()
        gpsRunning = false
        // Release the diagnostic listener (audit finding 33): it is
        // typically an Activity-capturing lambda, and nothing nulled
        // it, so a stopped client kept a destroyed screen alive.
        diagnosticListener = null
        SimulationEngine.removeListener(simListener)
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        locationManager = null
        thread?.join(1000)
        thread = null
        packetListeners.clear()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm
            val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                providers.add(LocationManager.FUSED_PROVIDER)
            }
            for (provider in providers) {
                try {
                    if (lm.isProviderEnabled(provider)) {
                        lm.requestLocationUpdates(provider, 500L, 0f, locationListener, Looper.getMainLooper())
                    }
                } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            }
        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private val isConnecting = AtomicBoolean(false)

    /**
     * Executes the connection flow:
     * 1. Try connecting Bluetooth.
     * 2. If Bluetooth key is not configured, connect local and fetch key and store it and Bluetooth again.
     * 3. Still fails -> fallback to local (searching both Wi-Fi and Hotspot subnets for port 8082).
     * 4. If local fails -> then use Cloud.
     */
    /** True while a startup connection sequence (including the LAN scan) is running. */
    fun isScanningNow(): Boolean = isConnecting.get()

    /**
     * @param preferLan AUTO only: run the Local discovery step BEFORE the
     * Bluetooth attempt. Used by the poll loop's failure path when the
     * BLE bridge is down, so recovery lands on the reachable LAN comma
     * immediately instead of spending 10 s retrying dead Bluetooth
     * first. When false the sequence order is exactly as before.
     */
    fun triggerStartupConnectionSequence(preferLan: Boolean = false, quickDiscovery: Boolean = false) {
        if (!isConnecting.compareAndSet(false, true)) {
            Log.d(TAG, "Startup connection sequence already running")
            return
        }
        Thread({
            try {
                runStartupConnectionSequence(preferLan, quickDiscovery)
            } catch (e: Exception) {
                Log.e(TAG, "Error in connection sequence: ${e.message}", e)
            } finally {
                isConnecting.set(false)
            }
        }, "comma-connect-sequence").start()
    }

    private fun runStartupConnectionSequence(
        @Suppress("UNUSED_PARAMETER") preferLan: Boolean = false,
        quickDiscovery: Boolean = false
    ) {
        if (SimulationEngine.isSimulating) {
            commaFound = true
            return
        }

        val mode = getConnectionMode()
        NavStateManager.connectionMode = mode

        if (mode == MODE_CLOUD || isCloudPinnedByUser()) {
            BleBridgeClient.stopMaintainer()
            // No use trying Cloud until it is configured: the device
            // Galaxy link fetched + the password saved (Param, 2026-11-04).
            if (!isCloudConfigured()) {
                val skip = TransportReadiness.autoSkipReason(
                    MODE_CLOUD, BleBridgeClient.hasPairingKey(context),
                    hasDedicatedGalaxyUrl(), GalaxyCloudSession.hasPassword()
                ) ?: "Galaxy Cloud skipped — not configured"
                notifyDiagnostic("$skip — Cloud was not tried. Configure it via Connection mode (Scan) or Galaxy Cloud & Web.")
                commaFound = false
                return
            }
            notifyDiagnostic("Galaxy Cloud selected — connecting to Cloud...")
            val cloudUrl = getGalaxyCloudBase()
            commaIp = cloudUrl
            isGalaxyCloud = true
            saveIp(cloudUrl)
            NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
            commaFound = true
            return
        }

        if (mode == MODE_BLUETOOTH) {
            // No use trying Bluetooth until its pairing key has been
            // fetched (over a Local connection) and saved (Param, 2026-11-04).
            if (!BleBridgeClient.hasPairingKey(context)) {
                notifyDiagnostic("Bluetooth not tried — pairing key not configured. Connect over Local once and the app fetches it.")
                commaFound = false
                return
            }
            notifyDiagnostic("Bluetooth mode selected — connecting BLE...")
            NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
            val hasKey = BleBridgeClient.hasPairingKey(context)
            val missingPermissions = BleBridgeClient.missingPermissions(context)
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val isBtEnabled = btManager?.adapter?.isEnabled == true
            if (hasKey && isBtEnabled && missingPermissions.isEmpty()) {
                try {
                    val ble = BleBridgeClient.getSharedClient(context)
                    ble.connect(timeoutMs = 10000)
                    if (ble.isConnected()) {
                        BleBridgeClient.startIfPaired(context)
                        commaFound = true
                        commaIp = "127.0.0.1"
                        notifyDiagnostic("Connected via Bluetooth")
                    } else {
                        commaFound = false
                    }
                } catch (e: Exception) {
                    commaFound = false
                    Log.w(TAG, "Bluetooth connect attempt failed: ${e.message}")
                    notifyDiagnostic("Bluetooth connect attempt failed: ${e.message}")
                }
            } else {
                commaFound = false
                notifyDiagnostic(
                    when {
                        !isBtEnabled -> "Bluetooth skipped — Bluetooth is off"
                        missingPermissions.isNotEmpty() -> "Bluetooth skipped — Bluetooth permission missing"
                        else -> "Bluetooth skipped — not ready"
                    }
                )
            }
            return
        }

        if (mode == MODE_LOCAL) {
            BleBridgeClient.stopMaintainer()
            notifyDiagnostic("Local mode selected — scanning network for Comma...")
            NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
            val userProvided = getUserLocalIp()
            val lastScanned = getLastScannedIp()
            val fallbackIp = userProvided ?: lastScanned ?: "192.168.68.9"

            val scannedIp = probeCloudReportedAddresses() ?: scanner.discover(userProvided ?: lastScanned)
            if (scannedIp != null) {
                commaIp = scannedIp
                isGalaxyCloud = false
                saveIp(scannedIp)
                saveLastScannedIp(scannedIp)
                commaFound = true
                notifyDiagnostic("Local comma found: $scannedIp:8082")
                Thread {
                    try {
                        val cloudUrl = LanPairing.fetchGalaxyStatusUrl(scannedIp)
                        if (!cloudUrl.isNullOrBlank()) saveGalaxyCloudBase(cloudUrl)
                    } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                }.start()
            } else {
                commaIp = fallbackIp
                isGalaxyCloud = false
                // Do NOT saveIp(fallbackIp) here: persisting the dead fallback
                // would re-pin it as the saved endpoint, so the next launch
                // would start from the stale IP again instead of scanning.
                commaFound = false
                notifyDiagnostic("No comma found — provided IP $fallbackIp not responding; will rescan")
            }
            return
        }

        // --- MODE_AUTO ONLY: priority Local -> Bluetooth -> Galaxy,
        // holding back any transport that is not configured yet ---
        var bluetoothConnected = false
        val hasKey = BleBridgeClient.hasPairingKey(context)
        val missingPermissions = BleBridgeClient.missingPermissions(context)
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val isBtEnabled = btManager?.adapter?.isEnabled == true

        // Show what is going on: the order Auto will actually try,
        // given what is configured (Param, 2026-11-04).
        notifyDiagnostic(
            "Auto connection — trying: " +
                TransportReadiness.autoOrderText(hasKey, hasDedicatedGalaxyUrl(), GalaxyCloudSession.hasPassword())
        )

        // 1. Try Local first (Priority 1)
        if (tryAutoLocalDiscovery(isBtEnabled, missingPermissions, quickOnly = quickDiscovery)) {
            return
        }

        // 2. Local unavailable. Param's rule (2026-11-12): when Galaxy
        // Cloud is configured it serves NOW, while Bluetooth is
        // searched in the background — the app never sits unconnected
        // waiting on a Bluetooth attempt; when the bridge connects,
        // the connection-state callback promotes Bluetooth over Cloud
        // (Bluetooth still outranks Cloud). Only when Cloud is not
        // configured does this sequence block on Bluetooth itself.
        val cloudSkip = TransportReadiness.autoSkipReason(
            MODE_CLOUD, hasKey, hasDedicatedGalaxyUrl(), GalaxyCloudSession.hasPassword()
        )
        if (TransportReadiness.autoServeCloudWhileSearching(cloudSkip)) {
            val searching = hasKey && isBtEnabled && missingPermissions.isEmpty()
            if (searching) {
                BleBridgeClient.startIfPaired(context)
            }
            val cloudUrl = getGalaxyCloudBase()
            commaIp = cloudUrl
            isGalaxyCloud = true
            saveIp(cloudUrl)
            commaFound = true
            NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
            notifyDiagnostic(
                when {
                    searching -> "Using Galaxy Cloud while Bluetooth is searched — Bluetooth takes over when it connects"
                    !hasKey -> "Bluetooth not configured yet — using Galaxy Cloud"
                    !isBtEnabled -> "Bluetooth is off — using Galaxy Cloud"
                    else -> "Bluetooth permission missing — using Galaxy Cloud"
                }
            )
            return
        }

        // Cloud not configured: block on the Bluetooth attempt (Priority 2).
        notifyDiagnostic("Local network unavailable — fallback to Bluetooth...")
        if (hasKey && isBtEnabled && missingPermissions.isEmpty()) {
            notifyDiagnostic("Trying Bluetooth connection to Comma...")
            try {
                val ble = BleBridgeClient.getSharedClient(context)
                ble.connect(timeoutMs = 10000)
                if (ble.isConnected()) {
                    bluetoothConnected = true
                    BleBridgeClient.startIfPaired(context)
                    NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
                    notifyDiagnostic("Connected via Bluetooth")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth connect attempt failed: ${e.message}")
                notifyDiagnostic("Bluetooth connect attempt failed: ${e.message}")
            }
        } else if (!hasKey) {
            notifyDiagnostic("Bluetooth skipped — pairing key not configured yet")
        } else {
            notifyDiagnostic(
                if (!isBtEnabled) "Bluetooth skipped — Bluetooth is off"
                else "Bluetooth skipped — Bluetooth permission missing"
            )
        }

        if (bluetoothConnected) {
            val savedLocal = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
                ?: "127.0.0.1"
            commaIp = savedLocal
            isGalaxyCloud = false
            commaFound = true
            NavStateManager.updateConnection(savedLocal, true, false)
            NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
            return
        }

        // 3. Bluetooth unavailable too — and Cloud is not configured
        // this run (the configured case already returned above,
        // serving Cloud while Bluetooth is searched).
        notifyDiagnostic("Local network and Bluetooth unavailable. $cloudSkip — Auto will keep retrying Local.")
        commaFound = false
    }

    /**
     * The AUTO sequence's Local discovery step: scans the local network &
     * hotspot for a comma on port 8082. On success the WiFi session is applied
     * (endpoint, saved IP, transport = IP).
     * Returns true when a comma was found and applied.
     */
    private fun tryAutoLocalDiscovery(
        isBtEnabled: Boolean,
        missingPermissions: List<String>,
        quickOnly: Boolean = false
    ): Boolean {
        notifyDiagnostic("Searching local network & hotspot for port 8082...")
        val savedIp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
        val foundLocal = if (quickOnly) {
            scanner.quickCheckLan()
        } else {
            probeCloudReportedAddresses() ?: scanner.discover(savedIp)
        } ?: return false
        notifyDiagnostic("Connected to local comma ($foundLocal:8082)")
        Thread {
            try {
                val cloudUrl = LanPairing.fetchGalaxyStatusUrl(foundLocal)
                if (!cloudUrl.isNullOrBlank()) saveGalaxyCloudBase(cloudUrl)
            } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }.start()
        commaIp = foundLocal
        isGalaxyCloud = false
        saveIp(foundLocal)
        saveLastScannedIp(foundLocal)
        commaFound = true
        failures = 0
        NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
        // If Bluetooth key is ready, start maintainer so Bluetooth is standby
        if (BleBridgeClient.hasPairingKey(context) && isBtEnabled && missingPermissions.isEmpty()) {
            BleBridgeClient.startIfPaired(context)
        } else if (!BleBridgeClient.hasPairingKey(context) && isBtEnabled && missingPermissions.isEmpty()) {
            fetchBluetoothKeyInBackground(foundLocal)
        }
        return true
    }

    private fun fetchBluetoothKeyInBackground(foundIp: String) {
        Thread({
            try {
                notifyDiagnostic("Fetching Bluetooth key over Wi-Fi…")
                val keyHex = BleBridgeClient.fetchAndSaveKeyFromComma(context, foundIp)
                if (!keyHex.isNullOrBlank() || BleBridgeClient.hasPairingKey(context)) {
                    notifyDiagnostic("Bluetooth key saved — Bluetooth ready as fallback")
                    BleBridgeClient.startIfPaired(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth key fetch failed: ${e.message}")
            }
        }, "comma-bt-key-fetch").start()
    }


    private fun dispatchPowerFallback() {
        val power = NavStateManager.latestPower
        if (power != null) {
            val cur = NavStateManager.latestPacket
            // Cloud gate (Param, 2026-11-12): no phone position or
            // phone speed in telemetry while on Galaxy Cloud.
            val phoneOk = HeartbeatTransport.phoneDataAllowed(NavStateManager.activeTransport)
            val liveGps = if (phoneOk) {
                lastPhoneGps ?: NavStateManager.currentGps
            } else {
                cur?.gps ?: NavStateManager.currentGps
            }
            val base = (cur ?: TelemetryPacket()).copy(
                power = power,
                gps = liveGps,
                speedMs = if (phoneOk) gpsSpeedMs else cur?.speedMs ?: 0.0
            )
            NavStateManager.updateTelemetry(base, commaIp, false, isGalaxyCloud)
            mainHandler.post {
                for (l in packetListeners) {
                    try { l.onPacket(base) } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                }
            }
        }
    }

    private val bleFailoverInFlight = AtomicBoolean(false); private val lanQuickCheckInFlight = AtomicBoolean(false)

    @Volatile
    private var lastBleFailoverAttemptMs = 0L

    /**
     * AUTO-mode Bluetooth-offline failover: called from the poll loop's
     * transport-sync section when the BLE bridge is not connected. When
     * [BleFailover.shouldFailover] fires (transport still BLUETOOTH,
     * cooldown elapsed), ONE background thread looks for a LAN comma in
     * cheapest-first order (Param's rule — a scan is the last resort):
     *   1. direct probe of the address learned from the comma over
     *      Bluetooth (then the last saved LAN address),
     *   2. quick check of known addresses (quickCheckLan),
     *   3. full discovery scan (discover) only if 1 and 2 both fail.
     * A found comma is applied exactly like the other LAN-found paths
     * (endpoint, saved IPs, transport = IP), so the HUD immediately
     * shows the LAN address; the BLE maintainer keeps retrying in the
     * background and the poll loop promotes Bluetooth again when it
     * revives. When no LAN comma answers, state is left alone — the
     * poll loop's failure path (startup sequence / Cloud) stays the
     * fallback.
     */
    private fun maybeBleFailoverToLan() {
        val now = System.currentTimeMillis()
        if (!BleFailover.shouldFailover(
                modeIsAuto = getConnectionMode() == MODE_AUTO,
                transportIsBluetooth = NavStateManager.activeTransport == NavStateManager.TransportType.BLUETOOTH,
                bleConnected = BleBridgeClient.isSharedClientConnected(),
                nowMs = now,
                lastAttemptMs = lastBleFailoverAttemptMs
            )
        ) return
        if (!bleFailoverInFlight.compareAndSet(false, true)) return
        lastBleFailoverAttemptMs = now
        Thread({
            try {
                // 1. Direct probes — no scanning. The learned address
                // comes first: the comma reported it about itself, so
                // if it answers there is nothing left to look for.
                val learned = lastLocalAccessIp
                var ip: String? = null
                var fromLearned = false
                // Param (2026-11-12): Wi-Fi and Hotspot both off means
                // no LAN probing at all — go straight to Galaxy Cloud.
                if (scanner.lanScanAllowed()) {
                    for (candidate in BleFailover.candidateOrder(learned, getSavedIp())) {
                        if (scanner.isPortOpen(candidate) && scanner.verifyComma(candidate)) {
                            ip = candidate
                            fromLearned = candidate == learned
                            break
                        }
                    }
                    // 2. Known-address quick check, then 3. the full scan.
                    if (ip == null) ip = scanner.quickCheckLan()
                    if (ip == null) ip = scanner.discover(null)
                }
                if (ip != null) {
                    commaIp = ip
                    isGalaxyCloud = false
                    saveIp(ip)
                    saveLastScannedIp(ip)
                    commaFound = true
                    NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
                    notifyDiagnostic(
                        if (fromLearned) "Bluetooth offline — switched to LAN $ip (learned over Bluetooth)"
                        else "Bluetooth offline — switched to LAN $ip"
                    )
                } else if (isCloudConfigured()) {
                    val cloudUrl = getGalaxyCloudBase()
                    commaIp = cloudUrl
                    isGalaxyCloud = true
                    saveIp(cloudUrl)
                    commaFound = true
                    failures = 0
                    NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
                    notifyDiagnostic("Bluetooth offline and no LAN comma — switched to Galaxy Cloud")
                    thread?.interrupt()
                } else {
                    notifyDiagnostic("Bluetooth offline — no LAN comma found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth-offline failover scan failed: ${e.message}")
            } finally {
                bleFailoverInFlight.set(false)
            }
        }, "comma-ble-failover").start()
    }

    /**
     * Addendum 123 (Param, 2026-11-21): before any subnet scan, ask
     * the comma itself which addresses it is listening on — GET
     * /api/local-access over the configured Galaxy Cloud session —
     * and probe just those addresses. Returns the first candidate
     * that verifies as the comma, or null when Cloud is not
     * configured, no session token is stored, LAN probing is not
     * allowed (Wi-Fi and hotspot both off), or no candidate answers;
     * callers then fall back to their scan exactly as before. Never
     * prompts: a missing or expired cloud session just skips the step.
     */
    private fun probeCloudReportedAddresses(): String? {
        if (!isCloudConfigured()) return null
        if (!scanner.lanScanAllowed()) return null
        val token = GalaxyCloudSession.currentToken()?.takeIf { it.isNotEmpty() } ?: return null
        val base = getGalaxyCloudBase().trimEnd('/')
        val body = try {
            val conn = (URL("$base/api/local-access").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = false
                setRequestProperty("Cookie", "${GalaxyCloudSession.COOKIE_NAME}=$token")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) {
                Log.d(TAG, "local-access over Cloud returned HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "local-access over Cloud failed: ${e.message}")
            return null
        }
        val ips = BleFailover.parseLocalAccessIps(JSONObject(body))
        for (candidate in ips) {
            if (scanner.isPortOpen(candidate) && scanner.verifyComma(candidate)) {
                notifyDiagnostic("Comma address from Galaxy ($candidate) — no scan needed")
                return candidate
            }
        }
        return null
    }

    private val localAccessLearnInFlight = AtomicBoolean(false)

    @Volatile
    private var lastLocalAccessLearnMs = 0L

    /**
     * The comma's LAN address as last reported by the comma itself via
     * GET /api/local-access over the BLE bridge. Written by the learn
     * thread below, read by the failover thread (direct probe) and
     * persisted so relaunches inherit it.
     */
    @Volatile
    private var lastLocalAccessIp: String? = null

    /**
     * Learns the comma's current LAN address while the BLE bridge is
     * healthy: GET /api/local-access is proxied by the bridge to the
     * comma's loopback Galaxy server, which answers with the addresses
     * the comma is actually listening on. Called from the poll loop's
     * transport-sync section; [BleFailover.shouldFetchLocalAccess]
     * gates it to the transport-flip pass and then at most once per
     * [BleFailover.LEARN_INTERVAL_MS]. Learning ONLY: the result is
     * persisted for the failover and for future launches, but the
     * live transport and endpoint never move while Bluetooth is up.
     * Failures are log-only — a missed learn just means the failover
     * falls back to its quick check / scan.
     */
    private fun maybeLearnLocalAccessOverBle() {
        val ble = BleBridgeClient.getConnectedClient() ?: return
        val now = System.currentTimeMillis()
        if (!BleFailover.shouldFetchLocalAccess(
                bleConnected = true,
                nowMs = now,
                lastFetchMs = lastLocalAccessLearnMs
            )
        ) return
        if (!localAccessLearnInFlight.compareAndSet(false, true)) return
        lastLocalAccessLearnMs = now
        Thread({
            try {
                val resp = ble.request(
                    method = "GET",
                    path = "/api/local-access",
                    headers = mapOf("Accept" to "application/json"),
                    timeoutMs = 6000
                )
                if (resp.status == 200) {
                    val ips = BleFailover.parseLocalAccessIps(JSONObject(resp.bodyText()))
                    val first = ips.firstOrNull()
                    if (first != null) {
                        lastLocalAccessIp = first
                        persistLearnedLocalIp(first)
                        Log.i(TAG, "Learned comma LAN address over Bluetooth: $first (of ${ips.size})")
                    } else {
                        Log.d(TAG, "local-access over BLE reported no LAN address")
                    }
                } else {
                    Log.d(TAG, "local-access over BLE returned HTTP ${resp.status}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "local-access learn over BLE failed: ${e.message}")
            } finally {
                localAccessLearnInFlight.set(false)
            }
        }, "comma-ble-local-access").start()
    }

    /**
     * Persists a learned LAN address WITHOUT touching live connection
     * state: [saveIp] also assigns commaIp and flips the active
     * transport to IP, which must not happen while the BLE bridge is
     * healthy. This writes exactly the prefs saveIp persists
     * (comma_ip, plus last_scanned_ip via [saveLastScannedIp]) so the
     * stored state is identical — quick checks, discovery and future
     * launches all inherit the learned address.
     */
    private fun persistLearnedLocalIp(ip: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_IP, ip).apply()
        saveLastScannedIp(ip)
    }

    private val queueFlushInFlight = AtomicBoolean(false)

    /**
     * Offline destination queue flush (Feature 10): called from the poll
     * loop whenever a telemetry fetch just succeeded, so an attempt
     * only ever runs while the comma is connected. Runs at most one
     * background flush at a time; the send itself is a normal
     * device-confirmed flow — the SG coordinate select for COORDS
     * entries, a re-search + selectPlace for COMMA_PLACE entries
     * (Fix B). On confirmation the queue clears; on failure the entry
     * stays queued and is retried every
     * [DestinationQueue.FLUSH_RETRY_INTERVAL_MS] (Param, 2026-11-21).
     */
    private fun maybeFlushQueuedDestination() {
        if (!NavStateManager.isSgGalaxy) return
        val entry = DestinationQueue.loadEntry(context)
        if (!DestinationQueue.shouldFlush(commaFound = true, hasQueued = entry != null, lastFailureAtMs = DestinationQueue.lastFlushFailureAtMs)) return
        if (!queueFlushInFlight.compareAndSet(false, true)) return
        val queued = entry!!
        Thread({
            try {
                val endpoint = getEffectiveNavigationEndpoint()
                val baseUrl = SgGalaxyClient.baseUrlFor(endpoint)
                val ok = when (queued.kind) {
                    DestinationQueue.Kind.COORDS -> NavigationManager().sendToCommaSg(
                        baseUrl, queued.coordsDestination()!!, NavStateManager.sgRevision
                    )
                    DestinationQueue.Kind.COMMA_PLACE -> flushQueuedCommaPlace(baseUrl, queued)
                }
                if (ok) {
                    DestinationQueue.clear(context)
                    DestinationQueue.noteFlushSuccess()
                    NavStateManager.queuedDestination = null
                    NavStateManager.queuedOutOfSync = false
                    NavStateManager.notifyChanged()
                    notifyDiagnostic("Queued destination sent to comma: ${queued.name}")
                } else {
                    DestinationQueue.noteFlushFailure()
                    // The comma answered but still did not accept the
                    // destination: phone and comma are out of sync
                    // (Addendum 122) — the queued indicator switches to
                    // the connect-the-comma-to-the-Internet message.
                    // Refresh the comma's network snapshot so the
                    // message can name the Wi-Fi it is on (Param,
                    // 2026-11-21), then notify either way: the Wi-Fi
                    // detail may have changed even when the sync flag
                    // did not.
                    NavStateManager.queuedOutOfSync = true
                    try {
                        NavigationManager().fetchNetworksSg(baseUrl)
                    } catch (e: Exception) {
                        Log.d(TAG, "Networks refresh for the sync message failed: ${e.message}")
                    }
                    NavStateManager.notifyChanged()
                    notifyDiagnostic("Queued destination send failed — will retry in 15 seconds while the comma stays connected")
                }
            } catch (e: Exception) {
                DestinationQueue.noteFlushFailure()
                Log.w(TAG, "Queued destination flush failed: ${e.message}")
            } finally {
                queueFlushInFlight.set(false)
            }
        }, "comma-queue-flush").start()
    }

    /**
     * Fix B: flushes a queued comma suggestion. Re-runs the recorded
     * comma search under a fresh session (the same function the UI
     * search uses, so the session + search-time revision bookkeeping is
     * identical), re-finds the place via [DestinationQueue.matchCommaPlace],
     * and sends it through the same confirmed flows a live tap uses —
     * the coordinate select when the fresh match carries coordinates,
     * selectPlace when it is still a temporary suggestion. Returns
     * false (one counted attempt) when the query is gone, the place no
     * longer appears, or the comma does not confirm.
     */
    private fun flushQueuedCommaPlace(baseUrl: String, entry: DestinationQueue.Entry): Boolean {
        val query = entry.query?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "Queued comma place '${entry.name}' has no recorded query — cannot re-search")
            return false
        }
        val navManager = NavigationManager()
        NavigationManager.beginCommaSearchSession()
        val results = navManager.searchPlacesCommaSg(query, baseUrl)
        val match = DestinationQueue.matchCommaPlace(entry, results) ?: run {
            Log.w(TAG, "Queued comma place '${entry.name}' not found in a fresh search for '$query'")
            return false
        }
        return if (match.hasCoordinates) {
            navManager.sendToCommaSg(baseUrl, match, NavStateManager.sgRevision)
        } else {
            navManager.selectCommaPlaceSg(baseUrl, match, NavStateManager.sgRevision)
        }
    }

    /**
     * runLoop, transport sync (extracted 2026-11-08): holds
     * the active transport to the mode policy — Bluetooth
     * mode follows the BLE link; AUTO runs the Local ->
     * Bluetooth -> Galaxy priority with immediate switches
     * on failure; Local and Cloud pin their transport.
     * Mutates only client fields; the loop calls it once
     * per cycle before fetching.
     */
    private fun syncTransportForMode(): String {
        val curLoopMode = getConnectionMode()
        if (curLoopMode == MODE_BLUETOOTH) {
            if (BleBridgeClient.isSharedClientConnected()) {
                if (NavStateManager.activeTransport != NavStateManager.TransportType.BLUETOOTH) {
                    NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
                    commaFound = true
                    if (isGalaxyCloud) {
                        isGalaxyCloud = false
                        commaIp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getString(KEY_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
                            ?: "127.0.0.1"
                    }
                    // Fresh Bluetooth transport: learn the comma's
                    // LAN address right away instead of waiting out
                    // the periodic learn interval.
                    lastLocalAccessLearnMs = 0L
                }
                // While Bluetooth is healthy, keep the learned LAN
                // address fresh (flip pass + at most once a minute)
                // so a later drop can fail over by direct probe.
                maybeLearnLocalAccessOverBle()
            }
        } else if (curLoopMode == MODE_AUTO) {
            // Priority in AUTO: Local (1) -> Bluetooth (2) -> Cloud (3)
            val curTransport = NavStateManager.activeTransport
            val bleConnected = BleBridgeClient.isSharedClientConnected()

            if (curTransport == NavStateManager.TransportType.IP) {
                // Local is Priority 1; stay on IP transport when working.
                // If Local is unreachable/failing, switch immediately to next available transport:
                if (!commaFound || failures > 0) {
                    if (bleConnected) {
                        NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
                        commaFound = true
                        failures = 0
                        commaIp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getString(KEY_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
                            ?: "127.0.0.1"
                        isGalaxyCloud = false
                        lastLocalAccessLearnMs = 0L
                        notifyDiagnostic("Local unreachable — switched immediately to Bluetooth")
                    } else if (failures >= 2 && isCloudConfigured()) {
                        val cloudUrl = getGalaxyCloudBase()
                        commaIp = cloudUrl
                        isGalaxyCloud = true
                        saveIp(cloudUrl)
                        commaFound = true
                        failures = 0
                        NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
                        notifyDiagnostic("Local unreachable and Bluetooth not connected — switched immediately to Galaxy Cloud")
                    }
                }
            } else if (curTransport == NavStateManager.TransportType.BLUETOOTH) {
                if (bleConnected) {
                    maybeLearnLocalAccessOverBle()
                } else {
                    // Bluetooth dropped: check LAN or Cloud immediately
                    val quickLan = scanner.quickCheckLan()
                    if (quickLan != null) {
                        commaIp = quickLan
                        isGalaxyCloud = false
                        saveIp(quickLan)
                        saveLastScannedIp(quickLan)
                        commaFound = true
                        failures = 0
                        NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
                        notifyDiagnostic("Bluetooth dropped — switched immediately to Local ($quickLan:8082)")
                    } else if (isCloudConfigured()) {
                        val cloudUrl = getGalaxyCloudBase()
                        commaIp = cloudUrl
                        isGalaxyCloud = true
                        saveIp(cloudUrl)
                        commaFound = true
                        failures = 0
                        NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
                        notifyDiagnostic("Bluetooth dropped — switched immediately to Galaxy Cloud")
                    } else {
                        maybeBleFailoverToLan()
                    }
                }
            } else {
                // Currently on Cloud or unassigned: promote to Bluetooth (Priority 2 over 3) if connected
                if (bleConnected) {
                    NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
                    commaFound = true
                    failures = 0
                    if (isGalaxyCloud) {
                        isGalaxyCloud = false
                        commaIp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getString(KEY_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
                            ?: "127.0.0.1"
                    }
                    lastLocalAccessLearnMs = 0L
                    notifyDiagnostic("Bluetooth connected — switched to Bluetooth")
                }
            }
        } else if (curLoopMode == MODE_LOCAL) {
            if (NavStateManager.activeTransport != NavStateManager.TransportType.IP) {
                NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
            }
        } else if (curLoopMode == MODE_CLOUD) {
            if (NavStateManager.activeTransport != NavStateManager.TransportType.CLOUD) {
                NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
            }
        }
        return curLoopMode
    }

    /**
     * Keeps Local's tracked availability fresh (Param, 2026-11-12:
     * AUTO tracks all three transports always). Bluetooth's state
     * is read live and Cloud's is its configured state, so Local is
     * the only probe: it runs on its own thread (the poll thread
     * never blocks on the LAN), gated by
     * [TransportReadiness.shouldProbeLocal] — every cycle while a
     * lower-priority transport serves, single-flight so an
     * unanswered probe self-throttles. While Local itself serves,
     * the heartbeat keeps [localAvailable] true (see
     * fetchSgTelemetry).
     */
    private fun trackLocalAvailability(curLoopMode: String) {
        if (!TransportReadiness.shouldProbeLocal(
                curLoopMode,
                NavStateManager.activeTransport,
                scanner.lanScanAllowed(),
                localProbeInFlight.get()
            )
        ) return
        if (!localProbeInFlight.compareAndSet(false, true)) return
        Thread({
            try {
                val found = scanner.quickCheckLan()
                if (found != null) {
                    lastProbedLocalIp = found
                    localAvailable = true
                } else {
                    localAvailable = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Local availability probe failed: ${e.message}")
            } finally {
                localProbeInFlight.set(false)
            }
        }, "comma-local-probe").apply { isDaemon = true }.start()
    }

    /**
     * AUTO's per-heartbeat choice (Param, 2026-11-12: "Auto choose
     * every second while heartbeat"): from the tracked
     * availability of all three transports, serve on the
     * highest-priority one ([TransportReadiness.autoChoice]).
     * Upward moves apply immediately; downward moves stay with the
     * existing failure-driven paths. Bluetooth promotion already
     * happens in the transport sync; the choice adds the Local
     * promotion the sync never had.
     */
    private fun applyAutoChoice() {
        if (getConnectionMode() != MODE_AUTO) return
        val choice = TransportReadiness.autoChoice(
            localAvailable,
            BleBridgeClient.isSharedClientConnected(),
            isCloudConfigured()
        )
        if (!TransportReadiness.outranks(choice, NavStateManager.activeTransport)) return
        if (choice == NavStateManager.TransportType.IP) {
            val ip = lastProbedLocalIp ?: return
            commaIp = ip
            isGalaxyCloud = false
            saveIp(ip)
            saveLastScannedIp(ip)
            commaFound = true
            failures = 0
            NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
            notifyDiagnostic("Local is available — switched to Local (Local has priority)")
        }
    }

    private fun runLoop(gen: Int) {
        var lanCooldown = 0
        while (running.get() && gen == loopGeneration) {
            if (SimulationEngine.isSimulating) {
                commaFound = true
                sleep(400)
                continue
            }

            val curLoopMode = syncTransportForMode()
            trackLocalAvailability(curLoopMode)
            applyAutoChoice()

            // 1. Non-blocking Background Discovery: Check local LAN and Galaxy Cloud without freezing telemetry
            if (commaIp == null) {
                if (isConnecting.get()) {
                    dispatchPowerFallback()
                    sleep(500)
                    continue
                }
                val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_IP, null)
                if (saved != null && saved.contains("galaxy.firestar.link") && isCloudPinnedByUser()) {
                    commaIp = saved
                    isGalaxyCloud = true
                    NavStateManager.setActiveTransport(NavStateManager.TransportType.CLOUD)
                } else {
                    triggerStartupConnectionSequence()
                    dispatchPowerFallback()
                    sleep(1000)
                    continue
                }
            } else {
                isGalaxyCloud = commaIp?.contains("galaxy.firestar.link") == true
            }

            val endpoint = commaIp ?: continue
            val started = System.currentTimeMillis()
            val sgMode = isSgEndpoint(endpoint, isGalaxyCloud)
            NavStateManager.setSgGalaxy(sgMode)
            if (isGalaxyCloud && !sgMode) {
                when {
                    GalaxyCloudSession.authFailed -> {
                        if (!cloudAuthNoticeShown) {
                            cloudAuthNoticeShown = true
                            notifyDiagnostic("Galaxy Cloud sign-in failed — reconnect to the comma locally to re-pair the App Key.")
                        }
                    }
                    !GalaxyCloudSession.hasPassword() -> {
                        if (!cloudSgNoticeShown) {
                            cloudSgNoticeShown = true
                            notifyDiagnostic("Galaxy Cloud on SG needs to pair — connect to the comma locally once (LAN or hotspot) and the App Key pairs automatically, or use LAN, hotspot, or Bluetooth.")
                        }
                    }
                }
            } else if (!isGalaxyCloud) {
                cloudSgNoticeShown = false
                cloudAuthNoticeShown = false
            }

            ensureGpsGate()

            val pkt = try {
                if (sgMode) {
                    fetchSgTelemetry(endpoint)
                } else {
                    if (!isGalaxyCloud) fetchDirectDeviceState(endpoint)
                    fetchParams(endpoint, isGalaxyCloud)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fetch error from $endpoint: ${e.message}")
                null
            }

            if (pkt != null) {
                failures = 0
                recoveryAttempts = 0
                commaFound = true
                val phoneOk = HeartbeatTransport.phoneDataAllowed(NavStateManager.activeTransport)
                val liveGps = pkt.gps ?: if (phoneOk) lastPhoneGps else null
                val liveSpeed = if (pkt.speedMs > 0.0 || !phoneOk) pkt.speedMs else gpsSpeedMs
                val withPower = pkt.copy(
                    power = NavStateManager.latestPower,
                    gps = liveGps,
                    speedMs = liveSpeed
                )
                NavStateManager.updateTelemetry(withPower, commaIp, commaFound, isGalaxyCloud)
                mainHandler.post {
                    for (l in packetListeners) {
                        try { l.onPacket(withPower) } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                    }
                }

                // Offline destination queue (Feature 10): the comma just
                // answered — flush a queued destination if one is waiting.
                maybeFlushQueuedDestination()

                // When on local Wi-Fi, periodically ensure Galaxy Cloud URL is grabbed and saved
                if (!isGalaxyCloud && endpoint != "127.0.0.1" && sgPollCount % 60 == 5) {
                    Thread {
                        try {
                            val cloudUrl = LanPairing.fetchGalaxyStatusUrl(endpoint)
                            if (!cloudUrl.isNullOrBlank()) saveGalaxyCloudBase(cloudUrl)
                        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                    }.start()
                }

                // If on Galaxy Cloud or on Bluetooth in AUTO mode, check for local Wi-Fi comma (Priority 1)
                val checkLan = if (curLoopMode == MODE_AUTO) {
                    NavStateManager.activeTransport == NavStateManager.TransportType.BLUETOOTH || isGalaxyCloud
                } else {
                    isGalaxyCloud && !isCloudPinnedByUser()
                }
                if (checkLan) {
                    lanCooldown++
                    if (lanCooldown >= 3) {
                        lanCooldown = 0
                        if (lanQuickCheckInFlight.compareAndSet(false, true)) Thread({
                            val lanCandidate = try { scanner.quickCheckLan() } finally { lanQuickCheckInFlight.set(false) }
                            if (lanCandidate != null) {
                                commaIp = lanCandidate
                                isGalaxyCloud = false
                                saveIp(lanCandidate)
                                saveLastScannedIp(lanCandidate)
                                commaFound = true
                                failures = 0
                                NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
                                notifyDiagnostic("Local comma appeared! Switched to $lanCandidate:8082")
                            }
                        }, "comma-lan-quick-check").start()
                    }
                }
            } else {
                failures++
                commaFound = false
                Log.w(TAG, "Failed fetching params from $endpoint (failure $failures/$MAX_FAILURES)")

                // Seamless power fallback: feed live battery and drive state so display is NEVER blocked
                dispatchPowerFallback()

                if (curLoopMode == MODE_AUTO) {
                    if (NavStateManager.activeTransport == NavStateManager.TransportType.IP && BleBridgeClient.isSharedClientConnected()) {
                        NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
                        commaFound = true
                        failures = 0
                        commaIp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getString(KEY_IP, null)?.takeIf { !it.contains("galaxy.firestar.link") }
                            ?: "127.0.0.1"
                        isGalaxyCloud = false
                        notifyDiagnostic("Local fetch failed — switched immediately to Bluetooth")
                    } else if (failures >= 2 && recoveryBackoffAllows()) {
                        triggerImmediateAutoFailover()
                    }
                } else if (failures >= MAX_FAILURES) {
                    failures = 0
                    if (getConnectionMode() == MODE_LOCAL) {
                        if (recoveryBackoffAllows()) {
                            Log.i(TAG, "Local mode offline, auto-scanning network for Comma...")
                            triggerStartupConnectionSequence()
                        }
                    } else {
                        Log.i(TAG, "Mode is ${getConnectionMode()}, keeping selected transport and retrying...")
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - started
            val isNavigating = NavStateManager.activeDestination != null && !NavStateManager.navClearedByUser
            val baseDelay = when {
                isNavigating -> 1000L
                sgMode -> currentSgPollIntervalMs(POLL_MS)
                else -> POLL_MS
            }
            // While every fetch is failing, stretch the cadence
            // (PollBackoff): an offline comma must not cost a request
            // per second through the Cloud relay or on the LAN.
            val pollDelay = PollBackoff.effectiveDelayMs(baseDelay, failures)
            sleep((pollDelay - elapsed).coerceIn(50L, pollDelay))
        }
    }

    private fun sgClientFor(endpoint: String): SgGalaxyClient {
        val base = if (endpoint.contains("galaxy.firestar.link")) {
            getGalaxyCloudBase()
        } else {
            "http://$endpoint:${NetworkScanner.PORT}"
        }
        if (sgClientBase != base) {
            sgClient = SgGalaxyClient(base)
            sgClientBase = base
        }
        return sgClient!!
    }

    /**
     * Probes (once per endpoint) whether the comma serves the SG typed APIs.
     * Cloud endpoints are probed only when a Galaxy password is saved — the
     * probe's first request signs in via [GalaxyCloudSession] on a 401.
     * Without a password, Cloud stays on the legacy path.
     */
    private fun isSgEndpoint(endpoint: String, isCloud: Boolean): Boolean {
        if (isCloud && !GalaxyCloudSession.hasPassword()) return false
        galaxyModeCache[endpoint]?.let { return it }
        val probeStatus = sgClientFor(endpoint).probe()
        val sg = probeStatus != null
        if (sg) {
            galaxyModeCache[endpoint] = true
        }
        if (sg && probeStatus != null) {
            // Stash the probe's nav-status snapshot so the first SG poll
            // reuses it instead of immediately refetching (review A7).
            pendingProbeNavStatus = SgGalaxyClient.NavStatusResponse(
                probeStatus,
                probeStatus.optString("routeKey", "").ifEmpty { null }
            )
            pendingProbeEndpoint = endpoint
            notifyDiagnostic(
                if (isCloud) {
                    "Galaxy Cloud signed in — polling the typed Galaxy API (SecretGoodStarPilot)"
                } else {
                    "Comma serves the typed Galaxy API (SecretGoodStarPilot) — polling /api/navigation/status"
                }
            )
        }
        return sg
    }

    /**
     * SLC-offset fetch interval: 60 s normally, stretched to 10 min after
     * [SLC_NULL_STREAK_FOR_BACKOFF] consecutive null results. Current SG
     * always returns null (its typed settings rows carry no key field), so
     * without the backoff this is one wasted request per minute forever.
     * Any non-null result resets the streak in [fetchSgTelemetry].
     */
    private fun currentSlcIntervalMs(): Long =
        if (slcNullStreak >= SLC_NULL_STREAK_FOR_BACKOFF) SLC_BACKOFF_MS else SLC_POLL_MS

    /**
     * Adaptive SG poll interval (review A7): while the most recent SG device
     * state says the comma is parked/standby/offroad, poll every 5 s; while
     * driving or when the state is unknown, keep the base interval
     * (1 s LAN / 1.2 s Galaxy Cloud). Legacy-params polling never calls this.
     */
    private fun currentSgPollIntervalMs(baseMs: Long): Long {
        val state = lastSgDeviceState?.lowercase()?.trim()
        return if (state != null && state in PARKED_DEVICE_STATES) SG_PARKED_POLL_MS else baseMs
    }

    /**
     * One SG poll cycle (1 Hz / every second): navigation status every poll (with routeKey,
     * so unchanged routes omit geometry), plots every poll, device state
     * every poll, SLC offsets on connect and then per [currentSlcIntervalMs].
     * Returns null when the status fetch fails (drives the existing
     * failure/rediscovery logic).
     */
    private fun fetchSgTelemetry(endpoint: String): TelemetryPacket? {
        UsageCounters.count(UsageCounters.GALAXY_POLL)
        val client = sgClientFor(endpoint)
        // The first poll after a successful probe reuses the probe's
        // nav-status snapshot instead of refetching it (review A7).
        val probedStatus = if (pendingProbeEndpoint == endpoint) pendingProbeNavStatus else null
        pendingProbeNavStatus = null
        pendingProbeEndpoint = null
        val statusResp = probedStatus ?: client.fetchNavStatus(NavStateManager.sgRouteKey) ?: return null
        val status = statusResp.json

        // Captured BEFORE the store below overwrites the previous
        // snapshot: is this payload identical to the last one?
        val statusUnchanged = !NavStateManager.navClearedByUser &&
            NavStateManager.sgNavStatus?.toString() == status.toString()

        NavStateManager.sgNavStatus = status
        SgSnapshot.revisionOf(status)?.let { NavStateManager.sgRevision = it }
        statusResp.routeKey?.let { NavStateManager.sgRouteKey = it }

        // Provider + Google budget state rides every poll (Param,
        // 2026-11-18): refresh the comma's provider/budget snapshot,
        // and when any Google free limit reaches 95%, switch the
        // comma's Google providers to Mapbox. The phone side switches
        // itself through the same flag (PhoneGoogle's key gate).
        NavigationManager.applySnapshotMetadataOnly(status)
        maybeSwitchCommaProvidersOnBudget(
            SgGalaxyClient.baseUrlFor(getEffectiveNavigationEndpoint() ?: "192.168.68.9")
        )

        val plots = client.fetchPlots()

        sgPollCount++
        val nowState = System.currentTimeMillis()
        // Heartbeat: getting state (/api/device/state, with /api/system/monitor)
        // runs every second (Param, 2026-11-04) — i.e. on every poll cycle.
        if (nowState - lastSgDeviceStateFetchMs >= HEARTBEAT_MS) {
            lastSgDeviceStateFetchMs = nowState
            val deviceState = client.fetchDeviceState()
            // Journal the heartbeat's transitions (2026-11-19): lost,
            // then recovered with the outage length and missed beats.
            if (deviceState == null) {
                if (heartbeatLostSinceMs == 0L) {
                    heartbeatLostSinceMs = nowState
                    heartbeatMissedBeats = 0
                    ConnectionJournal.record(
                        ConnectionJournal.CAT_HEARTBEAT,
                        "heartbeat lost (the comma did not answer)"
                    )
                }
                heartbeatMissedBeats++
            } else if (heartbeatLostSinceMs != 0L) {
                val lostSeconds = (nowState - heartbeatLostSinceMs) / 1000.0
                ConnectionJournal.record(
                    ConnectionJournal.CAT_HEARTBEAT,
                    "heartbeat recovered after " +
                        String.format(java.util.Locale.US, "%.1f", lostSeconds) +
                        "s ($heartbeatMissedBeats missed beats)"
                )
                heartbeatLostSinceMs = 0L
                heartbeatMissedBeats = 0
            }
            // The heartbeat identifies the transport for the whole app
            // (Param, 2026-11-04): adopt the channel this state fetch
            // was actually served over; every other part of the app
            // follows NavStateManager.activeTransport from here.
            val identifiedTransport = HeartbeatTransport.identified(
                client.lastHeartbeatTransport, NavStateManager.activeTransport
            )
            if (identifiedTransport != NavStateManager.activeTransport) {
                NavStateManager.setActiveTransport(identifiedTransport)
            }
            if (identifiedTransport == NavStateManager.TransportType.IP) {
                // Local is serving: its tracked availability is
                // proven by the heartbeat itself.
                localAvailable = true
                commaIp?.let { lastProbedLocalIp = it }
            }
            val state = deviceState?.optString("state", "")?.ifEmpty { null }
            if (state != null) {
                lastSgDeviceState = state
            }

            if (NavStateManager.softwareUpdatePending) {
                // Addendum 126 quiet mode (Param, 2026-11-21: "When
                // updating state active don't send any requests other
                // then connectivity checks"): the heartbeat above is
                // the only traffic while an install is pending. Keep
                // the device state fresh from it, run the install
                // engine off it, and skip everything else in this poll
                // (monitor, offsets, slow poll, route, telemetry).
                if (state != null) {
                    NavStateManager.setDeviceState(state)
                }
                runPendingSoftwareInstall(client, deviceState)
                return null
            }

            // Extract battery voltage from /api/system/monitor (primary) and /api/device/state (fallback)
            val monitor = client.fetchSystemMonitor()
            // Keep the raw payload for the health screen (Feature 4): it
            // renders only the fields actually present, never guesses.
            if (monitor != null) {
                NavStateManager.lastSystemMonitor = monitor
            }
            val vMonitor = monitor?.optDouble("batteryVoltage", Double.NaN) ?: Double.NaN
            val vState = deviceState?.optDouble("batteryVoltage", deviceState.optDouble("voltage", Double.NaN)) ?: Double.NaN
            val volt = if (!vMonitor.isNaN() && vMonitor > 1.0) vMonitor else vState

            if (!volt.isNaN() && volt > 1.0) {
                NavStateManager.updatePower(PowerState(voltageV = volt), state)
            } else if (state != null) {
                NavStateManager.setDeviceState(state)
            }
            Log.d(TAG, "SG device state: ${state ?: "unknown"}, volt: ${if (!volt.isNaN()) volt else "none"}")
        }

        val now = System.currentTimeMillis()
        if (lastSlcFetchMs == 0L || now - lastSlcFetchMs > currentSlcIntervalMs()) {
            lastSlcFetchMs = now
            val offsets = client.fetchSlcOffsets()
            if (offsets != null) {
                slcNullStreak = 0
                NavStateManager.sgOffsetsMph = offsets
                Log.i(TAG, "SG SLC offsets loaded: ${offsets.joinToString()}")
            } else {
                // Current SG always answers null (the typed settings rows
                // carry no key); repeated nulls stretch the fetch interval
                // from 60 s to 10 min (see currentSlcIntervalMs).
                slcNullStreak++
            }
        }

        // Slow device poll (Features 13–15): software status, drive stats
        // and the active model change rarely — refresh them on their own
        // thread every ~5 minutes so the 1 s telemetry loop never stalls.
        val nowSlow = System.currentTimeMillis()
        if (nowSlow - lastSlowPollMs >= SLOW_POLL_INTERVAL_MS) {
            lastSlowPollMs = nowSlow
            Thread({ runSlowDevicePoll(client) }, "comma-slow-poll").start()
        }

        // Route publication is the expensive downstream of this poll
        // (full snapshot parse + state writes + listener dispatch):
        // when the status payload is byte-identical to the last one
        // and no local clear is pending, republishing can only
        // reproduce the same state (audit finding 3, scoped). The
        // packet below is still built and published every cycle —
        // live speed and freshness depend on it.
        if (!statusUnchanged) {
            publishSgRoute(status)
        } else if (NavStateManager.activeDestination != null && !NavStateManager.navClearedByUser) {
            // Reopen continuity on the unchanged path (bug fix,
            // 2026-11-09): the publish skip above also skipped
            // publishSgRoute's continuity ensures. After a process
            // restart mid-trip the comma's payload is byte-identical
            // to the pre-kill one, so without this the nav-refresh
            // loop and the phone->comma location feed (Addendum 62)
            // never resume. Both ensures are idempotent and
            // self-gating (the feeder checks its Phone Data toggle).
            NavigationManager.startNavigationRefresh { getEffectiveNavigationEndpoint() }
            getEffectiveNavigationEndpoint().takeIf { it.isNotBlank() }?.let {
                PhoneGpsFeeder.startNavigationSession(it)
            }
        }
        applyRouteLimitSeries()
        val pkt = TelemetryPacket.fromSg(
            status, plots, NavStateManager.sgOffsetsMph,
            if (HeartbeatTransport.phoneDataAllowed(NavStateManager.activeTransport)) gpsSpeedMs else 0.0
        )
        // Speed-limit mirror (audit finding 17): the packet parser
        // used to write this global itself; the writer now lives
        // here, with the poll's other side-effects.
        if (pkt.speedLimit.limitMs > 0.0 && NavStateManager.speedLimitMph <= 0) {
            // Round, like every other mph conversion in the app
            // (bug fix, 2026-11-09): .toInt() truncated, so a
            // 29.6 mph limit mirrored as 29 while the packet path
            // showed 30.
            NavStateManager.speedLimitMph =
                (pkt.speedLimit.limitMs * Units.MS_TO_MPH).roundToInt()
        }
        return pkt
    }

    /** Background refresh of the slow-changing device facts (Features 13–15). */
    private fun runSlowDevicePoll(client: SgGalaxyClient) {
        try {
            CommaSoftwareUpdate(client).fetchStatus()?.let { st ->
                NavStateManager.softwareChecked = true
                NavStateManager.softwareUpdateReady = st.updateReady
                NavStateManager.softwareUpdateLabel =
                    st.targetBranch.ifEmpty { st.installedDisplayVersion }.ifEmpty { null }
                // Addendum 128: record the comma's channel from the
                // installed branch — testing-only functionality gates
                // on it. An unreadable branch never downgrades a
                // channel already known.
                val branch = parseCommaBranch(st.branch)
                if (branch != CommaBranch.UNKNOWN && branch != NavStateManager.commaBranch) {
                    NavStateManager.commaBranch = branch
                    CommaBranchStore.save(context, branch)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Slow poll software status failed: ${e.message}")
        }
        try {
            val tz = java.net.URLEncoder.encode(java.util.TimeZone.getDefault().id, "UTF-8")
            client.getJson("/api/drives/stats?timezone=$tz")?.let { stats ->
                LastDrive.parse(stats)?.let { NavStateManager.lastDrive = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Slow poll drive stats failed: ${e.message}")
        }
        try {
            val fromManager = ActiveModel.extract(client.getJson(SgGalaxyClient.PATH_MODELS_MANAGER))
            val name = fromManager ?: ModelManagerRepository(client).getActiveModel()?.second
            if (!name.isNullOrBlank()) {
                NavStateManager.activeModelName = name
            }
        } catch (e: Exception) {
            Log.w(TAG, "Slow poll active model failed: ${e.message}")
        }
    }

    /**
     * Addendum 126: the pending-install engine, run once per heartbeat
     * from fetchSgTelemetry's quiet branch. Quiet mode allows no
     * requests beyond the connectivity check, so everything here is
     * driven by the heartbeat result alone: retry the install POST
     * while the comma answers and the car is parked; read completion
     * from the reboot cycle (accepted → heartbeat lost → heartbeat
     * back). The intent is persisted (SoftwareUpdateIntentStore), so
     * it survives app restarts; decisions are SoftwareUpdatePlan's.
     */
    private fun runPendingSoftwareInstall(client: SgGalaxyClient, deviceState: JSONObject?) {
        try {
            var pending = SoftwareUpdateIntentStore.load(context)
            if (pending == null) {
                NavStateManager.softwareUpdatePending = false
                return
            }
            NavStateManager.softwareUpdatePending = true
            val heartbeatUp = deviceState != null
            // Record the comma going down after an accepted install:
            // its return is the completion signal.
            if (!heartbeatUp && pending.acceptedAtMs > 0L && !pending.commaWentDown) {
                pending = pending.copy(commaWentDown = true)
                SoftwareUpdateIntentStore.save(context, pending)
            }
            val parked = deviceState?.optString("state", "")?.trim()
                ?.equals("parked", ignoreCase = true) == true
            val now = System.currentTimeMillis()
            when (SoftwareUpdatePlan.evaluate(pending, heartbeatUp, parked, now)) {
                SoftwareUpdatePlan.Decision.ATTEMPT -> {
                    val (code, _) = CommaSoftwareUpdate(client).postAction(
                        "install", pending.branch.ifEmpty { null }
                    )
                    val attempted = pending.copy(lastAttemptMs = now)
                    SoftwareUpdateIntentStore.save(
                        context,
                        if (code in 200..299) attempted.copy(acceptedAtMs = now) else attempted
                    )
                }
                SoftwareUpdatePlan.Decision.COMPLETED -> {
                    SoftwareUpdateIntentStore.clear(context)
                    NavStateManager.softwareUpdatePending = false
                    NavStateManager.softwareUpdateCompletionNote = "Comma software updated"
                }
                else -> Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pending software install failed: ${e.message}")
        }
    }

    /**
     * Publishes the comma's own route into NavStateManager when it changes.
     * Polls with an unchanged route carry routeUnchanged=true and no geometry,
     * so the previously published route is kept. When the comma's destination
     * disappears after we published a route, the route is cleared.
     */
    private fun publishSgRoute(status: JSONObject) {
        if (NavStateManager.navClearedByUser) {
            clearRouteForUserEnd(status)
            return
        }

        // THE snapshot parse (layer 2, SgSnapshot) — shared with
        // NavigationManager's snapshot writer (audit findings 1/10);
        // this function now applies only its own side-effects.
        val parsed = SgSnapshot.parse(status)
        val hasValidDest = parsed.hasValidDest
        val navEnded = parsed.navEnded
        val dName = parsed.destination?.name
            ?: (NavStateManager.activeDestination?.name ?: "Destination")

        seedCurrentGpsFromSnapshot(status)

        if (hasValidDest && !navEnded) {
            val newDest = checkNotNull(parsed.destination) {
                "snapshot reported a valid destination but parsed none"
            }
            if (NavStateManager.activeDestination?.isSameAs(newDest) != true) {
                NavStateManager.activeDestination = newDest
                NavStateManager.notifyChanged()
            }
            NavigationManager.startNavigationRefresh { getEffectiveNavigationEndpoint() }
            // Reopen continuity (Param, 2026-11-06): a route the
            // comma already has — adopted here, e.g. after the app
            // process restarted mid-trip or the route was started
            // on the comma itself — must resume the phone->comma
            // location feed too. The send paths start the session
            // for app-chosen destinations; an adopted route has no
            // send path, so without this the feed silently never
            // resumes and the comma stops receiving phone location
            // mid-route. startNavigationSession self-gates on the
            // "Phone location -> Comma" toggle and is idempotent
            // per endpoint, so calling it on every publish is safe.
            getEffectiveNavigationEndpoint()?.takeIf { it.isNotBlank() }?.let {
                PhoneGpsFeeder.startNavigationSession(it)
            }
        } else if (navEnded) {
            if (applyPublishedRouteEnd(status)) return
        }

        val serverSelected = status.optInt("selectedRoute", NavStateManager.selectedRouteId)
        if (NavStateManager.selectedRouteId != serverSelected) {
            NavStateManager.selectedRouteId = serverSelected
            NavStateManager.notifyChanged()
        }

        if (parsed.routeUnchanged) {
            if (NavStateManager.usePhoneSpeedLimit && NavStateManager.routeLimitSeries == null) {
                val route = NavStateManager.selectedRoute ?: NavStateManager.activeRoutes.firstOrNull()
                if (route != null) {
                    maybeFetchRouteLimits(route)
                }
            }
            if (NavStateManager.usePhoneSignals || NavStateManager.usePhoneStops) {
                val route = NavStateManager.selectedRoute ?: NavStateManager.activeRoutes.firstOrNull()
                if (route != null) {
                    maybeFetchRouteFeatures(route)
                }
            }
            return
        }

        if (!parsed.hasRouteGeometry) {
            // No route geometry and no destination: the comma has dropped the route.
            if (!hasValidDest || navEnded) {
                sgRoutePublished = false
                if (NavStateManager.activeRoutes.isNotEmpty()) {
                    NavStateManager.activeRoutes = emptyList()
                    NavStateManager.selectedRouteId = 0
                    NavStateManager.notifyChanged()
                }
            }
            return
        }

        val selectedIdx = parsed.selectedRouteIndex
        // Steps derived from geometry are a phone-side invention:
        // only when phone routing is on. With it off, comma routes
        // carry no invented steps. (Derivation was inlined in both
        // route constructions before the shared parse.)
        val routesList = parsed.routes.map { route ->
            if (NavStateManager.usePhoneRoute) {
                route.copy(
                    steps = NavigationManager.deriveStepsFromPolyline(route.points, dName)
                )
            } else {
                route
            }
        }

        if (routesList.isEmpty()) return

        // Never resurrect routes or fabricate a destination if navigation ended or was cleared
        if (navEnded || NavStateManager.navClearedByUser) {
            if (NavStateManager.activeRoutes.isNotEmpty()) {
                sgRoutePublished = false
                NavStateManager.activeRoutes = emptyList()
                NavStateManager.selectedRouteId = 0
                NavStateManager.notifyChanged()
            }
            return
        }

        if (NavStateManager.activeDestination == null) {
            // Adopt from the route itself when the snapshot's destination
            // record is missing or carries no coordinates: the comma IS
            // navigating (its route is on screen), so guidance (turn card,
            // ETA) must exist too (field report, 2026-11-03: route drawn on
            // the car screen with no turn-by-turn card). The navEnded and
            // navClearedByUser cases already returned above.
            val lastPt = routesList.first().points.last()
            NavStateManager.activeDestination = NavDestination(dName, "Car Destination", lastPt.latitude, lastPt.longitude)
        }

        if (NavStateManager.activeDestination != null) {
            NavStateManager.activeRoutes = routesList
            NavStateManager.selectedRouteId = selectedIdx
            sgRoutePublished = true
            NavStateManager.notifyChanged()
            // Markers + limits belong to the SELECTED route, not
            // whichever alternative happens to be listed first.
            val selRoute = routesList.firstOrNull { it.id == selectedIdx } ?: routesList.first()
            maybeFetchRouteLimits(selRoute)
            maybeFetchRouteFeatures(selRoute)
        }
    }

    /**
     * publishSgRoute, user-end latch branch (extracted 2026-11-08):
     * the user ended navigation — drop any local route state, and
     * when the comma finally drops the destination too, resolve
     * the End Route state.
     */
    private fun clearRouteForUserEnd(status: JSONObject) {
        if (NavStateManager.activeRoutes.isNotEmpty() || NavStateManager.activeDestination != null) {
            sgRoutePublished = false
            NavStateManager.activeRoutes = emptyList()
            NavStateManager.activeDestination = null
            NavStateManager.selectedRouteId = 0
            NavigationManager.stopNavigationRefresh()
            NavStateManager.notifyChanged()
        }
        // End Route confidence (bug fix, 2026-11-08): when the
        // comma finally drops the destination, resolve STUCK
        // here. This poll writer is the only one still running
        // after an End — the nav-refresh writer was stopped by
        // the teardown and self-stops with no destination — so
        // without this the "Comma still navigating" banner
        // latched forever even after the device let go.
        if (SgSnapshot.parse(status).navEnded) {
            NavStateManager.noteEndRouteSnapshotConfirmed()
        }
    }

    /**
     * publishSgRoute, position seed (extracted 2026-11-08): until
     * the telemetry packet lands, the snapshot's location seeds
     * the shared current position so the map has somewhere real
     * to sit.
     */
    private fun seedCurrentGpsFromSnapshot(status: JSONObject) {
        val loc = status.optJSONObject("location")
        val sLat = loc?.optDouble("latitude", Double.NaN) ?: Double.NaN
        val sLng = loc?.optDouble("longitude", Double.NaN) ?: Double.NaN
        if (!sLat.isNaN() && !sLng.isNaN() && NavStateManager.currentGps == null) {
            NavStateManager.currentGps = GpsPosition(
                latitude = sLat,
                longitude = sLng,
                bearing = loc?.optDouble("bearing", 0.0) ?: 0.0,
                speed = 0.0,
                hasFix = true
            )
        }
    }

    /**
     * publishSgRoute, route-ended branch (extracted 2026-11-08).
     * Returns true when the end was applied and the caller must
     * stop; false while the destination-pending grace is running
     * (the caller then continues with the rest of the publish).
     */
    private fun applyPublishedRouteEnd(status: JSONObject): Boolean {
        if (NavStateManager.isDestinationPendingGrace()) return false
        // Same device-confirmation note the refresh writer
        // makes (bug fix, 2026-11-08): whichever writer sees
        // the end first resolves the End Route state.
        NavStateManager.noteEndRouteSnapshotConfirmed()
        // Arrival (bug fix, 2026-11-08): build the card via
        // the shared note BEFORE clearing — this writer
        // used to clear the destination and stop the
        // refresh loop first, so the arrival was lost
        // whenever the poll ticked before the loop.
        if (status.optString("status", "").lowercase() == "arrived") {
            NavStateManager.noteCommaArrival()
        }
        var changed = false
        sgRoutePublished = false
        // The trip is over: drop the route's limit series and
        // the last street text (it otherwise outlives the trip
        // on the car screen — "Your destination will be on the
        // right" still showing after arrival, 2026-11-04).
        NavStateManager.routeLimitSeries = null
        lastRouteLimitKey = null
        clearRouteFeatures()
        if (NavStateManager.currentStreet != null) {
            NavStateManager.currentStreet = null
            changed = true
        }
        if (NavStateManager.activeDestination != null) {
            NavStateManager.activeDestination = null
            changed = true
        }
        if (NavStateManager.activeRoutes.isNotEmpty()) {
            NavStateManager.activeRoutes = emptyList()
            NavStateManager.selectedRouteId = 0
            changed = true
        }
        NavigationManager.stopNavigationRefresh()
        if (changed) {
            NavStateManager.notifyChanged()
        }
        return true
    }

    /** Key of the route the limit series was last fetched for (destination + shape). */
    @Volatile
    private var lastRouteLimitKey: String? = null

    /**
     * "Use phone speed limit" data fetch: the comma's payload carries
     * no speed limit anywhere, so when the toggle is on AND the saved
     * Mapbox token may be used (the Mapbox key toggle) AND a token
     * exists, fetch Mapbox Directions annotations for the adopted
     * route once per route. Failures leave the series unset — the
     * sign simply shows no limit, never an invented one.
     */
    @Volatile
    private var googleBudgetSwitchInFlight = false

    @Volatile
    private var googleBudgetSwitchDone = false

    @Volatile
    private var lastGoogleBudgetSwitchAttemptMs = 0L

    /**
     * The comma half of the 95% Google budget rule (Param,
     * 2026-11-18): when the comma's own snapshot shows any Google
     * free limit at/over 95% and a provider is still set to Google,
     * write that provider back to Mapbox through the navigation
     * configure action (provider-only patches need no parked car).
     * One successful switch per budget period — the done flag
     * re-arms when the reported usage drops back under the
     * threshold (the comma's counters reset monthly/daily). A
     * failed write retries on a later poll, spaced a minute apart.
     */
    internal fun maybeSwitchCommaProvidersOnBudget(baseUrl: String) {
        val state = NavStateManager.commaNavProviders ?: return
        if (!state.budgetSwitchDue()) {
            googleBudgetSwitchDone = false
            return
        }
        if (googleBudgetSwitchDone) return
        val mapTarget = if (state.mapProvider == GoogleNav.GOOGLE) {
            GoogleNav.MAPBOX
        } else {
            null
        }
        val routeTarget = if (state.routeProvider == GoogleNav.GOOGLE) {
            GoogleNav.MAPBOX
        } else {
            null
        }
        if (mapTarget == null && routeTarget == null) {
            // Nothing left on Google — the switch is already effective.
            googleBudgetSwitchDone = true
            return
        }
        val now = System.currentTimeMillis()
        if (googleBudgetSwitchInFlight ||
            now - lastGoogleBudgetSwitchAttemptMs < 60_000
        ) {
            return
        }
        googleBudgetSwitchInFlight = true
        lastGoogleBudgetSwitchAttemptMs = now
        Thread({
            try {
                val (code, _) = NavigationManager().configureNavSettingsSg(
                    baseUrl,
                    GoogleNav.providerPatch(
                        mapProvider = mapTarget, routeProvider = routeTarget
                    )
                )
                if (code == 200) googleBudgetSwitchDone = true
            } finally {
                googleBudgetSwitchInFlight = false
            }
        }, "google-budget-switch").start()
    }

    internal fun maybeFetchRouteLimits(route: RouteOption) {
        if (!NavStateManager.usePhoneSpeedLimit) return
        // Cloud gate (Param, 2026-11-12): the phone speed limit is
        // neither fetched nor used while the transport is Cloud.
        if (!HeartbeatTransport.phoneDataAllowed(NavStateManager.activeTransport)) return
        val token = (mapboxToken ?: NavStateManager.mapboxToken)?.trim()?.takeIf { it.isNotBlank() }
        // Google Roads is the speed-limit source when the phone
        // Google key may be used (Param, 2026-11-18); Mapbox remains
        // the fallback when Google is off, keyless, or its fetch fails.
        val googleKey = PhoneGoogle.effectiveGoogleKey()
        if (token.isNullOrBlank() && googleKey == null) return
        val dest = NavStateManager.activeDestination ?: return
        if (!dest.hasCoordinates || route.points.size < 2) return
        val origin = route.points.first()
        // The key carries the route GEOMETRY fingerprint too (same
        // fix as the feature markers, 2026-11-10): alternatives
        // share endpoints + often the point count, and the old key
        // then kept the previous route's limits.
        val fp = RouteFeatures.fingerprint(route.points)
        val key = String.format(
            java.util.Locale.US, "%.4f,%.4f>%.4f,%.4f#%d",
            origin.latitude, origin.longitude, dest.latitude, dest.longitude, route.points.size
        ) + "@" + fp
        // Same staleness rule as the feature markers (2026-11-12): a
        // series fetched for another route's geometry must not drive
        // the limit on this one — drop it; the fetch below replaces it.
        if (key != lastRouteLimitKey && NavStateManager.routeLimitSeries != null) {
            NavStateManager.routeLimitSeries = null
        }
        if (key == lastRouteLimitKey && NavStateManager.routeLimitSeries != null) return
        if (!routeLimitsInFlight.compareAndSet(false, true)) return
        Thread({
            try {
                var seriesSource = "mapbox"
                val series = if (googleKey != null) {
                    val googleSeries = NavigationManager()
                        .fetchGoogleRouteLimits(route.points, googleKey)
                    if (googleSeries != null) {
                        seriesSource = "google"
                        googleSeries
                    } else if (!token.isNullOrBlank()) {
                        NavigationManager().fetchMapboxRouteLimits(
                            origin.latitude, origin.longitude,
                            dest.latitude, dest.longitude, token!!
                        )
                    } else {
                        null
                    }
                } else if (!token.isNullOrBlank()) {
                    NavigationManager().fetchMapboxRouteLimits(
                        origin.latitude, origin.longitude,
                        dest.latitude, dest.longitude, token!!
                    )
                } else {
                    null
                }
                if (series != null && isStillSelectedRoute(fp)) {
                    lastRouteLimitKey = key
                    NavStateManager.routeLimitSeries = series
                    NavStateManager.routeLimitSource = seriesSource
                    applyRouteLimitSeries()
                    NavStateManager.notifyChanged()
                }
            } finally {
                routeLimitsInFlight.set(false)
            }
        }, "route-limits").start()
    }

    /**
     * Keeps the displayed limit on the route segment the car is
     * currently on. The limit application itself lives in
     * NavStateManager.updateSpeedLimitFromRoute (audit finding 12:
     * this class used to carry a verbatim second copy).
     */
    internal fun applyRouteLimitSeries() {
        val gps = NavStateManager.currentGps
            ?: NavStateManager.latestPacket?.gps
            ?: lastPhoneGps
            ?: return
        NavStateManager.updateSpeedLimitFromRoute(gps.latitude, gps.longitude)
    }


    /** Route keys the feature markers were last fetched for (per kind). */
    @Volatile
    private var lastSignalKey: String? = null

    @Volatile
    private var lastStopKey: String? = null

    /** One feature fetch at a time + a retry pause after a failed
     *  attempt (field report, 2026-11-10: the fetch runs off 1 s
     *  poll/refresh paths, and an unguarded slow sweep — up to 90
     *  Tilequery calls, or a 30 s Overpass query — let every cycle
     *  spawn another full sweep thread until the app ANR'd). */
    private val routeFeaturesInFlight = AtomicBoolean(false)

    @Volatile
    private var lastSignalAttemptMs = 0L

    @Volatile
    private var lastStopAttemptMs = 0L

    private val routeLimitsInFlight = AtomicBoolean(false)
    private val localProbeInFlight = AtomicBoolean(false)
    @Volatile private var localAvailable = false
    @Volatile private var lastProbedLocalIp: String? = null

    /** Pause before re-attempting a failed feature fetch. */
    private val FEATURE_RETRY_MS = 30_000L

    /** Drops both route-feature marker sets (route ended/cleared). */
    internal fun clearRouteFeatures() {
        if (NavStateManager.routeSignals.isNotEmpty()) NavStateManager.routeSignals = emptyList()
        if (NavStateManager.routeStops.isNotEmpty()) NavStateManager.routeStops = emptyList()
        lastSignalKey = null
        lastStopKey = null
    }

    /**
     * Route feature markers fetch (Param, 2026-11-10): traffic
     * signals from Mapbox Tilequery (toggle + saved token) and stop
     * signs from OSM Overpass (toggle), once per route per kind.
     * Failures leave that kind's markers unset — nothing is drawn
     * rather than anything invented.
     */
    /**
     * Whether [routeKey] (a RouteFeatures fingerprint) still names
     * the route the app currently treats as selected — the same
     * selection (with the same first-route fallback) the feature and
     * limit fetch call sites use. A fetch that lands after the
     * selection moved on must not publish its results.
     */
    private fun isStillSelectedRoute(routeKey: String): Boolean {
        val sel = NavStateManager.selectedRoute ?: return false
        if (sel.points.size < 2) return false
        return RouteFeatures.fingerprint(sel.points) == routeKey
    }

    internal fun maybeFetchRouteFeatures(route: RouteOption) {
        if (route.points.size < 2) return
        val wantSignals = NavStateManager.usePhoneSignals
        val wantStops = NavStateManager.usePhoneStops
        if (!wantSignals && !wantStops) return
        // Identity is the route GEOMETRY (fingerprint), not just
        // endpoints + point count: alternative routes share the
        // endpoints and often the count, and the old key then
        // skipped the refetch (field report, 2026-11-10: lights
        // not refetched when an alternative route was selected).
        val routeKey = RouteFeatures.fingerprint(route.points)
        // The route changed under the displayed markers: they belong
        // to another route's geometry and come off NOW (wrong markers
        // are never better than none). The stored key goes with them
        // so the fetch decision below starts clean.
        if (wantSignals && RouteFeatures.markersStale(lastSignalKey, routeKey, NavStateManager.routeSignals.isNotEmpty())) {
            NavStateManager.routeSignals = emptyList()
            lastSignalKey = null
            NavStateManager.notifyChanged()
        }
        if (wantStops && RouteFeatures.markersStale(lastStopKey, routeKey, NavStateManager.routeStops.isNotEmpty())) {
            NavStateManager.routeStops = emptyList()
            lastStopKey = null
            NavStateManager.notifyChanged()
        }
        val token = (mapboxToken ?: NavStateManager.mapboxToken)?.trim()?.takeIf { it.isNotBlank() }
        val now = System.currentTimeMillis()
        val doSignals = wantSignals && token != null && routeKey != lastSignalKey &&
            now - lastSignalAttemptMs >= FEATURE_RETRY_MS
        val doStops = wantStops && routeKey != lastStopKey &&
            now - lastStopAttemptMs >= FEATURE_RETRY_MS
        if (!doSignals && !doStops) return
        if (!routeFeaturesInFlight.compareAndSet(false, true)) return
        if (doSignals) lastSignalAttemptMs = now
        if (doStops) lastStopAttemptMs = now
        val points = route.points
        Thread({
            try {
                if (doSignals) {
                    val candidates = NavigationManager().fetchRouteSignals(points, token!!)
                    if (candidates != null) {
                        if (isStillSelectedRoute(routeKey)) {
                            lastSignalKey = routeKey
                            NavStateManager.routeSignals =
                                RouteFeatures.alongRoute(points, candidates, RouteFeatures.Kind.SIGNAL)
                            NavStateManager.notifyChanged()
                        } else {
                            // The route changed mid-sweep: drop these
                            // markers and let the next trigger refetch
                            // for the route actually being navigated.
                            lastSignalAttemptMs = 0L
                        }
                    }
                }
                if (doStops) {
                    val candidates = NavigationManager().fetchRouteStops(points)
                    if (candidates != null) {
                        if (isStillSelectedRoute(routeKey)) {
                            lastStopKey = routeKey
                            NavStateManager.routeStops =
                                RouteFeatures.alongRoute(points, candidates, RouteFeatures.Kind.STOP)
                            NavStateManager.notifyChanged()
                        } else {
                            lastStopAttemptMs = 0L
                        }
                    }
                }
            } finally {
                routeFeaturesInFlight.set(false)
            }
        }, "route-features").start()
    }

    private var lastDirectStateFetchMs = 0L

    private fun fetchDirectDeviceState(endpoint: String) {
        val now = System.currentTimeMillis()
        // Heartbeat cadence also applies to the direct (non-SG) state fetch.
        if (now - lastDirectStateFetchMs < HEARTBEAT_MS) return
        lastDirectStateFetchMs = now
        val ble = BleBridgeClient.getConnectedClient()
        if (ble != null) {
            try {
                val resp = ble.request(
                    method = "GET",
                    path = "/api/device/state",
                    headers = mapOf("Accept" to "application/json"),
                    timeoutMs = 4000
                )
                if (resp.status == 200) {
                    // Direct heartbeat served over BLE: this identifies
                    // the app transport (Param, 2026-11-04).
                    NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
                    val body = resp.bodyText()
                    val json = JSONObject(body)
                    val state = json.optString("state", "").ifEmpty { null }
                    val volt = json.optDouble("batteryVoltage", json.optDouble("voltage", Double.NaN))
                    if (!volt.isNaN() && volt > 1.0) {
                        NavStateManager.updatePower(PowerState(voltageV = volt), state)
                    } else if (state != null) {
                        NavStateManager.setDeviceState(state)
                    }
                    return
                }
            } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http://$endpoint:${NetworkScanner.PORT}/api/device/state")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                useCaches = false
                val token = GalaxyCloudSession.currentLocalToken()
                if (!token.isNullOrEmpty()) {
                    setRequestProperty("Cookie", "${GalaxyCloudSession.COOKIE_NAME}=$token")
                }
            }
            if (conn.responseCode == 200) {
                // Direct heartbeat served over LAN: this identifies
                // the app transport (Param, 2026-11-04).
                NavStateManager.setActiveTransport(NavStateManager.TransportType.IP)
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val state = json.optString("state", "").ifEmpty { null }
                val volt = json.optDouble("batteryVoltage", json.optDouble("voltage", Double.NaN))
                if (!volt.isNaN() && volt > 1.0) {
                    NavStateManager.updatePower(PowerState(voltageV = volt), state)
                } else if (state != null) {
                    NavStateManager.setDeviceState(state)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") } finally {
            conn?.disconnect()
        }
    }

    private fun fetchParams(target: String, isCloud: Boolean, isRetry: Boolean = false): TelemetryPacket? {
        // Channel follows the heartbeat-identified transport
        // (HeartbeatTransport): the direct heartbeat runs first each
        // poll and identifies it; this fetch never flips it.
        val ble = if (HeartbeatTransport.useBle(NavStateManager.activeTransport, NavStateManager.connectionMode)) {
            BleBridgeClient.getConnectedClient()
        } else {
            null
        }
        if (ble != null && !isCloud) {
            try {
                val resp = ble.request(
                    method = "GET",
                    path = "/api/params/all",
                    headers = mapOf("Accept" to "application/json"),
                    timeoutMs = 8000
                )
                if (resp.status == 200) {
                    val body = resp.bodyText()
                    val pkt = TelemetryPacket.fromParams(body, gpsSpeedMs)
                    val newMbToken = pkt?.mapboxToken
                    if (!newMbToken.isNullOrBlank() && newMbToken != mapboxToken) {
                        setMapboxToken(newMbToken)
                    }
                    return pkt
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchParams over BLE failed: ${e.message}")
            }
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = if (isCloud) {
                val base = if (target.startsWith("http")) target else "https://$target"
                URL("$base/api/params/all")
            } else {
                URL("http://$target:${NetworkScanner.PORT}/api/params/all")
            }
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = if (isCloud) 5000 else 2500
                readTimeout = if (isCloud) 8000 else 5000
                useCaches = false
                val token = if (isCloud) GalaxyCloudSession.currentToken() else GalaxyCloudSession.currentLocalToken()
                if (!token.isNullOrEmpty()) {
                    setRequestProperty("Cookie", "${GalaxyCloudSession.COOKIE_NAME}=$token")
                }
                if (isCloud) {
                    setRequestProperty("Origin", NetworkScanner.GALAXY_URL)
                    setRequestProperty("Referer", "${NetworkScanner.GALAXY_URL}/")
                    setRequestProperty("User-Agent", NetworkScanner.GALAXY_USER_AGENT)
                }
            }
            if (conn.responseCode == 401) {
                conn.disconnect()
                if (isRetry) return null
                val refreshed = if (isCloud) {
                    val base = if (target.startsWith("http")) target else "https://$target"
                    GalaxyCloudSession.login(base)
                } else {
                    GalaxyCloudSession.refreshLocalSession("http://$target:${NetworkScanner.PORT}")
                }
                if (refreshed) {
                    return fetchParams(target, isCloud, isRetry = true)
                }
                return null
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }

            val pkt = TelemetryPacket.fromParams(body, gpsSpeedMs)
            val newMbToken = pkt?.mapboxToken
            if (!newMbToken.isNullOrBlank() && newMbToken != mapboxToken) {
                mapboxToken = newMbToken
                NavStateManager.mapboxToken = newMbToken
                NavStateManager.notifyChanged()
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_MAPBOX_TOKEN, newMbToken).apply()
                try {
                    org.maplibre.android.MapLibre.setApiKey(newMbToken)
                } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            }
            pkt
        } catch (e: Exception) {
            Log.e(TAG, "fetchParams error connecting to $target: ${e.message}")
            if (!isCloud && NavStateManager.connectionMode == MODE_AUTO) {
                val fallbackBle = BleBridgeClient.getConnectedClient()
                if (fallbackBle != null) {
                    try {
                        val resp = fallbackBle.request(
                            method = "GET",
                            path = "/api/params/all",
                            headers = mapOf("Accept" to "application/json"),
                            timeoutMs = 4000
                        )
                        if (resp.status == 200) {
                            NavStateManager.setActiveTransport(NavStateManager.TransportType.BLUETOOTH)
                            val body = resp.bodyText()
                            return TelemetryPacket.fromParams(body, gpsSpeedMs)
                        }
                    } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                }
            }
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val SLOW_POLL_INTERVAL_MS = 300_000L
        private const val TAG = "CommaClient"
        private const val POLL_MS = 1000L
        /** Heartbeat interval: getting state (/api/device/state) every second (Param, 2026-11-04). */
        private const val HEARTBEAT_MS = 1000L
        private const val GALAXY_POLL_MS = 1200L
        /** SG poll interval while the comma reports a parked-style state. */
        private const val SG_PARKED_POLL_MS = 5000L
        private const val SLC_POLL_MS = 60_000L
        /** SLC-offset fetch interval once the null streak hits the threshold. */
        private const val SLC_BACKOFF_MS = 600_000L
        private const val SLC_NULL_STREAK_FOR_BACKOFF = 3
        /** Device-state strings (from SG /api/device/state) that mean "not driving". */
        private val PARKED_DEVICE_STATES = setOf("parked", "standby", "offroad", "off-road")
        private const val MAX_FAILURES = 3
        private const val PREFS = "comma_client"
        private const val KEY_IP = "comma_ip"
        const val KEY_USER_LOCAL_IP = "user_local_ip"
        const val KEY_LAST_SCANNED_IP = "last_scanned_ip"
        private const val KEY_CLOUD_MANUAL = "cloud_manual"
        private const val KEY_GALAXY_URL = "galaxy_url"
        private const val KEY_MAPBOX_TOKEN = "mapbox_token"
        const val KEY_GALAXY_COOKIE = "galaxy_cookie"
        const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_USE_PHONE_SPEED = "use_phone_speed"
        private const val KEY_USE_PHONE_LOCATION = "use_phone_location"
        private const val KEY_USE_PHONE_LOCATION_TO_COMMA = "use_phone_location_to_comma"
        private const val KEY_USE_PHONE_MAPBOX_KEY = "use_phone_mapbox_key"
        private const val KEY_USE_PHONE_SPEED_LIMIT = "use_phone_speed_limit"
        private const val KEY_USE_PHONE_ROUTE = "use_phone_route"
        private const val KEY_USE_PHONE_SEARCH = "use_phone_search"
        private const val KEY_USE_PHONE_3D = "use_phone_3d"
        private const val KEY_USE_PHONE_TRAFFIC = "use_phone_traffic"
        private const val KEY_USE_PHONE_SIGNALS = "use_phone_signals"
        private const val KEY_USE_PHONE_STOPS = "use_phone_stops"
        private const val KEY_USE_PHONE_VECTOR_MAP = "use_phone_vector_map"
        private const val KEY_GOOGLE_API_KEY = "google_api_key"
        private const val KEY_USE_PHONE_GOOGLE_KEY = "use_phone_google_key"
        private const val KEY_PHONE_MAP_SOURCE = "phone_map_source"

        const val MODE_AUTO = "auto"
        const val MODE_BLUETOOTH = "bluetooth"
        const val MODE_LOCAL = "local"
        const val MODE_CLOUD = "cloud"
        const val DEFAULT_GALAXY_URL = NetworkScanner.GALAXY_URL
        const val DEFAULT_GALAXY_COOKIE =
            ""

        @Volatile
        internal var instance: CommaClient? = null

        fun getInstance(context: Context): CommaClient {
            return instance ?: synchronized(this) {
                instance ?: CommaClient(context.applicationContext).also { instance = it }
            }
        }
    }
}
