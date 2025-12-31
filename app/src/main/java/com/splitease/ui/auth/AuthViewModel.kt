package com.splitease.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    // Observes DB. UI navigates when this is non-null.
    val currentUser = authRepository.getCurrentUser()

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = authRepository.login(email, password)
            if (result.isFailure) {
                _uiState.value = AuthUiState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
            } else {
                 // Do NOTHING on success. UI observes currentUser.
                 // We can reset state to Idle.
                 _uiState.value = AuthUiState.Idle
            }
        }
    }

    fun signup(name: String, email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = authRepository.signup(name, email, password)
            if (result.isFailure) {
                _uiState.value = AuthUiState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
            } else {
                 _uiState.value = AuthUiState.Idle
            }
        }
    }
}

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}
