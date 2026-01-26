package com.splitease

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.deeplink.DeepLinkHandler
import com.splitease.data.deeplink.DeepLinkResult
import com.splitease.data.invite.PendingInviteStore
import com.splitease.ui.navigation.Screen
import com.splitease.ui.navigation.SplitEaseNavGraph
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    authManager: com.splitease.data.auth.AuthManager,
    private val deepLinkHandler: DeepLinkHandler,
    private val pendingInviteStore: PendingInviteStore
) : ViewModel() {
    /** Observable auth state for determining start destination */
    val authState = authManager.authState

    /**
     * One-shot navigation events using Channel.
     * Channel ensures each event is consumed exactly once.
     */
    private val _navigationEvent = Channel<NavigationEvent>(Channel.BUFFERED)
    val navigationEvent: Flow<NavigationEvent> = _navigationEvent.receiveAsFlow()

    /**
     * Process an incoming deep link intent.
     * Saves token to PendingInviteStore and emits navigation event.
     */
    fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return

        viewModelScope.launch {
            when (val result = deepLinkHandler.parse(uri)) {
                is DeepLinkResult.ClaimInvite -> {
                    // Save token for process death survival
                    pendingInviteStore.save(result.inviteToken)
                    // Emit one-shot navigation event
                    _navigationEvent.send(NavigationEvent.NavigateTo(Screen.ClaimInvite.route))
                }
                is DeepLinkResult.Unknown -> {
                    // Ignore unknown deep links
                }
            }
        }
    }
}

/**
 * Navigation events emitted by ViewModel, consumed by Activity.
 */
sealed class NavigationEvent {
    data class NavigateTo(val route: String) : NavigationEvent()
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle deep link on cold start
        viewModel.handleDeepLink(intent)

        setContent {
            val authState by viewModel.authState.collectAsState()
            val navController = androidx.navigation.compose.rememberNavController()
            
            // Stable startDestination based on auth TOKEN state (not local user existence)
            val startDestination = if (authState is com.splitease.data.auth.AuthState.Authenticated) {
                Screen.Dashboard.route
            } else {
                Screen.Login.route
            }
            
            // Collect one-shot navigation events from ViewModel
            LaunchedEffect(navController) {
                viewModel.navigationEvent.collect { event ->
                    when (event) {
                        is NavigationEvent.NavigateTo -> {
                            navController.navigate(event.route)
                        }
                    }
                }
            }
            
            // Gap 2/10: Handle Logout Transitions & Blocking UI
            LaunchedEffect(authState) {
                if (authState is com.splitease.data.auth.AuthState.Unauthenticated) {
                    // Only navigate if we are not already at the start/login to avoid loops
                    val currentRoute = navController.currentDestination?.route
                    if (currentRoute != Screen.Login.route) {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(navController.graph.id) { inclusive = true } // Clear entire backstack including MainScaffold ViewModels
                        }
                    }
                }
            }
            
            MaterialTheme {
                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        SplitEaseNavGraph(
                            navController = navController,
                            startDestination = startDestination
                        )
                    }
                    
                    // Blocking UI for LoggingOut state
                    if (authState is com.splitease.data.auth.AuthState.LoggingOut) {
                        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 8.dp
                            ) {
                                androidx.compose.foundation.layout.Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator()
                                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                                    Text("Logging out safely...")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle deep link on warm start
        viewModel.handleDeepLink(intent)
    }
}

