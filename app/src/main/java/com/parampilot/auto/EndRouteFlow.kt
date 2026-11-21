package com.parampilot.auto

/**
 * Device-confirmed End Route discipline (Feature 3).
 *
 * Ending navigation follows the same rule as starting it ("Destination is
 * only accept in app if it is accepted by device"): local state and UI are
 * cleared only after the comma confirms the clear action. On an SG comma the
 * clear is attempted via the typed API first, with the legacy clear as a
 * fallback — mirroring the fallback the old fire-and-forget flow used.
 *
 * Pure decision + message helpers shared by the phone UI (MainActivity) and
 * the Android Auto screen (NavScreen).
 */
object EndRouteFlow {

    /** True when any attempted clear path was confirmed by the comma. */
    fun confirmed(attemptedSg: Boolean, sgOk: Boolean, legacyOk: Boolean): Boolean =
        if (attemptedSg) (sgOk || legacyOk) else legacyOk

    /** Toast text when the comma did not confirm the end of navigation. */
    fun failureMessage(lastError: String?): String =
        "End route NOT confirmed by the comma — ${lastError?.takeIf { it.isNotBlank() } ?: "no confirmation from the device"}"
}
