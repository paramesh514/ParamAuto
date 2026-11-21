package com.parampilot.auto

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

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = ParamAutoSession()
}

