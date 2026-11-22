package com.parampilot.auto

import org.json.JSONObject

/**
 * A model download running on the comma, parsed from the top level of
 * the /api/models/manager payload (Addendum 135). The comma reports
 * the state all along — `downloading`, `modelToDownload`, the
 * human-readable `progress` line and the structured `downloadProgress`
 * counters — but the app only ever read the per-model entries, so a
 * download in progress was invisible in the app: the model just sat
 * there looking not-installed while the device was mid-download.
 * Pure parsing; ModelManagerRepository keeps the latest payload.
 */
data class ModelDownloadState(
    /** Model id being downloaded ("" when the payload doesn't name one). */
    val model: String,
    /** The comma's own progress line, e.g. "Fairy Tale V1.1: 46%". */
    val progressText: String,
    /** received/total as a whole percent; null when not reported. */
    val percent: Int?,
    /** downloadProgress.phase, e.g. "downloading". */
    val phase: String,
    /** downloadProgress.error; empty when there is none. */
    val error: String
) {
    /** The single line the UI shows for this download. */
    fun displayText(): String = when {
        error.isNotEmpty() -> error
        progressText.isNotEmpty() -> progressText
        percent != null -> "Downloading… $percent%"
        else -> "Downloading…"
    }
}

object ModelDownload {

    /**
     * The active download in a manager payload, or null when the comma
     * is not downloading. `downloading` is the master flag: when it
     * clears, the download is over (installed or gone) and the model
     * entries say the rest.
     */
    fun parse(manager: JSONObject?): ModelDownloadState? {
        if (manager == null) return null
        if (!manager.optBoolean("downloading", false)) return null
        val progress = manager.optJSONObject("downloadProgress")
        val model = manager.optString("modelToDownload", "")
            .ifEmpty { progress?.optString("model", "").orEmpty() }
        val received = progress?.optLong("received", 0L) ?: 0L
        val total = progress?.optLong("total", 0L) ?: 0L
        return ModelDownloadState(
            model = model,
            progressText = manager.optString("progress", ""),
            percent = percent(received, total),
            phase = progress?.optString("phase", "").orEmpty(),
            error = progress?.optString("error", "").orEmpty()
        )
    }

    /** Whole-percent downloaded; null when the totals aren't usable. */
    fun percent(received: Long, total: Long): Int? =
        if (total > 0L && received >= 0L) {
            (received * 100L / total).toInt().coerceIn(0, 100)
        } else {
            null
        }
}
