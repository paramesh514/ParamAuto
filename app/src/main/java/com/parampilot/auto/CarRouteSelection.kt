package com.parampilot.auto

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast

/**
 * Route selection from the car screens (Addendum 153, diagnosis
 * C4) — the one path both the NavScreen Alt action and the
 * Routes chooser go through.
 *
 * The choice itself stays in layer 2, exactly where the phone's
 * route taps put it: [NavStateManager.selectedRouteId] is set
 * optimistically, then [NavigationManager.selectRouteSg] asks
 * the comma. The pre-153 car code DISCARDED that call's result:
 * when the comma rejected or never answered, the car kept
 * showing the new route as selected while the comma kept
 * guiding the old one, until the next snapshot silently flipped
 * the display back. Here the result is honored — on failure the
 * local selection reverts to what it was and the comma's error
 * is shown, so the car never displays a route the comma
 * refused.
 */
object CarRouteSelection {

    fun select(
        carContext: CarContext,
        client: CommaClient,
        route: RouteOption,
        onDone: () -> Unit
    ) {
        val previousId = NavStateManager.selectedRouteId
        NavStateManager.selectedRouteId = route.id
        NavStateManager.notifyChanged()
        val ip = NavStateManager.commaIp ?: client.commaIp
        if (ip.isNullOrBlank()) {
            // No comma to confirm with: an unconfirmed selection
            // must not stand.
            NavStateManager.selectedRouteId = previousId
            NavStateManager.notifyChanged()
            CarToast.makeText(
                carContext,
                "Route not changed — comma not connected",
                CarToast.LENGTH_LONG
            ).show()
            onDone()
            return
        }
        Thread {
            val ok = NavigationManager().selectRouteSg(
                SgGalaxyClient.baseUrlFor(ip),
                route.id,
                NavStateManager.sgRevision
            )
            Handler(Looper.getMainLooper()).post {
                if (!ok) {
                    NavStateManager.selectedRouteId = previousId
                    NavStateManager.notifyChanged()
                    CarToast.makeText(
                        carContext,
                        "Route change rejected — " +
                            (NavigationManager.lastSendError ?: "comma didn't answer"),
                        CarToast.LENGTH_LONG
                    ).show()
                }
                onDone()
            }
        }.start()
    }
}
