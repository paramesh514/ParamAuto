package com.parampilot.auto

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Shared Material 3 bottom-sheet factory for the standalone dialogs
 * (Addendum 132): the same sheet the Settings screens build through
 * SettingsDialogs' private createM3Dialog — drag handle, title with an
 * optional subtitle, circular close button, height-capped scrolling
 * body, and a bottom padding that lifts past the navigation-bar inset.
 * HealthDialog, GpuDialog and RecordingsDialog build on this so every
 * dialog in the app shares one look; SettingsDialogs keeps its own
 * factory (its palette and helpers are woven through 5,000+ lines).
 */
object M3Sheet {

    data class Palette(
        val isNight: Boolean,
        val sheetBg: Int,
        val cardBg: Int,
        val inputBg: Int,
        val border: Int,
        val divider: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accentBlue: Int,
        val accentBlueTonalBg: Int,
        val accentGreen: Int,
        val accentGreenTonalBg: Int,
        val accentAmber: Int,
        val accentAmberTonalBg: Int
    )

    fun palette(activity: Activity): Palette {
        val night = ThemeManager.isNightMode(activity)
        return if (night) {
            Palette(
                isNight = true,
                sheetBg = 0xFF131314.toInt(),
                cardBg = 0xFF1E1F20.toInt(),
                inputBg = 0xFF28292A.toInt(),
                border = 0xFF3C4043.toInt(),
                divider = 0xFF2D2E30.toInt(),
                textPrimary = 0xFFE3E3E3.toInt(),
                textSecondary = 0xFF9AA0A6.toInt(),
                accentBlue = 0xFFA8C7FA.toInt(),
                accentBlueTonalBg = 0xFF172B4D.toInt(),
                accentGreen = 0xFF81C995.toInt(),
                accentGreenTonalBg = 0xFF0D3B22.toInt(),
                accentAmber = 0xFFFDD663.toInt(),
                accentAmberTonalBg = 0xFF4D3800.toInt()
            )
        } else {
            Palette(
                isNight = false,
                sheetBg = 0xFFF2F4F8.toInt(),
                cardBg = 0xFFFFFFFF.toInt(),
                inputBg = 0xFFF1F3F4.toInt(),
                border = 0xFFDADCE0.toInt(),
                divider = 0xFFECEDEF.toInt(),
                textPrimary = 0xFF1F1F1F.toInt(),
                textSecondary = 0xFF5F6368.toInt(),
                accentBlue = 0xFF1A73E8.toInt(),
                accentBlueTonalBg = 0xFFE8F0FE.toInt(),
                accentGreen = 0xFF188038.toInt(),
                accentGreenTonalBg = 0xFFE6F4EA.toInt(),
                accentAmber = 0xFFB06000.toInt(),
                accentAmberTonalBg = 0xFFFEF7E0.toInt()
            )
        }
    }

    private fun dp(activity: Activity, v: Int) =
        (v * activity.resources.displayMetrics.density).roundToInt()

    private fun TextView.dpText(sizeDp: Float) =
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp)

    /**
     * Creates the sheet and returns (dialog, body, palette). Add rows
     * and buttons to the body, then call dialog.show(). Mirrors
     * SettingsDialogs.createM3Dialog's construction exactly.
     */
    fun create(
        activity: Activity,
        title: String,
        subtitle: String? = null,
        maxHeightRatio: Float = 0.86f
    ): Triple<Dialog, LinearLayout, Palette> {
        val pal = palette(activity)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val outerWrapper = FrameLayout(activity).apply {
            setPadding(dp(activity, 8), dp(activity, 20), dp(activity, 8), dp(activity, 10))
        }

        val sheetCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = HudOverlay.makeCardBg(activity, pal.sheetBg, 28, pal.border, 1)
            elevation = 16f * activity.resources.displayMetrics.density
            setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        }

        val handle = View(activity).apply {
            background = HudOverlay.makeCardBg(activity, pal.border, 3)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(activity, 2), 0, dp(activity, 10))
            }
        }
        sheetCard.addView(handle)

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 4), 0, dp(activity, 2), dp(activity, 10))
        }

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(activity).apply {
            text = title
            dpText(20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(pal.textPrimary)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(titleTv)

        if (!subtitle.isNullOrEmpty()) {
            val subTv = TextView(activity).apply {
                text = subtitle
                dpText(13f)
                setTextColor(pal.textSecondary)
                // Addendum 133: wrap instead of cutting to one line.
                setSingleLine(false)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(activity, 2), 0, 0)
            }
            titleCol.addView(subTv)
        }
        headerRow.addView(titleCol)

        val closeBtn = CloseCircleButtonView(activity).apply {
            applyTheme(pal.isNight)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(closeBtn)
        sheetCard.addView(headerRow)

        val contentBody = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val ratioCap = (activity.resources.displayMetrics.heightPixels * maxHeightRatio).toInt()
                val offered = if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                    MeasureSpec.getSize(heightMeasureSpec)
                } else {
                    -1
                }
                val cappedSpec = MeasureSpec.makeMeasureSpec(
                    sheetScrollCap(ratioCap, offered), MeasureSpec.AT_MOST
                )
                super.onMeasure(widthMeasureSpec, cappedSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(contentBody)
        }

        sheetCard.addView(scrollView)
        outerWrapper.addView(sheetCard)
        dialog.setContentView(outerWrapper)

        // Edge-to-edge: lift the wrapper's bottom padding past the
        // nav-bar inset so the sheet's last row is never clipped
        // (same rule as SettingsDialogs' sheets).
        val baseBottomPad = dp(activity, 10)
        outerWrapper.setOnApplyWindowInsetsListener { v, insets ->
            val navBottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            v.setPadding(
                v.paddingLeft, v.paddingTop, v.paddingRight,
                liftedBottomMargin(baseBottomPad, navBottom)
            )
            insets
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            setLayout(activity.resources.displayMetrics.widthPixels, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            attributes = attributes?.apply {
                dimAmount = 0.45f
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        return Triple(dialog, contentBody, pal)
    }

    /** Label-over-value row, the health/GPU dialogs' shared shape. */
    fun addRow(body: LinearLayout, activity: Activity, pal: Palette, label: String, value: String) {
        val rowLabel = TextView(activity).apply {
            text = label
            dpText(12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(pal.textSecondary)
            setPadding(0, dp(activity, 10), 0, 0)
        }
        body.addView(rowLabel)
        val rowValue = TextView(activity).apply {
            text = value
            dpText(15f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(pal.textPrimary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        body.addView(rowValue)
    }

    private fun baseButton(
        activity: Activity,
        text: String,
        bg: Int,
        border: Int?,
        fg: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            this.text = text
            dpText(14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(fg)
            gravity = Gravity.CENTER
            background = if (border != null) {
                HudOverlay.makeCardBg(activity, bg, 20, border, 1)
            } else {
                HudOverlay.makeCardBg(activity, bg, 20)
            }
            setPadding(dp(activity, 18), dp(activity, 10), dp(activity, 18), dp(activity, 10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun primaryButton(activity: Activity, pal: Palette, text: String, onClick: () -> Unit): TextView =
        baseButton(
            activity, text, pal.accentBlue, null,
            if (pal.isNight) 0xFF131314.toInt() else Color.WHITE, onClick
        )

    fun tonalButton(activity: Activity, pal: Palette, text: String, onClick: () -> Unit): TextView =
        baseButton(activity, text, pal.cardBg, pal.border, pal.textPrimary, onClick)

    fun dangerButton(activity: Activity, pal: Palette, text: String, onClick: () -> Unit): TextView =
        baseButton(activity, text, 0xFFB3261E.toInt(), null, Color.WHITE, onClick)

    /** Right-aligned row of buttons to close out a sheet body. */
    fun buttonRow(activity: Activity, vararg buttons: View): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(activity, 16), 0, 0) }
        }
        buttons.forEachIndexed { i, b ->
            if (i > 0) {
                (b.layoutParams as? LinearLayout.LayoutParams)?.setMargins(dp(activity, 8), 0, 0, 0)
            }
            row.addView(b)
        }
        return row
    }
}
