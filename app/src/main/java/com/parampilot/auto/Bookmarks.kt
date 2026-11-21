package com.parampilot.auto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages saved locations, custom bookmarks, and user-configured Home and Office shortcuts.
 */
object Bookmarks {
    private const val PREFS = "bookmarks"
    private const val KEY = "items"
    private const val KEY_HOME = "home_destination"
    private const val KEY_OFFICE = "office_destination"
    private const val KEY_STARRED = "starred_places"
    private const val MAX_ITEMS = 100

    val defaultFallbackHome = NavDestination(
        name = "Home",
        placeName = "Not configured",
        latitude = 33.4484,
        longitude = -112.0740
    )

    val defaultFallbackWork = NavDestination(
        name = "Office",
        placeName = "Not configured",
        latitude = 33.4484,
        longitude = -112.0740
    )

    /**
     * Canonical Home destination if configured by the user, or fallback default if not configured.
     */
    val home: NavDestination
        get() = NavStateManager.appContext?.let { getHome(it) } ?: defaultFallbackHome

    /**
     * Canonical Office / Work destination if configured by the user, or fallback default if not configured.
     */
    val work: NavDestination
        get() = NavStateManager.appContext?.let { getOffice(it) } ?: defaultFallbackWork

    fun isHomeConfigured(context: Context): Boolean = getHome(context) != null

    fun isOfficeConfigured(context: Context): Boolean = getOffice(context) != null

    fun getHome(context: Context): NavDestination? = loadDest(context, KEY_HOME) ?: loadDest(context, "home")

    fun setHome(context: Context, dest: NavDestination) {
        saveDest(context, KEY_HOME, dest.copy(name = "Home"))
        NavStateManager.notifyChanged()
    }

    fun clearHome(context: Context) {
        val ctx = context.applicationContext ?: context
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HOME).apply()
        NavStateManager.notifyChanged()
    }

    fun getOffice(context: Context): NavDestination? {
        val dest = loadDest(context, KEY_OFFICE)
            ?: loadDest(context, "work_destination")
            ?: loadDest(context, "work")
        return dest?.let {
            if (it.name.isEmpty() || it.name.equals("Office", ignoreCase = true)) it.copy(name = "Work") else it
        }
    }

    fun setOffice(context: Context, dest: NavDestination) {
        val target = dest.copy(name = if (dest.name.isEmpty() || dest.name.equals("Office", ignoreCase = true)) "Work" else dest.name)
        saveDest(context, KEY_OFFICE, target)
        saveDest(context, "work_destination", target)
        NavStateManager.notifyChanged()
    }

    fun clearOffice(context: Context) {
        val ctx = context.applicationContext ?: context
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_OFFICE)
            .remove("work_destination")
            .remove("work")
            .apply()
        NavStateManager.notifyChanged()
    }

    private fun loadDest(context: Context, key: String): NavDestination? {
        val ctx = context.applicationContext ?: context
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val lat = obj.optDouble("latitude", Double.NaN)
            val lng = obj.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) null
            else NavDestination(
                name = obj.optString("name", ""),
                placeName = obj.optString("placeName", ""),
                latitude = lat,
                longitude = lng,
                mapboxId = obj.optString("mapboxId", "").takeIf { it.isNotEmpty() }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun saveDest(context: Context, key: String, dest: NavDestination) {
        val obj = JSONObject().apply {
            put("name", dest.name)
            put("placeName", dest.placeName)
            put("latitude", dest.latitude)
            put("longitude", dest.longitude)
            dest.mapboxId?.let { put("mapboxId", it) }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, obj.toString()).apply()
    }

    /**
     * User-starred locations with custom names (e.g. "Mom's House", "Gym", "Airport").
     */
    fun getStarred(context: Context): List<NavDestination> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STARRED, null)
        if (raw.isNullOrBlank()) return emptyList()
        val list = mutableListOf<NavDestination>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val lat = obj.optDouble("latitude", Double.NaN)
                val lng = obj.optDouble("longitude", Double.NaN)
                if (!lat.isNaN() && !lng.isNaN()) {
                    list.add(
                        NavDestination(
                            name = obj.optString("name", "Starred Location"),
                            placeName = obj.optString("placeName", ""),
                            latitude = lat,
                            longitude = lng,
                            mapboxId = obj.optString("mapboxId", "").takeIf { it.isNotEmpty() }
                        )
                    )
                }
            }
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        return list
    }

    fun addStarred(context: Context, dest: NavDestination) {
        val list = getStarred(context).toMutableList()
        val idx = list.indexOfFirst { existing ->
            existing.name.equals(dest.name, ignoreCase = true) ||
            (existing.hasCoordinates && dest.hasCoordinates &&
             Math.abs(existing.latitude - dest.latitude) < 0.0005 &&
             Math.abs(existing.longitude - dest.longitude) < 0.0005)
        }
        if (idx >= 0) {
            list[idx] = dest
        } else {
            list.add(0, dest)
        }
        saveStarredList(context, list)
    }

    fun removeStarred(context: Context, dest: NavDestination) {
        val list = getStarred(context).toMutableList()
        list.removeAll { existing ->
            existing.name.equals(dest.name, ignoreCase = true) ||
            (existing.hasCoordinates && dest.hasCoordinates &&
             Math.abs(existing.latitude - dest.latitude) < 0.0005 &&
             Math.abs(existing.longitude - dest.longitude) < 0.0005)
        }
        saveStarredList(context, list)
    }

    fun isStarred(context: Context, dest: NavDestination): Boolean {
        val list = getStarred(context)
        return list.any { existing ->
            existing.name.equals(dest.name, ignoreCase = true) ||
            (existing.hasCoordinates && dest.hasCoordinates &&
             Math.abs(existing.latitude - dest.latitude) < 0.0005 &&
             Math.abs(existing.longitude - dest.longitude) < 0.0005)
        }
    }

    private fun saveStarredList(context: Context, list: List<NavDestination>) {
        val arr = JSONArray()
        list.forEach { dest ->
            arr.put(JSONObject().apply {
                put("name", dest.name)
                put("placeName", dest.placeName)
                put("latitude", dest.latitude)
                put("longitude", dest.longitude)
                dest.mapboxId?.let { put("mapboxId", it) }
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STARRED, arr.toString()).apply()
    }

    fun add(context: Context, label: String, gps: GpsPosition?) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = load(prefs.getString(KEY, null))
            val item = JSONObject()
            item.put("t", System.currentTimeMillis())
            item.put("label", label)
            if (gps != null) {
                item.put("lat", gps.latitude)
                item.put("lng", gps.longitude)
            }
            arr.put(item)
            while (arr.length() > MAX_ITEMS) arr.remove(0)
            prefs.edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) {
            // Bookmarks are best-effort; never let them break navigation.
        }
    }

    fun count(context: Context): Int = try {
        load(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)).length()
    } catch (e: Exception) {
        0
    }

    private fun load(raw: String?): JSONArray {
        if (raw.isNullOrBlank()) return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }
}
