package com.homehotspot

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

object ActionSequenceExecutor {
    private val isExecuting = AtomicBoolean(false)
    private var lastExecutionTime: Long = 0
    private const val DEBOUNCE_INTERVAL_MS = 60_000L // 1 minute cooldown to prevent duplicate rapid triggers

    @SuppressLint("WakelockTimeout")
    fun executeEnterSequence(context: Context, onComplete: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        if (now - lastExecutionTime < DEBOUNCE_INTERVAL_MS && lastExecutionTime > 0) {
            val remainingSec = (DEBOUNCE_INTERVAL_MS - (now - lastExecutionTime)) / 1000
            LogManager.log(
                "ACTION_SEQUENCE_SKIPPED",
                LogStatus.INFO,
                "Action sequence skipped: Debounce cooldown active ($remainingSec s remaining)"
            )
            onComplete()
            return
        }

        if (!isExecuting.compareAndSet(false, true)) {
            LogManager.log(
                "ACTION_SEQUENCE_SKIPPED",
                LogStatus.INFO,
                "Action sequence already in progress, ignoring duplicate trigger"
            )
            onComplete()
            return
        }

        lastExecutionTime = now

        Thread {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HomeHotspot:ActionSequenceWakeLock"
            )?.apply {
                try {
                    acquire(15_000L) // 15 seconds max safety timeout
                } catch (e: Exception) {
                    LogManager.log("WAKELOCK", LogStatus.FAILED, "Could not acquire CPU WakeLock: ${e.message}")
                }
            }

            try {
                LogManager.log(
                    "ACTION_SEQUENCE_STARTED",
                    LogStatus.INFO,
                    "ENTER sequence initiated: Executing 3-step action sequence with 1s delays"
                )
                NotificationHelper.showNotification(
                    context,
                    "HOME Reached",
                    "Geofence ENTER triggered: Starting action sequence",
                    isEvent = true
                )

                // 1. Wait 1 second
                Thread.sleep(1000)

                // 2. Attempt to wake screen/device
                attemptWakeScreen(context, powerManager)

                // 2. Wait 1 second
                Thread.sleep(1000)

                // 3. Attempt to open/pull notification shade
                attemptOpenNotificationShade(context)

                // 3. Wait 1 second
                Thread.sleep(1000)

                // 4. Attempt to turn ON normal mobile hotspot/tethering (internet sharing)
                attemptTurnOnNormalHotspot(context)

                LogManager.log(
                    "ACTION_SEQUENCE_COMPLETED",
                    LogStatus.SUCCESS,
                    "ENTER action sequence finished"
                )

            } catch (e: Exception) {
                LogManager.log(
                    "ACTION_SEQUENCE_ERROR",
                    LogStatus.FAILED,
                    "Error during action sequence: ${e.message}"
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
                onComplete()
            }
        }.start()
    }

    private fun attemptWakeScreen(context: Context, powerManager: PowerManager?) {
        if (powerManager == null) {
            LogManager.log("WAKE_SCREEN", LogStatus.FAILED, "PowerManager is null")
            return
        }

        try {
            val wasInteractive = powerManager.isInteractive
            if (wasInteractive) {
                LogManager.log("WAKE_SCREEN", LogStatus.INFO, "Screen is already interactive / turned ON")
                return
            }

            @Suppress("DEPRECATION")
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "HomeHotspot:ScreenWakeLock"
            )
            screenWakeLock.acquire(3000L)

            val isNowInteractive = powerManager.isInteractive
            if (isNowInteractive) {
                LogManager.log("WAKE_SCREEN", LogStatus.SUCCESS, "Screen woken up successfully via WakeLock")
            } else {
                LogManager.log(
                    "WAKE_SCREEN",
                    LogStatus.UNAVAILABLE,
                    "WakeLock acquired, but screen remained non-interactive (background wake restricted by OS/OEM on Android ${Build.VERSION.SDK_INT})"
                )
            }
        } catch (e: SecurityException) {
            LogManager.log("WAKE_SCREEN", LogStatus.FAILED, "SecurityException acquiring WakeLock: ${e.message}")
        } catch (e: Exception) {
            LogManager.log("WAKE_SCREEN", LogStatus.FAILED, "Failed to wake screen: ${e.message}")
        }
    }

    private fun attemptOpenNotificationShade(context: Context) {
        try {
            val permissionCheck = ContextCompat.checkSelfPermission(
                context,
                "android.permission.EXPAND_STATUS_BAR"
            )
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                LogManager.log(
                    "EXPAND_NOTIFICATION_SHADE",
                    LogStatus.FAILED,
                    "Permission android.permission.EXPAND_STATUS_BAR is not granted"
                )
                return
            }

            @SuppressLint("WrongConstant")
            val statusBarService = context.getSystemService("statusbar")
            if (statusBarService == null) {
                LogManager.log("EXPAND_NOTIFICATION_SHADE", LogStatus.FAILED, "StatusBar service unavailable")
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
                "EXPAND_NOTIFICATION_SHADE",
                LogStatus.SUCCESS,
                "Invoked status bar notification panel expansion successfully"
            )
        } catch (e: SecurityException) {
            LogManager.log("EXPAND_NOTIFICATION_SHADE", LogStatus.FAILED, "SecurityException: ${e.message}")
        } catch (e: NoSuchMethodException) {
            LogManager.log("EXPAND_NOTIFICATION_SHADE", LogStatus.UNAVAILABLE, "Method expandNotificationsPanel not found on this platform")
        } catch (e: Exception) {
            LogManager.log("EXPAND_NOTIFICATION_SHADE", LogStatus.FAILED, "Failed to expand notification shade: ${e.message}")
        }
    }

    /**
     * Attempts to enable normal mobile Wi-Fi hotspot / tethering for internet sharing.
     * Uses the best legitimate Android APIs and logs exact outcomes or platform security restrictions.
     */
    private fun attemptTurnOnNormalHotspot(context: Context) {
        LogManager.log(
            "NORMAL_HOTSPOT",
            LogStatus.INFO,
            "Attempting to enable normal mobile Wi-Fi hotspot (internet-sharing tethering)..."
        )

        // Method 1: ConnectivityManager / TetheringManager startTethering API
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager != null) {
            val tetheringHandled = tryStartConnectivityTethering(context, connectivityManager)
            if (tetheringHandled) {
                return
            }
        }

        // Method 2: Legacy WifiManager.setWifiApEnabled reflection (older Android / custom ROMs)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager != null) {
            val apHandled = trySetWifiApEnabled(wifiManager)
            if (apHandled) {
                return
            }
        }

        LogManager.log(
            "NORMAL_HOTSPOT",
            LogStatus.UNAVAILABLE,
            "Normal mobile Wi-Fi hotspot cannot be enabled programmatically: Android OS restricts cellular tethering activation to system/carrier apps with TETHER_PRIVILEGED permission on Android ${Build.VERSION.SDK_INT}"
        )
    }

    private fun tryStartConnectivityTethering(context: Context, connectivityManager: ConnectivityManager): Boolean {
        return try {
            val cmClass = connectivityManager.javaClass
            val methods = cmClass.declaredMethods
            val startTetheringMethod = methods.find { it.name == "startTethering" } ?: return false

            startTetheringMethod.isAccessible = true
            val paramTypes = startTetheringMethod.parameterTypes

            val callbackClass = paramTypes.find {
                it.name.contains("OnStartTetheringCallback") || it.name.contains("StartTetheringCallback")
            }

            var callbackInstance: Any? = null
            if (callbackClass != null && callbackClass.isInterface) {
                callbackInstance = Proxy.newProxyInstance(
                    callbackClass.classLoader,
                    arrayOf(callbackClass)
                ) { _, method, args ->
                    when (method.name) {
                        "onTetheringStarted" -> {
                            LogManager.log(
                                "NORMAL_HOTSPOT",
                                LogStatus.SUCCESS,
                                "Normal mobile Wi-Fi hotspot (internet-sharing tethering) started successfully"
                            )
                            NotificationHelper.showNotification(
                                context,
                                "Mobile Hotspot Active",
                                "Normal Wi-Fi tethering enabled: Internet sharing is ON",
                                isEvent = true
                            )
                        }
                        "onTetheringFailed" -> {
                            val errorCode = args?.firstOrNull() ?: "UNKNOWN"
                            LogManager.log(
                                "NORMAL_HOTSPOT",
                                LogStatus.FAILED,
                                "Normal Wi-Fi tethering failed with error code: $errorCode"
                            )
                        }
                    }
                    null
                }
            }

            val TETHERING_WIFI = 0
            val mainHandler = Handler(Looper.getMainLooper())
            val executor = Executor { r -> mainHandler.post(r) }

            val args = arrayOfNulls<Any>(paramTypes.size)
            for (i in paramTypes.indices) {
                when {
                    paramTypes[i] == Int::class.javaPrimitiveType || paramTypes[i] == java.lang.Integer::class.java -> args[i] = TETHERING_WIFI
                    paramTypes[i] == Boolean::class.javaPrimitiveType || paramTypes[i] == java.lang.Boolean::class.java -> args[i] = true
                    paramTypes[i] == Handler::class.java -> args[i] = mainHandler
                    paramTypes[i] == Executor::class.java -> args[i] = executor
                    callbackClass != null && paramTypes[i].isAssignableFrom(callbackClass) -> args[i] = callbackInstance
                    else -> args[i] = null
                }
            }

            startTetheringMethod.invoke(connectivityManager, *args)
            LogManager.log(
                "NORMAL_HOTSPOT",
                LogStatus.INFO,
                "Dispatched normal Wi-Fi tethering start request to ConnectivityManager"
            )
            true
        } catch (e: InvocationTargetException) {
            val target = e.targetException ?: e
            if (target is SecurityException) {
                LogManager.log(
                    "NORMAL_HOTSPOT",
                    LogStatus.UNAVAILABLE,
                    "Security restriction: Programmatic mobile hotspot requires TETHER_PRIVILEGED system permission on Android ${Build.VERSION.SDK_INT} (${target.message})"
                )
            } else {
                LogManager.log(
                    "NORMAL_HOTSPOT",
                    LogStatus.FAILED,
                    "Failed to start mobile hotspot via ConnectivityManager: ${target.message}"
                )
            }
            true
        } catch (e: SecurityException) {
            LogManager.log(
                "NORMAL_HOTSPOT",
                LogStatus.UNAVAILABLE,
                "Security restriction: Programmatic mobile hotspot requires TETHER_PRIVILEGED system permission on Android ${Build.VERSION.SDK_INT} (${e.message})"
            )
            true
        } catch (e: Exception) {
            LogManager.log(
                "NORMAL_HOTSPOT",
                LogStatus.FAILED,
                "Error invoking ConnectivityManager.startTethering: ${e.message}"
            )
            false
        }
    }

    private fun trySetWifiApEnabled(wifiManager: WifiManager): Boolean {
        return try {
            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                Class.forName("android.net.wifi.WifiConfiguration"),
                Boolean::class.javaPrimitiveType
            )
            method.isAccessible = true
            val result = method.invoke(wifiManager, null, true) as? Boolean
            if (result == true) {
                LogManager.log(
                    "NORMAL_HOTSPOT",
                    LogStatus.SUCCESS,
                    "Normal mobile Wi-Fi hotspot enabled successfully via WifiManager.setWifiApEnabled"
                )
                true
            } else {
                LogManager.log(
                    "NORMAL_HOTSPOT",
                    LogStatus.FAILED,
                    "WifiManager.setWifiApEnabled returned false"
                )
                true
            }
        } catch (e: InvocationTargetException) {
            val target = e.targetException ?: e
            if (target is SecurityException) {
                LogManager.log(
                    "NORMAL_HOTSPOT",
                    LogStatus.UNAVAILABLE,
                    "Security restriction: setWifiApEnabled requires system privileges (${target.message})"
                )
            } else {
                LogManager.log(
                    "NORMAL_HOTSPOT",
                    LogStatus.FAILED,
                    "setWifiApEnabled failed: ${target.message}"
                )
            }
            true
        } catch (e: NoSuchMethodException) {
            false
        } catch (e: Exception) {
            LogManager.log(
                "NORMAL_HOTSPOT",
                LogStatus.FAILED,
                "Error calling setWifiApEnabled: ${e.message}"
            )
            false
        }
    }
}
