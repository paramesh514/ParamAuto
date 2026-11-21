package com.parampilot.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/**
 * Persistent disk cache for Android Auto's Mapbox static frames —
 * the shared-cache fix (Param, 2026-11-04: "Use same cache used by
 * phone and auto"). The phone's map tiles persist on disk
 * (CommaTileDiskCache for comma tiles, MapLibre's own store for
 * vector tiles), but Auto's Mapbox frames lived in a 6-entry
 * memory LRU that died with every session, so each session
 * re-raced the fetch and one early failure stuck. Frames now live
 * in the same cache home (the app's cacheDir, beside comma_tiles),
 * keyed by the renderer's static-image key, written through on
 * fetch and read before any network call.
 */
object MapFrameDiskCache {

    private const val CACHE_DIR_NAME = "map_frames"
    private const val MAX_CACHE_BYTES = 64L * 1024L * 1024L

    @Volatile
    private var cacheDir: File? = null

    fun init(context: Context) {
        if (cacheDir != null) return
        synchronized(this) {
            if (cacheDir != null) return
            val dir = File(context.applicationContext.cacheDir, CACHE_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            cacheDir = dir
        }
    }

    private fun fileFor(key: String): File? {
        val dir = cacheDir ?: return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.png")
    }

    fun getBitmap(key: String): Bitmap? {
        val file = fileFor(key) ?: return null
        if (!file.exists()) return null
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.also {
                // Touch: eviction is least-recently-used.
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {
            null
        }
    }

    fun putBytes(key: String, bytes: ByteArray) {
        val file = fileFor(key) ?: return
        if (!isSupportedImage(bytes)) return
        try {
            val tmp = File(file.parentFile, "${file.name}.${System.currentTimeMillis()}.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
            evictIfNeeded()
        } catch (_: Exception) {
            // Cache write failures are silent by design: the frame is
            // already in the memory cache and on screen.
        }
    }

    /** PNG (the static API's default) or JPEG signatures. */
    private fun isSupportedImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        return png || jpeg
    }

    private fun evictIfNeeded() {
        val dir = cacheDir ?: return
        val files = dir.listFiles()?.filter { it.extension == "png" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
