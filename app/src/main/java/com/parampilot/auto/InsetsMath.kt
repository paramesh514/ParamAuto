package com.parampilot.auto

/**
 * Bottom-margin rule for edge-to-edge screens (Param, 2026-11-02): a
 * bottom-anchored control keeps its authored margin, lifted to at least
 * the navigation-bar inset when the system reports one, so controls
 * never sit under the system navigation buttons. Pure math so the rule
 * is unit-testable; HudOverlay applies it on every insets change.
 */
fun liftedBottomMargin(baseMarginPx: Int, navBarInsetPx: Int): Int =
    maxOf(baseMarginPx, navBarInsetPx)

/**
 * Bottom margin when a notification stack occupies the bottom of the
 * screen (Addendum 127): the control must clear whichever is tallest —
 * its authored margin, the navigation-bar inset, or the stack's
 * clearance (the stack's height plus its own bottom margin and a
 * visual gap, computed by HudOverlay.setBottomStackHeight).
 */
fun stackedBottomMargin(baseMarginPx: Int, navBarInsetPx: Int, stackClearancePx: Int): Int =
    maxOf(liftedBottomMargin(baseMarginPx, navBarInsetPx), stackClearancePx)

/**
 * Height cap for a settings sheet's ScrollView: the sheet's own ratio cap,
 * but never more than the height the parent actually offered (the space
 * left after the drag handle, header and paddings). Ignoring a tighter
 * offer lets the sheet overflow its window, and the overflow rows can
 * never be scrolled into view (Param, 2026-11-03: "Still can't see last
 * setting"). An UNSPECIFIED offer (-1) means the ratio cap alone rules.
 */
fun sheetScrollCap(ratioCapPx: Int, offeredPx: Int): Int =
    if (offeredPx < 0) ratioCapPx else minOf(ratioCapPx, offeredPx)
