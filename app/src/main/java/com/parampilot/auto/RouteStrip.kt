package com.parampilot.auto

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * The one-line route strip stolen from the comma's projected UI: the whole
 * route polyline squeezed into a pill at the bottom of the Android Auto
 * surface, with the traveled part dimmed and the remaining part lit, plus a
 * position dot. Gives route progress at a glance without a full map.
 */
object RouteStrip {

    fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        route: RouteOption,
        gps: GpsPosition?,
        night: Boolean,
        density: Float
    ) {
        val pts = route.points
        if (pts.size < 2) return

        val stripH = 30f * density
        val marginX = 14f * density
        val bottom = h - 10f * density
        val top = bottom - stripH
        val left = marginX
        val right = w - marginX
        if (right - left < 80f) return

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (night) 0xCC0B1220.toInt() else 0xD9FFFFFF.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(left, top, right, bottom, stripH / 2f, stripH / 2f, bg)

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        for (p in pts) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLng) minLng = p.longitude
            if (p.longitude > maxLng) maxLng = p.longitude
        }
        val spanLat = (maxLat - minLat).takeIf { it > 1e-9 } ?: 1e-9
        val spanLng = (maxLng - minLng).takeIf { it > 1e-9 } ?: 1e-9

        val padX = 12f * density
        val padY = 7f * density
        val innerL = left + padX
        val innerR = right - padX
        val innerT = top + padY
        val innerB = bottom - padY
        fun sx(lng: Double): Float = innerL + ((lng - minLng) / spanLng).toFloat() * (innerR - innerL)
        fun sy(lat: Double): Float = innerB - ((lat - minLat) / spanLat).toFloat() * (innerB - innerT)

        val progress = gps?.let { RouteProgress.compute(pts, it.latitude, it.longitude) }
        val splitSeg = progress?.segmentIndex ?: -1

        val traveledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val remainingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF38BDF8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        if (progress == null) {
            val full = Path()
            for (i in pts.indices) {
                val x = sx(pts[i].longitude)
                val y = sy(pts[i].latitude)
                if (i == 0) full.moveTo(x, y) else full.lineTo(x, y)
            }
            canvas.drawPath(full, remainingPaint)
            return
        }

        val traveled = Path()
        val remaining = Path()
        for (i in 0 until pts.size - 1) {
            val x0 = sx(pts[i].longitude)
            val y0 = sy(pts[i].latitude)
            val x1 = sx(pts[i + 1].longitude)
            val y1 = sy(pts[i + 1].latitude)
            if (i < splitSeg) {
                if (i == 0) traveled.moveTo(x0, y0)
                traveled.lineTo(x1, y1)
            } else {
                if (i == splitSeg) remaining.moveTo(x0, y0)
                remaining.lineTo(x1, y1)
            }
        }
        canvas.drawPath(traveled, traveledPaint)
        canvas.drawPath(remaining, remainingPaint)

        // Position dot on the split segment.
        val a = pts[splitSeg]
        val b = pts[minOf(splitSeg + 1, pts.size - 1)]
        val t = progress.segmentT.toFloat()
        val dx = sx(a.longitude) + (sx(b.longitude) - sx(a.longitude)) * t
        val dy = sy(a.latitude) + (sy(b.latitude) - sy(a.latitude)) * t
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B1220.toInt(); style = Paint.Style.FILL }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
        canvas.drawCircle(dx, dy, 5.5f * density, ring)
        canvas.drawCircle(dx, dy, 3.5f * density, dot)
    }
}
