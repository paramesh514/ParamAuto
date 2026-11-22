package com.parampilot.auto



import android.content.Context

import android.graphics.Canvas

import android.graphics.Color

import android.graphics.Paint

import android.graphics.Typeface

import android.graphics.drawable.GradientDrawable

import android.text.TextUtils

import android.util.Log

import android.view.Gravity

import android.view.View

import android.widget.FrameLayout

import android.widget.LinearLayout

import android.widget.TextView

import kotlin.math.cos

import kotlin.math.min

import kotlin.math.roundToInt

import kotlin.math.sin



/**

 * Google Maps Material 3 HUD overlay on top of the MapView:

 * - Bottom-left: Compact Settings button + unified Status chip (connection + voltage)

 * - Bottom-right: Alternate routes, Compass, Speed Limit sign, and Speedometer circle

 * - Bottom-center: Google Maps Re-center capsule

 */

class HudOverlay(

    private val context: Context,

    private val onGpuClick: (() -> Unit)? = null,

    private val onStatusClick: () -> Unit,

    private val onSettingsClick: () -> Unit,

    private val onSimClick: () -> Unit,

    private val onAAScreenClick: () -> Unit,

    private val onAltRoutesClick: () -> Unit,

    private val onRecenterClick: () -> Unit,

    private val onSpeedCardClick: () -> Unit,

    private val onCompassClick: () -> Unit,

    private val onUpdateBadgeClick: (() -> Unit)? = null,

    private val onSpeedLimitLongPress: (() -> Unit)? = null

) {



    private val density = context.resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).roundToInt()



    private lateinit var statusDotView: StatusDotView

    private lateinit var statusLabelView: TextView

    private lateinit var statusPill: LinearLayout

    /** Feature 14 state, rendered IN the state pill (Param,
     * 2026-11-20: no separate update pill). */
    private var updateReady: Boolean = false

    /** Addendum 126: a user-confirmed install is pending; the badge
     * shows "Updating…" until the comma finishes it. */
    private var updatePending: Boolean = false

    /** Last device state passed to updateDeviceState, for badge re-renders. */
    private var lastDeviceState: String? = null

    private lateinit var deviceStateBadge: TextView

    private lateinit var settingsBtn: SettingsGearView

    private lateinit var gpuPill: TextView
    private lateinit var voltagePill: TextView





    private lateinit var altRoutesBtn: AltRouteView

    private lateinit var gpsFeedBtn: GpsSharingView

    private lateinit var compassView: CompassView

    private lateinit var speedLimitSign: SpeedLimitSignView

    private lateinit var speedLimitSourceView: TextView

    private lateinit var speedCard: LinearLayout

    private lateinit var speedValView: TextView

    private lateinit var speedUnitView: TextView

    private lateinit var routesBar: LinearLayout

    private lateinit var recenterBtn: TextView

    private lateinit var bottomLeftContainer: LinearLayout

    private lateinit var speedContainer: LinearLayout



    private var isNight: Boolean = true


    private var latestConnectionText: String = "Offline"

    private var latestDotColor: Int = COLOR_TEXT_MUTED

    private var latestFreshnessText: String = ""

    private var freshnessDotOverride: Int? = null

    private var isSpeeding: Boolean = false



    fun attachTo(root: FrameLayout) {

        // Edge-to-edge is enforced at targetSdk 35+: the map stays

        // full-bleed, but the bottom-anchored controls must clear the

        // system navigation bar. The base margins below assume gesture

        // navigation (no inset); attachTo lifts them by the real

        // navigation-bar inset whenever one is reported (Param, 2026-11-02).

        buildBottomLeftCluster(root)



        buildRoutesBar(root)



        buildSpeedCluster(root)



        buildRecenterButton(root)

        applySystemBarInsets(root)

        updateGpsSharing(PhoneGpsFeeder.isFeeding)

    }

    /**
     * attachTo, bottom-left cluster (extracted 2026-11-08): GPU pill + voltage pill over the parked/status row (device badge, status pill, update badge).
     */
    private fun buildBottomLeftCluster(root: FrameLayout) {
        // Bottom-Left: Vertical container with Battery Voltage pill on top of parked state pill & status pill

        bottomLeftContainer = LinearLayout(context).apply {

            orientation = LinearLayout.VERTICAL

            gravity = Gravity.START

            layoutParams = FrameLayout.LayoutParams(-2, -2).apply {

                gravity = Gravity.BOTTOM or Gravity.START

                setMargins(dp(8), 0, dp(80), dp(32))

            }

        }



        // GPU Pill: sits on top of the voltage pill (Param, 2026-11-06).
        // Text comes from GpuStatus (layer 2) via updateGpu; tap opens
        // the full GPU dialog.
        gpuPill = TextView(context).apply {
            text = "GPU —"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            elevation = 6f * density
            background = makeCardBg(context, COLOR_SURFACE_DARK, 14, COLOR_BORDER_DARK, 1)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply {
                gravity = Gravity.START
                setMargins(0, 0, 0, dp(6))
            }
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onGpuClick?.invoke() }
        }
        bottomLeftContainer.addView(gpuPill)

        // Battery Voltage Pill: positioned on top of the parked state pill

        voltagePill = TextView(context).apply {

            text = "🔋 --.-V"

            textSize = 12f

            typeface = Typeface.DEFAULT_BOLD

            setTextColor(Color.WHITE)

            elevation = 6f * density

            background = makeCardBg(context, COLOR_SURFACE_DARK, 14, COLOR_BORDER_DARK, 1)

            setPadding(dp(8), dp(4), dp(8), dp(4))

            layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply {

                gravity = Gravity.START

                setMargins(0, 0, 0, dp(6))

            }

            gravity = Gravity.CENTER

            isClickable = true

            isFocusable = true

            setOnClickListener { onStatusClick() }

        }

        bottomLeftContainer.addView(voltagePill)



        val bottomRow = LinearLayout(context).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL

            layoutParams = LinearLayout.LayoutParams(-2, -2)

        }



        deviceStateBadge = TextView(context).apply {

            text = "🅿️ Parked"

            textSize = 12f

            typeface = Typeface.DEFAULT_BOLD

            setTextColor(COLOR_ACCENT_AMBER)

            elevation = 6f * density

            background = makeCardBg(context, COLOR_BADGE_AMBER_BG, 14, COLOR_ACCENT_AMBER, 1)

            setPadding(dp(8), dp(4), dp(8), dp(4))

            visibility = View.GONE

            layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply {

                gravity = Gravity.CENTER_VERTICAL

                setMargins(0, 0, dp(6), 0)

            }

            gravity = Gravity.CENTER

            isClickable = true

            isFocusable = true

            setOnClickListener { onStatusClick() }

        }

        bottomRow.addView(deviceStateBadge)



        statusPill = LinearLayout(context).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL

            elevation = 6f * density

            background = makeCardBg(context, COLOR_SURFACE_DARK, 20, COLOR_BORDER_DARK, 1)

            setPadding(dp(10), dp(5), dp(10), dp(5))

            layoutParams = LinearLayout.LayoutParams(-2, dp(34))

            isClickable = true

            isFocusable = true

            setOnClickListener { onStatusClick() }

        }



        statusDotView = StatusDotView(context).apply {

            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {

                setMargins(0, 0, dp(6), 0)

            }

            isClickable = false

        }

        statusPill.addView(statusDotView)



        statusLabelView = TextView(context).apply {

            text = "Offline"

            textSize = 12f

            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

            setTextColor(Color.WHITE)

            setSingleLine(true)

            maxLines = 1

            ellipsize = android.text.TextUtils.TruncateAt.END

            includeFontPadding = false

            isClickable = false

        }

        statusPill.addView(statusLabelView)

        bottomRow.addView(statusPill)



        // No separate update badge: the update-ready state rides
        // the CAR STATE badge (deviceStateBadge) itself (Param,
        // 2026-11-20 — see setUpdateReady / renderDeviceStateBadge).

        bottomLeftContainer.addView(bottomRow)

        root.addView(bottomLeftContainer)
    }

    /**
     * attachTo, route option chips (extracted 2026-11-08): the overview-mode alternatives bar below the top bar.
     */
    private fun buildRoutesBar(root: FrameLayout) {
        // Route Option Chips (below top bar during overview mode)

        routesBar = LinearLayout(context).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER

            visibility = View.GONE

            layoutParams = FrameLayout.LayoutParams(-2, -2).apply {

                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL

                setMargins(dp(16), dp(48), dp(16), 0)

            }

        }

        root.addView(routesBar)
    }

    /**
     * attachTo, bottom-right stack (extracted 2026-11-08): alt-routes, GPS-feed, compass, speed limit + source, speed card, settings gear.
     */
    private fun buildSpeedCluster(root: FrameLayout) {
        // Bottom-Right: Vertical Stack (Alt Routes -> Compass (North-up) -> Speed Limit -> Speedometer -> Settings)

        // Compass, Speed Limit, and Current Speed moved up; Settings Gear placed at the bottom

        speedContainer = LinearLayout(context).apply {

            orientation = LinearLayout.VERTICAL

            gravity = Gravity.CENTER_HORIZONTAL

            layoutParams = FrameLayout.LayoutParams(-2, -2).apply {

                gravity = Gravity.BOTTOM or Gravity.END

                setMargins(0, 0, dp(16), dp(32))

            }

        }



        // Uniformly sized right-side control column: each widget is exactly 60dp x 60dp (+25% increase)

        altRoutesBtn = AltRouteView(context).apply {

            elevation = 6f * density

            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply {

                gravity = Gravity.CENTER_HORIZONTAL

                setMargins(0, 0, 0, dp(8))

            }

            visibility = View.GONE

            setOnClickListener { onAltRoutesClick() }

        }

        speedContainer.addView(altRoutesBtn)



        // GPS Sharing indicator button (Feature: GPS Icon on top of North Up / Head Up icon)

        gpsFeedBtn = GpsSharingView(context).apply {

            elevation = 6f * density

            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply {

                gravity = Gravity.CENTER_HORIZONTAL

                setMargins(0, 0, 0, dp(8))

            }

            visibility = View.GONE

            setOnClickListener {

                android.widget.Toast.makeText(context, "Phone is sharing GPS with comma", android.widget.Toast.LENGTH_SHORT).show()

            }

        }

        speedContainer.addView(gpsFeedBtn)



        compassView = CompassView(context).apply {

            elevation = 6f * density

            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply {

                gravity = Gravity.CENTER_HORIZONTAL

                setMargins(0, 0, 0, dp(8))

            }

            setOnClickListener { onCompassClick() }

        }

        speedContainer.addView(compassView)



        speedLimitSign = SpeedLimitSignView(context).apply {

            elevation = 6f * density

            layoutParams = LinearLayout.LayoutParams(dp(60), dp(104)).apply {

                gravity = Gravity.CENTER_HORIZONTAL

                setMargins(0, 0, 0, dp(8))

            }

            setOnClickListener { onSpeedCardClick() }

            // Offsets glance (Feature 19): long-press shows the comma's

            // current SLC offset table, read fresh, read-only.

            if (onSpeedLimitLongPress != null) {

                setOnLongClickListener {

                    onSpeedLimitLongPress.invoke()

                    true

                }

            }

        }

        speedContainer.addView(speedLimitSign)



        // Provenance label under the sign (Feature 5): "comma" when the

        // comma's payload carried the limit, "route" when it came from the

        // route's Mapbox annotations; hidden when there is no limit.

        speedLimitSourceView = TextView(context).apply {

            textSize = 10f

            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

            setTextColor(COLOR_TEXT_MUTED)

            gravity = Gravity.CENTER

            includeFontPadding = false

            setSingleLine(true)

            visibility = View.GONE

            layoutParams = LinearLayout.LayoutParams(

                LinearLayout.LayoutParams.WRAP_CONTENT,

                LinearLayout.LayoutParams.WRAP_CONTENT

            ).apply {

                gravity = Gravity.CENTER_HORIZONTAL

                setMargins(0, 0, 0, dp(8))

            }

        }

        speedContainer.addView(speedLimitSourceView)



        speedCard = LinearLayout(context).apply {

            orientation = LinearLayout.VERTICAL

            gravity = Gravity.CENTER

            background = makeCardBg(context, COLOR_SURFACE_DARK, 24, COLOR_BORDER_DARK, 1)

            elevation = 6f * density

            setPadding(0, 0, 0, dp(1))

            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply {

                gravity = Gravity.CENTER_HORIZONTAL

                setMargins(0, 0, 0, dp(8))

            }

            setOnClickListener { onSpeedCardClick() }



            speedValView = TextView(context).apply {

                text = "0"

                textSize = 22f

                typeface = Typeface.create("sans-serif", Typeface.BOLD)

                setTextColor(Color.WHITE)

                gravity = Gravity.CENTER

                includeFontPadding = false

                setSingleLine(true)

            }

            addView(speedValView)



            speedUnitView = TextView(context).apply {

                text = "mph"

                textSize = 12f

                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

                setTextColor(COLOR_TEXT_MUTED)

                gravity = Gravity.CENTER

                includeFontPadding = false

                setSingleLine(true)

            }

            addView(speedUnitView)

        }

        speedContainer.addView(speedCard)



        settingsBtn = SettingsGearView(context).apply {

            elevation = 6f * density

            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply {

                gravity = Gravity.CENTER_HORIZONTAL

            }

            setOnClickListener { onSettingsClick() }

        }

        speedContainer.addView(settingsBtn)

        root.addView(speedContainer)
    }

    /**
     * attachTo, recenter pill (extracted 2026-11-08): the bottom-center Google-Maps-style re-center button.
     */
    private fun buildRecenterButton(root: FrameLayout) {
        // Bottom-Center: Google Maps Re-center Pill Button

        recenterBtn = TextView(context).apply {

            text = "Re-center"

            textSize = 14f

            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)

            setTextColor(COLOR_GOOGLE_BLUE_DARK)

            gravity = Gravity.CENTER

            background = makeCardBg(context, COLOR_SURFACE_DARK, 22, COLOR_BORDER_DARK, 1)

            elevation = 8f * density

            setPadding(dp(18), dp(10), dp(18), dp(10))

            visibility = View.GONE

            layoutParams = FrameLayout.LayoutParams(-2, -2).apply {

                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL

                setMargins(0, 0, 0, dp(80))

            }

            setOnClickListener { onRecenterClick() }

        }

        root.addView(recenterBtn)
    }



    /**

     * Remember a bottom-anchored control's authored bottom margin so the

     * insets pass can always recompute from the base, never accumulate.

     * The tag records the base margin (px); see [liftedBottomMargin].

     */

    private fun baseBottomMargin(view: View): Int {

        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return 0

        val existing = (view.tag as? Int)

        if (existing != null) return existing

        view.tag = lp.bottomMargin

        return lp.bottomMargin

    }



    /**

     * Lift every bottom-anchored HUD control above the navigation bar:

     * whenever the window reports a nav-bar inset, each control's bottom

     * margin becomes max(authored base, inset). Runs again on every

     * insets change (rotation, gesture/button nav switch, foldables).

     */

    /** Last navigation-bar inset reported by the window (px). */
    private var lastNavInsetPx: Int = 0

    /** Clearance the bottom notification dock needs (px); 0 when empty. */
    private var bottomStackClearancePx: Int = 0

    /**
     * Addendum 127: the bottom notification dock (MainActivity) reports
     * its laid-out height after every layout pass; the bottom control
     * clusters lift above the whole stack. [contentHeightPx] is the
     * dock's own height — its 16dp bottom margin and an 8dp visual gap
     * are added here.
     */
    fun setBottomStackHeight(contentHeightPx: Int) {
        val clearance = if (contentHeightPx <= 0) 0 else contentHeightPx + dp(24)
        if (clearance == bottomStackClearancePx) return
        bottomStackClearancePx = clearance
        refreshBottomMargins()
    }

    /** Single writer of the bottom-anchored controls' bottom margins. */
    private fun refreshBottomMargins() {
        for (view in listOf(bottomLeftContainer, speedContainer, recenterBtn)) {
            val lp = view.layoutParams as? FrameLayout.LayoutParams ?: continue
            val target = stackedBottomMargin(
                (view.tag as? Int) ?: 0, lastNavInsetPx, bottomStackClearancePx
            )
            if (lp.bottomMargin != target) {
                lp.bottomMargin = target
                view.layoutParams = lp
            }
        }
    }

    private fun applySystemBarInsets(root: FrameLayout) {
        val bottomAnchored = listOf(bottomLeftContainer, speedContainer, recenterBtn)
        bottomAnchored.forEach { baseBottomMargin(it) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            lastNavInsetPx = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            ).bottom
            refreshBottomMargins()
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }



    fun updateSpeed(speedMs: Double, speedLimitMs: Double) {

        val rawMph = speedMs * MS_TO_MPH

        val curMph = SpeedometerSmoother.filter(rawMph)

        speedValView.text = "$curMph"



        val limMph = if (speedLimitMs >= 1.0) (speedLimitMs * MS_TO_MPH).roundToInt() else 0

        val offsetMph = NavStateManager.getSlcOffsetMph(limMph)

        speedLimitSign.setLimitAndOffset(limMph, offsetMph)



        isSpeeding = limMph > 0 && curMph > limMph

        updateSpeedCardAppearance()

    }



    /**

     * Shows the speed limit's provenance under the sign (Feature 5):

     * "comma" for a payload-carried limit, "route" for a Mapbox-derived

     * one; hidden whenever there is no usable limit or source.

     */

    /**
     * Feature 14: a downloaded update is staged on the comma. The
     * state shows on the CAR STATE badge (deviceStateBadge), not the
     * connection pill and not a pill of its own (Param, 2026-11-20:
     * "Use car state pill for update ready, updating").
     * renderDeviceStateBadge does the rendering.
     */
    fun setUpdateReady(ready: Boolean) {
        updateReady = ready
        renderDeviceStateBadge()
    }

    /** Addendum 126: a user-confirmed install is pending. */
    fun setUpdatePending(pending: Boolean) {
        updatePending = pending
        renderDeviceStateBadge()
    }



    fun setSpeedLimitSource(source: String?, speedLimitMs: Double) {

        if (!::speedLimitSourceView.isInitialized) return

        val limMph = if (speedLimitMs >= 1.0) (speedLimitMs * MS_TO_MPH).roundToInt() else 0

        val label = SpeedLimitSource.label(source, limMph)

        if (label == null) {

            speedLimitSourceView.visibility = View.GONE

        } else {

            speedLimitSourceView.text = label

            speedLimitSourceView.visibility = View.VISIBLE

        }

    }



    private fun updateSpeedCardAppearance() {

        val cardBg = if (isNight) COLOR_SURFACE_DARK else Color.WHITE

        val borderCol = if (isNight) COLOR_BORDER_DARK else COLOR_BORDER_LIGHT

        val textCol = if (isNight) Color.WHITE else COLOR_TEXT_PRIMARY_LIGHT

        val mutedCol = if (isNight) COLOR_TEXT_MUTED else COLOR_TEXT_MUTED_LIGHT



        if (isSpeeding) {

            speedValView.setTextColor(Color.WHITE)

            speedUnitView.setTextColor(0xEEFFFFFF.toInt())

            speedCard.background = makeCardBg(context, 0xFFD93025.toInt(), 24)

        } else {

            speedValView.setTextColor(textCol)

            speedUnitView.setTextColor(mutedCol)

            speedCard.background = makeCardBg(context, cardBg, 24, borderCol, 1)

        }

    }



    fun updateBearing(bearingDeg: Double) {

        compassView.setBearing(bearingDeg)

    }



    fun showRoutes(routes: List<RouteOption>, selectedId: Int, onSelect: (RouteOption) -> Unit) {

        routesBar.removeAllViews()

        if (routes.size <= 1) {

            routesBar.visibility = View.GONE

            return

        }

        val surfaceBg = if (isNight) COLOR_SURFACE_DARK else Color.WHITE

        val borderCol = if (isNight) COLOR_BORDER_DARK else COLOR_BORDER_LIGHT

        val normalText = if (isNight) Color.WHITE else COLOR_TEXT_PRIMARY_LIGHT

        val selBg = if (isNight) 0xFF1A73E8.toInt() else 0xFF1A73E8.toInt()



        for (r in routes) {

            val isSel = r.id == selectedId

            val cleanSummary = r.summary.split(",").firstOrNull()?.trim() ?: r.summary

            val chip = TextView(context).apply {

                text = if (cleanSummary.isNotBlank()) "${r.durationMin} min · $cleanSummary" else "${r.durationMin} min"

                textSize = 13f

                typeface = Typeface.create("sans-serif-medium", if (isSel) Typeface.BOLD else Typeface.NORMAL)

                setTextColor(if (isSel) Color.WHITE else normalText)

                background = if (isSel) makeCardBg(context, selBg, 18)

                else makeCardBg(context, surfaceBg, 18, borderCol, 1)

                setPadding(dp(14), dp(8), dp(14), dp(8))

                elevation = 6f * density

                maxLines = 1

                ellipsize = TextUtils.TruncateAt.END

                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) }

                setOnClickListener { onSelect(r) }

            }

            routesBar.addView(chip)

        }

        routesBar.visibility = View.VISIBLE

    }



    fun hideRoutes() { routesBar.visibility = View.GONE }



    fun setNavActive(active: Boolean) {
        altRoutesBtn.visibility = if (active) View.VISIBLE else View.GONE
        // Addendum 127: no margin writes here — the bottom clusters'
        // lift is driven by the notification dock's real height
        // (setBottomStackHeight). During navigation the TripInfoBar
        // is a dock child, so the lift follows it automatically.
    }



    fun applyTheme(night: Boolean) {

        isNight = night

        val cardBg = if (night) COLOR_SURFACE_DARK else Color.WHITE

        val borderCol = if (night) COLOR_BORDER_DARK else COLOR_BORDER_LIGHT

        val textCol = if (night) Color.WHITE else COLOR_TEXT_PRIMARY_LIGHT

        val blueAccent = if (night) COLOR_GOOGLE_BLUE_DARK else COLOR_GOOGLE_BLUE_LIGHT



        statusPill.background = makeCardBg(context, cardBg, 20, borderCol, 1)

        statusLabelView.setTextColor(textCol)

        settingsBtn.applyTheme(night)

        if (::voltagePill.isInitialized) {

            voltagePill.background = makeCardBg(context, cardBg, 14, borderCol, 1)

            val currentV = NavStateManager.latestPower?.voltageV

            if (currentV != null && currentV > 1.0 && currentV < 11.9) {

                voltagePill.setTextColor(COLOR_ACCENT_AMBER)

            } else {

                voltagePill.setTextColor(textCol)

            }

        }

        updateSpeedCardAppearance()

        recenterBtn.background = makeCardBg(context, cardBg, 22, borderCol, 1)

        recenterBtn.setTextColor(blueAccent)

        compassView.applyTheme(night)

        altRoutesBtn.applyTheme(night)

        if (::gpsFeedBtn.isInitialized) gpsFeedBtn.applyTheme(night)

    }



    fun setAltRouteOverviewActive(active: Boolean) { altRoutesBtn.setOverviewActive(active) }

    fun showRecenter() { recenterBtn.visibility = View.VISIBLE }

    fun hideRecenter() { recenterBtn.visibility = View.GONE }



    fun updateDeviceState(state: String?) {

        lastDeviceState = state

        renderDeviceStateBadge()

        refreshUnifiedStatusPill()

    }



    /**
     * Renders the car state badge. While a comma update is staged,
     * the badge carries the update state instead (Param, 2026-11-20:
     * "Use car state pill for update ready, updating") and its tap
     * opens the software dialog; otherwise it shows the car state
     * (Parked / Driving / Standby) with its usual tap.
     */
    private fun renderDeviceStateBadge() {

        if (!::deviceStateBadge.isInitialized) return

        if (updatePending) {

            deviceStateBadge.text = "⬆️ Updating…"

            deviceStateBadge.setTextColor(COLOR_ACCENT_AMBER)

            deviceStateBadge.background = makeCardBg(context, COLOR_BADGE_AMBER_BG, 14, COLOR_ACCENT_AMBER, 1)

            deviceStateBadge.visibility = View.VISIBLE

            deviceStateBadge.setOnClickListener { onUpdateBadgeClick?.invoke() }

            return

        }

        if (updateReady) {

            deviceStateBadge.text = "⬆️ Update ready"

            deviceStateBadge.setTextColor(COLOR_ACCENT_AMBER)

            deviceStateBadge.background = makeCardBg(context, COLOR_BADGE_AMBER_BG, 14, COLOR_ACCENT_AMBER, 1)

            deviceStateBadge.visibility = View.VISIBLE

            deviceStateBadge.setOnClickListener { onUpdateBadgeClick?.invoke() }

            return

        }

        deviceStateBadge.setOnClickListener { onStatusClick() }

        val state = lastDeviceState

        if (state.isNullOrBlank()) {

            deviceStateBadge.visibility = View.GONE

            return

        }

        val s = state.trim().lowercase()

        when (s) {

            "parked", "offroad" -> {

                deviceStateBadge.text = "🅿️ ${s.replaceFirstChar { it.uppercase() }}"

                deviceStateBadge.setTextColor(COLOR_ACCENT_AMBER)

                deviceStateBadge.background = makeCardBg(context, COLOR_BADGE_AMBER_BG, 14, COLOR_ACCENT_AMBER, 1)

                deviceStateBadge.visibility = View.VISIBLE

            }

            "driving", "started", "engaged" -> {

                deviceStateBadge.text = "🚗 ${s.replaceFirstChar { it.uppercase() }}"

                deviceStateBadge.setTextColor(COLOR_ACCENT_GREEN)

                deviceStateBadge.background = makeCardBg(context, COLOR_BADGE_GREEN_BG, 14, COLOR_ACCENT_GREEN, 1)

                deviceStateBadge.visibility = View.VISIBLE

            }

            "standby" -> {

                deviceStateBadge.text = "⏸️ Standby"

                deviceStateBadge.setTextColor(COLOR_ACCENT_CYAN)

                deviceStateBadge.background = makeCardBg(context, 0xEE0B2535.toInt(), 14, COLOR_ACCENT_CYAN, 1)

                deviceStateBadge.visibility = View.VISIBLE

            }

            else -> {

                deviceStateBadge.visibility = View.GONE

            }

        }

    }



    fun updateVoltage(power: PowerState?) {

        val vNum = if (power != null && power.voltageV > 1.0) {

            "%.1f".format(power.voltageV)

        } else {

            "--.-"

        }


        if (::voltagePill.isInitialized) {

            voltagePill.text = "🔋 ${vNum}V"

            if (power != null && power.voltageV > 1.0 && power.voltageV < 11.9) {

                voltagePill.setTextColor(COLOR_ACCENT_AMBER)

            } else {

                voltagePill.setTextColor(if (isNight) Color.WHITE else COLOR_TEXT_PRIMARY_LIGHT)

            }

        }

    }



    /** GPU pill text from the layer-2 parse of the last monitor
     *  payload; amber when the dock reports nothing (same honesty
     *  rule as the voltage pill's low-voltage amber). */
    fun updateGpu(gpu: GpuStatus?) {
        if (!::gpuPill.isInitialized) return
        gpuPill.text = gpu?.pillText() ?: "GPU —"
        // The pill's surface is the dark card color in BOTH themes, so
        // its text stays white — the light theme's dark primary text
        // on that dark pill was unreadable (found on-device
        // 2026-11-21, Addendum 127). Amber still marks a dock that
        // reports nothing.
        gpuPill.setTextColor(
            if (gpu != null && !gpu.egpuReporting) COLOR_ACCENT_AMBER else Color.WHITE
        )
    }

    fun updateFreshness(lastPacketAtMs: Long) {

        if (lastPacketAtMs == 0L) {

            latestFreshnessText = "Waiting for comma data"

            freshnessDotOverride = null

        } else {

            val ageMs = (System.currentTimeMillis() - lastPacketAtMs).coerceAtLeast(0L)

            val ageSec = ageMs / 1000

            val ageText = StaleDataPolicy.ageText(ageMs)

            // Bands come from StaleDataPolicy (transport-aware) — the
            // same standard the AA stale ribbon uses.
            val isBt = NavStateManager.activeTransport ==
                NavStateManager.TransportType.BLUETOOTH

            when (StaleDataPolicy.band(ageMs, isBt)) {

                StaleDataPolicy.Band.LIVE_NOW -> { latestFreshnessText = "LIVE \u00B7 just now"; freshnessDotOverride = null }

                StaleDataPolicy.Band.LIVE -> { latestFreshnessText = "LIVE \u00B7 ${ageText} ago"; freshnessDotOverride = null }

                StaleDataPolicy.Band.STALE -> { latestFreshnessText = "STALE \u00B7 ${ageText} ago"; freshnessDotOverride = COLOR_ACCENT_AMBER }

                StaleDataPolicy.Band.OFFLINE -> { latestFreshnessText = "OFFLINE \u00B7 ${ageText} ago"; freshnessDotOverride = COLOR_ACCENT_RED }

            }

        }

        refreshUnifiedStatusPill()

    }



    fun updateStatus(commaIp: String?, commaFound: Boolean, isGalaxyCloud: Boolean = false, isScanning: Boolean = false) {

        // Layer 3 only paints: the pill's text/dot decision lives in

        // ConnectionStatus (layer 2). Every live read is gathered here

        // and passed in as facts — including the BLE bridge state,

        // which alone decides the bluetooth mode (a comma found over

        // WiFi or Cloud must not mask a down bridge as "connected").

        val mode = NavStateManager.connectionMode

        val result = ConnectionStatus.resolve(

            ConnectionStatus.Input(

                simulating = SimulationEngine.isSimulating,

                mode = mode,

                commaFound = commaFound,

                commaIp = commaIp,

                isGalaxyCloud = isGalaxyCloud,

                isScanning = isScanning,

                bleConnected = BleBridgeClient.isSharedClientConnected(),

                cloudHasPassword = GalaxyCloudSession.hasPassword(),

                cloudAuthFailed = GalaxyCloudSession.authFailed,

                transportKey = when (NavStateManager.activeTransport) {

                    NavStateManager.TransportType.BLUETOOTH -> "bluetooth"

                    NavStateManager.TransportType.IP -> "ip"

                    NavStateManager.TransportType.CLOUD -> "cloud"

                    NavStateManager.TransportType.NONE -> "none"

                },

                bleLinkUp = NavStateManager.isBleConnected

            )

        )

        latestConnectionText = result.text

        latestDotColor = when (result.dot) {

            ConnectionStatus.Dot.GREEN -> COLOR_ACCENT_GREEN

            ConnectionStatus.Dot.BLUE -> COLOR_GOOGLE_BLUE_LIGHT

            ConnectionStatus.Dot.RED -> COLOR_ACCENT_RED

            ConnectionStatus.Dot.AMBER -> COLOR_ACCENT_AMBER

            ConnectionStatus.Dot.MUTED -> COLOR_TEXT_MUTED

        }

        refreshUnifiedStatusPill()

        Log.i("HudOverlay", "Status updated: '${statusLabelView.text}' (mode=$mode, commaFound=$commaFound)")

    }



    private fun refreshUnifiedStatusPill() {

        if (!::statusLabelView.isInitialized) return

        // The connection pill shows connection state only. Update-ready
        // lives on the CAR STATE badge (deviceStateBadge) — Param's
        // correction, 2026-11-20: "You used connection pill not car
        // state pill."
        statusDotView.setColor(freshnessDotOverride ?: latestDotColor)

        // Phone counterpart of the car screen's pulsing GPS pill: while

        // the phone feeds its location to the comma, the pill says so.

        val isSharing = GpsFeedIndicator.shouldShow(PhoneGpsFeeder.isFeeding)

        val gpsSuffix = if (isSharing) " · GPS → Comma" else ""

        statusLabelView.text = (if (latestFreshnessText.isEmpty()) latestConnectionText

            else "$latestConnectionText · $latestFreshnessText") + gpsSuffix

        updateGpsSharing(isSharing)

    }



    fun updateGpsSharing(isSharing: Boolean) {

        if (!::gpsFeedBtn.isInitialized) return

        val targetVis = if (GpsFeedIndicator.shouldShow(isSharing)) View.VISIBLE else View.GONE

        if (gpsFeedBtn.visibility != targetVis) {

            gpsFeedBtn.visibility = targetVis

        }

    }



    companion object {

        private const val MS_TO_MPH = Units.MS_TO_MPH



        const val COLOR_BACKGROUND = 0xFF131314.toInt()

        const val COLOR_SURFACE_DARK = 0xFF1E1F20.toInt()

        const val COLOR_BORDER_DARK = 0xFF3C4043.toInt()

        const val COLOR_BORDER_LIGHT = 0xFFDADCE0.toInt()

        const val COLOR_TEXT_PRIMARY_LIGHT = 0xFF202124.toInt()

        const val COLOR_TEXT_MUTED_LIGHT = 0xFF5F6368.toInt()



        const val COLOR_BORDER = 0xFF3C4043.toInt()

        const val COLOR_SURFACE_LIGHT = 0xFF28292A.toInt()

        const val COLOR_TEXT_MUTED = 0xFF9AA0A6.toInt()

        const val COLOR_ACCENT_CYAN = 0xFF8AB4F8.toInt()

        const val COLOR_ACCENT_GREEN = 0xFF34A853.toInt()

        const val COLOR_ACCENT_AMBER = 0xFFFBBC04.toInt()

        const val COLOR_ACCENT_RED = 0xFFEA4335.toInt()

        const val COLOR_GOOGLE_BLUE_DARK = 0xFF8AB4F8.toInt()

        const val COLOR_GOOGLE_BLUE_LIGHT = 0xFF1A73E8.toInt()

        const val COLOR_BADGE_AMBER_BG = 0xFF2D2412.toInt()

        const val COLOR_BADGE_GREEN_BG = 0xFF0D3520.toInt()



        fun makeCardBg(

            context: Context,

            color: Int,

            cornerRadiusDp: Int,

            strokeColor: Int = Color.TRANSPARENT,

            strokeWidthDp: Int = 0

        ): GradientDrawable {

            val d = context.resources.displayMetrics.density

            return GradientDrawable().apply {

                shape = GradientDrawable.RECTANGLE

                setColor(color)

                cornerRadius = cornerRadiusDp * d

                if (strokeWidthDp > 0) setStroke((strokeWidthDp * d).roundToInt(), strokeColor)

            }

        }

    }

}



/**

 * Small status indicator circle drawn on Canvas.

 */

class StatusDotView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

        style = Paint.Style.FILL

        color = 0xFF9AA0A6.toInt()

    }



    fun setColor(c: Int) {

        if (paint.color != c) {

            paint.color = c

            invalidate()

        }

    }



    override fun onDraw(canvas: Canvas) {

        val r = min(width, height) / 2f

        canvas.drawCircle(width / 2f, height / 2f, r, paint)

    }

}



/**

 * Clean Material 3 circular Settings button with a vector gear icon drawn on Canvas.

 */

class SettingsGearView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

        style = Paint.Style.FILL

        color = 0xFF1E1F20.toInt()

    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

        style = Paint.Style.STROKE

        strokeWidth = 1.5f

        color = 0xFF3C4043.toInt()

    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

        style = Paint.Style.STROKE

        strokeWidth = 2.2f * resources.displayMetrics.density

        strokeCap = Paint.Cap.ROUND

        color = 0xFFE3E3E3.toInt()

    }



    fun applyTheme(night: Boolean) {

        bgPaint.color = if (night) 0xFF1E1F20.toInt() else Color.WHITE

        borderPaint.color = if (night) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt()

        iconPaint.color = if (night) 0xFFE3E3E3.toInt() else 0xFF3C4043.toInt()

        invalidate()

    }



    override fun onDraw(canvas: Canvas) {

        val cx = width / 2f

        val cy = height / 2f

        val r = min(cx, cy) - 1.5f

        canvas.drawCircle(cx, cy, r, bgPaint)

        canvas.drawCircle(cx, cy, r, borderPaint)



        // Draw crisp Material gear icon

        val gearR = r * 0.30f

        val toothInner = r * 0.34f

        val toothOuter = r * 0.46f

        canvas.drawCircle(cx, cy, gearR, iconPaint)

        for (i in 0 until 8) {

            val angle = Math.toRadians(i * 45.0)

            val cosA = cos(angle).toFloat()

            val sinA = sin(angle).toFloat()

            canvas.drawLine(

                cx + cosA * toothInner,

                cy + sinA * toothInner,

                cx + cosA * toothOuter,

                cy + sinA * toothOuter,

                iconPaint

            )

        }

    }

}

