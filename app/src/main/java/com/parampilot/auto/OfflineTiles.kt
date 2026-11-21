package com.parampilot.auto

import android.content.Context
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline tile packs (review item D1).
 *
 * Downloads a MapLibre offline region covering the Phoenix metro so daily
 * drives around home need no tile traffic at all, and the map keeps working
 * with no data connection. All entry points are defensive: any failure is
 * reported through onComplete(false, message) instead of throwing.
 */
object OfflineTiles {

    private const val REGION_NAME = "Phoenix Metro"

    // Phoenix metro bounding box: NE (33.95, -111.85), SW (33.25, -112.35).
    private const val NORTH = 33.95
    private const val EAST = -111.85
    private const val SOUTH = 33.25
    private const val WEST = -112.35
    private const val MIN_ZOOM = 10.0
    private const val MAX_ZOOM = 14.0

    /**
     * Downloads (or resumes) the Phoenix-metro offline region for [styleUrl].
     * [onProgress] receives 0–100 percent complete; [onComplete] fires exactly
     * once with success/failure and a user-facing message.
     */
    fun downloadPhoenixMetro(
        context: Context,
        styleUrl: String,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        val finished = AtomicBoolean(false)
        fun completeOnce(success: Boolean, message: String) {
            if (finished.compareAndSet(false, true)) {
                onComplete(success, message)
            }
        }
        try {
            val bounds = LatLngBounds.Builder()
                .include(LatLng(NORTH, EAST))
                .include(LatLng(SOUTH, WEST))
                .build()
            val pixelRatio = context.resources.displayMetrics.density
            val definition = OfflineTilePyramidRegionDefinition(
                styleUrl, bounds, MIN_ZOOM, MAX_ZOOM, pixelRatio
            )
            val metadata = "{\"regionName\":\"$REGION_NAME\"}".toByteArray(Charsets.UTF_8)
            OfflineManager.getInstance(context).createOfflineRegion(
                definition,
                metadata,
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        try {
                            offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                                override fun onStatusChanged(status: OfflineRegionStatus) {
                                    if (status.isComplete) {
                                        try {
                                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                                        completeOnce(true, "Offline Phoenix map downloaded")
                                    } else {
                                        val required = status.requiredResourceCount
                                        val percent = if (required > 0L) {
                                            (status.completedResourceCount * 100L / required).toInt()
                                        } else {
                                            0
                                        }
                                        try {
                                            onProgress(percent.coerceIn(0, 100))
                                        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                                    }
                                }

                                override fun onError(error: OfflineRegionError) {
                                    completeOnce(false, "Offline map download failed: ${error.message}")
                                }

                                override fun mapboxTileCountLimitExceeded(limit: Long) {
                                    completeOnce(false, "Offline map download failed: tile count limit ($limit) exceeded")
                                }
                            })
                            offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        } catch (e: Exception) {
                            completeOnce(false, "Offline map download failed: ${e.message ?: "unknown error"}")
                        }
                    }

                    override fun onError(error: String) {
                        completeOnce(false, "Offline map download failed: $error")
                    }
                }
            )
        } catch (e: Exception) {
            completeOnce(false, "Offline map download failed: ${e.message ?: "unknown error"}")
        }
    }
}
