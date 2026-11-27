package com.parampilot.auto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * The live road scene for the VISUAL settings surface (Addendum
 * 148): a top-down road that reflects the settings page's real
 * values — the lead car sits at the following gap, centering
 * springs stiffen with lane strength, the road bends with the
 * curve setting, and a speed sign shows the limit/offset value.
 * Pure presentation: it draws the [SettingsScene.Scene] it is
 * given and holds no state of its own beyond it.
 */
class RoadSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var accentColor: Int = Color.rgb(0x6C, 0xA8, 0xFF)
    private var scene: SettingsScene.Scene = SettingsScene.Scene()

    fun update(newScene: SettingsScene.Scene) {
        scene = newScene
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val asphalt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x23, 0x27, 0x2E)
        style = Paint.Style.FILL
    }
    private val shoulder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x2E, 0x34, 0x3E)
        style = Paint.Style.FILL
    }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        pathEffect = DashPathEffect(floatArrayOf(dp(14f), dp(12f)), 0f)
    }
    private val springPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0x6C, 0xA8, 0xFF)
    }
    private val carBody = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x18, 0x1C, 0x22)
        style = Paint.Style.FILL
    }
    private val signRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xD8, 0x3A, 0x2E)
        style = Paint.Style.FILL
    }
    private val signFace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val signText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x18, 0x1C, 0x22)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val gapText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xC9, 0xD4, 0xE3)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val gapLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x8A, 0x96, 0xA8)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    /** Horizontal bend of the road at height fraction t (0 = bottom). */
    private fun bendAt(t: Float): Float =
        (scene.curve01 ?: 0.0).toFloat() * dp(46f) * t * t

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Road: a band that narrows toward the top (perspective-lite)
        // and bends with the curve setting.
        val bottomHalf = w * 0.34f
        val topHalf = w * 0.24f
        val cx = w / 2f
        val road = Path().apply {
            moveTo(cx - bottomHalf, h)
            quadTo(
                cx - (bottomHalf + topHalf) / 2f + bendAt(0.5f), h / 2f,
                cx - topHalf + bendAt(1f), 0f
            )
            lineTo(cx + topHalf + bendAt(1f), 0f)
            quadTo(
                cx + (bottomHalf + topHalf) / 2f + bendAt(0.5f), h / 2f,
                cx + bottomHalf, h
            )
            close()
        }
        canvas.drawPath(road, shoulder)
        val inner = Path().apply {
            moveTo(cx - bottomHalf + dp(7f), h)
            quadTo(
                cx - (bottomHalf + topHalf) / 2f + bendAt(0.5f), h / 2f,
                cx - topHalf + dp(5f) + bendAt(1f), 0f
            )
            lineTo(cx + topHalf - dp(5f) + bendAt(1f), 0f)
            quadTo(
                cx + (bottomHalf + topHalf) / 2f + bendAt(0.5f), h / 2f,
                cx + bottomHalf - dp(7f), h
            )
            close()
        }
        canvas.drawPath(inner, asphalt)

        // Lane divider lines (two lanes), following the bend.
        lanePaint.color = if (scene.anyOn) accentColor else Color.rgb(0x9A, 0xA5, 0xB5)
        for (laneFraction in listOf(-0.34f, 0.34f)) {
            val line = Path().apply {
                val steps = 16
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val y = h * (1f - t)
                    val half = bottomHalf + (topHalf - bottomHalf) * t
                    val x = cx + laneFraction * half + bendAt(t)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            canvas.drawPath(line, lanePaint)
        }

        val egoY = h * 0.74f
        val egoHalfW = dp(17f)
        val egoHalfH = dp(29f)

        // Centering springs: car to lane edges, stiffer = thicker.
        scene.laneStrength01?.let { s ->
            springPaint.strokeWidth = dp(1.5f) + dp(4.5f) * s.toFloat()
            springPaint.alpha = (70 + 160 * s).toInt().coerceIn(0, 255)
            val laneHalfAtEgo = bottomHalf + (topHalf - bottomHalf) * 0.26f
            canvas.drawLine(
                cx - egoHalfW, egoY,
                cx - laneHalfAtEgo * 0.34f - dp(2f), egoY, springPaint
            )
            canvas.drawLine(
                cx + egoHalfW, egoY,
                cx + laneHalfAtEgo * 0.34f + dp(2f), egoY, springPaint
            )
        }

        // Lead car at the following gap.
        scene.followGapSec?.let { gap ->
            val frac = ((gap - 0.5) / 2.5).coerceIn(0.0, 1.0).toFloat()
            val leadY = egoY - dp(52f) - frac * h * 0.34f
            val leadCx = cx + bendAt(1f - leadY / h)
            drawCar(canvas, leadCx, leadY, dp(13f), dp(22f), Color.rgb(0xB9, 0xC2, 0xCE))
            // Gap bracket + label on the right lane edge.
            val bx = cx + bottomHalf * 0.52f
            canvas.drawLine(bx, leadY, bx, egoY - egoHalfH, gapLine)
            canvas.drawLine(bx - dp(4f), leadY, bx + dp(4f), leadY, gapLine)
            canvas.drawLine(bx - dp(4f), egoY - egoHalfH, bx + dp(4f), egoY - egoHalfH, gapLine)
            gapText.textSize = dp(12f)
            canvas.drawText(
                "${GalaxySettings.formatNumber(gap)} s",
                bx + dp(16f), (leadY + egoY) / 2f + dp(4f), gapText
            )
        }

        // Ego car.
        drawCar(canvas, cx, egoY, egoHalfW, egoHalfH, accentColor)

        // Speed sign at the roadside.
        scene.speedSign?.let { sign ->
            val r = dp(21f)
            val sx = cx + bottomHalf + dp(30f)
            val sy = h * 0.30f
            if (sx + r < w) {
                canvas.drawCircle(sx, sy, r, signRing)
                canvas.drawCircle(sx, sy, r - dp(4.5f), signFace)
                signText.textSize = dp(12.5f)
                canvas.drawText(sign.take(7), sx, sy + dp(4.5f), signText)
            }
        }
    }

    private fun drawCar(
        canvas: Canvas, cx: Float, cy: Float,
        halfW: Float, halfH: Float, color: Int
    ) {
        carBody.color = color
        val body = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        canvas.drawRoundRect(body, halfW * 0.45f, halfW * 0.45f, carBody)
        val shield = RectF(
            cx - halfW * 0.78f, cy - halfH * 0.52f,
            cx + halfW * 0.78f, cy - halfH * 0.08f
        )
        canvas.drawRoundRect(shield, dp(3f), dp(3f), glass)
        val rear = RectF(
            cx - halfW * 0.78f, cy + halfH * 0.18f,
            cx + halfW * 0.78f, cy + halfH * 0.52f
        )
        canvas.drawRoundRect(rear, dp(3f), dp(3f), glass)
    }
}

/**
 * A slider drawn as a gauge: a filled track with step ticks and a
 * thumb, for the visual slider cards. Presentation only — the card
 * owns the value and the commit flow.
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var accentColor: Int = Color.rgb(0x6C, 0xA8, 0xFF)
    private var fraction: Double = 0.0
    private var ticks: Int = 0

    fun setValue(newFraction: Double, newTicks: Int) {
        fraction = newFraction.coerceIn(0.0, 1.0)
        ticks = newTicks.coerceIn(0, 41)
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x3A, 0x41, 0x4E)
        style = Paint.Style.FILL
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x8A, 0x96, 0xA8)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(26f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        if (w == 0f) return
        val midY = dp(10f)
        val left = dp(2f)
        val right = w - dp(2f)
        val trackRect = RectF(left, midY - dp(5f), right, midY + dp(5f))
        canvas.drawRoundRect(trackRect, dp(5f), dp(5f), track)
        val fillRight = left + (right - left) * fraction.toFloat()
        fill.color = accentColor
        val fillRect = RectF(left, midY - dp(5f), fillRight, midY + dp(5f))
        canvas.drawRoundRect(fillRect, dp(5f), dp(5f), fill)
        if (ticks > 1) {
            for (i in 0..ticks) {
                val x = left + (right - left) * (i / ticks.toFloat())
                canvas.drawLine(x, midY + dp(8f), x, midY + dp(12f), tick)
            }
        }
        canvas.drawCircle(fillRight, midY, dp(8f), thumb)
        fill.color = accentColor
        canvas.drawCircle(fillRight, midY, dp(4.5f), fill)
    }
}
