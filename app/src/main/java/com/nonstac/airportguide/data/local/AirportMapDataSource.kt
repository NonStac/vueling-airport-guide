package com.nonstac.airportguide.data.local

import android.content.Context
import android.util.Log // Add Log
import com.nonstac.airportguide.R // Import R class
import com.nonstac.airportguide.data.model.AirportMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException // Import specific exception

class AirportMapDataSource(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val TAG = "AirportMapDataSource"

    suspend fun loadMap(mapId: String): Result<AirportMap> = withContext(Dispatchers.IO) {
        try {
            // Determine which raw resource file to load based on mapId
            val resourceId = when (mapId.uppercase()) {
                "BCN_T1" -> R.raw.bcn_t1_map // Use the renamed file
                "JFK_T4" -> R.raw.jfk_t4_map // Use the new file
                else -> {
                    Log.e(TAG, "Unknown mapId requested: $mapId")
                    // Return failure if mapId is not recognized
                    return@withContext Result.failure(FileNotFoundException("Map data not found for ID: $mapId"))
                }
            }
            Log.d(TAG, "Loading map resource for ID: $mapId (ResourceId: $resourceId)")

            val inputStream = context.resources.openRawResource(resourceId)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val map = json.decodeFromString<AirportMap>(jsonString)
            Log.d(TAG, "Successfully parsed map: ${map.airportName}")
            Result.success(map)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading/parsing map for ID: $mapId", e)
            Result.failure(e)
        }
    }
}