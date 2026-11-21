package com.parampilot.auto

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/**
 * Home-screen widget (Feature 20): the last-known comma state at a
 * glance — Parked/Driving, current destination, voltage, and when the
 * snapshot was taken — without opening the app.
 *
 * The provider is deliberately dumb: it renders only what
 * [CommaWidgetUpdater] last wrote to the "comma_widget" preferences.
 * It never touches the network or the comma client, so it can refresh
 * on the system's cadence even while the app is closed; it simply shows
 * the freshest state the app itself last observed.
 */
class CommaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val state = CommaWidgetUpdater.read(context)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context, state))
        }
    }

    companion object {
        fun buildRemoteViews(context: Context, state: WidgetState): RemoteViews {
            // Resource ids are resolved by name so this file carries no
            // compile-time dependency on the generated R entries for the
            // widget layout (added with this feature).
            val res = context.resources
            val pkg = context.packageName
            fun layoutId(name: String) = res.getIdentifier(name, "layout", pkg)
            fun viewId(name: String) = res.getIdentifier(name, "id", pkg)
            val views = RemoteViews(pkg, layoutId("widget_comma"))
            val lines = state.formatLines()
            views.setTextViewText(viewId("widget_line1"), lines[0])
            views.setTextViewText(viewId("widget_line2"), lines[1])
            views.setTextViewText(viewId("widget_line3"), lines[2])
            views.setTextViewText(viewId("widget_line4"), lines[3])
            return views
        }
    }
}

/**
 * Writes the current NavStateManager snapshot into the widget's
 * preferences whenever a material value changes, and pushes the fresh
 * render to any placed widgets immediately. Called from MainActivity's
 * nav-state listener; the change key keeps steady-state polls from
 * rewriting the file on every tick.
 */
object CommaWidgetUpdater {

    const val PREFS_NAME = "comma_widget"
    private const val KEY_STATE = "state"
    private const val KEY_CHANGE = "change_key"

    fun read(context: Context): WidgetState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WidgetState.deserialize(prefs.getString(KEY_STATE, null))
            ?: WidgetState.EMPTY
    }

    /** Snapshot NSM and persist + republish when something material moved. */
    fun refresh(context: Context) {
        try {
            val state = WidgetState(
                deviceState = NavStateManager.deviceState,
                destinationName = NavStateManager.activeDestination?.name,
                voltageV = NavStateManager.latestPower?.voltageV,
                updatedAtMs = System.currentTimeMillis()
            )
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_CHANGE, null) == state.changeKey() &&
                prefs.contains(KEY_STATE)
            ) {
                return
            }
            prefs.edit()
                .putString(KEY_STATE, state.serialize())
                .putString(KEY_CHANGE, state.changeKey())
                .apply()
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, CommaWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val views = CommaWidgetProvider.buildRemoteViews(context, state)
            for (id in ids) manager.updateAppWidget(id, views)
        } catch (_: Exception) {
            // A widget write must never disturb the driving UI.
        }
    }
}
