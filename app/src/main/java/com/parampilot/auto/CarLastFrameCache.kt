package com.parampilot.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * The car map's last good frame, persisted across sessions
 * (Addendum 153, diagnosis B8) — the car counterpart of the
 * phone's [StartupMapCache].
 *
 * The phone paints its last rendered frame instantly at cold
 * open; the car started every session from the empty grid, and
 * the only head start was [MapFrameDiskCache], which serves a
 * previous Mapbox frame only for the exact same rounded view
 * key. This cache keeps ONE frame of any source (Mapbox static
 * or tile stitch) with the view it belongs to (centre, zoom,
 * night mode), so the next session opens on a real map of
 * roughly here while the fresh fetch runs. A stale frame is
 * only ever a backdrop for the first seconds; the live frame
 * replaces it, and the renderer never treats it as fetched
 * state (its fetch gates stay open).
 *
 * Deliberately one known frame file + one tiny meta file — no
 * directory scan — so reading it on the session path costs a
 * single decode. JPEG, not PNG, for the same reason as the
 * phone cache: ~10x smaller, decodes several times faster, and
 * a backdrop frame doesn't need lossless pixels.
 */
object CarLastFrameCache {
    private const val TAG = "CarLastFrameCache"
    private const val FRAME_FILE = "car_last_frame.jpg"
    private const val META_FILE = "car_last_frame.meta"

    class SavedFrame(
        val bitmap: Bitmap,
        val lat: Double,
        val lng: Double,
        val zoom: Double,
        val source: CarMapRenderer.BitmapSource,
        val isNight: Boolean
    )

    fun load(context: Context): SavedFrame? {
        return try {
            val dir = context.applicationContext.cacheDir
            val frameFile = File(dir, FRAME_FILE)
            val metaFile = File(dir, META_FILE)
            if (!frameFile.exists() || frameFile.length() == 0L || !metaFile.exists()) {
                return null
            }
            val fields = metaFile.readText().trim().split("|")
            if (fields.size != 5) return null
            val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath) ?: return null
            SavedFrame(
                bitmap = bitmap,
                lat = fields[0].toDouble(),
                lng = fields[1].toDouble(),
                zoom = fields[2].toDouble(),
                source = try {
                    CarMapRenderer.BitmapSource.valueOf(fields[3])
                } catch (e: Exception) {
                    CarMapRenderer.BitmapSource.OSM
                },
                isNight = fields[4] == "1"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Last car frame load failed: ${e.message}")
            null
        }
    }

    /**
     * Fire-and-forget save. The bitmap is COPIED on the calling
     * thread first: stitch frames live in a reused buffer pool
     * and Mapbox frames in a cache, so the original may be
     * redrawn or recycled while the JPEG encodes on the
     * background thread.
     */
    fun saveAsync(
        context: Context,
        bitmap: Bitmap,
        lat: Double,
        lng: Double,
        zoom: Double,
        source: CarMapRenderer.BitmapSource,
        isNight: Boolean
    ) {
        val appCtx = context.applicationContext
        val copy = try {
            bitmap.copy(bitmap.config ?: Bitmap.Config.RGB_565, false)
        } catch (e: Exception) {
            Log.w(TAG, "Last car frame copy failed: ${e.message}")
            return
        }
        Thread {
            try {
                val dir = appCtx.cacheDir
                val tmp = File(dir, "$FRAME_FILE.tmp")
                FileOutputStream(tmp).use { out ->
                    copy.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                val dest = File(dir, FRAME_FILE)
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                File(dir, META_FILE).writeText(
                    "$lat|$lng|$zoom|${source.name}|${if (isNight) "1" else "0"}"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Last car frame save failed: ${e.message}")
            } finally {
                try { copy.recycle() } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }
}
