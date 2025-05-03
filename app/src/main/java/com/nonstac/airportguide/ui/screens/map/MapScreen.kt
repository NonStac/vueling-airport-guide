package com.nonstac.airportguide.ui.screens.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AirplaneTicket
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonstac.airportguide.data.model.Node // Ensure Node is imported
import com.nonstac.airportguide.data.model.NodeType
import com.nonstac.airportguide.util.PermissionsHandler // Keep this if used elsewhere, otherwise check direct state
import com.nonstac.airportguide.ui.theme.VuelingYellow
import android.util.Log // Import Log

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    username: String,
    onNavigateToTickets: () -> Unit,
    mapViewModel: MapViewModel = viewModel(factory = MapViewModel.provideFactory(LocalContext.current, username))
) {
    val uiState by mapViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current // Context needed? Maybe not if PermissionsHandler is removed

    // --- Permission Launchers ---
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Check if FINE location was granted specifically
        val fineLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
        mapViewModel.onPermissionResult(Manifest.permission.ACCESS_FINE_LOCATION, fineLocationGranted)
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        mapViewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, isGranted)
        // No automatic action needed here after grant, user must tap again
    }

    // --- Request permissions on screen launch if not already granted ---
    LaunchedEffect(Unit) {
        // Check and request location permission
        val locationPermissionNeeded = !PermissionsHandler.hasLocationPermissions(context) // Assuming PermissionsHandler exists and is correct
        if (locationPermissionNeeded) {
            locationPermissionLauncher.launch(PermissionsHandler.locationPermissions)
        } else {
            mapViewModel.onPermissionResult(Manifest.permission.ACCESS_FINE_LOCATION, true)
        }
        // Update ViewModel with current audio permission status (important!)
        mapViewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, PermissionsHandler.hasAudioPermission(context))
    }

    // --- UI Scaffold ---
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.airportMap?.airportName ?: "Airport Map") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val minFloor = remember(uiState.airportMap) { uiState.airportMap?.nodes?.minOfOrNull { it.floor } ?: 1 }
                        val maxFloor = remember(uiState.airportMap) { uiState.airportMap?.nodes?.maxOfOrNull { it.floor } ?: 1 }

                        Text("Floor: ${uiState.currentFloor}", modifier = Modifier.padding(end = 8.dp))
                        IconButton(
                            onClick = { mapViewModel.changeFloor(uiState.currentFloor - 1) },
                            enabled = uiState.currentFloor > minFloor
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Go Down One Floor")
                        }
                        IconButton(
                            onClick = { mapViewModel.changeFloor(uiState.currentFloor + 1) },
                            enabled = uiState.currentFloor < maxFloor
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Go Up One Floor")
                        }
                    }
                    IconButton(onClick = onNavigateToTickets) {
                        Icon(Icons.AutoMirrored.Filled.AirplaneTicket, contentDescription = "View Tickets")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // --- MODIFIED onClick LOGIC ---
                    // Check permission directly from the ViewModel state
                    if (uiState.permissionsGranted[Manifest.permission.RECORD_AUDIO] == true) {
                        // Permission granted: Call the ViewModel function that stops TTS and starts ASR
                        Log.d("MapScreen", "FAB Clicked: Permission OK, calling interruptSpeechAndListen.")
                        mapViewModel.interruptSpeechAndListen() // <<<<<<< CALL THIS FUNCTION
                    } else {
                        // Permission not granted: Launch the permission request
                        Log.d("MapScreen", "FAB Clicked: Permission needed, launching request.")
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) // <<<<<<< LAUNCH PERMISSION REQUEST
                    }
                    // --- END OF MODIFIED onClick LOGIC ---
                },
                containerColor = VuelingYellow,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                if (uiState.isListening) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(Icons.Filled.Mic, contentDescription = "Start Voice Command")
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = mapViewModel.snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoadingMap -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.airportMap != null -> {
                    MapCanvas(
                        map = uiState.airportMap,
                        currentLocationNodeId = uiState.currentLocationNodeId,
                        destinationNodeId = uiState.destinationNodeId,
                        path = uiState.currentPath,
                        currentFloor = uiState.currentFloor,
                        isBlackout = uiState.isBlackout,
                        modifier = Modifier.fillMaxSize(),
                        onNodeClick = mapViewModel::selectNode // Use method reference
                    )
                }
                else -> {
                    Text(
                        "Could not load map data.",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter) // Changed alignment to TopCenter
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if(uiState.isBlackout) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SignalWifiOff, contentDescription = "Blackout Active", tint = VuelingYellow)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "BLACKOUT MODE ACTIVE",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = VuelingYellow,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = uiState.statusMessage ?: "Tap the mic or a map node.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Combined processing indicator for LLM or location finding
                    if (uiState.isProcessing || uiState.isLoadingLocation) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            NodeInfoDialog(
                node = uiState.selectedNodeInfo,
                onDismiss = mapViewModel::dismissNodeInfo // Use method reference
            )

        } // End Box
    } // End Scaffold
}

@Composable
fun NodeInfoDialog(
    node: Node?,
    onDismiss: () -> Unit
) {
    if (node != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                when(node.type) {
                    NodeType.GATE -> Icon(Icons.Filled.MeetingRoom, contentDescription = "Gate")
                    NodeType.BATHROOM -> Icon(Icons.Filled.Wc, contentDescription = "Bathroom")
                    NodeType.EMERGENCY_EXIT -> Icon(Icons.Filled.ExitToApp, contentDescription = "Exit")
                    NodeType.ENTRANCE -> Icon(Icons.Filled.DoorFront, contentDescription = "Entrance")
                    NodeType.WAYPOINT -> Icon(Icons.Filled.Place, contentDescription = "Waypoint")
                    NodeType.STAIRS_ELEVATOR -> { /* Render Nothing */ }
                    NodeType.CONNECTION -> { /* Render Nothing */ }
                }
            },
            title = { Text(text = node.name, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Type: ${node.type.name.lowercase().replaceFirstChar { it.titlecase() }}")
                    Text("ID: ${node.id}")
                    Text("Floor: ${node.floor}")
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
    }
}