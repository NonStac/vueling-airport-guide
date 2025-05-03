package com.nonstac.airportguide.data.repository

import com.nonstac.airportguide.data.local.AirportMapDataSource
import com.nonstac.airportguide.data.model.AirportMap
import com.nonstac.airportguide.data.model.Ticket
import com.nonstac.airportguide.data.model.TicketStatus
import com.nonstac.airportguide.data.model.User

interface UserRepository {
    suspend fun login(username: String, password: String): Result<User>
    suspend fun register(username: String, password: String): Result<User>
}

// --- Mock Implementation ---
class MockUserRepository : UserRepository {
    override suspend fun login(username: String, password: String): Result<User> {
        // Mock logic: accept any non-empty username/password for now
        return if (username.isNotBlank() && password.isNotBlank()) {
            Result.success(User(username))
        } else {
            Result.failure(IllegalArgumentException("Invalid credentials"))
        }
    }

    override suspend fun register(username: String, password: String): Result<User> {
        // Mock logic: Assume registration is always successful if fields are not blank
        return if (username.isNotBlank() && password.isNotBlank()) {
            Result.success(User(username))
        } else {
            Result.failure(IllegalArgumentException("Username and password cannot be empty"))
        }
    }
}

interface TicketRepository {
    suspend fun getBoughtTickets(username: String): Result<List<Ticket>>
    suspend fun searchAvailableTickets(origin: String, destination: String): Result<List<Ticket>>
    suspend fun buyTicket(username: String, ticketId: String): Result<Unit> // Simplified
}

// --- Mock Implementation ---
class MockTicketRepository : TicketRepository {
    private val boughtTickets = mutableListOf(
        Ticket("T1", "Vueling", "VY1001", "BCN", "CDG", "2025-07-15T08:00:00Z", "2025-07-15T09:45:00Z", "A2", "12A", "Test User", TicketStatus.BOUGHT),
        Ticket("T2", "Vueling", "VY2025", "BCN", "LHR", "2025-07-20T14:30:00Z", "2025-07-20T16:00:00Z", "B1", "5F", "Test User", TicketStatus.BOUGHT)
    )

    private val availableTickets = listOf(
        Ticket("T3", "Vueling", "VY3300", "BCN", "AMS", "2025-08-01T10:00:00Z", "2025-08-01T12:15:00Z", null, "N/A", "N/A", TicketStatus.AVAILABLE),
        Ticket("T4", "Vueling", "VY5050", "BCN", "FCO", "2025-08-02T17:00:00Z", "2025-08-02T18:40:00Z", null, "N/A", "N/A", TicketStatus.AVAILABLE),
        Ticket("T5", "Vueling", "VY1005", "BCN", "CDG", "2025-08-03T08:00:00Z", "2025-08-03T09:45:00Z", null, "N/A", "N/A", TicketStatus.AVAILABLE)
    )

    override suspend fun getBoughtTickets(username: String): Result<List<Ticket>> {
        // In a real app, filter by username
        kotlinx.coroutines.delay(300) // Simulate network latency
        return Result.success(boughtTickets)
    }

    override suspend fun searchAvailableTickets(origin: String, destination: String): Result<List<Ticket>> {
        kotlinx.coroutines.delay(500) // Simulate network latency
        // Basic mock search - doesn't really use origin/dest here
        return Result.success(availableTickets)
    }

    override suspend fun buyTicket(username: String, ticketId: String): Result<Unit> {
        kotlinx.coroutines.delay(600)
        val ticketToBuy = availableTickets.find { it.id == ticketId }
        if (ticketToBuy != null) {
            boughtTickets.add(ticketToBuy.copy(status = TicketStatus.BOUGHT, passengerName = username /* or fetched user profile name */))
            // In a real app, remove from available or mark as bought
            return Result.success(Unit)
        }
        return Result.failure(NoSuchElementException("Ticket not found"))
    }
}

interface MapRepository {
    suspend fun getAirportMap(airportCode: String): Result<AirportMap>
}

// Implementation uses local data source
class MapRepositoryImpl(private val dataSource: AirportMapDataSource) : MapRepository {
    override suspend fun getAirportMap(airportCode: String): Result<AirportMap> {
        // For now, always return the hardcoded map, ignoring airportCode
        return dataSource.loadMap()
    }
}