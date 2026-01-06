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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.util.TimeZone

@Composable
fun AccountScreen(viewModel: AccountViewModel = hiltViewModel()) {
    val currentUser by viewModel.currentUser.collectAsState(initial = null)
    val friendSuggestionEnabled by viewModel.friendSuggestionEnabled.collectAsState()

    Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
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
                            text =
                                    "Controls whether your profile may be suggested to others in the future.",
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

        // Danger Zone
        AccountSection(
                title = "Danger Zone",
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
        ) {
            Button(
                    onClick = { /* Disabled */},
                    enabled = false,
                    colors =
                            ButtonDefaults.buttonColors(
                                    disabledContainerColor =
                                            MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
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
