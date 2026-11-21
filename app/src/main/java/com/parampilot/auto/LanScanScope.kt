package com.parampilot.auto

/**
 * Layer 2 (business logic): which local subnets may be scanned for the comma.
 *
 * Param's rule (2026-11-05): scan only the subnet the phone is actually on —
 *  - Hotspot on -> the hotspot interface's subnet (online/ARP clients first,
 *    then every address on that subnet)
 *  - Wi-Fi on -> the Wi-Fi interface's subnet (every address on it)
 *  - Both off -> skip scanning entirely
 *
 * The subnet always comes from the phone's OWN address on that interface.
 * Cellular is never a scan source (the comma is never on the carrier subnet)
 * and no subnet is ever hardcoded.
 *
 * Pure: Android glue (Wi-Fi / hotspot state, interface enumeration) lives in
 * NetworkScanner; this object only decides from the gathered facts.
 */
object LanScanScope {

    /** One network interface fact: its name and one IPv4 address on it. */
    data class Iface(val name: String, val ipv4: String)

    /** Which local network a comma address belongs to. */
    enum class Scope { WIFI, HOTSPOT }

    data class Plan(
        /** Every scannable base, Wi-Fi first. */
        val bases: List<String>,
        val wifiBases: List<String>,
        val hotspotBases: List<String>,
        val wifiOn: Boolean,
        val hotspotOn: Boolean
    ) {
        val canScan: Boolean get() = bases.isNotEmpty()

        /** Why nothing can be scanned; null when [canScan]. */
        val skipReason: String?
            get() = when {
                canScan -> null
                !wifiOn && !hotspotOn -> "Wi-Fi and Hotspot are off — scanning skipped"
                else -> "Wi-Fi/Hotspot on but no local address yet — scanning skipped"
            }
    }

    /** "192.168.68.26" -> "192.168.68"; null for malformed, loopback or link-local input. */
    fun baseOf(ipv4: String): String? {
        val parts = ipv4.trim().split(".")
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.any { it !in 0..255 }) return null
        if (nums[0] == 127) return null                      // loopback
        if (nums[0] == 169 && nums[1] == 254) return null    // link-local (no real network)
        if (nums.all { it == 0 }) return null
        return "${nums[0]}.${nums[1]}.${nums[2]}"
    }

    /** Soft-AP interfaces: Pixels expose the hotspot as ap0 / swlan0 (never the STA name). */
    fun isHotspotInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("swlan") || (n.startsWith("ap") && n.drop(2).isNotEmpty() && n.drop(2).all { it.isDigit() })
    }

    /** Wi-Fi family interfaces (STA wlan* plus the soft-AP names); cellular/VPN/ethernet excluded. */
    fun isWifiInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("wlan") || isHotspotInterface(n)
    }

    /**
     * Decide the scan plan from gathered facts.
     *
     * A wlan* (STA-named) interface counts when Wi-Fi is on — or when the
     * hotspot is on, because on phones where the AP shares wlan0 with the
     * STA radio off, that interface IS the hotspot. An AP-named interface
     * counts only while the hotspot is on.
     */
    fun plan(wifiOn: Boolean, hotspotOn: Boolean, ifaces: List<Iface>): Plan {
        val wifi = LinkedHashSet<String>()
        val hot = LinkedHashSet<String>()
        if (wifiOn || hotspotOn) {
            for (iface in ifaces) {
                if (!isWifiInterface(iface.name)) continue
                val base = baseOf(iface.ipv4) ?: continue
                when {
                    isHotspotInterface(iface.name) -> if (hotspotOn) hot.add(base)
                    wifiOn -> wifi.add(base)
                    else -> hot.add(base)  // hotspot only: the wlan interface IS the AP
                }
            }
        }
        return Plan((wifi + hot).toList(), wifi.toList(), hot.toList(), wifiOn, hotspotOn)
    }

    /** The scope an address belongs to under this plan; null when it is on neither active subnet. */
    fun scopeOf(ip: String, plan: Plan): Scope? {
        val base = baseOf(ip.trim()) ?: return null
        return when {
            base in plan.wifiBases -> Scope.WIFI
            base in plan.hotspotBases -> Scope.HOTSPOT
            else -> null
        }
    }

    /**
     * Param's first-try order (2026-11-05): the saved last-known IPs go first —
     * the active scope's ahead of the other scope's — then any other known
     * addresses (provided / cached / last-scanned). Blanks, cloud URLs and
     * malformed values are dropped; duplicates collapse.
     */
    fun firstTryIps(plan: Plan, lastWifiIp: String?, lastHotspotIp: String?, extras: List<String?>): List<String> {
        val out = LinkedHashSet<String>()
        fun add(ip: String?) {
            val t = ip?.trim()
            if (!t.isNullOrEmpty() && !t.contains("galaxy.firestar.link") && baseOf(t) != null) out.add(t)
        }
        if (plan.wifiBases.isNotEmpty()) add(lastWifiIp)
        if (plan.hotspotBases.isNotEmpty()) add(lastHotspotIp)
        add(lastWifiIp)
        add(lastHotspotIp)
        extras.forEach { add(it) }
        return out.toList()
    }
}
