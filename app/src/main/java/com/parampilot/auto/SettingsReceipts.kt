package com.parampilot.auto

import org.json.JSONObject

/**
 * Human-readable receipts for settings writes (Feature 1).
 *
 * After a settings commit is confirmed by the comma, the page is re-read and
 * each written row produces one receipt line naming the row and the value the
 * device actually holds now, e.g. "SLC offset 26–34 mph → 3 (read back from
 * comma)". When the read-back disagrees with what was written, the receipt
 * says so plainly instead of claiming success.
 *
 * Pure formatting only: no Android, no I/O, so it is unit-testable.
 */
object SettingsReceipts {

    /** Receipt for a write whose read-back matches the written value. */
    fun format(rowLabel: String, value: Any?): String =
        "${labelOrFallback(rowLabel)} → ${render(value)} (read back from comma)"

    /** Receipt for a write whose read-back does NOT match the written value. */
    fun formatMismatch(rowLabel: String, readBack: Any?, expected: Any?): String =
        "${labelOrFallback(rowLabel)} → read back ${render(readBack)}, expected ${render(expected)}"

    /** Receipt for a confirmed write whose value could not be re-read. */
    fun formatUnverified(rowLabel: String): String =
        "${labelOrFallback(rowLabel)} → saved (could not read back)"

    private fun labelOrFallback(rowLabel: String): String =
        rowLabel.trim().ifEmpty { "Setting" }

    /**
     * Renders a settings row value the way a person would say it:
     * numbers without a trailing ".0" (3.0 → "3", 2.5 → "2.5"),
     * booleans as On/Off, strings as-is, null / JSON null as "—".
     */
    fun render(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "—"
        is Boolean -> if (value) "On" else "Off"
        is Number -> renderNumber(value.toDouble())
        is String -> value.trim().ifEmpty { "—" }
        else -> value.toString()
    }

    private fun renderNumber(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "—"
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) {
            d.toLong().toString()
        } else {
            java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
        }
    }
}
