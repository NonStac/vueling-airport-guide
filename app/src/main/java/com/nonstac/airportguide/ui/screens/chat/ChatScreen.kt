package com.nonstac.airportguide.ui.screens.chat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nonstac.airportguide.data.model.ChatMessage
import com.nonstac.airportguide.service.BluetoothDeviceMinimal // Import the data class
import com.nonstac.airportguide.service.ConnectionState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    ticketId: String,
    onNavigateBack: () -> Unit,
    chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.provideFactory(LocalContext.current, ticketId)
    )
) {
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }

    // --- Permissions & Bluetooth State ---
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE) // Add Advertise
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
    }
    var hasAllPermissions by remember { mutableStateOf(false) } // Track combined permission state

    val bluetoothAdapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    var isBluetoothEnabled by remember { mutableStateOf(bluetoothAdapter?.isEnabled == true) }

    // Permission Launcher
    val requestPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasAllPermissions = permissions.values.all { it }
        if (!hasAllPermissions) {
            coroutineScope.launch { snackbarHostState.showSnackbar("Required permissions denied.") }
        } else {
            // Check BT enabled state again after getting permissions
            isBluetoothEnabled = bluetoothAdapter?.isEnabled == true
        }
    }

    // Bluetooth Enable Launcher
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isBluetoothEnabled = (result.resultCode == android.app.Activity.RESULT_OK)
        if (!isBluetoothEnabled) {
            coroutineScope.launch { snackbarHostState.showSnackbar("Bluetooth must be enabled.") }
        }
    }

    // Check initial state and request if needed ONCE
    LaunchedEffect(key1 = Unit) {
        hasAllPermissions = requiredPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        isBluetoothEnabled = bluetoothAdapter?.isEnabled == true

        if (!hasAllPermissions) {
            Log.d("ChatScreen", "Requesting permissions on launch.")
            requestPermissionsLauncher.launch(requiredPermissions)
        } else if (!isBluetoothEnabled) {
            Log.d("ChatScreen", "Requesting BT enable on launch.")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        }
    }

    // Effect to show errors from ViewModel
    LaunchedEffect(uiState.error) {
        uiState.error?.let { errorMsg ->
            snackbarHostState.showSnackbar(errorMsg)
            chatViewModel.clearError()
        }
    }

    // Scroll message list
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            coroutineScope.launch { listState.animateScrollToItem(uiState.messages.lastIndex) }
        }
    }

    // --- UI ---
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            ChatTopAppBar(
                connectionState = uiState.connectionState,
                partnerName = uiState.partnerDeviceName, // Show name instead of address
                onNavigateBack = onNavigateBack
            )
        },
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Show Buttons only if Permissions and BT are enabled and not connected/connecting/listening
            val canInteract = hasAllPermissions && isBluetoothEnabled
            val showControlButtons = uiState.connectionState == ConnectionState.NONE ||
                    uiState.connectionState == ConnectionState.DISCONNECTED ||
                    uiState.connectionState == ConnectionState.ERROR

            AnimatedVisibility(visible = showControlButtons) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { chatViewModel.startListening() },
                        enabled = canInteract // Enable only if perms and BT are OK
                    ) {
                        Icon(Icons.Default.Hearing, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Listen")
                    }
                    Button(
                        onClick = {
                            if (uiState.isDiscovering) chatViewModel.stopDiscovery() else chatViewModel.startDiscovery()
                        },
                        enabled = canInteract // Enable only if perms and BT are OK
                    ) {
                        Icon(if(uiState.isDiscovering) Icons.Default.BluetoothSearching else Icons.Default.Search, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                        Text(if (uiState.isDiscovering) "Stop Scan" else "Find Companion")
                    }
                }
            }

            // Show Discovered Devices List (only when discovering or devices found)
            AnimatedVisibility(visible = uiState.isDiscovering || uiState.discoveredDevices.isNotEmpty()) {
                DiscoveredDevicesList(
                    devices = uiState.discoveredDevices,
                    isDiscovering = uiState.isDiscovering,
                    onDeviceClick = { device -> chatViewModel.connectToDevice(device) }
                )
            }


            // Message List (takes remaining space)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = false
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    ChatMessageItem(message)
                }
            }

            // Message Input Area - Only show if connected
            AnimatedVisibility(visible = uiState.connectionState == ConnectionState.CONNECTED) {
                MessageInput(
                    message = uiState.currentMessageInput,
                    onMessageChange = chatViewModel::updateMessageInput,
                    onSendClick = {
                        chatViewModel.sendMessage()
                        keyboardController?.hide()
                    },
                    enabled = uiState.connectionState == ConnectionState.CONNECTED // Check needed here too for Button state
                )
            }
        }
    }
}

// --- Composables for TopAppBar, Device List, Message Item, Input Field ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopAppBar(
    connectionState: ConnectionState,
    partnerName: String?, // Show name now
    onNavigateBack: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text("Companion Chat")
                Text(
                    text = when (connectionState) {
                        ConnectionState.NONE -> "Not connected"
                        ConnectionState.LISTEN -> "Waiting for companion..."
                        ConnectionState.CONNECTING -> "Connecting..."
                        ConnectionState.CONNECTED -> partnerName ?: "Connected" // Show partner name
                        ConnectionState.ERROR -> "Connection Error"
                        ConnectionState.DISCONNECTED -> "Disconnected"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { /* Status Icon (same as before) */
            val iconColor = when (connectionState) { ConnectionState.CONNECTED -> Color.Green; ConnectionState.CONNECTING, ConnectionState.LISTEN -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.error }
            Icon( imageVector = when (connectionState) { ConnectionState.CONNECTED -> Icons.Default.BluetoothConnected; ConnectionState.CONNECTING, ConnectionState.LISTEN -> Icons.Default.BluetoothSearching; ConnectionState.ERROR -> Icons.Default.ErrorOutline; else -> Icons.Default.BluetoothDisabled }, contentDescription = "Connection Status: ${connectionState.name}", tint = iconColor, modifier = Modifier.padding(end = 16.dp) )
        }
    )
}

@Composable
fun DiscoveredDevicesList(
    devices: List<BluetoothDeviceMinimal>,
    isDiscovering: Boolean,
    onDeviceClick: (BluetoothDeviceMinimal) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Found Devices", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            if(isDiscovering) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (devices.isEmpty() && !isDiscovering) {
            Text("No devices found.", style = MaterialTheme.typography.bodySmall)
        } else {
            // Limit height or make scrollable if needed
            Column(modifier = Modifier.heightIn(max = 150.dp)) { // Example height limit
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceClick(device) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.padding(end = 8.dp)) // Generic device icon
                        Text(device.name ?: "Unknown Device", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                    }
                    Divider()
                }
            }
        }
    }
}


@Composable
fun ChatMessageItem(message: ChatMessage) {
    val backgroundColor = if (message.isSentByUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (message.isSentByUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    val alignment = if (message.isSentByUser) Alignment.CenterEnd else Alignment.CenterStart
    val cornerSize = 16.dp

    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { // Add horizontal padding here too
        Column(
            modifier = Modifier
                .align(alignment)
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(topStart = cornerSize, topEnd = cornerSize, bottomStart = if (message.isSentByUser) cornerSize else 0.dp, bottomEnd = if (message.isSentByUser) 0.dp else cornerSize))
                .background(backgroundColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text = message.text, color = textColor)
            Text(
                text = formatTimestamp(message.timestamp),
                fontSize = 10.sp,
                color = textColor.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun MessageInput(
    message: String,
    onMessageChange: (String) -> Unit,
    onSendClick: () -> Unit,
    enabled: Boolean // Use this to control enabled state
) {
    Surface(shadowElevation = 4.dp) { // Reduced elevation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceBright) // Slightly different background
                .padding(horizontal = 8.dp, vertical = 8.dp) // Adjusted padding
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = message,
                onValueChange = onMessageChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Send message...") }, // Changed placeholder
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (enabled) onSendClick() }),
                enabled = enabled,
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, // Use theme colors
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton( // Use FilledIconButton for emphasis
                onClick = onSendClick,
                enabled = enabled,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary, // Ensure color
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send Message")
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))