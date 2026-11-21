package com.parampilot.auto

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * GPU details dialog (Param's ask, 2026-11-06 — the last feature
 * before his freeze): tapping the phone HUD's GPU pill shows every
 * GPU field Galaxy's /api/system/monitor carries, parsed by the
 * layer-2 [GpuStatus] from the payload the poll already stores.
 * Fields the dock isn't reporting render as "—", exactly like
 * Galaxy's own System Monitor page — nothing is invented.
 */
object GpuDialog {

    fun show(activity: Activity) {
        val den = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * den).roundToInt()

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = HudOverlay.makeCardBg(activity, HudOverlay.COLOR_SURFACE_DARK, 0)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        fun addRow(label: String, value: String) {
            val rowLabel = TextView(activity).apply {
                text = label
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(HudOverlay.COLOR_TEXT_MUTED)
                setPadding(0, dp(10), 0, 0)
            }
            body.addView(rowLabel)
            val rowValue = TextView(activity).apply {
                text = value
                textSize = 15f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            body.addView(rowValue)
        }

        val monitor = NavStateManager.lastSystemMonitor
        val gpu = GpuStatus.parse(monitor)
        if (gpu == null) {
            if (GpuStatus.availability(monitor) == GpuStatus.Companion.Availability.NO_GPU_SECTION) {
                addRow(
                    "GPU status",
                    "The comma is answering — its system monitor reports CPU, memory, storage and voltage — but its current software doesn't include a GPU section, so no GPU temperatures or VRAM are sent."
                )
                addRow(
                    "Galaxy shows the same",
                    "The comma's own Galaxy System Monitor page reads the same payload and shows the same blanks. If a future comma update restores the GPU section, this screen fills in by itself."
                )
            } else {
                addRow(
                    "GPU status",
                    "No monitor data yet — the comma hasn't sent a system snapshot on this connection."
                )
            }
        } else {
            addRow(
                "eGPU (Chestnut dock)",
                if (gpu.egpuReporting) "Reporting" else "Not reporting — Galaxy shows — for these fields too"
            )
            addRow("eGPU temperature", gpu.tempText(gpu.egpuEdgeTempC))
            addRow("eGPU hotspot temperature", gpu.tempText(gpu.egpuHotspotTempC))
            addRow("eGPU VRAM", gpu.vramText())
            addRow("Onboard GPU temperature", gpu.tempText(gpu.onboardGpuTempC))
            if (!gpu.egpuReporting) {
                addRow(
                    "If the dock should be live",
                    "Check its USB-C connection and power, then restart the comma — the big model and GPU reporting start at boot."
                )
            }
        }

        val scroll = ScrollView(activity).apply { addView(body) }
        AlertDialog.Builder(activity)
            .setTitle("GPU")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }
}
