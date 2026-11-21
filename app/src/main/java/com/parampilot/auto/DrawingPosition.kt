package com.parampilot.auto

/**
 * Layer-2 decision: which position the AA map draws as the car
 * (Param's rule, 2026-11-06: "Use phone location for drawing
 * current location. If comma location not available").
 *
 * The comma fix is the drawing source while it is AVAILABLE —
 * it carries a fix and is no older than [COMMA_AVAILABLE_MS].
 * Availability is judged on the fix's own age (packet timestamp),
 * not on packet arrival: over Bluetooth a packet can arrive
 * carrying a fix already seconds old, and the old freshness bar
 * (6 s, receipt-based) counted that as available, so the drawn
 * position rode the Bluetooth cadence and lagged the car.
 *
 * When the comma fix is not available, the phone fix draws —
 * but only while the "Use phone location" Phone Data toggle
 * allows phone location for drawing at all (settings are never
 * merged; that toggle's job is exactly map drawing). With the
 * toggle off the comma fix is drawn however old it is: comma
 * only, no phone substitute. Guidance, maneuvers and route
 * progress never consume this choice — it is drawing only.
 */
object DrawingPosition {

    /** A comma fix older than this is not available for drawing. */
    const val COMMA_AVAILABLE_MS = 2500L

    /** A phone fix older than this is not worth drawing either. */
    const val PHONE_AVAILABLE_MS = 5000L

    enum class Source { COMMA, PHONE }

    data class Choice(val gps: GpsPosition, val source: Source)

    /**
     * [commaAgeMs] / [phoneAgeMs] are the fixes' ages at decision
     * time; null means the age is unknown and counts as available.
     */
    fun choose(
        comma: GpsPosition?,
        commaAgeMs: Long?,
        phone: GpsPosition?,
        phoneAgeMs: Long?,
        phoneAllowed: Boolean
    ): Choice? {
        if (comma != null && comma.hasFix &&
            (commaAgeMs == null || commaAgeMs <= COMMA_AVAILABLE_MS)
        ) {
            return Choice(comma, Source.COMMA)
        }
        if (phoneAllowed && phone != null && phone.hasFix &&
            (phoneAgeMs == null || phoneAgeMs <= PHONE_AVAILABLE_MS)
        ) {
            return Choice(phone, Source.PHONE)
        }
        // Last resorts, in order: a stale comma fix (the only
        // source when the phone is not allowed), then a stale
        // phone fix when allowed. Never an invented position.
        if (comma != null && comma.hasFix) return Choice(comma, Source.COMMA)
        if (phoneAllowed && phone != null && phone.hasFix) return Choice(phone, Source.PHONE)
        return null
    }
}
