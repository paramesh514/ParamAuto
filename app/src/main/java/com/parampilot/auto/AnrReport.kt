package com.parampilot.auto

/**
 * Layer-2 core of the ANR watchdog (Param's "isn't responding"
 * reports, 2026-11-10): the block decision and the report text are
 * pure so the harness can pin them. Three aimed fixes (fetch storm,
 * style-reload flapping) did not close the field reports, and the
 * 15:18 sighting — parked, comma Offline, not navigating — cleared
 * both of those paths; the next fix gets aimed by the main thread's
 * actual stack, captured by AnrWatchdog, not by another theory.
 */
object AnrReport {

    /** A block counts when the main thread has not answered a tick
     *  for longer than [thresholdMs]. lastTickMs == 0 means the
     *  watchdog just started — never a block. */
    fun isBlocked(lastTickMs: Long, nowMs: Long, thresholdMs: Long): Boolean =
        lastTickMs > 0L && nowMs - lastTickMs > thresholdMs

    /** Param's rule (2026-11-13): a capture whose stack is the
     *  window's renderer stopping as the app backgrounds —
     *  HardwareRenderer setStopped/nSetStopped on the ViewRootImpl
     *  draw path (the vc32 capture: 12,266 ms, parked, the report
     *  "just appeared when opened app") — with NO app frame anywhere
     *  is a background transition, not a freeze, and gets NO report
     *  at all. Any stack carrying an app frame, or lacking either
     *  half of the renderer-stop signature, still reports exactly
     *  as before. */
    fun isBackgroundTransition(stackLines: List<String>): Boolean {
        val rendererStop = stackLines.any {
            it.contains("android.graphics.HardwareRenderer") &&
                (it.contains("setStopped") || it.contains("nSetStopped"))
        }
        if (!rendererStop) return false
        val viewRootDraw = stackLines.any { it.contains("android.view.ViewRootImpl") }
        if (!viewRootDraw) return false
        val appFrame = stackLines.any {
            it.contains("com.parampilot.auto") || it.contains("com.bogala.paramauto")
        }
        return !appFrame
    }

    /** The report Param sends back: identity + state first, then
     *  the main thread's stack exactly as captured. */
    fun format(
        versionName: String,
        versionCode: Int,
        capturedAtMs: Long,
        blockedForMs: Long,
        stateLines: List<String>,
        stackLines: List<String>
    ): String = buildString {
        appendLine("ParamAuto freeze report")
        appendLine("app: $versionName ($versionCode)")
        appendLine("capturedAtMs: $capturedAtMs")
        appendLine("mainThreadBlockedMs: $blockedForMs")
        for (line in stateLines) appendLine(line)
        appendLine("--- main thread stack ---")
        for (line in stackLines) appendLine(line)
    }.trimEnd()
}
