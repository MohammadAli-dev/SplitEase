package com.splitease.ui.account

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ShoppingCart
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

@Composable
fun AccountScreen(
    viewModel: AccountViewModel = hiltViewModel(),
    onLogout: () -> Unit = {}
) {
    val userProfile by viewModel.userProfile.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val currency by viewModel.currency.collectAsState()
    val timezone by viewModel.timezone.collectAsState()
    val friendSuggestionEnabled by viewModel.friendSuggestionEnabled.collectAsState()
    val inviteState by viewModel.inviteState.collectAsState()
    val profileUpdateState by viewModel.profileUpdateState.collectAsState()

    val isAuthenticated = authState is AuthState.Authenticated
    
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    // Dialog/Sheet states
    var showInviteBottomSheet by remember { mutableStateOf(false) }
    var currentDeepLink by remember { mutableStateOf("") }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showEditEmailDialog by remember { mutableStateOf(false) }
    var showCurrencyPicker by remember { mutableStateOf(false) }
    var showTimezonePicker by remember { mutableStateOf(false) }

    // Handle invite state changes
    LaunchedEffect(inviteState) {
        when (val state = inviteState) {
            is InviteUiState.Available -> {
                currentDeepLink = state.deepLink
                showInviteBottomSheet = true
            }
            is InviteUiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.consumeInvite()
            }
            else -> {}
        }
    }

    // Handle profile update state changes
    LaunchedEffect(profileUpdateState) {
        when (val state = profileUpdateState) {
            is ProfileUpdateState.Success -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.consumeProfileUpdateState()
            }
            is ProfileUpdateState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.consumeProfileUpdateState()
            }
            else -> {}
        }
    }

    // Show dialogs/sheets
    if (showInviteBottomSheet && currentDeepLink.isNotEmpty()) {
        InviteBottomSheet(
            deepLink = currentDeepLink,
            onDismiss = {
                showInviteBottomSheet = false
                viewModel.consumeInvite()
            },
            onShowSnackbar = { message ->
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        )
    }

    if (showEditNameDialog) {
        EditNameDialog(
            currentName = userProfile?.name ?: "",
            onDismiss = { showEditNameDialog = false },
            onConfirm = { newName ->
                showEditNameDialog = false
                viewModel.updateName(newName)
            }
        )
    }

    if (showEditEmailDialog) {
        EditEmailDialog(
            currentEmail = userProfile?.email ?: "",
            onDismiss = { showEditEmailDialog = false },
            onConfirm = { newEmail ->
                showEditEmailDialog = false
                viewModel.updateEmail(newEmail)
            }
        )
    }

    if (showCurrencyPicker) {
        CurrencyPickerSheet(
            currentCurrency = currency,
            onDismiss = { showCurrencyPicker = false },
            onSelect = { newCurrency ->
                viewModel.setCurrency(newCurrency)
            }
        )
    }

    if (showTimezonePicker) {
        TimezonePickerSheet(
            currentTimezone = timezone,
            onDismiss = { showTimezonePicker = false },
            onSelect = { newTimezone ->
                viewModel.setTimezone(newTimezone)
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

            // Profile Section (from AuthManager, not Room)
            if (isAuthenticated && userProfile != null) {
                val isLoading = profileUpdateState is ProfileUpdateState.Loading
                
                AccountSection(title = "Profile") {
                    EditableProfileItem(
                        icon = Icons.Default.AccountCircle,
                        label = "Name",
                        value = userProfile?.name ?: "Not set",
                        onClick = { showEditNameDialog = true },
                        enabled = !isLoading
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    EditableProfileItem(
                        icon = Icons.Default.Email,
                        label = "Email",
                        value = userProfile?.email ?: "Not set",
                        onClick = { showEditEmailDialog = true },
                        enabled = !isLoading
                    )
                    
                    if (isLoading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = "Updating...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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

            // Preferences Section (editable)
            AccountSection(title = "Preferences") {
                EditablePreferenceItem(
                    icon = Icons.Default.ShoppingCart,
                    label = "Currency",
                    value = currency,
                    onClick = { showCurrencyPicker = true }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                EditablePreferenceItem(
                    icon = Icons.Default.DateRange,
                    label = "Timezone",
                    value = timezone,
                    onClick = { showTimezonePicker = true }
                )
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

/**
 * Editable profile item that shows current value and edit icon.
 */
@Composable
fun EditableProfileItem(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = value, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = "Edit $label",
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant 
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Editable preference item with icon.
 */
@Composable
fun EditablePreferenceItem(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(8.dp))
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

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