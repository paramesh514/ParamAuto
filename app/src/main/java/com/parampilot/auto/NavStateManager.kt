package com.parampilot.auto

import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

/**
 * Global singleton managing live synchronized navigation and telemetry state
 * between [MainActivity] (phone UI) and [NavScreen] (Android Auto car UI).
 *
 * Guarantees that both phone and car screens display the exact same:
 * - Speed (MPH)
 * - Speed Limit (MUTCD sign)
 * - Vehicle GPS & heading
 * - Battery Voltage (12V)
 * - Comma connection status
 * - Active destination, route polyline, and turn cues
 * - Mapbox token
 */
object NavStateManager {

    @Volatile
    var appContext: android.content.Context? = null


    @Volatile
    var activeDestination: NavDestination? = null

    @Volatile
    var destinationSelectedAtMs: Long = 0L

    fun isDestinationPendingGrace(): Boolean {
        val elapsed = System.currentTimeMillis() - destinationSelectedAtMs
        return activeDestination != null && elapsed in 0..15_000L
    }

    fun markDestinationSelected(dest: NavDestination) {
        activeDestination = dest
        destinationSelectedAtMs = System.currentTimeMillis()
        navClearedByUser = false
        clearedDestination = null
    }

    // ---- Trip tracking for the arrival card (Feature 2) ----
    // Set when the comma CONFIRMS a destination (NavigationManager's
    // lastConfirmedDest sites). Totals start unknown (0) and are filled from
    // the comma's selected-route totals when a snapshot provides them; a
    // total the device never provides stays 0 and renders as "—".

    @Volatile
    var tripDestinationName: String? = null

    @Volatile
    var tripStartedAtMs: Long = 0L

    @Volatile
    var tripDurationMin: Int = 0

    @Volatile
    var tripDistanceMiles: Double = 0.0

    /** A completed arrival waiting to be shown by the phone UI (consume-once). */
    @Volatile
    var pendingArrival: ArrivalSummary? = null

    /**
     * The most recent arrival, retained after [pendingArrival] is
     * consumed so a display that was not focused at the moment of
     * arrival (e.g. the Android Auto surface) can still show the
     * arrival card briefly.
     */
    @Volatile
    var lastArrival: ArrivalSummary? = null

    @Volatile
    var lastArrivalAtMs: Long = 0L

    /**
     * End Route confidence (backlog #3): the comma-confirmed state of
     * the route the user just ended — what the nav header shows
     * (Ending… / Route ended ✓ / Comma still navigating) instead of
     * going silently blank. Same retained pattern as lastArrival /
     * lastDriveSummary: the phone consumes nothing; both displays read
     * these fields and the pure EndRouteConfidence rules decide what
     * shows. A live destination always wins over a stale end state
     * (see EndRouteConfidence.isVisible).
     */
    @Volatile
    var endRouteState: EndRouteConfidence.State = EndRouteConfidence.State.IDLE

    /** Destination name the current end state is about; null when idle. */
    @Volatile
    var endRouteDestName: String? = null

    /** When the current end state was entered (action confirmation for ENDED). */
    @Volatile
    var endRouteAtMs: Long = 0L

    /** The user tapped End Route; the clear action is in flight. */
    fun noteEndRouteStarted(destName: String?) {
        endRouteState = EndRouteConfidence.onEndRequested()
        endRouteDestName = destName
        endRouteAtMs = System.currentTimeMillis()
        notifyChanged()
    }

    /** The comma confirmed the clear action; the route is ended. */
    fun noteEndRouteActionConfirmed() {
        endRouteState = EndRouteConfidence.onActionConfirmed(endRouteState)
        endRouteAtMs = System.currentTimeMillis()
        notifyChanged()
    }

    /** The clear action failed: the route is still live, so the header goes back to normal navigation. */
    fun noteEndRouteFailed() {
        val next = EndRouteConfidence.onEndFailed(endRouteState)
        if (next != endRouteState) {
            endRouteState = next
            notifyChanged()
        }
    }

    /**
     * A snapshot with no destination confirms the end (also resolves
     * STUCK when the device finally lets go). No-op in any other state.
     */
    fun noteEndRouteSnapshotConfirmed() {
        val next = EndRouteConfidence.onSnapshotNoDestination(endRouteState)
        if (next != endRouteState) {
            endRouteState = next
            endRouteAtMs = System.currentTimeMillis()
            notifyChanged()
        }
    }

    /**
     * A packet/snapshot still carrying a destination the user ended.
     * Only the destination this end state is about can prove the end
     * didn't stick — any other destination is a new trip (the adoption
     * paths reset the state via [noteNewDestination] instead).
     */
    fun noteEndRouteSnapshotStillHasDestination(candidate: NavDestination?) {
        val matches = candidate != null &&
            (clearedDestination?.isSameAs(candidate) == true || candidate.name == endRouteDestName)
        if (!matches) return
        val next = EndRouteConfidence.onSnapshotStillHasDestination(
            endRouteState, endRouteAtMs, System.currentTimeMillis()
        )
        if (next != endRouteState) {
            endRouteState = next
            notifyChanged()
        }
    }

    /** A destination the app accepts supersedes any end state. */
    fun noteNewDestination() {
        if (endRouteState != EndRouteConfidence.State.IDLE) {
            endRouteState = EndRouteConfidence.onNewDestination()
            endRouteDestName = null
            endRouteAtMs = 0L
        }
    }

    fun markTripStarted(dest: NavDestination) {
        tripDestinationName = dest.name
        tripStartedAtMs = System.currentTimeMillis()
        tripDurationMin = 0
        tripDistanceMiles = 0.0
        pendingArrival = null
        lastDriveSummary = null
        lastDriveSummaryAtMs = 0L
        // Drive summary (Day 3): start accumulating real fixes for this
        // trip. startIfNew — a re-confirm of the same destination mid-trip
        // must not reset the totals already accumulated.
        driveTracker.startIfNew(dest.name, System.currentTimeMillis())
    }

    /** Fills trip totals from the comma's route data; only ever fills gaps. */
    /**
     * Builds the pending arrival card from the tracked trip
     * (Feature 2), exactly once. Called by BOTH snapshot writers
     * when the comma reports "arrived" (bug fix, 2026-11-08): the
     * poll writer used to clear the destination and stop the
     * refresh loop before the refresh writer could build the
     * card, so the arrival was silently lost whenever the poll
     * ticked first. Totals the device never provided stay 0 and
     * render as "—".
     */
    fun noteCommaArrival() {
        if (pendingArrival != null) return
        val arrivedName = tripDestinationName ?: activeDestination?.name
        if (arrivedName != null) {
            val summary = ArrivalSummary(
                destinationName = arrivedName,
                durationMin = tripDurationMin,
                distanceMiles = tripDistanceMiles
            )
            pendingArrival = summary
            // Retained copy for displays that were not focused at
            // the moment of arrival (the AA surface).
            lastArrival = summary
            lastArrivalAtMs = System.currentTimeMillis()
        }
        clearTripTracking()
    }

    fun noteTripRouteTotals(durationMin: Int, distanceMiles: Double) {
        if (tripDestinationName == null) return
        if (tripDurationMin <= 0 && durationMin > 0) tripDurationMin = durationMin
        if (tripDistanceMiles <= 0.0 && distanceMiles > 0.0) tripDistanceMiles = distanceMiles
    }

    fun clearTripTracking() {
        // Drive summary (Day 3): finalize BEFORE the trip fields are
        // reset — this is the single finish point for arrival (snapshot
        // navEnded), user End Route (clear()), and comma-cleared routes.
        // A parked/never-moved trip finishes to null and shows no card.
        driveTracker.finish(System.currentTimeMillis())?.let {
            pendingDriveSummary = it
            // Retained copy for the Android Auto surface: the phone UI
            // consumes pendingDriveSummary once, but the car screen was
            // usually not focused at that moment — same pattern as
            // lastArrival, so the AA surface can still show it briefly.
            lastDriveSummary = it
            lastDriveSummaryAtMs = System.currentTimeMillis()
        }
        tripDestinationName = null
        tripStartedAtMs = 0L
        tripDurationMin = 0
        tripDistanceMiles = 0.0
    }

    /** Drive summary accumulated from real fixes on the current trip. */
    private val driveTracker = DriveSummaryTracker()

    /** A finished drive summary waiting to be shown by the phone UI (consume-once). */
    @Volatile
    var pendingDriveSummary: DriveSummary? = null

    /**
     * The most recent finished drive summary, retained after
     * [pendingDriveSummary] is consumed so the Android Auto surface can
     * show what was actually driven for a short window after the trip
     * ends (arrival or End Route) — the drive-summary counterpart of
     * [lastArrival].
     */
    @Volatile
    var lastDriveSummary: DriveSummary? = null

    @Volatile
    var lastDriveSummaryAtMs: Long = 0L

    /** Raw last /api/system/monitor payload from the comma (Feature 4). */
    @Volatile
    var lastSystemMonitor: JSONObject? = null

    val isNavigating: Boolean
        get() = activeDestination != null && !navClearedByUser

    /**
     * Set to true when the user explicitly ends navigation (taps ✕ End).
     * Prevents stale Comma telemetry packets from re-activating the route.
     * Cleared automatically when the Comma confirms destination == null.
     */
    @Volatile
    var navClearedByUser: Boolean = false
        private set

    @Volatile
    var clearedDestination: NavDestination? = null

    fun setNavClearedByUser(cleared: Boolean) {
        navClearedByUser = cleared
    }

    @Volatile
    var activeRoutes: List<RouteOption> = emptyList()

    @Volatile
    var selectedRouteId: Int = 0

    /** Comma Mapbox search budget from the latest snapshot (Feature 6); null while unknown. */
    @Volatile
    var mapboxUsage: MapboxUsage? = null

    /**
     * The comma's navigation provider state (Google update, Nov 2026):
     * map/route providers, whether a Google key is saved, and the
     * Google budgets/failures. Null until a snapshot carries the
     * provider fields — older commas never send them.
     */
    @Volatile
    var commaNavProviders: GoogleNav.State? = null

    /**
     * The comma's Galaxy Networks snapshot (Wi-Fi manager), last read
     * by the Settings Wi-Fi page. Null until that page (or an action on
     * it) hears from the comma — the endpoint only exists on StarPilot
     * builds with the Networks page.
     */
    @Volatile
    var commaNetworks: GalaxyNetworks? = null

    /** The comma's own recent destinations from the latest snapshot (Feature 8). */
    @Volatile
    var commaRecents: List<NavDestination> = emptyList()

    /** The comma's own favorite destinations from the latest snapshot (Feature 8). */
    @Volatile
    var commaFavorites: List<NavDestination> = emptyList()

    /** When the comma last rerouted the active destination (Feature 9); 0 = never. */
    @Volatile
    var reroutingAtMs: Long = 0L

    /** Destination waiting in the offline queue (Feature 10); null when the queue is empty. */
    @Volatile
    var queuedDestination: NavDestination? = null

    /** Most recent drive from /api/drives/stats (Feature 13); null until polled. */
    @Volatile
    var lastDrive: LastDrive? = null

    /** True once a software-status poll has succeeded (Feature 14). */
    @Volatile
    var softwareChecked: Boolean = false

    /** True when the comma has a downloaded update staged (Feature 14). */
    @Volatile
    var softwareUpdateReady: Boolean = false

    /** Label for the staged update (branch, else installed version). */
    @Volatile
    var softwareUpdateLabel: String? = null

    /** Active driving model display name (Feature 15); null while unknown. */
    @Volatile
    var activeModelName: String? = null

    @Volatile
    var currentGps: GpsPosition? = null

    @Volatile
    var activeTurnCue: String? = null

    @Volatile
    var currentStreet: String? = null

    @Volatile
    var latestPower: PowerState? = null

    @Volatile
    var speedMs: Double = 0.0

    @Volatile
    var speedLimitMph: Int = 0

    /**
     * Mapbox maxspeed annotation series for the comma's active route
     * ("Use phone speed limit"): fetched by CommaClient when a route
     * is adopted and the toggle + a saved token allow it; read each
     * poll to keep [speedLimitMph] on the segment the car is on.
     * Null whenever no fetched series backs the displayed limit.
     */
    @Volatile
    var routeLimitSeries: MaxspeedAnnotations.Series? = null

    /** Which phone provider produced [routeLimitSeries]: "google"
     *  (Roads API) or "mapbox" (Directions maxspeed). Labels the
     *  displayed limit's source honestly (Param, 2026-11-18). */
    @Volatile
    var routeLimitSource: String = "mapbox"

    /**
     * Route feature markers along the adopted route (Param,
     * 2026-11-10): traffic signals from Mapbox Tilequery and stop
     * signs from OSM, each behind its own Phone Data toggle
     * (usePhoneSignals / usePhoneStops). Empty when the toggle is
     * off, the fetch failed, or no route is active — absence is
     * shown as absence, never filled in.
     */
    @Volatile
    var routeSignals: List<RouteFeatures.Marker> = emptyList()

    @Volatile
    var routeStops: List<RouteFeatures.Marker> = emptyList()

    @Volatile
    var mapboxToken: String? = null

    // PHONE DATA defaults are OFF (Param, 2026-11-02): the comma is the only
    // data source until the user turns a fallback on. Turning one on is also
    // the only moment its permission is requested (see PermissionFlow).
    @Volatile
    var usePhoneSpeed: Boolean = false

    @Volatile
    var usePhoneLocation: Boolean = false

    @Volatile
    var usePhoneLocationToComma: Boolean = false

    @Volatile
    var usePhoneMapboxKey: Boolean = false

    @Volatile
    var usePhoneSpeedLimit: Boolean = false

    @Volatile
    var usePhoneRoute: Boolean = false

    @Volatile
    var usePhoneSearch: Boolean = false

    @Volatile
    var usePhone3d: Boolean = false

    @Volatile
    var usePhoneTraffic: Boolean = false

    @Volatile
    var usePhoneSignals: Boolean = false

    @Volatile
    var usePhoneStops: Boolean = false

    @Volatile
    var usePhoneVectorMap: Boolean = false

    /** The PHONE's own Google API key (Param, 2026-11-18), saved in
     *  the app exactly like the Mapbox token — not the comma's key. */
    @Volatile
    var googleApiKey: String? = null

    /** Gate for every phone-side Google use (maps, search, speed
     *  limit); default OFF like every Phone Data toggle. */
    @Volatile
    var usePhoneGoogleKey: Boolean = false

    /** Phone base-map preference from the Phone maps dropdown:
     *  PhoneGoogle.SOURCE_GOOGLE / SOURCE_MAPBOX / SOURCE_OSM. */
    @Volatile
    var phoneMapSource: String = PhoneGoogle.SOURCE_MAPBOX

    /** True while any of the comma's Google free limits is at/over
     *  the 95% switch threshold (Param, 2026-11-18): the phone then
     *  stops using its own Google key too and everything runs on
     *  Mapbox until the comma's counters reset. */
    @Volatile
    var googleBudgetExhausted: Boolean = false

    /** Live Google Map Tiles session (GoogleTiles.Session fields);
     *  refreshed in the background when the Google map is chosen. */
    @Volatile
    var googleTilesSession: String? = null

    @Volatile
    var googleTilesSessionExpirySec: Long = 0

    @Volatile
    var commaIp: String? = null

    @Volatile
    var commaFound: Boolean = false

    @Volatile
    var isGalaxyCloud: Boolean = false


    val isConnected: Boolean
        get() = (commaFound && commaIp != null) || isBleConnected || SimulationEngine.isSimulating

    @Volatile
    var connectionMode: String = "auto"

    enum class TransportType {
        BLUETOOTH, IP, CLOUD, NONE
    }

    @Volatile
    private var _activeTransport: TransportType = TransportType.NONE
    val activeTransport: TransportType
        get() = _activeTransport

    fun setActiveTransport(transport: TransportType) {
        if (_activeTransport != transport) {
            val previous = _activeTransport
            _activeTransport = transport
            // The journal's transport record (2026-11-19): this setter
            // is the ONE place the serving transport changes.
            ConnectionJournal.record(
                ConnectionJournal.CAT_TRANSPORT,
                "transport: $previous -> $transport"
            )
            notifyChanged()
        }
    }

    @Volatile
    private var _bleStatus: String = "Off"
    val bleStatus: String
        get() = _bleStatus

    val isBleConnected: Boolean
        get() = BleBridgeClient.isSharedClientConnected() ||
            _bleStatus.equals("Connected", ignoreCase = true) ||
            _bleStatus.startsWith("Connected", ignoreCase = true)

    fun setBleStatus(status: String) {
        _bleStatus = status
        notifyChanged()
    }

    @Volatile
    private var _deviceState: String? = null
    val deviceState: String?
        get() = _deviceState

    fun setDeviceState(state: String?) {
        if (_deviceState != state) {
            _deviceState = state
            notifyChanged()
        }
    }

    fun updatePower(power: PowerState?, state: String? = null) {
        val prevPower = latestPower
        val prevState = _deviceState
        val prevPktPower = latestPacket?.power
        if (power != null) {
            latestPower = power
            updateLatestPacket { it.copy(power = power) }
        }
        if (state != null) {
            _deviceState = state
        }
        if (latestPower != prevPower || _deviceState != prevState || latestPacket?.power != prevPktPower) {
            notifyChanged()
        }
    }

    // ---- SecretGoodStarPilot typed-API state (LAN/hotspot/BLE, or Cloud with Galaxy password sign-in) ----

    /** True when the connected comma serves the SG typed Galaxy APIs. */
    @Volatile
    private var _isSgGalaxy: Boolean = false
    val isSgGalaxy: Boolean
        get() = _isSgGalaxy

    fun setSgGalaxy(value: Boolean) {
        if (_isSgGalaxy != value) {
            _isSgGalaxy = value
            notifyChanged()
        }
    }

    /** Latest /api/navigation/status snapshot (raw), for fields the packet doesn't carry. */
    @Volatile
    var sgNavStatus: JSONObject? = null

    /** Revision token from the latest status snapshot; required by every navigation action. */
    @Volatile
    var sgRevision: String? = null

    /** Route key from the latest status snapshot; sent back to detect unchanged routes. */
    @Volatile
    var sgRouteKey: String? = null

    /** SLC Offset1..7 (mph) parsed from /api/settings/pages/slc, or null when unavailable. */
    @Volatile
    var sgOffsetsMph: DoubleArray? = doubleArrayOf(0.0, 2.0, 3.0, 3.0, 4.0, 4.0, 5.0)

    @Volatile
    var latestPlots: GalaxyPlotsLive? = null

    @Volatile
    var controlsActive: Boolean = false

    @Volatile
    var lateralControlActive: Boolean = false

    @Volatile
    var longitudinalControlActive: Boolean = false

    @Volatile
    var lateralPid: Double = 0.0

    /** Computes SLC offset mph for the given road speed limit band (Section 4.3). */
    fun getSlcOffsetMph(limitMph: Int): Int {
        val offsets = sgOffsetsMph ?: return 0
        val idx = when {
            limitMph < 25 -> 0
            limitMph < 35 -> 1
            limitMph < 45 -> 2
            limitMph < 55 -> 3
            limitMph < 65 -> 4
            limitMph < 75 -> 5
            else -> 6
        }
        return offsets.getOrElse(idx) { 0.0 }.roundToInt()
    }

    @Volatile
    var latestPacket: TelemetryPacket? = null

    private val packetLock = Any()

    /**
     * The one synchronized read-modify-write for [latestPacket]
     * (audit finding 8): the poll, the nav-refresh, the route-limits
     * thread and the UI used to each read-copy-write the packet
     * unsynchronized, so two writers copying from the same stale
     * packet silently discarded each other's field updates (the
     * mechanism behind the lane flicker). Wholesale publishes
     * (applyPacket, the client's fresh packet) still assign directly;
     * every copy()-based update goes through here.
     */
    fun updateLatestPacket(transform: (TelemetryPacket) -> TelemetryPacket) {
        synchronized(packetLock) {
            val cur = latestPacket ?: return
            latestPacket = transform(cur)
        }
    }

    // Wall-clock time the last telemetry packet was received by this app; 0 = none yet.
    @Volatile
    var lastPacketReceivedAtMs: Long = 0L

    fun dataAgeMs(): Long? {
        val t = lastPacketReceivedAtMs
        return if (t == 0L) null else (System.currentTimeMillis() - t).coerceAtLeast(0L)
    }

    val selectedRoute: RouteOption?
        get() = activeRoutes.firstOrNull { it.id == selectedRouteId } ?: activeRoutes.firstOrNull()

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: () -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    fun notifyChanged() {
        val copy = synchronized(listeners) { listeners.toList() }
        for (l in copy) {
            try { l.invoke() } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    fun updateTelemetry(
        pkt: TelemetryPacket,
        ip: String? = null,
        found: Boolean = true,
        isCloud: Boolean = false
    ) {
        // Change detection (review C2): snapshot everything this updater can
        // mutate, plus the packet fields listeners render from (nav maneuver,
        // destination, gps, speed limit — the packet timestamp is deliberately
        // excluded), and notify at the end only if something actually changed.
        val prevGps = currentGps
        val prevDest = activeDestination
        val prevCue = activeTurnCue
        val prevPower = latestPower
        val prevSpeedMs = speedMs
        val prevLimitMph = speedLimitMph
        val prevStreet = currentStreet
        val prevToken = mapboxToken
        val prevIp = commaIp
        val prevFound = commaFound
        val prevCloud = isGalaxyCloud
        val prevNav = latestPacket?.nav
        val prevPktDest = latestPacket?.destination
        val prevPktGps = latestPacket?.gps
        val prevPktLimit = latestPacket?.speedLimit
        val prevControls = controlsActive
        val prevLateral = lateralControlActive
        latestPacket = pkt
        lastPacketReceivedAtMs = System.currentTimeMillis()
        pkt.plotsLive?.let { latestPlots = it }
        controlsActive = pkt.cruiseActive || pkt.plotsLive?.controlsActive == true
        lateralControlActive = pkt.lateralControlActive || pkt.plotsLive?.lateralControlActive == true
        longitudinalControlActive = pkt.longitudinalControlActive || pkt.plotsLive?.longitudinalControlActive == true
        lateralPid = pkt.lateralPid
        if (SimulationEngine.isSimulating) {
            if (pkt.gps != null) currentGps = pkt.gps
        } else if (pkt.gps != null) {
            currentGps = pkt.gps
            updateSpeedLimitFromRoute(pkt.gps.latitude, pkt.gps.longitude)
        }
        if (pkt.destination != null && !navClearedByUser &&
            (activeDestination?.isSameAs(pkt.destination) != true)) {
            activeDestination = pkt.destination
            // A destination the app accepts supersedes any end state.
            noteNewDestination()
        }
        // End Route confidence (backlog #3): the comma still publishing
        // the destination the user ended — after the grace window — means
        // the clear didn't stick, and the header must say so. Adoption
        // above is already blocked by navClearedByUser, so a live
        // destination here can only be the ended one coming back.
        if (pkt.destination != null && navClearedByUser) {
            noteEndRouteSnapshotStillHasDestination(pkt.destination)
        }
        // Drive summary (Day 3): feed real comma fixes while a route is
        // active. Simulated trips never feed the tracker — simulation
        // publishes through this same path and its positions are not
        // driven miles. startIfNew also covers routes the comma started
        // on its own, where markTripStarted never ran.
        if (!SimulationEngine.isSimulating && activeDestination != null) {
            val fix = pkt.gps
            if (fix != null && fix.hasFix) {
                activeDestination?.let { driveTracker.startIfNew(it.name, System.currentTimeMillis()) }
                val spd = if (pkt.speedMs > 0.0) pkt.speedMs else fix.speed
                driveTracker.onFix(fix.latitude, fix.longitude, spd, System.currentTimeMillis())
            }
        }
        if (pkt.nav.valid) {
            val maneuver = pkt.nav
            activeTurnCue = if (maneuver.street.isNotEmpty()) {
                if (maneuver.type.isNotEmpty()) "${maneuver.type} on ${maneuver.street}" else maneuver.street
            } else null
        }
        if (pkt.power != null) {
            latestPower = pkt.power
        } else if (latestPower != null) {
            latestPacket = pkt.copy(power = latestPower)
        }
        if (pkt.speedMs > 0.0 || (currentGps?.speed ?: 0.0) == 0.0) {
            speedMs = pkt.speedMs
        }
        if (pkt.speedLimit.limitMs > 0.0) {
            speedLimitMph = (pkt.speedLimit.limitMs * Units.MS_TO_MPH).roundToInt()
        }
        if (pkt.nav.street.isNotEmpty()) {
            currentStreet = pkt.nav.street
        }
        if (!pkt.mapboxToken.isNullOrBlank()) {
            mapboxToken = pkt.mapboxToken
        }
        if (ip != null) {
            commaIp = ip
        }
        commaFound = found
        isGalaxyCloud = isCloud
        if (currentGps != prevGps || activeDestination != prevDest || activeTurnCue != prevCue ||
            latestPower != prevPower || speedMs != prevSpeedMs || speedLimitMph != prevLimitMph ||
            currentStreet != prevStreet || mapboxToken != prevToken || commaIp != prevIp ||
            commaFound != prevFound || isGalaxyCloud != prevCloud ||
            latestPacket?.nav != prevNav || latestPacket?.destination != prevPktDest ||
            latestPacket?.gps != prevPktGps || latestPacket?.speedLimit != prevPktLimit ||
            prevControls != controlsActive || prevLateral != lateralControlActive
        ) {
            notifyChanged()
        }
    }

    fun updateSpeedLimitFromRoute(lat: Double, lng: Double) {
        if (!usePhoneSpeedLimit) return
        // Cloud gate (Param, 2026-11-12): while the heartbeat-
        // identified transport is Galaxy Cloud the phone (Mapbox)
        // speed limit is not used — and a Mapbox limit set earlier
        // on LAN/Bluetooth does not linger into a Cloud session.
        if (!HeartbeatTransport.phoneDataAllowed(activeTransport)) {
            if (latestPacket?.speedLimit?.source == "mapbox" ||
                latestPacket?.speedLimit?.source == "google"
            ) {
                speedLimitMph = 0
                updateLatestPacket { cur -> cur.copy(speedLimit = SpeedLimitInfo()) }
            }
            return
        }
        val series = routeLimitSeries ?: return
        val mph = MaxspeedAnnotations.limitAt(series, lat, lng) ?: return
        if (mph > 0 && mph != speedLimitMph) {
            speedLimitMph = mph
            val slcOffset = getSlcOffsetMph(mph)
            updateLatestPacket { cur ->
                val curLimit = cur.speedLimit
                if (curLimit.source != "sg" || curLimit.limitMs <= 0.0) {
                    cur.copy(
                        speedLimit = SpeedLimitInfo(
                            limitMs = mph / Units.MS_TO_MPH,
                            offsetMs = slcOffset / Units.MS_TO_MPH,
                            source = routeLimitSource
                        )
                    )
                } else {
                    cur
                }
            }
            notifyChanged()
        }
    }

    fun updateGps(gps: GpsPosition) {
        updateSpeedLimitFromRoute(gps.latitude, gps.longitude)
        // If Comma is connected and providing valid GPS fixes, ignore phone GPS updates to prevent jitter/fighting
        if (commaFound && latestPacket?.gps?.hasFix == true) {
            return
        }
        val prevGps = currentGps
        val prevSpeedMs = speedMs
        currentGps = gps
        if (gps.speed > 0.0) {
            speedMs = gps.speed
        }
        // Drive summary (Day 3): phone GPS is the marker source only when
        // the comma provides no fix (this function already early-returns
        // when a comma fix exists), so this feed never double-counts with
        // the telemetry feed above.
        if (!SimulationEngine.isSimulating && activeDestination != null && gps.hasFix) {
            activeDestination?.let { driveTracker.startIfNew(it.name, System.currentTimeMillis()) }
            driveTracker.onFix(gps.latitude, gps.longitude, gps.speed, System.currentTimeMillis())
        }
        if (currentGps != prevGps || speedMs != prevSpeedMs) {
            notifyChanged()
        }
    }

    fun updatePower(power: PowerState) {
        // Change detection (review C2): PowerState is a data class, so an
        // identical repeat reading (same voltage/current/fan) does not notify.
        val changed = latestPower != power || latestPacket?.power != power
        latestPower = power
        updateLatestPacket { it.copy(power = power) }
        if (changed) notifyChanged()
    }

    fun updateConnection(ip: String?, found: Boolean, isCloud: Boolean) {
        if (ip != null) commaIp = ip
        commaFound = found
        isGalaxyCloud = isCloud
        if (!found) {
            _deviceState = null
        }
        notifyChanged()
    }

    fun clear() {
        clearedDestination = activeDestination
        destinationSelectedAtMs = 0L
        navClearedByUser = true
        activeDestination = null
        activeRoutes = emptyList()
        selectedRouteId = 0
        activeTurnCue = null
        clearTripTracking()
        updateLatestPacket { it.copy(destination = null, nav = NavManeuver(valid = false)) }
        sgNavStatus = null
        notifyChanged()
    }

    /**
     * Derives the next real maneuver (turn / ramp / arrival) from the
     * selected route's steps ahead of the vehicle, skipping straight
     * and depart steps. Shared by every display through
     * [getEffectiveManeuver]: used when the comma's live instruction
     * is missing, and when it is only a pass-through step (Continue),
     * so the phone and the car screen show the same upcoming turn.
     */
    private fun deriveNextTurnFromSteps(
        dest: NavDestination?,
        chosenRoute: RouteOption?,
        car: GpsPosition?
    ): NavManeuver? {
        // Scan the route steps ahead of the vehicle
        if (chosenRoute != null && car != null && chosenRoute.points.isNotEmpty()) {
            val points = chosenRoute.points
            // Precomputed per-route geometry (review C1): cumulative distances,
            // per-step polyline indices, and the effective steps — derived at
            // most once per route instead of on every call.
            val geom = routeGeometryFor(chosenRoute, dest?.name ?: "Destination")
            val steps = geom.steps

            if (steps.isNotEmpty()) {
                // Find vehicle index along the route polyline (windowed around
                // the last known index; full scan when the window has no close point)
                val carIdx = locateCarIndex(geom, points, car)

                // Look ahead for the next upcoming turn or arrival
                var candidateStep: RouteStep? = null
                var candidateDistM = 0.0

                for (stepIndex in steps.indices) {
                    val step = steps[stepIndex]
                    val loc = step.location ?: continue
                    // Nearest polyline point for this step, precomputed once per route
                    val stepPtIdx = geom.stepPointIndex[stepIndex]
                    if (stepPtIdx < 0) continue

                    // Direct distance from car to step location
                    val directDist = GuidanceMath.haversineM(car.latitude, car.longitude, loc.latitude, loc.longitude)

                    // Skip steps that are clearly behind the vehicle (already executed)
                    if (stepPtIdx < carIdx - 1 && directDist > 40.0 && step.maneuverType != "arrive") {
                        continue
                    }

                    val mod = step.maneuverModifier.lowercase().trim()
                    val t = step.maneuverType.lowercase().trim()
                    val isTurn = mod.contains("left") || mod.contains("right") || mod.contains("uturn") ||
                        t in listOf("turn", "ramp", "on ramp", "off ramp", "fork", "roundabout", "rotary", "merge", "end of road")
                    val isArrive = t == "arrive" || mod == "arrive"

                    // If it's a straight or depart step, skip it in favor of the upcoming real turn
                    if (!isTurn && !isArrive) {
                        continue
                    }

                    // Distance along polyline from car to this step: the car's
                    // offset to its nearest point plus the precomputed cumulative
                    // distance between the two indices (the same segment sum the
                    // original loop accumulated point by point).
                    val dAlong = GuidanceMath.haversineM(car.latitude, car.longitude, points[carIdx].latitude, points[carIdx].longitude) +
                        kotlin.math.abs(geom.stepAlongDist[stepIndex] - geom.cumDist[carIdx])

                    // If vehicle is right on the turn (< 10m) and already passing it:
                    if (isTurn && directDist < 10.0 && stepPtIdx <= carIdx) {
                        continue
                    }

                    candidateStep = step
                    candidateDistM = kotlin.math.max(dAlong, directDist)
                    break
                }

                if (candidateStep != null) {
                    var targetStreet = candidateStep.street
                    if (targetStreet.isBlank() || targetStreet.equals("current road", ignoreCase = true)) {
                        val instr = candidateStep.instruction
                        if (instr.contains(" onto ")) {
                            targetStreet = instr.substringAfter(" onto ").trim()
                        } else {
                            val candidateIdxInList = steps.indexOf(candidateStep)
                            if (candidateIdxInList >= 0) {
                                for (k in candidateIdxInList until kotlin.math.min(steps.size, candidateIdxInList + 3)) {
                                    if (steps[k].street.isNotBlank() && !steps[k].street.equals("current road", ignoreCase = true)) {
                                        targetStreet = steps[k].street
                                        break
                                    }
                                }
                            }
                        }
                    }

                    val candLoc = candidateStep.location
                    if (targetStreet.isBlank() && candLoc != null) {
                        targetStreet = NavigationManager.resolveStepStreet(candLoc, appContext) ?: ""
                    }
                    if (targetStreet.isBlank()) {
                        targetStreet = dest?.placeName?.ifEmpty { dest.name } ?: dest?.name ?: "next street"
                    }

                    val actionType = candidateStep.maneuverType.ifEmpty { "turn" }
                    val actionMod = candidateStep.maneuverModifier.ifEmpty {
                        if (candidateStep.maneuverType == "arrive") "arrive" else "straight"
                    }

                    // Real lanes only: the maneuver passes through
                    // unchanged (the deleted attachLaneGuidance
                    // pass-through, audit finding 44).
                    return NavManeuver(
                            valid = true,
                            type = actionType,
                            modifier = actionMod,
                            distanceM = candidateDistM,
                            street = targetStreet
                        )
                }
            }
        }
        return null
    }

    fun getEffectiveManeuver(pkt: TelemetryPacket? = null): NavManeuver {
        if (navClearedByUser && activeDestination == null) {
            return NavManeuver(valid = false)
        }
        val dest = activeDestination ?: (if (!navClearedByUser) (pkt?.destination ?: latestPacket?.destination) else null)
        val car = currentGps ?: pkt?.gps ?: latestPacket?.gps
        val chosenRoute = selectedRoute ?: activeRoutes.firstOrNull()

        // 1. The comma's live instruction is the source of truth for
        // real maneuvers: show it exactly as issued.
        val pNav = pkt?.nav ?: latestPacket?.nav
        if (pNav != null && pNav.valid) {
            // A pass-through live step (Continue / straight / depart)
            // names no street and hides the upcoming turn behind the
            // destination's name. Derive the next real maneuver from
            // the route steps instead, so every display shows the
            // same turn (Param, 2026-11-03). Real maneuvers from the
            // comma (turns, ramps, merges, arrivals) are shown exactly
            // as issued.
            if (ManeuverLogic.isPassThrough(pNav.type, pNav.modifier)) {
                deriveNextTurnFromSteps(dest, chosenRoute, car)?.let { return it }
            }
            return pNav
        }

        // 2. No live instruction: derive the upcoming maneuver from
        // the route steps ahead of the vehicle.
        deriveNextTurnFromSteps(dest, chosenRoute, car)?.let { return it }

        // 3. Fallback if destination is active

        if (dest != null) {
            val totalRouteDistM = (chosenRoute?.distanceMiles ?: 0.0) * Units.METERS_PER_MILE
            val d = if (chosenRoute != null && chosenRoute.points.isNotEmpty() && car != null) {
                val geom = routeGeometryFor(chosenRoute, dest.name)
                val carIdx = locateCarIndex(geom, chosenRoute.points, car)
                val remainingAlong = if (carIdx in geom.cumDist.indices && geom.cumDist.isNotEmpty()) {
                    val lastDist = geom.cumDist.last()
                    val distToEnd = lastDist - geom.cumDist[carIdx]
                    val distToPoint = GuidanceMath.haversineM(car.latitude, car.longitude, chosenRoute.points[carIdx].latitude, chosenRoute.points[carIdx].longitude)
                    (distToEnd + distToPoint).coerceAtLeast(0.0)
                } else {
                    totalRouteDistM
                }
                if (remainingAlong > 0.0) remainingAlong else totalRouteDistM
            } else if (totalRouteDistM > 0.0) {
                totalRouteDistM
            } else if (car != null && dest.hasCoordinates) {
                GuidanceMath.haversineM(car.latitude, car.longitude, dest.latitude, dest.longitude)
            } else {
                dest.distanceM ?: 0.0
            }

            if (d > 0.0 || (car != null && dest.hasCoordinates)) {
                val isArrive = d in 0.1..60.0
                return NavManeuver(
                        valid = true,
                        type = if (isArrive) "arrive" else "straight",
                        modifier = if (isArrive) "arrive" else "straight",
                        distanceM = d,
                        street = dest.name
                    )
            }
        }

        return NavManeuver(valid = false)
    }

    // ---- Per-route geometry precompute (review C1) ----

    /**
     * Precomputed geometry for one route: cumulative along-route distance per
     * point index, each step's nearest polyline point index and its along-route
     * distance, and the effective step list (derived from the polyline once
     * when the route itself carries no steps). Built once per route and reused
     * by every [getEffectiveManeuver] call, which runs on render paths.
     */
    private class RouteGeometry(
        val routeId: Int,
        val points: List<LatLng>,
        val pointsSize: Int,
        val steps: List<RouteStep>,
        val cumDist: DoubleArray,
        val stepPointIndex: IntArray,
        val stepAlongDist: DoubleArray
    ) {
        /** Last polyline index the car was located at on this route (window hint). */
        @Volatile
        var lastCarIndex: Int = -1
    }

    private val routeGeomLock = Any()

    @Volatile
    private var routeGeometry: RouteGeometry? = null

    private fun routeGeometryFor(route: RouteOption, destName: String): RouteGeometry {
        val points = route.points
        routeGeometry?.let {
            if (it.points === points && it.pointsSize == points.size && it.routeId == route.id) return it
        }
        synchronized(routeGeomLock) {
            routeGeometry?.let {
                if (it.points === points && it.pointsSize == points.size && it.routeId == route.id) return it
            }
            val steps = if (NavStateManager.usePhoneRoute) {
                route.steps.ifEmpty {
                    NavigationManager.deriveStepsFromPolyline(points, destName)
                }
            } else {
                // Phone routing OFF: never invent steps from route geometry.
                route.steps
            }
            val cumDist = DoubleArray(points.size)
            for (i in 1 until points.size) {
                cumDist[i] = cumDist[i - 1] + GuidanceMath.haversineM(
                    points[i - 1].latitude, points[i - 1].longitude,
                    points[i].latitude, points[i].longitude
                )
            }
            val stepPointIndex = IntArray(steps.size) { -1 }
            val stepAlongDist = DoubleArray(steps.size)
            for (si in steps.indices) {
                val loc = steps[si].location ?: continue
                var bestIdx = 0
                var bestDist = Double.MAX_VALUE
                for (i in points.indices) {
                    val d = GuidanceMath.haversineM(loc.latitude, loc.longitude, points[i].latitude, points[i].longitude)
                    if (d < bestDist) {
                        bestDist = d
                        bestIdx = i
                    }
                }
                stepPointIndex[si] = bestIdx
                stepAlongDist[si] = cumDist[bestIdx]
            }
            val built = RouteGeometry(route.id, points, points.size, steps, cumDist, stepPointIndex, stepAlongDist)
            routeGeometry = built
            return built
        }
    }

    /**
     * Nearest polyline index for the car. Searches a window of ±250 indices
     * around the last known index first and accepts the best point there when
     * it is within 150 m; otherwise falls back to the full scan the original
     * implementation ran on every call.
     */
    private fun locateCarIndex(geom: RouteGeometry, points: List<LatLng>, car: GpsPosition): Int {
        val last = geom.lastCarIndex
        if (last in points.indices) {
            val from = kotlin.math.max(0, last - 250)
            val to = kotlin.math.min(points.size - 1, last + 250)
            var bestIdx = last
            var bestDist = Double.MAX_VALUE
            for (i in from..to) {
                val pt = points[i]
                val d = GuidanceMath.haversineM(car.latitude, car.longitude, pt.latitude, pt.longitude)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = i
                }
            }
            if (bestDist <= 150.0) {
                geom.lastCarIndex = bestIdx
                return bestIdx
            }
        }
        var carIdx = 0
        var minCarDist = Double.MAX_VALUE
        for (i in points.indices) {
            val pt = points[i]
            val d = GuidanceMath.haversineM(car.latitude, car.longitude, pt.latitude, pt.longitude)
            if (d < minCarDist) {
                minCarDist = d
                carIdx = i
            }
        }
        geom.lastCarIndex = carIdx
        return carIdx
    }


}

/**
 * 60 FPS hardware display interpolator (Section 7.3).
 * Smoothly glides vehicle marker between discrete 1 Hz coordinate updates.
 */
class VehicleMarkerAnimator(private val onFrame: (LatLng, Float) -> Unit) {
    private var startPos = LatLng(0.0, 0.0)
    private var targetPos = LatLng(0.0, 0.0)
    private var startBearing = 0f
    private var targetBearing = 0f
    private var currentPos = LatLng(0.0, 0.0)
    private var currentBearing = 0f
    private var initialized = false

    private val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        // Duration is set per glide in animateTo (MarkerGlide):
        // it matches the measured interval between fixes instead
        // of a fixed 1000 ms that left the marker frozen between
        // Bluetooth packets (field fix, 2026-11-06).
        duration = MarkerGlide.DEFAULT_GLIDE_MS
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { va ->
            val fraction = va.animatedFraction
            val curLat = startPos.latitude + (targetPos.latitude - startPos.latitude) * fraction
            val curLon = startPos.longitude + (targetPos.longitude - startPos.longitude) * fraction
            val curBrg = (startBearing + normalizeDegrees(targetBearing - startBearing) * fraction + 360f) % 360f
            currentPos = LatLng(curLat, curLon)
            currentBearing = curBrg
            onFrame(currentPos, currentBearing)
        }
    }

    /** Stops the underlying animator (audit finding 48): the AA
     *  session's onDestroy calls this so no frame callbacks can fire
     *  into a destroyed screen. */
    fun cancel() {
        animator.cancel()
    }

    /** Uptime of the previous animateTo call; 0 = none yet. */
    private var lastFixUptimeMs = 0L

    fun animateTo(newPos: LatLng, newBearing: Float, speedMs: Double = 0.0) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!initialized) {
            initialized = true
            startPos = newPos
            targetPos = newPos
            currentPos = newPos
            startBearing = newBearing
            targetBearing = newBearing
            currentBearing = newBearing
            lastFixUptimeMs = now
            onFrame(newPos, newBearing)
            return
        }
        // Glide for the measured fix interval (clamped by
        // MarkerGlide) and aim one glide ahead of the fix at the
        // current speed, so the displayed position tracks where
        // the car IS instead of where the last fix caught it —
        // on Bluetooth the gap is seconds, not one second.
        val intervalMs = if (lastFixUptimeMs > 0L) now - lastFixUptimeMs else 0L
        lastFixUptimeMs = now
        val glideMs = MarkerGlide.durationMs(intervalMs)
        val projM = MarkerGlide.projectionM(speedMs, glideMs)
        val target = if (projM > 0.0) {
            val (tLat, tLng) = MarkerGlide.project(
                newPos.latitude, newPos.longitude, newBearing.toDouble(), projM
            )
            LatLng(tLat, tLng)
        } else newPos
        startPos = currentPos
        targetPos = target
        startBearing = currentBearing
        targetBearing = newBearing
        animator.duration = glideMs
        animator.cancel()
        animator.start()
    }

    private fun normalizeDegrees(diff: Float): Float = ((diff + 180f) % 360f + 360f) % 360f - 180f
}

/**
 * Exponential Moving Average (EMA) smoother for digital speedometer readings (Section 7.3).
 */
object SpeedometerSmoother {
    private var smoothedMph = 0.0
    private const val ALPHA = 0.4

    fun filter(rawMph: Double): Int {
        smoothedMph = if (smoothedMph <= 0.0) rawMph else (smoothedMph * (1.0 - ALPHA) + rawMph * ALPHA)
        return smoothedMph.roundToInt().coerceAtLeast(0)
    }

    fun reset() {
        smoothedMph = 0.0
    }
}
