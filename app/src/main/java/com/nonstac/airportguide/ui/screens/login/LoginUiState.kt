package com.nonstac.airportguide.ui.screens.login

import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHostState
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

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val loginSuccess: Boolean = false,
    val errorMessage: String? = null
)

class LoginViewModel(private val userRepository: UserRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun updateUsername(username: String) {
        _uiState.update { currentState -> currentState.copy(username = username) }
    }

    fun updatePassword(password: String) {
        _uiState.update { currentState -> currentState.copy(password = password) }
    }

    fun login() {
        val currentState = _uiState.value
        if (currentState.username.isBlank() || currentState.password.isBlank()){
            _uiState.update { state -> state.copy(errorMessage = "Username and password required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { state -> state.copy(isLoading = true, errorMessage = null) }
            val result = userRepository.login(currentState.username, currentState.password)
            result.fold(
                onSuccess = { loggedInUser -> // Explicitly name the parameter
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            loginSuccess = true,
                            // Use the username from the successful login result
                            username = loggedInUser.username
                        )
                    }
                },
                onFailure = { loginError -> // Explicitly name the parameter
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            errorMessage = loginError.message ?: "Login failed"
                        )
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
        // Simple factory pattern
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras // Use CreationExtras
            ): T {
                // Provide the UserRepository implementation
                val repository = MockUserRepository() // Replace with actual implementation or DI
                return LoginViewModel(repository) as T
            }
        }
    }
}
