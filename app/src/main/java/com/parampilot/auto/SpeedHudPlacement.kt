package com.parampilot.auto

/**
 * Where the AA speed cluster (speed-limit sign + live speed box) sits on
 * the car surface. Layer 2: the renderer only draws where this says.
 *
 * Field report (Param, 2026-11-05, photo of the school run): while
 * navigating, the cluster was anchored bottom-left — exactly where the
 * Android Auto host draws its own destination/ETA card. The host card
 * is taller than the old h-138 allowance (a two-line destination name
 * makes it taller still), so the sign was half-buried and the live
 * speed box was completely hidden behind it.
 *
 * Rule: idle (no destination) keeps the cluster bottom-left — no host
 * card is shown then. While navigating it moves to the bottom-right,
 * right of the host card and below the status pills, with the same
 * bottom margin the idle placement has always used.
 */
object SpeedHudPlacement {

    /** Sign 46 + spacing 8 + speed box 58, per CarSurfaceHudDrawer. */
    const val CLUSTER_W = 112f
    const val EDGE = 20f
    const val BOTTOM_MARGIN = 24f

    data class Anchor(val startX: Float, val bottomY: Float)

    fun anchor(w: Float, h: Float, isNavRunning: Boolean): Anchor {
        return if (isNavRunning) {
            Anchor(startX = w - EDGE - CLUSTER_W, bottomY = h - BOTTOM_MARGIN)
        } else {
            Anchor(startX = EDGE, bottomY = h - BOTTOM_MARGIN)
        }
    }
}
