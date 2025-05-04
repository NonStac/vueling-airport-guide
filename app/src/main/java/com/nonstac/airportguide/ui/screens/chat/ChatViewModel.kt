package com.nonstac.airportguide.ui.screens.chat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice // Import needed if using BluetoothDevice directly
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nonstac.airportguide.data.model.ChatMessage
import com.nonstac.airportguide.service.BluetoothChatService
import com.nonstac.airportguide.service.BluetoothDeviceMinimal
import com.nonstac.airportguide.service.ConnectionState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.NONE,
    val currentMessageInput: String = "",
    val partnerDeviceName: String? = null, // Store name when connected if available
    val isDiscovering: Boolean = false,
    val discoveredDevices: List<BluetoothDeviceMinimal> = emptyList(),
    val error: String? = null
)

@SuppressLint("MissingPermission") // Permissions handled before calling service methods
class ChatViewModel(
    private val context: Context,
    private val ticketId: String
) : ViewModel() {

    private val bluetoothService = BluetoothChatService(context)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val TAG = "ChatViewModel"

    init {
        Log.d(TAG, "Initializing for ticket: $ticketId")
        collectServiceUpdates()
    }

    private fun collectServiceUpdates() {
        // Collect connection state updates
        viewModelScope.launch {
            bluetoothService.connectionState.collect { state ->
                Log.d(TAG, "Connection state changed: $state")
                val errorMsg = when(state) {
                    ConnectionState.ERROR -> "Connection Error"
                    ConnectionState.DISCONNECTED -> "Disconnected"
                    else -> null
                }
                _uiState.update { it.copy(connectionState = state, error = errorMsg) }
                // Clear discovery list when connected or starting to listen/connect
                if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING || state == ConnectionState.LISTEN) {
                    _uiState.update { it.copy(discoveredDevices = emptyList(), isDiscovering = false)}
                }
                // Clear partner name if not connected
                if (state != ConnectionState.CONNECTED) {
                    _uiState.update { it.copy(partnerDeviceName = null) }
                }
            }
        }

        // Collect received messages
        viewModelScope.launch {
            bluetoothService.receivedMessage.collect { messageText ->
                Log.d(TAG, "Message received: $messageText")
                val receivedMsg = ChatMessage(text = messageText, isSentByUser = false)
                _uiState.update { it.copy(messages = it.messages + receivedMsg) }
            }
        }

        // Collect discovery status
        viewModelScope.launch {
            bluetoothService.isDiscovering.collect { discovering ->
                Log.d(TAG, "Discovery state changed: $discovering")
                _uiState.update { it.copy(isDiscovering = discovering) }
            }
        }

        // Collect discovered devices
        viewModelScope.launch {
            bluetoothService.discoveredDevices.collect { devices ->
                Log.d(TAG, "Discovered devices updated: ${devices.size} found")
                _uiState.update { it.copy(discoveredDevices = devices.toList()) }
            }
        }
    }

    // --- Actions triggered by UI ---

    fun startListening() {
        Log.d(TAG, "Start Listening requested.")
        // Permissions should be checked by UI before calling this
        bluetoothService.startListener()
    }

    fun startDiscovery() {
        Log.d(TAG, "Start Discovery requested.")
        // Permissions should be checked by UI before calling this
        bluetoothService.startDiscovery()
    }

    fun stopDiscovery() {
        Log.d(TAG, "Stop Discovery requested.")
        bluetoothService.stopDiscovery()
    }

    fun connectToDevice(device: BluetoothDeviceMinimal) {
        Log.d(TAG, "Connect to device requested: ${device.name} (${device.address})")
        // Stop discovery before attempting connection
        bluetoothService.stopDiscovery() // Service handles check if actually discovering
        // Permissions should be checked by UI before calling this
        _uiState.update { it.copy(partnerDeviceName = device.name ?: device.address) } // Store potential partner name
        bluetoothService.connect(device.address)
    }


    // --- Message Handling ---
    fun updateMessageInput(text: String) {
        _uiState.update { it.copy(currentMessageInput = text) }
    }

    fun sendMessage() {
        val messageText = _uiState.value.currentMessageInput.trim()
        if (messageText.isEmpty() || _uiState.value.connectionState != ConnectionState.CONNECTED) return

        Log.d(TAG, "Attempting to send: $messageText")
        val success = bluetoothService.write(messageText)

        if (success) {
            Log.d(TAG, "Message sent successfully.")
            val sentMsg = ChatMessage(text = messageText, isSentByUser = true)
            _uiState.update { it.copy(messages = it.messages + sentMsg, currentMessageInput = "") }
        } else {
            Log.e(TAG, "Failed to send message.")
            _uiState.update { it.copy(error = "Failed to send message.") }
        }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared. Stopping Bluetooth service.")
        bluetoothService.stopService()
    }

    companion object {
        fun provideFactory(context: Context, ticketId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        return ChatViewModel(context.applicationContext, ticketId) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
    }
}