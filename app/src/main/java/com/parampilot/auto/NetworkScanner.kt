package com.parampilot.auto

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans local Wi-Fi subnets and gateways to discover a running Comma device (port 8082).
 */
class NetworkScanner(
    private val context: Context,
    private val isRunning: () -> Boolean,
    private val onDiagnostic: (String) -> Unit
) {

    private var lastDiagnosticMs = 0L

    private fun postDiagnostic(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastDiagnosticMs >= 250L) {
            lastDiagnosticMs = now
            onDiagnostic(msg)
        }
    }

    /**
     * Finds the first verified comma IP across all active subnets (Wi-Fi and Hotspot):
     * user-provided/last-scanned/cached first, then connected ARP/hotspot clients, then fast priority candidates on all subnets, then full subnet scans.
     */
    fun discover(cachedIp: String? = null): String? {
        val found = discoverScoped(cachedIp)
        if (found != null) rememberLastKnown(found)
        return found
    }

    private fun discoverScoped(cachedIp: String? = null): String? {
        // Param's scan rule (2026-11-05): scan only the subnet the phone is on —
        // the hotspot subnet when the hotspot is on, the Wi-Fi subnet when Wi-Fi
        // is on; both off (or no local address yet) means skip scanning entirely.
        val plan = scanPlan()
        if (!plan.canScan) {
            postDiagnostic(plan.skipReason ?: "Scanning skipped")
            Log.i(TAG, plan.skipReason ?: "Scanning skipped")
            return null
        }
        val prefs = context.getSharedPreferences("comma_client", Context.MODE_PRIVATE)
        val userIp = prefs.getString("user_local_ip", null)?.takeIf { !it.contains("galaxy.firestar.link") }
        val lastScanned = prefs.getString("last_scanned_ip", null)?.takeIf { !it.contains("galaxy.firestar.link") }

        // 1. First try the saved last-known IPs (Wi-Fi scope, then Hotspot scope),
        // then cached / user-provided / last-scanned — scan only if none answers.
        val priorityIps = LanScanScope.firstTryIps(
            plan,
            lastKnownIp(LanScanScope.Scope.WIFI),
            lastKnownIp(LanScanScope.Scope.HOTSPOT),
            listOf(cachedIp, userIp, lastScanned)
        )

        for (ip in priorityIps) {
            postDiagnostic("Checking IP $ip:$PORT...")
            if (isPortOpen(ip) && verifyComma(ip)) {
                return ip
            }
        }

        // 2. If phone is a hotspot, check connected ARP clients immediately
        val arpClients = getArpClients()
        for (clientIp in arpClients) {
            if (!isRunning()) return null
            postDiagnostic("Checking hotspot client $clientIp:$PORT...")
            if (isPortOpen(clientIp) && verifyComma(clientIp)) {
                Log.i(TAG, "Found Comma on hotspot client IP: $clientIp")
                return clientIp
            }
        }

        // 3. Fast priority candidates across all active subnets (both Wi-Fi and Hotspot)
        val bases = localSubnetBases()
        val fastCandidates = LinkedHashSet<String>()
        getWifiGateway()?.let { fastCandidates.add(it) }

        for (base in bases) {
            fastCandidates.add("$base.21")
            fastCandidates.add("$base.14")
            fastCandidates.add("$base.9")
            fastCandidates.add("$base.13")
            fastCandidates.add("$base.15")
            fastCandidates.add("$base.1")
            fastCandidates.add("$base.2")
            for (i in 3..30) {
                fastCandidates.add("$base.$i")
            }
        }

        for (cand in fastCandidates) {
            if (!isRunning()) return null
            postDiagnostic("Checking candidate $cand:$PORT...")
            if (isPortOpen(cand) && verifyComma(cand)) {
                return cand
            }
        }

        // 4. Scan all local subnets (both Wi-Fi and Hotspot)
        if (bases.isEmpty()) {
            postDiagnostic("No active Wi-Fi or Hotspot subnets found")
            return null
        }

        for (base in bases) {
            if (!isRunning()) return null
            postDiagnostic("Scanning subnet $base.0/24 for comma:8082...")
            val found = scanSubnet(base)
            if (found != null) {
                return found
            }
        }

        postDiagnostic("Comma not found on local network")
        return null
    }

    /**
     * Reads connected clients from /proc/net/arp (e.g. when Comma is connected to Phone's Wi-Fi Hotspot).
     */
    fun getArpClients(): List<String> {
        val clients = LinkedHashSet<String>()
        try {
            val file = java.io.File("/proc/net/arp")
            if (file.exists() && file.canRead()) {
                file.forEachLine { line ->
                    val tokens = line.trim().split("\\s+".toRegex())
                    if (tokens.isNotEmpty()) {
                        val ip = tokens[0]
                        if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) &&
                            ip != "0.0.0.0" &&
                            !ip.startsWith("127.")
                        ) {
                            clients.add(ip)
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        return clients.toList()
    }

    private fun getWifiGateway(): String? {
        // Modern Android: try ConnectivityManager LinkProperties default route gateway
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val active = cm?.activeNetwork
            val props = cm?.getLinkProperties(active)
            if (props != null) {
                val bases = localSubnetBases()
                for (route in props.routes) {
                    if (route.isDefaultRoute && route.gateway is Inet4Address) {
                        val gw = route.gateway?.hostAddress
                        // Only a gateway on a scannable Wi-Fi/hotspot subnet counts —
                        // the cellular gateway is never a comma candidate.
                        val gwBase = if (gw.isNullOrEmpty()) null else LanScanScope.baseOf(gw)
                        if (!gw.isNullOrEmpty() && gw != "0.0.0.0" && gwBase != null && gwBase in bases) return gw
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }

        // Fallback: WifiManager dhcpInfo
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val gw = wm?.dhcpInfo?.gateway ?: 0
            if (gw != 0) {
                val text = "${gw and 0xff}.${gw shr 8 and 0xff}.${gw shr 16 and 0xff}.${gw shr 24 and 0xff}"
                val base = LanScanScope.baseOf(text)
                if (base != null && base in localSubnetBases()) text else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun localSubnetBases(): List<String> = scanPlan().bases

    /**
     * Whether any LAN probing makes sense right now (Param,
     * 2026-11-12): Wi-Fi and Hotspot both off means there is no local
     * network to probe — callers skip every LAN step and go straight
     * to Bluetooth / Galaxy Cloud.
     */
    fun lanScanAllowed(): Boolean = scanPlan().canScan

    /** Gather the facts LanScanScope decides on: Wi-Fi state, hotspot state, interface addresses. */
    private fun scanPlan(): LanScanScope.Plan {
        val wm = try {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (_: Exception) { null }
        var wifiOn = false
        var hotspotOn = false
        var apStateKnown = false
        try { wifiOn = wm?.isWifiEnabled == true } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        try {
            // isWifiApEnabled is a hidden API; reflection is guarded. When
            // the call answers, its answer IS the hotspot state and is
            // trusted as-is. Only when it cannot answer at all does the
            // addressed-interface check below stand in for it (Param,
            // 2026-11-12: with Wi-Fi and Hotspot both off no LAN scan may
            // happen — a lingering soft-AP interface address must not
            // resurrect one).
            val m = wm?.javaClass?.getMethod("isWifiApEnabled")
            val ap = m?.invoke(wm) as? Boolean
            if (ap != null) {
                hotspotOn = ap
                apStateKnown = true
            }
        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        val ifaces = ArrayList<LanScanScope.Iface>()
        try {
            val nets = NetworkInterface.getNetworkInterfaces()
            if (nets != null) {
                for (iface in java.util.Collections.list(nets)) {
                    if (iface.isLoopback) continue
                    for (addr in java.util.Collections.list(iface.inetAddresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            ifaces.add(LanScanScope.Iface(iface.name, host))
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        if (!apStateKnown && !hotspotOn && ifaces.any { LanScanScope.isHotspotInterface(it.name) && LanScanScope.baseOf(it.ipv4) != null }) {
            hotspotOn = true  // an addressed soft-AP interface exists even if the state call was blocked
        }
        return LanScanScope.plan(wifiOn, hotspotOn, ifaces)
    }

    /**
     * Save a VERIFIED comma IP as the last-known address for its scope
     * (Param, 2026-11-05): one slot for Wi-Fi finds, one for Hotspot finds.
     * Only called with addresses that answered and verified as a comma, and
     * only labelled when the address sits on that scope's active subnet —
     * an unverified or off-subnet address is never saved here.
     */
    fun rememberLastKnown(ip: String?) {
        val t = ip?.trim()
        if (t.isNullOrEmpty() || t.contains("galaxy.firestar.link")) return
        val scope = LanScanScope.scopeOf(t, scanPlan()) ?: return
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(lastKnownKey(scope), t).apply()
            Log.i(TAG, "Saved last-known $scope comma IP: $t")
        } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun lastKnownIp(scope: LanScanScope.Scope): String? = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(lastKnownKey(scope), null)
    } catch (_: Exception) { null }

    private fun lastKnownKey(scope: LanScanScope.Scope): String =
        if (scope == LanScanScope.Scope.WIFI) KEY_LAST_WIFI_IP else KEY_LAST_HOTSPOT_IP

    private fun scanSubnet(base: String): String? {
        val pool = Executors.newFixedThreadPool(32)
        val found = arrayOfNulls<String>(1)
        val done = AtomicBoolean(false)
        try {
            val futures = (1..254).map { host ->
                pool.submit {
                    if (done.get() || !isRunning()) return@submit
                    val ip = "$base.$host"
                    if (isPortOpen(ip) && verifyComma(ip)) {
                        if (!done.getAndSet(true)) found[0] = ip
                    }
                }
            }
            futures.forEach { try { it.get() } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") } }
        } finally {
            pool.shutdownNow()
        }
        return found[0]
    }

    fun isPortOpen(ip: String): Boolean {
        var sock: Socket? = null
        return try {
            sock = Socket()
            sock.connect(InetSocketAddress(ip, PORT), SCAN_TIMEOUT_MS)
            true
        } catch (_: Exception) {
            false
        } finally {
            try { sock?.close() } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    fun verifyComma(ip: String): Boolean {
        // Probe endpoints in order:
        // 1. /api/auth/session (SecretGoodStarPilot direct session probe - mints/refreshes local session cookie)
        // 2. /api/device/status (legacy openpilot)
        // 3. /api/navigation/status (SecretGoodStarPilot typed Galaxy API)
        // 4. /api/device/state (SecretGoodStarPilot device state)
        // 5. /api/companion/runtime (companion runtime probe)
        val endpoints = listOf(
            "/api/auth/session",
            "/api/device/status",
            "/api/navigation/status",
            "/api/device/state",
            "/api/companion/runtime"
        )
        for (path in endpoints) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("http://$ip:$PORT$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 800
                    readTimeout = 800
                    useCaches = false
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val token = GalaxyCloudSession.parseSessionToken(conn.headerFields)
                    val isComma = body.contains("\"status\"") ||
                        body.contains("\"online\"") ||
                        body.contains("\"lanIp\"") ||
                        body.contains("\"state\"") ||
                        body.contains("\"revision\"") ||
                        body.contains("\"schemaVersion\"") ||
                        body.contains("\"instance\"") ||
                        body.contains("\"localAccess\"") ||
                        body.contains("\"authenticated\"")
                    if (isComma) {
                        if (token != null) {
                            GalaxyCloudSession.saveLocalSessionToken(token)
                        }
                        GalaxyCloudSession.ensurePaired("http://$ip:$PORT")
                        Log.i(TAG, "Comma verified successfully at $ip via $path")
                        onDiagnostic("Verified comma at $ip ($path)")
                        return true
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") } finally {
                conn?.disconnect()
            }
        }
        return false
    }

    /** Fast check of known LAN IPs to see if a comma has appeared locally on Wi-Fi or Hotspot. */
    fun quickCheckLan(): String? {
        val found = quickCheckLanScoped()
        if (found != null) rememberLastKnown(found)
        return found
    }

    private fun quickCheckLanScoped(): String? {
        // Same scan scope as discover(): with Wi-Fi and Hotspot both off there
        // is no local network to check, so don't probe anything.
        val plan = scanPlan()
        if (!plan.canScan) {
            Log.d(TAG, plan.skipReason ?: "LAN quick check skipped")
            return null
        }
        val candidates = LinkedHashSet<String>()
        val prefs = context.getSharedPreferences("comma_client", Context.MODE_PRIVATE)
        val saved = prefs.getString("comma_ip", null) ?: prefs.getString("saved_ip", null)
        val userLocal = prefs.getString("user_local_ip", null)
        val lastScanned = prefs.getString("last_scanned_ip", null)
        // Last-known IPs for the active scopes first, then the generic saved ones.
        candidates.addAll(
            LanScanScope.firstTryIps(
                plan,
                lastKnownIp(LanScanScope.Scope.WIFI),
                lastKnownIp(LanScanScope.Scope.HOTSPOT),
                listOf(saved, userLocal, lastScanned)
            )
        )
        candidates.addAll(getArpClients())
        getWifiGateway()?.let { candidates.add(it) }

        val bases = localSubnetBases()
        for (base in bases) {
            candidates.add("$base.16")
            candidates.add("$base.21")
            candidates.add("$base.14")
            candidates.add("$base.9")
            candidates.add("$base.13")
            candidates.add("$base.15")
            candidates.add("$base.1")
        }
        for (cand in candidates) {
            if (isPortOpen(cand) && verifyComma(cand)) return cand
        }
        return null
    }


    companion object {
        private const val TAG = "NetworkScanner"
        private const val PREFS_NAME = "comma_client"
        private const val KEY_LAST_WIFI_IP = "last_wifi_ip"
        private const val KEY_LAST_HOTSPOT_IP = "last_hotspot_ip"
        const val PORT = 8082
        private const val SCAN_TIMEOUT_MS = 150
        const val GALAXY_URL = "https://galaxy.firestar.link"
        const val GALAXY_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
}
