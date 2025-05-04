package com.nonstac.airportguide.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionsHandler {

    val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    val audioPermission = arrayOf(
        Manifest.permission.RECORD_AUDIO
    )

    fun hasLocationPermissions(context: Context): Boolean {
        return locationPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun requestLocationPermissions(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            locationPermissions,
            Constants.LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    fun requestAudioPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            audioPermission,
            Constants.AUDIO_PERMISSION_REQUEST_CODE
        )
    }
}