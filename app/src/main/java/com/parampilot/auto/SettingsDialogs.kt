package com.parampilot.auto

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Google Material 3 Settings & Configuration Sheets.
 *
 * Replaces legacy Android AlertDialogs with adaptive Light/Dark Material 3
 * bottom-sheet style cards, grouped sections, inline segmented theme controls,
 * custom Canvas vector icons, and clean single-line typography.
 */
class SettingsDialogs(private val activity: Activity) {

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun TextView.dpText(sizeDp: Float) = setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp)

    private data class M3Palette(
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

    private fun palette(): M3Palette {
        val night = ThemeManager.isNightMode(activity)
        return if (night) {
            M3Palette(
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
            M3Palette(
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

    /**
     * Creates a Google Material 3 bottom-anchored modal dialog card with a drag handle,
     * header title, and circular close button.
     */
    private fun createM3Dialog(
        title: String,
        subtitle: String? = null,
        maxHeightRatio: Float = 0.86f
    ): Triple<Dialog, LinearLayout, M3Palette> {
        val pal = palette()
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val outerWrapper = FrameLayout(activity).apply {
            setPadding(dp(8), dp(20), dp(8), dp(10))
        }

        val sheetCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = HudOverlay.makeCardBg(activity, pal.sheetBg, 28, pal.border, 1)
            elevation = 16f * density
            setPadding(dp(16), dp(10), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        }

        // Top drag handle pill
        val handle = View(activity).apply {
            background = HudOverlay.makeCardBg(activity, pal.border, 3)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(2), 0, dp(10))
            }
        }
        sheetCard.addView(handle)

        // Header Row: Title (+ optional subtitle) on left, Close circle button on right
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(2), dp(10))
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
                // Addendum 133: wrap instead of cutting to one line —
                // sheet subtitles carry real explanations.
                setSingleLine(false)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            titleCol.addView(subTv)
        }
        headerRow.addView(titleCol)

        val closeBtn = CloseCircleButtonView(activity).apply {
            applyTheme(pal.isNight)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
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
                // Honour a tighter parent offer (space left after handle,
                // header and paddings): overriding it with the ratio cap
                // alone made tall sheets overflow the dialog window, so
                // their last rows sat in the clipped zone and could never
                // be scrolled into view (Param, 2026-11-03).
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

        // Edge-to-edge (targetSdk 35+): this bottom-anchored sheet would
        // otherwise rest under the system navigation bar, and its last row
        // could never be scrolled into view (Param, 2026-11-03: "Can't see
        // last item in settings"). Lift the wrapper's bottom padding past
        // the nav-bar inset; the authored 10dp stays the floor. Same rule
        // as the HUD's bottom row (liftedBottomMargin in InsetsMath.kt).
        val baseBottomPad = dp(10)
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

    private fun createSectionHeader(title: String, pal: M3Palette): TextView {
        return TextView(activity).apply {
            text = title
            dpText(11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(pal.accentBlue)
            letterSpacing = 0.05f
            setPadding(dp(8), dp(11), dp(8), dp(5))
        }
    }

    /**
     * Settings-menu category header (Addendum 121): the category's
     * icon in its category tint beside the name. The three menu
     * categories — Connectivity (blue), Maps & Navigation (green),
     * Comma Settings (amber) — each carry one colour, used on the
     * header and on every row's icon tile in the group, so a
     * setting's family is visible at a glance. Colour is always a
     * second cue beside the label, never the only signal.
     */
    private fun createCategoryHeader(
        title: String,
        iconKind: SettingsIconView.Kind,
        iconTint: Int,
        iconTileBg: Int,
        pal: M3Palette
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(13), dp(8), dp(6))
            addView(SettingsIconView(activity, iconKind, iconTint, iconTileBg).apply {
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                    setMargins(0, 0, dp(9), 0)
                }
            })
            addView(TextView(activity).apply {
                text = title
                dpText(13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(pal.textPrimary)
                letterSpacing = 0.04f
            })
        }
    }

    private fun createGroupCard(pal: M3Palette): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = HudOverlay.makeCardBg(activity, pal.cardBg, 18, pal.border, 1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(2))
            }
        }
    }

    private fun createDivider(pal: M3Palette): View {
        return View(activity).apply {
            setBackgroundColor(pal.divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(dp(54), 0, dp(14), 0)
            }
        }
    }

    private fun createSettingsRow(
        iconKind: SettingsIconView.Kind,
        title: String,
        subtitle: String,
        badgeText: String? = null,
        badgeActive: Boolean = false,
        iconTint: Int? = null,
        iconTileBg: Int? = null,
        trailingActionText: String? = null,
        onTrailingAction: (() -> Unit)? = null,
        pal: M3Palette,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            val iconView = SettingsIconView(
                activity, iconKind,
                iconTint ?: pal.accentBlue, iconTileBg ?: pal.accentBlueTonalBg
            ).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    setMargins(0, 0, dp(12), 0)
                }
            }
            addView(iconView)

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(activity).apply {
                text = title
                dpText(15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(pal.textPrimary)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(titleTv)

            val subTv = TextView(activity).apply {
                text = subtitle
                dpText(12.5f)
                setTextColor(pal.textSecondary)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, 0)
            }
            textCol.addView(subTv)
            addView(textCol)

            if (!badgeText.isNullOrEmpty()) {
                val pillBg = if (badgeActive) pal.accentGreenTonalBg else pal.inputBg
                val pillFg = if (badgeActive) pal.accentGreen else pal.textSecondary
                val badgeTv = TextView(activity).apply {
                    text = badgeText
                    dpText(11.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(pillFg)
                    background = HudOverlay.makeCardBg(activity, pillBg, 11)
                    setPadding(dp(9), dp(3), dp(9), dp(3))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                        setMargins(dp(6), 0, dp(4), 0)
                    }
                }
                addView(badgeTv)
            }

            // Addendum 137: an optional trailing action pill (e.g. a
            // model's Delete button). It carries its own click
            // listener, so tapping it never fires the row's action;
            // when present it replaces the chevron.
            if (!trailingActionText.isNullOrEmpty() && onTrailingAction != null) {
                val actionTv = TextView(activity).apply {
                    text = trailingActionText
                    dpText(12f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(if (pal.isNight) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt())
                    background = HudOverlay.makeCardBg(activity, pal.inputBg, 11)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onTrailingAction() }
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                        setMargins(dp(6), 0, dp(2), 0)
                    }
                }
                addView(actionTv)
            } else {
                val chevron = TextView(activity).apply {
                    text = "›"
                    dpText(19f)
                    setTextColor(pal.textSecondary)
                    setPadding(dp(4), 0, dp(2), 0)
                }
                addView(chevron)
            }
        }
    }

    /** The subtitle TextView inside a [createSettingsRow] row (title column, second child). */
    private fun rowSubtitle(row: LinearLayout): TextView? =
        (row.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView

    /**
     * Menu row with a trailing M3-styled switch (PHONE DATA section).
     * The switch itself is non-clickable: the whole row is the tap target and
     * flips the switch, so the checked-change listener fires exactly once per
     * tap (no double-fire from switch + row both handling the touch).
     */
    private fun createSwitchRow(
        iconKind: SettingsIconView.Kind,
        title: String,
        subtitle: String,
        initialChecked: Boolean,
        pal: M3Palette,
        subtitleMaxLines: Int = 1,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val toggle = Switch(activity).apply {
            isChecked = initialChecked
            isClickable = false
            isFocusable = false
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(pal.accentBlue, pal.divider)
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, pal.textSecondary)
            )
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggle.isChecked = !toggle.isChecked }

            val iconView = SettingsIconView(activity, iconKind, pal.accentBlue, pal.accentBlueTonalBg).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    setMargins(0, 0, dp(12), 0)
                }
            }
            addView(iconView)

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleTv = TextView(activity).apply {
                text = title
                dpText(15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(pal.textPrimary)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(titleTv)
            val subTv = TextView(activity).apply {
                text = subtitle
                dpText(12.5f)
                setTextColor(pal.textSecondary)
                if (subtitleMaxLines > 1) {
                    setSingleLine(false)
                    maxLines = subtitleMaxLines
                } else {
                    setSingleLine(true)
                }
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, 0)
            }
            textCol.addView(subTv)
            addView(textCol)

            addView(toggle)
        }
    }

    // --- Lazy permissions (Param, 2026-11-02) ------------------------------
    // A system permission is requested ONLY when the user turns on (or taps
    // into) the feature that needs it, and always after an in-app
    // explanation of why. With every Phone Data toggle off (the default)
    // no permission is requested at all.

    private fun hasLocationPermission(): Boolean =
        activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            activity.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The switch inside a [createSwitchRow] row (icon, text column, switch). */
    private fun rowSwitch(row: LinearLayout?): Switch? = row?.getChildAt(2) as? Switch

    /**
     * Gate for a Phone Data toggle that needs Location: [apply] runs at
     * once when the permission is already granted. Otherwise the switch is
     * put back off, an explanation of why is shown, and the setting applies
     * only if the user grants the permission in the system prompt that
     * follows the explanation.
     */
    private fun gateLocationToggle(row: LinearLayout?, featureTitle: String, why: String, apply: () -> Unit) {
        if (hasLocationPermission()) {
            apply()
            return
        }
        rowSwitch(row)?.isChecked = false
        showSheetConfirm(
            title = "Location permission needed",
            message = "$featureTitle needs this phone's location.\n\nWhy: $why\n\nParamAuto asks for a permission only when you turn on a feature that needs it. With Phone Data off, no permission is required and the comma is the only data source.",
            negativeLabel = "Not now",
            positiveLabel = "Continue",
            onNegative = {
                Toast.makeText(activity, "$featureTitle stayed off - no permission requested", Toast.LENGTH_SHORT).show()
            },
            onPositive = {
                PermissionFlow.onLocationGranted = {
                    // Re-flip the switch; its listener now finds the
                    // permission granted and applies the setting.
                    rowSwitch(row)?.isChecked = true
                }
                PermissionFlow.onLocationDenied = {
                    Toast.makeText(activity, "Location not granted - $featureTitle stayed off", Toast.LENGTH_LONG).show()
                }
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    PermissionFlow.REQUEST_LOCATION
                )
            }
        )
    }

    /**
     * Same pattern for Bluetooth: explain why first, then request;
     * [onGranted] runs immediately when the permission is already held,
     * otherwise only after the user grants it in the system prompt.
     */
    private fun ensureBluetoothPermission(why: String, onGranted: () -> Unit) {
        val missing = BleBridgeClient.missingPermissions(activity)
        if (missing.isEmpty()) {
            onGranted()
            return
        }
        showSheetConfirm(
            title = "Bluetooth permission needed",
            message = "Why: $why\n\nParamAuto asks for a permission only when you use a feature that needs it.",
            negativeLabel = "Not now",
            positiveLabel = "Continue",
            onPositive = {
                PermissionFlow.onBluetoothGranted = onGranted
                PermissionFlow.onBluetoothDenied = {
                    Toast.makeText(activity, "Bluetooth not granted - Bluetooth stays unavailable", Toast.LENGTH_LONG).show()
                }
                ActivityCompat.requestPermissions(activity, missing.toTypedArray(), PermissionFlow.REQUEST_BLUETOOTH)
            }
        )
    }

    private fun createPrimaryButton(label: String, pal: M3Palette, onClick: () -> Unit): TextView {
        val btnBg = if (pal.isNight) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
        val btnFg = if (pal.isNight) 0xFF062E6F.toInt() else Color.WHITE
        return TextView(activity).apply {
            text = label
            dpText(14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(btnFg)
            gravity = Gravity.CENTER
            background = HudOverlay.makeCardBg(activity, btnBg, 22)
            setPadding(dp(20), dp(11), dp(20), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun createTonalButton(label: String, pal: M3Palette, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            dpText(14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(pal.textPrimary)
            gravity = Gravity.CENTER
            background = HudOverlay.makeCardBg(activity, pal.inputBg, 22, pal.border, 1)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun createDangerButton(label: String, pal: M3Palette, onClick: () -> Unit): TextView {
        val btnBg = if (pal.isNight) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
        val btnFg = if (pal.isNight) 0xFF601410.toInt() else Color.WHITE
        return TextView(activity).apply {
            text = label
            dpText(14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(btnFg)
            gravity = Gravity.CENTER
            background = HudOverlay.makeCardBg(activity, btnBg, 22)
            setPadding(dp(20), dp(11), dp(20), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /**
     * Addendum 133: an M3-sheet confirmation, replacing the legacy
     * AlertDialog confirms. Follows the Material 3 dialog spec
     * (m3.material.io/components/dialogs/specs): supporting text is
     * body-medium (14sp) in the secondary color, actions are
     * end-aligned with 8dp between them and 24dp above them.
     * Either button dismisses the sheet first, then runs its action —
     * the same order the AlertDialog buttons had.
     */
    private fun showSheetConfirm(
        title: String,
        message: String?,
        positiveLabel: String,
        negativeLabel: String = "Cancel",
        danger: Boolean = false,
        content: View? = null,
        onNegative: (() -> Unit)? = null,
        onPositive: () -> Unit
    ) {
        val (dialog, body, pal) = createM3Dialog(title)
        if (!message.isNullOrEmpty()) {
            body.addView(TextView(activity).apply {
                text = message
                dpText(14f)
                setTextColor(pal.textSecondary)
                setPadding(dp(4), 0, dp(4), 0)
            })
        }
        if (content != null) body.addView(content)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(24), 0, 0) }
        }
        row.addView(createTonalButton(negativeLabel, pal) {
            dialog.dismiss()
            onNegative?.invoke()
        })
        val posBtn = if (danger) {
            createDangerButton(positiveLabel, pal) {
                dialog.dismiss()
                onPositive()
            }
        } else {
            createPrimaryButton(positiveLabel, pal) {
                dialog.dismiss()
                onPositive()
            }
        }
        posBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(8), 0, 0, 0) }
        row.addView(posBtn)
        body.addView(row)
        dialog.show()
    }

    /**
     * Addendum 133: an M3-sheet single-choice picker, replacing the
     * legacy setSingleChoiceItems dialogs. The current option is
     * marked with a check in the accent color; tapping a different
     * option picks it, tapping the current one just closes.
     */
    private fun showSheetListPicker(
        title: String,
        options: List<String>,
        current: String,
        label: (String) -> String = { it },
        onPick: (String) -> Unit
    ) {
        val (dialog, body, pal) = createM3Dialog(title)
        for (option in options) {
            val isCurrent = option == current
            val row = TextView(activity).apply {
                text = if (isCurrent) "✓  ${label(option)}" else "     ${label(option)}"
                dpText(15f)
                typeface = Typeface.create(
                    if (isCurrent) "sans-serif-medium" else "sans-serif",
                    Typeface.NORMAL
                )
                setTextColor(if (isCurrent) pal.accentBlue else pal.textPrimary)
                setPadding(dp(4), dp(13), dp(4), dp(13))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    if (!isCurrent) onPick(option)
                }
            }
            body.addView(row)
        }
        dialog.show()
    }

    private fun createInputBox(initialText: String, hintText: String, pal: M3Palette, isPassword: Boolean = false): EditText {
        return EditText(activity).apply {
            setText(initialText)
            if (initialText.isNotEmpty()) setSelection(initialText.length)
            hint = hintText
            setHintTextColor(pal.textSecondary)
            setTextColor(pal.textPrimary)
            dpText(14f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = HudOverlay.makeCardBg(activity, pal.inputBg, 14, pal.border, 1)
            if (isPassword) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
            } else {
                setSingleLine(true)
            }
        }
    }

    fun showSettingsMenu(
        client: CommaClient?,
        onCenterVehicle: () -> Unit,
        onOpenExternalMap: () -> Unit,
        onStatusRefresh: () -> Unit,
        onPowerRefresh: (PowerState?) -> Unit,
        onThemeChanged: () -> Unit,
        onToggleSimulation: () -> Unit
    ) {
        val (dialog, body, pal) = createM3Dialog("Settings")

        // 1. Sleek single-row inline Material 3 Segmented Theme Bar (Appearance: Light | Dark | Auto)
        val themeCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = HudOverlay.makeCardBg(activity, pal.cardBg, 18, pal.border, 1)
            setPadding(dp(14), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(2))
            }
        }
        val themeHeader = TextView(activity).apply {
            text = "Appearance"
            dpText(14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(pal.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        themeCard.addView(themeHeader)

        val segRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = HudOverlay.makeCardBg(activity, pal.inputBg, 14, pal.border, 1)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(198), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val currentTheme = ThemeManager.getTheme(activity)
        val themeOptions = listOf(
            ThemeManager.AppTheme.LIGHT to "Light",
            ThemeManager.AppTheme.DARK to "Dark",
            ThemeManager.AppTheme.SYSTEM to "Auto"
        )
        // Theme chips carry a small preview swatch (Addendum 121):
        // appearance is the one visual setting, so it shows the
        // result — light, dark, or half-and-half for Auto — instead
        // of only naming it.
        fun themeSwatch(theme: ThemeManager.AppTheme): View {
            return object : View(activity) {
                override fun onDraw(canvas: Canvas) {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    val radius = 4f * dp(1)
                    val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL; color = 0xFFF8F9FA.toInt()
                    }
                    val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL; color = 0xFF202124.toInt()
                    }
                    val lineLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL; color = 0xFFBDC1C6.toInt()
                    }
                    val lineDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL; color = 0xFF5F6368.toInt()
                    }
                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 1f * dp(1)
                        color = 0xFF9AA0A6.toInt()
                    }
                    val bounds = RectF(1f, 1f, w - 1f, h - 1f)
                    when (theme) {
                        ThemeManager.AppTheme.LIGHT ->
                            canvas.drawRoundRect(bounds, radius, radius, light)
                        ThemeManager.AppTheme.DARK ->
                            canvas.drawRoundRect(bounds, radius, radius, dark)
                        ThemeManager.AppTheme.SYSTEM -> {
                            canvas.drawRoundRect(bounds, radius, radius, light)
                            canvas.save()
                            canvas.clipRect(w / 2f, 0f, w, h)
                            canvas.drawRoundRect(bounds, radius, radius, dark)
                            canvas.restore()
                        }
                    }
                    // Two "text" lines in the swatch's own contrast.
                    val linePaint =
                        if (theme == ThemeManager.AppTheme.DARK) lineDark else lineLight
                    val lineH = 2f * dp(1)
                    canvas.drawRoundRect(
                        RectF(w * 0.16f, h * 0.3f, w * 0.68f, h * 0.3f + lineH),
                        lineH / 2, lineH / 2, linePaint
                    )
                    canvas.drawRoundRect(
                        RectF(w * 0.16f, h * 0.58f, w * 0.48f, h * 0.58f + lineH),
                        lineH / 2, lineH / 2, linePaint
                    )
                    canvas.drawRoundRect(bounds, radius, radius, borderPaint)
                }
            }
        }

        for ((themeOpt, label) in themeOptions) {
            val isSel = currentTheme == themeOpt
            val chipBg = if (isSel) pal.cardBg else Color.TRANSPARENT
            val chipFg = if (isSel) pal.accentBlue else pal.textSecondary
            val chip = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = if (isSel) HudOverlay.makeCardBg(activity, chipBg, 11, pal.border, 1) else null
                setPadding(0, dp(6), 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (currentTheme != themeOpt) {
                        ThemeManager.setTheme(activity, themeOpt)
                        onThemeChanged()
                        dialog.dismiss()
                        showSettingsMenu(
                            client, onCenterVehicle, onOpenExternalMap, onStatusRefresh,
                            onPowerRefresh, onThemeChanged, onToggleSimulation
                        )
                    }
                }
                addView(themeSwatch(themeOpt).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(18)).apply {
                        setMargins(0, 0, 0, dp(4))
                    }
                })
                addView(TextView(activity).apply {
                    text = label
                    dpText(13f)
                    typeface = Typeface.create("sans-serif-medium", if (isSel) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(chipFg)
                    gravity = Gravity.CENTER
                })
            }
            segRow.addView(chip)
        }
        themeCard.addView(segRow)
        body.addView(themeCard)

        // 2. CONNECTION & VEHICLE LINK
        body.addView(
            createCategoryHeader(
                "CONNECTIVITY", SettingsIconView.Kind.HUB,
                pal.accentBlue, pal.accentBlueTonalBg, pal
            )
        )
        val connCard = createGroupCard(pal)

        val modeText = when (client?.getConnectionMode()) {
            CommaClient.MODE_BLUETOOTH -> "Bluetooth"
            CommaClient.MODE_CLOUD -> "Cloud"
            CommaClient.MODE_LOCAL -> client.commaIp ?: "Local IP"
            else -> "Auto"
        }
        val isConnected = NavStateManager.isBleConnected || (client?.commaFound == true)
        val voltStr = NavStateManager.latestPower?.voltageV
            ?.takeIf { it > 1.0 }?.let { "${"%.1f".format(it)}V" }
        // The comma's IP rides the row subtitle too (Param,
        // 2026-11-27): the address in use, or the learned local
        // address while Bluetooth or Cloud serves (Addendum 157).
        val rowIp = ConnectionIpDisplay.rowIp(
            transport = NavStateManager.activeTransport,
            activeIp = client?.commaIp,
            learnedIp = client?.getLearnedLocalIp()
                ?: client?.getLastScannedIp()
                ?: client?.getUserLocalIp()
                ?: client?.getSavedIp()
        )

        val connMode = client?.getConnectionMode() ?: CommaClient.MODE_AUTO
        val hasAuthError = !GalaxyCloudSession.hasPassword() || GalaxyCloudSession.authFailed
        val badgeLabel = when {
            connMode == CommaClient.MODE_CLOUD && hasAuthError -> "Config Error"
            isConnected -> "Online"
            else -> "Offline"
        }

        connCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.HUB,
                title = "Connection mode",
                subtitle = listOfNotNull(
                    modeText,
                    rowIp?.takeIf { it != modeText },
                    voltStr
                ).joinToString(" · "),
                badgeText = badgeLabel,
                badgeActive = isConnected,
                pal = pal
            ) {
                dialog.dismiss()
                showConnectionModeDialog(client, onStatusRefresh)
            }
        )
        connCard.addView(createDivider(pal))

        // Wi-Fi manager (Param, 2026-11-19): the comma's saved Wi-Fi
        // networks, cellular data and link preference, via the Galaxy
        // Networks endpoint the Dom build added. The Galaxy hotspot
        // toggle moved into this page too (Param, 2026-11-20).
        val connectedNet = NavStateManager.commaNetworks?.networks
            ?.firstOrNull { it.connected }?.name
        connCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.WIFI_LINK,
                title = "Wi-Fi networks",
                subtitle = "Comma's Wi-Fi, cellular, hotspot & saved networks",
                badgeText = connectedNet,
                badgeActive = connectedNet != null,
                pal = pal
            ) {
                dialog.dismiss()
                showNetworksDialog(client, onStatusRefresh)
            }
        )
        body.addView(connCard)

        // 3. MAPS & NAVIGATION — comma and phone together (Param,
        // 2026-11-20: Settings regrouped into Connectivity /
        // Maps & Navigation / Comma Settings).
        body.addView(
            createCategoryHeader(
                "MAPS & NAVIGATION", SettingsIconView.Kind.MAP,
                pal.accentGreen, pal.accentGreenTonalBg, pal
            )
        )
        val navCard = createGroupCard(pal)

        // Map keys & usage (Param, 2026-11-20): the Mapbox and
        // Google keys — comma and phone — with usage, or how-to-get
        // instructions when no key is saved.
        val mapboxKeySet = !client?.mapboxToken.isNullOrEmpty()
        val googleKeySet = !client?.googleApiKey.isNullOrEmpty()
        navCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.KEY,
                title = "Map keys & usage",
                subtitle = "Mapbox & Google keys — usage or setup help",
                badgeText = when {
                    mapboxKeySet && googleKeySet -> "Both set"
                    mapboxKeySet -> "Mapbox set"
                    googleKeySet -> "Google set"
                    else -> null
                },
                badgeActive = mapboxKeySet || googleKeySet,
                iconTint = pal.accentGreen,
                iconTileBg = pal.accentGreenTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                showMapKeysDialog(client, onStatusRefresh)
            }
        )
        navCard.addView(createDivider(pal))

        navCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.ROUTES,
                title = "Providers",
                subtitle = "Maps & directions sources — comma and phone",
                badgeText = NavStateManager.commaNavProviders?.let {
                    GoogleNav.providerLabel(it.mapProvider)
                },
                badgeActive = NavStateManager.commaNavProviders != null,
                iconTint = pal.accentGreen,
                iconTileBg = pal.accentGreenTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                showProvidersDialog(client)
            }
        )
        navCard.addView(createDivider(pal))

        val activeCount = listOf(
            NavStateManager.usePhoneSpeed,
            NavStateManager.usePhoneLocation,
            NavStateManager.usePhoneLocationToComma,
            NavStateManager.usePhoneMapboxKey,
            NavStateManager.usePhoneSpeedLimit,
            NavStateManager.usePhoneRoute,
            NavStateManager.usePhoneSearch,
            NavStateManager.usePhoneTraffic,
            NavStateManager.usePhoneVectorMap
        ).count { it }
        val isSimRunning = SimulationEngine.isSimulating
        val badge = when {
            isSimRunning -> "Sim running"
            activeCount > 0 -> "$activeCount active"
            else -> null
        }
        navCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.PHONE,
                title = "Phone data",
                subtitle = "Phone-sourced fallbacks for maps & navigation",
                badgeText = badge,
                badgeActive = isSimRunning || activeCount > 0,
                iconTint = pal.accentGreen,
                iconTileBg = pal.accentGreenTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                showPhoneDataDialog(client, onThemeChanged, onToggleSimulation)
            }
        )
        navCard.addView(createDivider(pal))

        navCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.SPEED,
                title = "Speed limit assist",
                subtitle = "Offsets, sources & confirmation — on the comma",
                iconTint = pal.accentGreen,
                iconTileBg = pal.accentGreenTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                showSlcOffsetDialog(client)
            }
        )
        navCard.addView(createDivider(pal))

        navCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.DOWNLOAD,
                title = "Maps & offline data",
                subtitle = "Offline map pack — keys are in Map keys & usage",
                iconTint = pal.accentGreen,
                iconTileBg = pal.accentGreenTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                showMapboxTokenDialog(client, onStatusRefresh)
            }
        )
        navCard.addView(createDivider(pal))

        val tileCount = CommaTileDiskCache.getCachedTileCount()
        val tileMb = CommaTileDiskCache.getCacheSizeBytes() / (1024 * 1024)
        navCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.CHART,
                title = "Data & cache usage",
                subtitle = "$tileCount cached tiles ($tileMb MB) · API counters",
                iconTint = pal.accentGreen,
                iconTileBg = pal.accentGreenTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                showUsageCountersDialog()
            }
        )
        navCard.addView(createDivider(pal))

        navCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.PLAY,
                title = "Route simulation",
                subtitle = if (isSimRunning) "Tap to stop active simulation" else "Simulate turn-by-turn drive",
                badgeText = if (isSimRunning) "Running" else null,
                badgeActive = isSimRunning,
                iconTint = pal.accentGreen,
                iconTileBg = pal.accentGreenTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                onToggleSimulation()
            }
        )
        body.addView(navCard)

        // 4. COMMA SETTINGS
        body.addView(
            createCategoryHeader(
                "COMMA SETTINGS", SettingsIconView.Kind.TUNE,
                pal.accentAmber, pal.accentAmberTonalBg, pal
            )
        )
        val driveCard = createGroupCard(pal)

        // Comma settings (Param, 2026-11-20): the Galaxy settings
        // pages — lane, torque, display and the rest — as native app
        // pages, edited through Galaxy's own preview/confirm flow.
        driveCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.TUNE,
                title = "Comma settings",
                subtitle = "Lane, torque, display & more — from Galaxy",
                iconTint = pal.accentAmber,
                iconTileBg = pal.accentAmberTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                showCommaSettingsListDialog(client, onStatusRefresh)
            }
        )
        driveCard.addView(createDivider(pal))

        driveCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.WHEEL,
                title = "Driving personality",
                subtitle = "Aggressive, Standard, or Relaxed follow profile",
                iconTint = pal.accentAmber,
                iconTileBg = pal.accentAmberTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                showPersonalityProfileDialog(client)
            }
        )
        driveCard.addView(createDivider(pal))

        driveCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.SPARK,
                title = "Driving models & audio",
                subtitle = NavStateManager.activeModelName?.let { "Active model: $it" }
                    ?: "Switch active AI model or sound theme",
                iconTint = pal.accentAmber,
                iconTileBg = pal.accentAmberTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                showModelLaboratoryDialog(client)
            }
        )
        driveCard.addView(createDivider(pal))

        driveCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.UPDATE,
                title = "Comma Software",
                subtitle = "Version, updates & reboot",
                badgeText = if (NavStateManager.softwareUpdateReady) "Update ready" else null,
                badgeActive = NavStateManager.softwareUpdateReady,
                iconTint = pal.accentAmber,
                iconTileBg = pal.accentAmberTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                showSoftwareUpdateDialog(client)
            }
        )
        driveCard.addView(createDivider(pal))

        driveCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.PULSE,
                title = "Comma health & caches",
                subtitle = "Transport, tile cache, system monitor",
                iconTint = pal.accentAmber,
                iconTileBg = pal.accentAmberTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                HealthDialog.show(activity, client)
            }
        )
        driveCard.addView(createDivider(pal))

        driveCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.VIDEO,
                title = "Recordings on comma",
                subtitle = "Drives stored on the comma · local connection only",
                iconTint = pal.accentAmber,
                iconTileBg = pal.accentAmberTonalBg,
                pal = pal
            ) {
                dialog.dismiss()
                RecordingsDialog.show(activity, client)
            }
        )
        driveCard.addView(createDivider(pal))

        // Comma cameras (Day 6, 2026-11-05): parked snapshots from the
        // comma's own cameras, served by /api/cameras/snapshot. The
        // dialog does the capture; failures show the comma's own words.
        driveCard.addView(createSettingsRow(
            iconKind = SettingsIconView.Kind.CAMERA,
            title = "Comma cameras",
            subtitle = "Road and cabin snapshots — parked only",
            iconTint = pal.accentAmber,
            iconTileBg = pal.accentAmberTonalBg,
            pal = pal
        ) { showCamerasDialog(client) })
        body.addView(driveCard)


        dialog.show()
    }

    /** Opens the Galaxy web UI (companion-style WebView transport). */
    private fun openGalaxyWeb() {
        GalaxyWebActivity.start(activity, BleBridgeClient.hasPairingKey(activity))
    }

    /** Galaxy at the comma's LAN address, no Bluetooth bridge (Addendum 119). */
    private fun openGalaxyLocal(client: CommaClient?) {
        val ip = client?.getUserLocalIp()
            ?: client?.getLastScannedIp()
            ?: client?.getSavedIp()?.takeIf { !it.contains("galaxy.firestar.link") }
            ?: NavStateManager.commaIp?.takeIf { !it.startsWith("http") }
        if (ip.isNullOrBlank()) {
            Toast.makeText(
                activity,
                "No local address known yet — join the comma's Wi-Fi or hotspot once",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        GalaxyWebActivity.start(activity, false, "http://$ip:8082/")
    }

    /** Galaxy through the cloud link, no Bluetooth bridge (Addendum 119). */
    private fun openGalaxyCloud(client: CommaClient?) {
        val url = client?.getGalaxyCloudBase() ?: CommaClient.DEFAULT_GALAXY_URL
        GalaxyWebActivity.start(activity, false, if (url.endsWith("/")) url else "$url/")
    }


    /**
     * Comma cameras dialog (Day 6, 2026-11-05): one chip per camera;
     * tapping captures a fresh parked snapshot from the comma and
     * shows it. The comma can take ~12 s (24 s cabin) and refuses
     * unless parked — its refusal text is what the user reads.
     */
    fun showCamerasDialog(client: CommaClient?) {
        val (dialog, body, pal) = createM3Dialog(
            "Comma cameras",
            "A fresh snapshot from the comma — it only serves one while parked"
        )
        val endpoint = client?.commaIp ?: NavStateManager.commaIp
        val camClient = SgGalaxyClient(SgGalaxyClient.baseUrlFor(endpoint ?: "127.0.0.1"))

        val statusTv = TextView(activity).apply {
            text = "Pick a camera. Capturing can take up to ~15 seconds."
            dpText(12.5f)
            setTextColor(pal.textSecondary)
            setPadding(0, dp(8), 0, 0)
        }
        val imageView = ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumHeight = dp(160)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        var capturing = false
        val chipRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(8))
        }
        for (cam in CommaCameras.ALL) {
            val chip = Button(activity).apply {
                text = cam.label
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(dp(3), 0, dp(3), 0) }
                setOnClickListener {
                    if (capturing) return@setOnClickListener
                    capturing = true
                    statusTv.text = "Capturing ${cam.label}…"
                    Thread {
                        val (code, jpeg, serverErr) = camClient.fetchCameraSnapshot(cam.id)
                        activity.runOnUiThread {
                            capturing = false
                            val bmp = if (code in 200..299 && CommaCameras.isJpeg(jpeg)) {
                                jpeg?.let {
                                    android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                                }
                            } else {
                                null
                            }
                            if (bmp != null) {
                                imageView.setImageBitmap(bmp)
                                statusTv.text = "${cam.label} · just now"
                            } else {
                                val msg = CommaCameras.errorMessage(code, serverErr)
                                statusTv.text = msg
                                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
            }
            chipRow.addView(chip)
        }
        body.addView(chipRow)
        body.addView(imageView)
        body.addView(statusTv)
        dialog.show()
    }

    fun showConnectionModeDialog(client: CommaClient?, onUpdated: () -> Unit) {
        activity.runOnUiThread {
            val (dialog, body, pal) = createM3Dialog("Connection mode", "Mode, Bluetooth bridge & Galaxy — one window")
            val currentMode = client?.getConnectionMode() ?: CommaClient.MODE_AUTO
            // Transport readiness (Param, 2026-11-04): Bluetooth needs
            // its pairing key fetched, Cloud needs the Galaxy link
            // fetched + the password saved. Until then only Auto and
            // Local can be selected; the unready rows show why.
            var hasBleKey = BleBridgeClient.hasPairingKey(activity)
            var hasGalaxyUrl = client?.hasDedicatedGalaxyUrl() ?: false
            var hasGalaxyPassword = GalaxyCloudSession.hasPassword()
            var selectedMode = currentMode
            if (!TransportReadiness.modeSelectable(selectedMode, hasBleKey, hasGalaxyUrl, hasGalaxyPassword)) {
                // The stored mode is not set up yet — preselect Auto in
                // this dialog; the stored mode's row shows what is missing.
                selectedMode = CommaClient.MODE_AUTO
            }
            // Set after the rows exist; the Galaxy Scan button uses it
            // to unlock rows in place once a scan configures the link.
            lateinit var refreshModeRows: () -> Unit
            var galaxySetupTouched = false
            val currentIp = client?.getUserLocalIp()
                ?: client?.getLastScannedIp()
                ?: client?.getSavedIp()?.takeIf { !it.contains("galaxy.firestar.link") }
                ?: "192.168.68.9"

            val modes = listOf(
                Triple(CommaClient.MODE_AUTO, "Automatic", "Priority: " + TransportReadiness.autoOrderText(hasBleKey, hasGalaxyUrl, hasGalaxyPassword)),
                Triple(CommaClient.MODE_BLUETOOTH, "Bluetooth (BLE)", "Direct low-energy bridge"),
                Triple(CommaClient.MODE_LOCAL, "Local Wi-Fi / IP", "Auto-scan LAN (:8082) or use custom IP"),
                Triple(
                    CommaClient.MODE_CLOUD,
                    "Galaxy Cloud",
                    if (!GalaxyCloudSession.hasPassword()) "Remote access (Not paired - connect locally once)"
                    else if (GalaxyCloudSession.authFailed) "Remote access (Pairing expired - reconnect locally to re-pair)"
                    else "Remote access via galaxy.firestar.link"
                )
            )

            val ipSection = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(4))
                visibility = if (selectedMode == CommaClient.MODE_LOCAL) View.VISIBLE else View.GONE
            }
            val ipLabel = TextView(activity).apply {
                text = "Comma LAN IP address"
                dpText(12.5f)
                setTextColor(pal.textSecondary)
                setPadding(dp(4), 0, 0, dp(6))
            }
            val ipInput = createInputBox(currentIp, "e.g. 192.168.68.9", pal)
            ipSection.addView(ipLabel)
            ipSection.addView(ipInput)

            val cloudSection = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(4))
                // Shown for Cloud mode, and whenever Cloud is not
                // configured yet — this section is where it gets set up.
                visibility = if (selectedMode == CommaClient.MODE_CLOUD ||
                    !TransportReadiness.cloudReady(hasGalaxyUrl, hasGalaxyPassword)
                ) View.VISIBLE else View.GONE
            }
            val cloudLabel = TextView(activity).apply {
                text = "Galaxy Cloud endpoint URL"
                dpText(12.5f)
                setTextColor(pal.textSecondary)
                setPadding(dp(4), 0, 0, dp(6))
            }
            val currentCloudUrl = client?.getGalaxyCloudBase() ?: CommaClient.DEFAULT_GALAXY_URL
            val cloudInput = createInputBox(currentCloudUrl, "https://galaxy.firestar.link/<slug>", pal)
            cloudSection.addView(cloudLabel)
            cloudSection.addView(cloudInput)

            // "User can use scan to configure Galaxy" (Param, 2026-11-04):
            // the scan finds the comma on this Wi-Fi and fetches its
            // Galaxy link from it — no typing the link by hand.
            val galaxyScanStatus = TextView(activity).apply {
                text = if (TransportReadiness.cloudReady(hasGalaxyUrl, hasGalaxyPassword)) {
                    "Galaxy link and password are configured — Cloud is ready."
                } else {
                    "Not configured yet. Scan finds your comma on this Wi-Fi and fetches its Galaxy link from it."
                }
                dpText(12.5f)
                setTextColor(pal.textSecondary)
                setPadding(dp(4), dp(8), dp(4), 0)
            }
            val galaxyScanButton = createTonalButton("Scan local network to configure Galaxy", pal) {
                galaxySetupTouched = true
                val scanClient = client
                if (scanClient == null) {
                    galaxyScanStatus.text = "App is still starting — try Scan again in a moment."
                } else {
                    galaxyScanStatus.text = "Scanning local network for your comma…"
                    scanClient.configureGalaxyFromScan { _, msg ->
                        galaxyScanStatus.text = msg
                        cloudInput.setText(scanClient.getGalaxyCloudBase())
                        hasGalaxyUrl = scanClient.hasDedicatedGalaxyUrl()
                        hasGalaxyPassword = GalaxyCloudSession.hasPassword()
                        refreshModeRows()
                        onUpdated()
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, 0) }
            }
            cloudSection.addView(galaxyScanButton)
            cloudSection.addView(galaxyScanStatus)

            val listCard = createGroupCard(pal)
            val badgeViews = mutableMapOf<String, TextView>()
            val rowViews = mutableMapOf<String, LinearLayout>()
            val subtitleViews = mutableMapOf<String, TextView>()

            modes.forEachIndexed { idx, (modeKey, title, subtitle) ->
                if (idx > 0) listCard.addView(createDivider(pal))
                val isSel = modeKey == selectedMode
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    isClickable = true
                    isFocusable = true
                }
                rowViews[modeKey] = row
                val textCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(TextView(activity).apply {
                    text = title
                    dpText(15f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(pal.textPrimary)
                })
                val subtitleView = TextView(activity).apply {
                    text = subtitle
                    dpText(12.5f)
                    setTextColor(pal.textSecondary)
                    setPadding(0, dp(2), 0, 0)
                }
                subtitleViews[modeKey] = subtitleView
                textCol.addView(subtitleView)
                row.addView(textCol)

                val badge = TextView(activity).apply {
                    text = if (isSel) "Selected" else ""
                    dpText(11.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(pal.accentBlue)
                    background = HudOverlay.makeCardBg(activity, pal.accentBlueTonalBg, 12)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    visibility = if (isSel) View.VISIBLE else View.GONE
                }
                badgeViews[modeKey] = badge
                row.addView(badge)

                row.setOnClickListener {
                    // Disabled rows (transport not set up yet) do not
                    // take the tap — the row itself says what is missing.
                    if (!TransportReadiness.modeSelectable(modeKey, hasBleKey, hasGalaxyUrl, hasGalaxyPassword)) {
                        return@setOnClickListener
                    }
                    selectedMode = modeKey
                    refreshModeRows()
                }
                listCard.addView(row)
            }

            // Re-renders every mode row from the current readiness
            // facts: unready transports are dimmed, unclickable, badged
            // "Not set up", and subtitled with the reason; the Auto row
            // shows the priority order Auto will actually try.
            refreshModeRows = {
                modes.forEach { (modeKey, _, _) ->
                    val selectable = TransportReadiness.modeSelectable(
                        modeKey, hasBleKey, hasGalaxyUrl, hasGalaxyPassword
                    )
                    rowViews[modeKey]?.let { rowView ->
                        rowView.alpha = if (selectable) 1.0f else 0.45f
                        rowView.isClickable = selectable
                    }
                    subtitleViews[modeKey]?.let { subtitleView ->
                        subtitleView.text = when (modeKey) {
                            CommaClient.MODE_AUTO ->
                                "Priority: " + TransportReadiness.autoOrderText(
                                    hasBleKey, hasGalaxyUrl, hasGalaxyPassword
                                )
                            CommaClient.MODE_BLUETOOTH ->
                                TransportReadiness.modeBlockedReason(
                                    modeKey, hasBleKey, hasGalaxyUrl, hasGalaxyPassword
                                ) ?: "Direct low-energy bridge"
                            CommaClient.MODE_CLOUD ->
                                TransportReadiness.modeBlockedReason(
                                    modeKey, hasBleKey, hasGalaxyUrl, hasGalaxyPassword
                                ) ?: if (GalaxyCloudSession.authFailed) {
                                    "Remote access (Pairing expired - reconnect locally to re-pair)"
                                } else {
                                    "Remote access via galaxy.firestar.link"
                                }
                            else -> subtitleView.text
                        }
                    }
                    badgeViews[modeKey]?.let { badge ->
                        when {
                            !selectable -> {
                                badge.text = "Not set up"
                                badge.visibility = View.VISIBLE
                            }
                            modeKey == selectedMode -> {
                                badge.text = "Selected"
                                badge.visibility = View.VISIBLE
                            }
                            else -> {
                                badge.text = ""
                                badge.visibility = View.GONE
                            }
                        }
                    }
                }
                ipSection.visibility =
                    if (selectedMode == CommaClient.MODE_LOCAL) View.VISIBLE else View.GONE
                cloudSection.visibility = if (selectedMode == CommaClient.MODE_CLOUD ||
                    !TransportReadiness.cloudReady(hasGalaxyUrl, hasGalaxyPassword) ||
                    galaxySetupTouched
                ) View.VISIBLE else View.GONE
            }
            refreshModeRows()

            body.addView(listCard)

            // The comma's IP, displayed (Param, 2026-11-27): the
            // address in use while Local serves, or the learned
            // local address (Addendum 156) while Bluetooth or Cloud
            // serves — the address the Local probe will try. Read
            // from the existing holders at open time; this dialog
            // does not re-render on state changes today, and display
            // never fetches — the heartbeat/learn paths own freshness.
            val ipStatusText = ConnectionIpDisplay.statusText(
                transport = NavStateManager.activeTransport,
                activeIp = client?.commaIp,
                learnedIp = client?.getLearnedLocalIp()
                    ?: client?.getLastScannedIp()
                    ?: client?.getUserLocalIp()
                    ?: client?.getSavedIp(),
                savedIp = client?.getLastScannedIp()
                    ?: client?.getUserLocalIp()
                    ?: client?.getSavedIp()
            )
            if (ipStatusText != null) {
                body.addView(TextView(activity).apply {
                    text = ipStatusText
                    dpText(12.5f)
                    setTextColor(pal.textSecondary)
                    setPadding(dp(4), dp(10), dp(4), 0)
                })
            }
            body.addView(ipSection)
            body.addView(cloudSection)

            // The Connection hub (Param, 2026-11-20): Bluetooth bridge
            // and Galaxy Cloud & Web live in this same window, with
            // per-transport buttons to open the Galaxy web UI.
            body.addView(createSectionHeader("BLUETOOTH BRIDGE", pal))
            val btStatus = TextView(activity).apply {
                text = "${NavStateManager.bleStatus} · " +
                    if (BleBridgeClient.hasPairingKey(activity)) "Key saved" else "No key"
                dpText(12.5f)
                setTextColor(pal.textSecondary)
                setPadding(dp(4), 0, 0, dp(6))
            }
            body.addView(btStatus)
            buildBluetoothSection(body, pal, client) { dialog.dismiss() }

            body.addView(createSectionHeader("GALAXY CLOUD & WEB", pal))
            buildGalaxySection(body, pal, client, onUpdated, embedded = true) { dialog.dismiss() }

            body.addView(createSectionHeader("OPEN GALAXY", pal))
            val openCard = createGroupCard(pal)
            openCard.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.BLUETOOTH,
                    title = "Galaxy over Bluetooth",
                    subtitle = "Web console through the BLE bridge",
                    pal = pal
                ) {
                    dialog.dismiss()
                    openGalaxyWeb()
                }
            )
            openCard.addView(createDivider(pal))
            openCard.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.WIFI_LINK,
                    title = "Galaxy over Local network",
                    subtitle = "Web console at the comma's LAN address",
                    pal = pal
                ) {
                    dialog.dismiss()
                    openGalaxyLocal(client)
                }
            )
            openCard.addView(createDivider(pal))
            openCard.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.CLOUD,
                    title = "Galaxy over Cloud",
                    subtitle = "Web console through galaxy.firestar.link",
                    pal = pal
                ) {
                    dialog.dismiss()
                    openGalaxyCloud(client)
                }
            )
            body.addView(openCard)

            val actionsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(16), 0, 0)
            }
            actionsRow.addView(createTonalButton("Cancel", pal) { dialog.dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(10), 0) }
            })
            actionsRow.addView(createPrimaryButton("Apply", pal) {
                val applySelection: () -> Unit = applySelection@{
                    dialog.dismiss()
                    val targetMode = selectedMode
                    val enteredIp = ipInput.text.toString().trim().takeIf { it.isNotEmpty() }
                    val enteredUrl = cloudInput.text.toString().trim()

                    Thread({
                        try {
                            when (targetMode) {
                                CommaClient.MODE_AUTO -> client?.setConnectionMode(CommaClient.MODE_AUTO)
                                CommaClient.MODE_BLUETOOTH -> {
                                    client?.setConnectionMode(CommaClient.MODE_BLUETOOTH)
                                    if (!BleBridgeClient.hasPairingKey(activity)) {
                                        activity.runOnUiThread { showBluetoothDialog(client) }
                                        return@Thread
                                    }
                                }
                                CommaClient.MODE_LOCAL -> {
                                    client?.setConnectionMode(CommaClient.MODE_LOCAL, enteredIp)
                                }
                                CommaClient.MODE_CLOUD -> {
                                    if (enteredUrl.isNotEmpty()) {
                                        client?.saveGalaxyCloudBase(enteredUrl)
                                    }
                                    client?.setConnectionMode(CommaClient.MODE_CLOUD)
                                }
                            }
                            activity.runOnUiThread { onUpdated() }
                        } catch (e: Exception) {
                            Log.e("SettingsDialogs", "Error setting connection mode: ${e.message}", e)
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Connection update failed: ${e.message ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
                                onUpdated()
                            }
                        }
                    }, "apply-conn-mode").start()
                }
                if (selectedMode == CommaClient.MODE_BLUETOOTH) {
                    // Choosing Bluetooth is the explicit opt-in: explain why
                    // the permission is needed, then ask (Param, 2026-11-02).
                    ensureBluetoothPermission("so ParamAuto can talk to your comma over Bluetooth when Wi-Fi is not available - the direct low-energy bridge between this phone and the comma.") {
                        applySelection()
                    }
                } else {
                    applySelection()
                }
            })
            body.addView(actionsRow)
            dialog.show()
        }
    }


    fun showBluetoothDialog(client: CommaClient? = null) {
        val hasKey = BleBridgeClient.hasPairingKey(activity)
        val statusSub = "${NavStateManager.bleStatus} · ${if (hasKey) "Key saved" else "No key"}"
        val (dialog, body, pal) = createM3Dialog("Bluetooth bridge", statusSub)
        buildBluetoothSection(body, pal, client) { dialog.dismiss() }
        dialog.show()
    }

    /**
     * The Bluetooth bridge controls, shared by the standalone dialog
     * and the Connection hub (Addendum 119). [dismiss] closes the
     * hosting dialog after a successful Save.
     */
    private fun buildBluetoothSection(
        body: LinearLayout,
        pal: M3Palette,
        client: CommaClient?,
        dismiss: () -> Unit
    ) {
        val hasKey = BleBridgeClient.hasPairingKey(activity)
        val savedHex = BleBridgeClient.savedKeyHex(activity)

        val input = createInputBox(savedHex ?: "", "Pairing JSON or 64-char hex key", pal, isPassword = true)
        body.addView(input)

        val commaIp = client?.commaIp ?: activity.getSharedPreferences("comma_client", Context.MODE_PRIVATE)
            .getString("comma_ip", null) ?: "192.168.68.16"

        val btControlRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(4))
        }
        btControlRow.addView(createTonalButton("Bluetooth ON", pal) {
            Toast.makeText(activity, "Turning Comma Bluetooth ON...", Toast.LENGTH_SHORT).show()
            Thread {
                val ok = LanPairing.setBluetoothPower(commaIp, true)
                activity.runOnUiThread {
                    Toast.makeText(activity, if (ok) "Comma Bluetooth turned ON" else "Could not reach Comma over Wi-Fi", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(6), 0)
            }
        })

        btControlRow.addView(createTonalButton("Bluetooth OFF", pal) {
            Toast.makeText(activity, "Turning Comma Bluetooth OFF...", Toast.LENGTH_SHORT).show()
            Thread {
                val ok = LanPairing.setBluetoothPower(commaIp, false)
                activity.runOnUiThread {
                    Toast.makeText(activity, if (ok) "Comma Bluetooth turned OFF" else "Could not reach Comma over Wi-Fi", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(6), 0)
            }
        })

        btControlRow.addView(createTonalButton("Pair phone", pal) {
            ensureBluetoothPermission("so ParamAuto can pair with your comma over Bluetooth.") {
                Toast.makeText(activity, "Opening Comma pairing window...", Toast.LENGTH_SHORT).show()
                Thread {
                    val opened = LanPairing.openPairingWindow(commaIp)
                    activity.runOnUiThread {
                        if (opened) {
                            Toast.makeText(activity, "Pairing window open (120s) · claiming key...", Toast.LENGTH_SHORT).show()
                        }
                    }
                    val key = BleBridgeClient.fetchAndSaveKeyFromComma(activity, commaIp)
                    activity.runOnUiThread {
                        if (!key.isNullOrBlank()) {
                            input.setText(key)
                            Toast.makeText(activity, "Paired! Key fetched and saved", Toast.LENGTH_SHORT).show()
                        } else if (!opened) {
                            Toast.makeText(activity, "Could not reach Comma over Wi-Fi", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "Pairing window open on Comma, but BLE claim timed out", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        body.addView(btControlRow)

        val fetchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        fetchRow.addView(createTonalButton("Fetch key from car Wi-Fi", pal) {
            ensureBluetoothPermission("so ParamAuto can fetch the Bluetooth key from your comma.") {
                Toast.makeText(activity, "Fetching key from car...", Toast.LENGTH_SHORT).show()
                Thread {
                    val key = BleBridgeClient.fetchAndSaveKeyFromComma(activity, commaIp)
                    activity.runOnUiThread {
                        if (!key.isNullOrBlank()) {
                            input.setText(key)
                            Toast.makeText(activity, "Key fetched and saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "Could not fetch key over Wi-Fi", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(8), 0)
            }
        })

        fetchRow.addView(createTonalButton("Clear key", pal) {
            BleBridgeClient.saveKeyHex(activity, null)
            BleBridgeClient.getSharedClient(activity).disconnect()
            NavStateManager.setBleStatus("Off")
            input.setText("")
            Toast.makeText(activity, "Pairing key cleared", Toast.LENGTH_SHORT).show()
        })
        body.addView(fetchRow)

        fun saveEnteredKey(): Boolean {
            val entered = input.text.toString().trim()
            if (entered.isEmpty()) return hasKey
            return try {
                val key = GalaxyBle.parsePairingKey(entered)
                BleBridgeClient.saveKeyHex(activity, GalaxyBle.bytesToHex(key))
                true
            } catch (e: Exception) {
                Toast.makeText(activity, e.message ?: "Invalid pairing key", Toast.LENGTH_LONG).show()
                false
            }
        }

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        actionsRow.addView(createTonalButton("Test link", pal) {
            if (saveEnteredKey()) {
                ensureBluetoothPermission("so ParamAuto can test the direct Bluetooth bridge between this phone and your comma.") {
                    Toast.makeText(activity, "Testing Bluetooth bridge...", Toast.LENGTH_SHORT).show()
                    Thread {
                        val bleClient = BleBridgeClient.getSharedClient(activity)
                        try {
                            val health = bleClient.connectAndHealth()
                            activity.runOnUiThread {
                                Toast.makeText(
                                    activity,
                                    "Connected · protocol ${health.optInt("protocol")}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } catch (e: Exception) {
                            NavStateManager.setBleStatus("Failed: ${e.message}")
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Bluetooth failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(10), 0) }
        })

        actionsRow.addView(createPrimaryButton("Save", pal) {
            if (saveEnteredKey()) {
                NavStateManager.setBleStatus("Paired (key saved)")
                dismiss()
            }
        })
        body.addView(actionsRow)
    }

    fun showGalaxySessionDialog(client: CommaClient?, onUpdated: () -> Unit) {
        val (dialog, body, pal) = createM3Dialog("Galaxy Cloud & Web", "App Key pairing and web console")
        buildGalaxySection(body, pal, client, onUpdated, embedded = false) { dialog.dismiss() }
        dialog.show()
    }

    /**
     * The Galaxy Cloud & Web controls, shared by the standalone
     * dialog and the Connection hub (Addendum 119). Embedded in the
     * hub, the web-console button and the Cancel/Save row drop out —
     * the hub supplies its own Open Galaxy buttons, and the URL gets
     * a Save button that does not close the hub.
     */
    private fun buildGalaxySection(
        body: LinearLayout,
        pal: M3Palette,
        client: CommaClient?,
        onUpdated: () -> Unit,
        embedded: Boolean,
        dismiss: () -> Unit
    ) {
        val pairStatus = TextView(activity).apply {
            text = if (client?.isGalaxyPaired() == true) {
                "App Key: paired (no password needed)"
            } else {
                "App Key: not paired - open the app once on the comma's Wi-Fi / hotspot and it pairs automatically"
            }
            dpText(12.5f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), 0, 0, dp(6))
        }
        body.addView(pairStatus)
        if (client?.isGalaxyPaired() == true) {
            body.addView(createTonalButton("Unpair", pal) {
                client.unpairGalaxy()
                pairStatus.text = "App Key: not paired - open the app once on the comma's Wi-Fi / hotspot and it pairs automatically"
                onUpdated()
            })
        }

        val urlLabel = TextView(activity).apply {
            text = "Galaxy Cloud URL (auto-grabbed on Wi-Fi)"
            dpText(12.5f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(12), 0, dp(6))
        }
        body.addView(urlLabel)
        val currentCloudUrl = client?.getGalaxyCloudBase() ?: CommaClient.DEFAULT_GALAXY_URL
        val urlInput = createInputBox(currentCloudUrl, "https://galaxy.firestar.link/<slug>", pal)
        body.addView(urlInput)

        if (!embedded) {
            val webBtn = createTonalButton("Open Galaxy web console", pal) {
                dismiss()
                openGalaxyWeb()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(12), 0, 0)
                }
            }
            body.addView(webBtn)

            val actionsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(16), 0, 0)
            }
            actionsRow.addView(createTonalButton("Cancel", pal) { dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(10), 0) }
            })
            actionsRow.addView(createPrimaryButton("Save", pal) {
                val enteredUrl = urlInput.text.toString().trim()
                if (enteredUrl.isNotEmpty()) {
                    client?.saveGalaxyCloudBase(enteredUrl)
                }
                onUpdated()
                dismiss()
            })
            body.addView(actionsRow)
        } else {
            body.addView(createPrimaryButton("Save Galaxy URL", pal) {
                val enteredUrl = urlInput.text.toString().trim()
                if (enteredUrl.isNotEmpty()) {
                    client?.saveGalaxyCloudBase(enteredUrl)
                }
                onUpdated()
                Toast.makeText(activity, "Galaxy URL saved", Toast.LENGTH_SHORT).show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(12), 0, 0)
                }
            })
        }
    }

    fun showMapboxTokenDialog(client: CommaClient?, onUpdated: () -> Unit) {
        val (dialog, body, pal) = createM3Dialog(
            "Maps & offline data",
            "Offline map pack — keys live in Map keys & usage"
        )

        val offlineBtn = createTonalButton("Download Phoenix metro offline map", pal) {
            dialog.dismiss()
            downloadOfflinePhoenixMap()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(14))
            }
        }
        body.addView(offlineBtn)

        // The Mapbox token and Google key fields moved to Settings
        // -> Map keys & usage (Param, 2026-11-20), where each
        // provider tab also shows usage — or setup instructions when
        // no key is saved yet.
        val note = TextView(activity).apply {
            text = "Map keys moved: the Mapbox token and the Google " +
                "keys now live in Map keys & usage, with usage and " +
                "setup instructions."
            dpText(13.5f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        body.addView(note)

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        actionsRow.addView(createTonalButton("Done", pal) { dialog.dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(10), 0) }
        })
        actionsRow.addView(createPrimaryButton("Map keys & usage", pal) {
            dialog.dismiss()
            showMapKeysDialog(client, onUpdated)
        })
        body.addView(actionsRow)
        dialog.show()
    }

    fun showUsageCountersDialog() {
        val snapshot = UsageCounters.snapshot()
        val tileCount = CommaTileDiskCache.getCachedTileCount()
        val tileMb = CommaTileDiskCache.getCacheSizeBytes() / (1024 * 1024)
        val (dialog, body, pal) = createM3Dialog("Data & cache usage", "Monthly API calls and local map storage")

        val cacheCard = createGroupCard(pal)
        val cacheStats = listOf(
            "Cached map tiles" to "$tileCount ($tileMb MB)",
            "Disk cache hits" to "${CommaTileDiskCache.hitCount.get()}",
            "Network tile fetches" to "${CommaTileDiskCache.missCount.get()}"
        )
        cacheStats.forEachIndexed { idx, (label, value) ->
            if (idx > 0) cacheCard.addView(createDivider(pal))
            cacheCard.addView(createKeyValueRow(label, value, pal))
        }
        body.addView(cacheCard)

        body.addView(createSectionHeader("MONTHLY REQUESTS", pal))
        val apiCard = createGroupCard(pal)
        val labels = listOf(
            UsageCounters.MAPBOX_SUGGEST to "Mapbox search",
            UsageCounters.MAPBOX_DIRECTIONS to "Mapbox routes",
            UsageCounters.PHOTON_SEARCH to "Photon search (free)",
            UsageCounters.OSRM_DIRECTIONS to "OSRM routes (free)",
            UsageCounters.SYSTEM_MONITOR to "System Monitor (12V)",
            UsageCounters.GALAXY_POLL to "Galaxy status polls"
        )
        labels.forEachIndexed { idx, (key, label) ->
            if (idx > 0) apiCard.addView(createDivider(pal))
            apiCard.addView(createKeyValueRow(label, "${snapshot[key] ?: 0L}", pal))
        }
        body.addView(apiCard)

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        actionsRow.addView(createTonalButton("Reset counters", pal) {
            UsageCounters.reset()
            dialog.dismiss()
            showUsageCountersDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(10), 0) }
        })
        actionsRow.addView(createPrimaryButton("Done", pal) { dialog.dismiss() })
        body.addView(actionsRow)
        dialog.show()
    }

    private fun createKeyValueRow(label: String, value: String, pal: M3Palette): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(TextView(activity).apply {
                text = label
                dpText(14f)
                setTextColor(pal.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(activity).apply {
                text = value
                dpText(14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(pal.textSecondary)
            })
        }
    }

    /**
     * A slim usage bar for a real quantity (Addendum 121): the
     * filled fraction of a monthly allowance — blue under the 95%
     * switch line, amber at/above it. Only ever built from a
     * measured Budget fraction; never decorative.
     */
    private fun createUsageBar(fraction: Double, pal: M3Palette): LinearLayout {
        val clamped = fraction.coerceIn(0.0, 1.0)
        val fillColor = if (clamped >= 0.95) pal.accentAmber else pal.accentBlue
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)
            ).apply { setMargins(dp(16), 0, dp(16), dp(10)) }
            background = HudOverlay.makeCardBg(activity, pal.inputBg, 2)
            addView(View(activity).apply {
                background = HudOverlay.makeCardBg(activity, fillColor, 2)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT,
                    (clamped * 1000).toFloat()
                )
            })
            addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT,
                    ((1.0 - clamped) * 1000).toFloat()
                )
            })
        }
    }

    fun downloadOfflinePhoenixMap() {
        Toast.makeText(activity, "Downloading offline Phoenix map...", Toast.LENGTH_SHORT).show()
        var lastToastedQuarter = 0
        OfflineTiles.downloadPhoenixMetro(
            activity,
            ThemeManager.getDayStyleUrl(),
            onProgress = { percent ->
                activity.runOnUiThread {
                    val quarter = percent / 25
                    if (quarter in 1..3 && quarter > lastToastedQuarter) {
                        lastToastedQuarter = quarter
                        Toast.makeText(activity, "Offline map: $percent%", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onComplete = { _, message ->
                activity.runOnUiThread {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    /**
     * Wi-Fi networks (Param, 2026-11-19): the comma's Galaxy Networks
     * page brought into Settings — saved Wi-Fi networks in priority
     * order with their per-network choices, scan + join (including
     * hidden networks), the Wi-Fi/cellular preference, the cellular
     * data switch and the hotspot-pause choice. Every change is one
     * POST to /api/galaxy/networks via
     * [NavigationManager.applyNetworksActionSg]; the comma locks
     * changes unless parked (the snapshot's editable flag) and its
     * error text is surfaced verbatim. See [GalaxyNetworks] for the
     * pinned contract.
     */
    fun showNetworksDialog(
        client: CommaClient?,
        onUpdated: () -> Unit,
        onDismiss: (() -> Unit)? = null
    ) {
        val (dialog, body, pal) = createM3Dialog(
            "Wi-Fi networks",
            "Saved Wi-Fi, cellular data & link preference on the comma"
        )
        val endpoint = client?.getEffectiveNavigationEndpoint()
        val baseUrl = SgGalaxyClient.baseUrlFor(endpoint ?: "192.168.68.9")
        val navManager = NavigationManager()
        val joinPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var forgetArmedUuid: String? = null
        var lastNetworks: GalaxyNetworks? = null
        var hotspotState: HotspotState? = null
        val hsClient = SgGalaxyClient(baseUrl)

        val statusLine = TextView(activity).apply {
            dpText(13f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(12), dp(4), 0)
        }
        body.addView(statusLine)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(content)

        fun noteView(text: String): TextView = TextView(activity).apply {
            this.text = text
            dpText(13.5f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        lateinit var render: (GalaxyNetworks) -> Unit

        // One action round-trip: busy text in the status line, the
        // comma's fresh snapshot re-rendered on success, its own error
        // text on failure (re-rendering the last known state too, so a
        // rejected switch snaps back instead of lying).
        fun runAction(actionBody: JSONObject, busyText: String, successText: String?) {
            statusLine.text = busyText
            Thread {
                val result = navManager.applyNetworksActionSg(baseUrl, actionBody)
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (result.code == 200 && result.state != null) {
                        statusLine.text = ""
                        forgetArmedUuid = null
                        if (successText != null) {
                            Toast.makeText(
                                activity, successText, Toast.LENGTH_SHORT
                            ).show()
                        }
                        onUpdated()
                        render(result.state)
                    } else {
                        statusLine.text = result.error
                            ?: "Comma answered HTTP ${result.code}"
                        NavStateManager.commaNetworks?.let { render(it) }
                    }
                }
            }.start()
        }

        fun pickFromList(
            title: String,
            options: List<String>,
            current: String,
            onPick: (String) -> Unit
        ) {
            showSheetListPicker(title, options, current, onPick = onPick)
        }

        fun promptJoin(fixedSsid: String?, hidden: Boolean) {
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            val ssidInput = if (hidden) {
                createInputBox("", "Network name (SSID)", pal).also {
                    container.addView(it)
                }
            } else {
                null
            }
            val passwordInput = createInputBox(
                "",
                if (hidden) {
                    "Password (blank if open)"
                } else {
                    "Password · 8–64 characters"
                },
                pal, isPassword = true
            )
            container.addView(passwordInput)
            showSheetConfirm(
                title = if (hidden) "Join hidden network" else "Join $fixedSsid",
                message = "Joining pauses the Galaxy hotspot for up to 30 " +
                    "seconds. If your phone is on it, it reconnects " +
                    "afterwards.",
                positiveLabel = "Join",
                content = container,
                onPositive = {
                    val ssid = if (hidden) {
                        ssidInput?.text?.toString()?.trim() ?: ""
                    } else {
                        fixedSsid ?: ""
                    }
                    val password = passwordInput.text.toString()
                    when {
                        !GalaxyNetworks.isValidSsid(ssid) ->
                            statusLine.text =
                                "Enter a network name (1–64 characters)."
                        !GalaxyNetworks.isValidPassword(password) ->
                            statusLine.text =
                                "Enter an 8–64 character password, or " +
                                    "leave it blank for an open network."
                        else -> runAction(
                            GalaxyNetworks.joinAction(ssid, password, hidden),
                            "Joining $ssid…",
                            null
                        )
                    }
                }
            )
        }

        fun refresh(showLoading: Boolean) {
            if (showLoading) {
                content.removeAllViews()
                content.addView(noteView("Loading network settings…"))
            }
            Thread {
                val state = navManager.fetchNetworksSg(baseUrl)
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (state != null) {
                        render(state)
                    } else if (showLoading) {
                        content.removeAllViews()
                        content.addView(
                            noteView(
                                "Couldn't read network settings from the " +
                                    "comma. It may not run a StarPilot " +
                                    "build with the Networks page yet, or " +
                                    "the connection dropped."
                            )
                        )
                    }
                }
            }.start()
        }

        // Galaxy hotspot (moved into this page from the Settings
        // CONNECTION card, Param, 2026-11-20): the toggle POSTs the
        // revision-checked payload, then trusts the re-read state,
        // never the request — the same receipt discipline the old
        // Settings row used.
        fun toggleHotspot() {
            val current = hotspotState
            val payload = current?.togglePayload()
            if (current == null || payload == null) {
                statusLine.text =
                    "Hotspot state isn't available yet — check the connection"
                return
            }
            statusLine.text = "Changing the hotspot…"
            Thread {
                val (code, resp) = hsClient.postHotspot(payload)
                val confirmed = if (code in 200..299) {
                    HotspotState.parse(hsClient.fetchHotspot())
                        ?: HotspotState.parse(resp)
                } else {
                    null
                }
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (code in 200..299) {
                        if (confirmed != null) hotspotState = confirmed
                        statusLine.text = ""
                        Toast.makeText(
                            activity,
                            confirmed?.label() ?: "Hotspot updated",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        statusLine.text =
                            resp?.optString("error", "")?.ifEmpty { null }
                                ?: "Hotspot change failed — HTTP $code"
                    }
                    lastNetworks?.let { render(it) }
                }
            }.start()
        }

        fun refreshHotspot() {
            Thread {
                val fetched = HotspotState.parse(hsClient.fetchHotspot())
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (fetched != null && fetched != hotspotState) {
                        hotspotState = fetched
                        lastNetworks?.let { render(it) }
                    }
                }
            }.start()
        }

        render = { state ->
            content.removeAllViews()
            lastNetworks = state
            val editable = state.editable

            if (!editable) {
                content.addView(
                    noteView(
                        "Park before changing network settings — the " +
                            "comma locks changes while driving. Everything " +
                            "here stays viewable."
                    )
                )
            }

            state.lastJoin?.let { join ->
                content.addView(
                    noteView(
                        when (join.state) {
                            "joining" ->
                                "Joining ${join.ssid}… The Galaxy hotspot " +
                                    "pauses briefly; your phone should " +
                                    "reconnect on its own."
                            "joined" -> "Joined ${join.ssid}."
                            else ->
                                "Couldn't join ${join.ssid}. ${join.error}"
                        }
                    )
                )
                if (join.state == "joining") {
                    joinPollHandler.postDelayed({
                        if (dialog.isShowing) refresh(false)
                    }, 3000)
                }
            }

            // ---- Connection: current link, preference, cellular ----
            content.addView(createSectionHeader("CONNECTION", pal))
            val connCard = createGroupCard(pal)
            val linkText = GalaxyNetworks.linkLabel(state.link) +
                if (state.linkChangedAt > 0) {
                    " · since " + java.text.SimpleDateFormat(
                        "h:mm a", java.util.Locale.US
                    ).format(
                        java.util.Date((state.linkChangedAt * 1000).toLong())
                    )
                } else {
                    ""
                }
            connCard.addView(createKeyValueRow("Current link", linkText, pal))
            connCard.addView(createDivider(pal))
            connCard.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.WIFI_LINK,
                    title = "Prefer",
                    subtitle = "Used when both are available; apps " +
                        "reconnect automatically",
                    badgeText = GalaxyNetworks.preferLabel(state.prefer),
                    badgeActive = true,
                    pal = pal
                ) {
                    if (!editable) {
                        statusLine.text =
                            "Park before changing network settings."
                    } else {
                        pickFromList(
                            "Prefer",
                            listOf("Wi-Fi", "Cellular"),
                            GalaxyNetworks.preferLabel(state.prefer)
                        ) { picked ->
                            runAction(
                                GalaxyNetworks.preferAction(
                                    if (picked == "Cellular") {
                                        GalaxyNetworks.PREFER_CELLULAR
                                    } else {
                                        GalaxyNetworks.PREFER_WIFI
                                    }
                                ),
                                "Saving the link preference…",
                                "Saved on the comma"
                            )
                        }
                    }
                }
            )
            connCard.addView(createDivider(pal))
            if (editable) {
                connCard.addView(
                    createSwitchRow(
                        iconKind = SettingsIconView.Kind.CLOUD,
                        title = "Cellular data",
                        subtitle = state.cellularDetail(),
                        initialChecked = state.cellularEnabled,
                        pal = pal,
                        subtitleMaxLines = 2
                    ) { checked ->
                        runAction(
                            GalaxyNetworks.cellularAction(checked),
                            "Saving cellular data…",
                            "Saved on the comma"
                        )
                    }
                )
            } else {
                connCard.addView(
                    createSettingsRow(
                        iconKind = SettingsIconView.Kind.CLOUD,
                        title = "Cellular data",
                        subtitle = state.cellularDetail(),
                        badgeText = if (state.cellularEnabled) "On" else "Off",
                        badgeActive = state.cellularEnabled,
                        pal = pal
                    ) {
                        statusLine.text =
                            "Park before changing network settings."
                    }
                )
            }
            content.addView(connCard)

            // ---- Saved Wi-Fi networks, in priority order ----
            content.addView(createSectionHeader("SAVED WI-FI NETWORKS", pal))
            if (state.networks.isEmpty()) {
                content.addView(
                    noteView(
                        "No saved Wi-Fi networks yet. Scan for nearby " +
                            "networks below to join one."
                    )
                )
            } else {
                content.addView(
                    noteView("Joined in priority order when in range.")
                )
                state.networks.forEachIndexed { index, network ->
                    val card = createGroupCard(pal)
                    val header = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(10), dp(8), dp(10))
                    }
                    val headerText = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        )
                    }
                    headerText.addView(TextView(activity).apply {
                        text = "${index + 1}. ${network.name}"
                        dpText(15f)
                        typeface = Typeface.create(
                            "sans-serif-medium", Typeface.NORMAL
                        )
                        setTextColor(pal.textPrimary)
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    headerText.addView(TextView(activity).apply {
                        text = GalaxyNetworks.networkSummary(network)
                        dpText(12.5f)
                        setTextColor(pal.textSecondary)
                    })
                    header.addView(headerText)
                    if (editable && state.networks.size > 1) {
                        if (index > 0) {
                            header.addView(
                                createTonalButton("↑", pal) {
                                    val uuids = GalaxyNetworks.reorderedUuids(
                                        state.networks, index, -1
                                    )
                                    if (uuids != null) {
                                        runAction(
                                            GalaxyNetworks.orderAction(uuids),
                                            "Reordering networks…",
                                            "Saved on the comma"
                                        )
                                    }
                                }
                            )
                        }
                        if (index < state.networks.size - 1) {
                            header.addView(
                                createTonalButton("↓", pal) {
                                    val uuids = GalaxyNetworks.reorderedUuids(
                                        state.networks, index, 1
                                    )
                                    if (uuids != null) {
                                        runAction(
                                            GalaxyNetworks.orderAction(uuids),
                                            "Reordering networks…",
                                            "Saved on the comma"
                                        )
                                    }
                                }
                            )
                        }
                    }
                    card.addView(header)
                    card.addView(createDivider(pal))
                    if (editable) {
                        card.addView(
                            createSwitchRow(
                                iconKind = SettingsIconView.Kind.WIFI_LINK,
                                title = "Join automatically",
                                subtitle = "Join this network when in range",
                                initialChecked = network.autoJoin,
                                pal = pal
                            ) { checked ->
                                runAction(
                                    GalaxyNetworks.networkBoolAction(
                                        network.uuid, "autoJoin", checked
                                    ),
                                    "Saving ${network.name}…",
                                    "Saved on the comma"
                                )
                            }
                        )
                    } else {
                        card.addView(
                            createSettingsRow(
                                iconKind = SettingsIconView.Kind.WIFI_LINK,
                                title = "Join automatically",
                                subtitle = "Join this network when in range",
                                badgeText = if (network.autoJoin) "On" else "Off",
                                badgeActive = network.autoJoin,
                                pal = pal
                            ) {
                                statusLine.text =
                                    "Park before changing network settings."
                            }
                        )
                    }
                    card.addView(createDivider(pal))
                    val drivingRow = if (editable) {
                        createSwitchRow(
                            iconKind = SettingsIconView.Kind.TUNE,
                            title = "Join while StarPort is on",
                            subtitle = "When off, stays on the current " +
                                "connection until the drive ends",
                            initialChecked = network.whileDriving,
                            pal = pal,
                            subtitleMaxLines = 2
                        ) { checked ->
                            runAction(
                                GalaxyNetworks.networkBoolAction(
                                    network.uuid, "whileDriving", checked
                                ),
                                "Saving ${network.name}…",
                                "Saved on the comma"
                            )
                        }
                    } else {
                        createSettingsRow(
                            iconKind = SettingsIconView.Kind.TUNE,
                            title = "Join while StarPort is on",
                            subtitle = "When off, stays on the current " +
                                "connection until the drive ends",
                            badgeText = if (network.whileDriving) "On" else "Off",
                            badgeActive = network.whileDriving,
                            pal = pal
                        ) {
                            statusLine.text =
                                "Park before changing network settings."
                        }
                    }
                    if (editable && !network.autoJoin) {
                        drivingRow.alpha = 0.45f
                        drivingRow.isClickable = false
                    }
                    card.addView(drivingRow)
                    card.addView(createDivider(pal))
                    card.addView(
                        createSettingsRow(
                            iconKind = SettingsIconView.Kind.MAP,
                            title = "Data limit",
                            subtitle = "Metered networks skip large uploads",
                            badgeText = network.metered,
                            badgeActive = true,
                            pal = pal
                        ) {
                            if (!editable) {
                                statusLine.text =
                                    "Park before changing network settings."
                            } else {
                                pickFromList(
                                    "Data limit",
                                    GalaxyNetworks.METERED_OPTIONS,
                                    network.metered
                                ) { picked ->
                                    runAction(
                                        GalaxyNetworks.networkMeteredAction(
                                            network.uuid, picked
                                        ),
                                        "Saving ${network.name}…",
                                        "Saved on the comma"
                                    )
                                }
                            }
                        }
                    )
                    if (editable) {
                        card.addView(createDivider(pal))
                        val actions = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.END
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                        }
                        if (!network.connected && network.signal != null) {
                            actions.addView(
                                createTonalButton("Connect", pal) {
                                    runAction(
                                        GalaxyNetworks.connectAction(
                                            network.uuid
                                        ),
                                        "Connecting to ${network.name}…",
                                        null
                                    )
                                }.apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply { setMargins(0, 0, dp(10), 0) }
                                }
                            )
                        }
                        val armed = forgetArmedUuid == network.uuid
                        actions.addView(
                            createTonalButton(
                                if (armed) "Confirm forget" else "Forget",
                                pal
                            ) {
                                if (!armed) {
                                    forgetArmedUuid = network.uuid
                                    statusLine.text =
                                        if (network.connected) {
                                            "The comma will leave " +
                                                "${network.name} now. Tap " +
                                                "Confirm forget to go ahead."
                                        } else {
                                            "Tap Confirm forget to remove " +
                                                "${network.name} from the comma."
                                        }
                                    render(state)
                                } else {
                                    runAction(
                                        GalaxyNetworks.forgetAction(
                                            network.uuid
                                        ),
                                        "Forgetting ${network.name}…",
                                        "Removed from the comma"
                                    )
                                }
                            }
                        )
                        card.addView(actions)
                    }
                    content.addView(card)
                }
            }
            if (editable) {
                content.addView(
                    createSwitchRow(
                        iconKind = SettingsIconView.Kind.TUNE,
                        title = "Pause Galaxy hotspot while joining Wi-Fi",
                        subtitle = "Briefly pauses the hotspot during a " +
                            "drive so joining Wi-Fi does not disturb " +
                            "StarPort",
                        initialChecked = state.pauseHotspot,
                        pal = pal,
                        subtitleMaxLines = 2
                    ) { checked ->
                        runAction(
                            GalaxyNetworks.pauseHotspotAction(checked),
                            "Saving the hotspot choice…",
                            "Saved on the comma"
                        )
                    }
                )
            } else {
                content.addView(
                    createSettingsRow(
                        iconKind = SettingsIconView.Kind.TUNE,
                        title = "Pause Galaxy hotspot while joining Wi-Fi",
                        subtitle = "Briefly pauses the hotspot during a " +
                            "drive so joining Wi-Fi does not disturb " +
                            "StarPort",
                        badgeText = if (state.pauseHotspot) "On" else "Off",
                        badgeActive = state.pauseHotspot,
                        pal = pal
                    ) {
                        statusLine.text =
                            "Park before changing network settings."
                    }
                )
            }

            // ---- Nearby networks: scan + join ----
            content.addView(createSectionHeader("NEARBY NETWORKS", pal))
            if (!editable) {
                content.addView(
                    noteView("Park to scan for and join Wi-Fi networks.")
                )
            } else {
                val scanRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(2), 0, dp(6))
                }
                scanRow.addView(
                    createTonalButton("Scan for networks", pal) {
                        runAction(
                            GalaxyNetworks.scanAction(),
                            "Scanning for nearby Wi-Fi…",
                            null
                        )
                    }
                )
                content.addView(scanRow)
                if (state.available.isEmpty()) {
                    content.addView(
                        noteView(
                            "No new Wi-Fi networks found. Scan to look " +
                                "again."
                        )
                    )
                } else {
                    val nearbyCard = createGroupCard(pal)
                    state.available.forEachIndexed { i, found ->
                        if (i > 0) nearbyCard.addView(createDivider(pal))
                        val row = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(16), dp(10), dp(8), dp(10))
                        }
                        val texts = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                            )
                        }
                        texts.addView(TextView(activity).apply {
                            text = found.ssid
                            dpText(15f)
                            typeface = Typeface.create(
                                "sans-serif-medium", Typeface.NORMAL
                            )
                            setTextColor(pal.textPrimary)
                            setSingleLine(true)
                            ellipsize = TextUtils.TruncateAt.END
                        })
                        texts.addView(TextView(activity).apply {
                            text = "Signal ${found.signal}% · " +
                                when (found.security) {
                                    "open" -> "Open"
                                    "unsupported" ->
                                        "Enterprise Wi-Fi (unsupported)"
                                    else -> "Password required"
                                }
                            dpText(12.5f)
                            setTextColor(pal.textSecondary)
                        })
                        row.addView(texts)
                        if (found.security != "unsupported") {
                            row.addView(
                                createTonalButton("Join", pal) {
                                    if (found.security == "open") {
                                        runAction(
                                            GalaxyNetworks.joinAction(
                                                found.ssid, "", false
                                            ),
                                            "Joining ${found.ssid}…",
                                            null
                                        )
                                    } else {
                                        promptJoin(found.ssid, false)
                                    }
                                }
                            )
                        }
                        nearbyCard.addView(row)
                    }
                    content.addView(nearbyCard)
                }
                val hiddenRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(8), 0, 0)
                }
                hiddenRow.addView(
                    createTonalButton("Join hidden network", pal) {
                        promptJoin(null, true)
                    }
                )
                content.addView(hiddenRow)
            }

            // ---- Galaxy hotspot (moved here from the Settings
            // CONNECTION card, Param, 2026-11-20) ----
            content.addView(createSectionHeader("GALAXY HOTSPOT", pal))
            val hs = hotspotState
            val hsCard = createGroupCard(pal)
            val hsSubtitle = when {
                hs == null ->
                    "State unavailable — check the connection"
                hs.enabled ->
                    "On · ${hs.ssid} — join it from a phone to reach " +
                        "the comma"
                else ->
                    "Off — turn on to reach the comma when there's " +
                        "no Wi-Fi"
            }
            if (hs != null && editable) {
                hsCard.addView(
                    createSwitchRow(
                        iconKind = SettingsIconView.Kind.WIFI_LINK,
                        title = "Galaxy hotspot",
                        subtitle = hsSubtitle,
                        initialChecked = hs.enabled,
                        pal = pal,
                        subtitleMaxLines = 2
                    ) { toggleHotspot() }
                )
            } else {
                hsCard.addView(
                    createSettingsRow(
                        iconKind = SettingsIconView.Kind.WIFI_LINK,
                        title = "Galaxy hotspot",
                        subtitle = hsSubtitle,
                        badgeText = hs?.let {
                            if (it.enabled) "On" else "Off"
                        },
                        badgeActive = hs?.enabled == true,
                        pal = pal
                    ) {
                        if (hs == null) {
                            refreshHotspot()
                        } else {
                            statusLine.text =
                                "Park before changing the Galaxy hotspot."
                        }
                    }
                )
            }
            content.addView(hsCard)
        }

        val cached = NavStateManager.commaNetworks
        if (cached != null) render(cached)
        refresh(cached == null)
        refreshHotspot()
        if (onDismiss != null) {
            dialog.setOnDismissListener { onDismiss() }
        }
        dialog.show()
    }

    /**
     * Map keys & usage (Param, 2026-11-20): one page for the Mapbox
     * and Google keys, replacing the key + usage blocks that lived in
     * Phone Data (Addendum 107) and the phone keys that lived in Maps
     * & offline data. Two tabs, one per provider; a tab shows usage
     * when a key is configured and plain how-to-get-a-key
     * instructions when it is not ([ProviderGuide]). The comma's keys
     * are written through the navigation configure action and the
     * comma's refusals are shown in its own words; the phone's keys
     * save locally. After a comma key change the tab re-renders once
     * the next status snapshot lands, so the Saved state and usage
     * reflect the comma, not the request.
     */
    fun showMapKeysDialog(
        client: CommaClient?,
        onUpdated: () -> Unit,
        initialTab: String = ProviderGuide.TAB_MAPBOX
    ) {
        val (dialog, body, pal) = createM3Dialog(
            "Map keys & usage",
            "Mapbox and Google keys — usage, or how to get one"
        )
        val baseUrl = SgGalaxyClient.baseUrlFor(
            client?.getEffectiveNavigationEndpoint() ?: "192.168.68.9"
        )
        var tab = initialTab
        var mapboxDraft: String? = null
        var mapboxStatus: String? = null
        var googleDraft: String? = null
        var googlePhoneDraft: String? = null
        var googleStatus: String? = null

        fun fieldLabel(text: String): TextView = TextView(activity).apply {
            this.text = text
            dpText(12.5f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(12), 0, dp(6))
        }

        fun noteView(text: String): TextView = TextView(activity).apply {
            this.text = text
            dpText(13.5f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        fun statusView(text: String): TextView = TextView(activity).apply {
            this.text = text
            dpText(13f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(8), dp(4), 0)
        }

        fun actionRow(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }

        fun trailingMargin(view: View) {
            view.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(10), 0) }
        }

        // One comma configure round-trip on a worker thread; the
        // callback runs on the UI thread while the dialog is up.
        fun commaPatch(patch: JSONObject, onDone: (Int, String?) -> Unit) {
            Thread {
                val (code, error) = NavigationManager()
                    .configureNavSettingsSg(baseUrl, patch)
                activity.runOnUiThread {
                    if (dialog.isShowing) onDone(code, error)
                }
            }.start()
        }

        val mapboxContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val googleContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        lateinit var renderMapbox: () -> Unit
        lateinit var renderGoogle: () -> Unit
        lateinit var styleTabs: () -> Unit

        fun refreshGoogleSoon() {
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({
                    if (dialog.isShowing) renderGoogle()
                }, 1500)
        }

        renderMapbox = {
            mapboxContent.removeAllViews()
            val savedToken = (client?.mapboxToken
                ?: NavStateManager.mapboxToken)?.takeIf { it.isNotBlank() }
            val card = createGroupCard(pal)
            card.addView(
                createKeyValueRow(
                    "Token",
                    if (savedToken != null) "Saved" else "Not saved",
                    pal
                )
            )
            mapboxContent.addView(card)
            if (ProviderGuide.panelFor(savedToken != null) ==
                ProviderGuide.Panel.USAGE
            ) {
                val mb = NavStateManager.commaNavProviders?.mapboxUsage
                if (mb != null) {
                    mapboxContent.addView(
                        createSectionHeader("USAGE — ON THE COMMA", pal)
                    )
                    val mbCard = createGroupCard(pal)
                    val mbRows = listOfNotNull(
                        mb.searchSessions?.let {
                            Triple("Search sessions", it.label(), it.monthFraction())
                        },
                        mb.geocoding?.let {
                            Triple("Address lookups", it.label(), it.monthFraction())
                        },
                        mb.staticTiles?.let {
                            Triple("Map views", it.label(), it.monthFraction())
                        },
                        mb.directions?.let {
                            Triple("Routes", it.label(), it.monthFraction())
                        }
                    )
                    mbRows.forEachIndexed { idx, (label, value, fraction) ->
                        if (idx > 0) mbCard.addView(createDivider(pal))
                        mbCard.addView(createKeyValueRow(label, value, pal))
                        if (fraction != null) {
                            mbCard.addView(createUsageBar(fraction, pal))
                        }
                    }
                    mapboxContent.addView(mbCard)
                    mapboxContent.addView(
                        noteView(
                            "Counted on the comma for this month (UTC). " +
                                "Each flow stops at 99% of its free " +
                                "allowance; use of the same token " +
                                "elsewhere isn't counted."
                        )
                    )
                } else {
                    mapboxContent.addView(
                        noteView("Usage shows here once the comma reports it.")
                    )
                }
            } else {
                mapboxContent.addView(noteView(ProviderGuide.MAPBOX_STEPS))
            }
            mapboxContent.addView(fieldLabel("Mapbox public token (pk.…)"))
            val input = createInputBox(
                mapboxDraft ?: savedToken ?: "", "pk.…", pal,
                isPassword = true
            )
            mapboxContent.addView(input)
            mapboxStatus?.let { mapboxContent.addView(statusView(it)) }
            val actions = actionRow()
            if (savedToken != null) {
                actions.addView(createTonalButton("Clear", pal) {
                    mapboxDraft = ""
                    client?.setMapboxToken(null)
                    onUpdated()
                    if (NavStateManager.commaNavProviders != null) {
                        mapboxStatus = "Clearing the token on the comma…"
                        renderMapbox()
                        commaPatch(GoogleNav.tokenPatch("")) { code, error ->
                            mapboxStatus = if (code == 200) {
                                "Cleared on this phone and on the comma."
                            } else {
                                "Cleared on this phone. The comma said: " +
                                    (error ?: "HTTP $code")
                            }
                            renderMapbox()
                        }
                    } else {
                        mapboxStatus = "Cleared on this phone."
                        renderMapbox()
                    }
                }.apply { trailingMargin(this) })
            }
            actions.addView(createPrimaryButton("Save token", pal) {
                val entered = input.text.toString().trim()
                if (entered.isEmpty()) {
                    mapboxStatus =
                        "Paste a token first — or Clear to remove the saved one."
                    renderMapbox()
                    return@createPrimaryButton
                }
                mapboxDraft = entered
                client?.setMapboxToken(entered)
                onUpdated()
                if (NavStateManager.commaNavProviders != null) {
                    mapboxStatus = "Saving the token on the comma…"
                    renderMapbox()
                    commaPatch(GoogleNav.tokenPatch(entered)) { code, error ->
                        mapboxStatus = if (code == 200) {
                            "Saved on this phone and on the comma."
                        } else {
                            "Saved on this phone. The comma said: " +
                                (error ?: "HTTP $code")
                        }
                        renderMapbox()
                    }
                } else {
                    mapboxStatus = "Saved on this phone."
                    renderMapbox()
                }
            })
            mapboxContent.addView(actions)
            mapboxContent.addView(
                noteView(
                    "One token, two homes: it's used on this phone, and " +
                        "saved to the comma for its maps too. Saving on " +
                        "the comma needs the car parked."
                )
            )
        }

        renderGoogle = {
            googleContent.removeAllViews()
            val state = NavStateManager.commaNavProviders
            googleContent.addView(
                createSectionHeader("GOOGLE KEY — ON THE COMMA", pal)
            )
            if (state == null) {
                googleContent.addView(
                    noteView(
                        "This comma hasn't reported provider settings " +
                            "yet. Once it runs the StarPilot build with " +
                            "Google support and ParamAuto has heard a " +
                            "status update from it, the comma's Google " +
                            "key can be set here."
                    )
                )
                googleContent.addView(noteView(ProviderGuide.GOOGLE_STEPS))
            } else {
                val keyCard = createGroupCard(pal)
                keyCard.addView(
                    createKeyValueRow(
                        "Key on comma",
                        if (state.hasGoogleKey) "Saved" else "Not saved",
                        pal
                    )
                )
                googleContent.addView(keyCard)
                if (ProviderGuide.panelFor(state.hasGoogleKey) ==
                    ProviderGuide.Panel.USAGE
                ) {
                    if (state.hasAnyBudget) {
                        googleContent.addView(
                            createSectionHeader("USAGE — ON THE COMMA", pal)
                        )
                        val usageCard = createGroupCard(pal)
                        val usageRows = listOfNotNull(
                            state.mapUsage?.let {
                                Triple("Map tiles", it.label(), it.monthFraction())
                            },
                            state.routesUsage?.let {
                                Triple("Routes (traffic-aware)", it.label(), it.monthFraction())
                            },
                            state.routesEssentials?.let {
                                Triple("Routes (basic)", it.label(), it.monthFraction())
                            },
                            state.placesUsage?.let {
                                Triple("Place searches", it.label(), it.monthFraction())
                            },
                            state.placesSuggestions?.let {
                                Triple("Search suggestions", it.label(), it.monthFraction())
                            },
                            state.mapFailure?.let { Triple("Map problem", it, null) },
                            state.routesFailure?.let { Triple("Routing problem", it, null) }
                        )
                        usageRows.forEachIndexed { idx, (label, value, fraction) ->
                            if (idx > 0) usageCard.addView(createDivider(pal))
                            usageCard.addView(
                                createKeyValueRow(label, value, pal)
                            )
                            if (fraction != null) {
                                usageCard.addView(createUsageBar(fraction, pal))
                            }
                        }
                        googleContent.addView(usageCard)
                        val pct = state.maxBudgetFraction?.let {
                            (it * 100 + 0.5).toInt()
                        }
                        googleContent.addView(
                            noteView(
                                if (state.budgetSwitchDue()) {
                                    "A Google free limit is at " +
                                        "${pct ?: 100}% — the app and this " +
                                        "comma have switched to Mapbox " +
                                        "until the counters reset."
                                } else {
                                    "At 95% of any free limit, the app " +
                                        "and this comma switch to Mapbox " +
                                        "automatically."
                                }
                            )
                        )
                    } else {
                        googleContent.addView(
                            noteView(
                                "Usage shows here once the comma reports it."
                            )
                        )
                    }
                } else {
                    googleContent.addView(noteView(ProviderGuide.GOOGLE_STEPS))
                }
                googleContent.addView(
                    fieldLabel(
                        "Google Maps API key (from your Google Cloud project)"
                    )
                )
                val input = createInputBox(
                    googleDraft ?: "", "AIza…", pal, isPassword = true
                )
                googleContent.addView(input)
                googleStatus?.let { googleContent.addView(statusView(it)) }
                val actions = actionRow()
                actions.addView(createTonalButton("Test key", pal) {
                    val entered = input.text.toString().trim()
                    googleDraft = entered
                    if (entered.isNotEmpty() &&
                        !GoogleNav.isValidGoogleKey(entered)
                    ) {
                        googleStatus =
                            "That doesn't look like a Google API key — " +
                                "20–128 characters: letters, digits, - and _."
                        renderGoogle()
                        return@createTonalButton
                    }
                    googleStatus = "Testing the key with Google…"
                    renderGoogle()
                    Thread {
                        val (code, resp) = NavigationManager()
                            .testGoogleKeySg(baseUrl, entered.ifEmpty { null })
                        val lines = GoogleNav.parseKeyTest(resp)
                        activity.runOnUiThread {
                            if (!dialog.isShowing) return@runOnUiThread
                            googleStatus = when {
                                code == 200 && lines.isNotEmpty() ->
                                    lines.joinToString("\n") { line ->
                                        if (line.ok) {
                                            "${line.label}: works"
                                        } else {
                                            "${line.label}: " +
                                                (line.message ?: "failed")
                                        }
                                    }
                                else -> resp?.optString("error", "")
                                    ?.ifEmpty { null }
                                    ?: "Key test failed — comma answered HTTP $code"
                            }
                            renderGoogle()
                        }
                    }.start()
                }.apply { trailingMargin(this) })
                if (state.hasGoogleKey) {
                    actions.addView(createTonalButton("Clear key", pal) {
                        googleStatus = "Clearing the key on the comma…"
                        renderGoogle()
                        commaPatch(GoogleNav.keyPatch("")) { code, error ->
                            googleStatus = if (code == 200) {
                                "Cleared on the comma."
                            } else {
                                error ?: "Comma answered HTTP $code"
                            }
                            renderGoogle()
                            refreshGoogleSoon()
                        }
                    }.apply { trailingMargin(this) })
                }
                actions.addView(createPrimaryButton("Save key", pal) {
                    val entered = input.text.toString().trim()
                    googleDraft = entered
                    if (entered.isEmpty() ||
                        !GoogleNav.isValidGoogleKey(entered)
                    ) {
                        googleStatus =
                            "Enter a valid Google Maps API key — " +
                                "20–128 characters: letters, digits, - and _."
                        renderGoogle()
                        return@createPrimaryButton
                    }
                    googleStatus = "Saving the key on the comma…"
                    renderGoogle()
                    commaPatch(GoogleNav.keyPatch(entered)) { code, error ->
                        googleStatus = if (code == 200) {
                            "Saved on the comma."
                        } else {
                            error ?: "Comma answered HTTP $code"
                        }
                        renderGoogle()
                        refreshGoogleSoon()
                    }
                })
                googleContent.addView(actions)
            }

            googleContent.addView(
                createSectionHeader("GOOGLE KEY — ON THIS PHONE", pal)
            )
            googleContent.addView(fieldLabel("Google API key (AIza…)"))
            val phoneInput = createInputBox(
                googlePhoneDraft ?: client?.googleApiKey ?: "", "AIza…",
                pal, isPassword = true
            )
            googleContent.addView(phoneInput)
            val phoneActions = actionRow()
            phoneActions.addView(createTonalButton("Save on this phone", pal) {
                val entered = phoneInput.text.toString().trim()
                googlePhoneDraft = entered
                client?.setGoogleApiKey(entered.ifEmpty { null })
                onUpdated()
                Toast.makeText(
                    activity, "Saved on this phone", Toast.LENGTH_SHORT
                ).show()
            })
            googleContent.addView(phoneActions)
            googleContent.addView(
                noteView(
                    "Used for this phone's Google maps, search and speed " +
                        "limits when \"Use phone Google key\" is on in " +
                        "Phone Data."
                )
            )
        }

        // Tabs (Material 3): equal-width labels at a 48dp touch
        // height, the selected tab in accent with a 3dp underline
        // indicator, and a divider under the row.
        fun tabView(
            label: String, target: String
        ): Pair<LinearLayout, Pair<TextView, View>> {
            val labelTv = TextView(activity).apply {
                text = label
                dpText(14f)
                typeface = Typeface.create(
                    "sans-serif-medium", Typeface.NORMAL
                )
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(14), dp(16), dp(11))
            }
            val indicator = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
                )
            }
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                isClickable = true
                isFocusable = true
                addView(labelTv)
                addView(indicator)
                setOnClickListener {
                    if (tab != target) {
                        tab = target
                        styleTabs()
                    }
                }
            }
            return container to (labelTv to indicator)
        }
        val (mapboxTab, mapboxTabParts) =
            tabView("Mapbox", ProviderGuide.TAB_MAPBOX)
        val (googleTab, googleTabParts) =
            tabView("Google", ProviderGuide.TAB_GOOGLE)
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(mapboxTab)
            addView(googleTab)
        }
        body.addView(tabRow)
        body.addView(createDivider(pal))
        body.addView(mapboxContent)
        body.addView(googleContent)

        styleTabs = {
            val mapboxSelected = tab == ProviderGuide.TAB_MAPBOX
            mapboxTabParts.first.setTextColor(
                if (mapboxSelected) pal.accentBlue else pal.textSecondary
            )
            mapboxTabParts.second.setBackgroundColor(
                if (mapboxSelected) {
                    pal.accentBlue
                } else {
                    android.graphics.Color.TRANSPARENT
                }
            )
            val googleSelected = tab == ProviderGuide.TAB_GOOGLE
            googleTabParts.first.setTextColor(
                if (googleSelected) pal.accentBlue else pal.textSecondary
            )
            googleTabParts.second.setBackgroundColor(
                if (googleSelected) {
                    pal.accentBlue
                } else {
                    android.graphics.Color.TRANSPARENT
                }
            )
            mapboxContent.visibility =
                if (mapboxSelected) View.VISIBLE else View.GONE
            googleContent.visibility =
                if (googleSelected) View.VISIBLE else View.GONE
        }

        renderMapbox()
        renderGoogle()
        styleTabs()
        dialog.show()
    }

    /**
     * First-run onboarding (Param, 2026-11-20): two steps, one action
     * each, in plain words. Step 1 gets the phone and the comma onto
     * the same network — the Wi-Fi manager for a shared Wi-Fi, or the
     * comma's own hotspot — so the app can fetch the Bluetooth
     * pairing and Galaxy details by itself. Step 2 explains that the
     * app starts on free OpenStreetMap maps and offers the Map keys
     * page for a Mapbox or Google key. Shown once ([Onboarding]);
     * closing it without finishing still counts as seen, and both
     * pages stay in Settings afterwards.
     */
    fun showOnboardingDialog(
        client: CommaClient?,
        onStatusRefresh: () -> Unit,
        step: Int = 1
    ) {
        val (dialog, body, pal) = createM3Dialog(
            "Welcome to ParamAuto",
            if (step == 1) "Two quick steps and you're set" else "Almost done"
        )
        var advancing = false
        dialog.setOnDismissListener {
            if (!advancing) Onboarding.markDone(activity)
        }

        fun noteView(text: String): TextView = TextView(activity).apply {
            this.text = text
            dpText(13.5f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        fun buttonRow(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }

        fun trailingMargin(view: View) {
            view.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(10), 0) }
        }

        // Progress dots: the standard "where am I" cue beside the
        // STEP X OF 2 header — accent-filled for the current step.
        fun progressRow(current: Int): LinearLayout =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, dp(10))
                for (i in 1..2) {
                    addView(View(activity).apply {
                        background = HudOverlay.makeCardBg(
                            activity,
                            if (i == current) {
                                pal.accentBlue
                            } else {
                                pal.inputBg
                            },
                            5
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            dp(10), dp(10)
                        ).apply {
                            if (i > 1) setMargins(dp(8), 0, 0, 0)
                        }
                    })
                }
            }

        // Onboarding CTAs are full-width and stacked — the primary
        // action first, secondaries below — the consumer standard.
        fun fullWidth(view: View, topMarginDp: Int) {
            view.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(topMarginDp), 0, 0) }
        }

        fun goToStep2() {
            advancing = true
            dialog.dismiss()
            showOnboardingDialog(client, onStatusRefresh, step = 2)
        }

        if (step == 1) {
            body.addView(progressRow(1))
            body.addView(
                createSectionHeader("STEP 1 OF 2 · CONNECT TO YOUR COMMA", pal)
            )
            body.addView(
                noteView(
                    "Your phone and the comma need to be on the same " +
                        "network. When they are, the app fetches the " +
                        "Bluetooth pairing and the Galaxy sign-in by " +
                        "itself — nothing to type."
                )
            )
            val card = createGroupCard(pal)
            card.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.WIFI_LINK,
                    title = "Join the same Wi-Fi",
                    subtitle = "Join the comma to a network your phone is on",
                    pal = pal
                ) {
                    advancing = true
                    dialog.dismiss()
                    showNetworksDialog(client, onStatusRefresh) {
                        showOnboardingDialog(
                            client, onStatusRefresh, step = 2
                        )
                    }
                }
            )
            card.addView(createDivider(pal))
            card.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.WIFI_LINK,
                    title = "Use the comma hotspot",
                    subtitle = "The comma makes its own Wi-Fi — join it here",
                    pal = pal
                ) {
                    body.removeAllViews()
                    body.addView(progressRow(1))
                    body.addView(
                        createSectionHeader(
                            "STEP 1 OF 2 · COMMA HOTSPOT", pal
                        )
                    )
                    body.addView(
                        noteView(
                            "1. Turn on the Galaxy hotspot — on the " +
                                "comma's screen, or in Wi-Fi networks " +
                                "here (it's at the bottom of that page).\n" +
                                "2. On this phone, open Wi-Fi settings " +
                                "and join the comma's network — its name " +
                                "looks like TheGalaxy-XXXX.\n" +
                                "3. Come back here and tap Continue."
                        )
                    )
                    body.addView(
                        createPrimaryButton("Continue", pal) { goToStep2() }
                            .apply { fullWidth(this, 14) }
                    )
                    val row = buttonRow()
                    row.addView(createTonalButton("Open Wi-Fi settings", pal) {
                        try {
                            activity.startActivity(
                                android.content.Intent(
                                    android.provider.Settings
                                        .ACTION_WIFI_SETTINGS
                                )
                            )
                        } catch (e: Exception) {
                            Toast.makeText(
                                activity,
                                "Open your phone's Wi-Fi settings and " +
                                    "join the comma's network",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }.apply { trailingMargin(this) })
                    row.addView(createTonalButton("Back", pal) {
                        advancing = true
                        dialog.dismiss()
                        showOnboardingDialog(
                            client, onStatusRefresh, step = 1
                        )
                    })
                    body.addView(row)
                }
            )
            body.addView(card)
            body.addView(createTonalButton("Skip setup", pal) {
                Onboarding.markDone(activity)
                advancing = true
                dialog.dismiss()
            }.apply { fullWidth(this, 14) })
        } else {
            body.addView(progressRow(2))
            body.addView(
                createSectionHeader("STEP 2 OF 2 · MAP KEYS", pal)
            )
            body.addView(
                noteView(
                    "Right now your maps come from OpenStreetMap — " +
                        "free, built in, no key needed. If you'd like " +
                        "richer maps, place search and speed limits, add " +
                        "a free Mapbox or Google key. You can do this " +
                        "any time in Settings → Map keys & usage."
                )
            )
            body.addView(createPrimaryButton("Add a map key", pal) {
                Onboarding.markDone(activity)
                advancing = true
                dialog.dismiss()
                showMapKeysDialog(client, onStatusRefresh)
            }.apply { fullWidth(this, 14) })
            body.addView(createTonalButton("Keep OpenStreetMap", pal) {
                Onboarding.markDone(activity)
                advancing = true
                dialog.dismiss()
                Toast.makeText(
                    activity,
                    "You're set — everything else lives in Settings",
                    Toast.LENGTH_LONG
                ).show()
            }.apply { fullWidth(this, 10) })
        }
        dialog.show()
    }

    /**
     * Comma settings — the section list (Param, 2026-11-20): the
     * Galaxy settings pages, grouped the way the app's Settings
     * menu groups everything else. Each section opens as ONE
     * combined visual scene over all its pages (Addendum 152);
     * a one-page section opens that page's visual editor.
     */
    fun showCommaSettingsListDialog(client: CommaClient?, onUpdated: () -> Unit) {
        val (dialog, body, pal) = createM3Dialog(
            "Comma settings",
            "StarPilot settings from Galaxy — saved on the comma"
        )
        val intro = TextView(activity).apply {
            text = "Changes go through the comma's own preview and " +
                "confirm, most need the car parked, and settings need " +
                "a local or Bluetooth connection."
            dpText(13.5f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        body.addView(intro)
        for ((section, entries) in GalaxySettings.SECTIONS) {
            body.addView(createSectionHeader(section, pal))
            val card = createGroupCard(pal)
            val only = entries.singleOrNull()
            card.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.TUNE,
                    title = only?.title ?: "All in one scene",
                    subtitle = only?.blurb
                        ?: entries.joinToString(" · ") { it.title },
                    pal = pal
                ) {
                    dialog.dismiss()
                    showVisualSettingsSectionDialog(
                        client, section, entries, onUpdated
                    )
                }
            )
            body.addView(card)
        }
        dialog.show()
    }

    /**
     * One Galaxy settings page as a native editor. Rows render by
     * the web UI's own control rules ([GalaxySettings.control]):
     * subpage links, switches, dropdowns, numeric steppers (with the
     * lane-strength 1–10 scale and Auto where the comma offers them),
     * action buttons and readouts. Value edits preview + confirm in
     * one go like the web UI; actions and resets show the comma's own
     * question first. Every failure shows the comma's error text, and
     * a stale view (they live 60 s) refreshes the page instead of
     * failing silently.
     */
    fun showSettingsPageDialog(
        client: CommaClient?,
        pageId: String,
        fallbackTitle: String,
        onUpdated: () -> Unit
    ) {
        val (dialog, body, pal) = createM3Dialog(
            fallbackTitle,
            "Comma setting — saved on the comma"
        )
        val baseUrl = SgGalaxyClient.baseUrlFor(
            client?.getEffectiveNavigationEndpoint() ?: "192.168.68.9"
        )
        val sgClient = SgGalaxyClient(baseUrl)
        var currentPage: GalaxySettings.Page? = null

        val statusLine = TextView(activity).apply {
            dpText(13f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(12), dp(4), 0)
        }
        body.addView(statusLine)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(content)

        fun noteView(text: String): TextView = TextView(activity).apply {
            this.text = text
            dpText(13.5f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        fun errorText(resp: JSONObject?, code: Int): String {
            val server = resp?.optString("error", "")?.ifEmpty { null }
            return when {
                code == 401 ->
                    (server ?: "Sign in to Galaxy") +
                        " — settings need a local or Bluetooth connection"
                server != null -> server
                code == -1 || code == 0 ->
                    "The comma didn't answer — check the connection"
                else -> "Comma answered HTTP $code"
            }
        }

        lateinit var render: (GalaxySettings.Page) -> Unit

        fun refresh(clearStatus: Boolean) {
            Thread {
                val (code, resp) = sgClient.fetchSettingsPage(pageId)
                val page = GalaxySettings.parsePage(resp)
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (code == 200 && page != null) {
                        if (clearStatus) statusLine.text = ""
                        render(page)
                    } else if (currentPage == null) {
                        content.removeAllViews()
                        content.addView(
                            noteView(
                                "Couldn't load this page from the comma. " +
                                    errorText(resp, code)
                            )
                        )
                    }
                }
            }.start()
        }

        fun confirmIntent(intent: String) {
            statusLine.text = "Saving…"
            Thread {
                val (code, resp) = sgClient.postSettingsConfirm(
                    GalaxySettings.confirmBody(intent, pageId)
                )
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (code == 200 &&
                        resp?.optBoolean("saved", false) == true
                    ) {
                        statusLine.text = ""
                        Toast.makeText(
                            activity, "Saved on the comma",
                            Toast.LENGTH_SHORT
                        ).show()
                        onUpdated()
                        val fresh = GalaxySettings.parsePage(
                            resp.optJSONObject("page")
                        )
                        if (fresh != null) {
                            render(fresh)
                        } else {
                            refresh(clearStatus = true)
                        }
                    } else {
                        statusLine.text = errorText(resp, code)
                        if (code == 409) {
                            refresh(clearStatus = false)
                        } else {
                            currentPage?.let { render(it) }
                        }
                    }
                }
            }.start()
        }

        fun previewFailed(resp: JSONObject?, code: Int) {
            statusLine.text = errorText(resp, code)
            if (code == 409) {
                refresh(clearStatus = false)
            } else {
                currentPage?.let { render(it) }
            }
        }

        fun askQuestion(
            rowLabel: String,
            question: String?,
            intent: String
        ) {
            showSheetConfirm(
                title = rowLabel,
                message = question?.ifEmpty { null } ?: "Apply this change?",
                positiveLabel = "Confirm",
                onNegative = { currentPage?.let { render(it) } },
                onPositive = { confirmIntent(intent) }
            )
        }

        fun commitValue(index: Int, value: Any) {
            val page = currentPage ?: return
            statusLine.text = "Saving…"
            Thread {
                val (code, resp) = sgClient.postSettingsPreview(
                    GalaxySettings.previewValueBody(page.view, index, value)
                )
                val intent = resp?.optString("intent", "")?.ifEmpty { null }
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (code == 200 && intent != null) {
                        confirmIntent(intent)
                    } else {
                        previewFailed(resp, code)
                    }
                }
            }.start()
        }

        fun reviewAction(index: Int, row: GalaxySettings.Row) {
            val page = currentPage ?: return
            statusLine.text = "Asking the comma…"
            Thread {
                val (code, resp) = sgClient.postSettingsPreview(
                    GalaxySettings.previewDirectionBody(
                        page.view, index, if (row.confirm) 0 else 1
                    )
                )
                val intent = resp?.optString("intent", "")?.ifEmpty { null }
                val question = resp?.optString("question", "")
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (code == 200 && intent != null) {
                        statusLine.text = ""
                        askQuestion(row.label, question, intent)
                    } else {
                        previewFailed(resp, code)
                    }
                }
            }.start()
        }

        fun resetDefault(index: Int, row: GalaxySettings.Row) {
            val page = currentPage ?: return
            statusLine.text = "Asking the comma…"
            Thread {
                val (code, resp) = sgClient.postSettingsResetDefault(
                    GalaxySettings.resetDefaultBody(page.view, index)
                )
                val intent = resp?.optString("intent", "")?.ifEmpty { null }
                val question = resp?.optString("question", "")
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (code == 200 && intent != null) {
                        statusLine.text = ""
                        askQuestion(
                            "Reset ${row.label}", question, intent
                        )
                    } else {
                        previewFailed(resp, code)
                    }
                }
            }.start()
        }

        fun pickFromList(
            title: String,
            options: List<String>,
            current: String,
            onPick: (String) -> Unit
        ) {
            showSheetListPicker(title, options, current, onPick = onPick)
        }

        // Galaxy rows carry no icons; these mirror the app's row
        // typography and badge/switch styling without the icon tile.
        fun textCol(label: String, reason: String): LinearLayout =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                addView(TextView(activity).apply {
                    text = label
                    dpText(15f)
                    typeface = Typeface.create(
                        "sans-serif-medium", Typeface.NORMAL
                    )
                    setTextColor(pal.textPrimary)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (reason.isNotEmpty()) {
                    addView(TextView(activity).apply {
                        text = reason
                        dpText(12.5f)
                        setTextColor(pal.textSecondary)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(1), 0, 0)
                    })
                }
            }

        fun badgeView(text: String, active: Boolean): TextView =
            TextView(activity).apply {
                this.text = text
                dpText(11.5f)
                typeface = Typeface.create(
                    "sans-serif-medium", Typeface.BOLD
                )
                setTextColor(
                    if (active) pal.accentGreen else pal.textSecondary
                )
                background = HudOverlay.makeCardBg(
                    activity,
                    if (active) pal.accentGreenTonalBg else pal.inputBg,
                    11
                )
                setPadding(dp(9), dp(3), dp(9), dp(3))
            }

        fun plainRow(
            label: String,
            reason: String,
            badgeText: String?,
            badgeActive: Boolean,
            chevron: Boolean,
            onClick: () -> Unit
        ): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(12), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(textCol(label, reason))
            if (!badgeText.isNullOrEmpty()) {
                addView(badgeView(badgeText, badgeActive).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(6), 0, dp(4), 0) }
                })
            }
            if (chevron) {
                addView(TextView(activity).apply {
                    text = "›"
                    dpText(19f)
                    setTextColor(pal.textSecondary)
                    setPadding(dp(4), 0, dp(2), 0)
                })
            }
        }

        fun plainSwitchRow(
            label: String,
            reason: String,
            checked: Boolean,
            onToggle: (Boolean) -> Unit
        ): LinearLayout {
            val toggle = Switch(activity).apply {
                isChecked = checked
                isClickable = false
                isFocusable = false
                trackTintList = android.content.res.ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(pal.accentBlue, pal.divider)
                )
                thumbTintList = android.content.res.ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(
                        android.graphics.Color.WHITE, pal.textSecondary
                    )
                )
                setOnCheckedChangeListener { _, now -> onToggle(now) }
            }
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(11), dp(12), dp(11))
                isClickable = true
                isFocusable = true
                setOnClickListener { toggle.isChecked = !toggle.isChecked }
                addView(textCol(label, reason))
                addView(toggle)
            }
        }

        fun sliderCard(
            index: Int,
            rawRow: GalaxySettings.Row,
            row: GalaxySettings.Row
        ): LinearLayout {
            val bounds = GalaxySettings.numericBounds(row)!!
            val hasAuto = row.choices.contains("Auto")
            val numericMax = GalaxySettings.snapNumeric(
                bounds.second, bounds.first, bounds.second, bounds.third
            )
            val isLane = rawRow.displayScale == "lane-strength"

            fun commitDisplay(displayValue: Double) {
                if (isLane) {
                    val saved = GalaxySettings.laneStrengthSaved(
                        displayValue, rawRow.minimum, rawRow.maximum
                    )
                    if (saved == null) {
                        statusLine.text = "Pick a value from 1 to 10"
                    } else {
                        commitValue(index, saved)
                    }
                } else {
                    commitValue(index, displayValue)
                }
            }

            fun stepBy(dir: Int) {
                if (!row.available) return
                if (row.value == "Auto") {
                    if (dir < 0) {
                        commitValue(index, numericMax)
                    } else {
                        statusLine.text = "Already Auto"
                    }
                    return
                }
                val current = row.value.toDoubleOrNull() ?: return
                if (dir > 0 && hasAuto && current >= numericMax) {
                    commitValue(index, "Auto")
                    return
                }
                val target = GalaxySettings.snapNumeric(
                    current + dir * bounds.third,
                    bounds.first, bounds.second, bounds.third
                )
                if (target == current) {
                    statusLine.text = if (dir > 0) {
                        "Already at the maximum"
                    } else {
                        "Already at the minimum"
                    }
                    return
                }
                commitDisplay(target)
            }

            fun promptTyped() {
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, 0)
                }
                val input = createInputBox(
                    if (row.value == "Auto") "" else row.value,
                    "Value", pal
                )
                input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                container.addView(input)
                showSheetConfirm(
                    title = row.label,
                    message = null,
                    positiveLabel = "Save",
                    content = container,
                    onPositive = {
                        val text = input.text.toString().trim()
                        when {
                            hasAuto && text.equals("Auto", true) ->
                                commitValue(index, "Auto")
                            else -> {
                                val parsed = text.toDoubleOrNull()
                                if (parsed == null) {
                                    statusLine.text = "Enter a number" +
                                        if (hasAuto) " — or Auto" else ""
                                } else if (isLane) {
                                    commitDisplay(
                                        parsed.coerceIn(1.0, 10.0)
                                    )
                                } else {
                                    commitValue(
                                        index,
                                        GalaxySettings.snapNumeric(
                                            parsed, bounds.first,
                                            bounds.second, bounds.third
                                        )
                                    )
                                }
                            }
                        }
                    }
                )
            }

            val card = createGroupCard(pal)
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), 0)
                addView(textCol(row.label, ""))
                addView(TextView(activity).apply {
                    text = GalaxySettings.valueWithUnit(row)
                    dpText(15f)
                    typeface = Typeface.create(
                        "sans-serif-medium", Typeface.NORMAL
                    )
                    setTextColor(pal.accentBlue)
                    isClickable = row.available
                    setOnClickListener { if (row.available) promptTyped() }
                })
            }
            card.addView(header)
            if (row.reason.isNotEmpty()) {
                card.addView(TextView(activity).apply {
                    text = row.reason
                    dpText(12.5f)
                    setTextColor(pal.textSecondary)
                    setPadding(dp(16), dp(2), dp(16), 0)
                })
            }
            val stepper = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(12))
                addView(createTonalButton("−", pal) { stepBy(-1) }.apply {
                    isEnabled = row.available
                })
                addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, 1, 1f
                    )
                })
                if (hasAuto && row.value != "Auto") {
                    addView(
                        createTonalButton("Auto", pal) {
                            commitValue(index, "Auto")
                        }.apply {
                            isEnabled = row.available
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { setMargins(0, 0, dp(10), 0) }
                        }
                    )
                }
                addView(createTonalButton("+", pal) { stepBy(1) }.apply {
                    isEnabled = row.available
                })
            }
            card.addView(stepper)
            if (!row.available) card.alpha = 0.55f
            return card
        }

        render = { page ->
            currentPage = page
            content.removeAllViews()
            if (page.subtitle.isNotEmpty()) {
                content.addView(noteView(page.subtitle))
            }
            if (!page.parked) {
                content.addView(
                    noteView(
                        "You're on a drive — most settings lock until " +
                            "you park. You can look; the comma refuses " +
                            "changes in its own words."
                    )
                )
            }
            var card: LinearLayout? = null
            var cardRows = 0
            fun addSimple(view: View) {
                if (card == null) {
                    val fresh = createGroupCard(pal)
                    content.addView(fresh)
                    card = fresh
                    cardRows = 0
                }
                if (cardRows > 0) card!!.addView(createDivider(pal))
                card!!.addView(view)
                cardRows++
            }
            fun addResetRow(index: Int, row: GalaxySettings.Row) {
                val defaultText = row.defaultValue?.let {
                    GalaxySettings.valueWithUnit(row.copy(value = it))
                }
                addSimple(
                    plainRow(
                        label = "Reset to default",
                        reason = if (defaultText != null) {
                            "${row.label} · default $defaultText"
                        } else {
                            row.label
                        },
                        badgeText = null,
                        badgeActive = false,
                        chevron = false
                    ) { resetDefault(index, row) }
                )
            }

            page.rows.forEachIndexed { index, rawRow ->
                val row = GalaxySettings.presented(rawRow)
                when (GalaxySettings.control(row)) {
                    GalaxySettings.Control.GROUP -> addSimple(
                        plainRow(
                            row.label, row.reason,
                            row.value.ifEmpty { null },
                            badgeActive = false, chevron = true
                        ) {
                            showSettingsPageDialog(
                                client, rawRow.page, rawRow.label,
                                onUpdated
                            )
                        }
                    )
                    GalaxySettings.Control.SWITCH ->
                        if (row.available) {
                            addSimple(
                                plainSwitchRow(
                                    row.label, row.reason,
                                    row.value == "On"
                                ) { checked ->
                                    commitValue(
                                        index,
                                        if (checked) "On" else "Off"
                                    )
                                }
                            )
                            if (rawRow.resetAvailable &&
                                !GalaxySettings.isAtDefault(row)
                            ) {
                                addResetRow(index, row)
                            }
                        } else {
                            addSimple(
                                plainRow(
                                    row.label, row.reason,
                                    if (row.value == "On") {
                                        "On"
                                    } else {
                                        "Off"
                                    },
                                    badgeActive = row.value == "On",
                                    chevron = false
                                ) {
                                    if (row.reason.isNotEmpty()) {
                                        statusLine.text = row.reason
                                    }
                                }.apply { alpha = 0.55f }
                            )
                        }
                    GalaxySettings.Control.SELECT ->
                        if (row.available) {
                            addSimple(
                                plainRow(
                                    row.label, row.reason, row.value,
                                    badgeActive = true, chevron = true
                                ) {
                                    pickFromList(
                                        row.label, row.choices, row.value
                                    ) { picked ->
                                        commitValue(index, picked)
                                    }
                                }
                            )
                            if (rawRow.resetAvailable &&
                                !GalaxySettings.isAtDefault(row)
                            ) {
                                addResetRow(index, row)
                            }
                        } else {
                            addSimple(
                                plainRow(
                                    row.label, row.reason, row.value,
                                    badgeActive = false, chevron = false
                                ) {
                                    if (row.reason.isNotEmpty()) {
                                        statusLine.text = row.reason
                                    }
                                }.apply { alpha = 0.55f }
                            )
                        }
                    GalaxySettings.Control.SLIDER -> {
                        card = null
                        content.addView(sliderCard(index, rawRow, row))
                        if (rawRow.resetAvailable &&
                            !GalaxySettings.isAtDefault(row)
                        ) {
                            addResetRow(index, row)
                        }
                    }
                    GalaxySettings.Control.ACTION -> {
                        val container = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(16), dp(11), dp(12), dp(11))
                            addView(textCol(row.label, row.reason))
                            addView(
                                createTonalButton(
                                    GalaxySettings.actionLabel(row), pal
                                ) {
                                    reviewAction(index, row)
                                }.apply { isEnabled = row.available }
                            )
                            if (!row.available) alpha = 0.55f
                        }
                        addSimple(container)
                    }
                    GalaxySettings.Control.READOUT -> addSimple(
                        plainRow(
                            row.label, row.reason,
                            GalaxySettings.valueWithUnit(row)
                                .ifEmpty { null },
                            badgeActive = false, chevron = false
                        ) {
                            if (row.reason.isNotEmpty()) {
                                statusLine.text = row.reason
                            }
                        }
                    )
                }
            }
            if (page.rows.isEmpty()) {
                content.addView(
                    noteView("This page has no settings to show.")
                )
            }
        }

        content.addView(noteView("Loading settings…"))
        refresh(clearStatus = true)
        dialog.show()
    }

    /**
     * One Galaxy settings page in VISUAL form (Addendum 148, Param
     * 2026-11-26: "Turn comma settings into visual format"). A live
     * road scene ([RoadSceneView]) at the top reflects the page's
     * real values — following gap, lane-centering strength, curve
     * bend, speed sign — and every row renders as a visual card:
     * sliders as gauges with steppers, selects as choice chips,
     * switches as big toggles, sub-pages as scene nav cards. The
     * plumbing is the classic editor's, unchanged: same fetch, same
     * preview + confirm per edit in one session, same comma
     * questions for actions and resets, same error text. A "Classic
     * view" footer reopens the row editor for the same page.
     */
    fun showVisualSettingsPageDialog(
        client: CommaClient?,
        pageId: String,
        fallbackTitle: String,
        onUpdated: () -> Unit
    ) {
        val entry = GalaxySettings.PageEntry(pageId, fallbackTitle, "")
        showVisualSettingsCombinedDialog(
            client, listOf(entry), fallbackTitle,
            classicEntry = entry, onUpdated = onUpdated
        )
    }

    /**
     * A whole settings SECTION as ONE combined visual scene
     * (Addendum 152, Param 2026-11-27: "Instead of one image per
     * setting. Combined multiple settings into images"). The
     * section's pages share a single dialog and a single road
     * scene drawn from the union of all their rows; the cards stay
     * grouped under a subhead per page, and each card still saves
     * through its own page's preview + confirm. A one-page section
     * behaves exactly like that page's own visual dialog, Classic
     * view footer included.
     */
    fun showVisualSettingsSectionDialog(
        client: CommaClient?,
        section: String,
        entries: List<GalaxySettings.PageEntry>,
        onUpdated: () -> Unit
    ) {
        val title = section.split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { c -> c.uppercaseChar() }
        }
        showVisualSettingsCombinedDialog(
            client, entries, title,
            classicEntry = entries.singleOrNull(),
            onUpdated = onUpdated
        )
    }

    /**
     * The combined visual editor behind both entry points above:
     * one [RoadSceneView] over the union of [entries]' rows, the
     * pages' cards grouped under per-page subheads, and per-page
     * preview + confirm plumbing inside one Galaxy session.
     * [classicEntry], when given, adds the "Classic view" footer
     * reopening that page's row editor; a multi-page section has
     * no single classic editor, so it gets no footer.
     */
    private fun showVisualSettingsCombinedDialog(
        client: CommaClient?,
        entries: List<GalaxySettings.PageEntry>,
        dialogTitle: String,
        classicEntry: GalaxySettings.PageEntry?,
        onUpdated: () -> Unit
    ) {
        val (dialog, body, pal) = createM3Dialog(
            dialogTitle,
            "Visual settings — saved on the comma"
        )
        val baseUrl = SgGalaxyClient.baseUrlFor(
            client?.getEffectiveNavigationEndpoint() ?: "192.168.68.9"
        )
        val sgClient = SgGalaxyClient(baseUrl)
        val pages = LinkedHashMap<String, GalaxySettings.Page>()
        val loadErrors = HashMap<String, String>()

        val statusLine = TextView(activity).apply {
            dpText(13f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(12), dp(4), 0)
        }
        body.addView(statusLine)
        val sceneView = RoadSceneView(activity).apply {
            accentColor = pal.accentBlue
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(168)
            ).apply { setMargins(0, dp(10), 0, dp(4)) }
        }
        body.addView(sceneView)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(content)
        if (classicEntry != null) {
            body.addView(TextView(activity).apply {
                text = "Classic view"
                dpText(13.5f)
                setTextColor(pal.accentBlue)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(14), dp(4), dp(2))
                setOnClickListener {
                    dialog.dismiss()
                    showSettingsPageDialog(
                        client, classicEntry.id, classicEntry.title,
                        onUpdated
                    )
                }
            })
        }

        fun noteView(text: String): TextView = TextView(activity).apply {
            this.text = text
            dpText(13.5f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        fun errorText(resp: JSONObject?, code: Int): String {
            val server = resp?.optString("error", "")?.ifEmpty { null }
            return when {
                code == 401 ->
                    (server ?: "Sign in to Galaxy") +
                        " — settings need a local or Bluetooth connection"
                server != null -> server
                code == -1 || code == 0 ->
                    "The comma didn't answer — check the connection"
                else -> "Comma answered HTTP $code"
            }
        }

        lateinit var render: () -> Unit

        fun refresh(pageId: String, clearStatus: Boolean) {
            Thread {
                val (code, resp) = sgClient.fetchSettingsPage(pageId)
                val page = GalaxySettings.parsePage(resp)
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (code == 200 && page != null) {
                        if (clearStatus) statusLine.text = ""
                        pages[pageId] = page
                        loadErrors.remove(pageId)
                        render()
                    } else if (!pages.containsKey(pageId)) {
                        loadErrors[pageId] = errorText(resp, code)
                        render()
                    }
                }
            }.start()
        }

        /** First load: every page of the section, fetched one
         *  after another on a single thread so they all share the
         *  one Galaxy session. A page that fails keeps a visible
         *  error note under its subhead while the others render. */
        fun loadAll() {
            Thread {
                val loaded = LinkedHashMap<String, GalaxySettings.Page>()
                val failed = HashMap<String, String>()
                for (entry in entries) {
                    val (code, resp) = sgClient.fetchSettingsPage(entry.id)
                    val page = GalaxySettings.parsePage(resp)
                    if (code == 200 && page != null) {
                        loaded[entry.id] = page
                    } else {
                        failed[entry.id] = errorText(resp, code)
                    }
                }
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    pages.putAll(loaded)
                    loadErrors.putAll(failed)
                    render()
                }
            }.start()
        }

        fun confirmIntent(pageId: String, intent: String) {
            statusLine.text = "Saving…"
            Thread {
                val (code, resp) = sgClient.postSettingsConfirm(
                    GalaxySettings.confirmBody(intent, pageId)
                )
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (code == 200 &&
                        resp?.optBoolean("saved", false) == true
                    ) {
                        statusLine.text = ""
                        Toast.makeText(
                            activity, "Saved on the comma",
                            Toast.LENGTH_SHORT
                        ).show()
                        onUpdated()
                        val fresh = GalaxySettings.parsePage(
                            resp.optJSONObject("page")
                        )
                        if (fresh != null) {
                            pages[pageId] = fresh
                            render()
                        } else {
                            refresh(pageId, clearStatus = true)
                        }
                    } else {
                        statusLine.text = errorText(resp, code)
                        if (code == 409) {
                            refresh(pageId, clearStatus = false)
                        } else {
                            render()
                        }
                    }
                }
            }.start()
        }

        fun previewFailed(pageId: String, resp: JSONObject?, code: Int) {
            statusLine.text = errorText(resp, code)
            if (code == 409) {
                refresh(pageId, clearStatus = false)
            } else {
                render()
            }
        }

        fun askQuestion(
            pageId: String,
            rowLabel: String,
            question: String?,
            intent: String
        ) {
            showSheetConfirm(
                title = rowLabel,
                message = question?.ifEmpty { null } ?: "Apply this change?",
                positiveLabel = "Confirm",
                onNegative = { render() },
                onPositive = { confirmIntent(pageId, intent) }
            )
        }

        fun commitValue(pageId: String, index: Int, value: Any) {
            val page = pages[pageId] ?: return
            statusLine.text = "Saving…"
            Thread {
                val (code, resp) = sgClient.postSettingsPreview(
                    GalaxySettings.previewValueBody(page.view, index, value)
                )
                val intent = resp?.optString("intent", "")?.ifEmpty { null }
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (code == 200 && intent != null) {
                        confirmIntent(pageId, intent)
                    } else {
                        previewFailed(pageId, resp, code)
                    }
                }
            }.start()
        }

        fun reviewAction(pageId: String, index: Int, row: GalaxySettings.Row) {
            val page = pages[pageId] ?: return
            statusLine.text = "Asking the comma…"
            Thread {
                val (code, resp) = sgClient.postSettingsPreview(
                    GalaxySettings.previewDirectionBody(
                        page.view, index, if (row.confirm) 0 else 1
                    )
                )
                val intent = resp?.optString("intent", "")?.ifEmpty { null }
                val question = resp?.optString("question", "")
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (code == 200 && intent != null) {
                        statusLine.text = ""
                        askQuestion(pageId, row.label, question, intent)
                    } else {
                        previewFailed(pageId, resp, code)
                    }
                }
            }.start()
        }

        fun resetDefault(pageId: String, index: Int, row: GalaxySettings.Row) {
            val page = pages[pageId] ?: return
            statusLine.text = "Asking the comma…"
            Thread {
                val (code, resp) = sgClient.postSettingsResetDefault(
                    GalaxySettings.resetDefaultBody(page.view, index)
                )
                val intent = resp?.optString("intent", "")?.ifEmpty { null }
                val question = resp?.optString("question", "")
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (code == 200 && intent != null) {
                        statusLine.text = ""
                        askQuestion(
                            pageId, "Reset ${row.label}", question, intent
                        )
                    } else {
                        previewFailed(pageId, resp, code)
                    }
                }
            }.start()
        }

        /** The presented rows of every loaded page, in section
         *  order — the union the one combined scene is drawn from
         *  (Addendum 152). */
        fun combinedRows(): List<GalaxySettings.Row> =
            entries.flatMap { entry ->
                pages[entry.id]?.rows?.map {
                    GalaxySettings.presented(it)
                } ?: emptyList()
            }

        /** Recompute the scene with one row's value swapped in, so
         *  the road reacts the moment a control is touched — before
         *  the comma's confirm comes back. */
        fun previewScene(pageId: String, changedIndex: Int, newValue: String) {
            val rows = entries.flatMap { entry ->
                val page = pages[entry.id]
                    ?: return@flatMap emptyList<GalaxySettings.Row>()
                page.rows.mapIndexed { i, r ->
                    val presented = GalaxySettings.presented(r)
                    if (entry.id == pageId && i == changedIndex) {
                        presented.copy(value = newValue)
                    } else {
                        presented
                    }
                }
            }
            sceneView.update(SettingsScene.from(rows))
        }

        fun cardShell(): LinearLayout = createGroupCard(pal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(5), 0, dp(5)) }
        }

        fun cardLabel(row: GalaxySettings.Row): TextView =
            TextView(activity).apply {
                text = row.label
                dpText(15f)
                typeface = Typeface.create(
                    "sans-serif-medium", Typeface.NORMAL
                )
                setTextColor(pal.textPrimary)
                setPadding(dp(16), dp(12), dp(16), 0)
            }

        fun cardReason(row: GalaxySettings.Row): TextView =
            TextView(activity).apply {
                text = row.reason
                dpText(12.5f)
                setTextColor(pal.textSecondary)
                setPadding(dp(16), dp(2), dp(16), 0)
            }

        /** One page's cards, rendered into the shared content
         *  column under its subhead. The two-argument helpers below
         *  bind this page's id, so the card code itself is the
         *  single-page editor's, unchanged — every save still goes
         *  through this page's own view, preview and confirm. */
        fun renderPageCards(pageId: String, page: GalaxySettings.Page) {
            fun commitValue(index: Int, value: Any) =
                commitValue(pageId, index, value)
            fun previewScene(index: Int, newValue: String) =
                previewScene(pageId, index, newValue)
            fun reviewAction(index: Int, row: GalaxySettings.Row) =
                reviewAction(pageId, index, row)
            fun resetDefault(index: Int, row: GalaxySettings.Row) =
                resetDefault(pageId, index, row)
            if (page.subtitle.isNotEmpty()) {
                content.addView(noteView(page.subtitle))
            }
            if (!page.parked) {
                content.addView(
                    noteView(
                        "You're on a drive — most settings lock until " +
                            "you park. You can look; the comma refuses " +
                            "changes in its own words."
                    )
                )
            }
            if (page.rows.isEmpty()) {
                content.addView(
                    noteView("This page has no settings to show.")
                )
            }

            fun addResetRow(index: Int, row: GalaxySettings.Row) {
                val defaultText = row.defaultValue?.let {
                    GalaxySettings.valueWithUnit(row.copy(value = it))
                }
                content.addView(TextView(activity).apply {
                    text = "Reset to default" +
                        (defaultText?.let { " · $it" } ?: "")
                    dpText(13f)
                    setTextColor(pal.accentBlue)
                    setPadding(dp(20), dp(2), dp(16), dp(8))
                    setOnClickListener { resetDefault(index, row) }
                })
            }

            page.rows.forEachIndexed { index, rawRow ->
                val row = GalaxySettings.presented(rawRow)
                when (GalaxySettings.control(row)) {
                    GalaxySettings.Control.GROUP -> {
                        val card = cardShell()
                        val inner = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(16), dp(14), dp(12), dp(14))
                            addView(LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                                )
                                addView(TextView(activity).apply {
                                    text = row.label
                                    dpText(15.5f)
                                    typeface = Typeface.create(
                                        "sans-serif-medium", Typeface.NORMAL
                                    )
                                    setTextColor(pal.textPrimary)
                                })
                                if (row.value.isNotEmpty()) {
                                    addView(TextView(activity).apply {
                                        text = row.value
                                        dpText(12.5f)
                                        setTextColor(pal.textSecondary)
                                    })
                                }
                            })
                            addView(TextView(activity).apply {
                                text = "›"
                                dpText(22f)
                                setTextColor(pal.textSecondary)
                            })
                        }
                        inner.setOnClickListener {
                            showVisualSettingsPageDialog(
                                client, rawRow.page, rawRow.label,
                                onUpdated
                            )
                        }
                        card.addView(inner)
                        content.addView(card)
                    }
                    GalaxySettings.Control.SWITCH -> {
                        val card = cardShell()
                        val inner = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(16), dp(10), dp(12), dp(10))
                            addView(LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                                )
                                addView(TextView(activity).apply {
                                    text = row.label
                                    dpText(15.5f)
                                    typeface = Typeface.create(
                                        "sans-serif-medium", Typeface.NORMAL
                                    )
                                    setTextColor(pal.textPrimary)
                                })
                                if (row.reason.isNotEmpty()) {
                                    addView(TextView(activity).apply {
                                        text = row.reason
                                        dpText(12.5f)
                                        setTextColor(pal.textSecondary)
                                        maxLines = 2
                                        ellipsize = TextUtils.TruncateAt.END
                                    })
                                }
                            })
                            val toggle = Switch(activity).apply {
                                isChecked = row.value == "On"
                                isEnabled = row.available
                                scaleX = 1.25f
                                scaleY = 1.25f
                            }
                            toggle.setOnCheckedChangeListener { _, checked ->
                                previewScene(
                                    index, if (checked) "On" else "Off"
                                )
                                commitValue(
                                    index, if (checked) "On" else "Off"
                                )
                            }
                            addView(toggle)
                        }
                        if (!row.available) card.alpha = 0.55f
                        card.addView(inner)
                        content.addView(card)
                        if (row.available && rawRow.resetAvailable &&
                            !GalaxySettings.isAtDefault(row)
                        ) {
                            addResetRow(index, row)
                        }
                    }
                    GalaxySettings.Control.SELECT -> {
                        if (!row.available) {
                            val card = cardShell()
                            card.addView(cardLabel(row))
                            card.addView(TextView(activity).apply {
                                text = row.value
                                dpText(19f)
                                typeface = Typeface.create(
                                    "sans-serif-medium", Typeface.NORMAL
                                )
                                setTextColor(pal.textSecondary)
                                setPadding(dp(16), dp(2), dp(16), dp(12))
                            })
                            card.alpha = 0.55f
                            content.addView(card)
                        } else if (row.choices.size <= 8) {
                            val card = cardShell()
                            card.addView(cardLabel(row))
                            if (row.reason.isNotEmpty()) {
                                card.addView(cardReason(row))
                            }
                            val chipRow = LinearLayout(activity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(dp(12), dp(10), dp(12), dp(12))
                            }
                            for (choice in row.choices) {
                                val selected = choice == row.value
                                chipRow.addView(TextView(activity).apply {
                                    text = choice
                                    dpText(14f)
                                    typeface = Typeface.create(
                                        "sans-serif-medium", Typeface.NORMAL
                                    )
                                    setTextColor(
                                        if (selected) {
                                            android.graphics.Color.WHITE
                                        } else {
                                            pal.textPrimary
                                        }
                                    )
                                    setPadding(
                                        dp(14), dp(8), dp(14), dp(8)
                                    )
                                    background = GradientDrawable().apply {
                                        cornerRadius = dp(16).toFloat()
                                        setColor(
                                            if (selected) {
                                                pal.accentBlue
                                            } else {
                                                pal.inputBg
                                            }
                                        )
                                        if (!selected) {
                                            setStroke(dp(1), pal.border)
                                        }
                                    }
                                    layoutParams =
                                        LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                        ).apply {
                                            setMargins(0, 0, dp(8), 0)
                                        }
                                    setOnClickListener {
                                        previewScene(index, choice)
                                        commitValue(index, choice)
                                    }
                                })
                            }
                            card.addView(HorizontalScrollView(activity).apply {
                                isHorizontalScrollBarEnabled = false
                                addView(chipRow)
                            })
                            content.addView(card)
                            if (rawRow.resetAvailable &&
                                !GalaxySettings.isAtDefault(row)
                            ) {
                                addResetRow(index, row)
                            }
                        } else {
                            val card = cardShell()
                            card.addView(cardLabel(row))
                            card.addView(TextView(activity).apply {
                                text = row.value + "  ›"
                                dpText(17f)
                                typeface = Typeface.create(
                                    "sans-serif-medium", Typeface.NORMAL
                                )
                                setTextColor(pal.accentBlue)
                                setPadding(dp(16), dp(2), dp(16), dp(12))
                                setOnClickListener {
                                    showSheetListPicker(
                                        row.label, row.choices, row.value
                                    ) { picked ->
                                        previewScene(index, picked)
                                        commitValue(index, picked)
                                    }
                                }
                            })
                            content.addView(card)
                            if (rawRow.resetAvailable &&
                                !GalaxySettings.isAtDefault(row)
                            ) {
                                addResetRow(index, row)
                            }
                        }
                    }
                    GalaxySettings.Control.SLIDER -> {
                        val bounds = GalaxySettings.numericBounds(row)!!
                        val hasAuto = row.choices.contains("Auto")
                        val numericMax = GalaxySettings.snapNumeric(
                            bounds.second, bounds.first,
                            bounds.second, bounds.third
                        )
                        val isLane = rawRow.displayScale == "lane-strength"
                        val card = cardShell()
                        val gauge = GaugeView(activity).apply {
                            accentColor = pal.accentBlue
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(dp(16), dp(2), dp(16), 0)
                            }
                        }
                        fun gaugeFor(display: Double) {
                            gauge.setValue(
                                (display - bounds.first) /
                                    (bounds.second - bounds.first),
                                ((bounds.second - bounds.first) /
                                    bounds.third).roundToInt()
                            )
                        }
                        fun commitDisplay(displayValue: Double) {
                            if (isLane) {
                                val saved = GalaxySettings.laneStrengthSaved(
                                    displayValue, rawRow.minimum,
                                    rawRow.maximum
                                )
                                if (saved == null) {
                                    statusLine.text =
                                        "Pick a value from 1 to 10"
                                } else {
                                    // The scene reads presented
                                    // (display-scale) values.
                                    previewScene(
                                        index,
                                        GalaxySettings.formatNumber(
                                            displayValue
                                        )
                                    )
                                    commitValue(index, saved)
                                }
                            } else {
                                previewScene(
                                    index,
                                    GalaxySettings.formatNumber(displayValue)
                                )
                                commitValue(index, displayValue)
                            }
                        }
                        fun stepBy(dir: Int) {
                            if (!row.available) return
                            if (row.value == "Auto") {
                                if (dir < 0) {
                                    gaugeFor(numericMax)
                                    commitDisplay(numericMax)
                                } else {
                                    statusLine.text = "Already Auto"
                                }
                                return
                            }
                            val current = row.value.toDoubleOrNull()
                                ?: return
                            if (dir > 0 && hasAuto &&
                                current >= numericMax
                            ) {
                                previewScene(index, "Auto")
                                commitValue(index, "Auto")
                                return
                            }
                            val target = GalaxySettings.snapNumeric(
                                current + dir * bounds.third,
                                bounds.first, bounds.second, bounds.third
                            )
                            if (target == current) {
                                statusLine.text = if (dir > 0) {
                                    "Already at the maximum"
                                } else {
                                    "Already at the minimum"
                                }
                                return
                            }
                            gaugeFor(target)
                            commitDisplay(target)
                        }
                        val header = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(16), dp(12), dp(16), 0)
                            addView(LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                                )
                                addView(TextView(activity).apply {
                                    text = row.label
                                    dpText(15f)
                                    typeface = Typeface.create(
                                        "sans-serif-medium", Typeface.NORMAL
                                    )
                                    setTextColor(pal.textPrimary)
                                })
                            })
                            addView(TextView(activity).apply {
                                text = GalaxySettings.valueWithUnit(row)
                                dpText(19f)
                                typeface = Typeface.create(
                                    "sans-serif-medium", Typeface.NORMAL
                                )
                                setTextColor(pal.accentBlue)
                                isClickable = row.available
                                setOnClickListener {
                                    if (!row.available) {
                                        return@setOnClickListener
                                    }
                                    val container =
                                        LinearLayout(activity).apply {
                                            orientation =
                                                LinearLayout.VERTICAL
                                            setPadding(0, dp(8), 0, 0)
                                        }
                                    val input = createInputBox(
                                        if (row.value == "Auto") {
                                            ""
                                        } else {
                                            row.value
                                        },
                                        "Value", pal
                                    )
                                    input.inputType =
                                        InputType.TYPE_CLASS_NUMBER or
                                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                                        InputType.TYPE_NUMBER_FLAG_SIGNED
                                    container.addView(input)
                                    showSheetConfirm(
                                        title = row.label,
                                        message = null,
                                        positiveLabel = "Save",
                                        content = container,
                                        onPositive = {
                                            val text = input.text
                                                .toString().trim()
                                            when {
                                                hasAuto && text.equals(
                                                    "Auto", true
                                                ) -> {
                                                    previewScene(
                                                        index, "Auto"
                                                    )
                                                    commitValue(
                                                        index, "Auto"
                                                    )
                                                }
                                                else -> {
                                                    val parsed =
                                                        text.toDoubleOrNull()
                                                    if (parsed == null) {
                                                        statusLine.text =
                                                            "Enter a number" +
                                                            if (hasAuto) {
                                                                " — or Auto"
                                                            } else {
                                                                ""
                                                            }
                                                    } else if (isLane) {
                                                        commitDisplay(
                                                            parsed.coerceIn(
                                                                1.0, 10.0
                                                            )
                                                        )
                                                    } else {
                                                        commitDisplay(
                                                            GalaxySettings
                                                                .snapNumeric(
                                                                    parsed,
                                                                    bounds.first,
                                                                    bounds.second,
                                                                    bounds.third
                                                                )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            })
                        }
                        card.addView(header)
                        if (row.reason.isNotEmpty()) {
                            card.addView(cardReason(row))
                        }
                        row.value.toDoubleOrNull()?.let { gaugeFor(it) }
                        card.addView(gauge)
                        val stepper = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(4), dp(12), dp(12))
                            addView(createTonalButton("−", pal) {
                                stepBy(-1)
                            }.apply { isEnabled = row.available })
                            addView(View(activity).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    0, 1, 1f
                                )
                            })
                            if (hasAuto && row.value != "Auto") {
                                addView(
                                    createTonalButton("Auto", pal) {
                                        previewScene(index, "Auto")
                                        commitValue(index, "Auto")
                                    }.apply {
                                        isEnabled = row.available
                                        layoutParams =
                                            LinearLayout.LayoutParams(
                                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                                ViewGroup.LayoutParams.WRAP_CONTENT
                                            ).apply {
                                                setMargins(0, 0, dp(10), 0)
                                            }
                                    }
                                )
                            }
                            addView(createTonalButton("+", pal) {
                                stepBy(1)
                            }.apply { isEnabled = row.available })
                        }
                        card.addView(stepper)
                        if (!row.available) card.alpha = 0.55f
                        content.addView(card)
                        if (rawRow.resetAvailable &&
                            !GalaxySettings.isAtDefault(row)
                        ) {
                            addResetRow(index, row)
                        }
                    }
                    GalaxySettings.Control.ACTION -> {
                        val card = cardShell()
                        val inner = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(16), dp(11), dp(12), dp(11))
                            addView(LinearLayout(activity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                                )
                                addView(TextView(activity).apply {
                                    text = row.label
                                    dpText(15f)
                                    typeface = Typeface.create(
                                        "sans-serif-medium", Typeface.NORMAL
                                    )
                                    setTextColor(pal.textPrimary)
                                })
                                if (row.reason.isNotEmpty()) {
                                    addView(TextView(activity).apply {
                                        text = row.reason
                                        dpText(12.5f)
                                        setTextColor(pal.textSecondary)
                                        maxLines = 2
                                        ellipsize = TextUtils.TruncateAt.END
                                    })
                                }
                            })
                            addView(
                                createTonalButton(
                                    GalaxySettings.actionLabel(row), pal
                                ) {
                                    reviewAction(index, row)
                                }.apply { isEnabled = row.available }
                            )
                        }
                        if (!row.available) card.alpha = 0.55f
                        card.addView(inner)
                        content.addView(card)
                    }
                    GalaxySettings.Control.READOUT -> {
                        val card = cardShell()
                        card.addView(cardLabel(row))
                        card.addView(TextView(activity).apply {
                            text = GalaxySettings.valueWithUnit(row)
                                .ifEmpty { "—" }
                            dpText(21f)
                            typeface = Typeface.create(
                                "sans-serif-medium", Typeface.NORMAL
                            )
                            setTextColor(pal.accentBlue)
                            setPadding(dp(16), dp(2), dp(16), dp(12))
                        })
                        if (row.reason.isNotEmpty()) {
                            card.addView(cardReason(row).apply {
                                setPadding(dp(16), 0, dp(16), dp(10))
                            })
                        }
                        content.addView(card)
                    }
                }
            }
        }

        render = {
            sceneView.update(SettingsScene.from(combinedRows()))
            content.removeAllViews()
            val combined = entries.size > 1
            for (entry in entries) {
                if (combined) {
                    content.addView(createSectionHeader(entry.title, pal))
                }
                val loaded = pages[entry.id]
                if (loaded == null) {
                    content.addView(
                        noteView(
                            loadErrors[entry.id]?.let { err ->
                                "Couldn't load this page from the " +
                                    "comma. " + err
                            } ?: "Loading settings…"
                        )
                    )
                } else {
                    renderPageCards(entry.id, loaded)
                }
            }
        }

        content.addView(noteView("Loading settings…"))
        loadAll()
        dialog.show()
    }

    /**
     * Providers (Addendum 119): the Maps & Directions dropdowns that
     * used to sit inside Phone Data — the comma's map/route sources
     * and this phone's base map, together under Maps & Navigation.
     * A pick is written to the comma through the navigation
     * configure action; success reopens this dialog on the fresh
     * state, failure shows the comma's own error and changes nothing.
     */
    fun showProvidersDialog(client: CommaClient?) {
        val (dialog, body, pal) = createM3Dialog(
            "Providers",
            "Maps & directions sources — the comma's and this phone's"
        )
        val hasMapboxToken = !(client?.mapboxToken ?: NavStateManager.mapboxToken).isNullOrBlank()
        val navState = NavStateManager.commaNavProviders

        fun applyProviderPatch(patch: JSONObject) {
            val endpoint = client?.getEffectiveNavigationEndpoint()
            val baseUrl = SgGalaxyClient.baseUrlFor(endpoint ?: "192.168.68.9")
            Thread {
                val (code, error) = NavigationManager()
                    .configureNavSettingsSg(baseUrl, patch)
                activity.runOnUiThread {
                    if (code == 200) {
                        Toast.makeText(
                            activity, "Saved on the comma", Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                        showProvidersDialog(client)
                    } else {
                        Toast.makeText(
                            activity,
                            error ?: "Comma answered HTTP $code",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }

        fun showDropdown(
            title: String,
            options: List<String>,
            current: String,
            label: (String) -> String,
            onPick: (String) -> Unit
        ) {
            showSheetListPicker(title, options, current, label, onPick)
        }

        fun showProviderDropdown(title: String, current: String, onPick: (String) -> Unit) {
            showDropdown(
                title,
                listOf(GoogleNav.MAPBOX, GoogleNav.GOOGLE),
                current,
                { GoogleNav.providerLabel(it) },
                onPick
            )
        }

        val providerCard = createGroupCard(pal)
        providerCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.MAP,
                title = "Maps",
                subtitle = if (navState != null) {
                    "Map tiles drawn by the comma"
                } else {
                    "Comma hasn't reported map settings yet"
                },
                badgeText = navState?.let { GoogleNav.providerLabel(it.mapProvider) },
                badgeActive = navState != null,
                pal = pal
            ) {
                if (navState == null) {
                    Toast.makeText(
                        activity,
                        "This comma hasn't reported provider settings yet",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    showProviderDropdown("Maps", navState.mapProvider) { picked ->
                        applyProviderPatch(
                            GoogleNav.providerPatch(
                                mapProvider = picked, routeProvider = null
                            )
                        )
                    }
                }
            }
        )
        providerCard.addView(createDivider(pal))
        providerCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.NAVIGATION,
                title = "Directions",
                subtitle = if (navState != null) {
                    "Routes & place search used by the comma"
                } else {
                    "Comma hasn't reported map settings yet"
                },
                badgeText = navState?.let { GoogleNav.providerLabel(it.routeProvider) },
                badgeActive = navState != null,
                pal = pal
            ) {
                if (navState == null) {
                    Toast.makeText(
                        activity,
                        "This comma hasn't reported provider settings yet",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    showProviderDropdown("Directions", navState.routeProvider) { picked ->
                        applyProviderPatch(
                            GoogleNav.providerPatch(
                                mapProvider = null, routeProvider = picked
                            )
                        )
                    }
                }
            }
        )
        providerCard.addView(createDivider(pal))
        // Phone maps (Param, 2026-11-18): directions come from the
        // comma, but the map drawn on THIS phone is the phone's own
        // choice — Google / Mapbox / OSM. The pick is a preference:
        // MapSourceChoice falls back to the next usable source when
        // the chosen one has no key or session yet, so the map never
        // blanks.
        val phoneMapPref = PhoneGoogle.sanitizeMapSource(NavStateManager.phoneMapSource)
        providerCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.MAP,
                title = "Phone maps",
                subtitle = "Base map shown on this phone",
                badgeText = PhoneGoogle.sourceLabel(phoneMapPref),
                badgeActive = true,
                pal = pal
            ) {
                showDropdown(
                    "Phone maps",
                    PhoneGoogle.MAP_SOURCES,
                    phoneMapPref,
                    { PhoneGoogle.sourceLabel(it) }
                ) { picked ->
                    client?.setPhoneMapSource(picked)
                    Toast.makeText(
                        activity,
                        "Phone map: ${PhoneGoogle.sourceLabel(picked)}",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    showProvidersDialog(client)
                }
            }
        )
        body.addView(providerCard)

        // Status (Param, 2026-11-18: "Show status"): which source is
        // actually serving the speed limit and search right now, and
        // where the comma's Google budgets stand against the 95%
        // switch to Mapbox.
        val statusLines = StringBuilder()
        statusLines.append(
            when {
                !NavStateManager.usePhoneSpeedLimit ->
                    "Speed limit: off — comma only"
                NavStateManager.googleBudgetExhausted ->
                    "Speed limit: Mapbox (Google budget at 95%+)"
                PhoneGoogle.effectiveGoogleKey() != null ->
                    "Speed limit: Google (Roads)"
                hasMapboxToken -> "Speed limit: Mapbox (Directions)"
                else -> "Speed limit: none — no phone key saved"
            }
        )
        statusLines.append("\n").append(
            when {
                !NavStateManager.usePhoneSearch -> "Search: comma"
                PhoneGoogle.effectiveGoogleKey() != null -> "Search: Google Places"
                NavStateManager.googleBudgetExhausted &&
                    NavStateManager.usePhoneGoogleKey ->
                    "Search: Mapbox (Google budget at 95%+)"
                hasMapboxToken -> "Search: Mapbox"
                else -> "Search: Photon (OSM)"
            }
        )
        val maxFraction = navState?.maxBudgetFraction
        if (navState != null && maxFraction != null) {
            val pct = (maxFraction * 100 + 0.5).toInt()
            statusLines.append("\n").append(
                if (navState.budgetSwitchDue()) {
                    "Comma Google usage: $pct% of a free limit — switched to Mapbox (95% rule)"
                } else {
                    "Comma Google usage: $pct% of a free limit — switches to Mapbox at 95%"
                }
            )
        }
        val statusView = TextView(activity).apply {
            text = statusLines.toString()
            dpText(12.5f)
            setTextColor(pal.textSecondary)
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        body.addView(statusView)

        if (navState != null && navState.routeProvider == GoogleNav.GOOGLE &&
            !navState.hasGoogleKey
        ) {
            val warn = TextView(activity).apply {
                text = "Google directions start once a Google key is saved on the " +
                    "comma (Settings → Map keys & usage) — until then the comma " +
                    "keeps routing with Mapbox."
                dpText(12.5f)
                setTextColor(pal.textSecondary)
                setPadding(dp(4), dp(8), dp(4), 0)
            }
            body.addView(warn)
        }
        dialog.show()
    }

    fun showPhoneDataDialog(
        client: CommaClient?,
        onThemeChanged: () -> Unit,
        onToggleSimulation: () -> Unit
    ) {
        val (dialog, body, pal) = createM3Dialog(
            "Phone data",
            "Off by default - the comma is the only source until you turn a fallback on. Turning one on explains and asks for exactly the permission it needs."
        )

        // Mapbox key gate (Param, 2026-11-04): "If Mapbox function
        // toggles are on and the Mapbox key is not there — throw error
        // and switch back." A saved ON state with no token saved is
        // switched back off here, before the rows are built, so the
        // switches below start from the corrected state.
        val hasMapboxToken = !(client?.mapboxToken ?: NavStateManager.mapboxToken).isNullOrBlank()
        var revertedAny = false
        if (MapboxKeyGate.mustSwitchBack(NavStateManager.usePhoneMapboxKey, hasMapboxToken)) {
            client?.setUsePhoneMapboxKey(false)
            revertedAny = true
        }
        if (MapboxKeyGate.mustSwitchBack(NavStateManager.usePhoneSpeedLimit, hasMapboxToken)) {
            client?.setUsePhoneSpeedLimit(false)
            NavStateManager.speedLimitMph = 0
            revertedAny = true
        }
        if (MapboxKeyGate.mustSwitchBack(NavStateManager.usePhoneTraffic, hasMapboxToken)) {
            client?.setUsePhoneTraffic(false)
            revertedAny = true
        }
        if (MapboxKeyGate.mustSwitchBack(NavStateManager.usePhoneSignals, hasMapboxToken)) {
            client?.setUsePhoneSignals(false)
            revertedAny = true
        }
        if (revertedAny) {
            Toast.makeText(activity, MapboxKeyGate.ERROR_MESSAGE, Toast.LENGTH_LONG).show()
        }

        // 1. Phone Data Fallbacks
        body.addView(createSectionHeader("PHONE FALLBACKS", pal))
        val card = createGroupCard(pal)
        var speedRow: LinearLayout? = null
        speedRow = createSwitchRow(
            iconKind = SettingsIconView.Kind.SPEED,
            title = "Use phone speed",
            subtitle = "Why: phone GPS speed fills the readout when the comma provides none. Needs Location permission - asked only if you turn this on.",
            initialChecked = NavStateManager.usePhoneSpeed,
            pal = pal,
            subtitleMaxLines = 6
        ) { enabled ->
            if (enabled) {
                gateLocationToggle(speedRow, "Use phone speed", "phone GPS speed fills the speed readout when the comma provides none.") {
                    client?.setUsePhoneSpeed(true)
                    (activity as? MainActivity)?.refreshPhoneLocationRegistration()
                }
            } else {
                client?.setUsePhoneSpeed(false)
                (activity as? MainActivity)?.refreshPhoneLocationRegistration()
            }
        }
        card.addView(speedRow)
        card.addView(createDivider(pal))
        var locationRow: LinearLayout? = null
        locationRow = createSwitchRow(
            iconKind = SettingsIconView.Kind.NAVIGATION,
            title = "Use phone location",
            subtitle = "Why: phone GPS positions the map when the comma has no fix. Needs Location permission - asked only if you turn this on.",
            initialChecked = NavStateManager.usePhoneLocation,
            pal = pal,
            subtitleMaxLines = 6
        ) { enabled ->
            if (enabled) {
                gateLocationToggle(locationRow, "Use phone location", "phone GPS positions the map when the comma has no fix.") {
                    client?.setUsePhoneLocation(true)
                    (activity as? MainActivity)?.refreshPhoneLocationRegistration()
                }
            } else {
                client?.setUsePhoneLocation(false)
                (activity as? MainActivity)?.refreshPhoneLocationRegistration()
            }
        }
        card.addView(locationRow)
        card.addView(createDivider(pal))
        var toCommaRow: LinearLayout? = null
        toCommaRow = createSwitchRow(
            iconKind = SettingsIconView.Kind.NAVIGATION,
            title = "Phone location → Comma",
            subtitle = "Why: sends phone GPS to the comma while you set a destination, so routing starts from where you are. Needs Location permission - asked only if you turn this on.",
            initialChecked = NavStateManager.usePhoneLocationToComma,
            pal = pal,
            subtitleMaxLines = 6
        ) { enabled ->
            if (enabled) {
                gateLocationToggle(toCommaRow, "Phone location to Comma", "it sends phone GPS to the comma while you set a destination, so routing starts from where you are.") {
                    client?.setUsePhoneLocationToComma(true)
                    (activity as? MainActivity)?.refreshPhoneLocationRegistration()
                }
            } else {
                client?.setUsePhoneLocationToComma(false)
                (activity as? MainActivity)?.refreshPhoneLocationRegistration()
            }
        }
        card.addView(toCommaRow)
        card.addView(createDivider(pal))
        var mapboxKeyRow: LinearLayout? = null
        mapboxKeyRow = createSwitchRow(
            iconKind = SettingsIconView.Kind.MAP,
            title = "Use phone Mapbox key",
            subtitle = "Why: uses the Mapbox key saved in this app for map tiles. No system permission needed.",
            initialChecked = NavStateManager.usePhoneMapboxKey,
            pal = pal,
            subtitleMaxLines = 6
        ) { enabled ->
            val hasToken = !(client?.mapboxToken ?: NavStateManager.mapboxToken).isNullOrBlank()
            if (enabled && !hasToken) {
                // No key saved: error, and the switch goes back off —
                // the revert re-fires this handler with false, saving
                // the off state like any other switch-off.
                rowSwitch(mapboxKeyRow)?.isChecked = false
                Toast.makeText(activity, MapboxKeyGate.ERROR_MESSAGE, Toast.LENGTH_LONG).show()
            } else {
                client?.setUsePhoneMapboxKey(enabled)
                onThemeChanged()
            }
        }
        card.addView(mapboxKeyRow)
        card.addView(createDivider(pal))
        var googleKeyRow: LinearLayout? = null
        googleKeyRow = createSwitchRow(
            iconKind = SettingsIconView.Kind.MAP,
            title = "Use phone Google key",
            subtitle = "Why: uses the Google API key saved in this app for Google phone maps, Google search and Google speed limits. No system permission needed.",
            initialChecked = NavStateManager.usePhoneGoogleKey,
            pal = pal,
            subtitleMaxLines = 6
        ) { enabled ->
            val hasKey = !(client?.googleApiKey ?: NavStateManager.googleApiKey)
                .isNullOrBlank()
            if (enabled && !hasKey) {
                // Same gate as the Mapbox key: no key saved is an
                // error, and the switch goes back off.
                rowSwitch(googleKeyRow)?.isChecked = false
                Toast.makeText(
                    activity,
                    "No Google API key saved — add it in Settings → Map keys & usage first.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                client?.setUsePhoneGoogleKey(enabled)
                onThemeChanged()
            }
        }
        card.addView(googleKeyRow)
        card.addView(createDivider(pal))
        var trafficRow: LinearLayout? = null
        trafficRow = createSwitchRow(
            iconKind = SettingsIconView.Kind.MAP,
            title = "Traffic",
            subtitle = "Why: live traffic colors on Mapbox maps (phone + Auto). Needs your Mapbox key - no system permission needed.",
            initialChecked = NavStateManager.usePhoneTraffic,
            pal = pal,
            subtitleMaxLines = 6
        ) { enabled ->
            val hasToken = !(client?.mapboxToken ?: NavStateManager.mapboxToken).isNullOrBlank()
            if (enabled && !hasToken) {
                // Traffic is Mapbox data: same gate as the key toggle.
                rowSwitch(trafficRow)?.isChecked = false
                Toast.makeText(activity, MapboxKeyGate.ERROR_MESSAGE, Toast.LENGTH_LONG).show()
            } else {
                client?.setUsePhoneTraffic(enabled)
                onThemeChanged()
            }
        }
        card.addView(trafficRow)
        card.addView(createDivider(pal))
        var speedLimitRow: LinearLayout? = null
        speedLimitRow = createSwitchRow(
            iconKind = SettingsIconView.Kind.SPEED,
            title = "Use phone speed limit",
            subtitle = "Why: shows the limit from Mapbox route data, because the comma doesn't share its own. No system permission needed.",
            initialChecked = NavStateManager.usePhoneSpeedLimit,
            pal = pal,
            subtitleMaxLines = 6
        ) { enabled ->
            val hasToken = !(client?.mapboxToken ?: NavStateManager.mapboxToken).isNullOrBlank()
            if (enabled && !hasToken) {
                // The limit IS Mapbox data — same gate as the key toggle.
                rowSwitch(speedLimitRow)?.isChecked = false
                Toast.makeText(activity, MapboxKeyGate.ERROR_MESSAGE, Toast.LENGTH_LONG).show()
            } else {
                client?.setUsePhoneSpeedLimit(enabled)
                if (!enabled) NavStateManager.speedLimitMph = 0
            }
        }
        card.addView(speedLimitRow)
        card.addView(createDivider(pal))
        var signalsRow: LinearLayout? = null
        signalsRow = createSwitchRow(
            iconKind = SettingsIconView.Kind.MAP,
            title = "Traffic signals on map",
            subtitle = "Why: draws traffic-light markers along the route from Mapbox map data (locations only - no live red/green state). Needs your Mapbox key - no system permission needed.",
            initialChecked = NavStateManager.usePhoneSignals,
            pal = pal,
            subtitleMaxLines = 6
        ) { enabled ->
            val hasToken = !(client?.mapboxToken ?: NavStateManager.mapboxToken).isNullOrBlank()
            if (enabled && !hasToken) {
                // The signals ARE Mapbox data — same gate as the key toggle.
                rowSwitch(signalsRow)?.isChecked = false
                Toast.makeText(activity, MapboxKeyGate.ERROR_MESSAGE, Toast.LENGTH_LONG).show()
            } else {
                client?.setUsePhoneSignals(enabled)
            }
        }
        card.addView(signalsRow)
        card.addView(createDivider(pal))
        card.addView(
            createSwitchRow(
                iconKind = SettingsIconView.Kind.MAP,
                title = "Stop signs on map",
                subtitle = "Why: draws stop-sign markers along the route from OpenStreetMap. Coverage is community-mapped and patchy - a missing marker does not mean no stop sign. No system permission needed.",
                initialChecked = NavStateManager.usePhoneStops,
                pal = pal,
                subtitleMaxLines = 4
            ) { enabled ->
                client?.setUsePhoneStops(enabled)
            }
        )
        card.addView(createDivider(pal))
        card.addView(
            createSwitchRow(
                iconKind = SettingsIconView.Kind.NAVIGATION,
                title = "Use phone route & steps",
                subtitle = "Why: derives turn steps from the comma's route on this phone - off shows only the comma's own maneuvers. No system permission needed.",
                initialChecked = NavStateManager.usePhoneRoute,
                pal = pal,
                subtitleMaxLines = 6
            ) { enabled ->
                client?.setUsePhoneRoute(enabled)
            }
        )
        card.addView(createDivider(pal))
        card.addView(
            createSwitchRow(
                iconKind = SettingsIconView.Kind.MAP,
                title = "Use phone search",
                subtitle = "Why: searches Mapbox/OSM from the phone - off searches on the comma only. No system permission needed.",
                initialChecked = NavStateManager.usePhoneSearch,
                pal = pal,
                subtitleMaxLines = 6
            ) { enabled ->
                client?.setUsePhoneSearch(enabled)
            }
        )
        card.addView(createDivider(pal))
        card.addView(
            createSwitchRow(
                iconKind = SettingsIconView.Kind.MAP,
                title = "Use phone vector map (OpenFreeMap)",
                subtitle = "Why: runs MapLibre GL Native GPU engine streaming PBF vector tiles from OpenFreeMap. Off uses Comma & CARTO raster tiles matching Android Auto.",
                initialChecked = NavStateManager.usePhoneVectorMap,
                pal = pal,
                subtitleMaxLines = 4
            ) { enabled ->
                client?.setUsePhoneVectorMap(enabled)
                onThemeChanged()
            }
        )
        body.addView(card)
        dialog.show()
    }

    /**
     * SLC offsets glance (Feature 19): the comma's current offset table,
     * read fresh on open, read-only. When the comma's page projection
     * doesn't expose the offsets (the known current-Dom behavior),
     * [OffsetsGlance] renders the honest not-reported line instead of a
     * fabricated table. A live drift recorded by the watchdog (F12) adds
     * its warning with both tables.
     */
    fun showOffsetsGlanceDialog(client: CommaClient?) {
        val endpoint = client?.commaIp ?: NavStateManager.commaIp
        val sg = SgGalaxyClient(SgGalaxyClient.baseUrlFor(endpoint ?: "127.0.0.1"))
        val (dialog, body, pal) = createM3Dialog(
            "Speed limit assist",
            "Read from the comma just now · read-only"
        )
        val loading = TextView(activity).apply {
            text = "Reading offsets…"
            dpText(14f)
            setTextColor(pal.textSecondary)
        }
        body.addView(loading)
        dialog.show()
        Thread {
            val offsetRows = try {
                sg.fetchSlcOffsetRows()
            } catch (_: Exception) {
                null
            }
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                body.removeAllViews()
                for (line in OffsetsGlance.labeledRows(offsetRows)) {
                    body.addView(TextView(activity).apply {
                        text = line
                        dpText(15f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(pal.textPrimary)
                        setPadding(0, dp(4), 0, dp(4))
                    })
                }
            }
        }.start()
    }

    fun showPersonalityProfileDialog(client: CommaClient?) {
        val endpoint = client?.commaIp ?: NavStateManager.commaIp
        val baseUrl = SgGalaxyClient.baseUrlFor(endpoint ?: "127.0.0.1")
        val repo = SettingsRepository(SgGalaxyClient(baseUrl))
        val devState = NavStateManager.deviceState?.lowercase()?.trim()
        val isParked = repo.isVehicleParked(devState)

        val sub = if (isParked) "Vehicle parked · Ready to apply" else "Shift into Park to modify"
        val (dialog, body, pal) = createM3Dialog("Driving personality", sub)

        val profiles = listOf(
            "Aggressive" to "Tighter follow distance and quicker lane response",
            "Standard" to "Balanced highway and city cruising",
            "Relaxed" to "Smooth acceleration and 1.8s follow distance",
            "Traffic" to "Low-speed traffic following, blends toward Relaxed as speed rises"
        )
        val card = createGroupCard(pal)
        profiles.forEachIndexed { idx, (name, desc) ->
            if (idx > 0) card.addView(createDivider(pal))
            card.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.TUNE,
                    title = name,
                    subtitle = desc,
                    pal = pal
                ) {
                    if (!isParked) {
                        Toast.makeText(activity, "Shift into Park to change driving personality", Toast.LENGTH_SHORT).show()
                        return@createSettingsRow
                    }
                    dialog.dismiss()
                    Thread {
                        val result = repo.updatePersonalityProfile("profiles", name, "parked")
                        activity.runOnUiThread {
                            result.onSuccess {
                                // Feature 1: show the read-back receipt
                                // (the value the comma actually holds now)
                                // when the commit produced one.
                                val receipts = repo.lastReceipts
                                if (receipts.isNotEmpty()) {
                                    Toast.makeText(
                                        activity,
                                        receipts.joinToString("\n"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(activity, "Personality: $name", Toast.LENGTH_SHORT).show()
                                }
                            }.onFailure { err ->
                                Toast.makeText(activity, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
            )
        }
        body.addView(card)
        // Addendum 150 (2026-11-27, Galaxy-parity mandate): the
        // switcher above only picks the active profile — the
        // per-profile tuning (follow/jerk numbers, acceleration /
        // braking / following presets and custom curves) lives on
        // the Galaxy personalities page, opened here in the visual
        // page editor like every other Comma settings page.
        val tuneCard = createGroupCard(pal)
        tuneCard.addView(
            createSettingsRow(
                iconKind = SettingsIconView.Kind.TUNE,
                title = "Tune profiles",
                subtitle = "Following, jerk & presets for each profile",
                pal = pal
            ) {
                dialog.dismiss()
                showVisualSettingsPageDialog(
                    client, "personalities", "Driving Personalities"
                ) {}
            }
        )
        body.addView(tuneCard)
        dialog.show()
    }

    fun showModelLaboratoryDialog(client: CommaClient?) {
        val endpoint = client?.commaIp ?: NavStateManager.commaIp
        if (endpoint.isNullOrBlank() && !NavStateManager.isBleConnected) {
            Toast.makeText(activity, "Connect to Comma first", Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = SgGalaxyClient.baseUrlFor(endpoint ?: "127.0.0.1")
        val repo = ModelManagerRepository(SgGalaxyClient(baseUrl))
        val devState = NavStateManager.deviceState?.lowercase()?.trim()

        Toast.makeText(activity, "Loading models...", Toast.LENGTH_SHORT).show()
        Thread {
            val isParked = repo.isVehicleParked(devState)
            val models = repo.getInstalledModels()
            val latestModels = repo.getLatestModels(5)
            val allModels = repo.getAllModels()
            val activeModel = repo.getActiveModel()
            val actives = repo.getManagerActives()
            val downloadState = repo.getDownloadState()
            val soundPacks = repo.getSoundPacks()
            // The Active badge needs a row to land on: if an active pick
            // (e.g. the built-in default) is filtered out of the installed
            // list, add its entry from the full catalog.
            val displayModels = models.toMutableList().also { list ->
                val present = list.map { it.value }.toSet()
                for (id in listOf(actives.current, actives.activeSmall, actives.activeBig)) {
                    if (id != null && id !in present) {
                        allModels.firstOrNull { it.value == id }?.let { list.add(it) }
                    }
                }
            }

            activity.runOnUiThread {
                val activeLabel = actives.current?.let { id ->
                    allModels.firstOrNull { it.value == id }?.label ?: id
                } ?: actives.activeSmall?.let { id ->
                    allModels.firstOrNull { it.value == id }?.label ?: id
                } ?: activeModel?.second ?: activeModel?.first ?: "Default"
                val (dialog, body, pal) = createM3Dialog(
                    "Driving models",
                    "Active: $activeLabel · ${if (isParked) "Parked" else "Locked while driving"}"
                )

                // Addendum 135: a model download running on the comma
                // gets a live banner at the top — until now the app
                // never read the manager payload's download fields, so
                // a download in progress looked like nothing was
                // happening. The value is refreshed every 3 s while
                // the sheet stays open (see the loop after show()).
                var downloadValueView: TextView? = null
                if (downloadState != null) {
                    val dlCard = createGroupCard(pal)
                    val dlRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                    }
                    dlRow.addView(TextView(activity).apply {
                        text = "Downloading model"
                        dpText(14f)
                        setTextColor(pal.textPrimary)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    val valueView = TextView(activity).apply {
                        text = downloadState.displayText()
                        dpText(14f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(pal.textSecondary)
                    }
                    dlRow.addView(valueView)
                    downloadValueView = valueView
                    dlCard.addView(dlRow)
                    body.addView(dlCard)
                }

                // Addendum 137: per-model Delete button (Param,
                // 2026-11-22) — always behind a confirmation, same
                // wording and flow as the delete-models list.
                val confirmDeleteModel: (GalaxyModelItem) -> Unit = { m ->
                    if (!isParked) {
                        Toast.makeText(activity, "Shift into Park to delete models", Toast.LENGTH_SHORT).show()
                    } else {
                        showSheetConfirm(
                            title = "Delete model",
                            message = "Delete ${m.label} from the comma? You can download it again later.",
                            positiveLabel = "Delete",
                            danger = true,
                            onPositive = {
                                Thread {
                                    val res = repo.deleteModel(m.value, "parked")
                                    activity.runOnUiThread {
                                        res.onSuccess {
                                            Toast.makeText(activity, "Deleted: ${m.label}", Toast.LENGTH_SHORT).show()
                                            dialog.dismiss()
                                            showModelLaboratoryDialog(client)
                                        }.onFailure { e ->
                                            Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }.start()
                            }
                        )
                    }
                }

                val card = createGroupCard(pal)
                if (displayModels.isEmpty()) {
                    card.addView(createKeyValueRow("Installed models", "None reported", pal))
                } else {
                    displayModels.forEachIndexed { idx, m ->
                        if (idx > 0) card.addView(createDivider(pal))
                        val badge = ActiveModel.badgeFor(m.value, actives)
                        card.addView(
                            createSettingsRow(
                                iconKind = SettingsIconView.Kind.SPARK,
                                title = m.label,
                                subtitle = "${m.value} · ${ActiveModel.sizeClass(m.small)}",
                                badgeText = badge,
                                badgeActive = badge != null,
                                trailingActionText = if (m.installed && !m.builtin) "Delete" else null,
                                onTrailingAction = { confirmDeleteModel(m) },
                                pal = pal
                            ) {
                                if (!isParked) {
                                    Toast.makeText(activity, "Shift into Park to switch models", Toast.LENGTH_SHORT).show()
                                    return@createSettingsRow
                                }
                                dialog.dismiss()
                                Thread {
                                    val res = repo.activateModel(m.value, m.small, "parked")
                                    activity.runOnUiThread {
                                        res.onSuccess {
                                            Toast.makeText(activity, "Set for next drive: ${m.label}", Toast.LENGTH_SHORT).show()
                                        }.onFailure { e ->
                                            Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }.start()
                            }
                        )
                    }
                }
                body.addView(card)

                if (latestModels.isNotEmpty()) {
                    val latestLabel = TextView(activity).apply {
                        text = "Latest models"
                        dpText(13f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(pal.textSecondary)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(dp(2), dp(12), 0, dp(6)) }
                    }
                    body.addView(latestLabel)

                    val latestCard = createGroupCard(pal)
                    latestModels.forEachIndexed { idx, m ->
                        if (idx > 0) latestCard.addView(createDivider(pal))
                        val sizeMb = if (m.artifactSizeBytes > 0) {
                            (m.artifactSizeBytes / 1_000_000.0).roundToInt()
                        } else 0
                        val subtitleParts = mutableListOf<String>()
                        if (m.released.isNotEmpty()) subtitleParts.add("Released ${m.released}")
                        if (sizeMb > 0) subtitleParts.add("$sizeMb MB")
                        subtitleParts.add(ActiveModel.sizeClass(m.small))
                        val dlThis = if (downloadState != null && downloadState.model == m.value) downloadState else null
                        val isDownloadingThis = dlThis != null
                        if (dlThis != null) subtitleParts.add(dlThis.displayText())
                        val latestSubtitle = subtitleParts.joinToString(" · ")
                        val activeBadge = ActiveModel.badgeFor(m.value, actives)
                        val latestBadge = activeBadge ?: when {
                            isDownloadingThis -> "Downloading"
                            m.installed -> "Downloaded"
                            m.downloadAvailable -> "Download"
                            else -> m.unavailableReason
                        }
                        latestCard.addView(
                            createSettingsRow(
                                iconKind = SettingsIconView.Kind.SPARK,
                                title = m.label,
                                subtitle = latestSubtitle,
                                badgeText = latestBadge,
                                badgeActive = activeBadge != null || m.installed,
                                trailingActionText = if (m.installed && !m.builtin) "Delete" else null,
                                onTrailingAction = { confirmDeleteModel(m) },
                                pal = pal
                            ) {
                                when {
                                    isDownloadingThis -> {
                                        Toast.makeText(activity, "Already downloading — ${dlThis?.displayText() ?: "Downloading…"}", Toast.LENGTH_SHORT).show()
                                    }
                                    !m.installed && m.downloadAvailable -> {
                                        if (!isParked) {
                                            Toast.makeText(activity, "Shift into Park to download models", Toast.LENGTH_SHORT).show()
                                            return@createSettingsRow
                                        }
                                        val sizeText = if (sizeMb > 0) " ($sizeMb MB)" else ""
                                        showSheetConfirm(
                                            title = "Download model",
                                            message = "Download ${m.label}$sizeText to the comma now?",
                                            positiveLabel = "Download",
                                            onPositive = {
                                                Thread {
                                                    val res = repo.downloadModel(m.value, if (isParked) "parked" else null)
                                                    activity.runOnUiThread {
                                                        res.onSuccess {
                                                            Toast.makeText(activity, "Download started: ${m.label}", Toast.LENGTH_SHORT).show()
                                                            dialog.dismiss()
                                                            showModelLaboratoryDialog(client)
                                                        }.onFailure { e ->
                                                            Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }.start()
                                            }
                                        )
                                    }
                                    m.installed && m.selectable -> {
                                        if (!isParked) {
                                            Toast.makeText(activity, "Shift into Park to switch models", Toast.LENGTH_SHORT).show()
                                            return@createSettingsRow
                                        }
                                        dialog.dismiss()
                                        Thread {
                                            val res = repo.activateModel(m.value, m.small, "parked")
                                            activity.runOnUiThread {
                                                res.onSuccess {
                                                    Toast.makeText(activity, "Set for next drive: ${m.label}", Toast.LENGTH_SHORT).show()
                                                }.onFailure { e ->
                                                    Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }.start()
                                    }
                                    m.installed && m.builtin -> {
                                        Toast.makeText(activity, "${m.label} is built into the comma", Toast.LENGTH_SHORT).show()
                                    }
                                    else -> {
                                        if (!m.unavailableReason.isNullOrEmpty()) {
                                            Toast.makeText(activity, m.unavailableReason, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        )
                    }
                    body.addView(latestCard)
                }

                val deletableModels = allModels.filter { it.installed && !it.builtin }
                if (deletableModels.isNotEmpty()) {
                    val deleteBtn = createTonalButton("Delete downloaded models", pal) {
                        dialog.dismiss()
                        showDeleteModelsDialog(repo, client, deletableModels, isParked)
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, dp(10), 0, 0)
                        }
                    }
                    body.addView(deleteBtn)
                }

                if (soundPacks.isNotEmpty()) {
                    val soundBtn = createTonalButton("Audio themes", pal) {
                        dialog.dismiss()
                        showSoundPacksDialog(repo, soundPacks, isParked)
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, dp(10), 0, 0)
                        }
                    }
                    body.addView(soundBtn)
                }
                dialog.show()
                if (downloadState != null) {
                    // Addendum 135: keep the banner current while the
                    // sheet stays open. When the download ends, reopen
                    // once so the model lands in its installed state.
                    // The loop dies with the sheet (isShowing checks).
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    val refresh = object : Runnable {
                        override fun run() {
                            if (!dialog.isShowing) return
                            Thread {
                                repo.getAllModels()
                                val fresh = repo.getDownloadState()
                                activity.runOnUiThread {
                                    if (!dialog.isShowing) return@runOnUiThread
                                    if (fresh == null) {
                                        dialog.dismiss()
                                        showModelLaboratoryDialog(client)
                                    } else {
                                        downloadValueView?.text = fresh.displayText()
                                        handler.postDelayed(this, 3000L)
                                    }
                                }
                            }.start()
                        }
                    }
                    handler.postDelayed(refresh, 3000L)
                }
            }
        }.start()
    }

    private fun showDeleteModelsDialog(
        repo: ModelManagerRepository,
        client: CommaClient?,
        deletable: List<GalaxyModelItem>,
        isParked: Boolean
    ) {
        val (dialog, body, pal) = createM3Dialog(
            "Delete downloaded models",
            if (isParked) "Tap a model to delete it from the comma" else "Parked only"
        )
        val card = createGroupCard(pal)
        deletable.forEachIndexed { idx, m ->
            if (idx > 0) card.addView(createDivider(pal))
            val sizeMb = if (m.artifactSizeBytes > 0) {
                (m.artifactSizeBytes / 1_000_000.0).roundToInt()
            } else 0
            card.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.SPARK,
                    title = m.label,
                    subtitle = if (sizeMb > 0) "$sizeMb MB" else m.value,
                    badgeText = null,
                    badgeActive = false,
                    pal = pal
                ) {
                    if (!isParked) {
                        Toast.makeText(activity, "Shift into Park to delete models", Toast.LENGTH_SHORT).show()
                        return@createSettingsRow
                    }
                    showSheetConfirm(
                        title = "Delete model",
                        message = "Delete ${m.label} from the comma? You can download it again later.",
                        positiveLabel = "Delete",
                        danger = true,
                        onPositive = {
                            Thread {
                                val res = repo.deleteModel(m.value, if (isParked) "parked" else null)
                                activity.runOnUiThread {
                                    res.onSuccess {
                                        Toast.makeText(activity, "Deleted: ${m.label}", Toast.LENGTH_SHORT).show()
                                        dialog.dismiss()
                                        showModelLaboratoryDialog(client)
                                    }.onFailure { e ->
                                        Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }.start()
                        }
                    )
                }
            )
        }
        body.addView(card)
        dialog.show()
    }

    private fun showSoundPacksDialog(
        repo: ModelManagerRepository,
        packs: List<GalaxySoundPack>,
        isParked: Boolean
    ) {
        val (dialog, body, pal) = createM3Dialog("Audio themes", if (isParked) "Select sound pack" else "Parked only")
        val card = createGroupCard(pal)
        packs.forEachIndexed { idx, p ->
            if (idx > 0) card.addView(createDivider(pal))
            card.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.SPARK,
                    title = p.name,
                    subtitle = if (p.installed) "Installed on device" else "Tap to download",
                    badgeText = if (p.installed) "Installed" else null,
                    badgeActive = p.installed,
                    pal = pal
                ) {
                    if (!isParked) {
                        Toast.makeText(activity, "Shift into Park to change audio theme", Toast.LENGTH_SHORT).show()
                        return@createSettingsRow
                    }
                    dialog.dismiss()
                    Thread {
                        val res = repo.downloadSoundPack(p.id, "parked")
                        activity.runOnUiThread {
                            res.onSuccess {
                                Toast.makeText(activity, "Audio theme: ${p.name}", Toast.LENGTH_SHORT).show()
                            }.onFailure { e ->
                                Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
            )
        }
        body.addView(card)
        dialog.show()
    }

    /**
     * Addendum 126: while an install is pending, the software dialog is
     * local-only — the quiet rule (connectivity checks only) forbids
     * the status fetch, and the user must be able to cancel even with
     * the comma unreachable.
     */
    fun showPendingSoftwareUpdateDialog(client: CommaClient?) {
        val (dialog, body, pal) = createM3Dialog("Comma software", "Update install pending")
        val card = createGroupCard(pal)
        card.addView(
            createKeyValueRow(
                "Install",
                "Pending — will install when the comma is available",
                pal
            )
        )
        body.addView(card)
        val cancelBtn = createTonalButton("Cancel pending update", pal) {
            SoftwareUpdateIntentStore.clear(activity)
            NavStateManager.softwareUpdatePending = false
            dialog.dismiss()
            Toast.makeText(activity, "Pending update cancelled", Toast.LENGTH_SHORT).show()
            showSoftwareUpdateDialog(client)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, 0) }
        }
        body.addView(cancelBtn)
        dialog.show()
    }

    fun showSoftwareUpdateDialog(client: CommaClient?) {
        // Addendum 126: a pending install short-circuits to the
        // local-only dialog — no comma requests while updating.
        if (SoftwareUpdateIntentStore.load(activity) != null) {
            showPendingSoftwareUpdateDialog(client)
            return
        }
        val endpoint = client?.commaIp ?: NavStateManager.commaIp
        if (endpoint.isNullOrBlank() && !NavStateManager.isBleConnected) {
            Toast.makeText(activity, "Connect to Comma first", Toast.LENGTH_SHORT).show()
            return
        }
        val baseUrl = SgGalaxyClient.baseUrlFor(endpoint ?: "127.0.0.1")
        val updater = CommaSoftwareUpdate(SgGalaxyClient(baseUrl))

        Toast.makeText(activity, "Checking comma software...", Toast.LENGTH_SHORT).show()
        Thread {
            val status = updater.fetchStatus()
            activity.runOnUiThread {
                if (status == null) {
                    Toast.makeText(activity, "Could not read comma software status (SecretGoodStarPilot only)", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val subtitle = when {
                    status.updateReady -> "Update ready to install"
                    status.updateAvailable -> "Update available"
                    else -> "Up to date"
                }
                val (dialog, body, pal) = createM3Dialog("Comma software", subtitle)

                val card = createGroupCard(pal)
                val rows = mutableListOf(
                    "Installed" to "${status.installedDisplayVersion} (${status.installedVersion})",
                    "Branch" to status.branch.ifEmpty { "—" },
                    "Commit" to status.commitShort.ifEmpty { "—" },
                    "Update" to when {
                        status.updateReady -> "Ready to install"
                        status.updateAvailable -> "Available — not downloaded yet"
                        else -> "Up to date"
                    },
                    "Last checked" to status.lastCheckedAt.ifEmpty { "—" }
                )
                if (status.progressText.isNotEmpty()) {
                    rows.add("Progress" to status.progressText)
                }
                rows.forEachIndexed { idx, (label, value) ->
                    if (idx > 0) card.addView(createDivider(pal))
                    card.addView(createKeyValueRow(label, value, pal))
                }
                body.addView(card)

                val autoCard = createGroupCard(pal)
                autoCard.addView(
                    createSettingsRow(
                        iconKind = SettingsIconView.Kind.CLOUD,
                        title = "Download updates automatically",
                        subtitle = "The comma stages new Dom builds while parked",
                        badgeText = when (status.autoDownloads) {
                            true -> "On"
                            false -> "Off"
                            null -> "Unknown"
                        },
                        badgeActive = status.autoDownloads == true,
                        pal = pal
                    ) {
                        val current = status.autoDownloads
                        if (current == null) {
                            Toast.makeText(
                                activity,
                                "This comma build doesn't report the auto-download state — set it in the Galaxy portal for now.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            val target = !current
                            showSheetConfirm(
                                title = "Download updates automatically",
                                message = if (target) {
                                    "The comma will download new Dom builds as they appear and mark them ready to install."
                                } else {
                                    "The comma will only download updates when you check manually."
                                },
                                positiveLabel = if (target) "Turn on" else "Turn off",
                                onPositive = {
                                    dialog.dismiss()
                                    Thread {
                                        val (code, resp) = updater.postPreferences(target)
                                        activity.runOnUiThread {
                                            if (code in 200..299) {
                                                val reported = resp?.let { CommaSoftwareUpdate.parseStatus(it).autoDownloads }
                                                val nowOn = reported ?: target
                                                Toast.makeText(
                                                    activity,
                                                    if (nowOn) "Automatic downloads on" else "Automatic downloads off",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                showSoftwareUpdateDialog(client)
                                            } else {
                                                Toast.makeText(activity, CommaSoftwareUpdate.errorText(code, resp), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }.start()
                                }
                            )
                        }
                    }
                )
                body.addView(autoCard)

                // Branch the updater targets; falls back to the installed branch.
                val actionBranch = status.targetBranch.ifEmpty { status.branch }
                val fullWidth = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(10), 0, 0) }

                val checkBtn = createTonalButton("Check for updates", pal) {
                    dialog.dismiss()
                    Thread {
                        val (code, resp) = updater.postAction("check", null)
                        activity.runOnUiThread {
                            if (code in 200..299 && resp != null) {
                                val fresh = CommaSoftwareUpdate.parseStatus(resp)
                                Toast.makeText(
                                    activity,
                                    if (fresh.updateAvailable || fresh.updateReady) "Update available" else "Comma is up to date",
                                    Toast.LENGTH_SHORT
                                ).show()
                                showSoftwareUpdateDialog(client)
                            } else {
                                Toast.makeText(activity, CommaSoftwareUpdate.errorText(code, resp), Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }.apply { layoutParams = fullWidth }
                body.addView(checkBtn)

                if (status.updateAvailable && !status.updateReady) {
                    val downloadBtn = createTonalButton("Download update", pal) {
                        showSheetConfirm(
                            title = "Download update",
                            message = "Download the update for branch $actionBranch now?",
                            positiveLabel = "Download",
                            onPositive = {
                                dialog.dismiss()
                                Thread {
                                    val (code, resp) = updater.postAction("download", actionBranch.ifEmpty { null })
                                    activity.runOnUiThread {
                                        if (code in 200..299) {
                                            Toast.makeText(activity, "Download started — reopen to check progress", Toast.LENGTH_SHORT).show()
                                            showSoftwareUpdateDialog(client)
                                        } else {
                                            Toast.makeText(activity, CommaSoftwareUpdate.errorText(code, resp), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }.start()
                            }
                        )
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, dp(10), 0, 0) }
                    }
                    body.addView(downloadBtn)
                }

                if (status.updateReady) {
                    val installBtn = createTonalButton("Install update", pal) {
                        showSheetConfirm(
                            title = "Install update",
                            message = "Install the downloaded update now? The comma will reboot and driving assistance will be unavailable until it restarts. The car must be parked.",
                            positiveLabel = "Install",
                            onPositive = {
                                // Addendum 126: record the intent FIRST, so
                                // the install keeps retrying (and survives
                                // an app restart) until the comma finishes
                                // it — the slow poll drives the retries.
                                val newPending = SoftwareUpdatePending(
                                    baseCommit = status.commitShort,
                                    branch = actionBranch,
                                    requestedAtMs = System.currentTimeMillis(),
                                    lastAttemptMs = 0L
                                )
                                SoftwareUpdateIntentStore.save(activity, newPending)
                                NavStateManager.softwareUpdatePending = true
                                dialog.dismiss()
                                Thread {
                                    val parked = SettingsRepository(SgGalaxyClient(baseUrl)).isVehicleParked(null)
                                    if (!parked) {
                                        activity.runOnUiThread {
                                            Toast.makeText(activity, "Update saved — will install once parked, when the comma is reachable", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        val (code, resp) = updater.postAction("install", actionBranch.ifEmpty { null })
                                        val attemptAt = System.currentTimeMillis()
                                        // Addendum 134: no answer back
                                        // (transport failure / ambiguous)
                                        // means the install may already
                                        // be running — the comma reboots
                                        // before the response survives.
                                        // Record the attempt unconfirmed
                                        // so the engine completes it from
                                        // the reboot cycle instead of
                                        // re-installing forever.
                                        val unconfirmed =
                                            code == -1 || code == SgGalaxyClient.CODE_AMBIGUOUS
                                        // Addendum 160: a refusal whose
                                        // reason says the updater is busy
                                        // / a request is pending means
                                        // the install is already running
                                        // server-side — record it so the
                                        // engine waits on status instead
                                        // of re-POSTing into refusals.
                                        val reason = resp?.optString("error", "")
                                            ?.ifEmpty { null }
                                        val busyRefusal =
                                            SoftwareUpdatePlan.classifyAttempt(code, reason) ==
                                                SoftwareUpdatePlan.AttemptOutcome.BUSY_REFUSAL
                                        SoftwareUpdateIntentStore.save(
                                            activity,
                                            if (code in 200..299) {
                                                newPending.copy(
                                                    lastAttemptMs = attemptAt,
                                                    acceptedAtMs = attemptAt
                                                )
                                            } else {
                                                newPending.copy(
                                                    lastAttemptMs = attemptAt,
                                                    attemptUnconfirmed = unconfirmed,
                                                    serverBusy = busyRefusal
                                                )
                                            }
                                        )
                                        activity.runOnUiThread {
                                            if (code in 200..299) {
                                                Toast.makeText(activity, "Install started — the comma will reboot", Toast.LENGTH_LONG).show()
                                            } else if (unconfirmed) {
                                                Toast.makeText(activity, "Install sent — no answer back (the comma may already be rebooting). Updating continues automatically.", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(activity, CommaSoftwareUpdate.errorText(code, resp) + " — update saved, will keep trying", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }.start()
                            }
                        )
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, dp(10), 0, 0) }
                    }
                    body.addView(installBtn)
                }

                val rebootBtn = createTonalButton("Reboot comma", pal) {
                    showSheetConfirm(
                        title = "Reboot comma",
                        message = "Reboot the comma now? It must be parked.",
                        positiveLabel = "Reboot",
                        onPositive = {
                            dialog.dismiss()
                            Thread {
                                val parked = SettingsRepository(SgGalaxyClient(baseUrl)).isVehicleParked(null)
                                if (!parked) {
                                    activity.runOnUiThread {
                                        Toast.makeText(activity, "Shift into Park to reboot the comma", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    val (code, resp) = updater.postAction("reboot", null)
                                    activity.runOnUiThread {
                                        if (code in 200..299) {
                                            Toast.makeText(activity, "Comma rebooting", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(activity, CommaSoftwareUpdate.errorText(code, resp), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }.start()
                        }
                    )
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dp(10), 0, 0) }
                }
                body.addView(rebootBtn)

                dialog.show()
            }
        }.start()
    }

    fun showSlcOffsetDialog(client: CommaClient?) {
        val endpoint = client?.commaIp ?: NavStateManager.commaIp
        val baseUrl = SgGalaxyClient.baseUrlFor(endpoint ?: "127.0.0.1")
        val repo = SettingsRepository(SgGalaxyClient(baseUrl))
        val currentOffsets = NavStateManager.sgOffsetsMph
        if (currentOffsets == null) {
            Toast.makeText(activity, "Fetching speed limit assist from device...", Toast.LENGTH_SHORT).show()
            Thread {
                val fetched = SgGalaxyClient(baseUrl).fetchSlcOffsets()
                activity.runOnUiThread {
                    if (fetched != null) {
                        NavStateManager.sgOffsetsMph = fetched
                        showSlcOffsetDialog(client)
                    } else {
                        Toast.makeText(activity, "Offsets unavailable. Connect to car to view.", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
            return
        }
        val devState = NavStateManager.deviceState?.lowercase()?.trim()
        val isParked = repo.isVehicleParked(devState)

        val (dialog, body, pal) = createM3Dialog(
            "Speed limit assist",
            if (isParked) "Tap a speed band to adjust offset" else "Shift into Park to modify"
        )

        // The comma's own band labels (Galaxy's slc page, current
        // Dom): inclusive whole-number ranges. No road-type subtitles
        // — the device has none; every row is a speed limit offset.
        val bands = listOf(
            "0–25 mph", "26–34 mph", "35–43 mph", "44–53 mph",
            "54–63 mph", "64–74 mph", "75–98 mph"
        )
        val card = createGroupCard(pal)
        bands.forEachIndexed { idx, range ->
            if (idx > 0) card.addView(createDivider(pal))
            val cur = currentOffsets.getOrElse(idx) { 0.0 }.roundToInt()
            val sign = if (cur >= 0) "+$cur mph" else "$cur mph"
            card.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.SPEED,
                    title = range,
                    subtitle = "Speed limit offset",
                    badgeText = sign,
                    badgeActive = true,
                    pal = pal
                ) {
                    if (!isParked) {
                        Toast.makeText(activity, "Shift into Park to change offsets", Toast.LENGTH_SHORT).show()
                        return@createSettingsRow
                    }
                    dialog.dismiss()
                    showBandAdjustmentDialog(repo, currentOffsets, idx, range, isParked, client)
                }
            )
        }
        body.addView(card)
        // Addendum 139: the rest of Galaxy's Speed Limit Controller
        // page (the controller toggle, sign display, confirmation
        // policy, source pickers, reset) loads under the band card.
        attachSlcAssistExtras(client, baseUrl, body, pal, dialog)
        dialog.show()
    }

    /** The comma's own error words for a settings call, as in the generic page dialog. */
    private fun slcErrorText(resp: JSONObject?, code: Int): String {
        val server = resp?.optString("error", "")?.ifEmpty { null }
        return when {
            code == 401 ->
                (server ?: "Sign in to Galaxy") +
                    " — settings need a local or Bluetooth connection"
            server != null -> server
            code == -1 || code == 0 ->
                "The comma didn't answer — check the connection"
            else -> "Comma answered HTTP $code"
        }
    }

    /**
     * Addendum 139: fetch the comma's slc page in one Galaxy session
     * and append its non-offset settings as a second card in the
     * Speed limit assist sheet. Writes run the preview -> confirm
     * flow in that same session (a preview intent is bound to the
     * session that created it); success and failure both land back
     * on a freshly opened sheet, and failures surface the comma's
     * own error first.
     */
    private fun attachSlcAssistExtras(
        client: CommaClient?,
        baseUrl: String,
        body: LinearLayout,
        pal: M3Palette,
        dialog: Dialog
    ) {
        val sgClient = SgGalaxyClient(baseUrl)
        Thread {
            val (code, resp) = sgClient.fetchSettingsPage("slc")
            val page = GalaxySettings.parsePage(resp)
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                if (code != 200 || page == null) {
                    body.addView(TextView(activity).apply {
                        text = "The comma's other speed-limit settings " +
                            "couldn't load (${slcErrorText(resp, code)})."
                        dpText(13f)
                        setTextColor(pal.textSecondary)
                        setPadding(dp(4), dp(10), dp(4), 0)
                    })
                    return@runOnUiThread
                }
                val extras = GalaxySettings.slcAssistExtras(page)
                if (extras.isEmpty()) return@runOnUiThread
                val card = createGroupCard(pal)
                extras.forEachIndexed { pos, (index, rawRow) ->
                    if (pos > 0) card.addView(createDivider(pal))
                    card.addView(
                        slcExtraRow(
                            client, sgClient, page, index,
                            GalaxySettings.presented(rawRow), pal, dialog
                        )
                    )
                }
                body.addView(card)
            }
        }.start()
    }

    /** One non-offset slc row in the assist sheet, by control type. */
    private fun slcExtraRow(
        client: CommaClient?,
        sgClient: SgGalaxyClient,
        page: GalaxySettings.Page,
        index: Int,
        row: GalaxySettings.Row,
        pal: M3Palette,
        dialog: Dialog
    ): View {
        if (!row.available) {
            return createSettingsRow(
                iconKind = SettingsIconView.Kind.SPEED,
                title = row.label,
                subtitle = row.reason,
                badgeText = GalaxySettings.valueWithUnit(row).ifEmpty { null },
                badgeActive = false,
                pal = pal
            ) {
                if (row.reason.isNotEmpty()) {
                    Toast.makeText(activity, row.reason, Toast.LENGTH_LONG).show()
                }
            }.apply { alpha = 0.55f }
        }
        return when (GalaxySettings.control(row)) {
            GalaxySettings.Control.SWITCH -> createSwitchRow(
                iconKind = SettingsIconView.Kind.SPEED,
                title = row.label,
                subtitle = row.reason,
                initialChecked = row.value == "On",
                pal = pal,
                subtitleMaxLines = 2
            ) { checked ->
                commitSlcExtraValue(
                    client, sgClient, page, index,
                    if (checked) "On" else "Off", dialog
                )
            }
            GalaxySettings.Control.SELECT -> createSettingsRow(
                iconKind = SettingsIconView.Kind.SPEED,
                title = row.label,
                subtitle = row.reason,
                badgeText = row.value.ifEmpty { null },
                badgeActive = true,
                pal = pal
            ) {
                showSheetListPicker(row.label, row.choices, row.value) { picked ->
                    commitSlcExtraValue(client, sgClient, page, index, picked, dialog)
                }
            }
            GalaxySettings.Control.ACTION -> createSettingsRow(
                iconKind = SettingsIconView.Kind.SPEED,
                title = row.label,
                subtitle = row.reason,
                badgeText = GalaxySettings.actionLabel(row),
                badgeActive = false,
                pal = pal
            ) {
                reviewSlcExtraAction(client, sgClient, page, index, row, dialog)
            }
            else -> createSettingsRow(
                iconKind = SettingsIconView.Kind.SPEED,
                title = row.label,
                subtitle = row.reason,
                badgeText = GalaxySettings.valueWithUnit(row).ifEmpty { null },
                badgeActive = false,
                pal = pal
            ) {}
        }
    }

    /** Preview -> confirm for one assist-sheet value edit. */
    private fun commitSlcExtraValue(
        client: CommaClient?,
        sgClient: SgGalaxyClient,
        page: GalaxySettings.Page,
        index: Int,
        value: Any,
        dialog: Dialog
    ) {
        Thread {
            val (code, resp) = sgClient.postSettingsPreview(
                GalaxySettings.previewValueBody(page.view, index, value)
            )
            val intent = resp?.optString("intent", "")?.ifEmpty { null }
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                if (code == 200 && intent != null) {
                    confirmSlcExtraIntent(client, sgClient, intent, dialog)
                } else {
                    Toast.makeText(
                        activity, slcErrorText(resp, code), Toast.LENGTH_LONG
                    ).show()
                    dialog.dismiss()
                    showSlcOffsetDialog(client)
                }
            }
        }.start()
    }

    /** Direction preview for an assist-sheet action row, then the comma's own question. */
    private fun reviewSlcExtraAction(
        client: CommaClient?,
        sgClient: SgGalaxyClient,
        page: GalaxySettings.Page,
        index: Int,
        row: GalaxySettings.Row,
        dialog: Dialog
    ) {
        Thread {
            val (code, resp) = sgClient.postSettingsPreview(
                GalaxySettings.previewDirectionBody(
                    page.view, index, if (row.confirm) 0 else 1
                )
            )
            val intent = resp?.optString("intent", "")?.ifEmpty { null }
            val question = resp?.optString("question", "")
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                if (code == 200 && intent != null) {
                    showSheetConfirm(
                        title = row.label,
                        message = question?.ifEmpty { null } ?: "Apply this change?",
                        positiveLabel = "Confirm",
                        onPositive = {
                            confirmSlcExtraIntent(client, sgClient, intent, dialog)
                        }
                    )
                } else {
                    Toast.makeText(
                        activity, slcErrorText(resp, code), Toast.LENGTH_LONG
                    ).show()
                    dialog.dismiss()
                    showSlcOffsetDialog(client)
                }
            }
        }.start()
    }

    /** Confirm a previewed assist-sheet intent; land back on the fresh sheet either way. */
    private fun confirmSlcExtraIntent(
        client: CommaClient?,
        sgClient: SgGalaxyClient,
        intent: String,
        dialog: Dialog
    ) {
        Thread {
            val (code, resp) = sgClient.postSettingsConfirm(
                GalaxySettings.confirmBody(intent, "slc")
            )
            activity.runOnUiThread {
                if (code == 200 && resp?.optBoolean("saved", false) == true) {
                    Toast.makeText(
                        activity, "Saved on the comma", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        activity, slcErrorText(resp, code), Toast.LENGTH_LONG
                    ).show()
                }
                if (dialog.isShowing) dialog.dismiss()
                showSlcOffsetDialog(client)
            }
        }.start()
    }

    private fun showBandAdjustmentDialog(
        repo: SettingsRepository,
        offsets: DoubleArray,
        bandIdx: Int,
        bandName: String,
        isParked: Boolean = true,
        client: CommaClient? = null
    ) {
        val (dialog, body, pal) = createM3Dialog("Adjust offset", bandName)
        val options = listOf(-5, -2, 0, 2, 3, 5, 7, 8, 10, 12, 15)
        val currentVal = offsets.getOrElse(bandIdx) { 0.0 }.roundToInt()
        val card = createGroupCard(pal)

        options.forEachIndexed { idx, opt ->
            if (idx > 0) card.addView(createDivider(pal))
            val label = if (opt >= 0) "+$opt mph" else "$opt mph"
            val isSel = opt == currentVal
            card.addView(
                createSettingsRow(
                    iconKind = SettingsIconView.Kind.SPEED,
                    title = label,
                    subtitle = if (opt == 0) "Match posted speed limit" else "Offset from posted limit",
                    badgeText = if (isSel) "Current" else null,
                    badgeActive = isSel,
                    pal = pal
                ) {
                    val updated = offsets.clone()
                    if (bandIdx in updated.indices) updated[bandIdx] = opt.toDouble()
                    dialog.dismiss()
                    Thread {
                        val res = repo.updateSlcOffsets(updated, if (isParked) "parked" else "motion")
                        activity.runOnUiThread {
                            // No success toast (Param, 2026-11-20): after a
                            // change, land back on the offsets summary, which
                            // shows the table the comma now holds. Failures
                            // still surface as an error before returning.
                            res.onFailure { e ->
                                Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            showSlcOffsetDialog(client)
                        }
                    }.start()
                }
            )
        }
        body.addView(card)
        dialog.show()
    }
}

/**
 * Clean Material 3 circular tonal icon view drawn with crisp Canvas vectors.
 */
class SettingsIconView(
    context: Context,
    private val kind: Kind,
    private val iconColor: Int,
    private val circleBgColor: Int
) : View(context) {

    enum class Kind {
        WIFI_LINK,
        BLUETOOTH,
        CLOUD,
        BATTERY,
        TUNE,
        SPARK,
        SPEED,
        NAVIGATION,
        MAP,
        STORAGE,
        HUB,
        KEY,
        ROUTES,
        PHONE,
        DOWNLOAD,
        CHART,
        PLAY,
        UPDATE,
        PULSE,
        VIDEO,
        CAMERA,
        WHEEL
    }

    private val density = context.resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = circleBgColor
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.9f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = iconColor
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = iconColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        canvas.drawCircle(cx, cy, r, bgPaint)

        val s = r * 0.46f
        when (kind) {
            Kind.WIFI_LINK -> {
                val arc1 = RectF(cx - s, cy - s * 0.6f, cx + s, cy + s * 1.4f)
                val arc2 = RectF(cx - s * 0.62f, cy - s * 0.15f, cx + s * 0.62f, cy + s * 1.1f)
                canvas.drawArc(arc1, 220f, 100f, false, strokePaint)
                canvas.drawArc(arc2, 220f, 100f, false, strokePaint)
                canvas.drawCircle(cx, cy + s * 0.65f, 1.8f * density, fillPaint)
            }
            Kind.BLUETOOTH -> {
                val path = Path().apply {
                    moveTo(cx - s * 0.45f, cy - s * 0.45f)
                    lineTo(cx + s * 0.45f, cy + s * 0.45f)
                    lineTo(cx, cy + s * 0.95f)
                    lineTo(cx, cy - s * 0.95f)
                    lineTo(cx + s * 0.45f, cy - s * 0.45f)
                    lineTo(cx - s * 0.45f, cy + s * 0.45f)
                }
                canvas.drawPath(path, strokePaint)
            }
            Kind.CLOUD -> {
                val rect = RectF(cx - s * 0.85f, cy - s * 0.35f, cx + s * 0.85f, cy + s * 0.55f)
                canvas.drawRoundRect(rect, s * 0.45f, s * 0.45f, strokePaint)
            }
            Kind.BATTERY -> {
                val body = RectF(cx - s * 0.6f, cy - s * 0.7f, cx + s * 0.6f, cy + s * 0.85f)
                canvas.drawRoundRect(body, 2f * density, 2f * density, strokePaint)
                canvas.drawLine(cx - s * 0.25f, cy - s * 0.92f, cx + s * 0.25f, cy - s * 0.92f, strokePaint)
            }
            Kind.TUNE -> {
                canvas.drawLine(cx - s * 0.8f, cy - s * 0.45f, cx + s * 0.8f, cy - s * 0.45f, strokePaint)
                canvas.drawCircle(cx - s * 0.25f, cy - s * 0.45f, s * 0.24f, fillPaint)
                canvas.drawLine(cx - s * 0.8f, cy + s * 0.45f, cx + s * 0.8f, cy + s * 0.45f, strokePaint)
                canvas.drawCircle(cx + s * 0.25f, cy + s * 0.45f, s * 0.24f, fillPaint)
            }
            Kind.SPARK -> {
                val path = Path().apply {
                    moveTo(cx, cy - s)
                    quadTo(cx, cy, cx + s, cy)
                    quadTo(cx, cy, cx, cy + s)
                    quadTo(cx, cy, cx - s, cy)
                    quadTo(cx, cy, cx, cy - s)
                    close()
                }
                canvas.drawPath(path, strokePaint)
            }
            Kind.SPEED -> {
                val arc = RectF(cx - s * 0.9f, cy - s * 0.75f, cx + s * 0.9f, cy + s * 1.05f)
                canvas.drawArc(arc, 165f, 210f, false, strokePaint)
                canvas.drawLine(cx, cy + s * 0.2f, cx + s * 0.48f, cy - s * 0.28f, strokePaint)
            }
            Kind.NAVIGATION -> {
                val path = Path().apply {
                    moveTo(cx, cy - s * 0.9f)
                    lineTo(cx + s * 0.7f, cy + s * 0.8f)
                    lineTo(cx, cy + s * 0.42f)
                    lineTo(cx - s * 0.7f, cy + s * 0.8f)
                    close()
                }
                canvas.drawPath(path, strokePaint)
            }
            Kind.MAP -> {
                val rect = RectF(cx - s * 0.8f, cy - s * 0.65f, cx + s * 0.8f, cy + s * 0.65f)
                canvas.drawRoundRect(rect, 2f * density, 2f * density, strokePaint)
                canvas.drawLine(cx - s * 0.25f, cy - s * 0.65f, cx - s * 0.25f, cy + s * 0.65f, strokePaint)
                canvas.drawLine(cx + s * 0.25f, cy - s * 0.65f, cx + s * 0.25f, cy + s * 0.65f, strokePaint)
            }
            Kind.STORAGE -> {
                val top = RectF(cx - s * 0.8f, cy - s * 0.7f, cx + s * 0.8f, cy - s * 0.1f)
                val bot = RectF(cx - s * 0.8f, cy + s * 0.1f, cx + s * 0.8f, cy + s * 0.7f)
                canvas.drawRoundRect(top, 2.5f * density, 2.5f * density, strokePaint)
                canvas.drawRoundRect(bot, 2.5f * density, 2.5f * density, strokePaint)
            }
            Kind.HUB -> {
                // Connection hub: a centre node linked to three endpoints.
                val nodes = listOf(
                    cx to cy - s * 0.8f,
                    cx - s * 0.75f to cy + s * 0.65f,
                    cx + s * 0.75f to cy + s * 0.65f
                )
                for ((nx, ny) in nodes) {
                    canvas.drawLine(cx, cy, nx, ny, strokePaint)
                }
                for ((nx, ny) in nodes) {
                    canvas.drawCircle(nx, ny, s * 0.2f, fillPaint)
                }
                canvas.drawCircle(cx, cy, s * 0.26f, fillPaint)
            }
            Kind.KEY -> {
                canvas.drawCircle(cx - s * 0.32f, cy - s * 0.1f, s * 0.45f, strokePaint)
                canvas.drawLine(cx + s * 0.02f, cy + s * 0.22f, cx + s * 0.82f, cy - s * 0.62f, strokePaint)
                canvas.drawLine(cx + s * 0.5f, cy - s * 0.3f, cx + s * 0.72f, cy - s * 0.08f, strokePaint)
                canvas.drawLine(cx + s * 0.82f, cy - s * 0.62f, cx + s * 0.98f, cy - s * 0.46f, strokePaint)
            }
            Kind.ROUTES -> {
                val path = Path().apply {
                    moveTo(cx - s * 0.62f, cy + s * 0.62f)
                    cubicTo(cx - s * 0.62f, cy - s * 0.5f, cx + s * 0.62f, cy + s * 0.5f, cx + s * 0.62f, cy - s * 0.62f)
                }
                canvas.drawPath(path, strokePaint)
                canvas.drawCircle(cx - s * 0.62f, cy + s * 0.62f, 2.2f * density, fillPaint)
                canvas.drawCircle(cx + s * 0.62f, cy - s * 0.62f, 2.2f * density, fillPaint)
            }
            Kind.PHONE -> {
                val body = RectF(cx - s * 0.5f, cy - s * 0.85f, cx + s * 0.5f, cy + s * 0.85f)
                canvas.drawRoundRect(body, 3f * density, 3f * density, strokePaint)
                canvas.drawLine(cx - s * 0.16f, cy + s * 0.58f, cx + s * 0.16f, cy + s * 0.58f, strokePaint)
            }
            Kind.DOWNLOAD -> {
                canvas.drawLine(cx, cy - s * 0.8f, cx, cy + s * 0.25f, strokePaint)
                canvas.drawLine(cx, cy + s * 0.25f, cx - s * 0.36f, cy - s * 0.12f, strokePaint)
                canvas.drawLine(cx, cy + s * 0.25f, cx + s * 0.36f, cy - s * 0.12f, strokePaint)
                canvas.drawLine(cx - s * 0.72f, cy + s * 0.45f, cx - s * 0.72f, cy + s * 0.78f, strokePaint)
                canvas.drawLine(cx - s * 0.72f, cy + s * 0.78f, cx + s * 0.72f, cy + s * 0.78f, strokePaint)
                canvas.drawLine(cx + s * 0.72f, cy + s * 0.45f, cx + s * 0.72f, cy + s * 0.78f, strokePaint)
            }
            Kind.CHART -> {
                canvas.drawLine(cx - s * 0.85f, cy + s * 0.8f, cx + s * 0.85f, cy + s * 0.8f, strokePaint)
                val barW = s * 0.3f
                canvas.drawRoundRect(
                    RectF(cx - s * 0.62f - barW / 2, cy + s * 0.15f, cx - s * 0.62f + barW / 2, cy + s * 0.8f),
                    1.5f * density, 1.5f * density, fillPaint
                )
                canvas.drawRoundRect(
                    RectF(cx - barW / 2, cy - s * 0.25f, cx + barW / 2, cy + s * 0.8f),
                    1.5f * density, 1.5f * density, fillPaint
                )
                canvas.drawRoundRect(
                    RectF(cx + s * 0.62f - barW / 2, cy - s * 0.65f, cx + s * 0.62f + barW / 2, cy + s * 0.8f),
                    1.5f * density, 1.5f * density, fillPaint
                )
            }
            Kind.PLAY -> {
                val path = Path().apply {
                    moveTo(cx - s * 0.42f, cy - s * 0.62f)
                    lineTo(cx + s * 0.68f, cy)
                    lineTo(cx - s * 0.42f, cy + s * 0.62f)
                    close()
                }
                canvas.drawPath(path, strokePaint)
            }
            Kind.UPDATE -> {
                val arc = RectF(cx - s * 0.68f, cy - s * 0.68f, cx + s * 0.68f, cy + s * 0.68f)
                canvas.drawArc(arc, -80f, 290f, false, strokePaint)
                val hx = cx + s * 0.68f * 0.1736f
                val hy = cy - s * 0.68f * 0.9848f
                val head = Path().apply {
                    moveTo(hx, hy - s * 0.3f)
                    lineTo(hx - s * 0.26f, hy + s * 0.14f)
                    lineTo(hx + s * 0.26f, hy + s * 0.14f)
                    close()
                }
                canvas.drawPath(head, fillPaint)
            }
            Kind.PULSE -> {
                val path = Path().apply {
                    moveTo(cx - s * 0.9f, cy)
                    lineTo(cx - s * 0.32f, cy)
                    lineTo(cx - s * 0.12f, cy - s * 0.58f)
                    lineTo(cx + s * 0.16f, cy + s * 0.58f)
                    lineTo(cx + s * 0.36f, cy)
                    lineTo(cx + s * 0.9f, cy)
                }
                canvas.drawPath(path, strokePaint)
            }
            Kind.VIDEO -> {
                val body = RectF(cx - s * 0.95f, cy - s * 0.5f, cx + s * 0.12f, cy + s * 0.5f)
                canvas.drawRoundRect(body, 3f * density, 3f * density, strokePaint)
                val wedge = Path().apply {
                    moveTo(cx + s * 0.12f, cy - s * 0.08f)
                    lineTo(cx + s * 0.92f, cy - s * 0.52f)
                    lineTo(cx + s * 0.92f, cy + s * 0.52f)
                    lineTo(cx + s * 0.12f, cy + s * 0.08f)
                    close()
                }
                canvas.drawPath(wedge, strokePaint)
            }
            Kind.CAMERA -> {
                val body = RectF(cx - s * 0.88f, cy - s * 0.42f, cx + s * 0.88f, cy + s * 0.62f)
                canvas.drawRoundRect(body, 3f * density, 3f * density, strokePaint)
                canvas.drawLine(cx - s * 0.28f, cy - s * 0.42f, cx - s * 0.28f, cy - s * 0.68f, strokePaint)
                canvas.drawLine(cx - s * 0.28f, cy - s * 0.68f, cx + s * 0.28f, cy - s * 0.68f, strokePaint)
                canvas.drawLine(cx + s * 0.28f, cy - s * 0.68f, cx + s * 0.28f, cy - s * 0.42f, strokePaint)
                canvas.drawCircle(cx, cy + s * 0.1f, s * 0.33f, strokePaint)
            }
            Kind.WHEEL -> {
                canvas.drawCircle(cx, cy, s * 0.88f, strokePaint)
                canvas.drawCircle(cx, cy, s * 0.24f, strokePaint)
                canvas.drawLine(cx, cy, cx - s * 0.72f, cy + s * 0.5f, strokePaint)
                canvas.drawLine(cx, cy, cx + s * 0.72f, cy + s * 0.5f, strokePaint)
                canvas.drawLine(cx - s * 0.2f, cy - s * 0.12f, cx - s * 0.82f, cy - s * 0.3f, strokePaint)
                canvas.drawLine(cx + s * 0.2f, cy - s * 0.12f, cx + s * 0.82f, cy - s * 0.3f, strokePaint)
            }
        }
    }
}
