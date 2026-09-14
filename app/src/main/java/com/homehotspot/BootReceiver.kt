package com.homehotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        LogManager.init(context)
        LogManager.log("BOOT_EVENT", LogStatus.INFO, "Received system broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                MonitoringManager.init(context)
                MonitoringManager.reconcileMonitoring(context)
            }
        }
    }
}
