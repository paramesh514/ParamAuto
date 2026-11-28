package com.parampilot.auto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "bookmark this spot" log: timestamped labels with the GPS
 * position where the user dropped them (NavScreen's Bookmark action).
 *
 * Saved PLACES do not live here anymore (Addendum 158): favorites,
 * recents, and the Home/Work shortcuts are the comma's own lists
 * ([CommaPlaces], from the nav status snapshot), saved and removed
 * through the comma's navigation actions ([CommaPlaceActions]).
 */
object Bookmarks {
    private const val PREFS = "bookmarks"
    private const val KEY = "items"
    private const val MAX_ITEMS = 100

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
