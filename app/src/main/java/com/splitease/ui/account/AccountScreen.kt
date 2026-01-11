package com.splitease.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.splitease.data.auth.AuthState
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Displays the account screen composed of profile, connections, preferences, privacy, support,
 * account actions, and a danger zone.
 *
 * Shows profile information when available and exposes actions that depend on authentication state
 * (for example, an "Invite a Friend" action and a "Log Out" action). When an invite becomes
 * available the composable presents an invite bottom sheet with the deep link; invite errors are
 * surfaced via a snackbar. The "Log Out" action also invokes the provided `onLogout` callback.
 *
 * @param onLogout Callback invoked after the user triggers logout. */
/**
 * Renders the Account screen UI, including profile, connections, preferences, privacy, support,
 * account actions, and a danger zone.
 *
 * The screen reacts to authentication and invite state: it shows invite-related UI and a
 * shareable invite bottom sheet when an invite becomes available, and displays logout controls
 * only for authenticated users. It also surfaces snackbars for invite errors.
 *
 * @param onLogout Callback invoked after the view model initiates logout; intended for host-level
 * navigation or teardown. */
@Composable
fun AccountScreen(
    viewModel: AccountViewModel = hiltViewModel(),
    onLogout: () -> Unit = {}
) {
    val currentUser by viewModel.currentUser.collectAsState(initial = null)
    val friendSuggestionEnabled by viewModel.friendSuggestionEnabled.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val inviteState by viewModel.inviteState.collectAsState()

    val isAuthenticated = authState is AuthState.Authenticated
    
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showBottomSheet by remember { mutableStateOf(false) }
    var currentDeepLink by remember { mutableStateOf("") }

    // Handle invite state changes
    LaunchedEffect(inviteState) {
        when (val state = inviteState) {
            is InviteUiState.Available -> {
                currentDeepLink = state.deepLink
                showBottomSheet = true
            }
            is InviteUiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.consumeInvite()
            }
            else -> {}
        }
    }

    // Show bottom sheet when invite is available
    if (showBottomSheet && currentDeepLink.isNotEmpty()) {
        InviteBottomSheet(
            deepLink = currentDeepLink,
            onDismiss = {
                showBottomSheet = false
                viewModel.consumeInvite()
            },
            onShowSnackbar = { message ->
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(text = "Account", style = MaterialTheme.typography.headlineMedium)

            // Profile Section
            currentUser?.let { user ->
                AccountSection(title = "Profile") {
                    ProfileItem(icon = Icons.Default.AccountCircle, label = "Name", value = user.name)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ProfileItem(
                        icon = Icons.Default.Email,
                        label = "Email",
                        value = user.email ?: "Not set"
                    )
                }
            }

            // Invite a Friend Section (only for authenticated users)
            if (isAuthenticated) {
                AccountSection(title = "Connections") {
                    Button(
                        onClick = { viewModel.createInvite() },
                        enabled = inviteState !is InviteUiState.Loading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (inviteState is InviteUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Creating invite...")
                        } else {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Invite a Friend")
                        }
                    }
                }
            }

            // Preferences Section
            AccountSection(title = "Preferences") {
                PreferenceDisplayItem(label = "Currency", value = "INR")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                PreferenceDisplayItem(label = "Timezone", value = TimeZone.getDefault().id)
            }

            // Privacy Section
            AccountSection(title = "Privacy") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Friend Suggestions", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Controls whether your profile may be suggested to others in the future.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Currently stored only on this device.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = friendSuggestionEnabled,
                        onCheckedChange = { viewModel.toggleFriendSuggestion(it) }
                    )
                }
            }

            // Support Section
            AccountSection(title = "Support") {
                DisabledActionItem(text = "Contact Us")
                DisabledActionItem(text = "Feedback")
            }

            // Account Actions (Logout)
            if (isAuthenticated) {
                AccountSection(title = "Account") {
                    OutlinedButton(
                        onClick = {
                            viewModel.logout()
                            onLogout()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Log Out")
                    }
                    Text(
                        text = "Your local data will be preserved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // Danger Zone
            AccountSection(
                title = "Danger Zone",
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
            ) {
                Button(
                    onClick = { /* Disabled */ },
                    enabled = false,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                        disabledContentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Delete Account")
                }
                Text(
                    text = "This action is not available in the current version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Text(
                text = "Some features will unlock after sign-in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 32.dp)
            )
        }
    }
}
/**
 * Renders a titled section with a card container for grouping account-related UI elements.
 *
 * @param title The section title displayed above the card.
 * @param containerColor The background color used for the card container.
 * @param content Composable slot rendered inside the card. */
@Composable
fun AccountSection(
        title: String,
        containerColor: Color = MaterialTheme.colorScheme.surface,
        content: @Composable () -> Unit
) {
    Column {
        Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor)
        ) { Column(modifier = Modifier.padding(16.dp)) { content() } }
    }
}

@Composable
fun ProfileItem(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.size(16.dp))
        Column {
            Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun PreferenceDisplayItem(label: String, value: String) {
    Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Displays a non-interactive action row with a primary label and a "Coming soon" indicator.
 *
 * @param text The primary action label to display; shown in a visually disabled style.
 */
@Composable
fun DisabledActionItem(text: String) {
    Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Text(
                text = "Coming soon",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}