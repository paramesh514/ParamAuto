package com.parampilot.auto

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Recording video play/download model (Param, 2026-11-20).
 *
 * Contract pinned from Dom source (galaxy/server.py do_GET,
 * galaxy/recording_http.py camera_video/send_recording, web/js
 * recording-data.js driveVideoUrl):
 *
 *   GET /api/recordings/route/<routeId>/<camera>
 *
 * streams the route's whole video for one camera as video/mp4,
 * with Range support (206 + Accept-Ranges: bytes), behind the
 * Galaxy session. Cameras: qcamera (the small "quick" road video),
 * fcamera (full-quality road), dcamera (driver), ecamera (wide).
 * Like every recordings read it is LOCAL-ONLY: through the Galaxy
 * Cloud tunnel the comma answers 403 {"code": "local_only"}. A
 * first GET may take a while — the comma prepares (concatenates)
 * the route video before streaming it.
 */
object RecordingMedia {

    data class Camera(val id: String, val label: String, val hint: String)

    /** Picker order: the quick road video first (smallest, fastest). */
    val CAMERAS: List<Camera> = listOf(
        Camera("qcamera", "Road — quick", "Small file, starts fast"),
        Camera("fcamera", "Road — full quality", "The big original video"),
        Camera("dcamera", "Driver", "Driver-facing camera"),
        Camera("ecamera", "Wide", "Wide-angle road camera")
    )

    fun camera(id: String): Camera? = CAMERAS.firstOrNull { it.id == id }

    /** The combined whole-route video path for one camera. */
    fun routeVideoPath(routeId: String, cameraId: String): String =
        "/api/recordings/route/" +
            URLEncoder.encode(routeId, "UTF-8") + "/" + cameraId

    /**
     * Which cameras this route actually has video for, from its
     * detail record (the videoBytes keys), in picker order. Null
     * when the detail is missing or names no known camera — the
     * caller then offers all four and lets the comma answer.
     */
    fun camerasFromDetail(routeJson: JSONObject?): List<Camera>? {
        val video = routeJson?.optJSONObject("videoBytes") ?: return null
        val present = ArrayList<Camera>()
        for (cam in CAMERAS) {
            val bytes = video.opt(cam.id) as? Number
            if (bytes != null && bytes.toLong() > 0) present.add(cam)
        }
        return present.ifEmpty { null }
    }

    /** A Downloads-safe file name: "<name> - <camera>.mp4". */
    fun downloadFileName(displayName: String, cameraId: String): String {
        val cleaned = displayName
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', ' ')
            .take(60)
            .trim('-', '.')
        val base = cleaned.ifEmpty { "recording" }
        return "$base-$cameraId.mp4"
    }
}
