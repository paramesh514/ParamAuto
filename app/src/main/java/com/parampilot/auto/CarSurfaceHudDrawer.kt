package com.parampilot.auto

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Clean Google Maps style Speed Limit Sign & Digital Speedometer for Android Auto Canvas.
 */
class CarSurfaceHudDrawer {

    /** Speed-limit sign height (Param, 2026-11-05: "Increase speed limit
     *  height" — the 41f sign was hard to read at a glance; the value is
     *  the glanceable part, so the sign and its number both grew). */
    private val signH = 58f

    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val speedUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA0A6.toInt()
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val signBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val signBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F1F1F.toInt()
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
    }

    private val signTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F1F1F.toInt()
        textSize = 8.5f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val signValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F1F1F.toInt()
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val cardDropShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44000000
        style = Paint.Style.FILL
    }

    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1F20.toInt()
        style = Paint.Style.FILL
    }

    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3C4043.toInt()
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

        private val engagementBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val offsetBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A2A44.toInt()
        style = Paint.Style.FILL
    }

    private val offsetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4285F4.toInt()
        strokeWidth = 1.2f
        style = Paint.Style.STROKE
    }

    private val offsetTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8C7FA.toInt()
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    fun draw(
        canvas: Canvas,
        startX: Float,
        bottomY: Float,
        speedMps: Double,
        slcLimitMph: Int?,
        slcOffsetMph: Int,
        controlsActive: Boolean,
        lateralActive: Boolean,
        longitudinalActive: Boolean,
        @Suppress("UNUSED_PARAMETER") lateralPid: Double = 0.0,
        isNight: Boolean = false,
        speedLimitSourceLabel: String? = null
    ) {
        val rawMph = (speedMps * Units.MS_TO_MPH).coerceAtLeast(0.0)
        val speedMph = SpeedometerSmoother.filter(rawMph)
        val limit = slcLimitMph ?: 0

        val signW = 46f
        val offsetH = 26f // Doubled vertical size (was 13f)
        val gap = 2f
        val totalSignH = signH + gap + offsetH
        val speedBoxW = 58f
        val speedBoxH = 56f
        val spacing = 8f

        val signTop = bottomY - totalSignH
        drawSpeedLimitSign(canvas, startX, signTop, signW, limit, slcOffsetMph, speedLimitSourceLabel)

        val speedLeft = startX + signW + spacing
        val speedTop = bottomY - speedBoxH
        drawSpeedometerCard(
            canvas = canvas,
            x = speedLeft,
            y = speedTop,
            w = speedBoxW,
            h = speedBoxH,
            speedMph = speedMph,
            isSpeeding = limit > 0 && speedMph > limit,
            isNight = isNight,
            controlsActive = controlsActive,
            lateralActive = lateralActive,
            longitudinalActive = longitudinalActive
        )
    }

    private val sourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 10f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private fun drawSpeedLimitSign(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        limit: Int,
        offsetMph: Int,
        sourceLabel: String? = null
    ) {
        val offsetH = 26f
        val gap = 2f

        // 1. MUTCD Main Sign
        val signRect = RectF(x, y, x + w, y + signH)
        canvas.drawRoundRect(RectF(signRect.left, signRect.top + 2f, signRect.right, signRect.bottom + 2f), 6f, 6f, cardDropShadow)
        canvas.drawRoundRect(signRect, 6f, 6f, signBgPaint)
        canvas.drawRoundRect(signRect, 6f, 6f, signBorderPaint)

        val cx = signRect.centerX()
        canvas.drawText("SPEED", cx, y + 13f, signTitlePaint)
        canvas.drawText("LIMIT", cx, y + 23f, signTitlePaint)

        val limitStr = if (limit > 0) "$limit" else "--"
        signValuePaint.textSize = if (limit >= 100) 20f else 26f
        canvas.drawText(limitStr, cx, y + 51f, signValuePaint)

        // 2. Attached offset box directly below the sign
        val offsetY = y + signH + gap
        val offsetRect = RectF(x, offsetY, x + w, offsetY + offsetH)
        canvas.drawRoundRect(offsetRect, 4f, 4f, offsetBgPaint)
        canvas.drawRoundRect(offsetRect, 4f, 4f, offsetBorderPaint)

        val offsetStr = if (limit > 0) {
            if (offsetMph >= 0) "+$offsetMph" else "$offsetMph"
        } else "--"
        canvas.drawText(offsetStr, cx, offsetY + 18f, offsetTextPaint)

        // Provenance badge under the sign, same label the phone shows
        // (SpeedLimitSource: "comma" / "route"); absent when there is
        // no sourced limit to attribute.
        if (sourceLabel != null) {
            canvas.drawText(sourceLabel, cx, offsetY + offsetH + 13f, sourcePaint)
        }
    }

    private fun drawSpeedometerCard(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        speedMph: Int,
        isSpeeding: Boolean,
        isNight: Boolean,
        controlsActive: Boolean,
        lateralActive: Boolean,
        longitudinalActive: Boolean
    ) {
        val cardRect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(RectF(cardRect.left, cardRect.top + 2f, cardRect.right, cardRect.bottom + 2f), 14f, 14f, cardDropShadow)

        // Google Maps look (GoogleMapsLook): white card by day, dark
        // gray by night, Google red while over the limit.
        cardBgPaint.color = GoogleMapsLook.speedCardBg(isNight, isSpeeding)
        canvas.drawRoundRect(cardRect, 14f, 14f, cardBgPaint)
        if (!isSpeeding) {
            canvas.drawRoundRect(cardRect, 14f, 14f, cardBorderPaint)
        }

        val cx = cardRect.centerX()
        speedPaint.color = GoogleMapsLook.speedCardText(isNight, isSpeeding)
        speedUnitPaint.color = GoogleMapsLook.speedCardUnit(isNight, isSpeeding)
        canvas.drawText("$speedMph", cx, y + 32f, speedPaint)
        canvas.drawText("mph", cx, y + 46f, speedUnitPaint)

        // Subtle 3px engagement indicator bar at bottom of speedometer card when openpilot is active
        if (controlsActive || lateralActive || longitudinalActive) {
            engagementBarPaint.color = when {
                controlsActive && lateralActive -> 0xFF34A853.toInt() // Google Green
                lateralActive -> 0xFF4285F4.toInt()                  // Google Blue
                else -> 0xFF8AB4F8.toInt()
            }
            val barW = w * 0.45f
            val barRect = RectF(cx - barW / 2f, y + h - 5f, cx + barW / 2f, y + h - 2f)
            canvas.drawRoundRect(barRect, 2f, 2f, engagementBarPaint)
        }
    }
}
