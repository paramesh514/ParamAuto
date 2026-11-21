package com.parampilot.auto

import android.os.Handler
import android.os.Looper
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Realistic driving simulation engine for ParamAuto HUD & Android Auto.
 *
 * Simulates:
 * - Vehicle accelerating, cruising (45 MPH), and turning smoothly
 * - MUTCD Speed limit (45 MPH)
 * - Car 12V battery voltage (13.8V alternator charge) and power draw
 * - Turn-by-turn maneuvers with countdown distance and lane guidance
 * - Realistic GPS coordinate progression along Phoenix streets
 * - Trip remaining distance & duration countdown
 */
object SimulationEngine {

    fun interface SimListener {
        fun onPacket(packet: TelemetryPacket)
    }

    private val listeners = CopyOnWriteArrayList<SimListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isSimulating: Boolean = false
        private set

    private var simThread: Thread? = null
    private var tickCount = 0

    // Default simulation destination: a warehouse store in Phoenix
    val DEFAULT_DESTINATION = NavDestination(
        name = "Warehouse Store",
        placeName = "Warehouse Store, Downtown Phoenix, AZ",
        latitude = 33.4971,
        longitude = -112.0661
    )

    // Predefined realistic waypoint track (Downtown Phoenix, north then east)
    private val DEFAULT_WAYPOINTS = listOf(
        LatLng(33.4484, -112.0740), // Start downtown
        LatLng(33.4586, -112.0740),
        LatLng(33.4736, -112.0740),
        LatLng(33.4886, -112.0740),
        LatLng(33.4961, -112.0740), // Corner (Turn Right)
        LatLng(33.4961, -112.0711), // Heading East
        LatLng(33.4961, -112.0686),
        LatLng(33.4971, -112.0661)  // Store Entrance (Turn Left / Arrive)
    )

    private var activePath: List<LatLng> = DEFAULT_WAYPOINTS
    private var activeDest: NavDestination = DEFAULT_DESTINATION

    fun addListener(listener: SimListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: SimListener) {
        listeners.remove(listener)
    }

    fun start(customPoints: List<LatLng>? = null, destination: NavDestination? = null) {
        if (isSimulating) return
        activePath = if (!customPoints.isNullOrEmpty() && customPoints.size >= 2) customPoints else DEFAULT_WAYPOINTS
        activeDest = destination ?: DEFAULT_DESTINATION
        isSimulating = true
        tickCount = 0

        simThread = Thread({
            runSimulationLoop()
        }, "sim-driving-engine").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val wasSim = isSimulating
        isSimulating = false
        simThread?.interrupt()
        simThread = null
        if (wasSim) {
            NavStateManager.clear()
            NavStateManager.currentGps = null
            NavStateManager.speedMs = 0.0
            NavStateManager.speedLimitMph = 0
            NavStateManager.notifyChanged()
        }
    }

    fun toggle(customPoints: List<LatLng>? = null, destination: NavDestination? = null): Boolean {
        if (isSimulating) {
            stop()
            return false
        } else {
            start(customPoints, destination)
            return true
        }
    }

    private fun runSimulationLoop() {
        val totalTicks = 240 // ~48 seconds loop at 200ms per tick
        var curSpeedMph = 0.0
        val targetCruisingMph = 45.0
        val speedLimitMph = 45.0

        // 3-lane guidance for right turn maneuver
        val lanesTurnRight = listOf(
            LaneInfo(listOf("straight"), active = false),
            LaneInfo(listOf("straight"), active = false),
            LaneInfo(listOf("right"), active = true)
        )

        // 3-lane guidance for cruising
        val lanesCruise = listOf(
            LaneInfo(listOf("straight"), active = false),
            LaneInfo(listOf("straight"), active = true),
            LaneInfo(listOf("straight"), active = false)
        )

        // 4-lane guidance for left turn into destination
        val lanesTurnLeft = listOf(
            LaneInfo(listOf("left"), active = true),
            LaneInfo(listOf("left"), active = true),
            LaneInfo(listOf("straight"), active = false),
            LaneInfo(listOf("straight"), active = false)
        )

        while (isSimulating) {
            val progress = (tickCount % totalTicks).toDouble() / totalTicks.toDouble()
            val phase = (tickCount % totalTicks)

            // 1. Vehicle Speed & Dynamics
            val currentMph = when {
                phase < 20 -> { // Accelerating from stop
                    (phase / 20.0) * targetCruisingMph
                }
                phase in 50..65 -> { // Slowing down for right turn
                    targetCruisingMph - ((phase - 50) / 15.0) * 23.0
                }
                phase in 66..80 -> { // Accelerating after right turn
                    22.0 + ((phase - 66) / 14.0) * 23.0
                }
                phase in 180..195 -> { // Slowing down for final destination turn
                    targetCruisingMph - ((phase - 180) / 15.0) * 30.0
                }
                phase >= 210 -> { // Arrived at destination
                    0.0
                }
                else -> { // Cruising with slight realistic micro-fluctuation (44.5 - 45.5 mph)
                    targetCruisingMph + sin(phase * 0.4) * 0.6
                }
            }
            curSpeedMph = currentMph
            val curSpeedMs = curSpeedMph / Units.MS_TO_MPH

            // 2. Battery Voltage (13.8V alternator charging with subtle fluctuations)
            val voltage = 13.8 + sin(phase * 0.1) * 0.15
            val currentAmp = 4.2 + cos(phase * 0.15) * 0.3
            val power = PowerState(
                voltageV = voltage,
                currentA = currentAmp,
                fanRpm = 1320,
                pandaType = "dos"
            )

            // 3. Coordinate progression & bearing along path
            val pathIndexFloat = progress * (activePath.size - 1)
            val segIdx = pathIndexFloat.toInt().coerceIn(0, activePath.size - 2)
            val segFrac = pathIndexFloat - segIdx
            val p0 = activePath[segIdx]
            val p1 = activePath[segIdx + 1]

            val curLat = p0.latitude + (p1.latitude - p0.latitude) * segFrac
            val curLng = p0.longitude + (p1.longitude - p0.longitude) * segFrac

            // Bearing calculation
            val targetBearing = when {
                phase < 55 -> 0.0 // North on N Central Ave
                phase in 55..65 -> { // Turning right
                    val turnProgress = (phase - 55) / 10.0
                    turnProgress * 90.0
                }
                phase in 66..185 -> 90.0 // East on E Roosevelt St
                phase in 186..195 -> { // Turning left towards Costco
                    val turnProgress = (phase - 186) / 9.0
                    90.0 - turnProgress * 90.0
                }
                else -> 0.0
            }

            val gps = GpsPosition(
                latitude = curLat,
                longitude = curLng,
                bearing = targetBearing,
                speed = curSpeedMs,
                hasFix = true
            )

            // 4. Maneuver Guidance
            val maneuver = when {
                phase < 60 -> {
                    // Turn right on E Roosevelt St
                    val remainingM = ((60 - phase) / 60.0) * 350.0 + 10.0
                    NavManeuver(
                        valid = true,
                        type = "turn",
                        modifier = "right",
                        distanceM = remainingM,
                        street = "E Roosevelt St",
                        laneCount = 3,
                        activeLaneDirection = "right",
                        activeLaneIndex = 2,
                        lanes = lanesTurnRight
                    )
                }
                phase in 60..180 -> {
                    // Cruising on E Roosevelt St
                    val remainingM = ((180 - phase) / 120.0) * 1600.0 + 50.0
                    NavManeuver(
                        valid = true,
                        type = "turn",
                        modifier = "left",
                        distanceM = remainingM,
                        street = "N 83rd Ave / Costco Entrance",
                        laneCount = 3,
                        activeLaneDirection = "straight",
                        activeLaneIndex = 1,
                        lanes = lanesCruise
                    )
                }
                phase in 181..210 -> {
                    // Turning left into destination
                    val remainingM = ((210 - phase) / 30.0) * 120.0
                    NavManeuver(
                        valid = true,
                        type = "turn",
                        modifier = "left",
                        distanceM = remainingM,
                        street = "Costco Wholesale",
                        laneCount = 4,
                        activeLaneDirection = "left",
                        activeLaneIndex = 1,
                        lanes = lanesTurnLeft
                    )
                }
                else -> {
                    // Arrived
                    NavManeuver(
                        valid = true,
                        type = "arrive",
                        modifier = "arrive",
                        distanceM = 0.0,
                        street = activeDest.name,
                        laneCount = 0
                    )
                }
            }

            // 5. Trip remaining distance & duration
            val remainingMiles = (1.0 - progress) * 4.2
            val remainingMin = ((remainingMiles / (targetCruisingMph / 60.0))).roundToInt().coerceAtLeast(1)

            val packet = TelemetryPacket(
                version = 3,
                timestamp = System.currentTimeMillis() / 1000.0,
                speedMs = curSpeedMs,
                cruiseActive = curSpeedMph > 20.0,
                experimentalMode = false,
                nav = maneuver,
                speedLimit = SpeedLimitInfo(
                    limitMs = speedLimitMph / Units.MS_TO_MPH,
                    offsetMs = 0.0,
                    source = "MUTCD"
                ),
                leadPresent = phase in 30..80,
                leadDistanceM = 35.0 + sin(phase * 0.2) * 8.0,
                power = power,
                gps = gps,
                mapboxToken = null,
                destination = activeDest
            )

            NavStateManager.activeDestination = activeDest
            NavStateManager.updateTelemetry(packet, "SIMULATOR", true, false)

            // Dispatch to listeners on main thread
            mainHandler.post {
                for (listener in listeners) {
                    try {
                        listener.onPacket(packet)
                    } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                }
            }

            tickCount++
            try {
                Thread.sleep(200) // 5 Hz update rate
            } catch (_: InterruptedException) {
                break
            }
        }
    }
}
