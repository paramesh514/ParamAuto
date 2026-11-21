package com.parampilot.auto

/**
 * Lengths of tile files that are known not to be maps.
 *
 * Pure layer-2 decision (no Android classes) so the JVM unit-test
 * harness can pin it: [CommaTileDiskCache] holds Android state and
 * cannot even load under the harness's android.jar stubs.
 *
 * - 337,892 B: the bad tile the disk cache already knew.
 * - 2,049 B / 2,513 B: CARTO's static "API KEY REQUIRED" watermark
 *   PNGs (voyager/light and dark_all/dark — verified live 2026-11-20:
 *   byte-identical for every tile of the style). The old cellular
 *   fallback and the phone MapLibre CARTO interceptor cached these
 *   on disk, so a keyless Auto map could keep showing a blank
 *   watermark tile from disk even after the fetch path moved to OSM.
 *   A real tile of exactly one of these sizes is only re-fetched,
 *   never lost.
 */
object KnownBadTiles {

    val LENGTHS = setOf(337892L, 2049L, 2513L)

    fun isBad(length: Long): Boolean = LENGTHS.contains(length)
}
