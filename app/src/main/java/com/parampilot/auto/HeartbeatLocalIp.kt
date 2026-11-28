package com.parampilot.auto

/**
 * Layer 2 (business logic): the heartbeat local-IP learn decision
 * (Param, 2026-11-27): "When comma connected. Fetch local IP too in
 * every heartbeat if transport is not local. Then use same for
 * connecting."
 *
 * The heartbeat payload itself (GET /api/device/state) carries no
 * address — the server builds it from state + maxAgeMs only (Dom
 * galaxy/device_state.py). The comma DOES report its own current LAN
 * addresses over the same session via GET /api/local-access (Dom
 * galaxy/local_access.py: every RFC1918 address it is listening on,
 * as http://<ip>:8082/ URLs), reachable over the BLE bridge and over
 * the Galaxy Cloud session alike. While Bluetooth or Cloud serves,
 * the poll loop fetches that payload alongside every heartbeat and
 * this object decides what becomes the Local candidate:
 *
 *  - transport is already Local (or nothing is connected): no-op —
 *    the heartbeat itself runs over the local address;
 *  - the first reported address (payload order) that sits on one of
 *    the phone's OWN active subnets ([LanScanScope.scopeOf] against
 *    the current plan — Wi-Fi or Hotspot) is the candidate; reported
 *    addresses on any other network are ignored, so a stale or
 *    foreign address can never displace a working candidate;
 *  - a candidate equal to the saved one is no change (null), so the
 *    caller writes nothing on the steady-state heartbeat;
 *  - a different on-subnet candidate REPLACES the saved one — that
 *    is the point: when the comma's DHCP address moves (it went
 *    192.168.68.9 -> 192.168.68.18 on 2026-11-26), the saved address
 *    must die on the next heartbeat, not at the next scan.
 *
 * The candidate is recorded exactly where the existing Local probe
 * reads (the per-scope last-known slot + the saved endpoint prefs),
 * so the existing per-heartbeat rank (Local > Bluetooth > Cloud)
 * upgrades the session through its own logic — this object never
 * picks or flips a transport.
 *
 * Pure: the plan comes from [LanScanScope] facts the caller gathers;
 * no Android, no I/O.
 */
object HeartbeatLocalIp {

    /**
     * The Local candidate to record, or null for "no change": keep
     * the current candidate (already-Local/no session, nothing
     * on-subnet reported, or the reported address is already saved).
     */
    fun candidate(
        reportedIps: List<String>,
        plan: LanScanScope.Plan,
        savedIp: String?,
        transport: NavStateManager.TransportType
    ): String? {
        if (transport == NavStateManager.TransportType.IP) return null
        if (transport == NavStateManager.TransportType.NONE) return null
        val saved = savedIp?.trim()?.takeIf { it.isNotEmpty() }
        for (raw in reportedIps) {
            val ip = raw.trim()
            if (ip.isEmpty()) continue
            // scopeOf also rejects malformed values (baseOf == null)
            // and anything off the phone's active subnets.
            if (LanScanScope.scopeOf(ip, plan) == null) continue
            return if (ip == saved) null else ip
        }
        return null
    }
}
