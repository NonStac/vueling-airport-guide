package com.nonstac.airportguide.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log // Added for logging
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
// Removed kotlinx.coroutines.launch as it's not directly used here

class LocationService(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _lastKnownLocation = MutableStateFlow<Location?>(null)
    val lastKnownLocation: StateFlow<Location?> = _lastKnownLocation.asStateFlow()

    // Store the active location callback to allow explicit removal
    private var locationCallback: LocationCallback? = null
    private val TAG = "LocationService_Airport" // Added TAG

    @SuppressLint("MissingPermission")
    fun requestLocationUpdates(): Flow<Location> = callbackFlow {
        Log.d(TAG, "Requesting location updates...")
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted.")
            close(SecurityException("Missing location permission"))
            return@callbackFlow
        }

        // Check if updates are already active with a callback
        if (locationCallback != null) {
            Log.w(TAG, "Location updates already requested.")
            // You might choose to close the flow immediately or let it continue if idempotent behaviour is desired.
            // close(IllegalStateException("Location updates already requested."))
            // For now, let's allow multiple flows but only one active callback registration.
            // If a flow is collected again, it will replace the callback.
            // fusedLocationClient.removeLocationUpdates(locationCallback!!) // Remove previous before starting new? Consider implications.
        }


        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000) // 5 seconds interval
            .setMinUpdateIntervalMillis(2000) // Minimum 2 seconds
            .build()


        // Create and store the callback instance
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.v(TAG, "New location received: ${location.latitude}, ${location.longitude}")
                    _lastKnownLocation.value = location // Update StateFlow
                    trySend(location).isSuccess // Emit to the callbackFlow
                }
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                // Optional: Handle changes in location availability (e.g., GPS signal lost/found)
                Log.d(TAG, "Location Availability: ${locationAvailability.isLocationAvailable}")
            }
        }

        // Request updates using the stored callback
        Log.d(TAG, "Registering location callback with FusedLocationProviderClient.")
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!, // Use the stored callback (non-null here)
            Looper.getMainLooper() // Use main looper for callbacks
        ).addOnFailureListener { e ->
            Log.e(TAG, "Failed to request location updates", e)
            close(e) // Close the flow on failure to register listener
        }

        // This block is executed when the Flow collector cancels the collection (e.g., scope cancellation)
        awaitClose {
            Log.d(TAG, "Flow collection stopped. Removing location updates callback.")
            // Remove the stored callback when the flow is closed/cancelled
            locationCallback?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d(TAG, "Location updates successfully removed via awaitClose.")
                        } else {
                            Log.w(TAG, "Failed to remove location updates via awaitClose.", task.exception)
                        }
                    }
            }
            // Clear the stored reference as it's no longer active via this flow instance
            locationCallback = null
        }
    }

    // --- NEW FUNCTION ---
    /**
     * Explicitly stops location updates requested via [requestLocationUpdates].
     * This is useful if updates need to be stopped outside the lifecycle
     * of the flow collection (e.g., user action, specific condition).
     */
    fun stopLocationUpdates() {
        Log.d(TAG, "Explicit request to stop location updates.")
        // Check if a callback is currently registered and stored
        locationCallback?.let { callback ->
            Log.d(TAG, "Found active callback, removing updates...")
            val task = fusedLocationClient.removeLocationUpdates(callback)
            task.addOnCompleteListener { removeTask ->
                if (removeTask.isSuccessful) {
                    Log.d(TAG, "Location updates successfully removed via stopLocationUpdates().")
                } else {
                    Log.w(TAG, "Failed to remove location updates via stopLocationUpdates().", removeTask.exception)
                }
            }
            // Clear the stored reference after attempting removal
            locationCallback = null
        } ?: run {
            Log.d(TAG, "No active location callback found to remove.")
        }
    }
    // --- END OF NEW FUNCTION ---


    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocationDirect(): Location? {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Cannot get last known location, permission missing.")
            return null
        }
        return try {
            Log.d(TAG, "Requesting last known location directly.")
            val location = fusedLocationClient.lastLocation.await()
            if(location != null) {
                Log.d(TAG, "Last known location retrieved: ${location.latitude}, ${location.longitude}")
                _lastKnownLocation.value = location // Update StateFlow as well
            } else {
                Log.d(TAG, "Last known location is null.")
            }
            location
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last known location", e)
            null
        }
    }


    fun hasLocationPermission(): Boolean {
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        // Log.v(TAG, "ACCESS_FINE_LOCATION Permission Granted: $permissionGranted") // Verbose logging if needed
        return permissionGranted
    }
}