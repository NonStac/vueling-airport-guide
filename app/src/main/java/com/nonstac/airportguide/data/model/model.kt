package com.nonstac.airportguide.data.model

import kotlinx.serialization.Serializable

data class User(val username: String)

@Serializable
data class Ticket(
    val id: String,
    val airline: String = "Vueling", // Hardcoded
    val flightNumber: String,
    val originAirportCode: String,
    val destinationAirportCode: String,
    val departureTime: String,
    val arrivalTime: String,
    val gate: String?, // Gate might not be assigned initially
    val seat: String,
    val passengerName: String,
    val status: TicketStatus
)

enum class TicketStatus { BOUGHT, AVAILABLE }


@Serializable
data class Node(
    val id: String,
    val name: String,
    val type: NodeType,
    val x: Int,
    val y: Int,
    val floor: Int
)

@Serializable
enum class NodeType { ENTRANCE, GATE, BATHROOM, EMERGENCY_EXIT, WAYPOINT }

@Serializable
data class Edge(
    val from: String,
    val to: String,
    val stairs: Boolean,
    // Weight will be calculated dynamically based on node coordinates
)

@Serializable
data class AirportMap(
    val airportName: String,
    val nodes: List<Node>,
    val edges: List<Edge>
)

// --- LLM Interaction Models ---
sealed class LLMResponse {
    data class FunctionCall(val functionName: String, val parameters: Map<String, String>) : LLMResponse()
    data class ClarificationNeeded(val question: String) : LLMResponse()
    data class GeneralResponse(val text: String): LLMResponse()
    data class ErrorResponse(val message: String): LLMResponse()
}

data class MapStateInfo(
    val currentKnownLocationId: String?,
    val currentDestinationId: String?,
    val userFlightGate: String?,
    val isPathActive: Boolean
)