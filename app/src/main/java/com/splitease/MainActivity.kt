package com.splitease

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
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
import com.splitease.data.repository.AuthRepository
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
    authRepository: AuthRepository,
    private val deepLinkHandler: DeepLinkHandler,
    private val pendingInviteStore: PendingInviteStore
) : ViewModel() {
    val currentUser = authRepository.getCurrentUser()

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
            val currentUser by viewModel.currentUser.collectAsState(initial = null)
            val navController = androidx.navigation.compose.rememberNavController()
            
            // Stable startDestination based ONLY on auth state (no deep link influence)
            val startDestination = if (currentUser != null) {
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
            
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SplitEaseNavGraph(
                        navController = navController,
                        startDestination = startDestination
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep link on warm start
        viewModel.handleDeepLink(intent)
    }
}

