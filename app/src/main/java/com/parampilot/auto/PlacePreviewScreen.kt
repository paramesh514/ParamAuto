package com.parampilot.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/**
 * Place preview for the car search (Addendum 154, diagnosis
 * A1): tapping a search result lands here first — name,
 * address, distance and ETA — and nothing is sent to the comma
 * until the driver taps Navigate.
 *
 * The pre-154 flow sent the destination the instant a result
 * row was tapped, so one mistap on a name + one-line-address
 * row started a route to the wrong place and the only
 * recovery was End Route. Google Maps on AA shows the same
 * kind of preview before starting.
 *
 * Navigate pops this preview and hands the destination back
 * to the search screen's existing send path, so the send,
 * queueing and failure behavior behind it are unchanged.
 */
class PlacePreviewScreen(
    carContext: CarContext,
    private val dest: NavDestination,
    private val onNavigate: (NavDestination) -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val gps = NavStateManager.currentGps
        val etaText = when {
            dest.hasCoordinates && gps != null -> SearchRowText.etaDistanceText(
                GuidanceMath.haversineM(
                    gps.latitude, gps.longitude, dest.latitude, dest.longitude
                )
            )
            dest.distanceM != null -> SearchRowText.etaDistanceText(dest.distanceM!!)
            else -> null
        }
        val address = SearchRowText.addressLine(dest.placeName, dest.name)
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(address ?: dest.name)
                    .apply { if (etaText != null) addText(etaText) }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Navigate")
                    .setOnClickListener {
                        try {
                            screenManager.pop()
                        } catch (e: Exception) {
                            android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}")
                        }
                        onNavigate(dest)
                    }
                    .build()
            )
            .build()
        return PaneTemplate.Builder(pane)
            .setTitle(dest.name)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
