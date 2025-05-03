package com.nonstac.airportguide.service

import com.nonstac.airportguide.data.model.LLMResponse
import com.nonstac.airportguide.data.model.MapStateInfo
import kotlinx.coroutines.delay

// IMPORTANT: This is a MOCK service. Integrating a real local LLM
// (like Phi-3 Mini, Gemma 2B via ONNX Runtime or TensorFlow Lite)
// is a complex task involving model conversion, native libraries,
// and potentially significant performance considerations.
// This mock uses simple keyword matching to simulate function calling.
class MockLlmService {

    suspend fun processUserInput(userInput: String, currentState: MapStateInfo): LLMResponse {
        delay(400) // Simulate processing time
        val inputLower = userInput.lowercase()

        // 1. Handle Location Updates
        if (inputLower.contains("i am at") || inputLower.contains("i'm at")) {
            // Very basic entity extraction - assumes location follows "at "
            val locationGuess = inputLower.substringAfter(" at ").trim().ifEmpty { null }
            return if (locationGuess != null) {
                LLMResponse.FunctionCall("updateLocation", mapOf("locationName" to locationGuess))
            } else {
                LLMResponse.ClarificationNeeded("Sorry, where did you say you are?")
            }
        }

        // 2. Handle Navigation Requests
        if (inputLower.contains("how do i get to") || inputLower.contains("take me to") || inputLower.contains("go to") || inputLower.contains("where is")) {
            var destination: String? = null
            if (inputLower.contains("my gate")) {
                destination = currentState.userFlightGate ?: return LLMResponse.ClarificationNeeded("I don't have your gate information. Which gate are you looking for?")
            } else if (inputLower.contains("bathroom") || inputLower.contains("restroom")) {
                destination = "BATHROOM" // Special keyword for nearest bathroom
            } else if (inputLower.contains("exit") || inputLower.contains("emergency exit")) {
                destination = "EMERGENCY_EXIT"
            } else {
                // Basic extraction - find common locations
                val potentialDestinations = listOf("entrance", "security", "duty free", "lounge")
                potentialDestinations.forEach { dest ->
                    if (inputLower.contains(dest)) {
                        destination = dest
                        return@forEach
                    }
                }
                // Try to extract gate numbers (simple A/B + number)
                val gateRegex = """(gate\s?)([a-zA-Z]\d+)""".toRegex()
                gateRegex.find(inputLower)?.groupValues?.get(2)?.let { gate ->
                    destination = "Gate ${gate.uppercase()}"
                }
            }

            if (destination != null) {
                if (currentState.currentKnownLocationId == null) {
                    return LLMResponse.ClarificationNeeded("Okay, I can help you get to ${destination}. Where are you right now?")
                } else {
                    return LLMResponse.FunctionCall("findPath", mapOf("destinationName" to destination!!))
                }
            } else {
                return LLMResponse.ClarificationNeeded("Sorry, where do you want to go?")
            }
        }

        // 3. Handle Distance Request
        if (inputLower.contains("how far") || inputLower.contains("distance left")) {
            if (!currentState.isPathActive) {
                return LLMResponse.GeneralResponse("You don't have an active route. Where do you want to go?")
            }
            return LLMResponse.FunctionCall("getDistance", emptyMap())
        }

        // 4. Handle "Lost" scenario
        if (inputLower.contains("i am lost") || inputLower.contains("where am i")) {
            return LLMResponse.FunctionCall("localizeUser", emptyMap())
        }

        // Default / Fallback
        return LLMResponse.GeneralResponse("Sorry, I didn't understand that. Can you please rephrase? You can ask me how to get somewhere, where you are, or how far your destination is.")
    }
}