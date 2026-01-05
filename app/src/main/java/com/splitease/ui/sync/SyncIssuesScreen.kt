package com.splitease.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncIssuesScreen(
    onNavigateBack: () -> Unit,
    viewModel: SyncIssuesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var deleteConfirmationId by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Issues") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.issues.isEmpty() && !uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No sync issues",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                
                items(uiState.issues, key = { it.id }) { issue ->
                    SyncIssueCard(
                        issue = issue,
                        isActionInProgress = uiState.pendingActionId == issue.id,
                        onRetry = { viewModel.retry(issue.id) },
                        onDelete = { deleteConfirmationId = issue.id }
                    )
                }
                
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }

    // Delete confirmation dialog
    deleteConfirmationId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteConfirmationId = null },
            title = { Text("Delete Unsynced Change") },
            text = {
                Text(
                    "This change could not be synced and cannot be fixed.\n\n" +
                    "To resolve this, it must be deleted from your device.\n\n" +
                    "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteChange(id)
                        deleteConfirmationId = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmationId = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SyncIssueCard(
    issue: SyncIssueUiModel,
    isActionInProgress: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val containerColor = if (issue.isError) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Display human-readable name
                val nameText = when (val name = issue.displayName) {
                    is DisplayName.Text -> name.value
                    is DisplayName.Resource -> androidx.compose.ui.res.stringResource(name.resId)
                }
                Text(
                    text = nameText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${issue.operationType} ${issue.entityType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = issue.failureReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (issue.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            
            // Only show actions for errors
            if (issue.isError) {
                Row {
                    if (issue.canRetry) {
                        IconButton(
                            onClick = onRetry,
                            enabled = !isActionInProgress
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(
                        onClick = onDelete,
                        enabled = !isActionInProgress
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                 // For pending, logic is read-only. Maybe show spinner? 
                 // For now just empty.
            }
        }
    }
}
