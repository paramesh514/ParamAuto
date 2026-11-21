package com.parampilot.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * The last rendered phone-map frame, kept as a single JPEG in the
 * cache dir so the next cold open can paint a real map instantly
 * while MapLibre initializes (Param, 2026-11-07: "Why can't you
 * use cache"). Deliberately ONE known file — no directory scan —
 * so reading it on the startup path costs a single decode.
 * JPEG, not PNG (2026-11-08 startup debug): a map frame as PNG is
 * several MB and decodes slowly on the main thread during
 * onCreate; JPEG q85 is ~10x smaller and decodes several times
 * faster, and a backdrop frame doesn't need lossless pixels.
 *
 * Saved from MainActivity.onPause (the map's last frame of the
 * session); shown by buildContentView as the map container's
 * placeholder and removed when the real map finishes its first
 * full render. A stale frame is only ever a backdrop for a moment;
 * the live map replaces it.
 */
object StartupMapCache {
    private const val TAG = "StartupMapCache"
    private const val FILE_NAME = "startup-map.jpg"
    private const val MAX_WIDTH = 1440

    fun loadBitmap(context: Context): Bitmap? {
        return try {
            val f = File(context.applicationContext.cacheDir, FILE_NAME)
            if (!f.exists() || f.length() == 0L) null else BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Startup frame load failed: ${e.message}")
            null
        }
    }

    /** Fire-and-forget: scales and writes on a background thread. */
    fun saveAsync(context: Context, bitmap: Bitmap) {
        val appCtx = context.applicationContext
        Thread {
            try {
                val scaled = if (bitmap.width > MAX_WIDTH) {
                    val h = (bitmap.height * (MAX_WIDTH.toFloat() / bitmap.width)).toInt()
                    Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, h, true)
                } else bitmap
                val tmp = File(appCtx.cacheDir, "$FILE_NAME.tmp")
                FileOutputStream(tmp).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out) }
                val dest = File(appCtx.cacheDir, FILE_NAME)
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Startup frame save failed: ${e.message}")
            }
        }.start()
    }
}
