package com.parampilot.auto

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-known comma state for the home-screen widget (Feature 20).
 *
 * A plain snapshot of what NavStateManager already knows — device state,
 * current destination name, battery voltage — plus when it was written.
 * It is serialized to the dedicated "comma_widget" SharedPreferences
 * file by [CommaWidgetUpdater] while the app runs; the widget provider
 * reads ONLY that file (no network, no client) and renders [formatLines].
 * Missing values render as "—"/plain fallbacks, never invented numbers:
 * a voltage the comma never reported stays "—".
 */
data class WidgetState(
    val deviceState: String?,
    val destinationName: String?,
    val voltageV: Double?,
    val updatedAtMs: Long
) {
    /**
     * The four widget lines:
     *   "Comma · Parked" / "→ Home" / "Voltage 13.9 V" / "as of 9:12 AM"
     */
    fun formatLines(): List<String> {
        val state = deviceState?.trim()?.takeIf { it.isNotEmpty() }
            ?.replaceFirstChar { it.uppercase() }
        val dest = destinationName?.trim()?.takeIf { it.isNotEmpty() }
        val volts = voltageV?.takeIf { it > 1.0 }
        val asOf = if (updatedAtMs > 0) {
            "as of " + SimpleDateFormat("h:mm a", Locale.US).format(Date(updatedAtMs))
        } else {
            "Not updated yet"
        }
        return listOf(
            "Comma · ${state ?: "—"}",
            if (dest != null) "→ $dest" else "No destination",
            if (volts != null) {
                "Voltage " + String.format(Locale.US, "%.1f", volts) + " V"
            } else {
                "Voltage —"
            },
            asOf
        )
    }

    /** Change-detection key: everything except the timestamp. */
    fun changeKey(): String =
        "${deviceState ?: ""}|${destinationName ?: ""}|${voltageV ?: ""}"

    fun serialize(): String = JSONObject().apply {
        put("deviceState", deviceState ?: JSONObject.NULL)
        put("destinationName", destinationName ?: JSONObject.NULL)
        put("voltageV", voltageV ?: JSONObject.NULL)
        put("updatedAtMs", updatedAtMs)
    }.toString()

    companion object {
        val EMPTY = WidgetState(null, null, null, 0L)

        /** Tolerant inverse of [serialize]; null when unusable. */
        fun deserialize(text: String?): WidgetState? {
            if (text.isNullOrBlank()) return null
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                return null
            }
            val volts = json.opt("voltageV")
            return WidgetState(
                deviceState = json.optString("deviceState", "").ifEmpty { null },
                destinationName = json.optString("destinationName", "").ifEmpty { null },
                voltageV = (volts as? Number)?.toDouble(),
                updatedAtMs = json.optLong("updatedAtMs", 0L)
            )
        }
    }
}
