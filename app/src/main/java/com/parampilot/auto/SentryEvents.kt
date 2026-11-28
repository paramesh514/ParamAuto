package com.parampilot.auto

import org.json.JSONObject

/**
 * Sentry motion events (Galaxy-parity day 4, gap #19).
 *
 * Contract pinned from the StarPilot server source (origin/testing,
 * verified 2026-11-28; the same endpoints exist on origin/stable, so
 * no CommaBranch gate applies):
 *
 * - GET /api/sentry/events (Galaxy session required) answers
 *   {"schemaVersion": 1, "source": "local", "scanIncomplete": bool,
 *    "capacity": 512, "events": [{"eventId": "<32 lowercase hex>",
 *    "kind": "warning"|"alarm"|"selfie", "systemTimeMs": <epoch ms>,
 *    "simulated": true (only when simulated),
 *    "images": ["wide"|"cabin"]}], "storageBytes": int,
 *    "storageIncomplete": bool}
 *   (galaxy/sentry_events.py SentryEvents.snapshot; the web portal's
 *   validSentryEvents in galaxy/web/js/sentry.js validates the same
 *   shape). 503 {"error": "Local motion events are unavailable"}
 *   when the store cannot be read.
 * - GET /api/sentry/image/<eventId>/<wide|cabin> answers the stored
 *   JPEG (SOI FFD8 … EOI FFD9, ≤ 1 MB); 404 when the image is
 *   unavailable. The portal does NOT load images over Bluetooth
 *   (sentry.js hides them behind a Bluetooth notice) — this app
 *   does the same.
 * - POST /api/sentry/events/delete with exactly {"eventId": "<id>"}
 *   deletes one event, or {"all": true, "confirmed": true} deletes
 *   them all (galaxy/batch_routes.py delete_records). The response
 *   is the fresh snapshot merged with {"more": bool}; the app
 *   re-fetches the list after a delete instead of trusting that
 *   merge. A Galaxy session is required (401 otherwise); there is
 *   no parked gate on this endpoint.
 * - GET /api/sentry/notifications answers the notification state
 *   (sentry_mode/notifications.py NotificationOwner.snapshot):
 *   {"schemaVersion": 1, "channels": {"webPush"|"discord"|"webhook"|
 *    "ntfy"|"appPush": {"enabled", "configured", "pending",
 *    "lastState", "lastError"}}, "queueFull": bool,
 *    "subscriptionCount": int, "subscriptions": [{"id"}],
 *    "appSubscriptions": [{"id", "label", "appId", "addedAt",
 *    "lastResult", "lastAt"}], "appPushAvailable": bool, ...}.
 *
 * Honesty notes:
 * - The timestamps use the device clock and may be inaccurate
 *   (the portal says so on its page header); the app shows them
 *   as the device reported them, never adjusted.
 * - Validation mirrors the portal's validSentryEvents exactly: a
 *   payload that fails any check parses to null, and the dialog
 *   says the records are unavailable instead of showing a partial
 *   list that would read as the truth.
 */
object SentryEvents {

    /** One motion event from GET /api/sentry/events. */
    data class SentryEvent(
        val eventId: String,
        val kind: String,
        val systemTimeMs: Long,
        val simulated: Boolean,
        /** Cameras with a stored photo, subset of ["wide", "cabin"]. */
        val images: List<String>
    ) {
        val kindLabel: String
            get() = when (kind) {
                "alarm" -> "Alarm"
                "selfie" -> "Selfie"
                else -> "Warning"
            }

        /** "Alarm · Nov 27, 9:12 PM" (+ " · Simulated" when simulated). */
        fun rowLabel(): String {
            val base = "${kindLabel} · " +
                RecordingEntry.formatDate(systemTimeMs / 1000.0)
            return if (simulated) "$base · Simulated" else base
        }
    }

    /** The parsed events list plus the snapshot's honesty flags. */
    data class Snapshot(
        val events: List<SentryEvent>,
        val scanIncomplete: Boolean,
        val storageBytes: Long?,
        val storageIncomplete: Boolean
    ) {
        /**
         * "3 events · 12.4 MB stored" — the size part is omitted when
         * the comma reported none, and "At least" prefixes it when
         * the storage tally itself is incomplete, so the figure never
         * silently passes as exact.
         */
        fun headerLine(): String {
            val count = if (events.size == 1) "1 event" else "${events.size} events"
            val size = storageBytes?.let {
                (if (storageIncomplete) "At least " else "") +
                    "${RecordingEntry.formatBytes(it)} stored"
            }
            return if (size != null) "$count · $size" else count
        }
    }

    /** One notification channel from GET /api/sentry/notifications. */
    data class ChannelState(
        val name: String,
        val enabled: Boolean,
        val configured: Boolean,
        val pending: Int,
        val lastState: String,
        val lastError: String
    )

    /** One phone subscribed to Sentry app-push notifications. */
    data class AppSubscription(
        val id: String,
        val label: String,
        val appId: String
    )

    /** The parsed notification state (channels + subscriptions). */
    data class NotificationState(
        val appPushAvailable: Boolean,
        val subscriptionCount: Int,
        val appSubscriptions: List<AppSubscription>,
        val channels: List<ChannelState>
    ) {
        /**
         * One honest line, e.g. "App push available · 1 phone
         * subscribed · On: appPush". Channels the comma never
         * configured are not listed as on or off — only channels
         * actually enabled are named.
         */
        fun summaryLine(): String {
            val parts = mutableListOf<String>()
            parts.add(
                if (appPushAvailable) "App push available"
                else "App push not set up"
            )
            if (appSubscriptions.isNotEmpty()) {
                val n = appSubscriptions.size
                parts.add(if (n == 1) "1 phone subscribed" else "$n phones subscribed")
            }
            if (subscriptionCount > 0) {
                parts.add(
                    if (subscriptionCount == 1) "1 browser subscribed"
                    else "$subscriptionCount browsers subscribed"
                )
            }
            val enabled = channels.filter { it.enabled }.map { it.name }
            if (enabled.isNotEmpty()) {
                parts.add("On: " + enabled.joinToString(", "))
            }
            return parts.joinToString(" · ")
        }
    }

    private val EVENT_ID_REGEX = Regex("^[0-9a-f]{32}$")
    private val KINDS = setOf("warning", "alarm", "selfie")
    private val CAMERAS = setOf("wide", "cabin")
    /** Largest systemTimeMs the portal accepts (8_640_000_000_000_000). */
    private const val MAX_SYSTEM_TIME_MS = 8_640_000_000_000_000L

    /**
     * Parses a GET /api/sentry/events payload, newest first. Null
     * when the payload fails any of the portal's validity checks —
     * a partial list is never presented as the full record.
     */
    fun parse(json: JSONObject?): Snapshot? {
        if (json == null) return null
        if (json.optInt("schemaVersion", -1) != 1) return null
        if (json.optString("source", "") != "local") return null
        if (json.opt("scanIncomplete") !is Boolean) return null
        if (json.optInt("capacity", -1) != 512) return null
        val eventsJson = json.optJSONArray("events") ?: return null
        if (eventsJson.length() > 512) return null
        val storageBytes = if (json.has("storageBytes")) {
            val v = json.opt("storageBytes")
            if (v !is Number || v.toLong() < 0) return null
            v.toLong()
        } else {
            null
        }
        val storageIncomplete = if (json.has("storageIncomplete")) {
            val v = json.opt("storageIncomplete")
            if (v !is Boolean) return null
            v
        } else {
            false
        }
        val seenIds = mutableSetOf<String>()
        val events = ArrayList<SentryEvent>(eventsJson.length())
        for (i in 0 until eventsJson.length()) {
            val event = eventsJson.optJSONObject(i) ?: return null
            // Exact key set, as the portal checks it.
            val keys = event.keys().asSequence().toSet()
            if (!keys.all { it in setOf("eventId", "kind", "systemTimeMs", "images", "simulated") }) {
                return null
            }
            val eventId = event.optString("eventId", "")
            if (!EVENT_ID_REGEX.matches(eventId) || !seenIds.add(eventId)) return null
            val kind = event.optString("kind", "")
            if (kind !in KINDS) return null
            val timeRaw = event.opt("systemTimeMs")
            if (timeRaw !is Number) return null
            val systemTimeMs = timeRaw.toLong()
            if (systemTimeMs <= 0 || systemTimeMs > MAX_SYSTEM_TIME_MS) return null
            val simulated = when (val s = event.opt("simulated")) {
                null -> false
                true -> true
                else -> return null
            }
            val images = ArrayList<String>()
            if (event.has("images")) {
                val imagesJson = event.optJSONArray("images") ?: return null
                if (imagesJson.length() > 2) return null
                for (j in 0 until imagesJson.length()) {
                    val camera = imagesJson.optString(j, "")
                    if (camera !in CAMERAS || camera in images) return null
                    images.add(camera)
                }
            }
            events.add(SentryEvent(eventId, kind, systemTimeMs, simulated, images))
        }
        events.sortByDescending { it.systemTimeMs }
        return Snapshot(
            events = events,
            scanIncomplete = json.optBoolean("scanIncomplete", false),
            storageBytes = storageBytes,
            storageIncomplete = storageIncomplete
        )
    }

    /**
     * Parses a GET /api/sentry/notifications payload. Lenient where
     * the server is: missing channels or subscriptions read as
     * empty, because the notification state is a summary, not the
     * record itself — but a payload with no usable shape at all is
     * null, so the dialog says the state is unknown instead of
     * inventing "off".
     */
    fun parseNotifications(json: JSONObject?): NotificationState? {
        if (json == null) return null
        if (!json.has("channels") && !json.has("appPushAvailable") &&
            !json.has("subscriptionCount") && !json.has("appSubscriptions")
        ) return null
        val channels = ArrayList<ChannelState>()
        val channelsJson = json.optJSONObject("channels")
        if (channelsJson != null) {
            val names = channelsJson.keys()
            while (names.hasNext()) {
                val name = names.next()
                val channel = channelsJson.optJSONObject(name) ?: continue
                channels.add(
                    ChannelState(
                        name = name,
                        enabled = channel.optBoolean("enabled", false),
                        configured = channel.optBoolean("configured", false),
                        pending = channel.optInt("pending", 0),
                        lastState = channel.optString("lastState", ""),
                        lastError = channel.optString("lastError", "")
                    )
                )
            }
        }
        val appSubscriptions = ArrayList<AppSubscription>()
        val subsJson = json.optJSONArray("appSubscriptions")
        if (subsJson != null) {
            for (i in 0 until subsJson.length()) {
                val sub = subsJson.optJSONObject(i) ?: continue
                val id = sub.optString("id", "")
                if (id.isEmpty()) continue
                appSubscriptions.add(
                    AppSubscription(
                        id = id,
                        label = sub.optString("label", ""),
                        appId = sub.optString("appId", "")
                    )
                )
            }
        }
        return NotificationState(
            appPushAvailable = json.optBoolean("appPushAvailable", false),
            subscriptionCount = json.optInt("subscriptionCount", 0),
            appSubscriptions = appSubscriptions,
            channels = channels.sortedBy { it.name }
        )
    }

    /** The exact POST /api/sentry/events/delete body for one event. */
    fun deletePayload(eventId: String): JSONObject = JSONObject().apply {
        put("eventId", eventId)
    }

    /** The exact POST /api/sentry/events/delete body for all events. */
    fun deleteAllPayload(): JSONObject = JSONObject().apply {
        put("all", true)
        put("confirmed", true)
    }

    /** GET path for one stored event photo. */
    fun imagePath(eventId: String, camera: String): String =
        "/api/sentry/image/$eventId/$camera"

    /** Display label for a sentry camera id (same names as CommaCameras). */
    fun cameraLabel(camera: String): String = when (camera) {
        "wide" -> "Road wide"
        "cabin" -> "Cabin"
        else -> camera
    }
}
