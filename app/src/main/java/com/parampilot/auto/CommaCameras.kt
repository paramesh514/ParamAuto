package com.parampilot.auto

import org.json.JSONObject

/**
 * Layer-2 facts behind the Comma cameras feature (Day 6, 2026-11-05):
 * POST /api/cameras/snapshot on the comma. Source-checked against
 * StarPilot's Galaxy server the same morning:
 *
 * - The request body is EXACTLY {"camera": "<id>"} — any extra key
 *   earns a 400 "Choose a camera".
 * - Camera ids are exactly "wide", "narrow", "cabin".
 * - The server answers only while the car is PARKED, with a fresh
 *   connection and a Galaxy identity: 409 when that doesn't hold,
 *   503 when no fresh frame exists, 400 for an unknown camera.
 * - Success is a raw JPEG (SOI FFD8 … EOI FFD9, ≤ 1 MB); capture can
 *   take up to ~12 s (24 s for the cabin camera).
 */
object CommaCameras {

    data class Camera(val id: String, val label: String)

    val ALL: List<Camera> = listOf(
        Camera("wide", "Road wide"),
        Camera("narrow", "Road narrow"),
        Camera("cabin", "Cabin")
    )

    /** The exact request body — one key, nothing else. */
    fun requestBody(cameraId: String): JSONObject =
        JSONObject().put("camera", cameraId)

    /** Same framing check the server applies to its own captures. */
    fun isJpeg(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 4) return false
        return bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()
    }

    /**
     * What the user reads when a snapshot fails: the comma's own
     * error text whenever it sent one (Param's error rule — failures
     * surface, never silently), a plain fallback per status otherwise.
     */
    fun errorMessage(code: Int, serverError: String?): String = when {
        !serverError.isNullOrBlank() -> serverError
        code == 400 -> "Choose a supported camera"
        code == 401 -> "Sign in to Galaxy first"
        code == 409 -> "Needs a fresh parked connection to the comma"
        code == 503 -> "No fresh camera frame — try taking a new snapshot"
        code == -1 -> "Couldn't reach the comma"
        else -> "Camera request failed (HTTP $code)"
    }
}
