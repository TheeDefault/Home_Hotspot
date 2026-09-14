package com.homehotspot

import android.app.Application

class HomeHotspotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LogManager.init(this)
        NotificationHelper.createNotificationChannel(this)
        MonitoringManager.init(this)
        LogManager.log("APP_STARTUP", LogStatus.INFO, "HomeHotspot application initialized")
    }
}
