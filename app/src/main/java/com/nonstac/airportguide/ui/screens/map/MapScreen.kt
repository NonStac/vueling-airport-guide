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
import com.nonstac.airportguide.util.PermissionsHandler
import com.nonstac.airportguide.ui.theme.VuelingYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    username: String,
    onNavigateToTickets: () -> Unit,
    mapViewModel: MapViewModel = viewModel(factory = MapViewModel.provideFactory(LocalContext.current, username))
) {
    val uiState by mapViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // --- Permission Launchers ---
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Check if FINE location was granted specifically
        val fineLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
        mapViewModel.onPermissionResult(Manifest.permission.ACCESS_FINE_LOCATION, fineLocationGranted)
        // Optionally check COARSE if needed, but FINE is preferred
        // val coarseLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
        // mapViewModel.onPermissionResult(Manifest.permission.ACCESS_COARSE_LOCATION, coarseLocationGranted)
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        mapViewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, isGranted)
        if (isGranted) {
            // Automatically start listening after permission granted if desired
            // mapViewModel.startListening()
        } else {
            // Optionally show a snackbar message explaining why permission is needed
            // mapViewModel.showError("Audio permission needed for voice commands.")
        }
    }

    // --- Request permissions on screen launch if not already granted ---
    LaunchedEffect(Unit) {
        if (!PermissionsHandler.hasLocationPermissions(context)) {
            locationPermissionLauncher.launch(PermissionsHandler.locationPermissions)
        } else {
            // Notify ViewModel it already has permission to start location updates
            mapViewModel.onPermissionResult(Manifest.permission.ACCESS_FINE_LOCATION, true)
        }
        // Audio permission is requested on demand when FAB is clicked
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
                    // Floor Switcher
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val minFloor = uiState.airportMap?.nodes?.minOfOrNull { it.floor } ?: 1
                        val maxFloor = uiState.airportMap?.nodes?.maxOfOrNull { it.floor } ?: 1

                        Text("Floor: ${uiState.currentFloor}", modifier = Modifier.padding(end = 8.dp))
                        // Go Down Button
                        IconButton(
                            onClick = { mapViewModel.changeFloor(uiState.currentFloor - 1) },
                            enabled = uiState.currentFloor > minFloor // Enable if not on the lowest floor
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Go Down One Floor")
                        }
                        // Go Up Button
                        IconButton(
                            onClick = { mapViewModel.changeFloor(uiState.currentFloor + 1) },
                            enabled = uiState.currentFloor < maxFloor // Enable if not on the highest floor
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Go Up One Floor")
                        }
                    }
                    // Navigate to Tickets Button
                    IconButton(onClick = onNavigateToTickets) {
                        Icon(Icons.AutoMirrored.Filled.AirplaneTicket, contentDescription = "View Tickets")
                    }
                }
            )
        },
        floatingActionButton = {
            // Voice Input FAB
            FloatingActionButton(
                onClick = {
                    if (PermissionsHandler.hasAudioPermission(context)) {
                        mapViewModel.startListening()
                    } else {
                        // Request permission if not granted
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                containerColor = VuelingYellow,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                // Show progress indicator while listening
                if (uiState.isListening) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    // Show mic icon otherwise
                    Icon(Icons.Filled.Mic, contentDescription = "Start Voice Command")
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = mapViewModel.snackbarHostState) }
    ) { paddingValues ->
        // --- Main Content Area ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // Apply padding from scaffold
        ) {
            // --- Map Loading / Display ---
            when {
                uiState.isLoadingMap -> {
                    // Show loading indicator while map loads
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.airportMap != null -> {
                    // Display the MapCanvas when map is loaded
                    MapCanvas(
                        map = uiState.airportMap,
                        currentLocationNodeId = uiState.currentLocationNodeId,
                        destinationNodeId = uiState.destinationNodeId,
                        path = uiState.currentPath,
                        currentFloor = uiState.currentFloor,
                        isBlackout = uiState.isBlackout,
                        modifier = Modifier.fillMaxSize(),
                        onNodeClick = { clickedNode ->
                            // Call ViewModel function when a node is clicked on the canvas
                            mapViewModel.selectNode(clickedNode)
                        }
                    )
                }
                else -> {
                    // Show error if map failed to load
                    Text(
                        "Could not load map data.",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // --- Status Overlay Card ---
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp), // Adjusted padding
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f) // Slightly different color
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Blackout Indicator
                    if(uiState.isBlackout) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SignalWifiOff, contentDescription = "Blackout Active", tint = VuelingYellow)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "BLACKOUT MODE ACTIVE",
                                style = MaterialTheme.typography.titleSmall, // Slightly smaller
                                fontWeight = FontWeight.Bold,
                                color = VuelingYellow,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    // Status Message Text
                    Text(
                        text = uiState.statusMessage ?: "Tap the mic or a map node.",
                        style = MaterialTheme.typography.bodyMedium, // Adjusted size
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Processing Indicator
                    if (uiState.isProcessing || uiState.isLoadingLocation) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // --- Node Info Dialog ---
            // This dialog will appear centered on the screen when selectedNodeInfo is not null
            NodeInfoDialog(
                node = uiState.selectedNodeInfo,
                onDismiss = { mapViewModel.dismissNodeInfo() } // Call VM function to dismiss
            )
            // --- End Node Info Dialog ---

        } // End Box
    } // End Scaffold
}


// --- Node Information Dialog Composable ---
@Composable
fun NodeInfoDialog(
    node: Node?, // Node is nullable; dialog shows only if non-null
    onDismiss: () -> Unit
) {
    if (node != null) {
        AlertDialog(
            onDismissRequest = onDismiss, // Call lambda when user clicks outside or back button
            icon = { // Add an icon based on node type
                when(node.type) {
                    NodeType.GATE -> Icon(Icons.Filled.MeetingRoom, contentDescription = "Gate")
                    NodeType.BATHROOM -> Icon(Icons.Filled.Wc, contentDescription = "Bathroom")
                    NodeType.EMERGENCY_EXIT -> Icon(Icons.Filled.ExitToApp, contentDescription = "Exit")
                    NodeType.ENTRANCE -> Icon(Icons.Filled.DoorFront, contentDescription = "Entrance")
                    NodeType.WAYPOINT -> Icon(Icons.Filled.Place, contentDescription = "Waypoint")
                }
            },
            title = {
                Text(text = node.name, fontWeight = FontWeight.Bold)
            },
            text = {
                // Display node details in a column
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Type: ${node.type.name.lowercase().replaceFirstChar { it.titlecase() }}") // Nicer formatting
                    Text("ID: ${node.id}")
                    Text("Floor: ${node.floor}")
                    // Add more details if needed, e.g., coordinates
                    // Text("Coordinates: (X=${node.x}, Y=${node.y})")
                }
            },
            confirmButton = {
                // Simple close button
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
            // Optionally add dismissButton = { ... } if needed
        )
    }
}