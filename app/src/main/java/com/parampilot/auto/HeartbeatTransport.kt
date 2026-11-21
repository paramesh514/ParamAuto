package com.parampilot.auto

/**
 * Heartbeat transport rule (Param, 2026-11-04): "Heartbeat identified
 * transport and same will be used by all app."
 *
 * The heartbeat — getting state, GET /api/device/state every second —
 * is the ONE place that identifies which transport the app is talking
 * over (Bluetooth bridge, LAN IP, or Galaxy Cloud). The identified
 * transport lives in [NavStateManager.activeTransport] and every other
 * part of the app follows it: feature requests never pick a channel of
 * their own and never flip the identified transport.
 *
 * These are the pure decisions behind that rule:
 * - a request may use the BLE bridge only when the heartbeat-identified
 *   transport is BLUETOOTH (and the connection mode allows Bluetooth);
 * - an HTTP request's transport is fixed by its base: Galaxy Cloud host
 *   means CLOUD, anything else means LAN IP;
 * - the heartbeat adopts the transport its state fetch was actually
 *   served over; a failed fetch (null) identifies nothing and leaves
 *   the current transport untouched.
 */
object HeartbeatTransport {

    /** True when a non-cloud request must go over the BLE bridge. */
    fun useBle(
        identified: NavStateManager.TransportType,
        connectionMode: String
    ): Boolean =
        identified == NavStateManager.TransportType.BLUETOOTH &&
            (connectionMode == CommaClient.MODE_AUTO ||
                connectionMode == CommaClient.MODE_BLUETOOTH)

    /** The transport an HTTP base represents: Cloud host or LAN IP. */
    fun httpTransport(isCloudBase: Boolean): NavStateManager.TransportType =
        if (isCloudBase) {
            NavStateManager.TransportType.CLOUD
        } else {
            NavStateManager.TransportType.IP
        }

    /**
     * What the heartbeat identifies after a state fetch: the transport
     * the fetch was served over, or the current transport when the
     * fetch failed and identified nothing.
     */
    fun identified(
        served: NavStateManager.TransportType?,
        current: NavStateManager.TransportType
    ): NavStateManager.TransportType = served ?: current

    /**
     * Phone-sourced data gate (Param, 2026-11-12): "When cloud is
     * connected. Don't send location to comma and don't use phone
     * location, phone speed, phone speed limit." While the heartbeat-
     * identified transport is CLOUD, no phone-sourced value is used
     * anywhere: phone GPS is not fed to the comma, and phone
     * location, phone speed and the phone (Mapbox) speed limit do
     * not drive position, speed or limit displays — comma data only.
     * Any other transport (including NONE, before the heartbeat has
     * identified one) leaves phone data available to its own
     * Phone Data toggles.
     */
    fun phoneDataAllowed(identified: NavStateManager.TransportType): Boolean =
        identified != NavStateManager.TransportType.CLOUD
}
