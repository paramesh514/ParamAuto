package com.parampilot.auto

/**
 * Transport readiness rules (Param, 2026-11-04).
 *
 * The app has three transports — Local, Bluetooth, Galaxy Cloud — and
 * two of them depend on a successful connection first:
 * - Bluetooth needs the pairing key, which is fetched over a Local
 *   connection (the pairing window + BLE claim run over LAN).
 * - Galaxy Cloud needs the device's Galaxy link, which is fetched
 *   from the comma over a Local connection (or pasted by the user),
 *   plus the Galaxy password.
 *
 * Until a transport's prerequisites exist there is no use trying it:
 * Local is the safe bet for a first-time connection. The user can
 * select only Auto or Local until the key and the Galaxy link +
 * password are configured; selecting Bluetooth is disabled until the
 * pairing key exists, and selecting Cloud is disabled until the link
 * and password exist. Once the first connection is done, Auto tries
 * in priority order: Local -> Bluetooth -> Galaxy — with one serving
 * refinement (Param, 2026-11-12): after Local, a configured Galaxy
 * Cloud serves immediately while Bluetooth is searched in the
 * background, and Bluetooth takes over when it connects.
 *
 * Pure decisions only — callers supply the three configuration facts.
 */
object TransportReadiness {

    /** Bluetooth is ready once its pairing key has been fetched and saved. */
    fun bluetoothReady(hasPairingKey: Boolean): Boolean = hasPairingKey

    /** Cloud is ready once the device Galaxy link AND the password exist. */
    fun cloudReady(hasGalaxyUrl: Boolean, hasPassword: Boolean): Boolean =
        hasGalaxyUrl && hasPassword

    /**
     * AUTO after Local fails (Param, 2026-11-12): when Galaxy Cloud
     * is configured it serves immediately while Bluetooth is searched
     * in the background — the app never sits unconnected waiting on a
     * Bluetooth attempt, and Bluetooth takes over when it connects.
     * Only when Cloud is not configured (a skip reason exists) does
     * Auto block on the Bluetooth attempt itself.
     */
    fun autoServeCloudWhileSearching(cloudSkipReason: String?): Boolean =
        cloudSkipReason == null

    /** Whether the user may select [mode] in the connection-mode dialog. */
    fun modeSelectable(
        mode: String,
        hasPairingKey: Boolean,
        hasGalaxyUrl: Boolean,
        hasPassword: Boolean
    ): Boolean = when (mode) {
        CommaClient.MODE_BLUETOOTH -> bluetoothReady(hasPairingKey)
        CommaClient.MODE_CLOUD -> cloudReady(hasGalaxyUrl, hasPassword)
        else -> true // Auto and Local are always selectable.
    }

    /**
     * Why [mode] cannot be selected yet, or null when it can. The text
     * is shown on the disabled row itself, so the user sees what is
     * missing and how it gets configured.
     */
    fun modeBlockedReason(
        mode: String,
        hasPairingKey: Boolean,
        hasGalaxyUrl: Boolean,
        hasPassword: Boolean
    ): String? = when (mode) {
        CommaClient.MODE_BLUETOOTH ->
            if (hasPairingKey) null
            else "Not set up — needs the Bluetooth pairing key. Connect over Local once and the app fetches it."
        CommaClient.MODE_CLOUD -> when {
            !hasGalaxyUrl ->
                "Not set up — needs your Galaxy link. Use Scan in the Galaxy setup below, or connect over Local once and the app fetches it."
            !hasPassword ->
                "Not set up — needs the Galaxy password (Settings → Galaxy Cloud & Web)."
            else -> null
        }
        else -> null
    }

    /**
     * The transports Auto will actually try, in priority order
     * Local -> Bluetooth -> Galaxy, holding back any transport whose
     * prerequisites are not configured yet. Local is always tried.
     */
    fun autoOrder(
        hasPairingKey: Boolean,
        hasGalaxyUrl: Boolean,
        hasPassword: Boolean
    ): List<String> {
        val order = ArrayList<String>(3)
        order.add(CommaClient.MODE_LOCAL)
        if (bluetoothReady(hasPairingKey)) order.add(CommaClient.MODE_BLUETOOTH)
        if (cloudReady(hasGalaxyUrl, hasPassword)) order.add(CommaClient.MODE_CLOUD)
        return order
    }

    /** Display label for a transport in the Auto priority line. */
    fun label(mode: String): String = when (mode) {
        CommaClient.MODE_LOCAL -> "Local"
        CommaClient.MODE_BLUETOOTH -> "Bluetooth"
        CommaClient.MODE_CLOUD -> "Galaxy"
        else -> mode
    }

    /** The Auto priority line, e.g. "Local -> Bluetooth -> Galaxy". */
    fun autoOrderText(
        hasPairingKey: Boolean,
        hasGalaxyUrl: Boolean,
        hasPassword: Boolean
    ): String = autoOrder(hasPairingKey, hasGalaxyUrl, hasPassword)
        .joinToString(" -> ") { label(it) }

    /**
     * Why Auto is skipping [transport] this run, or null when Auto
     * will try it. Surfaced as a diagnostic so the user sees what is
     * going on instead of a silent skip.
     */
    fun autoSkipReason(
        transport: String,
        hasPairingKey: Boolean,
        hasGalaxyUrl: Boolean,
        hasPassword: Boolean
    ): String? = when (transport) {
        CommaClient.MODE_BLUETOOTH ->
            if (hasPairingKey) null
            else "Bluetooth skipped — pairing key not configured yet"
        CommaClient.MODE_CLOUD -> when {
            !hasGalaxyUrl -> "Galaxy Cloud skipped — Galaxy link not configured yet"
            !hasPassword -> "Galaxy Cloud skipped — Galaxy password not configured yet"
            else -> null
        }
        else -> null
    }

    /**
     * AUTO tracks all three transports and chooses on every
     * heartbeat (Param, 2026-11-12: "keep track all 3 transports
     * always, Auto choose based on order" / "Auto choose every
     * second while heartbeat") — after his field report that the
     * hotspot was on yet Cloud served an entire trip: Local was
     * only ever evaluated by the startup sequence and on failures,
     * so a trip-start Local miss stuck for the whole drive.
     * Bluetooth availability is its live GATT state, known for
     * free; Cloud availability is its configured state (its
     * liveness is proven by the heartbeat whenever it serves, and
     * by the failover paths when it is selected); Local is the
     * only transport that needs an active probe.
     */

    /** Priority rank in AUTO: Local (3) > Bluetooth (2) > Cloud (1). */
    fun rank(transport: NavStateManager.TransportType?): Int = when (transport) {
        NavStateManager.TransportType.IP -> 3
        NavStateManager.TransportType.BLUETOOTH -> 2
        NavStateManager.TransportType.CLOUD -> 1
        else -> 0
    }

    /** The transport AUTO should serve on, from tracked availability. */
    fun autoChoice(
        localAvailable: Boolean,
        bleConnected: Boolean,
        cloudConfigured: Boolean
    ): NavStateManager.TransportType? = when {
        localAvailable -> NavStateManager.TransportType.IP
        bleConnected -> NavStateManager.TransportType.BLUETOOTH
        cloudConfigured -> NavStateManager.TransportType.CLOUD
        else -> null
    }

    /** True when [candidate] outranks the [current] serving transport. */
    fun outranks(
        candidate: NavStateManager.TransportType?,
        current: NavStateManager.TransportType?
    ): Boolean = candidate != null && rank(candidate) > rank(current)

    /**
     * Whether the Local availability probe should run this cycle:
     * AUTO only, not while Local already serves, only when a LAN
     * scan is allowed (Wi-Fi or hotspot on), and never while a
     * probe is already in flight — the single-flight bound is what
     * keeps a heartbeat-cadence probe cheap when no comma answers.
     */
    fun shouldProbeLocal(
        mode: String,
        identified: NavStateManager.TransportType?,
        lanAllowed: Boolean,
        probeInFlight: Boolean
    ): Boolean =
        mode == CommaClient.MODE_AUTO &&
            identified != NavStateManager.TransportType.IP &&
            lanAllowed &&
            !probeInFlight
}
