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
import com.nonstac.airportguide.data.model.*
import com.nonstac.airportguide.data.repository.MapRepository
import com.nonstac.airportguide.data.repository.MapRepositoryImpl
import com.nonstac.airportguide.data.repository.MockTicketRepository
import com.nonstac.airportguide.data.repository.TicketRepository
import com.nonstac.airportguide.service.*
import com.nonstac.airportguide.util.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.roundToInt

data class MapUiState(
    val isLoadingMap: Boolean = true,
    val isLoadingLocation: Boolean = false,
    val isProcessing: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val airportMap: AirportMap? = null,
    val currentLocation: Location? = null,
    val currentLocationNodeId: String? = null,
    val destinationNodeId: String? = null,
    val currentPath: List<Node>? = null,
    val statusMessage: String? = "Tap the mic to start",
    val isBlackout: Boolean = false,
    val userFlightGate: String? = null,
    val currentFloor: Int = 1,
    val permissionsGranted: Map<String, Boolean> = mapOf(
        Manifest.permission.ACCESS_FINE_LOCATION to false,
        Manifest.permission.RECORD_AUDIO to false
    ),
    val selectedNodeInfo: Node? = null
)

class MapViewModel(
    private val mapRepository: MapRepository,
    private val ticketRepository: TicketRepository,
    private val connectivityService: ConnectivityService,
    private val locationService: LocationService,
    private val ttsService: TextToSpeechService, // For stopping speech
    private val speechRecognitionService: SpeechRecognitionService,
    private val llmService: MockLlmService,
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
        loadInitialData()
        observeServices()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMap = true) }
            mapRepository.getAirportMap(Constants.DEFAULT_AIRPORT_CODE)
                .onSuccess { map ->
                    Log.d(TAG, "Map loaded: ${map.airportName}")
                    mapNodesById = map.nodes.associateBy { node -> node.id }
                    _uiState.update { it.copy(airportMap = map, isLoadingMap = false) }
                    requestInitialLocation()
                }.onFailure { error ->
                    Log.e(TAG, "Failed to load map", error)
                    _uiState.update { it.copy(isLoadingMap = false) }
                    showError("Failed to load airport map: ${error.message}")
                }

            ticketRepository.getBoughtTickets(username)
                .onSuccess { tickets ->
                    val relevantTicket = tickets.firstOrNull { it.status == TicketStatus.BOUGHT }
                    Log.d(TAG, "Found relevant ticket: ${relevantTicket?.flightNumber}, Gate: ${relevantTicket?.gate}")
                    _uiState.update { it.copy(userFlightGate = relevantTicket?.gate) }
                }.onFailure { error ->
                    Log.w(TAG, "Could not fetch user tickets/gate: ${error.message}")
                }
        }
    }

    private fun observeServices() {
        connectivityService.isConnected
            .onEach { isConnected -> _uiState.update { it.copy(isBlackout = !isConnected) } }
            .launchIn(viewModelScope)

        speechRecognitionService.isListening
            .onEach { listening -> _uiState.update { it.copy(isListening = listening) } }
            .launchIn(viewModelScope)

        speechRecognitionService.recognizedText
            .filterNotNull()
            .onEach { text ->
                Log.d(TAG, "ASR Result: $text")
                _uiState.update { it.copy(statusMessage = "Processing: '$text'") }
                processLlmInput(text)
                speechRecognitionService.clearRecognizedText()
            }
            .launchIn(viewModelScope)

        speechRecognitionService.error
            .filterNotNull()
            .onEach { errorMsg ->
                Log.e(TAG, "ASR Error: $errorMsg")
                showError("Speech recognition error: $errorMsg")
                _uiState.update { it.copy(isProcessing = false, isListening = false) }
                speechRecognitionService.clearError()
            }
            .launchIn(viewModelScope)

        ttsService.isSpeaking
            .onEach { speaking -> _uiState.update { it.copy(isSpeaking = speaking) } }
            .launchIn(viewModelScope)

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
        if (permission == Manifest.permission.RECORD_AUDIO && !granted) {
            viewModelScope.launch { showError("Audio permission is required for voice commands.") }
        }
    }

    private fun requestInitialLocation() {
        if (uiState.value.permissionsGranted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoadingLocation = true) }
                val location = locationService.getLastKnownLocationDirect()
                Log.d(TAG, "Initial location fetched: $location")
                location?.let { handleLocationUpdate(it) }
                    ?: _uiState.update { it.copy(isLoadingLocation = false, statusMessage = "Trying to find your location...") }
                _uiState.update { it.copy(isLoadingLocation = false) }
            }
        } else {
            Log.w(TAG, "Location permission not granted, cannot request initial location.")
            _uiState.update { it.copy(statusMessage = "Location permission needed.") }
        }
    }

    private fun startLocationUpdates() {
        if (locationUpdateJob?.isActive == true || uiState.value.permissionsGranted[Manifest.permission.ACCESS_FINE_LOCATION] != true) return
        Log.d(TAG, "Starting location updates flow.")
        locationUpdateJob = locationService.requestLocationUpdates()
            .catch { e ->
                Log.e(TAG, "Location updates error", e)
                showError("Location updates failed: ${e.message}")
                _uiState.update { it.copy(isLoadingLocation = false) }
            }
            .launchIn(viewModelScope)
    }

    private fun handleLocationUpdate(location: Location) {
        _uiState.update { it.copy(currentLocation = location, isLoadingLocation = false) }
        findAndSetNearestNode(location)
    }

    private fun findAndSetNearestNode(location: Location) {
        val currentMap = uiState.value.airportMap ?: return
        val currentFloor = uiState.value.currentFloor
        val mockMapX = location.longitude // Placeholder for real projection
        val mockMapY = location.latitude  // Placeholder for real projection

        val closestNode = LocationUtils.findClosestNode(mockMapX, mockMapY, currentFloor, currentMap.nodes)

        if (closestNode != null && closestNode.id != uiState.value.currentLocationNodeId) {
            Log.d(TAG, "GPS mapped to nearest node: ${closestNode.id} (${closestNode.name}) on floor $currentFloor")
            _uiState.update { it.copy(currentLocationNodeId = closestNode.id) }

            if (uiState.value.currentPath != null) {
                val nodeIndexInPath = uiState.value.currentPath?.indexOfFirst { it.id == closestNode.id }
                if (nodeIndexInPath != null && nodeIndexInPath != -1) {
                    speak("Okay, you are now at ${closestNode.name}.")
                } else {
                    speak("It seems you are now at ${closestNode.name}. Recalculating if needed.")
                    uiState.value.destinationNodeId?.let { destId -> findAndSetPath(closestNode.id, destId) }
                }
            } else {
                speak("Detected your location near ${closestNode.name}.")
                updateStatus("Current location: ${closestNode.name}")
            }
        } else if (closestNode == null) {
            Log.w(TAG, "Could not find a close node on floor $currentFloor for the current location.")
        }
    }

    // --- New function to handle Mic Button Click ---
    fun interruptSpeechAndListen() {
        Log.d(TAG, "Interrupt speech and listen requested.")
        // Stop TTS immediately
        ttsService.stop()

        // Check Audio Permission and Start Listening
        if (uiState.value.permissionsGranted[Manifest.permission.RECORD_AUDIO] == true) {
            if (!uiState.value.isListening) {
                Log.d(TAG, "Starting ASR listening.")
                speechRecognitionService.clearError() // Clear previous errors
                speechRecognitionService.clearRecognizedText() // Clear previous text
                speechRecognitionService.startListening()
                _uiState.update { it.copy(statusMessage = "Listening...", isProcessing = false) }
            } else {
                Log.d(TAG, "Already listening, ignoring request.")
                // Optionally: Stop listening if tapped while already listening?
                // speechRecognitionService.stopListening()
            }
        } else {
            Log.w(TAG, "Cannot start listening - Audio permission required.")
            // UI should have triggered permission request via launcher.
            // Show snackbar message here to remind user if needed.
            viewModelScope.launch {
                showError("Audio permission required to use voice commands.")
            }
        }
    }
    // --- End of new function ---

    // Existing startListening might become obsolete or private
    // fun startListening() { ... }


    private fun processLlmInput(text: String) {
        if (text.isBlank()) return
        _uiState.update { it.copy(isProcessing = true) }
        viewModelScope.launch {
            val currentStateInfo = generateMapStateInfo()
            Log.d(TAG, "Sending to LLM: '$text' with state: $currentStateInfo")
            val response = llmService.processUserInput(text, currentStateInfo)
            Log.d(TAG, "LLM Response: $response")
            _uiState.update { it.copy(isProcessing = false) }

            when (response) {
                is LLMResponse.FunctionCall -> handleFunctionCall(response.functionName, response.parameters)
                is LLMResponse.ClarificationNeeded -> {
                    updateStatus(response.question); speak(response.question)
                }
                is LLMResponse.GeneralResponse -> {
                    updateStatus(response.text); speak(response.text)
                }
                is LLMResponse.ErrorResponse -> {
                    showError(response.message); speak(response.message)
                }
            }
        }
    }

    private fun handleFunctionCall(functionName: String, parameters: Map<String, String>) {
        Log.d(TAG, "Handling function call: $functionName with params: $parameters")
        viewModelScope.launch {
            when (functionName) {
                "findPath" -> parameters["destinationName"]?.let { handleFindPathRequest(it) }
                    ?: speak("Sorry, I didn't catch the destination.")
                "updateLocation" -> parameters["locationName"]?.let { handleUpdateLocationRequest(it) }
                    ?: speak("Sorry, I didn't catch the location name you mentioned.")
                "getDistance" -> handleGetDistanceRequest()
                "localizeUser" -> handleLocalizeUserRequest()
                "findNearest" -> {
                    val type = try { NodeType.valueOf(parameters["type"] ?: "") } catch (e: Exception) { null }
                    type?.let { handleFindNearestRequest(it) }
                        ?: speak("Sorry, I don't know how to find the nearest item of that type.")
                }
                else -> {
                    Log.w(TAG, "Unknown function call: $functionName")
                    speak("Sorry, I encountered an unexpected request.")
                }
            }
        }
    }

    private suspend fun handleFindPathRequest(destinationName: String) {
        val startNodeId = uiState.value.currentLocationNodeId ?: run {
            speak("I need to know your current location first.")
            updateStatus("Please provide your current location.")
            return
        }
        val currentMap = uiState.value.airportMap ?: run {
            showError("Map data is not available.")
            speak("I can't calculate a path without map data.")
            return
        }
        val destinationNode = findNodeByNameOrType(destinationName, currentMap.nodes) ?: run {
            speak("Sorry, I couldn't find '$destinationName' on the map.")
            updateStatus("Destination '$destinationName' not found.")
            return
        }
        val startNode = mapNodesById[startNodeId]
        if (startNode?.floor != destinationNode.floor) {
            speak("Okay, heading to ${destinationNode.name} on floor ${destinationNode.floor}.")
        } else {
            speak("Okay, calculating route to ${destinationNode.name}.")
        }
        findAndSetPath(startNodeId, destinationNode.id)
    }

    private fun handleUpdateLocationRequest(locationName: String) {
        val currentMap = uiState.value.airportMap ?: return
        val foundNode = findNodeByNameOrType(locationName, currentMap.nodes) ?: run {
            speak("Sorry, I couldn't find a location matching '$locationName' on the map.")
            updateStatus("Could not update location to '$locationName'.")
            return
        }

        Log.d(TAG, "User stated location resolved to node: ${foundNode.id} (${foundNode.name})")
        if (foundNode.floor != uiState.value.currentFloor) {
            changeFloor(foundNode.floor)
            speak("Okay, you are at ${foundNode.name} on floor ${foundNode.floor}.")
        } else {
            speak("Okay, noted you are at ${foundNode.name}.")
        }
        _uiState.update { it.copy(currentLocationNodeId = foundNode.id) }
        updateStatus("Current location updated to: ${foundNode.name}")

        val destId = uiState.value.destinationNodeId
        if (destId != null) {
            if (foundNode.id == destId) {
                speak("You have arrived at your destination: ${foundNode.name}")
                _uiState.update { it.copy(destinationNodeId = null, currentPath = null) }
            } else if (uiState.value.currentPath != null) {
                findAndSetPath(foundNode.id, destId)
            }
        }
    }

    private fun handleGetDistanceRequest() {
        val path = uiState.value.currentPath
        val currentLocationId = uiState.value.currentLocationNodeId
        if (path == null || path.size < 2 || currentLocationId == null) {
            speak("There is no active route to measure distance for."); return
        }
        val currentIndex = path.indexOfFirst { it.id == currentLocationId }
        if (currentIndex == -1) {
            speak("Your current location is not on the calculated path."); return
        }
        val remainingPath = path.subList(currentIndex, path.size)
        val distance = LocationUtils.calculatePathDistance(remainingPath)
        val distanceInSteps = (distance / 25).roundToInt() // Adjust scale factor!
        speak("You have approximately $distanceInSteps steps remaining.")
        updateStatus("Approx. $distanceInSteps steps left.")
    }

    private fun handleLocalizeUserRequest() {
        val location = uiState.value.currentLocation ?: run {
            speak("I don't have your current GPS location.")
            requestInitialLocation()
            return
        }
        speak("Okay, trying to pinpoint your location on the map.")
        findAndSetNearestNode(location)
    }

    private suspend fun handleFindNearestRequest(nodeType: NodeType) {
        val startNodeId = uiState.value.currentLocationNodeId ?: run {
            speak("I need to know your current location first."); return
        }
        val currentMap = uiState.value.airportMap ?: run { showError("Map data not available."); return }
        val startNode = mapNodesById[startNodeId] ?: return

        val nearestNode = LocationUtils.findClosestNodeOfType(startNode, nodeType, currentMap.nodes)
        if (nearestNode != null) {
            speak("The nearest ${nodeType.name.lowercase().replace('_', ' ')} is ${nearestNode.name}. Starting route.")
            findAndSetPath(startNodeId, nearestNode.id)
        } else {
            speak("Sorry, I couldn't find any ${nodeType.name.lowercase().replace('_', ' ')} on the map.")
            updateStatus("No ${nodeType.name.lowercase().replace('_', ' ')} found.")
        }
    }

    private fun findAndSetPath(startNodeId: String, destinationNodeId: String) {
        val currentMap = uiState.value.airportMap ?: return
        Log.d(TAG, "Calculating path from $startNodeId to $destinationNodeId")
        updateStatus("Calculating route...")
        _uiState.update { it.copy(isProcessing = true) }

        viewModelScope.launch {
            val pathResult = AStar.findPath(startNodeId, destinationNodeId, currentMap)
            if (pathResult != null && pathResult.isNotEmpty()) {
                Log.d(TAG, "Path found with ${pathResult.size} nodes.")
                _uiState.update { it.copy(currentPath = pathResult, destinationNodeId = destinationNodeId, isProcessing = false) }
                updateStatus("Route calculated to ${mapNodesById[destinationNodeId]?.name ?: "destination"}.")
                generateAndSpeakInstructions(pathResult)
            } else {
                Log.w(TAG, "Path not found from $startNodeId to $destinationNodeId")
                _uiState.update { it.copy(currentPath = null, destinationNodeId = null, isProcessing = false) }
                speak("Sorry, I could not find a path to ${mapNodesById[destinationNodeId]?.name ?: "your destination"}.")
                updateStatus("Could not find path.")
            }
        }
    }

    private fun generateAndSpeakInstructions(path: List<Node>) {
        if (path.size < 2) return
        val startNode = path[0]
        val nextNode = path[1]
        val angle = atan2((nextNode.y - startNode.y).toDouble(), (nextNode.x - startNode.x).toDouble()) * 180 / Math.PI
        val direction = angleToDirection(angle)
        val edge = uiState.value.airportMap?.edges?.find { (it.from == startNode.id && it.to == nextNode.id) || (it.from == nextNode.id && it.to == startNode.id) }
        val stairsInfo = if (edge?.stairs == true) if (nextNode.floor > startNode.floor) " Take stairs/elevator up." else " Take stairs/elevator down." else ""
        val instruction = "Head $direction towards ${nextNode.name}.$stairsInfo"
        speak(instruction)
        updateStatus("Next: $instruction")
    }

    private fun speak(text: String) {
        if (text.isNotBlank()) {
            Log.d(TAG, "TTS Request: '$text'")
            ttsService.speak(text)
        }
    }

    private fun updateStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private suspend fun showError(message: String) {
        Log.e(TAG, "Error: $message")
        snackbarHostState.showSnackbar(message)
    }

    private fun findNodeByNameOrType(nameOrType: String, nodes: List<Node>): Node? {
        val lowerQuery = nameOrType.lowercase().trim()
        mapNodesById[nameOrType.uppercase()]?.let { return it }
        nodes.find { it.name.equals(lowerQuery, ignoreCase = true) }?.let { return it }
        nodes.find { it.name.lowercase().contains(lowerQuery) }?.let { return it }
        try { NodeType.valueOf(lowerQuery.uppercase())?.let { type -> return nodes.find { it.type == type } } } catch (e: Exception) { /* Ignore */ }
        val gateRegex = """(gate\s?)?([a-zA-Z])\s?(\d+)""".toRegex()
        gateRegex.matchEntire(lowerQuery)?.let { match ->
            val gateName = "Gate ${match.groupValues[2].uppercase()}${match.groupValues[3]}"
            return nodes.find { it.name.equals(gateName, ignoreCase = true) }
        }
        return null
    }

    private fun generateMapStateInfo(): MapStateInfo = MapStateInfo(
        currentKnownLocationId = uiState.value.currentLocationNodeId,
        currentDestinationId = uiState.value.destinationNodeId,
        userFlightGate = uiState.value.userFlightGate,
        isPathActive = !uiState.value.currentPath.isNullOrEmpty()
    )

    fun changeFloor(newFloor: Int) {
        val maxFloor = uiState.value.airportMap?.nodes?.maxOfOrNull { it.floor } ?: 1
        val minFloor = uiState.value.airportMap?.nodes?.minOfOrNull { it.floor } ?: 1
        if (newFloor in minFloor..maxFloor && newFloor != uiState.value.currentFloor) {
            Log.d(TAG, "Changing floor view to $newFloor")
            _uiState.update { it.copy(currentFloor = newFloor) }
            uiState.value.currentLocation?.let { loc -> findAndSetNearestNode(loc) }
        }
    }

    data class Vec(val x: Float, val y: Float)
    private fun Node.toVec(): Vec = Vec(this.x.toFloat(), this.y.toFloat())
    private fun angleToDirection(angle: Double): String = when (angle) {
        in -22.5..22.5 -> "right"; in 22.5..67.5 -> "up-right"; in 67.5..112.5 -> "up"
        in 112.5..157.5 -> "up-left"; in 157.5..180.0, in -180.0..-157.5 -> "left"
        in -157.5..-112.5 -> "down-left"; in -112.5..-67.5 -> "down"; in -67.5..-22.5 -> "down-right"
        else -> "nearby"
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared called.")
        locationUpdateJob?.cancel()
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
                        val mapRepo = MapRepositoryImpl(AirportMapDataSource(context))
                        val ticketRepo = MockTicketRepository()
                        val connectivitySvc = ConnectivityService(context)
                        val locationSvc = LocationService(context)
                        val ttsSvc = TextToSpeechService(context)
                        val speechSvc = SpeechRecognitionService(context)
                        val llmSvc = MockLlmService()
                        return MapViewModel(mapRepo, ticketRepo, connectivitySvc, locationSvc, ttsSvc, speechSvc, llmSvc, username) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
    }
}