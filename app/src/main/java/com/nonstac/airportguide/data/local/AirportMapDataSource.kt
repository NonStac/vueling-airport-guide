package com.nonstac.airportguide.data.local

import android.content.Context
import com.nonstac.airportguide.R
import com.nonstac.airportguide.data.model.AirportMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AirportMapDataSource(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadMap(): Result<AirportMap> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.resources.openRawResource(R.raw.airport_map)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val map = json.decodeFromString<AirportMap>(jsonString)
            Result.success(map)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}