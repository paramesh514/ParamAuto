package com.parampilot.auto

import kotlin.math.abs

/**
 * SLC offsets glance (Feature 19): the comma's current speed-limit
 * offset table as read-only rows, shown on a long-press of the
 * speed-limit sign.
 *
 * The glance renders the DEVICE's own range labels via
 * [labeledRows] (from SgGalaxyClient.fetchSlcOffsetRows): Dom's
 * SlcOffsetOwner derives the band bounds from its stored document, so
 * the app never hardcodes ranges for display. [rows] keeps the older
 * value-only form with the device's own band labels ([BAND_RANGES])
 * for callers that only hold a parsed curve.
 *
 * A null (or short) read renders the single honest line "Offsets not
 * reported by the comma", never an invented table — that is still the
 * outcome for a km/h page or an invalid-document page.
 */
object OffsetsGlance {

    val BAND_RANGES: List<String> =
        listOf("0–25", "26–34", "35–43", "44–53", "54–63", "64–74", "75–98")

    const val NOT_REPORTED_LINE = "Offsets not reported by the comma"

    /** One row per band, or the single not-reported line. */
    fun rows(offsets: DoubleArray?): List<String> {
        if (offsets == null || offsets.size < BAND_RANGES.size) {
            return listOf(NOT_REPORTED_LINE)
        }
        return BAND_RANGES.indices.map { i ->
            "${BAND_RANGES[i]} mph · ${formatOffset(offsets[i])}"
        }
    }

    /**
     * Rows from the device's own (range label, offset) pairs — labels
     * used verbatim, e.g. "0–25 mph · +3 mph" on current Dom
     * ("0–<25 mph · +3 mph" on older Dom). A null or short read
     * renders the single not-reported line.
     */
    fun labeledRows(deviceRows: List<Pair<String, Double>>?): List<String> {
        if (deviceRows == null || deviceRows.size < BAND_RANGES.size) {
            return listOf(NOT_REPORTED_LINE)
        }
        return deviceRows.take(BAND_RANGES.size).map { (label, value) ->
            "$label · ${formatOffset(value)}"
        }
    }

    /** "+3 mph" for a positive offset, "0" at zero, "-2 mph" below. */
    /** Whole numbers without a decimal point ("3", not "3.0"). */
    private fun fmt(value: Double): String =
        if (abs(value - Math.round(value)) < 0.001) Math.round(value).toString()
        else value.toString()

    fun formatOffset(value: Double): String {
        val text = fmt(value)
        return when {
            abs(value) < 0.001 -> "0"
            value > 0 -> "+$text mph"
            else -> "$text mph"
        }
    }
}
