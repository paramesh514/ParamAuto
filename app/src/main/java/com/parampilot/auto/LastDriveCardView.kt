package com.parampilot.auto

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Last-drive card (Feature 13), modeled on ArrivalCardView: a bottom card
 * shown once per app start when the comma is parked with no active
 * destination, summarizing the most recent drive from /api/drives/stats
 * (the comma's own numbers, via [LastDrive]). Dismiss hides it for the
 * rest of this app start.
 */
class LastDriveCardView(
    private val ctx: Context,
    private val onDismissClick: () -> Unit
) {

    private val den = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * den).roundToInt()

    private lateinit var container: LinearLayout
    private lateinit var detailText: TextView

    fun attachTo(root: FrameLayout) {
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = HudOverlay.makeCardBg(ctx, HudOverlay.COLOR_SURFACE_DARK, 28, HudOverlay.COLOR_BORDER_DARK, 1)
            elevation = 12f * den
            setPadding(dp(20), dp(14), dp(14), dp(14))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(12), 0, dp(12), dp(16))
            }
        }

        val infoCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(ctx).apply {
            text = "Last drive"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(HudOverlay.COLOR_ACCENT_CYAN)
            includeFontPadding = false
            setSingleLine(true)
        }
        infoCol.addView(title)

        detailText = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            includeFontPadding = false
            setSingleLine(true)
            setPadding(0, dp(2), 0, 0)
        }
        infoCol.addView(detailText)
        container.addView(infoCol)

        val dismissBtn = TextView(ctx).apply {
            text = "Dismiss"
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
            setOnClickListener { onDismissClick() }
        }
        container.addView(dismissBtn)

        root.addView(container)
    }

    fun applyTheme(night: Boolean) {
        if (!::container.isInitialized) return
        val bgCard = if (night) HudOverlay.COLOR_SURFACE_DARK else Color.WHITE
        val borderColor = if (night) HudOverlay.COLOR_BORDER_DARK else HudOverlay.COLOR_BORDER_LIGHT
        container.background = HudOverlay.makeCardBg(ctx, bgCard, 28, borderColor, 1)
        detailText.setTextColor(if (night) Color.WHITE else HudOverlay.COLOR_TEXT_PRIMARY_LIGHT)
    }

    fun show(drive: LastDrive) {
        if (!::container.isInitialized) return
        detailText.text = drive.formatDetail(System.currentTimeMillis())
        container.visibility = View.VISIBLE
    }

    fun hide() {
        if (!::container.isInitialized) return
        container.visibility = View.GONE
    }

    val isShowing: Boolean
        get() = ::container.isInitialized && container.visibility == View.VISIBLE
}
