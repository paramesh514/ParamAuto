package com.parampilot.auto

/**
 * Layer 2 (business logic): which comma IP the connection surfaces
 * show (Param, 2026-11-27): "Display IP in settings of connection
 * mode."
 *
 * The Connection mode dialog (and the Settings row that opens it)
 * reads live state only — the active endpoint, the address the
 * heartbeat learn (Addendum 156) last recorded, and the saved
 * last-known address — and this object decides the exact wording:
 *
 *  - Local serving: the address the heartbeat itself runs over —
 *    "Comma IP: <ip>". There is nothing to qualify: it is in use.
 *  - Bluetooth or Cloud serving: the connection in use is named,
 *    and the comma's local address rides along as "Local IP: <ip>"
 *    — that is the address the Local probe will try, so the user
 *    can see where an upgrade to Local would land. When no local
 *    address has been learned or saved yet, the line says so
 *    ("Local IP: not known yet") instead of inventing one.
 *  - Not connected: only a saved address may be shown, labelled
 *    "Last known IP: <ip>" — never presented as current. With
 *    nothing saved there is no line at all (null), rather than a
 *    stale default.
 *
 * Values that are not plain addresses (blank, a Galaxy Cloud URL,
 * anything with a scheme or path) are treated as absent everywhere:
 * a URL is not an IP and must never be displayed as one.
 *
 * Pure: callers gather the three addresses from their existing
 * holders; no Android, no I/O, no fetching.
 */
object ConnectionIpDisplay {

    /**
     * The connection mode dialog's IP status text (may contain a
     * newline for the two-line Bluetooth/Cloud form), or null when
     * there is nothing honest to show.
     *
     * [activeIp] is the endpoint the live connection runs over
     * (meaningful when [transport] is IP); [learnedIp] is the
     * comma's local address as learned from the comma itself
     * (Addendum 156) or persisted by it / a verified scan;
     * [savedIp] is the last known saved local address.
     */
    fun statusText(
        transport: NavStateManager.TransportType,
        activeIp: String?,
        learnedIp: String?,
        savedIp: String?
    ): String? {
        val active = clean(activeIp)
        val learned = clean(learnedIp)
        val saved = clean(savedIp)
        return when (transport) {
            NavStateManager.TransportType.IP ->
                when {
                    active != null -> "Comma IP: $active"
                    saved != null -> "Last known IP: $saved"
                    else -> null
                }
            NavStateManager.TransportType.BLUETOOTH ->
                "Connected via Bluetooth\n" +
                    (learned?.let { "Local IP: $it" } ?: "Local IP: not known yet")
            NavStateManager.TransportType.CLOUD ->
                "Connected via Galaxy Cloud\n" +
                    (learned?.let { "Local IP: $it" } ?: "Local IP: not known yet")
            NavStateManager.TransportType.NONE ->
                saved?.let { "Last known IP: $it" }
        }
    }

    /**
     * The bare IP worth appending to the Settings "Connection mode"
     * row subtitle: the active address while Local serves, else the
     * learned local address while Bluetooth or Cloud serves. Null
     * when nothing is connected or nothing is known — the row then
     * keeps its plain mode text.
     */
    fun rowIp(
        transport: NavStateManager.TransportType,
        activeIp: String?,
        learnedIp: String?
    ): String? = when (transport) {
        NavStateManager.TransportType.IP -> clean(activeIp)
        NavStateManager.TransportType.BLUETOOTH,
        NavStateManager.TransportType.CLOUD -> clean(learnedIp)
        NavStateManager.TransportType.NONE -> null
    }

    /** A displayable address, or null for blanks and non-addresses. */
    private fun clean(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.contains("galaxy.firestar.link")) return null
        if (value.contains("://") || value.contains('/') || value.contains(' ')) return null
        return value
    }
}
