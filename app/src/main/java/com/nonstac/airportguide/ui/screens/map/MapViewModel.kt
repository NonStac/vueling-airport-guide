package com.nonstac.airportguide.ui.screens.map

import android.Manifest
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nonstac.airportguide.data.local.AirportMapDataSource
import com.nonstac.airportguide.data.model.* // Import Node, NodeType etc.
import com.nonstac.airportguide.data.repository.MapRepository
import com.nonstac.airportguide.data.repository.MapRepositoryImpl
import com.nonstac.airportguide.data.repository.MockTicketRepository
import com.nonstac.airportguide.data.repository.TicketRepository
import com.nonstac.airportguide.service.*
import com.nonstac.airportguide.util.*
import com.nonstac.airportguide.util.Constants
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.roundToInt

data class MapUiState(
    val isLoadingMap: Boolean = true,
    val isLoadingLocation: Boolean = false, // Separate loading state for location
    val isProcessing: Boolean = false, // For LLM/Action processing
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val airportMap: AirportMap? = null,
    val currentLocation: Location? = null,
    val currentLocationNodeId: String? = null,
    val destinationNodeId: String? = null,
    val currentPath: List<Node>? = null,
    val statusMessage: String? = "Tap the mic to start",
    val isBlackout: Boolean = false,
    val userFlightGate: String? = null, // Gate ID like "A2"
    val currentFloor: Int = 1, // Default starting floor
    val permissionsGranted: Map<String, Boolean> = mapOf(
        Manifest.permission.ACCESS_FINE_LOCATION to false,
        Manifest.permission.RECORD_AUDIO to false
    ),
    val selectedNodeInfo: Node? = null,

    val selectedMapId: String = Constants.DEFAULT_MAP_ID,
    val availableMaps: List<Pair<String, String>> = listOf(
        Constants.MAP_ID_BCN_T1 to "BCN T1",
        Constants.MAP_ID_JFK_T4 to "JFK T4"
    ),
    val currentMapDisplayName: String = "Loading Map...",
    val blackoutStartTime: Long? = null,

    val selectableNodes: List<Node> = emptyList(),
    val selectedSourceNodeId: String? = null,
    val selectedDestinationNodeId: String? = null,
)

class MapViewModel(
    // Repositories
    private val mapRepository: MapRepository,
    private val ticketRepository: TicketRepository,
    // Services
    private val connectivityService: ConnectivityService,
    private val locationService: LocationService,
    private val ttsService: TextToSpeechService,
    private val speechRecognitionService: SpeechRecognitionService,
    private val llmService: MockLlmService, // Using the mock
    // User Info
    private val username: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    val snackbarHostState = SnackbarHostState()
    private var locationUpdateJob: Job? = null
    private var mapNodesById: Map<String, Node> = emptyMap()
    private val TAG = "MapViewModel_Airport"

    init {
        Log.d(TAG, "Initializing for user: $username")
        loadMapData(uiState.value.selectedMapId)
        loadTicketData()
        observeServices()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMap = true) }
            // Load Map
            mapRepository.getAirportMap(Constants.DEFAULT_MAP_ID)
                .onSuccess { map ->
                    Log.d(TAG, "Map loaded: ${map.airportName}")
                    mapNodesById =
                        map.nodes.associateBy { node -> node.id } // Populate mapNodesById
                    _uiState.update {
                        it.copy(
                            airportMap = map,
                            isLoadingMap = false,
                            selectableNodes = map.nodes.sortedBy { node -> node.name })
                    }
                    // requestInitialLocation() // Consider if you want location attempt here
                }.onFailure { error ->
                    Log.e(TAG, "Failed to load map", error)
                    _uiState.update { it.copy(isLoadingMap = false) }
                    showError("Failed to load airport map: ${error.message}")
                }

            // Fetch user's gate
            ticketRepository.getBoughtTickets(username)
                .onSuccess { tickets ->
                    val relevantTicket = tickets.firstOrNull { it.status == TicketStatus.BOUGHT }
                    Log.d(
                        TAG,
                        "Found relevant ticket: ${relevantTicket?.flightNumber}, Gate: ${relevantTicket?.gate}"
                    )
                    _uiState.update { it.copy(userFlightGate = relevantTicket?.gate) }
                }.onFailure { error ->
                    Log.w(TAG, "Could not fetch user tickets/gate: ${error.message}")
                }
        }
    }

    private fun loadMapData(mapId: String) {
        viewModelScope.launch {
            Log.d(TAG, "Requesting map data for ID: $mapId")
            val displayName = uiState.value.availableMaps.firstOrNull { it.first == mapId }?.second
                ?: "Unknown Map"

            // Set loading state and clear old map-specific data
            _uiState.update { currentState ->
                currentState.copy(
                    isLoadingMap = true,
                    currentMapDisplayName = displayName,
                    airportMap = null, // Clear map data
                    // mapNodesById will be repopulated below
                    selectableNodes = emptyList(), // Clear nodes for dropdowns
                    currentLocationNodeId = null, // Reset location on map change
                    destinationNodeId = null, // Reset destination
                    currentPath = null, // Reset path
                    selectedNodeInfo = null, // Close node info dialog
                    currentFloor = 1, // Reset floor
                    selectedSourceNodeId = null, // Reset dropdown selections
                    selectedDestinationNodeId = null
                )
            }

            mapRepository.getAirportMap(mapId)
                .onSuccess { map ->
                    Log.d(TAG, "Map loaded successfully: ${map.airportName}")
                    mapNodesById =
                        map.nodes.associateBy { node -> node.id } // Repopulate node lookup map
                    _uiState.update { currentState ->
                        currentState.copy(
                            airportMap = map,
                            isLoadingMap = false,
                            currentMapDisplayName = map.airportName, // Use name from loaded map
                            selectableNodes = map.nodes.sortedBy { node -> node.name } // Update selectable nodes
                        )
                    }
                    // Now attempt location for the new map
                    requestInitialLocation() // Or just let location updates handle it if running
                }.onFailure { error ->
                    Log.e(TAG, "Failed to load map ID: $mapId", error)
                    _uiState.update { currentState ->
                        currentState.copy(
                            isLoadingMap = false,
                            currentMapDisplayName = "Error Loading Map"
                        )
                    }
                    showError("Failed to load map: ${error.message}")
                }
        }
    }

    // Add separate function for ticket loading if not already done
    private fun loadTicketData() {
        viewModelScope.launch {
            ticketRepository.getBoughtTickets(username)
                .onSuccess { tickets -> /* ... update userFlightGate ... */ }
                .onFailure { error -> /* ... log error ... */ }
        }
    }

    private fun observeServices() {
        // Connectivity
        connectivityService.isConnected
            .onEach { isConnected ->
                val wasBlackout = uiState.value.isBlackout
                val isNowBlackout = !isConnected
                var startTime = uiState.value.blackoutStartTime

                if (isNowBlackout && !wasBlackout) {
                    Log.i(TAG, "Blackout detected.")
                    startTime = System.currentTimeMillis()
                    speak("Warning: Network connection lost. Entering blackout mode.")
                } else if (!isNowBlackout && wasBlackout) {
                    Log.i(TAG, "Connection restored.")
                    startTime = null
                    speak("Network connection restored.")
                }

                _uiState.update { it.copy(isBlackout = isNowBlackout, blackoutStartTime = startTime) }
            }
            .launchIn(viewModelScope)

        // ASR Listening State
        speechRecognitionService.isListening
            .onEach { listening -> _uiState.update { it.copy(isListening = listening) } }
            .launchIn(viewModelScope)

        // ASR Results
        speechRecognitionService.recognizedText
            .filterNotNull()
            .onEach { text ->
                Log.d(TAG, "ASR Result: $text")
                _uiState.update { it.copy(statusMessage = "Processing: '$text'") }
                processLlmInput(text)
                speechRecognitionService.clearRecognizedText() // Consume the text
            }
            .launchIn(viewModelScope)

        // ASR Errors
        speechRecognitionService.error
            .filterNotNull()
            .onEach { errorMsg ->
                Log.e(TAG, "ASR Error: $errorMsg")
                showError("Speech recognition error: $errorMsg")
                _uiState.update { it.copy(isProcessing = false, isListening = false) }
                speechRecognitionService.clearError() // Consume the error
            }
            .launchIn(viewModelScope)

        // TTS Speaking State
        ttsService.isSpeaking
            .onEach { speaking -> _uiState.update { it.copy(isSpeaking = speaking) } }
            .launchIn(viewModelScope)

        // Location (updates started based on permission)
        locationService.lastKnownLocation
            .onEach { location -> location?.let { handleLocationUpdate(it) } }
            .launchIn(viewModelScope)
    }

    fun onPermissionResult(permission: String, granted: Boolean) {
        Log.d(TAG, "Permission result: $permission, Granted: $granted")
        _uiState.update {
            it.copy(permissionsGranted = it.permissionsGranted + (permission to granted))
        }

        if (permission == Manifest.permission.ACCESS_FINE_LOCATION) {
            if (granted) {
                startLocationUpdates()
                requestInitialLocation()
            } else {
                viewModelScope.launch { showError("Location permission is required for navigation assistance.") }
            }
        }
        // No specific action needed here for RECORD_AUDIO denial, handled by interruptSpeechAndListen
    }

    fun updateSelectedSourceNode(nodeId: String?) {
        _uiState.update {
            it.copy(selectedSourceNodeId = nodeId)
        }
    }

    fun updateSelectedDestinationNode(nodeId: String?) {
        _uiState.update {
            it.copy(selectedDestinationNodeId = nodeId)
        }
    }

    fun triggerPathfindingFromDropdowns() {
        val sourceId = _uiState.value.selectedSourceNodeId
        val destId = _uiState.value.selectedDestinationNodeId
        val currentMap = _uiState.value.airportMap // Ensure map is loaded

        if (sourceId != null && destId != null && currentMap != null) {
            findAndSetPath(sourceId, destId)

            _uiState.update {
                it.copy(
                    selectedSourceNodeId = null,
                    selectedDestinationNodeId = null
                )
            }
        } else {
            viewModelScope.launch {
                showError("Please select both start and end locations.")
            }
        }
    }

    // --- NEW FUNCTION: Set current location from dialog button ---
    fun setCurrentNodeFromDialog(node: Node) {
        Log.d(TAG, "Setting current node from dialog: ${node.id}")

        val newStateUpdate =
            { currentState: MapUiState -> currentState.copy(currentLocationNodeId = node.id) }

        // Check if floor needs changing
        if (node.floor != _uiState.value.currentFloor) {
            changeFloor(node.floor) // This might reset path/destination depending on its implementation
            // Apply the node ID update *after* potential floor change effects
            _uiState.update(newStateUpdate)
            updateStatus("Switched to Floor ${node.floor} and set location: ${node.name}")
            speak("Okay, moved to floor ${node.floor} and set your location to ${node.name}.")

        } else {
            _uiState.update(newStateUpdate)
            updateStatus("Current location set to: ${node.name}")
            speak("Okay, location set to ${node.name}.")
        }


        // If a destination is already set, consider recalculating the path
        val destId = _uiState.value.destinationNodeId
        if (destId != null && destId != node.id) {
            Log.d(TAG, "Current location changed with active destination, recalculating path.")
            speak("Recalculating route from your new location.")
            findAndSetPath(node.id, destId) // Recalculate
        }
    }

    // --- NEW FUNCTION: Set destination from dialog button ---
    fun setDestinationNodeFromDialog(node: Node) {
        Log.d(TAG, "Setting destination node from dialog: ${node.id}")
        val startNodeId = _uiState.value.currentLocationNodeId
        if (startNodeId == null) {
            speak("Okay, destination set to ${node.name}. Please also set your current location or use voice commands.")
            // Update destination but don't calculate path yet
            _uiState.update { it.copy(destinationNodeId = node.id) }
            updateStatus("Destination set: ${node.name}. Set current location to find path.")
        } else if (startNodeId == node.id) {
            speak("Current location and destination cannot be the same.")
            updateStatus("Cannot set destination to current location.")
        } else {
            speak("Okay, setting destination to ${node.name} and calculating route.")
            // Update destination and trigger pathfinding
            _uiState.update { it.copy(isProcessing = true) } // Show processing indicator
            findAndSetPath(startNodeId, node.id) // This will update state and clear processing flag
        }
    }

    private fun requestInitialLocation() {
        if (uiState.value.permissionsGranted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoadingLocation = true) }
                val location = locationService.getLastKnownLocationDirect()
                Log.d(TAG, "Initial location fetched: $location")
                location?.let { handleLocationUpdate(it) }
                    ?: _uiState.update {
                        it.copy(
                            isLoadingLocation = false,
                            statusMessage = "Trying to find your location..."
                        )
                    }
                // Don't reset isLoadingLocation here, handleLocationUpdate does it
            }
        } else {
            Log.w(TAG, "Location permission not granted, cannot request initial location.")
            _uiState.update { it.copy(statusMessage = "Location permission needed.") }
        }
    }

    private fun startLocationUpdates() {
        if (locationUpdateJob?.isActive == true || uiState.value.permissionsGranted[Manifest.permission.ACCESS_FINE_LOCATION] != true) return
        Log.d(TAG, "Starting location updates flow.")
        // locationUpdateJob = locationService.requestLocationUpdates()... // Keep commented if not used
        Log.w(
            TAG,
            "startLocationUpdates: locationService.requestLocationUpdates() is commented out."
        )
    }


    private fun handleLocationUpdate(location: Location) {
        // Update location state first
        _uiState.update { it.copy(currentLocation = location, isLoadingLocation = false) }
        // Then find the nearest node based on this new location
        findAndSetNearestNode(location)
    }

    private fun findAndSetNearestNode(location: Location) {
        val currentMap = uiState.value.airportMap ?: return
        val currentFloor = uiState.value.currentFloor // Use current floor view
        // TODO: Convert GPS (lat/lon) to map coordinates (x/y)
        // This requires a calibration/mapping process specific to your map.
        // Using lat/lon directly as x/y is likely incorrect unless map is scaled to GPS.
        val mockMapX = location.longitude // Placeholder - NEEDS CORRECT CONVERSION
        val mockMapY = location.latitude  // Placeholder - NEEDS CORRECT CONVERSION

        val closestNode =
            LocationUtils.findClosestNode(mockMapX, mockMapY, currentFloor, currentMap.nodes)

        val previousNodeId = uiState.value.currentLocationNodeId
        if (closestNode != null && closestNode.id != previousNodeId) {
            Log.d(
                TAG,
                "GPS mapped to nearest node: ${closestNode.id} (${closestNode.name}) on floor $currentFloor"
            )
            // Update the current location node ID
            _uiState.update { it.copy(currentLocationNodeId = closestNode.id) }

            val currentPath = uiState.value.currentPath
            val destinationNodeId = uiState.value.destinationNodeId

            if (currentPath != null && destinationNodeId != null) {
                // If navigating, check if we reached the destination or need recalculation
                if (closestNode.id == destinationNodeId) {
                    speak("You have arrived at ${closestNode.name}.")
                    updateStatus("Arrived at destination.")
                    _uiState.update {
                        it.copy(
                            destinationNodeId = null,
                            currentPath = null
                        )
                    } // Clear navigation state
                } else {
                    // Check if the new node is part of the existing path
                    val nodeIndexInPath = currentPath.indexOfFirst { it.id == closestNode.id }
                    if (nodeIndexInPath != -1) {
                        // User is on the path, provide next instruction
                        speak("Okay, you are now at ${closestNode.name}.")
                        // Update path state if needed (e.g., remove passed nodes)
                        // Then generate next instruction based on remaining path
                        val remainingPath = currentPath.drop(nodeIndexInPath)
                        if (remainingPath.size >= 2) {
                            _uiState.update { it.copy(currentPath = remainingPath) } // Update path state
                            generateAndSpeakInstructions(remainingPath)
                        } else {
                            // This shouldn't happen if destination check passed, but handle just in case
                            speak("You are at ${closestNode.name}, near your destination.")
                        }
                    } else {
                        // User is off-path, recalculate
                        speak("It seems you are now at ${closestNode.name}. Recalculating route.")
                        findAndSetPath(closestNode.id, destinationNodeId)
                    }
                }
            } else {
                // Not currently navigating, just inform user of location
                speak("Detected your location near ${closestNode.name}.")
                updateStatus("Current location: ${closestNode.name}")
            }
        } else if (closestNode == null) {
            Log.w(
                TAG,
                "Could not find a close node on floor $currentFloor for the current location."
            )
            // Optionally provide feedback: speak("Having trouble pinpointing your exact location on the map.")
        }
        // If closestNode.id == previousNodeId, do nothing (user hasn't moved significantly to a new node area)
    }

    fun interruptSpeechAndListen() {
        Log.d(TAG, "Interrupt speech and listen requested.")
        ttsService.stop()

        if (uiState.value.permissionsGranted[Manifest.permission.RECORD_AUDIO] == true) {
            if (!uiState.value.isListening) {
                Log.d(TAG, "Starting ASR listening.")
                speechRecognitionService.clearError()
                speechRecognitionService.clearRecognizedText()
                speechRecognitionService.startListening()
                _uiState.update { it.copy(statusMessage = "Listening...", isProcessing = false) }
            } else {
                Log.d(TAG, "Already listening, stopping listening.")
                speechRecognitionService.stopListening() // Allow tapping again to stop
                _uiState.update { it.copy(statusMessage = "Tap mic to start") } // Reset status
            }
        } else {
            Log.w(TAG, "Cannot start listening - Audio permission required.")
            viewModelScope.launch {
                showError("Audio permission required to use voice commands.")
                // The UI should trigger the permission request itself.
            }
        }
    }

    // Keep startListening for potential internal use, though FAB uses interruptSpeechAndListen
    fun startListening() {
        if (!uiState.value.isListening) {
            if (uiState.value.permissionsGranted[Manifest.permission.RECORD_AUDIO] == true) {
                Log.d(TAG, "Starting ASR listening (via startListening).")
                speechRecognitionService.clearError()
                speechRecognitionService.clearRecognizedText()
                speechRecognitionService.startListening()
                _uiState.update { it.copy(statusMessage = "Listening...", isProcessing = false) }
            } else {
                viewModelScope.launch { showError("Audio permission required to use voice commands.") }
            }
        }
    }

    private fun processLlmInput(text: String) {
        if (text.isBlank()) return
        _uiState.update {
            it.copy(
                isProcessing = true,
                statusMessage = "Thinking..."
            )
        } // Show processing
        viewModelScope.launch {
            val currentStateInfo = generateMapStateInfo()
            Log.d(TAG, "Sending to LLM: '$text' with state: $currentStateInfo")
            val response = llmService.processUserInput(text, currentStateInfo)
            Log.d(TAG, "LLM Response: $response")
            // isProcessing will be handled by the subsequent actions or set to false if no action

            when (response) {
                is LLMResponse.FunctionCall -> {
                    // isProcessing might be set to true again inside handleFunctionCall if pathfinding starts
                    handleFunctionCall(response.functionName, response.parameters)
                }

                is LLMResponse.ClarificationNeeded -> {
                    _uiState.update { it.copy(isProcessing = false) } // Done processing LLM part
                    updateStatus(response.question); speak(response.question)
                }

                is LLMResponse.GeneralResponse -> {
                    _uiState.update { it.copy(isProcessing = false) } // Done processing LLM part
                    updateStatus(response.text); speak(response.text)
                }

                is LLMResponse.ErrorResponse -> {
                    _uiState.update { it.copy(isProcessing = false) } // Done processing LLM part
                    showError(response.message); speak(response.message)
                }
            }
        }
    }

    private fun handleFunctionCall(functionName: String, parameters: Map<String, String>) {
        Log.d(TAG, "Handling function call: $functionName with params: $parameters")
        viewModelScope.launch { // Ensure operations run in view model scope
            when (functionName) {
                "findPath" -> parameters["destinationName"]?.let { handleFindPathRequest(it) } // Already suspend
                    ?: run {
                        speak("Sorry, I didn't catch the destination.")
                        _uiState.update { it.copy(isProcessing = false) }
                    }

                "updateLocation" -> parameters["locationName"]?.let { handleUpdateLocationRequest(it) } // Already suspend
                    ?: run {
                        speak("Sorry, I didn't catch the location name you mentioned.")
                        _uiState.update { it.copy(isProcessing = false) }
                    }

                "getDistance" -> {
                    handleGetDistanceRequest() // Not suspend, finishes quickly
                    _uiState.update { it.copy(isProcessing = false) }
                }

                "localizeUser" -> {
                    handleLocalizeUserRequest() // Not suspend, but triggers async location work
                    // isProcessing state might be managed by isLoadingLocation instead
                    _uiState.update { it.copy(isProcessing = false) } // Assume quick request start
                }
                // REMOVED "findNearest" as LLM now returns "BATHROOM" / "EMERGENCY_EXIT" to findPath
                // "findNearest" -> { ... }
                else -> {
                    Log.w(TAG, "Unknown function call: $functionName")
                    speak("Sorry, I encountered an unexpected request.")
                    _uiState.update { it.copy(isProcessing = false) }
                }
            }
        }
    }

    // *** MODIFIED handleFindPathRequest ***
    private suspend fun handleFindPathRequest(destinationNameOrType: String) {
        val startNodeId = uiState.value.currentLocationNodeId ?: run {
            speak("I need to know your current location first.")
            updateStatus("Please provide your current location.")
            _uiState.update { it.copy(isProcessing = false) } // Clear processing state
            return // No need for qualified return as it's direct return from suspend fun
        }
        val currentMap = uiState.value.airportMap ?: run {
            showError("Map data is not available.")
            speak("I can't calculate a path without map data.")
            _uiState.update { it.copy(isProcessing = false) }
            return
        }
        val startNode = mapNodesById[startNodeId] ?: run {
            // Should not happen if startNodeId is valid, but handle defensively
            showError("Error: Current location node not found in map data.")
            speak("There's an issue finding your current location on the map.")
            _uiState.update { it.copy(isProcessing = false) }
            return
        }

        // Determine the actual destination node
        val actualDestinationNode: Node? = when (destinationNameOrType.uppercase()) {
            "BATHROOM" -> {
                Log.d(TAG, "Finding nearest BATHROOM from ${startNode.id}")
                LocationUtils.findClosestNodeOfType(startNode, NodeType.BATHROOM, currentMap.nodes)
            }

            "EMERGENCY_EXIT" -> {
                Log.d(TAG, "Finding nearest EMERGENCY_EXIT from ${startNode.id}")
                LocationUtils.findClosestNodeOfType(
                    startNode,
                    NodeType.EMERGENCY_EXIT,
                    currentMap.nodes
                )
            }

            else -> {
                // Handle specific named destinations (e.g., "Gate A1", "Restrooms near Duty Free")
                Log.d(TAG, "Finding node for specific name/type: $destinationNameOrType")
                findNodeByNameOrType(destinationNameOrType, currentMap.nodes)
            }
        }

        // Check if a destination node was found
        if (actualDestinationNode == null) {
            val typeName = when (destinationNameOrType.uppercase()) {
                "BATHROOM" -> "bathroom"
                "EMERGENCY_EXIT" -> "emergency exit"
                else -> "'$destinationNameOrType'" // For specific names that weren't found
            }
            speak("Sorry, I couldn't find the nearest $typeName from your current location on the map.")
            updateStatus("Nearest $typeName not found.")
            _uiState.update { it.copy(isProcessing = false) }
            return
        }

        // Proceed with pathfinding using the found specific node
        val destinationNode = actualDestinationNode // Use the resolved node

        // Provide feedback before starting calculation
        val feedbackDestName = when (destinationNameOrType.uppercase()) {
            "BATHROOM", "EMERGENCY_EXIT" -> "the nearest ${
                destinationNameOrType.lowercase().replace('_', ' ')
            } (${destinationNode.name})" // Add specific name found
            else -> destinationNode.name // Use the actual name for specific destinations
        }

        if (startNode.floor != destinationNode.floor) {
            speak("Okay, heading to $feedbackDestName on floor ${destinationNode.floor}.")
        } else {
            speak("Okay, calculating route to $feedbackDestName.")
        }
        // isProcessing should be true before calling findAndSetPath which is async
        _uiState.update { it.copy(isProcessing = true) }
        findAndSetPath(
            startNodeId,
            destinationNode.id
        ) // This function handles setting isProcessing = false on completion/failure
    }

    // *** MODIFIED handleUpdateLocationRequest to use suspend fun return ***
    private suspend fun handleUpdateLocationRequest(locationName: String) {
        val currentMap = uiState.value.airportMap ?: run {
            showError("Map data is not available.")
            _uiState.update { it.copy(isProcessing = false) }
            return // Direct return from suspend fun
        }
        val foundNode = findNodeByNameOrType(locationName, currentMap.nodes) ?: run {
            speak("Sorry, I couldn't find a location matching '$locationName' on the map.")
            updateStatus("Could not update location to '$locationName'.")
            _uiState.update { it.copy(isProcessing = false) }
            return // Direct return
        }

        Log.d(TAG, "User stated location resolved to node: ${foundNode.id} (${foundNode.name})")

        var feedback = ""
        if (foundNode.floor != uiState.value.currentFloor) {
            // Important: Change floor *before* updating node ID if floor changes
            changeFloor(foundNode.floor)
            feedback = "Okay, you are at ${foundNode.name} on floor ${foundNode.floor}."
        } else {
            feedback = "Okay, noted you are at ${foundNode.name}."
        }
        speak(feedback)
        _uiState.update { it.copy(currentLocationNodeId = foundNode.id) } // Update after potential floor change
        updateStatus("Current location updated to: ${foundNode.name}")

        val destId = uiState.value.destinationNodeId
        if (destId != null) {
            if (foundNode.id == destId) {
                speak("You have arrived at your destination: ${foundNode.name}")
                _uiState.update { it.copy(destinationNodeId = null, currentPath = null) }
            } else if (uiState.value.currentPath != null) {
                // Location updated mid-navigation, recalculate
                speak("Location updated. Recalculating route to ${mapNodesById[destId]?.name ?: "destination"}.")
                _uiState.update { it.copy(isProcessing = true) } // Set processing for path recalc
                findAndSetPath(foundNode.id, destId) // This will reset isProcessing
                return // findAndSetPath handles further state updates
            }
        }
        _uiState.update { it.copy(isProcessing = false) } // Reset processing if no path recalc needed
    }


    private fun handleGetDistanceRequest() {
        val path = uiState.value.currentPath
        val currentLocationId = uiState.value.currentLocationNodeId
        if (path == null || path.size < 2 || currentLocationId == null) {
            speak("There is no active route to measure distance for."); return
        }
        val currentIndex = path.indexOfFirst { it.id == currentLocationId }
        if (currentIndex == -1) {
            speak("Your current location is not on the calculated path. Recalculating if needed.");
            // Optionally trigger recalculation if destination exists
            uiState.value.destinationNodeId?.let { destId ->
                viewModelScope.launch {
                    findAndSetPath(currentLocationId, destId)
                }
            }
            return
        }
        val remainingPath = path.subList(currentIndex, path.size)
        val distance = LocationUtils.calculatePathDistance(remainingPath)
        // TODO: Refine distance/steps calculation based on map scale and average step length
        val distanceInSteps =
            (distance / Constants.MOCK_MAP_SCALE_FACTOR).roundToInt() // Example scaling
        speak("You have approximately $distanceInSteps steps remaining.")
        updateStatus("Approx. $distanceInSteps steps left.")
    }

    private fun handleLocalizeUserRequest() {
        val location = uiState.value.currentLocation
        if (location == null && uiState.value.permissionsGranted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            speak("Okay, trying to get your current GPS location first.")
            requestInitialLocation() // This will eventually call handleLocationUpdate -> findAndSetNearestNode
            return // The result will come asynchronously
        } else if (location == null) {
            speak("I need location permission to find where you are.")
            return
        }

        // Location is available, proceed with finding nearest node
        speak("Okay, trying to pinpoint your location on the map.")
        _uiState.update { it.copy(isLoadingLocation = true) } // Show loading while mapping GPS->Node
        findAndSetNearestNode(location) // This will update state and speak result
        _uiState.update { it.copy(isLoadingLocation = false) } // May happen quickly
    }

    // REMOVED handleFindNearestRequest - logic moved into handleFindPathRequest
    // private suspend fun handleFindNearestRequest(nodeType: NodeType) { ... }

    // Note: findAndSetPath now takes specific IDs, determined by handleFindPathRequest
    private fun findAndSetPath(startNodeId: String, destinationNodeId: String) {
        val currentMap = uiState.value.airportMap ?: run {
            Log.e(TAG, "findAndSetPath called with null map")
            _uiState.update { it.copy(isProcessing = false) }
            return
        }
        Log.d(TAG, "Calculating path from $startNodeId to $destinationNodeId")
        updateStatus("Calculating route...")
        // Ensure isProcessing is true when starting async pathfinding
        _uiState.update { it.copy(isProcessing = true) }

        viewModelScope.launch {
            val pathResult = AStar.findPath(startNodeId, destinationNodeId, currentMap)
            if (!pathResult.isNullOrEmpty()) {
                Log.d(TAG, "Path found with ${pathResult.size} nodes.")
                val destNodeName = mapNodesById[destinationNodeId]?.name ?: "destination"
                // Update state *before* speaking instructions
                _uiState.update {
                    it.copy(
                        currentPath = pathResult,
                        destinationNodeId = destinationNodeId,
                        isProcessing = false // Path calculation done
                    )
                }
                updateStatus("Route calculated to $destNodeName.")
                generateAndSpeakInstructions(pathResult) // Speak first step
            } else {
                Log.w(TAG, "Path not found from $startNodeId to $destinationNodeId")
                val destNodeName = mapNodesById[destinationNodeId]?.name ?: "your destination"
                speak("Sorry, I could not find a path to $destNodeName.")
                updateStatus("Could not find path.")
                // Clear path state and reset processing flag
                _uiState.update {
                    it.copy(
                        currentPath = null,
                        destinationNodeId = null,
                        isProcessing = false
                    )
                }
            }
        }
    }

    private fun generateAndSpeakInstructions(path: List<Node>) {
        if (path.size < 2) {
            // Path only has one node (start = end?), or is empty. Should be handled by caller.
            Log.w(TAG, "generateAndSpeakInstructions called with path size < 2")
            // Optionally speak "You have arrived" if path size is 1 and matches destination?
            return
        }
        val startNode = path[0]
        val nextNode = path[1]

        // Check for floor change via stairs/elevator edge property
        val edge = uiState.value.airportMap?.edges?.find {
            (it.from == startNode.id && it.to == nextNode.id) || (it.from == nextNode.id && it.to == startNode.id)
        }
        val stairsInfo = if (edge?.stairs == true) {
            if (nextNode.floor > startNode.floor) " Take the stairs or elevator up to floor ${nextNode.floor}."
            else if (nextNode.floor < startNode.floor) " Take the stairs or elevator down to floor ${nextNode.floor}."
            else "" // Stairs edge but no floor change? Unlikely but possible.
        } else ""

        // Calculate direction based on map coordinates (assuming Y increases upwards)
        val angle = atan2(
            (nextNode.y - startNode.y).toDouble(),
            (nextNode.x - startNode.x).toDouble()
        ) * 180 / Math.PI
        val direction = angleToDirection(angle)

        // Construct instruction
        val instruction = if (stairsInfo.isNotEmpty() && startNode.type in listOf(
                NodeType.STAIRS_ELEVATOR,
                NodeType.CONNECTION
            )
        ) {
            // If starting at stairs/elevator and the edge involves floor change
            stairsInfo.trim() + " Then head towards ${nextNode.name}."
        } else {
            "Head $direction towards ${nextNode.name}.$stairsInfo"
        }

        speak(instruction)
        updateStatus("Next: $instruction")
    }

    private fun speak(text: String) {
        if (text.isNotBlank()) {
            Log.d(TAG, "TTS Request: '$text'")
            // Consider stopping previous speech before starting new one if needed
            // ttsService.stop() // Uncomment if you want immediate interruption
            ttsService.speak(text)
        }
    }

    private fun updateStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private suspend fun showError(message: String) {
        Log.e(TAG, "Error: $message")
        snackbarHostState.showSnackbar(message)
        // Optionally speak the error too, depending on severity/context
        // speak("Error: $message")
    }

    // *** Refined findNodeByNameOrType ***
    private fun findNodeByNameOrType(nameOrType: String, nodes: List<Node>): Node? {
        val lowerQuery = nameOrType.lowercase().trim()
        val upperQuery = nameOrType.uppercase().trim() // For potential ID or Type match

        // 1. Exact ID Match (case sensitive typically)
        mapNodesById[nameOrType]?.let { return it } // Use the provided name directly as potential ID

        // 2. Exact Name Match (case insensitive)
        nodes.find { it.name.equals(lowerQuery, ignoreCase = true) }?.let { return it }

        // 3. Gate Name Match (specific format)
        val gateRegex = """(gate\s?)?([a-zA-Z])\s?(\d+)""".toRegex(RegexOption.IGNORE_CASE)
        gateRegex.matchEntire(lowerQuery)?.let { match ->
            val gateName = "Gate ${match.groupValues[2].uppercase()}${match.groupValues[3]}"
            return nodes.find { it.name.equals(gateName, ignoreCase = true) }
        }
        // Check if the original input was already the canonical Gate name
        nodes.find { it.name.equals(nameOrType, ignoreCase = true) && it.type == NodeType.GATE }
            ?.let { return it }


        // 4. Check if it's a known NodeType enum name (case insensitive)
        // Ensure NodeType enum values are uppercase if comparing with upperQuery
        try {
            val nodeType = NodeType.valueOf(upperQuery)
            // If it matches a type, should we return the FIRST match? Or null?
            // Returning null might be safer if the user meant a specific instance.
            // The "nearest" logic is handled separately in handleFindPathRequest.
            // For now, let's assume if they use a type name, they mean *any* instance.
            // Log a warning if returning the first match for a type.
            Log.w(
                TAG,
                "Query '$nameOrType' matched NodeType $nodeType. Returning first node found with this type."
            )
            return nodes.find { it.type == nodeType }
        } catch (e: Exception) { /* Ignore if not a valid NodeType name */
        }

        // 5. Partial Name Contains Match (case insensitive) - Last resort, might be ambiguous
        // Only return if unambiguous or clearly intended?
        // Example: "Duty" -> "Duty Free Shop" (good)
        // Example: "Area" -> "Check-in Area A" (might be ok if only one)
        // Consider relevance score or length of match if multiple contain the query.
        nodes.filter { it.name.lowercase().contains(lowerQuery) }.minByOrNull { it.name.length }
            ?.let {
                Log.d(TAG, "Partial match found for '$nameOrType': ${it.name}")
                return it
            } // Find shortest name containing the query as heuristic


        // 6. No Match Found
        Log.w(TAG, "Could not find node matching name or type: $nameOrType")
        return null
    }


    private fun generateMapStateInfo(): MapStateInfo = MapStateInfo(
        currentKnownLocationId = uiState.value.currentLocationNodeId,
        currentDestinationId = uiState.value.destinationNodeId,
        userFlightGate = uiState.value.userFlightGate,
        isPathActive = !uiState.value.currentPath.isNullOrEmpty()
    )

    fun selectMap(newMapId: String) {
        if (newMapId != uiState.value.selectedMapId) {
            Log.i(TAG, "Map selection changed to: $newMapId")
            _uiState.update { currentState ->
                currentState.copy(selectedMapId = newMapId)
            }
            loadMapData(newMapId)
        } else {
            Log.d(TAG, "Map $newMapId already selected.")
        }
    }

    fun changeFloor(newFloor: Int) {
        val currentMap = uiState.value.airportMap ?: return
        val maxFloor = currentMap.nodes.maxOfOrNull { it.floor } ?: 1
        val minFloor = currentMap.nodes.minOfOrNull { it.floor } ?: 1

        if (newFloor >= minFloor && newFloor <= maxFloor && newFloor != uiState.value.currentFloor) {
            Log.d(TAG, "Changing floor view to $newFloor")
            _uiState.update {
                it.copy(
                    currentFloor = newFloor,
                )
            }
            updateStatus("Switched to Floor $newFloor. Tap mic if you need help.")
        }
    }
    
    private fun angleToDirection(angle: Double): String {
        // Ensure angle is within -180 to 180
        val normalizedAngle = (angle + 180.0) % 360.0 - 180.0
        return when (normalizedAngle) {
            in -22.5..22.5 -> "right" // East
            in 22.5..67.5 -> "up and right" // Northeast
            in 67.5..112.5 -> "up" // North
            in 112.5..157.5 -> "up and left" // Northwest
            in 157.5..180.0, in -180.0..-157.5 -> "left" // West
            in -157.5..-112.5 -> "down and left" // Southwest
            in -112.5..-67.5 -> "down" // South
            in -67.5..-22.5 -> "down and right" // Southeast
            else -> "nearby" // Should not happen with normalization
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared called.")
        locationUpdateJob?.cancel()
        locationService.stopLocationUpdates() // Ensure updates are stopped
        connectivityService.unregisterCallback()
        ttsService.shutdown()
        speechRecognitionService.destroy()
    }

    fun selectNode(node: Node) {
        Log.d(TAG, "Node selected: ${node.id} (${node.name})")
        _uiState.update { it.copy(selectedNodeInfo = node) }
    }

    fun dismissNodeInfo() {
        Log.d(TAG, "Dismissing node info dialog.")
        _uiState.update { it.copy(selectedNodeInfo = null) }
    }

    companion object {
        fun provideFactory(context: Context, username: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(MapViewModel::class.java)) {
                        val appContext = context.applicationContext // Use application context
                        val mapRepo = MapRepositoryImpl(AirportMapDataSource(appContext))
                        val ticketRepo = MockTicketRepository()
                        val connectivitySvc = ConnectivityService(appContext)
                        val locationSvc = LocationService(appContext)
                        val ttsSvc = TextToSpeechService(appContext)
                        val speechSvc = SpeechRecognitionService(appContext)
                        val llmSvc = MockLlmService()
                        return MapViewModel(
                            mapRepo,
                            ticketRepo,
                            connectivitySvc,
                            locationSvc,
                            ttsSvc,
                            speechSvc,
                            llmSvc,
                            username
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
    }
}

