package com.nonstac.airportguide.ui.screens.map

import android.Manifest
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nonstac.airportguide.data.model.*
import com.nonstac.airportguide.data.repository.MapRepository
import com.nonstac.airportguide.data.repository.MapRepositoryImpl
import com.nonstac.airportguide.data.repository.MockTicketRepository
import com.nonstac.airportguide.data.repository.TicketRepository
import com.nonstac.airportguide.data.local.AirportMapDataSource
import com.nonstac.airportguide.data.model.Node
import com.nonstac.airportguide.service.*
import com.nonstac.airportguide.util.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val selectedNodeInfo: Node? = null
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

    init {
        Log.d("MapViewModel", "Initializing for user: $username")
        loadInitialData()
        observeServices()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMap = true) }
            // Load Map
            mapRepository.getAirportMap(Constants.DEFAULT_AIRPORT_CODE)
                .onSuccess { map ->
                    Log.d("MapViewModel", "Map loaded: ${map.airportName}")
                    mapNodesById = map.nodes.associateBy { node -> node.id }
                    _uiState.update { it.copy(airportMap = map, isLoadingMap = false) }
                    // Try to get initial location after map load
                    requestInitialLocation()
                }.onFailure { error ->
                    Log.e("MapViewModel", "Failed to load map", error)
                    _uiState.update { it.copy(isLoadingMap = false) }
                    showError("Failed to load airport map: ${error.message}")
                }

            // Fetch user's gate (find first relevant upcoming flight)
            ticketRepository.getBoughtTickets(username)
                .onSuccess { tickets ->
                    val relevantTicket = tickets.firstOrNull { it.status == TicketStatus.BOUGHT /* Add time check if needed */ }
                    Log.d("MapViewModel", "Found relevant ticket: ${relevantTicket?.flightNumber}, Gate: ${relevantTicket?.gate}")
                    _uiState.update { it.copy(userFlightGate = relevantTicket?.gate) }
                }.onFailure { error ->
                    Log.w("MapViewModel", "Could not fetch user tickets/gate: ${error.message}")
                }
        }
    }

    private fun observeServices() {
        // Connectivity
        connectivityService.isConnected
            .onEach { isConnected -> _uiState.update { it.copy(isBlackout = !isConnected) } }
            .launchIn(viewModelScope)

        // ASR Listening State
        speechRecognitionService.isListening
            .onEach { listening -> _uiState.update { it.copy(isListening = listening) } }
            .launchIn(viewModelScope)

        // ASR Results
        speechRecognitionService.recognizedText
            .filterNotNull() // Only process non-null results
            .onEach { text ->
                Log.d("MapViewModel", "ASR Result: $text")
                _uiState.update { it.copy(statusMessage = "Processing: '$text'") }
                processLlmInput(text)
                speechRecognitionService.clearRecognizedText() // Consume the text
            }
            .launchIn(viewModelScope)

        // ASR Errors
        speechRecognitionService.error
            .filterNotNull()
            .onEach { errorMsg ->
                Log.e("MapViewModel", "ASR Error: $errorMsg")
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
            .onEach { location ->
                if (location != null) {
                    handleLocationUpdate(location)
                }
            }
            .launchIn(viewModelScope)
    }

    fun onPermissionResult(permission: String, granted: Boolean) {
        Log.d("MapViewModel", "Permission result: $permission, Granted: $granted")
        _uiState.update {
            it.copy(permissionsGranted = it.permissionsGranted + (permission to granted))
        }

        if (permission == Manifest.permission.ACCESS_FINE_LOCATION) {
            if (granted) {
                startLocationUpdates() // This internally uses viewModelScope
                requestInitialLocation() // This internally uses viewModelScope
            } else {
                // Launch coroutine for suspend function showError
                viewModelScope.launch {
                    showError("Location permission is required for navigation assistance.")
                }
            }
        }

        if (permission == Manifest.permission.RECORD_AUDIO && !granted) {
            // Launch coroutine for suspend function showError
            viewModelScope.launch {
                showError("Audio permission is required for voice commands.")
            }
        }
    }


    private fun requestInitialLocation() {
        if (uiState.value.permissionsGranted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoadingLocation = true) }
                val location = locationService.getLastKnownLocationDirect()
                Log.d("MapViewModel", "Initial location fetched: $location")
                if (location != null) {
                    handleLocationUpdate(location)
                } else {
                    // If no last known location, wait for updates
                    _uiState.update { it.copy(isLoadingLocation = false, statusMessage = "Trying to find your location...") }
                }
                _uiState.update { it.copy(isLoadingLocation = false) }
            }
        } else {
            Log.w("MapViewModel", "Location permission not granted, cannot request initial location.")
            _uiState.update { it.copy(statusMessage = "Location permission needed.") }
        }
    }

    private fun startLocationUpdates() {
        if (locationUpdateJob?.isActive == true) return // Already running
        if (uiState.value.permissionsGranted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            Log.d("MapViewModel", "Starting location updates flow.")
            locationUpdateJob = locationService.requestLocationUpdates()
                // Using .catch is important for flow cancellation/errors
                .catch { e ->
                    Log.e("MapViewModel", "Location updates error", e)
                    showError("Location updates failed: ${e.message}")
                    _uiState.update { it.copy(isLoadingLocation = false) }
                }
                .launchIn(viewModelScope) // Collects implicitly
        } else {
            Log.w("MapViewModel", "Cannot start location updates, permission missing.")
        }
    }


    private fun handleLocationUpdate(location: Location) {
        _uiState.update { it.copy(currentLocation = location, isLoadingLocation = false) }
        findAndSetNearestNode(location)
    }

    private fun findAndSetNearestNode(location: Location) {
        val currentMap = uiState.value.airportMap ?: return
        val currentFloor = uiState.value.currentFloor // Use the currently viewed floor

        // --- VERY IMPORTANT HACKATHON SIMPLIFICATION ---
        // Real indoor positioning is complex. Here we simulate it by finding
        // the closest node on the *currently viewed floor* based on GPS (which usually doesn't have floor info).
        // We assume GPS gives X,Y usable for the map's coordinate system.
        // In reality, you'd use Wifi/Beacon fingerprinting or other indoor methods.
        // We also need to translate GPS lat/lon to map X/Y - skipping that translation here for brevity.
        // Let's pretend location.latitude maps to map Y and location.longitude maps to map X.

        // Find node on the *current floor* closest to the (mock) location coordinates
        // Using 0,0 as placeholder for GPS to Map XY conversion - REPLACE with actual calculation if possible
        val mockMapX = location.longitude // Placeholder
        val mockMapY = location.latitude // Placeholder

        val closestNode = LocationUtils.findClosestNode(
            mockMapX, // Provide X coordinate derived from GPS/Indoor system
            mockMapY, // Provide Y coordinate derived from GPS/Indoor system
            currentFloor,
            currentMap.nodes
        )

        if (closestNode != null && closestNode.id != uiState.value.currentLocationNodeId) {
            Log.d("MapViewModel", "GPS mapped to nearest node: ${closestNode.id} (${closestNode.name}) on floor $currentFloor")
            _uiState.update { it.copy(currentLocationNodeId = closestNode.id) }
            // Optionally update status message
            // If a path is active, check if user is still on path or recalculate distance?
            if (uiState.value.currentPath != null) {
                // Check if the new node is part of the existing path
                val nodeIndexInPath = uiState.value.currentPath?.indexOfFirst { it.id == closestNode.id }
                if (nodeIndexInPath != null && nodeIndexInPath != -1) {
                    // User is on the path, update progress
                    speak("Okay, you are now at ${closestNode.name}.")
                    // Update the remaining path visually if desired (e.g. dimming past segments)
                } else {
                    // User deviated or reached destination via different node?
                    speak("It seems you are now at ${closestNode.name}. Recalculating if needed.")
                    // Potentially recalculate path from new location if destination still exists
                    val destId = uiState.value.destinationNodeId
                    if (destId != null) {
                        findAndSetPath(closestNode.id, destId)
                    }
                }
            } else {
                speak("Detected your location near ${closestNode.name}.")
                updateStatus("Current location: ${closestNode.name}")
            }
        } else if (closestNode == null) {
            Log.w("MapViewModel", "Could not find a close node on floor $currentFloor for the current location.")
            // Don't clear the existing node ID unless we are certain
        }
    }


    fun startListening() {
        if (!uiState.value.isListening) {
            if (uiState.value.permissionsGranted[Manifest.permission.RECORD_AUDIO] == true) {
                Log.d("MapViewModel", "Starting ASR listening.")
                // Assuming speechRecognitionService.startListening() is NOT suspend.
                // If it IS suspend, it should also be inside the launch block.
                speechRecognitionService.startListening()
                _uiState.update { it.copy(statusMessage = "Listening...", isProcessing = false) }
            } else {
                // Launch coroutine for suspend function showError
                viewModelScope.launch {
                    showError("Audio permission required to use voice commands.")
                }
                // Trigger permission request from UI again if needed (UI logic)
            }
        }
    }

    private fun processLlmInput(text: String) {
        if (text.isBlank()) return
        _uiState.update { it.copy(isProcessing = true) }
        viewModelScope.launch {
            val currentStateInfo = generateMapStateInfo()
            Log.d("MapViewModel", "Sending to LLM: '$text' with state: $currentStateInfo")
            val response = llmService.processUserInput(text, currentStateInfo)
            Log.d("MapViewModel", "LLM Response: $response")

            _uiState.update { it.copy(isProcessing = false) } // Stop processing indicator

            when (response) {
                is LLMResponse.FunctionCall -> handleFunctionCall(response.functionName, response.parameters)
                is LLMResponse.ClarificationNeeded -> {
                    updateStatus(response.question)
                    speak(response.question)
                }
                is LLMResponse.GeneralResponse -> {
                    updateStatus(response.text)
                    speak(response.text)
                }
                is LLMResponse.ErrorResponse -> {
                    showError(response.message)
                    speak(response.message)
                }
            }
        }
    }

    private fun handleFunctionCall(functionName: String, parameters: Map<String, String>) {
        Log.d("MapViewModel", "Handling function call: $functionName with params: $parameters")
        viewModelScope.launch { // Ensure calls run in scope
            when (functionName) {
                "findPath" -> {
                    val destinationName = parameters["destinationName"]
                    if (destinationName != null) {
                        handleFindPathRequest(destinationName)
                    } else {
                        speak("Sorry, I didn't catch the destination.")
                    }
                }
                "updateLocation" -> {
                    val locationName = parameters["locationName"]
                    if (locationName != null) {
                        handleUpdateLocationRequest(locationName)
                    } else {
                        speak("Sorry, I didn't catch the location name you mentioned.")
                    }
                }
                "getDistance" -> handleGetDistanceRequest()
                "localizeUser" -> handleLocalizeUserRequest()
                // Add cases for findNearestBathroom, findNearestExit etc. if LLM outputs those
                "findNearest" -> { // Example for a generic nearest function
                    val typeString = parameters["type"] // e.g., "BATHROOM", "EXIT"
                    val type = try { NodeType.valueOf(typeString ?: "") } catch (e: Exception) { null }
                    if (type != null) {
                        handleFindNearestRequest(type)
                    } else {
                        speak("Sorry, I don't know how to find the nearest item of that type.")
                    }
                }
                else -> {
                    Log.w("MapViewModel", "Unknown function call: $functionName")
                    speak("Sorry, I encountered an unexpected request.")
                }
            }
        }
    }

    // --- Action Handlers ---

    private suspend fun handleFindPathRequest(destinationName: String) {
        val startNodeId = uiState.value.currentLocationNodeId
        val currentMap = uiState.value.airportMap

        if (startNodeId == null) {
            speak("I need to know your current location first. Can you tell me where you are or use the 'localize me' command?")
            updateStatus("Please provide your current location.")
            return
        }
        if (currentMap == null) {
            showError("Map data is not available.")
            speak("I can't calculate a path without map data.")
            return
        }

        // Find destination node ID (more robust matching needed)
        val destinationNode = findNodeByNameOrType(destinationName, currentMap.nodes)

        if (destinationNode == null) {
            speak("Sorry, I couldn't find '$destinationName' on the map.")
            updateStatus("Destination '$destinationName' not found.")
            return
        }

        // Check if destination is on a different floor and if path requires floor change
        val startNode = mapNodesById[startNodeId]
        if (startNode?.floor != destinationNode.floor) {
            Log.d("MapViewModel", "Destination is on floor ${destinationNode.floor}, current floor is ${startNode?.floor}")
            // Pathfinding should handle this via stair edges, but inform user.
            speak("Okay, heading to ${destinationNode.name} on floor ${destinationNode.floor}.")
        } else {
            speak("Okay, calculating route to ${destinationNode.name}.")
        }


        findAndSetPath(startNodeId, destinationNode.id)
    }

    private fun handleUpdateLocationRequest(locationName: String) {
        val currentMap = uiState.value.airportMap ?: return
        val foundNode = findNodeByNameOrType(locationName, currentMap.nodes)

        if (foundNode != null) {
            Log.d("MapViewModel", "User stated location resolved to node: ${foundNode.id} (${foundNode.name})")
            // Check if floor matches current floor view, update floor view if needed?
            if (foundNode.floor != uiState.value.currentFloor) {
                changeFloor(foundNode.floor) // Switch view to the stated location's floor
                speak("Okay, you are at ${foundNode.name} on floor ${foundNode.floor}.")
            } else {
                speak("Okay, noted you are at ${foundNode.name}.")
            }
            _uiState.update { it.copy(currentLocationNodeId = foundNode.id) }
            updateStatus("Current location updated to: ${foundNode.name}")

            // If a path was active, recalculate distance or check if destination reached
            val destId = uiState.value.destinationNodeId
            if (destId != null) {
                if (foundNode.id == destId) {
                    speak("You have arrived at your destination: ${foundNode.name}")
                    _uiState.update { it.copy(destinationNodeId = null, currentPath = null) } // Clear path
                } else if (uiState.value.currentPath != null) {
                    // Recalculate remaining path/distance? Or just let GPS handle next update.
                    findAndSetPath(foundNode.id, destId) // Recalculate path from new confirmed spot
                }
            }
        } else {
            speak("Sorry, I couldn't find a location matching '$locationName' on the map.")
            updateStatus("Could not update location to '$locationName'.")
        }
    }

    private fun handleGetDistanceRequest() {
        val path = uiState.value.currentPath
        val currentLocationId = uiState.value.currentLocationNodeId

        if (path == null || path.size < 2 || currentLocationId == null) {
            speak("There is no active route to measure distance for.")
            return
        }

        val currentIndex = path.indexOfFirst { it.id == currentLocationId }
        if (currentIndex == -1) {
            speak("Your current location is not on the calculated path. Cannot calculate remaining distance.")
            return
        }

        val remainingPath = path.subList(currentIndex, path.size)
        val distance = LocationUtils.calculatePathDistance(remainingPath)
        val distanceInSteps = (distance / 25).roundToInt() // Rough estimate: 1 map unit = 25 'steps'? Adjust this scale factor!

        speak("You have approximately $distanceInSteps steps remaining.")
        updateStatus("Approx. $distanceInSteps steps left.")
    }

    private fun handleLocalizeUserRequest() {
        val location = uiState.value.currentLocation
        if (location == null) {
            speak("I don't have your current GPS location. Please ensure location services are enabled and permissions granted.")
            requestInitialLocation() // Try to get it again
            return
        }
        speak("Okay, trying to pinpoint your location on the map based on GPS.")
        // findAndSetNearestNode already does the core logic, just trigger it explicitly
        findAndSetNearestNode(location)
        // The result will be spoken by findAndSetNearestNode's logic
    }

    private suspend fun handleFindNearestRequest(nodeType: NodeType) {
        val startNodeId = uiState.value.currentLocationNodeId
        val currentMap = uiState.value.airportMap

        if (startNodeId == null) {
            speak("I need to know your current location first.")
            return
        }
        if (currentMap == null) {
            showError("Map data is not available.")
            return
        }
        val startNode = mapNodesById[startNodeId] ?: return

        val nearestNode = LocationUtils.findClosestNodeOfType(startNode, nodeType, currentMap.nodes)

        if (nearestNode != null) {
            speak("The nearest ${nodeType.name.lowercase().replace('_',' ')} is ${nearestNode.name}. Starting route.")
            findAndSetPath(startNodeId, nearestNode.id)
        } else {
            speak("Sorry, I couldn't find any ${nodeType.name.lowercase().replace('_',' ')} on the map.")
            updateStatus("No ${nodeType.name.lowercase().replace('_',' ')} found.")
        }
    }


    // --- Pathfinding and Navigation ---

    private fun findAndSetPath(startNodeId: String, destinationNodeId: String) {
        val currentMap = uiState.value.airportMap ?: return
        Log.d("MapViewModel", "Calculating path from $startNodeId to $destinationNodeId")
        updateStatus("Calculating route...")
        _uiState.update { it.copy(isProcessing = true) } // Show processing for pathfinding

        viewModelScope.launch {
            val pathResult = AStar.findPath(startNodeId, destinationNodeId, currentMap)
            if (pathResult != null && pathResult.isNotEmpty()) {
                Log.d("MapViewModel", "Path found with ${pathResult.size} nodes.")
                _uiState.update {
                    it.copy(
                        currentPath = pathResult,
                        destinationNodeId = destinationNodeId,
                        isProcessing = false
                    )
                }
                updateStatus("Route calculated to ${mapNodesById[destinationNodeId]?.name ?: "destination"}.")
                generateAndSpeakInstructions(pathResult)
            } else {
                Log.w("MapViewModel", "Path not found from $startNodeId to $destinationNodeId")
                _uiState.update { it.copy(currentPath = null, destinationNodeId = null, isProcessing = false) } // Clear path/destination if failed
                speak("Sorry, I could not find a path to ${mapNodesById[destinationNodeId]?.name ?: "your destination"}.")
                updateStatus("Could not find path.")
            }
        }
    }

    private fun generateAndSpeakInstructions(path: List<Node>) {
        if (path.size < 2) return

        val startNode = path[0]
        val nextNode = path[1]
        val startOffset = startNode.toVec()
        val nextOffset = nextNode.toVec()

        // Calculate direction
        val dx = nextOffset.x - startOffset.x
        val dy = nextOffset.y - startOffset.y // Y might be inverted depending on map coordinates
        val angle = atan2(dy.toDouble(), dx.toDouble()) * 180 / Math.PI
        val direction = angleToDirection(angle)

        // Check for stairs
        val edge = uiState.value.airportMap?.edges?.find { (it.from == startNode.id && it.to == nextNode.id) || (it.from == nextNode.id && it.to == startNode.id) }
        val stairsInfo = if (edge?.stairs == true) {
            // Check floor change direction
            if (nextNode.floor > startNode.floor) " Take the stairs or elevator up." else " Take the stairs or elevator down."
        } else ""

        val instruction = "Head $direction towards ${nextNode.name}.$stairsInfo"
        speak(instruction)
        updateStatus("Next: $instruction")
    }


    // --- Utility Functions ---

    private fun speak(text: String) {
        if (text.isNotBlank()) {
            Log.d("MapViewModel", "TTS Request: '$text'")
            ttsService.speak(text)
        }
    }

    private fun updateStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private suspend fun showError(message: String) {
        Log.e("MapViewModel", "Error: $message")
        snackbarHostState.showSnackbar(message)
    }

    private fun findNodeByNameOrType(nameOrType: String, nodes: List<Node>): Node? {
        val lowerQuery = nameOrType.lowercase().trim()

        // 1. Exact ID match
        val byId = mapNodesById[nameOrType.uppercase()]
        if (byId != null) return byId

        // 2. Exact Name match (case insensitive)
        var found = nodes.find { it.name.equals(lowerQuery, ignoreCase = true) }
        if (found != null) return found

        // 3. Partial Name match (contains)
        found = nodes.find { it.name.lowercase().contains(lowerQuery) }
        if (found != null) return found

        // 4. Type match (e.g., "bathroom", "exit", "gate a2")
        try {
            val nodeType = NodeType.valueOf(lowerQuery.uppercase())
            // Find closest of this type? Requires current location. For now, just return first?
            // This part needs context - if user says "go to bathroom", we need handleFindNearest.
            // If user says "I am at the bathroom", we might find the first one matching.
            return nodes.find { it.type == nodeType } // Return first match for now
        } catch (e: IllegalArgumentException) { /* Not a valid type */ }

        // 5. Gate matching (e.g., "Gate A5", "a 5", "gate b10")
        val gateRegex = """(gate\s?)?([a-zA-Z])\s?(\d+)""".toRegex()
        gateRegex.matchEntire(lowerQuery)?.let { match ->
            val letter = match.groupValues[2].uppercase()
            val number = match.groupValues[3]
            val gateId = "GATE_$letter$number" // Assuming ID format
            val gateName = "Gate $letter$number" // Assuming Name format
            return nodes.find { it.id.equals(gateId, ignoreCase = true) || it.name.equals(gateName, ignoreCase = true) }
        }


        return null // Not found
    }


    private fun generateMapStateInfo(): MapStateInfo {
        val state = uiState.value
        return MapStateInfo(
            currentKnownLocationId = state.currentLocationNodeId,
            currentDestinationId = state.destinationNodeId,
            userFlightGate = state.userFlightGate, // Pass the fetched gate info
            isPathActive = state.currentPath != null && state.currentPath.isNotEmpty()
        )
    }

    fun changeFloor(newFloor: Int) {
        val maxFloor = uiState.value.airportMap?.nodes?.maxOfOrNull { it.floor } ?: 1
        val minFloor = uiState.value.airportMap?.nodes?.minOfOrNull { it.floor } ?: 1
        if (newFloor in minFloor..maxFloor && newFloor != uiState.value.currentFloor) {
            Log.d("MapViewModel", "Changing floor view to $newFloor")
            _uiState.update { it.copy(currentFloor = newFloor) }
            // When floor changes, re-evaluate the nearest node based on GPS if available
            uiState.value.currentLocation?.let { loc -> findAndSetNearestNode(loc) }
            // Clear path/destination if they are not relevant to the new floor? Optional.
            // _uiState.update { it.copy(currentPath = null, destinationNodeId = null) }
        }
    }


    // Simple vector representation for angle calculation
    data class Vec(val x: Float, val y: Float)
    private fun Node.toVec(): Vec = Vec(this.x.toFloat(), this.y.toFloat())

    // Convert angle to human-readable direction
    private fun angleToDirection(angle: Double): String {
        return when (angle) {
            in -22.5..22.5 -> "right" // East
            in 22.5..67.5 -> "diagonally up and right" // Northeast
            in 67.5..112.5 -> "straight up" // North
            in 112.5..157.5 -> "diagonally up and left" // Northwest
            in 157.5..180.0, in -180.0..-157.5 -> "left" // West
            in -157.5..-112.5 -> "diagonally down and left" // Southwest
            in -112.5..-67.5 -> "straight down" // South
            in -67.5..-22.5 -> "diagonally down and right" // Southeast
            else -> "in an unknown direction"
        }
    }


    override fun onCleared() {
        super.onCleared()
        Log.d("MapViewModel", "onCleared called.")
        locationUpdateJob?.cancel()
        connectivityService.unregisterCallback()
        ttsService.shutdown()
        speechRecognitionService.destroy()
    }

    fun selectNode(node: Node) {
        Log.d("MapViewModel", "Node selected: ${node.id} (${node.name})")
        _uiState.update { it.copy(selectedNodeInfo = node) }
    }

    fun dismissNodeInfo() {
        Log.d("MapViewModel", "Dismissing node info dialog.")
        _uiState.update { it.copy(selectedNodeInfo = null) }
    }

    // --- Factory for instantiation ---
    companion object {
        fun provideFactory(
            context: Context,
            username: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MapViewModel::class.java)) {
                    // Instantiate dependencies here if not using DI
                    val mapRepo = MapRepositoryImpl(AirportMapDataSource(context))
                    val ticketRepo = MockTicketRepository() // Use non-mock if available
                    val connectivitySvc = ConnectivityService(context)
                    val locationSvc = LocationService(context)
                    val ttsSvc = TextToSpeechService(context)
                    val speechSvc = SpeechRecognitionService(context)
                    val llmSvc = MockLlmService()

                    return MapViewModel(
                        mapRepository = mapRepo,
                        ticketRepository = ticketRepo,
                        connectivityService = connectivitySvc,
                        locationService = locationSvc,
                        ttsService = ttsSvc,
                        speechRecognitionService = speechSvc,
                        llmService = llmSvc,
                        username = username
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}
