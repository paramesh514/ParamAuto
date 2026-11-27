package com.parampilot.auto

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Comma health & caches screen (Feature 4).
 *
 * One dialog answering "what is the app actually working with": the live
 * transport and endpoint, the most recent connection diagnostic, the map
 * tile cache's real counters, how many places the search cache has resolved,
 * and the comma's own /api/system/monitor fields from the last fetch.
 * Anything the device or a cache never provided renders as "—" — nothing
 * here is estimated or invented.
 */
object HealthDialog {

    fun show(activity: Activity, client: CommaClient?) {
        // Addendum 132: an M3 sheet (M3Sheet), like the Settings screens.
        val (dialog, body, pal) = M3Sheet.create(activity, "Comma health & caches")

        fun addRow(label: String, value: String) =
            M3Sheet.addRow(body, activity, pal, label, value)

        val endpoint = client?.commaIp ?: NavStateManager.commaIp
        addRow(
            "Transport",
            HealthLines.connection(
                mode = client?.getConnectionMode() ?: NavStateManager.connectionMode,
                transport = NavStateManager.activeTransport,
                endpoint = endpoint,
                cloud = client?.isGalaxyCloud ?: NavStateManager.isGalaxyCloud
            )
        )
        addRow("Last status", client?.lastDiagnostic ?: "—")

        // Addendum 128: the comma's StarPilot channel — functionality
        // backed by testing-only APIs keys off this (CommaBranch.kt).
        addRow("Comma channel", commaChannelLabel(NavStateManager.commaBranch))

        // Connection journal (2026-11-19): how much glitch evidence
        // is on file; "View log" below opens it.
        val journalStats = ConnectionJournal.stats()
        addRow(
            "Connection log",
            if (journalStats.events == 0L) "—"
            else "${journalStats.events} events · last " +
                ConnectionJournal.formatTimestamp(journalStats.lastEventMs)
        )

        addRow(
            "Map tile cache",
            TileCacheSummary.format(
                tileCount = CommaTileDiskCache.getCachedTileCount(),
                sizeBytes = CommaTileDiskCache.getCacheSizeBytes(),
                hits = CommaTileDiskCache.hitCount.get(),
                misses = CommaTileDiskCache.missCount.get()
            )
        )

        val resolvedCount = try {
            SearchCache.resolvedPlaceCount(activity)
        } catch (_: Exception) {
            0
        }
        addRow("Resolved places", if (resolvedCount > 0) "$resolvedCount saved" else "—")

        val monitor = NavStateManager.lastSystemMonitor
        val monitorLines = MonitorSummary.lines(monitor)
        if (monitorLines.isEmpty()) {
            addRow("Comma system", "—")
        } else {
            for ((label, value) in monitorLines) addRow(label, value)
        }
        addRow(
            "Battery voltage",
            MonitorSummary.voltageText(monitor, NavStateManager.latestPower?.voltageV)
        )

        // Last drive (Feature 13): the comma's own totals, "—" until the
        // slow poll has read /api/drives/stats.
        val lastDrive = NavStateManager.lastDrive
        addRow(
            "Last drive",
            lastDrive?.formatDetail(System.currentTimeMillis()) ?: "—"
        )

        // Staged software update (Feature 14).
        addRow(
            "Comma update",
            when {
                NavStateManager.softwareUpdateReady ->
                    "Ready to install" + (NavStateManager.softwareUpdateLabel?.let { " · $it" } ?: "")
                NavStateManager.softwareChecked -> "No update staged"
                else -> "—"
            }
        )

        // Active driving model (Feature 15).
        addRow("Driving model", NavStateManager.activeModelName ?: "—")

        body.addView(
            M3Sheet.buttonRow(
                activity,
                M3Sheet.tonalButton(activity, pal, "View log") { showJournal(activity) },
                M3Sheet.primaryButton(activity, pal, "Done") { dialog.dismiss() }
            )
        )
        dialog.show()
    }

    /** The connection journal viewer: recent events, share, clear. */
    private fun showJournal(activity: Activity) {
        val (dialog, body, pal) = M3Sheet.create(activity, "Connection log")
        val tv = TextView(activity).apply {
            text = ConnectionJournal.recentText()
                .ifEmpty { "No connection events recorded yet." }
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(pal.textPrimary)
        }
        body.addView(tv)
        body.addView(
            M3Sheet.buttonRow(
                activity,
                M3Sheet.tonalButton(activity, pal, "Clear") {
                    ConnectionJournal.clear()
                    Toast.makeText(
                        activity, "Connection log cleared", Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                },
                M3Sheet.primaryButton(activity, pal, "Share") {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "ParamAuto connection log")
                        putExtra(Intent.EXTRA_TEXT, ConnectionJournal.recentText())
                    }
                    activity.startActivity(
                        Intent.createChooser(intent, "Share connection log")
                    )
                    dialog.dismiss()
                }
            )
        )
        dialog.show()
    }
}

/** Connection line for the health screen: transport first, endpoint when LAN. */
object HealthLines {

    fun connection(
        mode: String?,
        transport: NavStateManager.TransportType?,
        endpoint: String?,
        cloud: Boolean
    ): String {
        val base = when {
            transport == NavStateManager.TransportType.BLUETOOTH -> "Bluetooth bridge"
            transport == NavStateManager.TransportType.CLOUD || cloud -> "Galaxy Cloud"
            transport == NavStateManager.TransportType.IP -> "LAN · ${endpoint?.takeIf { it.isNotBlank() } ?: "—"}"
            else -> endpoint?.takeIf { it.isNotBlank() } ?: "—"
        }
        val modeLabel = mode?.trim()?.takeIf { it.isNotEmpty() }
            ?.replaceFirstChar { it.uppercase() }
        return if (modeLabel != null) "$base · $modeLabel mode" else base
    }
}

/** One-line tile cache summary from CommaTileDiskCache's real counters. */
object TileCacheSummary {

    fun format(tileCount: Int, sizeBytes: Long, hits: Long, misses: Long): String {
        val total = hits + misses
        val hitPart = if (total > 0) " · ${hits * 100 / total}% cache hits" else ""
        return "$tileCount tiles · ${formatBytes(sizeBytes)}$hitPart"
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

/**
 * Renders the comma's /api/system/monitor payload (as last fetched and stored
 * raw in NavStateManager) into label/value lines. Only fields actually
 * present, in a shape this formatter understands, produce a line — the
 * payload's documented fields are batteryVoltage, cpuPercent, uptime,
 * memory and storage; anything else is left out rather than guessed at.
 */
object MonitorSummary {

    fun lines(monitor: JSONObject?): List<Pair<String, String>> {
        if (monitor == null) return emptyList()
        val out = mutableListOf<Pair<String, String>>()

        numberField(monitor, "cpuPercent")?.let { out.add("CPU" to "${percentText(it)}%") }

        val uptimeSec = numberField(monitor, "uptime") ?: numberField(monitor, "uptimeSeconds")
        uptimeSec?.let { out.add("Uptime" to uptimeText(it)) }

        percentField(monitor, "memory", "memoryPercent")?.let { out.add("Memory" to "${percentText(it)}%") }
        percentField(monitor, "storage", "storagePercent")?.let { out.add("Storage" to "${percentText(it)}%") }

        tempText(monitor)?.let { out.add("Temperature" to it) }

        return out
    }

    /**
     * Battery voltage with an honest fallback: the live power state wins,
     * then the monitor payload's own batteryVoltage; when neither reports a
     * plausible value the answer is "not reported", never a placeholder.
     */
    fun voltageText(monitor: JSONObject?, powerVolts: Double?): String {
        val live = powerVolts?.takeIf { it > 1.0 }
        val fromMonitor = monitor?.let { numberField(it, "batteryVoltage") }?.takeIf { it > 1.0 }
        val v = live ?: fromMonitor ?: return "not reported"
        return String.format(Locale.US, "%.1f V", v)
    }

    private fun numberField(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        val v = json.optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }

    /** A percent stored either as a plain number or as a used/total object. */
    private fun percentField(json: JSONObject, objectKey: String, percentKey: String): Double? {
        numberField(json, percentKey)?.let { return it }
        val obj = json.optJSONObject(objectKey) ?: return null
        val used = numberField(obj, "usedBytes") ?: numberField(obj, "used") ?: return null
        val total = numberField(obj, "totalBytes") ?: numberField(obj, "total") ?: return null
        if (total <= 0.0) return null
        return used * 100.0 / total
    }

    private fun tempText(json: JSONObject): String? {
        // Current servers carry the temps under `vitals`
        // (Addendum 149; API audit 2026-11-26).
        json.optJSONObject("vitals")?.let { vitals ->
            numberField(vitals, "cpuTempC")?.let {
                return "${it.roundToInt()} °C"
            }
            numberField(vitals, "gpuTempC")?.let {
                return "${it.roundToInt()} °C"
            }
        }
        numberField(json, "cpuTemp")?.let { return "${it.roundToInt()} °C" }
        numberField(json, "temperature")?.let { return "${it.roundToInt()} °C" }
        val temps = json.optJSONObject("temps") ?: return null
        val keys = temps.keys()
        while (keys.hasNext()) {
            val v = numberField(temps, keys.next())
            if (v != null) return "${v.roundToInt()} °C"
        }
        return null
    }

    private fun percentText(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString()
        else String.format(Locale.US, "%.1f", v)

    private fun uptimeText(seconds: Double): String {
        val s = seconds.toLong()
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            s % 3600 == 0L -> "${s / 3600} h"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }
}
