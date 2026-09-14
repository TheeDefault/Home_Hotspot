package com.homehotspot

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.CopyOnWriteArrayList

enum class MonitoringState {
    NOT_MONITORING,
    STARTING,
    MONITORING,
    STOPPING,
    ERROR
}

object MonitoringManager {
    private const val PREFS_NAME = "home_hotspot_prefs"
    private const val KEY_HOME_LAT = "home_latitude"
    private const val KEY_HOME_LNG = "home_longitude"
    private const val KEY_HOME_ACC = "home_accuracy"
    private const val KEY_RADIUS = "geofence_radius"
    private const val KEY_IS_MONITORING = "is_monitoring_active"

    const val HOME_GEOFENCE_ID = "HOME_GEOFENCE_ID"
    private const val GEOFENCE_PENDING_INTENT_REQUEST_CODE = 2001
    private const val DEFAULT_RADIUS_METERS = 30

    private val mainHandler = Handler(Looper.getMainLooper())
    private var delayedStartRunnable: Runnable? = null
    private val stateListeners = CopyOnWriteArrayList<(MonitoringState) -> Unit>()
    private val homeLocationListeners = CopyOnWriteArrayList<(Location?) -> Unit>()

    private var currentState = MonitoringState.NOT_MONITORING
    private var appContext: Context? = null
    private var geofencingClient: GeofencingClient? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        geofencingClient = LocationServices.getGeofencingClient(app)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(app)

        // Restore state from persistence
        val prefs = getPrefs()
        val wasMonitoring = prefs.getBoolean(KEY_IS_MONITORING, false)
        if (wasMonitoring && isHomeConfigured()) {
            reconcileMonitoring(app)
        } else {
            updateState(MonitoringState.NOT_MONITORING)
        }
    }

    private fun getPrefs(): SharedPreferences {
        val ctx = appContext ?: throw IllegalStateException("MonitoringManager not initialized")
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCurrentState(): MonitoringState = currentState

    private fun updateState(newState: MonitoringState) {
        currentState = newState
        mainHandler.post {
            for (listener in stateListeners) {
                try {
                    listener(newState)
                } catch (e: Exception) {
                    // Safe callback invocation
                }
            }
        }
    }

    fun addStateListener(listener: (MonitoringState) -> Unit) {
        stateListeners.add(listener)
        mainHandler.post { listener(currentState) }
    }

    fun removeStateListener(listener: (MonitoringState) -> Unit) {
        stateListeners.remove(listener)
    }

    fun addHomeLocationListener(listener: (Location?) -> Unit) {
        homeLocationListeners.add(listener)
        mainHandler.post { listener(getSavedHomeLocation()) }
    }

    fun removeHomeLocationListener(listener: (Location?) -> Unit) {
        homeLocationListeners.remove(listener)
    }

    private fun notifyHomeLocationChanged(loc: Location?) {
        mainHandler.post {
            for (listener in homeLocationListeners) {
                try {
                    listener(loc)
                } catch (e: Exception) {
                    // Safe callback invocation
                }
            }
        }
    }

    // --- HOME Location Management ---

    fun isHomeConfigured(): Boolean {
        val prefs = getPrefs()
        return prefs.contains(KEY_HOME_LAT) && prefs.contains(KEY_HOME_LNG)
    }

    fun getSavedHomeLocation(): Location? {
        val prefs = getPrefs()
        if (!prefs.contains(KEY_HOME_LAT) || !prefs.contains(KEY_HOME_LNG)) {
            return null
        }
        val lat = prefs.getFloat(KEY_HOME_LAT, 0f).toDouble()
        val lng = prefs.getFloat(KEY_HOME_LNG, 0f).toDouble()
        val acc = prefs.getFloat(KEY_HOME_ACC, 0f)

        return Location("HOME_PROVIDER").apply {
            latitude = lat
            longitude = lng
            accuracy = acc
        }
    }

    private fun persistHomeLocation(lat: Double, lng: Double, accuracy: Float) {
        val prefs = getPrefs()
        prefs.edit()
            .putFloat(KEY_HOME_LAT, lat.toFloat())
            .putFloat(KEY_HOME_LNG, lng.toFloat())
            .putFloat(KEY_HOME_ACC, accuracy)
            .apply()

        val loc = Location("HOME_PROVIDER").apply {
            latitude = lat
            longitude = lng
            this.accuracy = accuracy
        }
        notifyHomeLocationChanged(loc)
    }

    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation(context: Context, onResult: (Boolean, Location?, String) -> Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val msg = "Location permission not granted"
            LogManager.log("FETCH_LOCATION", LogStatus.FAILED, msg)
            onResult(false, null, msg)
            return
        }

        val client = fusedLocationClient ?: LocationServices.getFusedLocationProviderClient(context)
        LogManager.log("FETCH_LOCATION", LogStatus.INFO, "Requesting high-accuracy current location...")

        val cts = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    persistHomeLocation(location.latitude, location.longitude, location.accuracy)
                    val details = "HOME saved at (${location.latitude}, ${location.longitude}) [acc: ${location.accuracy}m]"
                    LogManager.log("HOME_SAVED", LogStatus.SUCCESS, details)

                    // If currently monitoring, re-register geofence with new coordinates
                    if (currentState == MonitoringState.MONITORING) {
                        LogManager.log("GEOFENCE_RECONCILE", LogStatus.INFO, "HOME updated while monitoring. Re-registering geofence...")
                        startMonitoringImmediately(context)
                    }

                    onResult(true, location, details)
                } else {
                    // Fallback to getLastLocation if fresh location was null
                    client.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                        if (lastLoc != null) {
                            persistHomeLocation(lastLoc.latitude, lastLoc.longitude, lastLoc.accuracy)
                            val details = "HOME saved from last known location at (${lastLoc.latitude}, ${lastLoc.longitude}) [acc: ${lastLoc.accuracy}m]"
                            LogManager.log("HOME_SAVED", LogStatus.SUCCESS, details)

                            if (currentState == MonitoringState.MONITORING) {
                                startMonitoringImmediately(context)
                            }
                            onResult(true, lastLoc, details)
                        } else {
                            val msg = "Location hardware returned null (ensure GPS is enabled)"
                            LogManager.log("FETCH_LOCATION", LogStatus.FAILED, msg)
                            onResult(false, null, msg)
                        }
                    }.addOnFailureListener { e ->
                        val msg = "Failed to fetch location: ${e.message}"
                        LogManager.log("FETCH_LOCATION", LogStatus.FAILED, msg)
                        onResult(false, null, msg)
                    }
                }
            }
            .addOnFailureListener { e ->
                val msg = "Failed to fetch current location: ${e.message}"
                LogManager.log("FETCH_LOCATION", LogStatus.FAILED, msg)
                onResult(false, null, msg)
            }
    }

    // --- Radius Management ---

    fun getSavedRadius(): Int {
        return getPrefs().getInt(KEY_RADIUS, DEFAULT_RADIUS_METERS).coerceIn(0, 100)
    }

    fun setRadius(context: Context, radiusMeters: Int) {
        val clamped = radiusMeters.coerceIn(0, 100)
        getPrefs().edit().putInt(KEY_RADIUS, clamped).apply()
        LogManager.log("RADIUS_CHANGED", LogStatus.INFO, "Geofence radius set to $clamped m")

        // If currently monitoring, re-register with new radius
        if (currentState == MonitoringState.MONITORING) {
            LogManager.log("GEOFENCE_RECONCILE", LogStatus.INFO, "Radius updated while monitoring. Re-registering geofence...")
            startMonitoringImmediately(context)
        }
    }

    // --- Geofence PendingIntent ---

    private fun getGeofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            context,
            GEOFENCE_PENDING_INTENT_REQUEST_CODE,
            intent,
            flags
        )
    }

    // --- Monitoring Operations ---

    fun cancelDelayedStart() {
        delayedStartRunnable?.let {
            mainHandler.removeCallbacks(it)
            delayedStartRunnable = null
            LogManager.log("DELAYED_START", LogStatus.INFO, "Cancelled pending 10-second delayed monitoring")
        }
    }

    fun startMonitoringIn10Seconds(context: Context) {
        if (!isHomeConfigured()) {
            val msg = "Cannot monitor: HOME location is not configured"
            LogManager.log("START_MONITORING", LogStatus.FAILED, msg)
            updateState(MonitoringState.ERROR)
            return
        }

        cancelDelayedStart()
        updateState(MonitoringState.STARTING)

        LogManager.log("START_MONITORING", LogStatus.INFO, "Monitoring will start in 10 seconds...")
        NotificationHelper.showNotification(
            context,
            "Monitoring Scheduled",
            "Monitoring is starting in 10 seconds...",
            isEvent = false
        )

        val runnable = Runnable {
            delayedStartRunnable = null
            LogManager.log("START_MONITORING", LogStatus.INFO, "10-second delay completed. Initiating geofence registration...")
            startMonitoringImmediately(context)
        }
        delayedStartRunnable = runnable
        mainHandler.postDelayed(runnable, 10_000L)
    }

    @SuppressLint("MissingPermission")
    fun startMonitoringImmediately(context: Context) {
        cancelDelayedStart()

        val homeLoc = getSavedHomeLocation()
        if (homeLoc == null) {
            val msg = "Cannot start monitoring: HOME location is not set"
            LogManager.log("START_MONITORING", LogStatus.FAILED, msg)
            updateState(MonitoringState.ERROR)
            return
        }

        // Validate foreground location permission
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val msg = "ACCESS_FINE_LOCATION permission is missing"
            LogManager.log("START_MONITORING", LogStatus.FAILED, msg)
            updateState(MonitoringState.ERROR)
            return
        }

        // Check background location permission where required
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
            if (bgPermission != PackageManager.PERMISSION_GRANTED) {
                LogManager.log(
                    "BACKGROUND_PERMISSION_WARNING",
                    LogStatus.INFO,
                    "ACCESS_BACKGROUND_LOCATION not granted. Monitoring may be limited when app is closed."
                )
            }
        }

        updateState(MonitoringState.STARTING)
        val radius = getSavedRadius().coerceAtLeast(1) // Ensure at least 1m for geofence API
        val pendingIntent = getGeofencePendingIntent(context)
        val client = geofencingClient ?: LocationServices.getGeofencingClient(context)

        val geofence = Geofence.Builder()
            .setRequestId(HOME_GEOFENCE_ID)
            .setCircularRegion(homeLoc.latitude, homeLoc.longitude, radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        // Remove any existing geofence first to prevent duplicates
        client.removeGeofences(pendingIntent).addOnCompleteListener {
            client.addGeofences(geofencingRequest, pendingIntent)
                .addOnSuccessListener {
                    getPrefs().edit().putBoolean(KEY_IS_MONITORING, true).apply()
                    updateState(MonitoringState.MONITORING)
                    val details = "Geofence registered successfully (ID: $HOME_GEOFENCE_ID, HOME: ${homeLoc.latitude}, ${homeLoc.longitude}, Radius: ${radius}m)"
                    LogManager.log("GEOFENCE_REGISTRATION", LogStatus.SUCCESS, details)
                    NotificationHelper.showNotification(
                        context,
                        "Monitoring Active",
                        "Geofence registered at ($homeLoc.latitude, $homeLoc.longitude), radius ${radius}m",
                        isEvent = false
                    )
                }
                .addOnFailureListener { e ->
                    getPrefs().edit().putBoolean(KEY_IS_MONITORING, false).apply()
                    updateState(MonitoringState.ERROR)
                    val details = "Geofence registration failed: ${e.message}"
                    LogManager.log("GEOFENCE_REGISTRATION", LogStatus.FAILED, details)
                    NotificationHelper.showNotification(
                        context,
                        "Monitoring Error",
                        details,
                        isEvent = false
                    )
                }
        }
    }

    fun stopMonitoring(context: Context) {
        cancelDelayedStart()

        if (currentState == MonitoringState.NOT_MONITORING && !getPrefs().getBoolean(KEY_IS_MONITORING, false)) {
            LogManager.log("STOP_MONITORING", LogStatus.INFO, "Already not monitoring")
            return
        }

        updateState(MonitoringState.STOPPING)
        val pendingIntent = getGeofencePendingIntent(context)
        val client = geofencingClient ?: LocationServices.getGeofencingClient(context)

        client.removeGeofences(pendingIntent)
            .addOnCompleteListener {
                getPrefs().edit().putBoolean(KEY_IS_MONITORING, false).apply()
                updateState(MonitoringState.NOT_MONITORING)
                LogManager.log("STOP_MONITORING", LogStatus.SUCCESS, "Monitoring stopped and geofence removed")
                NotificationHelper.showNotification(
                    context,
                    "Monitoring Stopped",
                    "Geofence monitoring has been stopped",
                    isEvent = false
                )
            }
    }

    fun reconcileMonitoring(context: Context) {
        val wasMonitoring = getPrefs().getBoolean(KEY_IS_MONITORING, false)
        if (wasMonitoring && isHomeConfigured()) {
            LogManager.log("RECONCILE", LogStatus.INFO, "Reconciling active monitoring state after process recreation / reboot...")
            startMonitoringImmediately(context)
        }
    }
}
