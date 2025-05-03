package com.nonstac.airportguide.ui.screens.register

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.nonstac.airportguide.data.repository.MockUserRepository
import com.nonstac.airportguide.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RegisterUiState(
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val registrationSuccess: Boolean = false,
    val errorMessage: String? = null
)

class RegisterViewModel(private val userRepository: UserRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    fun updateUsername(username: String) {
        _uiState.update { currentState -> currentState.copy(username = username) }
    }

    fun updatePassword(password: String) {
        _uiState.update { currentState -> currentState.copy(password = password) }
    }

    fun updateConfirmPassword(confirmPassword: String) {
        _uiState.update { currentState -> currentState.copy(confirmPassword = confirmPassword) }
    }

    fun register() {
        val currentState = _uiState.value
        if (currentState.username.isBlank() || currentState.password.isBlank()) {
            _uiState.update { state -> state.copy(errorMessage = "Username and password cannot be empty") }
            return
        }
        if (currentState.password != currentState.confirmPassword) {
            _uiState.update { state -> state.copy(errorMessage = "Passwords do not match") }
            return
        }

        viewModelScope.launch {
            _uiState.update { state -> state.copy(isLoading = true, errorMessage = null) }
            val result = userRepository.register(currentState.username, currentState.password)
            result.fold(
                onSuccess = { user -> // Explicitly name the parameter
                    _uiState.update { state ->
                        state.copy(isLoading = false, registrationSuccess = true, username = user.username /* Ensure username from result is used */)
                    }
                },
                onFailure = { throwable -> // Explicitly name the parameter
                    _uiState.update { state ->
                        state.copy(isLoading = false, errorMessage = throwable.message ?: "Registration failed")
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { currentState -> currentState.copy(errorMessage = null) }
    }

    // --- Factory for instantiation ---
    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras // Use CreationExtras for modern Factory
            ): T {
                // In a real app with DI, repository would be injected.
                // For this example, we instantiate the mock directly.
                val repository = MockUserRepository()
                return RegisterViewModel(repository) as T
            }
        }
    }
}
