package com.parampilot.auto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Google Maps style turn-by-turn navigation banner shown at the top during
 * active navigation. Renders maneuver arrow, distance to next turn, street
 * name, and optional lane guidance tray when approaching a maneuver.
 */
class TurnBannerView(
    private val ctx: Context,
    private val onEndRouteClick: (() -> Unit)? = null
) {

    private val den = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * den).roundToInt()

    private lateinit var root: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var icon: ManeuverIconView
    private lateinit var distTv: TextView
    private lateinit var streetTv: TextView
    private lateinit var laneRow: LinearLayout
    private lateinit var laneArrowsContainer: LinearLayout
    private lateinit var rerouteLabel: TextView
    private lateinit var queuedRow: LinearLayout
    private lateinit var queuedLabel: TextView
    private var onQueuedCancel: (() -> Unit)? = null
    private var queuedShownFor: NavDestination? = null
    private var queuedShownSync: Boolean = false
    // Route-end state card (backlog #3, moved to the bottom in
    // Addendum 122): a separate bottom-anchored card, so an ended
    // route never covers the search bar at the top.
    private lateinit var endCard: LinearLayout
    private lateinit var endIcon: ManeuverIconView
    private lateinit var endTitle: TextView
    private lateinit var endSub: TextView
    private var isNight: Boolean = true

    fun attachTo(parent: FrameLayout) {
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.TOP
                setMargins(dp(12), dp(42), dp(12), 0)
            }
        }
        card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = HudOverlay.makeCardBg(ctx, COLOR_GMAPS_NAV_DARK, 20)
            elevation = 10f * den
            setPadding(dp(16), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        icon = ManeuverIconView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(0, 0, dp(14), 0)
            }
        }
        // A stuck route is retried by tapping this banner (backlog #3):
        // the card is only clickable while the stuck state is showing
        // (see showEndState); during normal navigation taps fall through
        // to the map exactly as before.
        card.setOnClickListener {
            if (showingEndState == EndRouteConfidence.State.STUCK) onEndRouteClick?.invoke()
        }
        card.addView(icon)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        distTv = TextView(ctx).apply {
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(distTv)

        streetTv = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xEEFFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        col.addView(streetTv)
        card.addView(col)
        root.addView(card)

        laneRow = object : LinearLayout(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val halfScreen = (resources.displayMetrics.widthPixels * 0.5f).roundToInt()
                val wSpec = MeasureSpec.makeMeasureSpec(halfScreen, MeasureSpec.EXACTLY)
                super.onMeasure(wSpec, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = HudOverlay.makeCardBg(ctx, COLOR_GMAPS_LANE_DARK, 18)
            elevation = 8f * den
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.START
                setMargins(0, dp(6), 0, 0)
            }
        }
        laneArrowsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        laneRow.addView(laneArrowsContainer)
        root.addView(laneRow)

        // Transient "Rerouting…" pill (Feature 9): shown by update() for a
        // few seconds after the comma recomputes the active route.
        rerouteLabel = TextView(ctx).apply {
            text = "Rerouting…"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = HudOverlay.makeCardBg(ctx, COLOR_GMAPS_LANE_DARK, 14)
            elevation = 8f * den
            setPadding(dp(14), dp(7), dp(14), dp(7))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(6), 0, 0)
            }
            visibility = View.GONE
        }
        root.addView(rerouteLabel)

        // Offline queue row (Feature 10, Addendum 122): while a
        // destination is queued for the comma, the phone keeps
        // navigating locally (map-app mode) and this row — the ONE
        // queued popup during navigation, with a close icon — rides
        // under the maneuver card. Tapping the row or its ✕ cancels
        // the queue and the local navigation with it.
        queuedRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = HudOverlay.makeCardBg(ctx, HudOverlay.COLOR_SURFACE_DARK, 16, HudOverlay.COLOR_ACCENT_AMBER, 1)
            elevation = 8f * den
            setPadding(dp(12), dp(7), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(6), 0, 0)
            }
            visibility = View.GONE
            setOnClickListener { onQueuedCancel?.invoke() }
        }
        queuedLabel = TextView(ctx).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFE3E3E3.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        queuedRow.addView(queuedLabel)
        queuedRow.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 14f
            setTextColor(HudOverlay.COLOR_ACCENT_AMBER)
            setPadding(dp(10), dp(4), dp(8), dp(4))
            setOnClickListener { onQueuedCancel?.invoke() }
        })
        root.addView(queuedRow)
        parent.addView(root)

        // Route-end state card: bottom-anchored, drawn by
        // showEndState. The STUCK state is tappable — it retries
        // End Route (same handler as the old banner placement).
        endCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = HudOverlay.makeCardBg(ctx, COLOR_GMAPS_NAV_DARK, 20)
            elevation = 10f * den
            setPadding(dp(16), dp(14), dp(18), dp(14))
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(12), 0, dp(12), dp(16))
            }
            visibility = View.GONE
            setOnClickListener {
                if (showingEndState == EndRouteConfidence.State.STUCK) onEndRouteClick?.invoke()
            }
        }
        endIcon = ManeuverIconView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(0, 0, dp(14), 0)
            }
        }
        endCard.addView(endIcon)
        val endCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        endTitle = TextView(ctx).apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        endCol.addView(endTitle)
        endSub = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xEEFFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        endCol.addView(endSub)
        endCard.addView(endCol)
        parent.addView(endCard)
    }

    /** Background color for the route-end card given its state. */
    private fun endStateBg(state: EndRouteConfidence.State?): Int = when {
        state == EndRouteConfidence.State.STUCK -> COLOR_END_STUCK
        isNight -> COLOR_GMAPS_NAV_DARK
        else -> COLOR_GMAPS_NAV_LIGHT
    }

    fun applyTheme(night: Boolean) {
        isNight = night
        if (::card.isInitialized) {
            val bg = if (night) COLOR_GMAPS_NAV_DARK else COLOR_GMAPS_NAV_LIGHT
            card.background = HudOverlay.makeCardBg(ctx, bg, 20)
            val laneBg = if (night) COLOR_GMAPS_LANE_DARK else COLOR_GMAPS_LANE_LIGHT
            laneRow.background = HudOverlay.makeCardBg(ctx, laneBg, 18)
            if (::rerouteLabel.isInitialized) {
                rerouteLabel.background = HudOverlay.makeCardBg(ctx, laneBg, 14)
            }
        }
        if (::endCard.isInitialized) {
            endCard.background = HudOverlay.makeCardBg(ctx, endStateBg(showingEndState), 20)
        }
    }

    private var isSuppressed: Boolean = false

    /**
     * The end state this banner is currently showing (backlog #3), if
     * any. While one is showing, [update] leaves the banner alone —
     * the maneuver it would draw belongs to a route that is ending or
     * ended — and the observer re-drives this from NavStateManager.
     */
    private var showingEndState: EndRouteConfidence.State? = null

    /**
     * Shows a comma-confirmed end state on the BOTTOM card instead of
     * the maneuver banner (moved from the top in Addendum 122 — at the
     * top it covered the search bar after a route ended): "Ending
     * route…" while the clear is in flight, "Route ended ✓" once the
     * comma confirms, "⚠ Comma still navigating" when the device kept
     * the destination. The stuck card is tappable — it retries End
     * Route (see the click listener wired in [attachTo]).
     */
    fun showEndState(state: EndRouteConfidence.State, destName: String?) {
        val title = EndRouteConfidence.title(state) ?: return
        showingEndState = state
        if (!::endCard.isInitialized) return
        // The maneuver banner clears the top of the screen.
        if (::root.isInitialized) root.visibility = View.GONE
        if (isSuppressed) {
            endCard.visibility = View.GONE
            return
        }
        endCard.background = HudOverlay.makeCardBg(ctx, endStateBg(state), 20)
        endIcon.setManeuver(if (state == EndRouteConfidence.State.ENDED) "arrive" else "straight", "")
        endTitle.text = title
        val sub = EndRouteConfidence.subtitle(state, destName)
        if (sub != null) {
            endSub.text = sub
            endSub.visibility = View.VISIBLE
        } else {
            endSub.visibility = View.GONE
        }
        endCard.isClickable = (state == EndRouteConfidence.State.STUCK)
        endCard.visibility = View.VISIBLE
    }

    /** Clears a shown end state; a no-op unless one is showing. The normal maneuver banner is re-drawn by [update] on the next fix. */
    fun hideEndState() {
        if (showingEndState == null) return
        showingEndState = null
        if (::endCard.isInitialized) {
            endCard.isClickable = false
            endCard.visibility = View.GONE
        }
        if (::root.isInitialized) {
            root.visibility = View.GONE
        }
    }

    fun setSuppressed(suppressed: Boolean) {
        isSuppressed = suppressed
        if (suppressed) {
            root.visibility = View.GONE
            if (::endCard.isInitialized) endCard.visibility = View.GONE
        }
    }

    /**
     * Offline queue row (Addendum 122): [dest] non-null shows the row
     * under the maneuver card; null hides it. Mirrors the top bar's
     * queued chip — exactly one of the two is on screen at a time,
     * because the top bar hides while navigating.
     */
    fun setQueued(dest: NavDestination?, outOfSync: Boolean, onCancel: () -> Unit) {
        onQueuedCancel = onCancel
        if (!::queuedRow.isInitialized) return
        if (dest == null) {
            queuedShownFor = null
            queuedRow.visibility = View.GONE
            return
        }
        val label = DestinationQueue.label(dest, outOfSync, NavStateManager.commaNetworks)
        if (queuedShownFor?.isSameAs(dest) == true && queuedShownSync == outOfSync &&
            queuedLabel.text.toString() == label && queuedRow.visibility == View.VISIBLE
        ) return
        queuedShownFor = dest
        queuedShownSync = outOfSync
        queuedLabel.text = label
        queuedRow.visibility = View.VISIBLE
    }

    fun update(@Suppress("UNUSED_PARAMETER") nav: NavManeuver, dest: NavDestination? = null) {
        // An end state owns the banner while it shows — the maneuver
        // belongs to a route that is ending or ended.
        if (showingEndState != null) return
        if (isSuppressed) {
            root.visibility = View.GONE
            return
        }
        // The queued row's visibility follows the recorded queued
        // destination on every redraw (Addendum 122).
        if (::queuedRow.isInitialized) {
            queuedRow.visibility = if (queuedShownFor != null) View.VISIBLE else View.GONE
        }
        val route = NavStateManager.selectedRoute ?: NavStateManager.activeRoutes.firstOrNull()
        val car = NavStateManager.currentGps
        // One maneuver logic on every display (Param, 2026-11-03): the
        // phone banner reads the same effective maneuver the Android
        // Auto card and template read — no per-display divergence.
        val rawEffective = NavStateManager.getEffectiveManeuver()
        val effective = rawEffective

        if (effective.valid && dest != null) {
            root.visibility = View.VISIBLE
            icon.setManeuver(effective.type, effective.modifier)
            val targetRoad = effective.street.ifEmpty { dest.name }
            if (effective.distanceM > 0.0) {
                distTv.textSize = 24f
                distTv.text = fmtDist(effective.distanceM)
                streetTv.text = targetRoad
                streetTv.visibility = View.VISIBLE
            } else {
                distTv.textSize = 20f
                distTv.text = targetRoad
                val sub = dest.placeName.takeIf { it.isNotEmpty() && it != targetRoad }
                if (sub != null) {
                    streetTv.text = sub
                    streetTv.visibility = View.VISIBLE
                } else {
                    streetTv.visibility = View.GONE
                }
            }
            buildLanes(effective.lanes, effective.laneCount, effective.activeLaneIndex, effective)
        } else if (dest != null) {
            root.visibility = View.VISIBLE
            icon.setManeuver("straight", "")
            val chosenRoute = NavStateManager.selectedRoute ?: NavStateManager.activeRoutes.firstOrNull()
            val dMeters = if (chosenRoute != null && chosenRoute.distanceMiles > 0.0) {
                chosenRoute.distanceMiles * Units.METERS_PER_MILE
            } else {
                dest.distanceM ?: 0.0
            }
            if (dMeters > 0.0) {
                distTv.textSize = 24f
                distTv.text = fmtDist(dMeters)
                streetTv.text = dest.name
                streetTv.visibility = View.VISIBLE
            } else {
                distTv.textSize = 20f
                distTv.text = dest.name
                val sub = dest.placeName.takeIf { it.isNotEmpty() && it != dest.name }
                if (sub != null) {
                    streetTv.text = sub
                    streetTv.visibility = View.VISIBLE
                } else {
                    streetTv.visibility = View.GONE
                }
            }
            laneRow.visibility = View.GONE
        } else {
            root.visibility = View.GONE
        }

        // Rerouting pill (Feature 9): transient, tied to the timestamp
        // NavigationManager stamps when the routeKey changes under the
        // same destination. update() runs per packet, so it clears itself.
        if (::rerouteLabel.isInitialized) {
            val rerouting = dest != null && NavStateManager.reroutingAtMs > 0L &&
                System.currentTimeMillis() - NavStateManager.reroutingAtMs < REROUTE_SHOW_MS
            rerouteLabel.visibility = if (rerouting) View.VISIBLE else View.GONE
        }
    }

    fun show() { if (!isSuppressed) root.visibility = View.VISIBLE }
    fun hide() {
        // The banner is gone: an end state it was showing is gone with
        // it (the observer re-shows it if it is still visible).
        showingEndState = null
        if (::card.isInitialized) card.isClickable = false
        root.visibility = View.GONE
    }

    private fun buildLanes(lanes: List<LaneInfo>, cnt: Int, activeIdx: Int, @Suppress("UNUSED_PARAMETER") nav: NavManeuver? = null) {
        laneArrowsContainer.removeAllViews()
        if (lanes.isEmpty() && cnt <= 0) {
            laneRow.visibility = View.GONE
            return
        }
        val items = lanes.ifEmpty {
            (0 until cnt).map { LaneInfo(listOf("straight"), it == activeIdx) }
        }
        val halfScreen = (ctx.resources.displayMetrics.widthPixels * 0.5f).roundToInt()
        val availW = halfScreen - dp(24)
        val count = items.size.coerceAtLeast(1)
        val perLaneMaxW = (availW / count) - dp(4)
        val laneW = kotlin.math.min(dp(50), kotlin.math.max(dp(28), perLaneMaxW))
        val laneH = (laneW * (62f / 50f)).roundToInt()

        for (l in items) {
            laneArrowsContainer.addView(LaneArrowView(ctx, l.directions, l.active).apply {
                layoutParams = LinearLayout.LayoutParams(laneW, laneH).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            })
        }
        laneRow.visibility = View.VISIBLE
    }

    /** Formats distance in imperial units: feet when < 0.1 mi, miles otherwise. */
    private fun fmtDist(m: Double): String = DisplayUnits.bannerDistance(m)

    companion object {
        const val COLOR_GMAPS_NAV_DARK = 0xFF0D652D.toInt()
        const val COLOR_GMAPS_NAV_LIGHT = 0xFF137333.toInt()
        const val COLOR_GMAPS_LANE_DARK = 0xFF094C22.toInt()
        const val COLOR_GMAPS_LANE_LIGHT = 0xFF0D5B27.toInt()

        /** Dark red card for the stuck end state (backlog #3) — the route is still live on the comma. */
        const val COLOR_END_STUCK = 0xFF8C1D18.toInt()

        /** How long the "Rerouting…" pill stays up after a detected reroute. */
        const val REROUTE_SHOW_MS = 3000L
    }
}

/**
 * Canvas-drawn maneuver arrow icon matching Google Maps navigation style.
 */
class ManeuverIconView(context: Context) : View(context) {

    private var mType = ""
    private var mMod = ""

    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val p = Path()

    fun setManeuver(t: String, m: String) {
        if (mType != t || mMod != m) {
            mType = t
            mMod = m
            invalidate()
        }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val size = (44 * resources.displayMetrics.density).roundToInt()
        setMeasuredDimension(resolveSize(size, wSpec), resolveSize(size, hSpec))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val s = min(cx, cy) * 0.72f
        when {
            mType == "arrive" -> drawArrive(c, cx, cy, s)
            mType == "roundabout" || mType == "rotary" ->
                drawRoundabout(c, cx, cy, s, mMod.contains("left"))
            mType == "fork" -> drawFork(c, cx, cy, s, mMod.contains("left"))
            mType == "merge" -> drawStraight(c, cx, cy, s)
            mMod.contains("right") -> drawTurn(
                c, cx, cy, s, true, mMod.contains("slight"), mMod.contains("sharp")
            )
            mMod.contains("left") -> drawTurn(
                c, cx, cy, s, false, mMod.contains("slight"), mMod.contains("sharp")
            )
            else -> drawStraight(c, cx, cy, s)
        }
    }

    private fun drawStraight(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.reset()
        p.moveTo(cx, cy + s)
        p.lineTo(cx, cy - s * 0.7f)
        c.drawPath(p, sp)
        arrowH(c, cx, cy - s * 0.85f, 0f)
    }

    private fun drawTurn(
        c: Canvas, cx: Float, cy: Float, s: Float,
        right: Boolean, slight: Boolean, sharp: Boolean
    ) {
        val d = if (right) 1f else -1f
        p.reset()
        p.moveTo(cx, cy + s)
        p.lineTo(cx, if (sharp) cy + s * 0.15f else cy - s * 0.1f)
        val ex = cx + d * (if (slight) s * 0.55f else s * 0.75f)
        val ey = when {
            slight -> cy - s * 0.70f
            sharp -> cy + s * 0.05f
            else -> cy - s * 0.2f
        }
        p.quadTo(cx, cy - s * 0.3f, ex, ey)
        c.drawPath(p, sp)
        arrowH(c, ex, ey, d * (if (slight) 45f else if (sharp) 135f else 90f))
    }

    private fun drawFork(c: Canvas, cx: Float, cy: Float, s: Float, left: Boolean) {
        val d = if (left) -1f else 1f
        p.reset(); p.moveTo(cx, cy + s); p.lineTo(cx, cy); c.drawPath(p, sp)
        p.reset(); p.moveTo(cx, cy)
        p.lineTo(cx + d * s * 0.65f, cy - s * 0.75f); c.drawPath(p, sp)
        arrowH(c, cx + d * s * 0.65f, cy - s * 0.75f, d * 55f)
        val dimP = Paint(sp).apply { alpha = 100 }
        p.reset(); p.moveTo(cx, cy)
        p.lineTo(cx - d * s * 0.35f, cy - s * 0.65f); c.drawPath(p, dimP)
    }

    private fun drawRoundabout(c: Canvas, cx: Float, cy: Float, s: Float, left: Boolean) {
        val r = s * 0.32f; val circY = cy - s * 0.15f
        c.drawCircle(cx, circY, r, sp)
        p.reset(); p.moveTo(cx, cy + s); p.lineTo(cx, circY + r); c.drawPath(p, sp)
        val exitX = if (left) cx - r else cx + r
        arrowH(c, exitX, circY, if (left) -90f else 90f)
    }

    private fun drawArrive(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.reset(); p.moveTo(cx, cy + s); p.lineTo(cx, cy - s * 0.2f)
        c.drawPath(p, sp)
        c.drawCircle(cx, cy - s * 0.55f, s * 0.22f, sp)
        c.drawCircle(cx, cy - s * 0.55f, s * 0.08f, fp)
    }

    private fun arrowH(c: Canvas, x: Float, y: Float, deg: Float) {
        val sz = 7.5f * resources.displayMetrics.density
        c.save(); c.translate(x, y); c.rotate(deg)
        p.reset(); p.moveTo(0f, -sz * 0.6f)
        p.lineTo(-sz * 0.85f, sz * 0.6f)
        p.lineTo(sz * 0.85f, sz * 0.6f)
        p.close()
        c.drawPath(p, fp); c.restore()
    }
}

/**
 * Small lane direction arrow indicator for the lane guidance row.
 */
class LaneArrowView(
    ctx: Context,
    private val dirs: List<String>,
    private val active: Boolean
) : View(ctx) {

    private val den = resources.displayMetrics.density
    private val col = if (active) Color.WHITE else 0x55FFFFFF.toInt()
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (active) 4.2f * den else 3.0f * den
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = col
    }
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = col
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val p = Path()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = (50 * den).roundToInt()
        val h = (62 * den).roundToInt()
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f - 4f * den
        val s = min(cx, cy) * 0.75f

        val hasStraight = dirs.any { it.contains("straight") }
        val hasRight = dirs.any { it.contains("right") }
        val hasLeft = dirs.any { it.contains("left") }
        val hasUturn = dirs.any { it.contains("uturn") }

        if (hasStraight && hasLeft && hasRight) {
            drawStraight(c, cx, cy, s)
            drawLeftTurn(c, cx, cy, s)
            drawRightTurn(c, cx, cy, s)
        } else if (hasLeft && hasRight) {
            drawLeftTurn(c, cx, cy, s)
            drawRightTurn(c, cx, cy, s)
        } else if (hasStraight && hasRight) {
            drawStraight(c, cx, cy, s)
            drawRightTurn(c, cx, cy, s)
        } else if (hasStraight && hasLeft) {
            drawStraight(c, cx, cy, s)
            drawLeftTurn(c, cx, cy, s)
        } else if (hasRight) {
            if (dirs.any { it.contains("slight") }) {
                drawSlightRight(c, cx, cy, s)
            } else {
                drawRightTurn(c, cx, cy, s)
            }
        } else if (hasLeft) {
            if (dirs.any { it.contains("slight") }) {
                drawSlightLeft(c, cx, cy, s)
            } else {
                drawLeftTurn(c, cx, cy, s)
            }
        } else if (hasUturn) {
            drawUturn(c, cx, cy, s)
        } else {
            drawStraight(c, cx, cy, s)
        }

        // Active lane dot indicator at bottom (signature Google Maps lane marker)
        if (active) {
            c.drawCircle(cx, height - 5.5f * den, 3.8f * den, dotPaint)
        }
    }

    private fun drawStraight(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.reset()
        p.moveTo(cx, cy + s * 0.72f)
        p.lineTo(cx, cy - s * 0.45f)
        c.drawPath(p, sp)
        drawHead(c, cx, cy - s * 0.85f, 0f, s)
    }

    private fun drawRightTurn(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.reset()
        val startX = cx - s * 0.22f
        val startY = cy + s * 0.72f
        p.moveTo(startX, startY)
        p.lineTo(startX, cy + s * 0.15f)
        p.quadTo(startX, cy - s * 0.25f, cx + s * 0.45f, cy - s * 0.25f)
        c.drawPath(p, sp)
        drawHead(c, cx + s * 0.82f, cy - s * 0.25f, 90f, s)
    }

    private fun drawLeftTurn(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.reset()
        val startX = cx + s * 0.22f
        val startY = cy + s * 0.72f
        p.moveTo(startX, startY)
        p.lineTo(startX, cy + s * 0.15f)
        p.quadTo(startX, cy - s * 0.25f, cx - s * 0.45f, cy - s * 0.25f)
        c.drawPath(p, sp)
        drawHead(c, cx - s * 0.82f, cy - s * 0.25f, -90f, s)
    }

    private fun drawSlightRight(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.reset()
        p.moveTo(cx - s * 0.2f, cy + s * 0.72f)
        p.quadTo(cx - s * 0.2f, cy, cx + s * 0.45f, cy - s * 0.45f)
        c.drawPath(p, sp)
        drawHead(c, cx + s * 0.72f, cy - s * 0.72f, 45f, s)
    }

    private fun drawSlightLeft(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.reset()
        p.moveTo(cx + s * 0.2f, cy + s * 0.72f)
        p.quadTo(cx + s * 0.2f, cy, cx - s * 0.45f, cy - s * 0.45f)
        c.drawPath(p, sp)
        drawHead(c, cx - s * 0.72f, cy - s * 0.72f, -45f, s)
    }

    private fun drawUturn(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.reset()
        val stemX = cx + s * 0.35f
        p.moveTo(stemX, cy + s * 0.72f)
        p.lineTo(stemX, cy - s * 0.1f)
        val arcRect = RectF(cx - s * 0.55f, cy - s * 0.85f, stemX, cy - s * 0.05f)
        p.arcTo(arcRect, 0f, -180f)
        p.lineTo(cx - s * 0.55f, cy + s * 0.25f)
        c.drawPath(p, sp)
        drawHead(c, cx - s * 0.55f, cy + s * 0.65f, 180f, s)
    }

    private fun drawHead(c: Canvas, x: Float, y: Float, angleDeg: Float, s: Float) {
        val sz = s * 0.46f
        c.save()
        c.translate(x, y)
        c.rotate(angleDeg)
        p.reset()
        p.moveTo(0f, -sz * 0.65f)
        p.lineTo(-sz * 0.75f, sz * 0.6f)
        p.lineTo(sz * 0.75f, sz * 0.6f)
        p.close()
        c.drawPath(p, fp)
        c.restore()
    }
}
