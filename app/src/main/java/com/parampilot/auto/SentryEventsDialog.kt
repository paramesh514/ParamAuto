package com.parampilot.auto

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Sentry events viewer (Galaxy-parity day 4, gap #19).
 *
 * Lists the comma's stored Sentry motion events from
 * GET /api/sentry/events, newest first, with the totals line from
 * [SentryEvents.Snapshot.headerLine] and the notification state from
 * GET /api/sentry/notifications. Each event shows its kind, the
 * device-clock time, a Simulated tag when applicable, its event ID,
 * and its stored photos (Road wide / Cabin) when the connection can
 * carry them.
 *
 * Photos are NOT loaded over the Bluetooth bridge — the Galaxy
 * portal hides them there too (sentry.js), and a ~1 MB JPEG is far
 * too large for BLE. Over Bluetooth the dialog says so up front
 * instead of showing broken thumbnails. Photos auto-load for the
 * newest [MAX_AUTO_PHOTOS] events; older events load on tap, so a
 * full 512-event store can never trigger hundreds of megabyte
 * downloads at once.
 *
 * Deleting an event (or all events) posts the source-pinned body to
 * POST /api/sentry/events/delete behind an explicit confirm that
 * names what goes. Any failure surfaces the server's error and the
 * list is left as-is; a success reloads the list from the comma.
 */
object SentryEventsDialog {

    /** Photos auto-loaded for the newest N events; older ones load on tap. */
    private const val MAX_AUTO_PHOTOS = 10

    fun show(activity: Activity, client: CommaClient?) {
        val den = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * den).roundToInt()

        val endpoint = client?.commaIp ?: NavStateManager.commaIp
        val sg = SgGalaxyClient(SgGalaxyClient.baseUrlFor(endpoint ?: "127.0.0.1"))
        val bluetooth = NavStateManager.activeTransport ==
            NavStateManager.TransportType.BLUETOOTH

        val (dialog, body, pal) = M3Sheet.create(
            activity,
            "Sentry events",
            "Motion events from the parked car — timestamps use the device clock and may be inaccurate"
        )
        val status = TextView(activity).apply {
            text = "Loading Sentry events…"
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(pal.textSecondary)
        }
        body.addView(status)
        val warn = TextView(activity).apply {
            text = "This scan was incomplete. More events may exist."
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(pal.accentAmber)
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        body.addView(warn)
        val notifications = TextView(activity).apply {
            text = "Loading notification state…"
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(pal.textSecondary)
            setPadding(0, dp(4), 0, 0)
        }
        body.addView(notifications)
        val bluetoothNote = TextView(activity).apply {
            text = "Event photos need a local or Cloud connection — " +
                "Bluetooth can't carry them."
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(pal.textSecondary)
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        body.addView(bluetoothNote)
        val listBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(listBox)

        // Per-render state. [generation] invalidates the photo thread
        // of a superseded render (e.g. after a delete reload) so it
        // can never write into the new list. [deleteAllBtn] is
        // lateinit because render (declared first, so the delete
        // callbacks below can reference it) sets its visibility.
        var generation = 0
        var shownEvents: List<SentryEvents.SentryEvent> = emptyList()
        lateinit var deleteAllBtn: TextView

        fun render(snapshot: SentryEvents.Snapshot) {
            generation++
            shownEvents = snapshot.events
            listBox.removeAllViews()
            status.text = if (snapshot.events.isEmpty()) {
                "No Sentry events found in this scan"
            } else {
                snapshot.headerLine()
            }
            warn.visibility = if (snapshot.scanIncomplete) View.VISIBLE else View.GONE
            val anyPhotos = snapshot.events.any { it.images.isNotEmpty() }
            bluetoothNote.visibility =
                if (bluetooth && anyPhotos) View.VISIBLE else View.GONE
            deleteAllBtn.visibility =
                if (snapshot.events.isEmpty()) View.GONE else View.VISIBLE
            for ((index, event) in snapshot.events.withIndex()) {
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                }
                val headerRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val label = TextView(activity).apply {
                    text = event.rowLabel()
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(pal.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                headerRow.addView(label)
                val deleteBtn = TextView(activity).apply {
                    text = "Delete"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(HudOverlay.COLOR_ACCENT_RED)
                    setPadding(dp(12), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        confirmDelete(activity, sg, event) {
                            load(activity, sg, dialog, status, warn, notifications, bluetoothNote, listBox, ::render)
                        }
                    }
                }
                headerRow.addView(deleteBtn)
                card.addView(headerRow)
                val idView = TextView(activity).apply {
                    text = "Event ID: ${event.eventId}"
                    textSize = 12f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    setTextColor(pal.textSecondary)
                    setPadding(0, dp(2), 0, 0)
                }
                card.addView(idView)
                if (event.images.isNotEmpty() && !bluetooth) {
                    val photoBox = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(6), 0, 0)
                    }
                    card.addView(photoBox)
                    val gen = generation
                    if (index < MAX_AUTO_PHOTOS) {
                        loadPhotos(activity, sg, dialog, { gen == generation }, event, photoBox, null, pal)
                    } else {
                        val showBtn = TextView(activity).apply {
                            text = "Show photos (" +
                                event.images.joinToString(", ") { SentryEvents.cameraLabel(it) } +
                                ")"
                            textSize = 13f
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setTextColor(pal.accentBlue)
                            setPadding(dp(4), dp(8), dp(4), dp(8))
                            setOnClickListener {
                                setOnClickListener(null)
                                text = "Loading photos…"
                                loadPhotos(activity, sg, dialog, { gen == generation }, event, photoBox, this, pal)
                            }
                        }
                        photoBox.addView(showBtn)
                    }
                }
                listBox.addView(card)
            }
        }

        deleteAllBtn = M3Sheet.dangerButton(activity, pal, "Delete all") {
            confirmDeleteAll(activity, sg, shownEvents) {
                load(activity, sg, dialog, status, warn, notifications, bluetoothNote, listBox, ::render)
            }
        }.apply { visibility = View.GONE }
        body.addView(
            M3Sheet.buttonRow(
                activity,
                deleteAllBtn,
                M3Sheet.primaryButton(activity, pal, "Done") { dialog.dismiss() }
            )
        )
        dialog.show()

        load(activity, sg, dialog, status, warn, notifications, bluetoothNote, listBox, ::render)
    }

    private fun load(
        activity: Activity,
        sg: SgGalaxyClient,
        dialog: android.app.Dialog,
        status: TextView,
        warn: TextView,
        notifications: TextView,
        bluetoothNote: TextView,
        listBox: LinearLayout,
        render: (SentryEvents.Snapshot) -> Unit
    ) {
        Thread {
            val (code, json) = sg.fetchSentryEvents()
            val (notifCode, notifJson) = sg.fetchSentryNotifications()
            val snapshot = SentryEvents.parse(json)
            val notifState = SentryEvents.parseNotifications(notifJson)
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                when {
                    code !in 200..299 || json == null -> {
                        listBox.removeAllViews()
                        warn.visibility = View.GONE
                        bluetoothNote.visibility = View.GONE
                        notifications.text = ""
                        status.text = "Couldn't load Sentry events — " +
                            describeError(code, json?.optString("error", ""))
                    }
                    snapshot == null -> {
                        listBox.removeAllViews()
                        warn.visibility = View.GONE
                        bluetoothNote.visibility = View.GONE
                        notifications.text = ""
                        status.text = "Local motion records are unavailable"
                    }
                    else -> {
                        val notifText = notifState?.summaryLine()
                            ?: "state unavailable" +
                                if (notifCode !in 200..299) {
                                    " (" + describeError(
                                        notifCode,
                                        notifJson?.optString("error", ""),
                                        "Sentry notifications are unavailable"
                                    ) + ")"
                                } else {
                                    ""
                                }
                        notifications.text = "Notifications: $notifText"
                        render(snapshot)
                    }
                }
            }
        }.start()
    }

    /**
     * Fetches one event's stored photos into [photoBox], replacing
     * the [tapTarget] ("Show photos" / "Loading photos…") with the
     * decoded thumbnails as they arrive. A photo that fails to load
     * leaves a visible per-camera error line instead of a silent
     * gap. [isCurrent] guards against writing into a superseded
     * render (e.g. after a delete reload rebuilt the list).
     */
    private fun loadPhotos(
        activity: Activity,
        sg: SgGalaxyClient,
        dialog: android.app.Dialog,
        isCurrent: () -> Boolean,
        event: SentryEvents.SentryEvent,
        photoBox: LinearLayout,
        tapTarget: TextView?,
        pal: M3Sheet.Palette
    ) {
        val den = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * den).roundToInt()
        Thread {
            var tapTargetRemoved = false
            for (camera in event.images) {
                val (code, jpeg, serverErr) = sg.fetchSentryImage(event.eventId, camera)
                val bmp = if (code in 200..299 && CommaCameras.isJpeg(jpeg)) {
                    jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } else {
                    null
                }
                activity.runOnUiThread {
                    if (!dialog.isShowing || !isCurrent()) return@runOnUiThread
                    if (!tapTargetRemoved) {
                        tapTarget?.let { photoBox.removeView(it) }
                        tapTargetRemoved = true
                    }
                    if (bmp != null) {
                        val imageView = ImageView(activity).apply {
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            minimumHeight = dp(160)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { setMargins(0, dp(4), 0, dp(4)) }
                            contentDescription =
                                "${SentryEvents.cameraLabel(camera)} camera at motion event"
                        }
                        imageView.setImageBitmap(bmp)
                        val caption = TextView(activity).apply {
                            text = SentryEvents.cameraLabel(camera)
                            textSize = 12f
                            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                            setTextColor(pal.textSecondary)
                        }
                        photoBox.addView(caption)
                        photoBox.addView(imageView)
                    } else {
                        val errView = TextView(activity).apply {
                            text = "${SentryEvents.cameraLabel(camera)} photo unavailable — " +
                                describeError(code, serverErr)
                            textSize = 12f
                            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                            setTextColor(pal.textSecondary)
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                        }
                        photoBox.addView(errView)
                    }
                }
            }
        }.start()
    }

    private fun describeError(
        code: Int,
        serverError: String?,
        unavailableText: String = "Local motion events are unavailable"
    ): String = when {
        !serverError.isNullOrBlank() -> serverError
        code == 401 -> "Sign in to Galaxy first"
        code == 503 -> unavailableText
        code == SgGalaxyClient.CODE_AMBIGUOUS -> "Outcome unknown"
        code == -1 -> "Couldn't reach the comma"
        else -> "HTTP $code"
    }

    private fun confirmDelete(
        activity: Activity,
        sg: SgGalaxyClient,
        event: SentryEvents.SentryEvent,
        onDeleted: () -> Unit
    ) {
        val (dialog, body, pal) = M3Sheet.create(activity, "Delete event?")
        val msg = TextView(activity).apply {
            text = "${event.rowLabel()}\n\nThis removes the event and its " +
                "photos from the comma. This cannot be undone."
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(pal.textPrimary)
        }
        body.addView(msg)
        body.addView(
            M3Sheet.buttonRow(
                activity,
                M3Sheet.tonalButton(activity, pal, "Cancel") { dialog.dismiss() },
                M3Sheet.dangerButton(activity, pal, "Delete") {
                    dialog.dismiss()
                    delete(activity, sg, SentryEvents.deletePayload(event.eventId)) {
                        Toast.makeText(
                            activity,
                            "Deleted ${event.rowLabel()}",
                            Toast.LENGTH_SHORT
                        ).show()
                        onDeleted()
                    }
                }
            )
        )
        dialog.show()
    }

    private fun confirmDeleteAll(
        activity: Activity,
        sg: SgGalaxyClient,
        events: List<SentryEvents.SentryEvent>,
        onDeleted: () -> Unit
    ) {
        val (dialog, body, pal) = M3Sheet.create(activity, "Delete all Sentry events?")
        val msg = TextView(activity).apply {
            text = "This removes all ${events.size} events and their " +
                "photos from the comma. This cannot be undone."
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(pal.textPrimary)
        }
        body.addView(msg)
        body.addView(
            M3Sheet.buttonRow(
                activity,
                M3Sheet.tonalButton(activity, pal, "Cancel") { dialog.dismiss() },
                M3Sheet.dangerButton(activity, pal, "Delete all") {
                    dialog.dismiss()
                    delete(activity, sg, SentryEvents.deleteAllPayload()) {
                        Toast.makeText(
                            activity,
                            "Deleted all Sentry events",
                            Toast.LENGTH_SHORT
                        ).show()
                        onDeleted()
                    }
                }
            )
        )
        dialog.show()
    }

    private fun delete(
        activity: Activity,
        sg: SgGalaxyClient,
        body: org.json.JSONObject,
        onDeleted: () -> Unit
    ) {
        Thread {
            val (code, resp) = sg.postSentryEventsDelete(body)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (code in 200..299) {
                    onDeleted()
                } else {
                    val err = resp?.optString("error", "")?.ifEmpty { null }
                    val msg = when {
                        !err.isNullOrBlank() -> err
                        code == SgGalaxyClient.CODE_AMBIGUOUS ->
                            "Outcome unknown — the delete may have run; " +
                                "reopen the list to check"
                        code == -1 -> "Couldn't reach the comma"
                        else -> "HTTP $code"
                    }
                    Toast.makeText(
                        activity,
                        "Delete failed — $msg",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}
