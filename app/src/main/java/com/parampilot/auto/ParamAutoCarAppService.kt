package com.parampilot.auto

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Entry point for Android Auto. Declared as a NAVIGATION car app in the manifest. */
class ParamAutoCarAppService : CarAppService() {
    override fun onCreate() {
        super.onCreate()
        if (NavStateManager.appContext == null) {
            NavStateManager.appContext = applicationContext
        }
        CommaTileDiskCache.init(applicationContext)
        MapFrameDiskCache.init(applicationContext)
    }

    /**
     * Host validation, per the Car App Library's own guidance:
     * debuggable builds accept any host (DHU, emulators, test
     * hosts); release builds accept only the hosts Google ships
     * in the library's sample allowlist — Android Auto and the
     * AAOS template host, matched by signing certificate.
     * Shipping ALLOW_ALL in release would let any app pose as a
     * car host and bind to this service.
     */
    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = ParamAutoSession()
}

