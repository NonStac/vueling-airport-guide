package com.nonstac.airportguide.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// --- Permissions Required ---
// Manifest: BLUETOOTH, BLUETOOTH_ADMIN (<=30), BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, ACCESS_FINE_LOCATION
// Runtime: BLUETOOTH_CONNECT, BLUETOOTH_SCAN (>=31), ACCESS_FINE_LOCATION (<31 for discovery)

class BluetoothChatService(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow(ConnectionState.NONE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedMessage = MutableSharedFlow<String>()
    val receivedMessage: SharedFlow<String> = _receivedMessage.asSharedFlow()

    private val _discoveredDevices = MutableStateFlow<Set<BluetoothDeviceMinimal>>(emptySet())
    val discoveredDevices: StateFlow<Set<BluetoothDeviceMinimal>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    // Jobs for different operations
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var connectedReadJob: Job? = null
    private var discoveryJob: Job? = null // To manage discovery timeout/cancellation

    private var connectedSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var serverSocket: BluetoothServerSocket? = null // Keep reference to close it

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "BluetoothChatService"


    // --- BroadcastReceiver for Discovery ---
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission") // Permissions checked before starting discovery
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    // Add device to list if it has a name (common for phones) and isn't already there
                    if (device != null && device.name != null && hasConnectPermission()) { // Check CONNECT needed for getName/getAddress sometimes
                        Log.d(TAG, "Device Found: ${device.name} - ${device.address}")
                        val minimalDevice = BluetoothDeviceMinimal(device.name, device.address)
                        _discoveredDevices.update { it + minimalDevice }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Discovery Finished.")
                    _isDiscovering.value = false
                    // Optionally stop discovery job if needed, though it might finish naturally
                }
            }
        }
    }

    // --- Public API ---

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!checkAdapterAndPermissions("Start Discovery")) return
        if (_isDiscovering.value) {
            Log.w(TAG, "Discovery already active.")
            return
        }
        stopEverything() // Ensure clean state before starting discovery

        Log.d(TAG, "Starting discovery...")
        _discoveredDevices.value = emptySet() // Clear previous results
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        context.registerReceiver(discoveryReceiver, filter)
        _isDiscovering.value = true

        // Start discovery (might return false if error)
        val discoveryStarted = bluetoothAdapter?.startDiscovery() ?: false
        if (!discoveryStarted) {
            Log.e(TAG, "bluetoothAdapter.startDiscovery() returned false.")
            stopDiscovery() // Clean up receiver if start failed
            _connectionState.value = ConnectionState.ERROR
        } else {
            // Optional: Timeout for discovery
            discoveryJob = serviceScope.launch {
                delay(15000) // Discover for 15 seconds
                if (_isDiscovering.value) {
                    Log.d(TAG, "Discovery timeout reached.")
                    stopDiscovery()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        if (!_isDiscovering.value) return
        discoveryJob?.cancel() // Cancel timeout job if running
        discoveryJob = null
        if (bluetoothAdapter?.isDiscovering == true) {
            Log.d(TAG, "Stopping discovery...")
            bluetoothAdapter.cancelDiscovery()
        }
        try {
            context.unregisterReceiver(discoveryReceiver)
            Log.d(TAG, "Discovery receiver unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Discovery receiver already unregistered?")
        }
        _isDiscovering.value = false
    }

    @SuppressLint("MissingPermission")
    fun startListener() {
        if (!checkAdapterAndPermissions("Start Listener")) return
        if (serverJob?.isActive == true || _connectionState.value == ConnectionState.CONNECTED) {
            Log.w(TAG, "Listener already running or already connected.")
            return
        }
        stopEverything() // Ensure clean state

        Log.d(TAG, "Starting listener...")
        _connectionState.value = ConnectionState.LISTEN

        // Optional: Make device discoverable while listening
        // This requires BLUETOOTH_ADVERTISE permission on API 31+
        // You'd launch an Intent like this:
        // val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        // discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300) // 5 minutes
        // context.startActivity(discoverableIntent) // Needs Activity context or special handling

        serverJob = serviceScope.launch {
            var tempSocket: BluetoothSocket? = null
            try {
                // Close previous server socket if it exists
                closeServerSocket()
                // Create new listening socket
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(
                    BluetoothConstants.SERVICE_NAME, BluetoothConstants.SERVICE_UUID
                ) ?: throw IOException("Failed to create server socket")

                Log.i(TAG, "Server socket listening...")
                // Accept() is blocking - waits for connection or throws exception
                tempSocket = serverSocket?.accept() // Assign to temp var

                // If we get here, a connection was accepted
                tempSocket?.let {
                    Log.i(TAG, "Connection accepted from ${it.remoteDevice.address}")
                    // Important: Close the listening socket once a connection is accepted
                    // to avoid accepting more connections (unless designed for multiple)
                    closeServerSocket()
                    // Handle the connection in a separate function/job
                    handleConnection(it)
                } ?: if(isActive) throw IOException("Accept returned null or was closed prematurely") else TODO()

            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "Server socket error: ${e.message}")
                    _connectionState.value = ConnectionState.ERROR
                    closeServerSocket() // Ensure closed on error
                    closeClientSocket(tempSocket) // Close client socket if partially accepted
                } else {
                    Log.d(TAG,"Server job cancelled.")
                }
            }
        }
    }


    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String) {
        if (!checkAdapterAndPermissions("Connect")) return
        if (clientJob?.isActive == true || _connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) {
            Log.w(TAG, "Connect job already active or already connected.")
            return
        }
        stopEverything() // Ensure clean state

        Log.d(TAG, "Attempting to connect to $deviceAddress")
        _connectionState.value = ConnectionState.CONNECTING

        clientJob = serviceScope.launch {
            var socket: BluetoothSocket? = null
            try {
                // Always cancel discovery before connecting
                if (bluetoothAdapter?.isDiscovering == true) {
                    stopDiscovery() // Ensures receiver is unregistered too
                    delay(500) // Short delay after cancelling discovery
                }

                val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(deviceAddress)
                    ?: throw IOException("Failed to get remote device $deviceAddress")

                Log.d(TAG, "Creating RFCOMM socket...")
                socket = device.createRfcommSocketToServiceRecord(BluetoothConstants.SERVICE_UUID)

                Log.d(TAG, "Connecting socket...")
                socket.connect() // Blocking call - requires BLUETOOTH_CONNECT

                // If connect() succeeds without exception, connection is established
                Log.i(TAG, "Connection successful to ${device.address}")
                handleConnection(socket) // Handle the established connection

            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "Client connection failed: ${e.message}")
                    _connectionState.value = ConnectionState.ERROR
                    closeClientSocket(socket) // Attempt to close socket on error
                } else {
                    Log.d(TAG,"Client connect job cancelled.")
                }
            } catch (se: SecurityException) {
                // Catch potential permission issues here too
                if (isActive) {
                    Log.e(TAG, "Client connection security error: ${se.message}")
                    _connectionState.value = ConnectionState.ERROR
                    closeClientSocket(socket)
                }
            }
        }
    }


    private fun handleConnection(socket: BluetoothSocket) {
        Log.d(TAG, "Handling established connection to ${socket.remoteDevice.address}")
        stopEverythingExceptConnectedJob() // Stop discovery, server, client jobs
        connectedSocket = socket
        _connectionState.value = ConnectionState.CONNECTED

        try {
            inputStream = socket.inputStream
            outputStream = socket.outputStream
        } catch (e: IOException) {
            Log.e(TAG, "Failed to get streams", e); _connectionState.value = ConnectionState.ERROR; closeConnection(); return
        }

        connectedReadJob = serviceScope.launch {
            Log.i(TAG, "Starting read loop.")
            val buffer = ByteArray(1024)
            var numBytes: Int

            while (isActive) { // Loop while coroutine is active
                try {
                    numBytes = inputStream?.read(buffer) ?: -1
                    if (numBytes > 0) {
                        val readMessage = String(buffer, 0, numBytes)
                        Log.d(TAG, "Read: $readMessage")
                        _receivedMessage.emit(readMessage)
                    } else if (numBytes == -1 && isActive) {
                        Log.i(TAG, "Input stream closed by remote device.")
                        _connectionState.value = ConnectionState.DISCONNECTED; break
                    }
                } catch (e: IOException) {
                    if(isActive) { Log.e(TAG, "Read loop error: ${e.message}"); _connectionState.value = ConnectionState.ERROR }
                    else { Log.d(TAG,"Read loop cancelled") }
                    break // Exit loop on error or cancellation
                }
            }
            Log.d(TAG, "Read loop finished. State: ${_connectionState.value}")
            // Ensure connection is marked closed if loop finishes naturally while connected
            if (_connectionState.value == ConnectionState.CONNECTED) _connectionState.value = ConnectionState.DISCONNECTED
            closeConnection() // Clean up
        }
    }

    fun write(message: String): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED || outputStream == null) {
            Log.e(TAG, "Write failed: Not connected or output stream null.")
            return false
        }
        val bytes = message.toByteArray()
        return try {
            Log.d(TAG, "Writing: $message")
            outputStream?.write(bytes)
            outputStream?.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Write error", e)
            _connectionState.value = ConnectionState.ERROR
            closeConnection()
            false
        }
    }

    // Call this when chat screen closes or app exits
    fun stopService() {
        Log.d(TAG, "stopService called.")
        stopEverything()
        serviceScope.cancel() // Cancel all coroutines in the scope
        _connectionState.value = ConnectionState.NONE
    }

    // Stops all background Bluetooth activity
    private fun stopEverything() {
        Log.d(TAG, "Stopping all BT operations (Discovery, Server, Client, Connected)")
        stopDiscovery() // Stops discovery and unregisters receiver
        serverJob?.cancel(); serverJob = null
        clientJob?.cancel(); clientJob = null
        connectedReadJob?.cancel(); connectedReadJob = null
        closeServerSocket()
        closeConnection() // Closes client socket and streams
    }

    // Stops everything EXCEPT an active connection read loop
    private fun stopEverythingExceptConnectedJob() {
        Log.d(TAG, "Stopping Discovery, Server, Client jobs")
        stopDiscovery()
        serverJob?.cancel(); serverJob = null
        clientJob?.cancel(); clientJob = null
        closeServerSocket()
    }

    private fun closeServerSocket() {
        try { serverSocket?.close() } catch (e: IOException) { Log.e(TAG, "Failed to close server socket", e) }
        serverSocket = null
    }

    private fun closeClientSocket(socket: BluetoothSocket?) {
        try { socket?.close() } catch (e: IOException) { Log.e(TAG, "Failed to close client socket", e) }
    }

    private fun closeConnection() {
        Log.d(TAG, "Closing active connection resources.")
        try { inputStream?.close() } catch (e: Exception) { Log.e(TAG, "Input stream close error", e) }
        try { outputStream?.close() } catch (e: Exception) { Log.e(TAG, "Output stream close error", e) }
        try { connectedSocket?.close() } catch (e: Exception) { Log.e(TAG, "Connected socket close error", e) }
        inputStream = null
        outputStream = null
        connectedSocket = null
    }

    // --- Permission and Address Helpers ---
    @SuppressLint("MissingPermission")
    private fun getMyBluetoothAddress(): String? = try { bluetoothAdapter?.address } catch (e: SecurityException) { null }
    private fun hasPermission(permission: String): Boolean = ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    private fun hasConnectPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasPermission(Manifest.permission.BLUETOOTH_CONNECT) else (hasPermission(Manifest.permission.BLUETOOTH) && hasPermission(Manifest.permission.BLUETOOTH_ADMIN))
    private fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasPermission(Manifest.permission.BLUETOOTH_SCAN) else hasLocationPermission() // Scan needs Location pre-S
    private fun hasLocationPermission(): Boolean = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun checkAdapterAndPermissions(operation: String): Boolean {
        if (bluetoothAdapter == null) { Log.e(TAG, "$operation failed: Bluetooth not supported."); _connectionState.value = ConnectionState.ERROR; return false }
        if (!bluetoothAdapter.isEnabled) { Log.e(TAG, "$operation failed: Bluetooth not enabled."); _connectionState.value = ConnectionState.ERROR; return false }
        if (!hasConnectPermission()) { Log.e(TAG, "$operation failed: Missing BLUETOOTH_CONNECT permission."); _connectionState.value = ConnectionState.ERROR; return false }
        if (operation == "Start Discovery" || operation == "Connect") { // Scan/Location needed for these
            if (!hasScanPermission()) {
                Log.e(TAG, "$operation failed: Missing BLUETOOTH_SCAN (or Location pre-S) permission.")
                _connectionState.value = ConnectionState.ERROR
                return false
            }
        }
        return true
    }
}