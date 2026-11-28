package com.parampilot.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/**
 * Alternate-route chooser for the car (Addendum 153, diagnosis
 * C3/C5): one row per route the comma offers, each with its
 * total time and distance, the current one marked. Tapping a
 * row selects that route through [CarRouteSelection] — the
 * same layer-2 selection the phone's routes list uses — and
 * returns to the map.
 *
 * This is the car's primary route-selection surface: the map
 * itself has no tap hit-testing for alternates, so the list is
 * how a driver picks one deliberately instead of cycling blind
 * with the Alt action.
 */
class RoutesScreen(
    carContext: CarContext,
    private val client: CommaClient
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val routes = NavStateManager.activeRoutes
        val selectedId = NavStateManager.selectedRouteId
        val listBuilder = ItemList.Builder()
        if (routes.isEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("No routes available")
                    .build()
            )
        } else {
            for (route in routes) {
                val isCurrent = route.id == selectedId
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(RouteChoice.labelText(route))
                        .apply {
                            if (route.summary.isNotEmpty()) addText(route.summary)
                            if (isCurrent) addText("Current route")
                        }
                        .setOnClickListener {
                            if (isCurrent) {
                                popSelf()
                            } else {
                                CarRouteSelection.select(carContext, client, route) {
                                    popSelf()
                                }
                            }
                        }
                        .build()
                )
            }
        }
        return ListTemplate.Builder()
            .setTitle("Routes")
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun popSelf() {
        try {
            screenManager.pop()
        } catch (e: Exception) {
            android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
