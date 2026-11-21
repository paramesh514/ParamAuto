package com.parampilot.auto

import java.net.HttpURLConnection
import java.net.URL

/**
 * Phone-side expansion of shared short links (Param, 2026-11-18:
 * "Don't want risk comma side. Do in phone"). A Google Maps short
 * link (maps.app.goo.gl/…) is only an HTTP redirect — the phone
 * follows the redirect chain itself, reads the final Google Maps
 * URL, and hands it back to SharedTextParser. Nothing is sent to
 * the comma for resolution.
 *
 * Only headers are read; the body is never downloaded. The chain is
 * bounded ([MAX_HOPS]) and every hop must stay on http(s).
 */
object LinkExpander {

    const val MAX_HOPS = 6

    sealed class Result {
        data class Expanded(val finalUrl: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun isRedirect(code: Int): Boolean =
        code == 301 || code == 302 || code == 303 || code == 307 || code == 308

    /**
     * Resolves a redirect's Location against the URL that produced
     * it (absolute, scheme-relative and path-relative forms). Null
     * when the result is not an http(s) URL.
     */
    fun resolveLocation(currentUrl: String, location: String): String? {
        val loc = location.trim()
        if (loc.isEmpty()) return null
        val resolved = try {
            URL(URL(currentUrl), loc).toString()
        } catch (e: Exception) {
            return null
        }
        return if (resolved.startsWith("http://") || resolved.startsWith("https://")) {
            resolved
        } else {
            null
        }
    }

    /** Follows [url]'s redirect chain on the calling (background) thread. */
    fun expand(url: String): Result {
        var current = url
        var hops = 0
        while (true) {
            val conn = try {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) ParamAuto/1.0"
                    )
                }
            } catch (e: Exception) {
                return Result.Failed("couldn't open the link (${e.message ?: "network error"})")
            }
            try {
                val code = conn.responseCode
                if (!isRedirect(code)) {
                    return Result.Expanded(current)
                }
                val location = conn.getHeaderField("Location")
                    ?: return Result.Failed("the link didn't lead anywhere (HTTP $code)")
                val next = resolveLocation(current, location)
                    ?: return Result.Failed("the link led somewhere unreadable")
                hops++
                if (hops > MAX_HOPS) {
                    return Result.Failed("the link kept redirecting")
                }
                current = next
            } catch (e: Exception) {
                return Result.Failed("couldn't open the link (${e.message ?: "network error"})")
            } finally {
                conn.disconnect()
            }
        }
    }
}
