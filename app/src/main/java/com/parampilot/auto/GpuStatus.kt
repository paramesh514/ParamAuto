package com.parampilot.auto

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Layer 2: the comma's GPU state, parsed from the /api/system/monitor
 * payload the poll already fetches every cycle (the same fetch that
 * carries batteryVoltage). Galaxy's System Monitor page renders the
 * same `vitals` object:
 * - gpuEdgeTempC   — eGPU (Chestnut dock) edge temperature
 * - hotspotTempC   — eGPU hotspot temperature
 * - memoryUsedBytes / memoryTotalBytes — eGPU VRAM
 * - gpuTempC       — the comma's onboard GPU temperature
 *
 * Freshness follows Galaxy's own rule: a field counts only while its
 * age budget (maxAgeMs for eGPU temps, memoryMaxAgeMs for VRAM,
 * onboardMaxAgeMs for the onboard GPU) is present and positive —
 * Galaxy renders "—" otherwise, and so do we. Nothing here is
 * estimated: a dock that isn't reporting yields nulls, and the UI
 * shows "—" (Param's standing no-invented-values rule).
 *
 * There is no GPU utilization %, clock, or power field anywhere in
 * the payload — Galaxy doesn't have them either.
 */
data class GpuStatus(
    val egpuEdgeTempC: Double?,
    val egpuHotspotTempC: Double?,
    val onboardGpuTempC: Double?,
    val vramUsedBytes: Long?,
    val vramTotalBytes: Long?
) {
    /** True when the Chestnut dock is actually reporting GPU data. */
    val egpuReporting: Boolean
        get() = egpuEdgeTempC != null || egpuHotspotTempC != null ||
            (vramUsedBytes != null && vramTotalBytes != null)

    /** VRAM usage 0..100, null when VRAM isn't reported. */
    val vramPercent: Int?
        get() {
            val used = vramUsedBytes ?: return null
            val total = vramTotalBytes ?: return null
            if (total <= 0L || used > total) return null
            return (used * 100.0 / total).roundToInt()
        }

    /** Compact pill label: the eGPU edge temp (hotspot as fallback). */
    fun pillText(): String {
        val t = egpuEdgeTempC ?: egpuHotspotTempC
        return if (t != null) "GPU ${t.roundToInt()}°" else "GPU —"
    }

    fun tempText(v: Double?): String =
        v?.let { "${it.roundToInt()}°C" } ?: "—"

    /** "6.2 / 16.0 GB (39%)" — or "—" when VRAM isn't reported. */
    fun vramText(): String {
        val used = vramUsedBytes ?: return "—"
        val total = vramTotalBytes ?: return "—"
        val pct = vramPercent?.let { " ($it%)" } ?: ""
        return "${gb(used)} / ${gb(total)} GB$pct"
    }

    companion object {

        /** Why GPU data may be unavailable, for honest UI text:
         *  NO_MONITOR (the comma hasn't answered yet), NO_GPU_SECTION
         *  (it answered, but its current software sends no GPU data —
         *  source-checked 2026-11-10: the Dom system monitor sample
         *  carries CPU/memory/storage/voltage only; the vitals shape
         *  survives only in Galaxy's web fixtures/tests), PRESENT. */
        enum class Availability { NO_MONITOR, NO_GPU_SECTION, PRESENT }

        fun availability(monitor: JSONObject?): Availability = when {
            monitor == null -> Availability.NO_MONITOR
            monitor.optJSONObject("vitals") == null -> Availability.NO_GPU_SECTION
            else -> Availability.PRESENT
        }

        /** Parse the monitor payload; null when there is no payload
         *  or no vitals object at all (nothing to show yet). */
        fun parse(monitor: JSONObject?): GpuStatus? {
            val vitals = monitor?.optJSONObject("vitals") ?: return null
            val egpuFresh = fresh(vitals, "maxAgeMs")
            val memFresh = fresh(vitals, "memoryMaxAgeMs")
            val onboardFresh = fresh(vitals, "onboardMaxAgeMs")
            return GpuStatus(
                egpuEdgeTempC = if (egpuFresh) num(vitals, "gpuEdgeTempC") else null,
                egpuHotspotTempC = if (egpuFresh) num(vitals, "hotspotTempC") else null,
                onboardGpuTempC = if (onboardFresh) num(vitals, "gpuTempC") else null,
                vramUsedBytes = if (memFresh) longNum(vitals, "memoryUsedBytes") else null,
                vramTotalBytes = if (memFresh) longNum(vitals, "memoryTotalBytes") else null
            )
        }

        /** Galaxy's freshness gate: the age budget must exist and be
         *  positive; a missing budget means the feed never reported. */
        private fun fresh(vitals: JSONObject, ageKey: String): Boolean =
            !vitals.isNull(ageKey) && vitals.optLong(ageKey, 0L) > 0L

        private fun num(o: JSONObject, key: String): Double? =
            if (o.isNull(key)) null else o.optDouble(key, Double.NaN)
                .takeUnless { it.isNaN() }

        private fun longNum(o: JSONObject, key: String): Long? =
            if (o.isNull(key)) null else o.optLong(key, -1L).takeIf { it >= 0L }

        private fun gb(bytes: Long): String =
            String.format(java.util.Locale.US, "%.1f", bytes / 1_073_741_824.0)
    }
}
