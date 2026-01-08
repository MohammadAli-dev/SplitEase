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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val deepLinkHandler: DeepLinkHandler,
    private val pendingInviteStore: PendingInviteStore
) : ViewModel() {
    val currentUser = authRepository.getCurrentUser()

    private val _pendingDeepLinkRoute = MutableStateFlow<String?>(null)
    val pendingDeepLinkRoute: StateFlow<String?> = _pendingDeepLinkRoute.asStateFlow()

    /**
     * Process an incoming deep link intent.
     * Saves token to PendingInviteStore and signals navigation.
     */
    fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return

        viewModelScope.launch {
            when (val result = deepLinkHandler.parse(uri)) {
                is DeepLinkResult.ClaimInvite -> {
                    // Save token to authoritative store
                    pendingInviteStore.save(result.inviteToken)
                    // Signal navigation to ClaimInvite screen
                    _pendingDeepLinkRoute.value = Screen.ClaimInvite.route
                }
                is DeepLinkResult.Unknown -> {
                    // Ignore unknown deep links
                }
            }
        }
    }

    fun consumePendingDeepLinkRoute() {
        _pendingDeepLinkRoute.value = null
    }
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
            val pendingDeepLinkRoute by viewModel.pendingDeepLinkRoute.collectAsState()

            // Determine start destination
            var startDestination by remember { mutableStateOf<String?>(null) }
            
            LaunchedEffect(currentUser, pendingDeepLinkRoute) {
                startDestination = when {
                    // Deep link takes priority
                    pendingDeepLinkRoute != null -> {
                        viewModel.consumePendingDeepLinkRoute()
                        pendingDeepLinkRoute
                    }
                    currentUser != null -> Screen.Dashboard.route
                    else -> Screen.Login.route
                }
            }
            
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    startDestination?.let { dest ->
                        SplitEaseNavGraph(startDestination = dest)
                    }
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

