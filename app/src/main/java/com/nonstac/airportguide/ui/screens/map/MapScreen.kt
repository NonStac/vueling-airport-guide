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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonstac.airportguide.util.PermissionsHandler
import com.nonstac.airportguide.ui.theme.VuelingYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    username: String,
    onNavigateToTickets: () -> Unit,
    mapViewModel: MapViewModel = viewModel(factory = MapViewModel.provideFactory(LocalContext.current, username)) // Use factory if needed
) {
    val uiState by mapViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission Launchers
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)) {
            mapViewModel.onPermissionResult(Manifest.permission.ACCESS_FINE_LOCATION, true)
        } else {
            mapViewModel.onPermissionResult(Manifest.permission.ACCESS_FINE_LOCATION, false)
            // Show rationale or disable feature
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        mapViewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, isGranted)
        if (isGranted) {
            mapViewModel.startListening() // Start listening immediately after granted
        } else {
            // Show rationale or disable feature
        }
    }

    // Request permissions on launch if needed
    LaunchedEffect(Unit) {
        if (!PermissionsHandler.hasLocationPermissions(context)) {
            locationPermissionLauncher.launch(PermissionsHandler.locationPermissions)
        } else {
            mapViewModel.onPermissionResult(Manifest.permission.ACCESS_FINE_LOCATION, true) // Notify VM it has permission
        }
        // Audio permission requested only when mic button is pressed
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.airportMap?.airportName ?: "Airport Map") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // Floor switcher - Simple example, make more robust
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Floor: ${uiState.currentFloor}", modifier = Modifier.padding(end = 8.dp))
                        IconButton(onClick = { mapViewModel.changeFloor(uiState.currentFloor - 1) }, enabled = uiState.currentFloor > 1) { // Assuming floor 1 is min
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Go Down One Floor")
                        }
                        IconButton(onClick = { mapViewModel.changeFloor(uiState.currentFloor + 1) }, enabled = uiState.currentFloor < (uiState.airportMap?.nodes?.maxOfOrNull { it.floor } ?: 1) ) {
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
                    if (PermissionsHandler.hasAudioPermission(context)) {
                        mapViewModel.startListening()
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
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
            if (uiState.isLoadingMap) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.airportMap != null) {
                MapCanvas(
                    map = uiState.airportMap,
                    currentLocationNodeId = uiState.currentLocationNodeId,
                    destinationNodeId = uiState.destinationNodeId,
                    path = uiState.currentPath,
                    currentFloor = uiState.currentFloor,
                    isBlackout = uiState.isBlackout,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    "Could not load map.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Status Overlay Card
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if(uiState.isBlackout) {
                        Text(
                            text = "⚠️ BLACKOUT MODE ACTIVE",
                            style = MaterialTheme.typography.titleMedium,
                            color = VuelingYellow,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = uiState.statusMessage ?: "Tap the mic and speak your request.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    if (uiState.isProcessing) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}