package com.parampilot.auto

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * In-app Google Android Auto (Coolwalk Material 3) Head Unit preview.
 * Clean, zero-duplication layout:
 * - Header: Title, status/voltage chip, clock, and circular Close button
 * - Guidance Card: Maneuver arrow, distance, street name, and lane guidance
 * - Bottom Bar: Travel estimate (Time + Distance · ETA), Speed Limit & Speedometer, and Action buttons
 */
class AndroidAutoScreenView(
    private val ctx: Context,
    private val onEndNavClick: () -> Unit,
    private val onCloseClick: () -> Unit,
    private val onToggleSimClick: () -> Unit
) : FrameLayout(ctx) {

    private val den = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * den).roundToInt()

    private val clockTv: TextView
    private val statusChipTv: TextView
    private val endNavBtn: TextView
    private val simToggleBtn: TextView

    private val maneuverCard: LinearLayout
    private val maneuverIcon: ManeuverIconView
    private val maneuverDistTv: TextView
    private val maneuverRoadTv: TextView
    private val laneBoxRow: LinearLayout

    private val idleCard: LinearLayout
    private val idleTitleTv: TextView
    private val idleMessageTv: TextView

    private val speedPillTv: TextView
    private val speedLimitValTv: TextView
    private val speedLimitOffsetTv: TextView

    private val estimateCol: LinearLayout
    private val timeRemainingTv: TextView
    private val metaRemainingTv: TextView

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        visibility = View.GONE
        setBackgroundColor(0xD90B0C0E.toInt())
        elevation = 60f * den
        isClickable = true
        isFocusable = true
        setOnClickListener { onCloseClick() }

        val consoleFrame = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                setMargins(dp(16), dp(24), dp(16), dp(24))
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(0xFF131314.toInt())
                setStroke(dp(1), 0xFF3C4043.toInt())
            }
            elevation = 16f * den
            setPadding(dp(18), dp(16), dp(18), dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { /* consume */ }
        }

        // 1. Header Row
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(14))
            }
        }

        val appTitle = TextView(ctx).apply {
            text = "Android Auto"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFE3E3E3.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(appTitle)

        statusChipTv = TextView(ctx).apply {
            text = "Standby"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFF9AA0A6.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF1E1F20.toInt())
                setStroke(dp(1), 0xFF3C4043.toInt())
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(10), 0)
            }
        }
        topBar.addView(statusChipTv)

        clockTv = TextView(ctx).apply {
            text = "12:00 PM"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFE3E3E3.toInt())
            setPadding(0, 0, dp(12), 0)
        }
        topBar.addView(clockTv)

        val closeBtn = CloseCircleButtonView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { onCloseClick() }
        }
        topBar.addView(closeBtn)
        consoleFrame.addView(topBar)

        // 2. Google Maps Navigation Card
        maneuverCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xFF0D652D.toInt())
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }
        }

        val cardRow1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        maneuverIcon = ManeuverIconView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(0, 0, dp(14), 0)
            }
        }
        cardRow1.addView(maneuverIcon)

        val cardTextCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        maneuverDistTv = TextView(ctx).apply {
            text = "450 ft"
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            includeFontPadding = false
            setSingleLine(true)
        }
        cardTextCol.addView(maneuverDistTv)

        maneuverRoadTv = TextView(ctx).apply {
            text = "E Roosevelt St"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xEEFFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        cardTextCol.addView(maneuverRoadTv)
        cardRow1.addView(cardTextCol)
        maneuverCard.addView(cardRow1)

        laneBoxRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        maneuverCard.addView(laneBoxRow)
        consoleFrame.addView(maneuverCard)

        // 3. Idle Card (when no route is active)
        idleCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xFF1E1F20.toInt())
                setStroke(dp(1), 0xFF3C4043.toInt())
            }
            setPadding(dp(18), dp(16), dp(18), dp(16))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }
        }
        idleTitleTv = TextView(ctx).apply {
            text = "Ready to navigate"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFE3E3E3.toInt())
        }
        idleCard.addView(idleTitleTv)
        idleMessageTv = TextView(ctx).apply {
            text = "Search for a destination or start a route simulation."
            textSize = 13f
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        idleCard.addView(idleMessageTv)
        consoleFrame.addView(idleCard)

        // 4. Unified Bottom Bar: [ Travel Estimate | Speed + Limit | Actions ]
        val bottomRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xFF1E1F20.toInt())
                setStroke(dp(1), 0xFF3C4043.toInt())
            }
            setPadding(dp(16), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        estimateCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        timeRemainingTv = TextView(ctx).apply {
            text = "7 min"
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFF81C995.toInt())
            includeFontPadding = false
            setSingleLine(true)
        }
        estimateCol.addView(timeRemainingTv)

        metaRemainingTv = TextView(ctx).apply {
            text = "3.8 mi · 5:32 PM"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFF9AA0A6.toInt())
            setSingleLine(true)
            setPadding(0, dp(2), 0, 0)
        }
        estimateCol.addView(metaRemainingTv)
        bottomRow.addView(estimateCol)

        // Compact Speed & Limit Cluster
        val speedCluster = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF28292A.toInt())
            }
            setPadding(dp(10), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), 0, dp(10), 0)
            }
        }

        val limitBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.BLACK)
            }
            setPadding(dp(5), dp(2), dp(5), dp(2))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        limitBox.addView(TextView(ctx).apply {
            text = "LIMIT"
            textSize = 6.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            includeFontPadding = false
        })
        speedLimitValTv = TextView(ctx).apply {
            text = "--"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            includeFontPadding = false
        }
        limitBox.addView(speedLimitValTv)

        speedLimitOffsetTv = TextView(ctx).apply {
            text = "--"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFA8C7FA.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(0xFF1A2A44.toInt())
            }
            setPadding(dp(4), dp(3), dp(4), dp(3))
            includeFontPadding = false
        }
        limitBox.addView(speedLimitOffsetTv)
        speedCluster.addView(limitBox)

        speedPillTv = TextView(ctx).apply {
            text = "0 mph"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        speedCluster.addView(speedPillTv)
        bottomRow.addView(speedCluster)

        // Action Buttons (Simulate / End)
        simToggleBtn = TextView(ctx).apply {
            text = "Simulate"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFF062E6F.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFFA8C7FA.toInt())
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onToggleSimClick() }
        }
        bottomRow.addView(simToggleBtn)

        endNavBtn = TextView(ctx).apply {
            text = "End"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFFD93025.toInt())
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), 0, 0, 0)
            }
            setOnClickListener { onEndNavClick() }
        }
        bottomRow.addView(endNavBtn)

        consoleFrame.addView(bottomRow)
        addView(consoleFrame)
    }

    fun update(pkt: TelemetryPacket) {
        clockTv.text = SimpleDateFormat("h:mm a", Locale.US).format(Calendar.getInstance().time)

        val curMph = (pkt.speedMs * Units.MS_TO_MPH).roundToInt()
        val limMph = (pkt.speedLimit.limitMs * Units.MS_TO_MPH).roundToInt()
        val offsetMph = NavStateManager.getSlcOffsetMph(limMph)
        speedPillTv.text = "$curMph mph"
        speedLimitValTv.text = if (limMph > 0) "$limMph" else "--"
        speedLimitOffsetTv.text = if (limMph > 0) {
            if (offsetMph >= 0) "+$offsetMph" else "$offsetMph"
        } else "--"

        val isControls = pkt.cruiseActive || NavStateManager.controlsActive
        val isLateral = pkt.lateralControlActive || NavStateManager.lateralControlActive
        val stateLabel = when {
            isControls && isLateral -> "Engaged"
            isLateral -> "Steering"
            isControls -> "Cruise"
            else -> "Standby"
        }
        val voltStr = pkt.power?.takeIf { it.voltageV > 1.0 }?.let { " · ${"%.1f".format(it.voltageV)}V" } ?: ""
        statusChipTv.text = "$stateLabel$voltStr"

        val nav = pkt.nav
        val dest = pkt.destination ?: NavStateManager.activeDestination
        val chRoute = NavStateManager.selectedRoute ?: NavStateManager.activeRoutes.firstOrNull()
        val carPos = pkt.gps ?: NavStateManager.currentGps
        val rawEffective = if (nav.valid && nav.type.isNotEmpty() && nav.type != "straight") nav
        else NavStateManager.getEffectiveManeuver(pkt)
        val effective = rawEffective

        if (effective.valid && dest != null) {
            maneuverCard.visibility = View.VISIBLE
            idleCard.visibility = View.GONE
            maneuverIcon.setManeuver(effective.type, effective.modifier)
            val targetRoad = effective.street.ifEmpty { dest.name }
            if (effective.distanceM > 0.0) {
                maneuverDistTv.text = formatDistance(effective.distanceM)
                maneuverRoadTv.text = targetRoad
                maneuverRoadTv.visibility = View.VISIBLE
            } else {
                maneuverDistTv.text = targetRoad
                maneuverRoadTv.visibility = View.GONE
            }
            val lanesToBuild = if (nav.valid && nav.lanes.isNotEmpty()) nav.lanes else effective.lanes
            buildLanes(lanesToBuild, effective.laneCount, effective.activeLaneIndex)
        } else if (dest != null) {
            maneuverCard.visibility = View.VISIBLE
            idleCard.visibility = View.GONE
            maneuverIcon.setManeuver("straight", "")
            val chosenRoute = NavStateManager.selectedRoute ?: NavStateManager.activeRoutes.firstOrNull()
            val dMeters = if (chosenRoute != null && chosenRoute.distanceMiles > 0.0) {
                chosenRoute.distanceMiles * Units.METERS_PER_MILE
            } else {
                dest.distanceM ?: 0.0
            }
            if (dMeters > 0.0) {
                maneuverDistTv.text = formatDistance(dMeters)
                maneuverRoadTv.text = dest.name
                maneuverRoadTv.visibility = View.VISIBLE
            } else {
                maneuverDistTv.text = dest.name
                maneuverRoadTv.visibility = View.GONE
            }
            laneBoxRow.removeAllViews()
            laneBoxRow.visibility = View.GONE
        } else {
            maneuverCard.visibility = View.GONE
            idleCard.visibility = View.VISIBLE
        }

        val chosenRoute = NavStateManager.selectedRoute
        val curGps = pkt.gps ?: NavStateManager.currentGps
        val hasDist = (chosenRoute != null && curGps != null && chosenRoute.points.isNotEmpty()) ||
            (dest != null && dest.hasCoordinates && curGps != null) ||
            (nav.valid && nav.distanceM > 0.0)

        val distM = if (chosenRoute != null && curGps != null && chosenRoute.points.isNotEmpty()) {
            val prog = RouteProgress.compute(chosenRoute.points, curGps.latitude, curGps.longitude)
            if (prog != null && prog.remainingM > 0.0) prog.remainingM else (chosenRoute.distanceMiles * Units.METERS_PER_MILE)
        } else if (dest != null && dest.hasCoordinates && curGps != null) {
            GuidanceMath.haversineM(curGps.latitude, curGps.longitude, dest.latitude, dest.longitude)
        } else if (nav.valid && nav.distanceM > 0.0) {
            nav.distanceM
        } else {
            0.0
        }

        if (dest != null) {
            estimateCol.visibility = View.VISIBLE
            endNavBtn.visibility = View.VISIBLE
            val rawSpeedMs = if (pkt.speedMs > 0.0) pkt.speedMs else NavStateManager.speedMs
            val prog = if (chosenRoute != null && curGps != null && chosenRoute.points.isNotEmpty()) {
                RouteProgress.compute(chosenRoute.points, curGps.latitude, curGps.longitude)
            } else null

            // Same layer-2 GuidanceMath the AA template and the
            // phone trip bar run (audit finding 11: this preview
            // used to compute its own ETA chain and could disagree
            // with both). The distM chain above stays as the
            // distance fallback when GuidanceMath has no total.
            val g = GuidanceMath.compute(
                GuidanceMath.Input.fromState(
                    effNav = effective,
                    nav = nav,
                    route = chosenRoute,
                    dest = dest,
                    gps = curGps,
                    rawSpeedMs = rawSpeedMs,
                    progress = prog
                )
            )
            val remainingDistMiles = g.totalDistanceMiles
                ?: (g.totalDistanceM?.let { it * Units.METERS_TO_MILES }
                    ?: if (hasDist && distM > 0.0) distM * Units.METERS_TO_MILES else (chosenRoute?.distanceMiles ?: 0.0))
            val remainingMin: Int? = g.totalEtaSeconds?.let { maxOf(1, (it / 60).toInt()) }

            if (remainingMin != null && remainingDistMiles > 0.0) {
                timeRemainingTv.text = "$remainingMin min"
                val etaCal = Calendar.getInstance().apply { add(Calendar.MINUTE, remainingMin) }
                val etaStr = SimpleDateFormat("h:mm a", Locale.US).format(etaCal.time)
                metaRemainingTv.text = "${"%.1f".format(remainingDistMiles)} mi · $etaStr"
            } else {
                timeRemainingTv.text = "-- min"
                metaRemainingTv.text = if (remainingDistMiles > 0.0) "${"%.1f".format(remainingDistMiles)} mi" else "-- mi"
            }
        } else {
            estimateCol.visibility = View.INVISIBLE
            endNavBtn.visibility = View.GONE
        }

        simToggleBtn.text = if (SimulationEngine.isSimulating) "Stop" else "Simulate"
    }

    private fun buildLanes(lanes: List<LaneInfo>, cnt: Int, activeIdx: Int) {
        laneBoxRow.removeAllViews()
        if (lanes.isEmpty() && cnt <= 0) {
            laneBoxRow.visibility = View.GONE
            return
        }
        val items = lanes.ifEmpty {
            (0 until cnt).map { LaneInfo(listOf("straight"), it == activeIdx) }
        }
        for (l in items) {
            laneBoxRow.addView(LaneArrowView(ctx, l.directions, l.active).apply {
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(54)).apply {
                    setMargins(0, 0, dp(8), 0)
                }
            })
        }
        laneBoxRow.visibility = View.VISIBLE
    }

    private fun formatDistance(m: Double): String {
        val ft = m * 3.28084
        if (ft < 528) return "${((ft / 10).roundToInt() * 10).coerceAtLeast(10)} ft"
        val mi = m * Units.METERS_TO_MILES
        return if (mi < 10) "${"%.1f".format(mi)} mi" else "${mi.roundToInt()} mi"
    }
}
