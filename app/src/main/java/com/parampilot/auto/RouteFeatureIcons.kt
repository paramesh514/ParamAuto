package com.parampilot.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Phone-map marker icons for route features (Param, 2026-11-10):
 * a traffic-signal glyph and a stop-sign glyph, drawn the same way
 * as the other canvas-drawn map icons (VehicleChevron,
 * DestinationPin) so the phone map and the Android Auto canvas
 * renderer show the same symbols.
 */
class TrafficSignalIcon(context: Context) {

    private val density = context.resources.displayMetrics.density

    fun createBitmap(): Bitmap {
        val w = (24 * density).roundToInt()
        val h = (38 * density).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF212124.toInt()
            style = Paint.Style.FILL
        }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
        }
        val rect = RectF(2f * density, 2f * density, w - 2f * density, h - 2f * density)
        val corner = 5f * density
        canvas.drawRoundRect(rect, corner, corner, body)
        canvas.drawRoundRect(rect, corner, corner, rim)
        val cx = w / 2f
        val r = 3.6f * density
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF28B82.toInt() }
        val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFDD663.toInt() }
        val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF81C995.toInt() }
        canvas.drawCircle(cx, h * 0.26f, r, red)
        canvas.drawCircle(cx, h * 0.50f, r, yellow)
        canvas.drawCircle(cx, h * 0.74f, r, green)
        return bmp
    }
}

/** Red octagon with a white rim and STOP text. */
class StopSignIcon(context: Context) {

    private val density = context.resources.displayMetrics.density

    fun createBitmap(): Bitmap {
        val size = (36 * density).roundToInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val radius = size / 2f - 2f * density
        val path = Path()
        for (i in 0 until 8) {
            val ang = Math.toRadians(i * 45.0 + 22.5)
            val px = cx + (radius * cos(ang)).toFloat()
            val py = cy + (radius * sin(ang)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFC5221F.toInt()
            style = Paint.Style.FILL
        }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, rim)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = 10.5f * density
        }
        val tw = text.measureText("STOP")
        val fm = text.fontMetrics
        canvas.drawText("STOP", cx - tw / 2, cy - (fm.ascent + fm.descent) / 2, text)
        return bmp
    }
}
