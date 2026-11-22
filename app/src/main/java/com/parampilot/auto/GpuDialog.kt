package com.parampilot.auto

import android.app.Activity

/**
 * GPU details dialog (Param's ask, 2026-11-06 — the last feature
 * before his freeze): tapping the phone HUD's GPU pill shows every
 * GPU field Galaxy's /api/system/monitor carries, parsed by the
 * layer-2 [GpuStatus] from the payload the poll already stores.
 * Fields the dock isn't reporting render as "—", exactly like
 * Galaxy's own System Monitor page — nothing is invented.
 *
 * Addendum 132: rendered as an M3 sheet (M3Sheet), like Settings.
 */
object GpuDialog {

    fun show(activity: Activity) {
        val (dialog, body, pal) = M3Sheet.create(activity, "GPU")

        fun addRow(label: String, value: String) =
            M3Sheet.addRow(body, activity, pal, label, value)

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

        body.addView(
            M3Sheet.buttonRow(
                activity,
                M3Sheet.primaryButton(activity, pal, "Done") { dialog.dismiss() }
            )
        )
        dialog.show()
    }
}
