package com.parampilot.auto

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Feeds phone GPS fixes to the comma's Galaxy server while the user is
 * setting a destination (Param, 2026-10-31), and — since 2026-11-03 —
 * for the whole navigation once a destination is confirmed: a
 * navigation session started with [startNavigationSession] has no
 * time cap and runs until [stop] is called when navigation ends
 * (Param: "push phone location to comma untill navigation ends").
 *
 * Why: Dom's navigation lives on the comma, and the comma's own receiver is
 * weakest exactly when a destination is being set — parked in a garage,
 * cold-starting, or just woken. A fresh phone fix gives the comma's route
 * engine a good starting position. Feeding is session-scoped: [start] when
 * destination entry begins, [stop] once the destination is sent and the
 * comma has finalized its route; a hard 120 s cap bounds every session.
 *
 * Server contract (source-verified from openpilot/starpilot/gps/phone.py):
 * POST /api/companion/location, DIRECT-LOCAL ONLY — the server answers
 * 403 {"error": "Phone location requires a direct local connection"} for
 * cloud/remote peers, which stops the session immediately. The body may
 * contain ONLY: latitude, longitude, altitude, speed, bearing,
 * horizontalAccuracy, verticalAccuracy, speedAccuracy, bearingAccuracy,
 * timestamp, ageMs (any extra key is a 400). latitude/longitude must be
 * valid and not 0,0; horizontalAccuracy must be 0 < a <= 10000; ageMs must
 * be 0..2000 (the fix must be <= 2 s old when posted); timestamp is integer
 * epoch millis. Responses: 200 {"ok": true} accepted; 200 with
 * "accepted": false is a soft rejection (poorAccuracy / outOfOrder /
 * delayed) — delivered, feeding continues; 400 is a validation error —
 * feeding continues but stops after 5 consecutive 400s.
 *
 * The POST goes through [SgGalaxyClient.postJson], the same path as
 * /api/navigation/action: on a LAN base it bootstraps the local
 * galaxy_session via GET /api/auth/session when no local token is held and
 * attaches the session cookie; a 401 gets one re-login + one retry.
 *
 * Provenance gate: when NavStateManager.usePhoneLocationToComma is
 * false, [start] is a no-op. The phone-to-comma GPS feed has its own
 * toggle, separate from the map-drawing "Use phone location" setting.
 *
 * Threading: [onLocation] never blocks its caller; fixes are copied and
 * posted serially on a single background executor.
 */
object PhoneGpsFeeder {

    private const val TAG = "PhoneGpsFeeder"
    private const val PATH_COMPANION_LOCATION = "/api/companion/location"

    /** Hard cap on an ENTRY session. Navigation sessions are uncapped. */
    private const val SESSION_TIMEOUT_MS = 120_000L

    /** At most one POST per this interval. */
    // 1.2 s: the comma's freshness window for a fed fix is 2 s
    // (ageMs must be 0..2000) and the send path already rejects
    // fixes older than 1800 ms, so 500 ms posting bought nothing —
    // it only cost radio wakeups (audit finding 29: up to ~7,200
    // POSTs/hour, now ~3,000 worst case, typically far fewer).
    private const val MIN_POST_INTERVAL_MS = 1200L

    /** Server rejects ageMs > 2000; fixes older than this at send are skipped. */
    private const val MAX_FIX_AGE_AT_SEND_MS = 1800L

    /** Consecutive 400 validation errors that end a session. */
    private const val MAX_CONSECUTIVE_400 = 5

    /** True while a feeding session is active. */
    @Volatile
    var isFeeding: Boolean = false
        private set

    /**
     * True while the active session is a navigation session (started
     * with [startNavigationSession]): no 120 s cap; it ends only when
     * [stop] is called at the end of navigation.
     */
    @Volatile
    var isNavigationSession: Boolean = false
        private set

    /**
     * Last session-ending (or most recent 400) error, for UI/debugging.
     * Set on a 403 stop and on each 400; cleared by [start].
     */
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var endpoint: String? = null

    @Volatile
    private var sessionStartedAtMs: Long = 0L

    /** Bumped on every start/stop so a stale auto-stop runnable can't kill a newer session. */
    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var lastPostAtMs: Long = 0L

    /** Only mutated on the executor thread, plus reset in [start] before any task can run. */
    @Volatile
    private var consecutive400: Int = 0

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var autoStopRunnable: Runnable? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PhoneGpsFeeder").apply { isDaemon = true }
    }

    /**
     * Begins a feeding session for [endpoint] (bare IP/host or URL — the
     * base URL is derived with [SgGalaxyClient.baseUrlFor], same as every
     * other SG call). No-op when the location-to-comma feed is toggled off, or when a
     * session for the same endpoint is already running. Starting for a
     * different endpoint replaces the running session.
     */
    fun start(endpoint: String) {
        if (!NavStateManager.usePhoneLocationToComma) {
            Log.i(TAG, "start() ignored: phone location to comma is off")
            return
        }
        if (!HeartbeatTransport.phoneDataAllowed(NavStateManager.activeTransport)) {
            Log.i(TAG, "start() ignored: Galaxy Cloud connected — phone location is not sent to the comma")
            return
        }
        val gen: Long
        synchronized(lock) {
            if (isFeeding && this.endpoint == endpoint) return
            cancelAutoStopLocked()
            this.endpoint = endpoint
            sessionStartedAtMs = System.currentTimeMillis()
            lastPostAtMs = 0L
            consecutive400 = 0
            lastError = null
            isFeeding = true
            isNavigationSession = false
            gen = ++generation
            val runnable = Runnable {
                synchronized(lock) {
                    if (isFeeding && generation == gen) {
                        Log.i(TAG, "Session hit the ${SESSION_TIMEOUT_MS}ms cap; stopping")
                        stopLocked()
                    }
                }
            }
            autoStopRunnable = runnable
            mainHandler.postDelayed(runnable, SESSION_TIMEOUT_MS)
        }
        Log.i(TAG, "Feeding phone GPS to $endpoint")
        mainHandler.post { NavStateManager.notifyChanged() }
    }

    /**
     * Begins (or upgrades the current session to) a navigation session:
     * feeding continues with NO time cap until [stop] is called when
     * navigation ends. Same provenance gate as [start]. Safe to call
     * when an entry session for the same endpoint is already running —
     * it simply lifts the cap.
     */
    fun startNavigationSession(endpoint: String) {
        if (!NavStateManager.usePhoneLocationToComma) {
            Log.i(TAG, "startNavigationSession() ignored: phone location to comma is off")
            return
        }
        if (!HeartbeatTransport.phoneDataAllowed(NavStateManager.activeTransport)) {
            Log.i(TAG, "startNavigationSession() ignored: Galaxy Cloud connected — phone location is not sent to the comma")
            return
        }
        synchronized(lock) {
            if (isFeeding && isNavigationSession && this.endpoint == endpoint) return
            cancelAutoStopLocked()
            this.endpoint = endpoint
            sessionStartedAtMs = System.currentTimeMillis()
            lastPostAtMs = 0L
            consecutive400 = 0
            lastError = null
            isFeeding = true
            isNavigationSession = true
            generation++
        }
        Log.i(TAG, "Feeding phone GPS to $endpoint until navigation ends")
        mainHandler.post { NavStateManager.notifyChanged() }
    }

    /**
     * Ends an ENTRY session only. A navigation session is left running:
     * abandoning destination entry must not cut the feed of a trip
     * already under way.
     */
    fun stopEntrySession() {
        synchronized(lock) { if (!isNavigationSession) stopLocked() }
    }

    /** Ends the current session, if any. Safe to call from any thread. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        if (!isFeeding) return
        isFeeding = false
        isNavigationSession = false
        endpoint = null
        generation++
        cancelAutoStopLocked()
        Log.i(TAG, "Phone GPS feeding stopped")
        mainHandler.post { NavStateManager.notifyChanged() }
    }

    private fun cancelAutoStopLocked() {
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable = null
    }

    /**
     * Called by MainActivity's phone location listener for every fix.
     * When a session is active, a copy of the fix is queued for posting —
     * throttled to one POST per 500 ms, and fixes that could not reach the
     * server within the server's 2 s age limit are dropped here rather
     * than posted stale. Never blocks the caller.
     */
    fun onLocation(loc: Location) {
        if (!isFeeding) return
        val now = System.currentTimeMillis()
        if (!isNavigationSession && now - sessionStartedAtMs > SESSION_TIMEOUT_MS) {
            stop()
            return
        }
        // Fixes that can never pass server validation are skipped locally,
        // so they never burn the consecutive-400 budget: coordinates must
        // be valid and not 0,0, and horizontalAccuracy is required.
        val lat = loc.latitude
        val lng = loc.longitude
        if (lat == 0.0 && lng == 0.0) return
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) return
        if (!loc.hasAccuracy()) return
        val accuracy = loc.accuracy
        if (accuracy <= 0f || accuracy > 10_000f) return
        // Age gate at enqueue; re-checked at send time on the executor.
        val ageMs = now - loc.time
        if (ageMs > MAX_FIX_AGE_AT_SEND_MS) return
        // Throttle: at most one POST per MIN_POST_INTERVAL_MS.
        if (now - lastPostAtMs < MIN_POST_INTERVAL_MS) return
        lastPostAtMs = now
        val copy = Location(loc)
        val ep = endpoint ?: return
        try {
            executor.execute { postFix(ep, copy) }
        } catch (_: Exception) {
            // Executor rejected (should not happen — it is never shut down).
        }
    }

    /** Builds the exact-contract body and POSTs one fix. Runs on [executor]. */
    private fun postFix(ep: String, loc: Location) {
        if (!isFeeding || endpoint != ep) return
        // The transport can flip to Cloud mid-session: stop sending
        // the moment it does (the session itself ends through the
        // usual navigation-end / stop path).
        if (!HeartbeatTransport.phoneDataAllowed(NavStateManager.activeTransport)) return
        val now = System.currentTimeMillis()
        if (!isNavigationSession && now - sessionStartedAtMs > SESSION_TIMEOUT_MS) {
            stop()
            return
        }
        // The server validates the age at receipt, so ageMs and timestamp
        // are computed at send time, not at enqueue time.
        var ageMs = now - loc.time
        if (ageMs > MAX_FIX_AGE_AT_SEND_MS) return
        if (ageMs < 0L) ageMs = 0L

        val body = JSONObject()
        body.put("latitude", loc.latitude)
        body.put("longitude", loc.longitude)
        body.put("horizontalAccuracy", loc.accuracy.toDouble())
        body.put("timestamp", loc.time)
        body.put("ageMs", ageMs)
        // Optional fields only when the phone actually has them.
        if (loc.hasAltitude()) body.put("altitude", loc.altitude)
        if (loc.hasSpeed()) body.put("speed", loc.speed.toDouble())
        if (loc.hasBearing()) body.put("bearing", loc.bearing.toDouble())
        if (loc.hasVerticalAccuracy()) body.put("verticalAccuracy", loc.verticalAccuracyMeters.toDouble())
        if (loc.hasSpeedAccuracy()) body.put("speedAccuracy", loc.speedAccuracyMetersPerSecond.toDouble())
        if (loc.hasBearingAccuracy()) body.put("bearingAccuracy", loc.bearingAccuracyDegrees.toDouble())

        val client = SgGalaxyClient(SgGalaxyClient.baseUrlFor(ep))
        val (code, json) = client.postJson(PATH_COMPANION_LOCATION, body)
        when (code) {
            200 -> {
                // accepted absent/true = stored; accepted=false = soft
                // rejection (poorAccuracy/outOfOrder/delayed): the fix was
                // delivered, so the 400 streak resets either way.
                consecutive400 = 0
                if (json != null && json.has("accepted") && !json.optBoolean("accepted", true)) {
                    Log.d(TAG, "Phone fix soft-rejected: ${json.optString("reason", "")}")
                }
            }
            400 -> {
                consecutive400++
                val err = json?.optString("error", "")?.ifEmpty { null }
                    ?: "Phone location rejected by the comma (400)"
                lastError = err
                Log.w(TAG, "Phone location 400 ($consecutive400/$MAX_CONSECUTIVE_400): $err")
                if (consecutive400 >= MAX_CONSECUTIVE_400) {
                    Log.w(TAG, "Stopping phone GPS feed after $MAX_CONSECUTIVE_400 consecutive 400s")
                    stop()
                }
            }
            403 -> {
                // Direct-local only: over cloud/remote the server refuses
                // outright — feeding can never succeed on this transport.
                lastError = json?.optString("error", "")?.ifEmpty { null }
                    ?: "Phone location requires a direct local connection"
                Log.w(TAG, "Phone location 403: $lastError")
                stop()
            }
            else -> {
                // -1 = network failure, anything else unexpected: transient.
                // The session cap bounds the retries; the next fix tries again.
                Log.d(TAG, "Phone location POST returned $code; continuing")
            }
        }
    }
}
