package com.parampilot.auto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** One session per car connection. Attaches to the shared singleton CommaClient for the session lifetime. */
class ParamAutoSession : Session() {
    private var client: CommaClient? = null
    private var locationManager: LocationManager? = null

    private val feederListener = LocationListener { loc -> PhoneGpsFeeder.onLocation(loc) }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                stopFeederLocation()
                client?.detach()
                client = null
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val c = CommaClient.getInstance(carContext).attach()
        client = c
        val mode = c.getConnectionMode()
        if (mode == CommaClient.MODE_AUTO || mode == CommaClient.MODE_BLUETOOTH) {
            BleBridgeClient.startIfPaired(carContext)
        }
        startFeederLocation()
        return NavScreen(carContext, c)
    }

    /**
     * Supplies phone GPS fixes to [PhoneGpsFeeder] for the life of the
     * car session, so a trip started from Android Auto keeps feeding
     * the comma even when the phone activity is not running. Registered
     * only when the "Phone location → Comma" toggle is on AND location
     * permission was already granted through that toggle's in-app gate;
     * never requested here. Fixes are ignored unless a feeding session
     * is active.
     */
    private fun startFeederLocation() {
        if (!NavStateManager.usePhoneLocationToComma) return
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val lm = carContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            locationManager = lm
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, feederListener, Looper.getMainLooper()
            )
        } catch (_: Exception) {
            // No GPS provider or the permission raced away: the AA side
            // simply has no fix source; the phone activity still feeds.
        }
    }

    private fun stopFeederLocation() {
        try {
            locationManager?.removeUpdates(feederListener)
        } catch (e: Exception) { android.util.Log.w("ParamAuto", "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        locationManager = null
    }
}

