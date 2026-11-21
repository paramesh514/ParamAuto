package com.parampilot.auto

/**
 * Lazy permission requests (Param, 2026-11-02): the app asks for a system
 * permission ONLY at the moment the user turns on the feature that needs it,
 * and always after an in-app explanation of why. Nothing is requested at
 * startup, and with every Phone Data toggle off (the default) no permission
 * is required at all.
 *
 * Settings registers one-shot callbacks here before firing the system
 * prompt; MainActivity.onRequestPermissionsResult dispatches the result
 * through [handleResult]. Pure Kotlin (no Android types) so the dispatch
 * contract is unit-testable.
 */
object PermissionFlow {
    const val REQUEST_LOCATION = 9421
    const val REQUEST_BLUETOOTH = 9422

    @Volatile
    var onLocationGranted: (() -> Unit)? = null

    @Volatile
    var onLocationDenied: (() -> Unit)? = null

    @Volatile
    var onBluetoothGranted: (() -> Unit)? = null

    @Volatile
    var onBluetoothDenied: (() -> Unit)? = null

    /**
     * Dispatch a permission result to the registered one-shot callbacks and
     * clear them. Returns true when [requestCode] is one of ours.
     */
    fun handleResult(requestCode: Int, granted: Boolean): Boolean {
        when (requestCode) {
            REQUEST_LOCATION -> {
                val grantedCb = onLocationGranted
                val deniedCb = onLocationDenied
                onLocationGranted = null
                onLocationDenied = null
                if (granted) grantedCb?.invoke() else deniedCb?.invoke()
                return true
            }
            REQUEST_BLUETOOTH -> {
                val grantedCb = onBluetoothGranted
                val deniedCb = onBluetoothDenied
                onBluetoothGranted = null
                onBluetoothDenied = null
                if (granted) grantedCb?.invoke() else deniedCb?.invoke()
                return true
            }
            else -> return false
        }
    }

    /** Drop any pending callbacks (e.g. the hosting dialog was dismissed). */
    fun clear() {
        onLocationGranted = null
        onLocationDenied = null
        onBluetoothGranted = null
        onBluetoothDenied = null
    }
}
