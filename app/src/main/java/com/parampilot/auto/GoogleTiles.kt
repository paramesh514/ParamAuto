package com.parampilot.auto

import org.json.JSONObject

/**
 * Layer 2: Google Map Tiles API for the phone map's Google source.
 *
 * Google's tiles are served by the Map Tiles API, which needs a
 * session token: POST /v1/createSession returns {session, expiry};
 * tiles then load from /v1/2dtiles/{z}/{x}/{y}?session=…&key=… .
 * This object is the pure half — session parsing, freshness, the
 * tile URL template and the MapLibre raster style JSON (modeled on
 * the comma raster style). The createSession network call lives in
 * NavigationManager; the session state lives in NavStateManager.
 *
 * Pure: values in, values out.
 */
object GoogleTiles {

    data class Session(val token: String, val expiryEpochSec: Long)

    /** Parses a createSession response; null when it carries no usable session. */
    fun parseSession(json: JSONObject): Session? {
        val token = json.optString("session", "")
        if (token.isBlank()) return null
        val expiry = json.optString("expiry", "").toLongOrNull() ?: return null
        return Session(token, expiry)
    }

    /**
     * A session counts as usable only with at least an hour of life
     * left, so a style built from it never starts with tiles that are
     * about to expire mid-drive.
     */
    fun sessionUsable(session: Session?, nowEpochSec: Long): Boolean =
        session != null && session.expiryEpochSec > nowEpochSec + 3600

    fun tileUrlTemplate(session: String, key: String): String =
        "https://tile.googleapis.com/v1/2dtiles/{z}/{x}/{y}?session=$session&key=$key"

    /**
     * MapLibre raster style JSON for Google roadmap tiles. Google
     * serves one (light) roadmap look through this API — there is no
     * dark variant — so night only changes the letterbox background.
     */
    fun styleJson(session: String, key: String, isNight: Boolean): String {
        val bg = if (isNight) "#0B0E13" else "#E8EAED"
        val tiles = tileUrlTemplate(session, key)
        return """
        {
          "version": 8,
          "sources": {
            "google-raster-tiles": {
              "type": "raster",
              "tiles": [
                "$tiles"
              ],
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 19,
              "attribution": "© Google"
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "$bg" }
            },
            {
              "id": "google-raster-layer",
              "type": "raster",
              "source": "google-raster-tiles",
              "paint": { "raster-opacity": 1.0 }
            }
          ]
        }
        """.trimIndent()
    }
}
