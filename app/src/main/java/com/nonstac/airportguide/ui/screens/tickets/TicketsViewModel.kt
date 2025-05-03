package com.nonstac.airportguide.ui.screens.tickets

import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.nonstac.airportguide.data.model.Ticket
import com.nonstac.airportguide.data.repository.MockTicketRepository
import com.nonstac.airportguide.data.repository.TicketRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TicketsUiState(
    val boughtTickets: List<Ticket> = emptyList(),
    val availableTickets: List<Ticket> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isBuyingTicketId: String? = null // ID of the ticket currently being purchased
)

class TicketsViewModel(
    private val ticketRepository: TicketRepository,
    private val username: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(TicketsUiState())
    val uiState: StateFlow<TicketsUiState> = _uiState.asStateFlow()

    private val snackbarHostState = SnackbarHostState()

    init {
        loadTickets()
    }

    private fun loadTickets(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }
            val boughtResult = ticketRepository.getBoughtTickets(username)
            val availableResult = ticketRepository.searchAvailableTickets("", "") // Mock doesn't use params

            val bought = boughtResult.getOrElse { emptyList() }
            val available = availableResult.getOrElse { emptyList() }

            val errorMsg = boughtResult.exceptionOrNull()?.message ?: availableResult.exceptionOrNull()?.message

            _uiState.update { currentState ->
                currentState.copy(
                    boughtTickets = bought,
                    availableTickets = available,
                    isLoading = false,
                    errorMessage = currentState.errorMessage ?: errorMsg // Keep existing error if present
                )
            }
        }
    }

    fun buyTicket(ticketId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBuyingTicketId = ticketId, errorMessage = null) }
            val result = ticketRepository.buyTicket(username, ticketId)
            result.fold(
                onSuccess = {
                    // Show success feedback
                    snackbarHostState.showSnackbar("Ticket $ticketId purchased successfully!")
                    // Reload tickets to reflect the change
                    loadTickets(showLoading = false) // Don't show full loading indicator
                },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "Failed to buy ticket") }
                }
            )
            _uiState.update { it.copy(isBuyingTicketId = null) } // Reset buying state regardless of outcome
        }
    }

    fun clearError() {
        _uiState.update { currentState -> currentState.copy(errorMessage = null) }
        // Consider clearing snackbarHostState as well if needed immediately
    }

    // --- Factory for instantiation with username ---
    companion object {
        fun provideFactory(
            username: String,
            // Inject repository if using DI, otherwise create mock here
            ticketRepository: TicketRepository = MockTicketRepository()
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(TicketsViewModel::class.java)) {
                    return TicketsViewModel(ticketRepository, username) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}
