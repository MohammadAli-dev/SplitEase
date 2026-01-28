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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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

    /** form state for Login/Signup */
    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _confirmPassword = MutableStateFlow("")
    val confirmPassword: StateFlow<String> = _confirmPassword.asStateFlow()

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
            var previousState: AuthState? = null
            authManager.authState.collect { currentState ->
                if (currentState is AuthState.Authenticated && 
                    previousState != null && 
                    previousState !is AuthState.Authenticated
                ) {
                    Log.d(TAG, "Auth state transition to Authenticated, emitting loginSuccess")
                    _loginSuccess.emit(Unit)
                }
                previousState = currentState
            }
        }
    }

    // --- State setters ---
    fun onEmailChange(newValue: String) { _email.value = newValue; clearError() }
    fun onPasswordChange(newValue: String) { _password.value = newValue; clearError() }
    fun onNameChange(newValue: String) { _name.value = newValue; clearError() }
    fun onConfirmPasswordChange(newValue: String) { _confirmPassword.value = newValue; clearError() }

    // --- Validation Logic ---
    private val MIN_PASSWORD_LENGTH = 6

    val loginPasswordFeedback = combine(_password) { (pass) ->
        if (pass.length < MIN_PASSWORD_LENGTH && pass.isNotEmpty()) 
             "Password must be at least $MIN_PASSWORD_LENGTH characters" else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val signupPasswordFeedback = combine(_password, _confirmPassword) { pass, confirm ->
         if (pass.length < MIN_PASSWORD_LENGTH && pass.isNotEmpty()) {
             "Password must be at least $MIN_PASSWORD_LENGTH characters"
         } else if (pass != confirm && confirm.isNotEmpty()) {
             "Passwords do not match"
         } else {
             null
         }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isLoginValid = combine(_email, _password) { email, pass ->
        email.isNotBlank() && pass.length >= MIN_PASSWORD_LENGTH
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isSignupValid = combine(_name, _email, _password, _confirmPassword) { name, email, pass, confirm ->
        name.isNotBlank() && 
        email.isNotBlank() && 
        pass.length >= MIN_PASSWORD_LENGTH &&
        pass == confirm
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun login() {
        val emailVal = _email.value
        val passVal = _password.value
        
        if (!isLoginValid.value) {
            _uiState.value = AuthUiState.Error("Please check your input")
            return
        }

        viewModelScope.launch {
            _uiState.value = AuthUiState.Idle 
            Log.d(TAG, "login: starting")
            
            val result = authManager.loginWithEmail(emailVal, passVal)
            handleAuthResult(result)
        }
    }

    fun signup() {
        val nameVal = _name.value
        val emailVal = _email.value
        val passVal = _password.value

        if (!isSignupValid.value) {
            _uiState.value = AuthUiState.Error("Please check your input")
            return
        }

        viewModelScope.launch {
            _uiState.value = AuthUiState.Idle
            Log.d(TAG, "signup: starting")
            
            val result = authManager.signupWithEmail(
                name = nameVal,
                email = emailVal,
                password = passVal
            )
            handleAuthResult(result)
        }
    }
    
    private fun handleAuthResult(result: Result<Unit>) {
        result.onFailure { error ->
             // Presentation-layer error mapping
             val message = when {
                 error.message?.contains("invalid_grant", ignoreCase = true) == true -> "Incorrect email or password."
                 error.message?.contains("User already registered", ignoreCase = true) == true -> "Account already exists."
                 error.message?.contains("Unable to resolve host", ignoreCase = true) == true -> "No internet connection. Please check your network."
                 else -> error.message ?: "Authentication failed."
             }
             _uiState.value = AuthUiState.Error(message)
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
