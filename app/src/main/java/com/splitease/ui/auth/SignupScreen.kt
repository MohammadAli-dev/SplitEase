package com.splitease.ui.auth

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.splitease.BuildConfig
import com.splitease.data.auth.AuthProvider
import com.splitease.data.auth.AuthState
import com.splitease.ui.navigation.AuthResult
import com.splitease.ui.navigation.NavResultKeys

/**
 * Signup screen with email/password and Google Sign-In.
 * 
 * Per FIX 15: Activity result launcher is owned by this screen.
 * GoogleSignInButton is a dumb component that only triggers onClick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignupScreen(
    navController: NavController,
    onNavigateToLogin: () -> Unit,
    onSignupSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val currency = "INR" // Default, not editable yet

    val isLoading = authState is AuthState.Authenticating

    // Configure Google Sign-In
    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    // FIX 15: Activity result launcher owned by screen (hoisted side-effect)
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                val idToken = account?.idToken
                if (idToken != null) {
                    android.util.Log.d("SignupScreen", "Google Sign-In success, exchanging token")
                    viewModel.loginWithIdToken(AuthProvider.GOOGLE, idToken)
                } else {
                    Toast.makeText(context, "Failed to get ID token", Toast.LENGTH_LONG).show()
                }
            } catch (e: ApiException) {
                android.util.Log.e("SignupScreen", "Google Sign-In failed: ${e.statusCode}")
                Toast.makeText(context, "Google Sign-In failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Handle signup success event
    LaunchedEffect(Unit) {
        viewModel.loginSuccess.collect {
            android.util.Log.d("SignupScreen", "Signup success, setting AUTH_RESULT and navigating")
            navController.previousBackStackEntry?.savedStateHandle?.set(
                NavResultKeys.AUTH_RESULT, AuthResult.SUCCESS.name
            )
            onSignupSuccess()
        }
    }

    // Handle auth errors via toast
    LaunchedEffect(Unit) {
        viewModel.authError.collect { errorMessage ->
            // Use Snackbar for errors too if possible, or keep Toast for consistency
            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.authInfo.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Create Account") },
                navigationIcon = {
                    IconButton(onClick = onNavigateToLogin, enabled = !isLoading) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it; viewModel.clearError() },
                label = { Text("Full Name") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it; viewModel.clearError() },
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; viewModel.clearError() },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            OutlinedTextField(
                value = currency,
                onValueChange = {},
                label = { Text("Currency") },
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState is AuthUiState.Error) {
                Text(
                    text = (uiState as AuthUiState.Error).message,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = { viewModel.signup(name, email, password) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Sign Up")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // OR divider
            AuthDivider()

            Spacer(modifier = Modifier.height(16.dp))

            // Google Sign-In button (FIX 15: dumb component, side-effect hoisted)
            GoogleSignInButton(
                onClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) },
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onNavigateToLogin, enabled = !isLoading) {
                Text("Already have an account? Login")
            }
        }
    }
}
