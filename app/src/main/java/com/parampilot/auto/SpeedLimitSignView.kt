package com.parampilot.auto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Authentic MUTCD Speed Limit sign matching SVG:
 * <svg viewBox="0 0 118 148">
 *   <rect x="5" y="5" width="108" height="138" rx="11" fill="#ffffff" stroke="#151515" stroke-width="5"/>
 *   <text x="59" y="40">SPEED</text>
 *   <text x="59" y="63">LIMIT</text>
 *   <text x="59" y="122">35</text>
 * </svg>
 */
class SpeedLimitSignView(context: Context) : View(context) {

    var speedLimit: Int = 0
        private set

    var slcOffset: Int = 0
        private set

    private val rect = RectF(5f, 5f, 113f, 143f)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = 0xFF151515.toInt()
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF151515.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 19f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.05f
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF151515.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 56f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val offsetBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A2A44.toInt()
        style = Paint.Style.FILL
    }
    private val offsetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4285F4.toInt()
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    private val offsetTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8C7FA.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setLimit(limitMph: Int) {
        setLimitAndOffset(limitMph, slcOffset)
    }

    fun setLimitAndOffset(limitMph: Int, offsetMph: Int) {
        if (speedLimit != limitMph || slcOffset != offsetMph) {
            speedLimit = limitMph
            slcOffset = offsetMph
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val defaultWidth = (44 * density).roundToInt()
        val defaultHeight = (78 * density).roundToInt()
        setMeasuredDimension(
            resolveSize(defaultWidth, widthMeasureSpec),
            resolveSize(defaultHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val scale = min(w / 118f, h / 206f)
        canvas.save()
        canvas.translate((w - 118f * scale) / 2f, (h - 206f * scale) / 2f)
        canvas.scale(scale, scale)

        // Main Speed Limit Sign
        val signRect = RectF(5f, 4f, 113f, 140f)

        canvas.drawRoundRect(signRect, 14f, 14f, bgPaint)
        canvas.drawRoundRect(signRect, 14f, 14f, strokePaint)
        canvas.drawText("SPEED", 59f, 36f, headerPaint)
        canvas.drawText("LIMIT", 59f, 58f, headerPaint)

        val text = if (speedLimit > 0) "$speedLimit" else "--"
        numberPaint.textSize = if (speedLimit >= 100) 44f else 54f
        canvas.drawText(text, 59f, 116f, numberPaint)

        // Attached offset box at bottom (height 60f - doubled vertical size)
        val offsetRect = RectF(10f, 142f, 108f, 202f)
        canvas.drawRoundRect(offsetRect, 10f, 10f, offsetBgPaint)
        canvas.drawRoundRect(offsetRect, 10f, 10f, offsetBorderPaint)

        val offsetStr = if (speedLimit > 0) {
            if (slcOffset >= 0) "+$slcOffset" else "$slcOffset"
        } else "--"
        canvas.drawText(offsetStr, 59f, 185f, offsetTextPaint)

        canvas.restore()
    }
}

/**
 * Google Maps style rotating compass button.
 * Renders a circular card with red (North) and silver (South) needle,
 * rotating smoothly with map bearing.
 */
class CompassView(context: Context) : View(context) {

    private var bearingDeg: Double = 0.0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF1E1F20.toInt()
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFF3C4043.toInt()
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFEA4335.toInt()
    }
    private val southPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF9AA0A6.toInt()
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val path = Path()

    fun setBearing(bearing: Double) {
        if (bearingDeg != bearing) {
            bearingDeg = bearing
            rotation = -bearing.toFloat()
        }
    }

    fun applyTheme(isNight: Boolean) {
        bgPaint.color = if (isNight) 0xFF1E1F20.toInt() else Color.WHITE
        borderPaint.color = if (isNight) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt()
        southPaint.color = if (isNight) 0xFF9AA0A6.toInt() else 0xFF80868B.toInt()
        pivotPaint.color = if (isNight) 0xFF1E1F20.toInt() else Color.WHITE
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val size = (42 * density).roundToInt()
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - 1.5f

        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)

        val needleLen = radius * 0.56f
        val halfW = radius * 0.20f

        // North pointer (Google Red)
        path.reset()
        path.moveTo(cx, cy - needleLen)
        path.lineTo(cx - halfW, cy)
        path.lineTo(cx + halfW, cy)
        path.close()
        canvas.drawPath(path, northPaint)

        // South pointer (Google Grey)
        path.reset()
        path.moveTo(cx, cy + needleLen)
        path.lineTo(cx - halfW, cy)
        path.lineTo(cx + halfW, cy)
        path.close()
        canvas.drawPath(path, southPaint)

        canvas.drawCircle(cx, cy, radius * 0.10f, pivotPaint)
    }
}

/**
 * Google Maps style Alternate Routes button with branching Y-shape and dual arrows.
 * Tapping toggles alternate routes overview showing all possible paths on the full map.
 */
class AltRouteView(context: Context) : View(context) {

    private var isOverviewActive: Boolean = false
    private var isNight: Boolean = true

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
        strokeWidth = 2.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFE3E3E3.toInt()
    }
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 1.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFE3E3E3.toInt()
    }
    private val path = Path()

    fun applyTheme(night: Boolean) {
        isNight = night
        updateColors()
        invalidate()
    }

    fun setOverviewActive(active: Boolean) {
        if (isOverviewActive != active) {
            isOverviewActive = active
            updateColors()
            invalidate()
        }
    }

    private fun updateColors() {
        val activeBlue = if (isNight) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
        val col = if (isOverviewActive) activeBlue else (if (isNight) 0xFFE3E3E3.toInt() else 0xFF3C4043.toInt())
        iconPaint.color = col
        arrowFillPaint.color = col
        borderPaint.color = if (isOverviewActive) activeBlue else (if (isNight) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt())
        bgPaint.color = if (isOverviewActive) {
            if (isNight) 0xFF1A2B4C.toInt() else 0xFFE8F0FE.toInt()
        } else {
            if (isNight) 0xFF1E1F20.toInt() else Color.WHITE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val size = (42 * density).roundToInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 1.5f

        canvas.drawCircle(cx, cy, r, bgPaint)
        canvas.drawCircle(cx, cy, r, borderPaint)

        // Draw Google Maps Y-shape with dual arrows
        val stemStartY = cy + r * 0.42f
        val forkY = cy + r * 0.04f
        val leftX = cx - r * 0.36f
        val leftY = cy - r * 0.36f
        val rightX = cx + r * 0.36f
        val rightY = cy - r * 0.36f

        path.reset()
        path.moveTo(cx, stemStartY)
        path.lineTo(cx, forkY)
        path.quadTo(cx, cy - r * 0.14f, leftX, leftY)
        path.moveTo(cx, forkY)
        path.quadTo(cx, cy - r * 0.14f, rightX, rightY)
        canvas.drawPath(path, iconPaint)

        val arrLen = r * 0.20f
        path.reset()
        path.moveTo(leftX, leftY)
        path.lineTo(leftX + arrLen, leftY)
        path.lineTo(leftX, leftY + arrLen)
        path.close()
        canvas.drawPath(path, arrowFillPaint)

        path.reset()
        path.moveTo(rightX, rightY)
        path.lineTo(rightX - arrLen, rightY)
        path.lineTo(rightX, rightY + arrLen)
        path.close()
        canvas.drawPath(path, arrowFillPaint)
    }
}


/**
 * Material 3 GPS Sharing indicator button.
 * Renders a satellite in the middle with expanding radio signal waves
 * radiating outward, shown on top of the North-Up / Heading-Up icon
 * when the phone is actively feeding GPS fixes to the comma.
 */
class GpsSharingView(context: Context) : View(context) {

    private var isNight: Boolean = true

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF1A2B4C.toInt()
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFF8AB4F8.toInt()
    }
    private val panelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF263E69.toInt()
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF8AB4F8.toInt()
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = 0xFF8AB4F8.toInt()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF8AB4F8.toInt()
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = 0xFF8AB4F8.toInt()
    }

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (visibility == VISIBLE) {
                invalidate()
                postDelayed(this, 150L)
            }
        }
    }

    init {
        updateColors()
        contentDescription = "Sharing phone GPS with comma"
    }

    fun applyTheme(night: Boolean) {
        isNight = night
        updateColors()
        invalidate()
    }

    private fun updateColors() {
        val accent = if (isNight) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
        val bgCol = if (isNight) 0xFF1A2B4C.toInt() else 0xFFE8F0FE.toInt()
        val panelCol = if (isNight) 0xFF263E69.toInt() else 0xFFD2E3FC.toInt()
        bgPaint.color = bgCol
        borderPaint.color = accent
        panelFillPaint.color = panelCol
        iconPaint.color = accent
        gridPaint.color = accent
        dotPaint.color = accent
        wavePaint.color = accent
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            removeCallbacks(pulseRunnable)
            post(pulseRunnable)
        } else {
            removeCallbacks(pulseRunnable)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) {
            post(pulseRunnable)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(pulseRunnable)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val size = (42 * density).roundToInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 1.5f

        // Card background circle & border
        canvas.drawCircle(cx, cy, r, bgPaint)
        canvas.drawCircle(cx, cy, r, borderPaint)

        // Pulsing alpha for radiating signal waves (Param, 2026-11-04)
        val now = System.currentTimeMillis()
        val pulseAlpha = GpsFeedIndicator.alpha(now)
        wavePaint.alpha = pulseAlpha

        val satCy = cy - r * 0.08f
        val bodyW = r * 0.28f
        val bodyH = r * 0.36f

        // 1. Solar Panels & Struts
        val pw = r * 0.38f
        val ph = r * 0.24f
        val strutLen = r * 0.08f

        // Struts connecting satellite body to solar arrays
        canvas.drawLine(cx - bodyW / 2f, satCy, cx - bodyW / 2f - strutLen, satCy, iconPaint)
        canvas.drawLine(cx + bodyW / 2f, satCy, cx + bodyW / 2f + strutLen, satCy, iconPaint)

        // Left solar panel
        val leftX2 = cx - bodyW / 2f - strutLen
        val leftX1 = leftX2 - pw
        val leftRect = RectF(leftX1, satCy - ph / 2f, leftX2, satCy + ph / 2f)
        canvas.drawRoundRect(leftRect, r * 0.03f, r * 0.03f, panelFillPaint)
        canvas.drawRoundRect(leftRect, r * 0.03f, r * 0.03f, iconPaint)
        canvas.drawLine((leftX1 + leftX2) / 2f, leftRect.top, (leftX1 + leftX2) / 2f, leftRect.bottom, gridPaint)
        canvas.drawLine(leftRect.left, satCy, leftRect.right, satCy, gridPaint)

        // Right solar panel
        val rightX1 = cx + bodyW / 2f + strutLen
        val rightX2 = rightX1 + pw
        val rightRect = RectF(rightX1, satCy - ph / 2f, rightX2, satCy + ph / 2f)
        canvas.drawRoundRect(rightRect, r * 0.03f, r * 0.03f, panelFillPaint)
        canvas.drawRoundRect(rightRect, r * 0.03f, r * 0.03f, iconPaint)
        canvas.drawLine((rightX1 + rightX2) / 2f, rightRect.top, (rightX1 + rightX2) / 2f, rightRect.bottom, gridPaint)
        canvas.drawLine(rightRect.left, satCy, rightRect.right, satCy, gridPaint)

        // 2. Central Satellite Body
        val bodyRect = RectF(cx - bodyW / 2f, satCy - bodyH / 2f, cx + bodyW / 2f, satCy + bodyH / 2f)
        canvas.drawRoundRect(bodyRect, r * 0.06f, r * 0.06f, bgPaint)
        canvas.drawRoundRect(bodyRect, r * 0.06f, r * 0.06f, iconPaint)
        canvas.drawCircle(cx, satCy, r * 0.055f, dotPaint)

        // 3. Top Sensor Antenna
        val topAntennaLen = r * 0.15f
        canvas.drawLine(cx, bodyRect.top, cx, bodyRect.top - topAntennaLen, iconPaint)
        canvas.drawCircle(cx, bodyRect.top - topAntennaLen, r * 0.035f, dotPaint)

        // 4. Bottom Dish Antenna
        val txY = bodyRect.bottom + r * 0.08f
        val dishRect = RectF(cx - r * 0.10f, bodyRect.bottom - r * 0.03f, cx + r * 0.10f, bodyRect.bottom + r * 0.08f)
        canvas.drawArc(dishRect, 15f, 150f, false, iconPaint)
        canvas.drawCircle(cx, txY, r * 0.03f, dotPaint)

        // 5. Signal waves radiating downward and outward (expanding wavefronts)
        val waveR1 = r * 0.24f
        val waveR2 = r * 0.44f
        val waveR3 = r * 0.64f
        val waves = floatArrayOf(waveR1, waveR2, waveR3)
        for (wr in waves) {
            val waveRect = RectF(cx - wr, txY - wr, cx + wr, txY + wr)
            canvas.drawArc(waveRect, 42f, 96f, false, wavePaint)
        }
    }
}
