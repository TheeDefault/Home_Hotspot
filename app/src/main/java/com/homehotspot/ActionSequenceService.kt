package com.homehotspot

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ActionSequenceService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_ENTER_SEQUENCE) {
            startExecution()
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startExecution() {
        if (!isExecuting.compareAndSet(false, true)) {
            LogManager.log(
                "ACTION_SEQUENCE_SKIPPED",
                LogStatus.INFO,
                "Action sequence already in progress"
            )
            stopSelf()
            return
        }

        // Promote to foreground service for reliable background survival
        startServiceInForeground()

        Thread {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HomeHotspot:ActionSequenceServiceWakeLock"
            )?.apply {
                try {
                    acquire(25_000L) // 25 seconds safety timeout
                } catch (e: Exception) {
                    LogManager.log("WAKELOCK", LogStatus.FAILED, "Failed to acquire WakeLock: ${e.message}")
                }
            }

            try {
                LogManager.log(
                    "ACTION_SEQUENCE_STARTING",
                    LogStatus.INFO,
                    "ENTER sequence starting: Beginning 3-step action sequence with 1s delays"
                )
                LogManager.log(
                    "ACTION_SEQUENCE_STARTED",
                    LogStatus.INFO,
                    "Action sequence actively executing in foreground service"
                )
                NotificationHelper.showNotification(
                    this,
                    "HOME Reached",
                    "Geofence ENTER triggered: Starting action sequence",
                    isEvent = true
                )

                // 1. Wait ~1 second
                Thread.sleep(1000)

                // 2. Modern Screen Wake
                performScreenWake(powerManager)

                // 2. Wait ~1 second
                Thread.sleep(1000)

                // 3. Notification Shade Expansion
                performNotificationShade()

                // 3. Wait ~1 second
                Thread.sleep(1000)

                // 4. Mobile Hotspot via Accessibility Automation
                performHotspotAutomation()

                LogManager.log(
                    "ACTION_SEQUENCE_COMPLETED",
                    LogStatus.SUCCESS,
                    "Action sequence finished successfully"
                )

            } catch (e: Exception) {
                LogManager.log(
                    "ACTION_SEQUENCE_ERROR",
                    LogStatus.FAILED,
                    "Exception during action sequence: ${e.message}"
                )
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (e: Exception) {
                    // Safe cleanup
                }
                isExecuting.set(false)
                stopForeground(true)
                stopSelf()
            }
        }.start()
    }

    private fun startServiceInForeground() {
        val channelId = NotificationHelper.CHANNEL_ID
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Home Hotspot Automation")
            .setContentText("Executing HOME action sequence...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID_FOREGROUND,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID_FOREGROUND, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION_ID_FOREGROUND, notification)
            } catch (e2: Exception) {
                LogManager.log("FOREGROUND_SERVICE", LogStatus.INFO, "Foreground notification attached with fallback")
            }
        }
    }

    private fun performScreenWake(powerManager: PowerManager?) {
        LogManager.log("SCREEN_WAKE_REQUESTED", LogStatus.INFO, "Requesting modern screen wake via ScreenWakeActivity")

        try {
            val wakeIntent = Intent(this, ScreenWakeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            startActivity(wakeIntent)

            // Verify if screen turned on
            Thread.sleep(500)
            val isInteractive = powerManager?.isInteractive ?: false
            if (isInteractive) {
                LogManager.log("SCREEN_WAKE_SUCCESS", LogStatus.SUCCESS, "Screen woken up successfully")
            } else {
                LogManager.log(
                    "SCREEN_WAKE_UNAVAILABLE",
                    LogStatus.UNAVAILABLE,
                    "Screen wake activity launched, but device display remained non-interactive"
                )
            }
        } catch (e: Exception) {
            LogManager.log("SCREEN_WAKE_FAILED", LogStatus.FAILED, "Failed to launch screen wake activity: ${e.message}")
        }
    }

    private fun performNotificationShade() {
        LogManager.log("NOTIFICATION_SHADE_REQUESTED", LogStatus.INFO, "Requesting notification shade expansion")

        try {
            val permissionCheck = ContextCompat.checkSelfPermission(
                this,
                "android.permission.EXPAND_STATUS_BAR"
            )
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                LogManager.log(
                    "NOTIFICATION_SHADE_FAILED",
                    LogStatus.FAILED,
                    "Permission android.permission.EXPAND_STATUS_BAR is not granted"
                )
                return
            }

            @SuppressLint("WrongConstant")
            val statusBarService = getSystemService("statusbar")
            if (statusBarService == null) {
                LogManager.log("NOTIFICATION_SHADE_FAILED", LogStatus.FAILED, "StatusBar service unavailable")
                return
            }

            val statusBarManagerClass = Class.forName("android.app.StatusBarManager")
            val expandMethod: Method = try {
                statusBarManagerClass.getMethod("expandNotificationsPanel")
            } catch (e: NoSuchMethodException) {
                try {
                    statusBarManagerClass.getMethod("expand")
                } catch (e2: NoSuchMethodException) {
                    null
                } ?: throw e
            }

            expandMethod.invoke(statusBarService)
            LogManager.log(
                "NOTIFICATION_SHADE_SUCCESS",
                LogStatus.SUCCESS,
                "Notification panel expansion requested successfully"
            )
        } catch (e: SecurityException) {
            LogManager.log("NOTIFICATION_SHADE_FAILED", LogStatus.FAILED, "SecurityException expanding shade: ${e.message}")
        } catch (e: NoSuchMethodException) {
            LogManager.log("NOTIFICATION_SHADE_UNAVAILABLE", LogStatus.UNAVAILABLE, "expandNotificationsPanel method not found on this OS")
        } catch (e: Exception) {
            LogManager.log("NOTIFICATION_SHADE_FAILED", LogStatus.FAILED, "Error expanding notification shade: ${e.message}")
        }
    }

    private fun performHotspotAutomation() {
        LogManager.log("HOTSPOT_REQUESTED", LogStatus.INFO, "Requesting normal mobile Wi-Fi hotspot activation")

        if (!HotspotAccessibilityService.isRunning()) {
            LogManager.log(
                "HOTSPOT_UNAVAILABLE",
                LogStatus.UNAVAILABLE,
                "Accessibility Service is disabled. Enable 'Home Hotspot' in Accessibility settings for automatic hotspot toggling."
            )
            return
        }

        LogManager.log(
            "HOTSPOT_ACCESSIBILITY_STARTED",
            LogStatus.INFO,
            "Accessibility Service active: Opening settings and automating hotspot toggle"
        )

        val latch = CountDownLatch(1)
        var automationSuccess = false
        var automationDetails = ""

        HotspotAccessibilityService.requestEnableHotspot(this) { success, details ->
            automationSuccess = success
            automationDetails = details
            latch.countDown()
        }

        val completed = try {
            latch.await(16, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }

        if (completed && automationSuccess) {
            LogManager.log("HOTSPOT_SUCCESS", LogStatus.SUCCESS, automationDetails)
            NotificationHelper.showNotification(
                this,
                "Mobile Hotspot Active",
                "Mobile hotspot turned ON successfully",
                isEvent = true
            )
        } else if (completed) {
            LogManager.log("HOTSPOT_FAILED", LogStatus.FAILED, automationDetails)
        } else {
            LogManager.log("HOTSPOT_FAILED", LogStatus.FAILED, "Hotspot automation timed out")
        }
    }

    companion object {
        const val ACTION_START_ENTER_SEQUENCE = "com.homehotspot.action.START_ENTER_SEQUENCE"
        private const val NOTIFICATION_ID_FOREGROUND = 2002
        private val isExecuting = AtomicBoolean(false)

        fun startActionSequence(context: Context) {
            LogManager.log("ACTION_SEQUENCE_REQUESTED", LogStatus.INFO, "ENTER event requesting action sequence service")
            try {
                val intent = Intent(context, ActionSequenceService::class.java).apply {
                    action = ACTION_START_ENTER_SEQUENCE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                LogManager.log(
                    "ACTION_SEQUENCE_FAILED_TO_START",
                    LogStatus.FAILED,
                    "Failed to start ActionSequenceService: ${e.message}"
                )
            }
        }
    }
}
