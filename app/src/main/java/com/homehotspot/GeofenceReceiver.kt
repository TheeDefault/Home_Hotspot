package com.homehotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) {
            LogManager.log("GEOFENCE_EVENT", LogStatus.FAILED, "Received null intent in GeofenceReceiver")
            return
        }

        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            LogManager.log("GEOFENCE_EVENT", LogStatus.FAILED, "Could not extract GeofencingEvent from intent")
            return
        }

        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            LogManager.log(
                "GEOFENCE_ERROR",
                LogStatus.FAILED,
                "Geofencing error code: ${geofencingEvent.errorCode} ($errorMessage)"
            )
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: emptyList()
        val geofenceIds = triggeringGeofences.joinToString { it.requestId }
        val location = geofencingEvent.triggeringLocation
        val locDesc = if (location != null) "at (${location.latitude}, ${location.longitude}) [acc: ${location.accuracy}m]" else "unknown location"

        when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                LogManager.log(
                    "GEOFENCE_ENTER",
                    LogStatus.SUCCESS,
                    "ENTER transition triggered for [$geofenceIds] $locDesc"
                )

                ActionSequenceService.startActionSequence(context)
            }

            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                LogManager.log(
                    "GEOFENCE_EXIT",
                    LogStatus.INFO,
                    "EXIT transition ignored for [$geofenceIds] $locDesc"
                )
            }

            Geofence.GEOFENCE_TRANSITION_DWELL -> {
                LogManager.log(
                    "GEOFENCE_DWELL",
                    LogStatus.INFO,
                    "DWELL transition for [$geofenceIds] $locDesc"
                )
            }

            else -> {
                LogManager.log(
                    "GEOFENCE_UNKNOWN",
                    LogStatus.INFO,
                    "Unknown transition type $transition for [$geofenceIds]"
                )
            }
        }
    }
}
