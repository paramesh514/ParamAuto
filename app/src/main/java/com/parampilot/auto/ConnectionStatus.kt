package com.parampilot.auto

/**
 * Layer-2 decision behind BOTH displays' connection pills
 * (3-layer architecture; phone since the 2026-11-04 refactor,
 * Auto since the business-logic push the same evening): given the
 * raw facts — mode, reachability, transport — decide WHICH
 * connection state the app is in. [Result.kind] is the shared
 * decision; each display renders it in its own words (the phone's
 * verbose [Result.text], Auto's short label), so the logic exists
 * exactly once.
 *
 * Pure: the caller resolves every live read (BLE link state,
 * Galaxy session state) into [Input] first.
 */
object ConnectionStatus {

    enum class Dot { GREEN, BLUE, RED, AMBER, MUTED }

    enum class Kind {
        SIMULATING,
        LOCAL_CONNECTED,
        SCANNING,
        LOCAL_OFFLINE,
        BLUETOOTH_CONNECTED,
        BLUETOOTH_OFFLINE,
        CLOUD_CONNECTED,
        CLOUD_CONFIG_ERROR,
        CLOUD_OFFLINE,
        OFFLINE
    }

    data class Result(val text: String, val dot: Dot, val kind: Kind)

    data class Input(
        val simulating: Boolean,
        val mode: String,
        val commaFound: Boolean,
        val commaIp: String?,
        val isGalaxyCloud: Boolean,
        val isScanning: Boolean,
        val bleConnected: Boolean,
        val cloudHasPassword: Boolean,
        val cloudAuthFailed: Boolean,
        /** "bluetooth" | "ip" | "cloud" | "none" — the heartbeat-identified transport. */
        val transportKey: String,
        /** NavStateManager.isBleConnected, for the AUTO/NONE heuristic. */
        val bleLinkUp: Boolean
    )

    private const val CLOUD_HOST_MARKER = "galaxy.firestar.link"

    // The connected results recur across the explicit-mode and
    // auto branches (builders extracted 2026-11-08).
    private fun bluetoothConnected() = Result("Bluetooth", Dot.BLUE, Kind.BLUETOOTH_CONNECTED)
    private fun cloudConnected() = Result("Cloud", Dot.BLUE, Kind.CLOUD_CONNECTED)
    private fun localConnected(label: String) = Result(label, Dot.GREEN, Kind.LOCAL_CONNECTED)

    fun resolve(input: Input): Result {
        if (input.simulating) return Result("Simulating", Dot.GREEN, Kind.SIMULATING)

        val cloudError = !input.cloudHasPassword || input.cloudAuthFailed
        val displayIp = input.commaIp?.takeIf { !it.contains(CLOUD_HOST_MARKER) }

        fun cloudResult(): Result = when {
            input.commaFound -> cloudConnected()
            cloudError -> Result("Cloud (Config Error)", Dot.RED, Kind.CLOUD_CONFIG_ERROR)
            // The heartbeat identified Cloud as the serving transport:
            // the state IS cloud, even while `found` lags a beat behind.
            input.transportKey == "cloud" ->
                Result("Cloud (Offline)", Dot.MUTED, Kind.CLOUD_CONNECTED)
            else -> Result("Cloud (Offline)", Dot.MUTED, Kind.CLOUD_OFFLINE)
        }

        return when (input.mode) {
            "local" -> when {
                input.commaFound ->
                    localConnected(displayIp ?: "Local")
                input.isScanning -> Result("Scanning…", Dot.AMBER, Kind.SCANNING)
                else -> Result(
                    if (displayIp != null) "$displayIp (Offline)" else "Local (Offline)",
                    Dot.MUTED,
                    Kind.LOCAL_OFFLINE
                )
            }
            "bluetooth" -> if (input.bleConnected) {
                bluetoothConnected()
            } else {
                Result("Bluetooth (Offline)", Dot.MUTED, Kind.BLUETOOTH_OFFLINE)
            }
            "cloud" -> cloudResult()
            else -> when (input.transportKey) { // "auto": follow the identified transport
                "bluetooth" -> bluetoothConnected()
                "ip" -> localConnected(input.commaIp ?: "Connected")
                "cloud" -> cloudResult()
                else -> {
                    // transportKey is none of bluetooth/ip/cloud
                    // here (the when above consumed them), so the
                    // heuristic rests on the link/session facts.
                    val isBle = input.bleLinkUp
                    val isCloud = input.isGalaxyCloud ||
                        input.commaIp?.contains(CLOUD_HOST_MARKER) == true
                    when {
                        isBle -> bluetoothConnected()
                        isCloud && !input.commaFound && cloudError ->
                            Result("Cloud (Config Error)", Dot.RED, Kind.CLOUD_CONFIG_ERROR)
                        isCloud && input.commaFound ->
                            cloudConnected()
                        input.commaFound && input.commaIp != null ->
                            localConnected(input.commaIp)
                        else -> Result("Offline", Dot.MUTED, Kind.OFFLINE)
                    }
                }
            }
        }
    }
}
