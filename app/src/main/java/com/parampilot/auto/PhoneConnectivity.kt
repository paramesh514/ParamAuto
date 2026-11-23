package com.parampilot.auto

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the phone currently has a working internet connection,
 * as Android itself judges it: the default network must carry the
 * INTERNET capability and be VALIDATED (Android's own probe of
 * actual reachability — a dead-router Wi-Fi does not count).
 * Unknown states (no ConnectivityManager, no capability record)
 * read as online: a notice must never cry wolf on a guess. No
 * default network at all is the one certain offline state.
 */
object PhoneConnectivity {

    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
