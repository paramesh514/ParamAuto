package com.parampilot.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent disk cache for comma map tiles on the phone.
 *
 * Caches raster PNG tiles fetched from the comma device
 * (/api/navigation/map/tiles/{z}/{x}/{y}.png?theme={theme}) locally on the phone's
 * internal storage so:
 * 1. Previously fetched tiles load instantaneously (< 1ms) directly from phone storage.
 * 2. Comma Wi-Fi / network is not flooded by re-requesting identical tiles every frame.
 * 3. Maps render offline or on cellular/BLE when comma is disconnected or Wi-Fi is weak.
 * 4. Tiles are shared seamlessly across both Phone (MapLibre OkHttp) and Android Auto (CarMapRenderer).
 */
object CommaTileDiskCache {

    private const val TAG = "CommaTileDiskCache"
    private const val CACHE_DIR_NAME = "comma_tiles"

    // Max cache size: 500 MB (~25,000 to 50,000 tiles)
    private const val MAX_CACHE_BYTES = 500L * 1024L * 1024L
    private const val PRUNE_TARGET_BYTES = 400L * 1024L * 1024L

    @Volatile
    private var baseDir: File? = null

    private val isPruning = AtomicBoolean(false)
    private val pruningExecutor = Executors.newSingleThreadExecutor()

    // Fast in-memory index of tiles known to exist on disk to minimize stat() calls
    private val knownTiles = ConcurrentHashMap<String, Long>()

    // Fast in-memory LruCache (32 MB) to serve repeat tile requests instantaneously without disk I/O or network queries
    private val memoryTileCache = object : android.util.LruCache<String, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    val hitCount = AtomicLong(0L)
    val missCount = AtomicLong(0L)
    val writeCount = AtomicLong(0L)

    fun init(context: Context) {
        if (baseDir != null) return
        synchronized(this) {
            if (baseDir == null) {
                val dir = File(context.applicationContext.cacheDir, CACHE_DIR_NAME)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                baseDir = dir
                scanInitialFiles(dir)
                Log.i(TAG, "Initialized comma tile cache at ${dir.absolutePath} with ${knownTiles.size} tiles")
            }
        }
    }

    fun initForTesting(testDir: File) {
        synchronized(this) {
            val dir = File(testDir, CACHE_DIR_NAME)
            dir.mkdirs()
            baseDir = dir
            scanInitialFiles(dir)
        }
    }

    private fun getOrInitDir(): File? {
        if (baseDir != null) return baseDir
        NavStateManager.appContext?.let { init(it) }
        return baseDir
    }

    // The on-disk theme is coerced in getFileForTile (only "light"
    // stays light, everything else is dark); the index key must use
    // the SAME normalized theme (bug fix, 2026-11-09): it used to
    // lowercase the raw theme, so a tile stored under key
    // "voyager_..." lived in file dark_... — the disk read still
    // found the file, but the in-memory index, the cached-tile
    // count and prune bookkeeping disagreed with the disk.
    private fun tileKey(z: Int, x: Int, y: Int, theme: String): String =
        "${if (theme.equals("light", ignoreCase = true)) "light" else "dark"}_${z}_${x}_${y}"

    private fun scanInitialFiles(root: File) {
        pruningExecutor.execute {
            try {
                var count = 0
                root.listFiles()?.forEach { themeDir ->
                    if (themeDir.isDirectory) {
                        val theme = themeDir.name
                        themeDir.listFiles()?.forEach { file ->
                            if (file.isFile && file.extension == "png" && file.length() > 0) {
                                val nameWithoutExt = file.nameWithoutExtension
                                knownTiles["${theme}_$nameWithoutExt"] = file.lastModified()
                                count++
                            }
                        }
                    }
                }
                Log.d(TAG, "Scanned $count existing tiles into memory index")
            } catch (e: Exception) {
                Log.w(TAG, "Error scanning existing tiles: ${e.message}")
            }
        }
    }

    private fun getFileForTile(z: Int, x: Int, y: Int, theme: String, createParents: Boolean = false): File? {
        val root = getOrInitDir() ?: return null
        val cleanTheme = if (theme.equals("light", ignoreCase = true)) "light" else "dark"
        val themeDir = File(root, cleanTheme)
        if (createParents && !themeDir.exists()) {
            themeDir.mkdirs()
        }
        return File(themeDir, "${z}_${x}_${y}.png")
    }


    /** Retrieves the raw PNG bytes for a tile from memory or phone disk, or null on cache miss. */
    fun getTileBytes(z: Int, x: Int, y: Int, theme: String): ByteArray? {
        val key = tileKey(z, x, y, theme)
        // 1. Instant in-memory LruCache hit (0 ms)
        memoryTileCache.get(key)?.let {
            hitCount.incrementAndGet()
            return it
        }

        // 2. Persistent phone disk cache
        val file = getFileForTile(z, x, y, theme) ?: return null
        if (!file.exists() || file.length() < 64L || KnownBadTiles.isBad(file.length())) {
            if (file.exists() && (file.length() == 0L || KnownBadTiles.isBad(file.length()))) file.delete()
            missCount.incrementAndGet()
            knownTiles.remove(key)
            return null
        }
        return try {
            val bytes = file.readBytes()
            if (isValidPng(bytes)) {
                hitCount.incrementAndGet()
                knownTiles[key] = file.lastModified()
                memoryTileCache.put(key, bytes)
                bytes
            } else {
                missCount.incrementAndGet()
                file.delete()
                knownTiles.remove(key)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading tile bytes ($key): ${e.message}")
            missCount.incrementAndGet()
            null
        }
    }

    /** Retrieves the decoded RGB_565 Bitmap for a tile from phone disk, or null on cache miss. */
    fun getTileBitmap(z: Int, x: Int, y: Int, theme: String): Bitmap? {
        val key = tileKey(z, x, y, theme)
        // If present in memory cache, decode directly from bytes
        memoryTileCache.get(key)?.let { bytes ->
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bmp != null) {
                hitCount.incrementAndGet()
                return bmp
            }
        }

        val file = getFileForTile(z, x, y, theme) ?: return null
        if (!file.exists() || file.length() < 64L || KnownBadTiles.isBad(file.length())) {
            if (file.exists() && KnownBadTiles.isBad(file.length())) file.delete()
            missCount.incrementAndGet()
            knownTiles.remove(key)
            return null
        }
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            if (bmp != null) {
                hitCount.incrementAndGet()
                knownTiles[key] = file.lastModified()
                bmp
            } else {
                missCount.incrementAndGet()
                file.delete()
                knownTiles.remove(key)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed decoding tile bitmap ($key): ${e.message}")
            missCount.incrementAndGet()
            null
        }
    }

    private fun isValidPng(bytes: ByteArray): Boolean {
        if (bytes.size < 64) return false
        return bytes[0] == 0x89.toByte() &&
               bytes[1] == 'P'.code.toByte() &&
               bytes[2] == 'N'.code.toByte() &&
               bytes[3] == 'G'.code.toByte()
    }

    /**
     * Persists tile PNG bytes received from the comma device to phone storage.
     * Caches immediately to memory and uses atomic disk write so repeat requests never query again.
     */
    fun putTileBytes(z: Int, x: Int, y: Int, theme: String, bytes: ByteArray) {
        if (!isValidPng(bytes)) return
        val targetFile = getFileForTile(z, x, y, theme, createParents = true) ?: return
        val key = tileKey(z, x, y, theme)

        // Store immediately in fast memory cache so subsequent calls never wait on disk or re-query
        memoryTileCache.put(key, bytes)
        knownTiles[key] = System.currentTimeMillis()

        pruningExecutor.execute {
            try {
                val tmpFile = File(targetFile.parentFile, "${targetFile.name}.${System.currentTimeMillis()}.tmp")
                FileOutputStream(tmpFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                }
                if (tmpFile.renameTo(targetFile)) {
                    targetFile.setLastModified(System.currentTimeMillis())
                    knownTiles[key] = targetFile.lastModified()
                    writeCount.incrementAndGet()
                    checkPruneThreshold()
                } else {
                    // Fallback to copy if atomic rename fails across partitions
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                    knownTiles[key] = targetFile.lastModified()
                    writeCount.incrementAndGet()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed persisting tile ($key): ${e.message}")
            }
        }
    }

    /** Returns total number of cached tiles on phone. */
    fun getCachedTileCount(): Int = knownTiles.size

    /** Computes total disk space used by cached tiles in bytes. */
    fun getCacheSizeBytes(): Long {
        val root = getOrInitDir() ?: return 0L
        return calculateDirSize(root)
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateDirSize(file) else file.length()
        }
        return size
    }

    /** Deletes all cached tiles from the phone. */
    fun clearCache(): Boolean {
        val root = getOrInitDir() ?: return false
        memoryTileCache.evictAll()
        knownTiles.clear()
        return try {
            root.deleteRecursively()
            root.mkdirs()
            Log.i(TAG, "Cleared comma tile disk cache")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing tile cache: ${e.message}")
            false
        }
    }

    private fun checkPruneThreshold() {
        if (isPruning.get()) return
        val root = baseDir ?: return
        // Only run disk size check periodically
        if (writeCount.get() % 250L != 0L) return

        if (!isPruning.compareAndSet(false, true)) return
        pruningExecutor.execute {
            try {
                val totalSize = calculateDirSize(root)
                if (totalSize > MAX_CACHE_BYTES) {
                    Log.i(TAG, "Tile cache size ($totalSize bytes) exceeds $MAX_CACHE_BYTES bytes, pruning...")
                    val allFiles = mutableListOf<File>()
                    root.listFiles()?.forEach { themeDir ->
                        if (themeDir.isDirectory) {
                            themeDir.listFiles()?.filter { it.isFile && it.extension == "png" }?.let {
                                allFiles.addAll(it)
                            }
                        }
                    }
                    // Sort by oldest modified
                    allFiles.sortBy { it.lastModified() }
                    var currentSize = totalSize
                    for (file in allFiles) {
                        if (currentSize <= PRUNE_TARGET_BYTES) break
                        val length = file.length()
                        val theme = file.parentFile?.name ?: "dark"
                        val key = "${theme}_${file.nameWithoutExtension}"
                        if (file.delete()) {
                            currentSize -= length
                            knownTiles.remove(key)
                        }
                    }
                    Log.i(TAG, "Pruned tile cache to $currentSize bytes")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during tile cache pruning: ${e.message}")
            } finally {
                isPruning.set(false)
            }
        }
    }
}
