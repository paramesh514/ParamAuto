package com.parampilot.auto

/**
 * Layer-2 policy for the car map's tile stitch (Addendum 153):
 * when a stitch in progress may replace what is on screen, and
 * when it counts as a usable frame.
 *
 * The pre-153 stitch waited on every tile against one shared
 * 3 s deadline and discarded the whole frame unless at least
 * half the tiles had arrived, so first frames at a new location
 * were routinely thrown away and the driver stared at the empty
 * grid (diagnosis B3/B4). The stitch now publishes progressively:
 * tiles are drawn as they land and the frame goes on screen as
 * soon as it passes [publishThreshold]; the remaining tiles keep
 * filling in behind it (the drain) instead of the frame being
 * held hostage to the slowest tile.
 */
object StitchPolicy {

    /** How long the stitch's first phase waits for tiles before
     *  its usable/not-usable verdict (the historical deadline). */
    const val FIRST_PHASE_DEADLINE_MS = 3000L

    /** How long the background drain keeps filling a published
     *  partial frame with its late tiles. */
    const val DRAIN_DEADLINE_MS = 9000L

    /**
     * Tiles that must be drawn before the stitch may go on
     * screen. With nothing on screen yet, a single tile is
     * already better than the empty grid, so the frame publishes
     * immediately and fills in. With a frame already displayed,
     * the stitch must reach the usable bar first — a sparse
     * frame must never replace a complete one.
     */
    fun publishThreshold(totalTiles: Int, hasCurrentFrame: Boolean): Int {
        if (totalTiles <= 0) return 1
        return if (hasCurrentFrame) {
            ((totalTiles + 1) / 2).coerceAtLeast(1)
        } else {
            1
        }
    }

    /** The historical success rule: at least half the tiles. */
    fun isUsable(loadedTiles: Int, totalTiles: Int): Boolean {
        if (totalTiles <= 0) return false
        return loadedTiles * 2 >= totalTiles
    }
}
