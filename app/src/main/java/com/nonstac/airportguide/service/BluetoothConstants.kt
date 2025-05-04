package com.nonstac.airportguide.service

import java.util.*

object BluetoothConstants {
    // No Hardcoded Addresses needed anymore

    // Standard UUID for Serial Port Profile (SPP)
    val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val SERVICE_NAME = "AirportGuideChat" // Used for listening socket
}

// ConnectionState Enum remains the same
enum class ConnectionState {
    NONE, LISTEN, CONNECTING, CONNECTED, ERROR, DISCONNECTED
}

// Simple data class for discovered devices
data class BluetoothDeviceMinimal(
    val name: String?,
    val address: String
) {
    // Override equals/hashCode for use in lists/sets based on address
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BluetoothDeviceMinimal
        return address == other.address
    }
    override fun hashCode(): Int = address.hashCode()
}