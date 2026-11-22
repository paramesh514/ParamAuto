package com.parampilot.auto

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Arrival card (Feature 2), modeled on TripInfoBar: a bottom card shown when
 * the comma reports the trip as arrived. Shows the destination and the trip
 * totals the comma itself provided (via [ArrivalSummary]); "Done" dismisses
 * it, and starting a new destination hides it.
 */
class ArrivalCardView(
    private val ctx: Context,
    private val onDoneClick: () -> Unit
) {

    private val den = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * den).roundToInt()

    private lateinit var container: LinearLayout
    private lateinit var nameText: TextView
    private lateinit var detailText: TextView
    private lateinit var driveText: TextView
    private var isNight: Boolean = true

    fun attachTo(dock: LinearLayout) {
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = HudOverlay.makeCardBg(ctx, HudOverlay.COLOR_SURFACE_DARK, 28, HudOverlay.COLOR_BORDER_DARK, 1)
            elevation = 12f * den
            setPadding(dp(20), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val infoCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val arrivedLabel = TextView(ctx).apply {
            text = "Arrived"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(HudOverlay.COLOR_ACCENT_GREEN)
            includeFontPadding = false
            setSingleLine(true)
        }
        infoCol.addView(arrivedLabel)

        nameText = TextView(ctx).apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        infoCol.addView(nameText)

        detailText = TextView(ctx).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(HudOverlay.COLOR_TEXT_MUTED)
            includeFontPadding = false
            setSingleLine(true)
            setPadding(0, dp(2), 0, 0)
        }
        infoCol.addView(detailText)

        // Drive summary line (Day 3): appended by MainActivity when the
        // finished trip also produced a DriveSummary, so arrival shows one
        // card, not two stacked bottom cards. GONE until set.
        driveText = TextView(ctx).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(HudOverlay.COLOR_TEXT_MUTED)
            includeFontPadding = false
            setSingleLine(true)
            setPadding(0, dp(2), 0, 0)
            visibility = View.GONE
        }
        infoCol.addView(driveText)
        container.addView(infoCol)

        val doneBtn = TextView(ctx).apply {
            text = "Done"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(HudOverlay.COLOR_GOOGLE_BLUE_LIGHT)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), 0, 0, 0)
            }
            setOnClickListener { onDoneClick() }
        }
        container.addView(doneBtn)

        dock.addView(container)
    }

    fun applyTheme(night: Boolean) {
        isNight = night
        if (!::container.isInitialized) return
        val bgCard = if (night) HudOverlay.COLOR_SURFACE_DARK else Color.WHITE
        val borderColor = if (night) HudOverlay.COLOR_BORDER_DARK else HudOverlay.COLOR_BORDER_LIGHT
        container.background = HudOverlay.makeCardBg(ctx, bgCard, 28, borderColor, 1)
        nameText.setTextColor(if (night) Color.WHITE else HudOverlay.COLOR_TEXT_PRIMARY_LIGHT)
    }

    fun show(summary: ArrivalSummary) {
        if (!::container.isInitialized) return
        nameText.text = summary.destinationName
        detailText.text = summary.detailText()
        // A fresh arrival starts without a drive line; the observer adds
        // one only when this same trip produced a DriveSummary.
        if (::driveText.isInitialized) driveText.visibility = View.GONE
        container.visibility = View.VISIBLE
    }

    /** Appends the actually-driven totals under the comma's arrival totals. */
    fun showDriveSummary(summary: DriveSummary) {
        if (!::container.isInitialized || !::driveText.isInitialized) return
        driveText.text = "Driven: ${summary.detailText()}"
        driveText.visibility = View.VISIBLE
    }

    fun hide() {
        if (!::container.isInitialized) return
        container.visibility = View.GONE
        if (::driveText.isInitialized) driveText.visibility = View.GONE
    }

    val isShowing: Boolean
        get() = ::container.isInitialized && container.visibility == View.VISIBLE
}
