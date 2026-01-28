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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Login screen with email/password and Google Sign-In.
 * 
 * Per FIX 15: Activity result launcher is owned by this screen.
 * GoogleSignInButton is a dumb component that only triggers onClick.
 */
@Composable
fun LoginScreen(
    navController: NavController,
    onNavigateToSignup: () -> Unit,
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var isPasswordVisible by remember { mutableStateOf(false) }

    val email by viewModel.email.collectAsState()
    val password by viewModel.password.collectAsState()

    // Derived UI states
    val isLoginValid by viewModel.isLoginValid.collectAsState()
    val passwordFeedback by viewModel.loginPasswordFeedback.collectAsState()

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
                    android.util.Log.d("LoginScreen", "Google Sign-In success, exchanging token")
                    viewModel.loginWithIdToken(AuthProvider.GOOGLE, idToken)
                } else {
                    Toast.makeText(context, "Failed to get ID token", Toast.LENGTH_LONG).show()
                }
            } catch (e: ApiException) {
                android.util.Log.e("LoginScreen", "Google Sign-In failed: ${e.statusCode}")
                Toast.makeText(context, "Google Sign-In failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Handle login success event (explicit navigation trigger)
    LaunchedEffect(Unit) {
        viewModel.loginSuccess.collect {
            android.util.Log.d("LoginScreen", "Login success, setting AUTH_RESULT and navigating")
            // Set AUTH_RESULT for previous screen (ClaimInvite resume pattern)
            // TODO: Revisit this assumption. If LoginScreen is a start destination, previousBackStackEntry is null.
            // Future Sprint: Decouple from backstack (use global/graph-scoped state).
            navController.previousBackStackEntry?.savedStateHandle?.set(
                NavResultKeys.AUTH_RESULT, AuthResult.SUCCESS.name
            )
            onLoginSuccess()
        }
    }

    // Handle auth errors via toast
    LaunchedEffect(Unit) {
        viewModel.authError.collect { errorMessage ->
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
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        Text(text = "SplitEase Login", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = email,
            onValueChange = viewModel::onEmailChange,
            label = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Password") },
            visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            // DONE Action support
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { 
                    if (isLoginValid && !isLoading) viewModel.login() 
                }
            ),
            trailingIcon = {
                val image = if (isPasswordVisible)
                    androidx.compose.material.icons.Icons.Filled.Visibility
                else androidx.compose.material.icons.Icons.Filled.VisibilityOff

                androidx.compose.material3.IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                    androidx.compose.material3.Icon(imageVector = image, contentDescription = if (isPasswordVisible) "Hide password" else "Show password")
                }
            },
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                 passwordFeedback?.let {
                     Text(
                         text = it,
                         color = MaterialTheme.colorScheme.error,
                         style = MaterialTheme.typography.bodySmall
                     )
                 }
            },
            enabled = !isLoading
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Show UI-local validation errors
        if (uiState is AuthUiState.Error) {
            Text(
                text = (uiState as AuthUiState.Error).message,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = { viewModel.login() },
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
                Text("Log In")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // OR divider
        AuthDivider()

        Spacer(modifier = Modifier.height(24.dp))

        // Google Sign-In button (FIX 15: dumb component, side-effect hoisted)
        GoogleSignInButton(
            onClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) },
            enabled = !isLoading
        )

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = onNavigateToSignup,
            enabled = !isLoading
        ) {
            Text("Don't have an account? Sign up")
        }
        }
    }
}
