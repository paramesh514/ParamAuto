package com.parampilot.auto

/**
 * The point a long-press drops on the map (Param, 2026-11-20:
 * "Long press any point on map show add favorites. Click on it
 * save as favorite"). Layer 2: turning the pressed coordinates —
 * plus whatever a reverse geocoder found for them — into the
 * NavDestination the save dialog starts from.
 *
 * Without a reverse result the favorite's name is "Dropped pin"
 * and its address line is the coordinates themselves; the save
 * dialog always lets him rename before saving.
 */
object DroppedPin {

    const val DEFAULT_NAME = "Dropped pin"

    fun coordsLabel(latitude: Double, longitude: Double): String =
        "%.5f, %.5f".format(latitude, longitude)

    fun destination(
        latitude: Double,
        longitude: Double,
        reverse: NavDestination?
    ): NavDestination {
        val name = reverse?.name?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NAME
        val placeName = reverse?.placeName?.takeIf { it.isNotBlank() }
            ?: coordsLabel(latitude, longitude)
        return NavDestination(
            name = name,
            placeName = placeName,
            latitude = latitude,
            longitude = longitude,
            source = "pin"
        )
    }
}
