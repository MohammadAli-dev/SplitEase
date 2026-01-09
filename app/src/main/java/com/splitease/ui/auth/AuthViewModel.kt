package com.splitease.ui.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.auth.AuthManager
import com.splitease.data.auth.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Login and Signup screens.
 * Uses AuthManager for real Supabase authentication.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager
) : ViewModel() {

    companion object {
        private const val TAG = "AuthViewModel"
    }

    /** Observable auth state for loading indication */
    val authState: StateFlow<AuthState> = authManager.authState

    /** One-shot error events for snackbar display */
    val authError: SharedFlow<String> = authManager.authError

    /** One-shot info events (e.g. "Check your email") */
    val authInfo: SharedFlow<String> = authManager.authInfo

    /** UI-local state for form validation errors */
    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /** One-shot event emitted on successful login (for navigation) */
    private val _loginSuccess = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loginSuccess: SharedFlow<Unit> = _loginSuccess.asSharedFlow()

    init {
        // Reactive State Management:
        // Automatically trigger navigation when AuthState becomes Authenticated.
        // This decouples the "how" (Google, Email, etc) from the "what" (User is logged in).
        viewModelScope.launch {
            authManager.authState.collect { state ->
                if (state is AuthState.Authenticated) {
                    Log.d(TAG, "Auth state is Authenticated, emitting loginSuccess")
                    _loginSuccess.emit(Unit)
                }
            }
        }
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = AuthUiState.Error("Email and password are required")
            return
        }

        viewModelScope.launch {
            _uiState.value = AuthUiState.Idle // Clear any previous error
            Log.d(TAG, "login: starting")
            
            authManager.loginWithEmail(email, password)
            // No manual success emission. 
            // If successful and authenticated, authState flow will trigger navigation.
            // If verification needed, authInfo will be emitted.
            // If error, authError will be emitted.
        }
    }

    fun signup(name: String, email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = AuthUiState.Error("Email and password are required")
            return
        }

        viewModelScope.launch {
            _uiState.value = AuthUiState.Idle
            Log.d(TAG, "signup: starting")
            
            authManager.signupWithEmail(
                name = name.takeIf { it.isNotBlank() },
                email = email,
                password = password
            )
        }
    }

    /**
     * Login with OAuth ID token (Google, Apple, etc).
     * Provider-agnostic: ViewModel delegates to AuthManager.
     */
    fun loginWithIdToken(provider: com.splitease.data.auth.AuthProvider, idToken: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Idle
            Log.d(TAG, "loginWithIdToken: starting for ${provider.supabaseProvider}")
            
            authManager.loginWithIdToken(provider, idToken)
        }
    }

    fun clearError() {
        _uiState.value = AuthUiState.Idle
    }
}

sealed class AuthUiState {
    object Idle : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}
