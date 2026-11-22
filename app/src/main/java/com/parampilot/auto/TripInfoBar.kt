package com.parampilot.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.maplibre.android.geometry.LatLng
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Official Google Maps style bottom navigation sheet.
 * Left side:
 *   - Primary line: Remaining duration in Google Maps Green (e.g. "5 min")
 *   - Secondary line: Distance • Arrival Time on a single line (e.g. "1.0 mi • 2:09 PM")
 * Right side:
 *   - Clean circular Close (✕) icon button.
 */
class TripInfoBar(
    private val ctx: Context,
    private val onCloseClick: () -> Unit
) {

    private val den = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * den).roundToInt()

    private lateinit var container: LinearLayout
    private lateinit var primaryTimeText: TextView
    private lateinit var secondaryMetaText: TextView
    private lateinit var closeBtn: CloseCircleButtonView
    private var isNight: Boolean = true

    fun attachTo(dock: LinearLayout) {
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = HudOverlay.makeCardBg(ctx, HudOverlay.COLOR_SURFACE_DARK, 28, HudOverlay.COLOR_BORDER_DARK, 1)
            elevation = 12f * den
            setPadding(dp(20), dp(12), dp(14), dp(12))
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

        primaryTimeText = TextView(ctx).apply {
            text = "-- min"
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(COLOR_GMAPS_TIME_DARK)
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        infoCol.addView(primaryTimeText)

        secondaryMetaText = TextView(ctx).apply {
            text = "-- mi · --:--"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(HudOverlay.COLOR_TEXT_MUTED)
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        infoCol.addView(secondaryMetaText)
        container.addView(infoCol)

        closeBtn = CloseCircleButtonView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                setMargins(dp(12), 0, 0, 0)
            }
            setOnClickListener { onCloseClick() }
        }
        container.addView(closeBtn)

        dock.addView(container)
    }

    fun applyTheme(night: Boolean) {
        isNight = night
        val bgCard = if (night) HudOverlay.COLOR_SURFACE_DARK else Color.WHITE
        val borderColor = if (night) HudOverlay.COLOR_BORDER_DARK else HudOverlay.COLOR_BORDER_LIGHT
        container.background = HudOverlay.makeCardBg(ctx, bgCard, 28, borderColor, 1)

        val timeColor = if (night) COLOR_GMAPS_TIME_DARK else COLOR_GMAPS_TIME_LIGHT
        val mutedColor = if (night) HudOverlay.COLOR_TEXT_MUTED else HudOverlay.COLOR_TEXT_MUTED_LIGHT
        primaryTimeText.setTextColor(timeColor)
        secondaryMetaText.setTextColor(mutedColor)
        closeBtn.applyTheme(night)
    }

    /** Updates the trip info display with route duration and distance. */
    fun update(durationMin: Int, distanceMiles: Double) {
        container.visibility = View.VISIBLE

        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, durationMin) }
        val etaStr = SimpleDateFormat("h:mm a", Locale.US).format(cal.time)
        val distStr = NavFormatter.formatDistance(distanceMiles)

        primaryTimeText.text = NavFormatter.formatDuration(durationMin)
        secondaryMetaText.text = "$distStr · $etaStr"
    }

    fun show() { container.visibility = View.VISIBLE }
    fun hide() { container.visibility = View.GONE }

    companion object {
        const val COLOR_GMAPS_TIME_DARK = 0xFF81C995.toInt()
        const val COLOR_GMAPS_TIME_LIGHT = 0xFF188038.toInt()
    }
}

/**
 * Clean circular Close (✕) icon button matching Google Maps bottom navigation bar.
 */
class CloseCircleButtonView(context: Context) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF28292A.toInt()
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFF3C4043.toInt()
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = 0xFFE3E3E3.toInt()
    }

    fun applyTheme(night: Boolean) {
        bgPaint.color = if (night) 0xFF28292A.toInt() else 0xFFF1F3F4.toInt()
        borderPaint.color = if (night) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt()
        crossPaint.color = if (night) 0xFFE3E3E3.toInt() else 0xFF3C4043.toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 1f
        canvas.drawCircle(cx, cy, r, bgPaint)
        canvas.drawCircle(cx, cy, r, borderPaint)

        val d = r * 0.34f
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, crossPaint)
        canvas.drawLine(cx + d, cy - d, cx - d, cy + d, crossPaint)
    }
}

/**
 * Google Maps style blue directional chevron for vehicle position.
 */
/**
 * Custom Google Maps style 3D navigation chevron arrow.
 * Rendered with authentic Google Maps depth facets, crisp white border,
 * and soft drop shadow over a fully transparent background so map tiles
 * and road geometry remain visible behind and around the vehicle.
 */
class VehicleChevron(context: Context) {

    private val density = context.resources.displayMetrics.density
    private val size = (42 * density).roundToInt()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x4D000000
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val leftFacetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF4285F4.toInt() // Vibrant Google Blue
    }
    private val rightFacetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF1A73E8.toInt() // Deeper 3D facet
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x264285F4.toInt() // Soft pulsing navigation glow
    }

    private val outerPath = Path()
    private val leftFacetPath = Path()
    private val rightFacetPath = Path()
    private val shadowPath = Path()

    /** Creates a Bitmap of the Google Maps blue navigation chevron with transparent background. */
    fun createBitmap(rotationDegrees: Float = 0f): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        if (rotationDegrees != 0f) {
            canvas.save()
            canvas.rotate(rotationDegrees, cx, cy)
        }

        val arrowH = 13f * density
        val arrowHalfW = 9.5f * density
        val notchY = 3.5f * density
        val shadowDy = 1.8f * density

        // Soft glow halo centered at vehicle
        canvas.drawCircle(cx, cy, 14f * density, haloPaint)

        // Drop shadow path (offset downwards)
        shadowPath.reset()
        shadowPath.moveTo(cx, cy - arrowH + shadowDy)
        shadowPath.lineTo(cx + arrowHalfW, cy + arrowH + shadowDy)
        shadowPath.lineTo(cx, cy + notchY + shadowDy)
        shadowPath.lineTo(cx - arrowHalfW, cy + arrowH + shadowDy)
        shadowPath.close()
        canvas.drawPath(shadowPath, shadowPaint)

        // Crisp white border
        outerPath.reset()
        outerPath.moveTo(cx, cy - arrowH)
        outerPath.lineTo(cx + arrowHalfW, cy + arrowH)
        outerPath.lineTo(cx, cy + notchY)
        outerPath.lineTo(cx - arrowHalfW, cy + arrowH)
        outerPath.close()
        canvas.drawPath(outerPath, borderPaint)

        // Left facet (lighter)
        leftFacetPath.reset()
        leftFacetPath.moveTo(cx, cy - arrowH)
        leftFacetPath.lineTo(cx, cy + notchY)
        leftFacetPath.lineTo(cx - arrowHalfW, cy + arrowH)
        leftFacetPath.close()
        canvas.drawPath(leftFacetPath, leftFacetPaint)

        // Right facet (shaded 3D)
        rightFacetPath.reset()
        rightFacetPath.moveTo(cx, cy - arrowH)
        rightFacetPath.lineTo(cx + arrowHalfW, cy + arrowH)
        rightFacetPath.lineTo(cx, cy + notchY)
        rightFacetPath.close()
        canvas.drawPath(rightFacetPath, rightFacetPaint)

        if (rotationDegrees != 0f) {
            canvas.restore()
        }

        return bmp
    }

    fun getSize(): Int = size
}

/**
 * Custom Google Maps style red teardrop destination pin marker bitmap.
 */
class DestinationPin(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val width = (28 * density).roundToInt()
    private val height = (38 * density).roundToInt()

    fun createBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = width / 2f
        val headRadius = width * 0.44f
        val cy = headRadius + 2f * density

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x44000000
            style = Paint.Style.FILL
        }
        val shadowRect = android.graphics.RectF(
            cx - width * 0.32f,
            height - 5f * density,
            cx + width * 0.32f,
            height.toFloat()
        )
        canvas.drawOval(shadowRect, shadowPaint)

        val pinPath = Path()
        val tipY = height - 4f * density
        pinPath.moveTo(cx, tipY)
        pinPath.lineTo(cx + headRadius, cy)
        pinPath.arcTo(
            android.graphics.RectF(cx - headRadius, cy - headRadius, cx + headRadius, cy + headRadius),
            0f,
            -180f,
            false
        )
        pinPath.lineTo(cx, tipY)
        pinPath.close()

        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEA4335.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawPath(pinPath, pinPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        canvas.drawPath(pinPath, borderPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB31412.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, headRadius * 0.38f, dotPaint)

        return bmp
    }
}

/**
 * Maps Mapbox congestion annotation strings to colors for route rendering.
 */
object CongestionColors {

    fun forLevel(level: String): Int = when (level) {
        "low" -> COLOR_LOW
        "moderate" -> COLOR_MODERATE
        "heavy" -> COLOR_HEAVY
        "severe" -> COLOR_SEVERE
        else -> COLOR_UNKNOWN
    }

    fun widthFor(level: String): Float = when (level) {
        "heavy", "severe" -> 1.3f
        "moderate" -> 1.1f
        else -> 1.0f
    }

    private const val COLOR_LOW = 0xFF1A73E8.toInt()
    private const val COLOR_MODERATE = 0xFFF9AB00.toInt()
    private const val COLOR_HEAVY = 0xFFE8710A.toInt()
    private const val COLOR_SEVERE = 0xFFD93025.toInt()
    private const val COLOR_UNKNOWN = 0xFF1A73E8.toInt()
}

/** Navigation display formatting utilities shared across HUD components. */
object NavFormatter {

    fun formatDuration(minutes: Int): String = when {
        minutes < 1 -> "1 min"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} hr"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }

    fun formatDistance(miles: Double): String = when {
        miles < 0.1 -> "${((miles * 5280 / 10).roundToInt() * 10).coerceAtLeast(10)} ft"
        miles < 100 -> "${"%.1f".format(miles)} mi"
        else -> "${miles.roundToInt()} mi"
    }

    fun formatEta(minutesFromNow: Int): String {
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, minutesFromNow) }
        return SimpleDateFormat("h:mm a", Locale.US).format(cal.time)
    }

    fun speedMsToMph(ms: Double): Int = (ms * Units.MS_TO_MPH).roundToInt()

    fun metersToMiles(m: Double): Double = m * Units.METERS_TO_MILES

    fun metersToFeet(m: Double): Double = m * 3.28084
}

/**
 * Utility to track route progress during navigation.
 */
object RouteProgressHelper {

    fun findClosestIndex(route: List<LatLng>, current: LatLng): Int {
        var minDist = Double.MAX_VALUE; var idx = 0
        for (i in route.indices) {
            val d = GuidanceMath.haversineM(
                current.latitude, current.longitude,
                route[i].latitude, route[i].longitude
            )
            if (d < minDist) { minDist = d; idx = i }
        }
        return idx
    }

    fun remainingDistanceM(route: List<LatLng>, fromIndex: Int): Double {
        var total = 0.0
        for (i in fromIndex until route.size - 1) {
            total += GuidanceMath.haversineM(
                route[i].latitude, route[i].longitude,
                route[i + 1].latitude, route[i + 1].longitude
            )
        }
        return total
    }

}
