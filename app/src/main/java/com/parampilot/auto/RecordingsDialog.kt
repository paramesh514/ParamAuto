package com.parampilot.auto

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import kotlin.math.roundToInt

/**
 * Recordings & storage manager (Feature 17).
 *
 * Lists the routes stored on the comma from GET /api/recordings/local,
 * newest first, with the totals line from [RecordingEntry]. The endpoint
 * is LOCAL-ONLY — through the Galaxy tunnel it answers 403
 * {"code": "local_only"}, and the dialog says exactly that instead of
 * showing an empty list that would read as "no recordings".
 *
 * Deleting a route posts the source-pinned body
 * {"action": "delete", "routeId", "confirmed": true} to
 * /api/recordings/action, behind an explicit confirm that names the
 * route. The server itself refuses unless the vehicle is off (409);
 * any failure surfaces the server's error and the list is left as-is.
 *
 * Video sizes (Fix C): the list payload carries only log bytes, so
 * right after the list renders, the dialog background-fetches
 * /api/recordings/detail for the listed routes (capped at
 * [MAX_DETAIL_ROUTES]) and folds each route's real video size into its
 * row as it arrives. The totals line keeps showing logs only until
 * every fetch has settled; then it adds the summed video bytes and
 * calls out any route whose video size stayed unavailable.
 *
 * Play / Download (Param, 2026-11-20): each row can stream one
 * camera's whole-route video in an in-app player, or download it
 * into the phone's Downloads folder — see [RecordingMedia] for the
 * contract. Both are local-connection features: over Galaxy Cloud
 * the comma refuses recordings, and the row says so up front.
 */
object RecordingsDialog {

    /** Detail fetches per list render — bounds the background work. */
    private const val MAX_DETAIL_ROUTES = 20

    fun show(activity: Activity, client: CommaClient?) {
        val den = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * den).roundToInt()

        val endpoint = client?.commaIp ?: NavStateManager.commaIp
        val sg = SgGalaxyClient(SgGalaxyClient.baseUrlFor(endpoint ?: "127.0.0.1"))

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = HudOverlay.makeCardBg(activity, HudOverlay.COLOR_SURFACE_DARK, 0)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val status = TextView(activity).apply {
            text = "Loading recordings…"
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(HudOverlay.COLOR_TEXT_MUTED)
        }
        body.addView(status)
        val listBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(listBox)

        val scroll = ScrollView(activity).apply { addView(body) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Recordings on comma")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .create()
        dialog.show()

        // Per-render detail state (Fix C). [generation] invalidates the
        // detail thread of a superseded render (e.g. after a delete
        // reload) so it can never write into the new list.
        var generation = 0
        var shownEntries: List<RecordingEntry> = emptyList()
        val videoBytesByRoute = mutableMapOf<String, Long>()
        val camerasByRoute =
            mutableMapOf<String, List<RecordingMedia.Camera>>()
        var detailsPending = 0
        val rowLabels = mutableMapOf<String, TextView>()

        fun updateTotals() {
            status.text = if (shownEntries.isEmpty()) {
                "No recordings on the comma"
            } else {
                RecordingEntry.totalsLine(
                    shownEntries, videoBytesByRoute, detailsPending == 0
                ) + " on the comma"
            }
        }

        fun loadDetails(gen: Int, entries: List<RecordingEntry>) {
            Thread {
                for (entry in entries.take(MAX_DETAIL_ROUTES)) {
                    var routeJson: org.json.JSONObject? = null
                    val bytes = try {
                        routeJson = RecordingDetail.routeOf(
                            sg.fetchRecordingDetail(entry.routeId)
                        )
                        routeJson?.let { RecordingDetail.totalVideoBytes(it) }
                    } catch (_: Exception) {
                        null
                    }
                    val cameras = RecordingMedia.camerasFromDetail(routeJson)
                    activity.runOnUiThread {
                        if (!dialog.isShowing || gen != generation) {
                            return@runOnUiThread
                        }
                        if (bytes != null) {
                            videoBytesByRoute[entry.routeId] = bytes
                            rowLabels[entry.routeId]?.text = entry.rowLabel(bytes)
                        }
                        if (cameras != null) {
                            camerasByRoute[entry.routeId] = cameras
                        }
                        detailsPending--
                        if (detailsPending == 0) updateTotals()
                    }
                }
            }.start()
        }

        fun render(entries: List<RecordingEntry>) {
            generation++
            shownEntries = entries
            videoBytesByRoute.clear()
            camerasByRoute.clear()
            rowLabels.clear()
            detailsPending = entries.take(MAX_DETAIL_ROUTES).size
            listBox.removeAllViews()
            updateTotals()
            for (entry in entries) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                val label = TextView(activity).apply {
                    text = entry.rowLabel()
                    textSize = 13.5f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                rowLabels[entry.routeId] = label
                row.addView(label)
                val playBtn = TextView(activity).apply {
                    text = "Play"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), dp(4), dp(2), dp(4))
                    setOnClickListener {
                        if (!checkLocalForVideo(activity)) {
                            return@setOnClickListener
                        }
                        pickCamera(
                            activity,
                            camerasByRoute[entry.routeId]
                                ?: RecordingMedia.CAMERAS,
                            "Play — pick a camera"
                        ) { camera ->
                            playVideo(activity, sg, entry, camera)
                        }
                    }
                }
                row.addView(playBtn)
                val downloadBtn = TextView(activity).apply {
                    text = "Download"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(HudOverlay.COLOR_TEXT_MUTED)
                    setPadding(dp(10), dp(4), dp(2), dp(4))
                    setOnClickListener {
                        if (!checkLocalForVideo(activity)) {
                            return@setOnClickListener
                        }
                        pickCamera(
                            activity,
                            camerasByRoute[entry.routeId]
                                ?: RecordingMedia.CAMERAS,
                            "Download — pick a camera"
                        ) { camera ->
                            downloadVideo(activity, sg, entry, camera)
                        }
                    }
                }
                row.addView(downloadBtn)
                val deleteBtn = TextView(activity).apply {
                    text = "Delete"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(HudOverlay.COLOR_ACCENT_RED)
                    setPadding(dp(12), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        confirmDelete(activity, sg, entry) {
                            load(activity, sg, dialog, status, listBox, ::render)
                        }
                    }
                }
                row.addView(deleteBtn)
                listBox.addView(row)
            }
            if (detailsPending > 0) loadDetails(generation, entries)
        }

        load(activity, sg, dialog, status, listBox, ::render)
    }

    private fun load(
        activity: Activity,
        sg: SgGalaxyClient,
        dialog: AlertDialog,
        status: TextView,
        listBox: LinearLayout,
        render: (List<RecordingEntry>) -> Unit
    ) {
        Thread {
            val (code, json) = sg.fetchRecordingsLocal()
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                when {
                    json?.optString("code") == "local_only" -> {
                        listBox.removeAllViews()
                        status.text =
                            "Recordings are only visible on the local connection"
                    }
                    code !in 200..299 || json == null -> {
                        listBox.removeAllViews()
                        val err = json?.optString("error", "")?.ifEmpty { null }
                        status.text = "Couldn't load recordings — ${err ?: "HTTP $code"}"
                    }
                    else -> render(RecordingEntry.parseList(json))
                }
            }
        }.start()
    }

    /**
     * Recordings video is local-only on the comma (403 local_only
     * through the tunnel). Over Cloud, say so before offering a
     * camera the comma will only refuse.
     */
    private fun checkLocalForVideo(activity: Activity): Boolean {
        if (NavStateManager.activeTransport ==
            NavStateManager.TransportType.CLOUD
        ) {
            Toast.makeText(
                activity,
                "Video needs a local connection to the comma — " +
                    "Cloud can't carry recordings",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        return true
    }

    private fun pickCamera(
        activity: Activity,
        cameras: List<RecordingMedia.Camera>,
        title: String,
        onPick: (RecordingMedia.Camera) -> Unit
    ) {
        val labels = cameras.map { camera ->
            if (camera.hint.isEmpty()) {
                camera.label
            } else {
                "${camera.label} · ${camera.hint}"
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                onPick(cameras[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Streams one camera's whole-route video in a simple player.
     * The comma supports Range requests, so seeking works; the
     * first play can take a while because the comma prepares the
     * combined video before the stream starts — the spinner stays
     * up until the player is actually ready.
     */
    private fun playVideo(
        activity: Activity,
        sg: SgGalaxyClient,
        entry: RecordingEntry,
        camera: RecordingMedia.Camera
    ) {
        val den = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * den).roundToInt()
        Toast.makeText(activity, "Loading video…", Toast.LENGTH_SHORT).show()
        Thread {
            val cookie = sg.mediaCookieHeader()
            val url = sg.mediaUrl(
                RecordingMedia.routeVideoPath(entry.routeId, camera.id)
            )
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val frame = FrameLayout(activity)
                val video = VideoView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(260)
                    )
                }
                frame.addView(video)
                val spinner = ProgressBar(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                }
                frame.addView(spinner)
                val controller = MediaController(activity)
                controller.setAnchorView(video)
                video.setMediaController(controller)
                val playerDialog = AlertDialog.Builder(activity)
                    .setTitle("${entry.displayName} · ${camera.label}")
                    .setView(frame)
                    .setPositiveButton("Close", null)
                    .create()
                playerDialog.setOnDismissListener {
                    try {
                        video.stopPlayback()
                    } catch (_: Exception) {
                    }
                }
                video.setOnPreparedListener {
                    spinner.visibility = View.GONE
                    video.start()
                }
                video.setOnErrorListener { _, _, _ ->
                    Toast.makeText(
                        activity,
                        "Couldn't play this video — the comma may be " +
                            "unreachable or still preparing it",
                        Toast.LENGTH_LONG
                    ).show()
                    playerDialog.dismiss()
                    true
                }
                val headers = if (cookie != null) {
                    mapOf("Cookie" to cookie)
                } else {
                    emptyMap()
                }
                video.setVideoURI(Uri.parse(url), headers)
                playerDialog.show()
            }
        }.start()
    }

    /**
     * Downloads one camera's whole-route video into the phone's
     * Downloads folder (MediaStore, so no storage permission is
     * needed), with a cancellable progress dialog. A failed or
     * cancelled download removes its partial file.
     */
    private fun downloadVideo(
        activity: Activity,
        sg: SgGalaxyClient,
        entry: RecordingEntry,
        camera: RecordingMedia.Camera
    ) {
        val den = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * den).roundToInt()
        val fileName = RecordingMedia.downloadFileName(
            entry.displayName, camera.id
        )
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
        } catch (_: Exception) {
            null
        }
        if (uri == null) {
            Toast.makeText(
                activity, "Couldn't open Downloads for saving",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val bar = ProgressBar(
            activity, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            isIndeterminate = true
        }
        box.addView(bar)
        val progressText = TextView(activity).apply {
            text = "Starting…"
            textSize = 13f
            setTextColor(HudOverlay.COLOR_TEXT_MUTED)
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(progressText)
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("Downloading ${camera.label}")
            .setView(box)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .create()
        progressDialog.setOnCancelListener { cancelled.set(true) }
        progressDialog.show()
        val path = RecordingMedia.routeVideoPath(entry.routeId, camera.id)
        Thread {
            val result = try {
                val stream = resolver.openOutputStream(uri)
                if (stream == null) {
                    -1 to "Couldn't open the file"
                } else {
                    stream.use { out ->
                        sg.downloadMedia(path, out, { cancelled.get() }) {
                                downloaded, total ->
                            activity.runOnUiThread {
                                if (!progressDialog.isShowing) {
                                    return@runOnUiThread
                                }
                                if (total > 0) {
                                    bar.isIndeterminate = false
                                    bar.progress =
                                        (downloaded * 100 / total).toInt()
                                    progressText.text =
                                        "${formatBytes(downloaded)} of " +
                                            formatBytes(total)
                                } else {
                                    progressText.text =
                                        "${formatBytes(downloaded)} downloaded"
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                -1 to (e.message ?: "Download failed")
            }
            activity.runOnUiThread {
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (activity.isFinishing) return@runOnUiThread
                if (result.first == 200) {
                    try {
                        resolver.update(
                            uri,
                            ContentValues().apply {
                                put(MediaStore.Downloads.IS_PENDING, 0)
                            },
                            null, null
                        )
                    } catch (_: Exception) {
                    }
                    Toast.makeText(
                        activity, "Saved to Downloads: $fileName",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    try {
                        resolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                    if (result.first != -2) {
                        Toast.makeText(
                            activity,
                            "Download failed — " +
                                (result.second ?: "HTTP ${result.first}"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 ->
            String.format(java.util.Locale.US, "%.2f GB", bytes / 1e9)
        else ->
            String.format(java.util.Locale.US, "%.1f MB", bytes / 1e6)
    }

    private fun confirmDelete(
        activity: Activity,
        sg: SgGalaxyClient,
        entry: RecordingEntry,
        onDeleted: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle("Delete recording?")
            .setMessage(
                "${entry.displayName}\n\nThis removes the whole route and its " +
                    "videos from the comma. This cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    val (code, resp) = sg.postRecordingsAction(
                        RecordingEntry.deletePayload(entry.routeId)
                    )
                    val saved = code in 200..299 && resp?.optBoolean("saved", false) == true
                    activity.runOnUiThread {
                        if (activity.isFinishing) return@runOnUiThread
                        if (saved) {
                            Toast.makeText(
                                activity,
                                "Deleted ${entry.displayName}",
                                Toast.LENGTH_SHORT
                            ).show()
                            onDeleted()
                        } else {
                            val err = resp?.optString("error", "")?.ifEmpty { null }
                            Toast.makeText(
                                activity,
                                "Delete failed — ${err ?: "HTTP $code"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
            .show()
    }
}
