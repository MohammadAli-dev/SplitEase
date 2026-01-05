package com.splitease.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.splitease.R
import com.splitease.data.sync.SyncState

/**
 * Sync status indicator for TopAppBar.
 * Shows appropriate icon based on SyncState with unified string resources.
 */
@Composable
fun SyncStatusIcon(
    syncState: SyncState,
    failedCount: Int = 0,
    pendingCount: Int = 0,
    onNavigateToSyncIssues: () -> Unit
) {
    when (syncState) {
        SyncState.FAILED -> SyncIndicator(
            icon = Icons.Default.Warning,
            contentDescription = stringResource(R.string.sync_state_failed),
            tint = MaterialTheme.colorScheme.error,
            badgeText = if (failedCount > 0) "$failedCount" else null,
            onClick = onNavigateToSyncIssues
        )
        SyncState.PAUSED -> SyncIndicator(
            icon = Icons.Default.Warning, // Using Warning for PAUSED (💤 semantics)
            contentDescription = stringResource(R.string.sync_state_paused),
            tint = MaterialTheme.colorScheme.tertiary,
            badgeText = if (pendingCount > 0) "$pendingCount" else null,
            onClick = onNavigateToSyncIssues
        )
        SyncState.SYNCING -> SyncIndicator(
            icon = Icons.Default.Warning, // Could use Sync icon if available
            contentDescription = stringResource(R.string.sync_state_syncing),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            badgeText = if (pendingCount > 0) "$pendingCount" else null,
            onClick = onNavigateToSyncIssues
        )
        SyncState.IDLE -> {
            // No indicator shown
        }
    }
}

@Composable
private fun SyncIndicator(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    badgeText: String?,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint
        )
        if (badgeText != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
        }
    }
}

// Legacy overload for backward compatibility
@Composable
fun SyncStatusIcon(
    failedSyncCount: Int,
    pendingSyncCount: Int,
    onNavigateToSyncIssues: () -> Unit
) {
    val state = when {
        failedSyncCount > 0 -> SyncState.FAILED
        pendingSyncCount > 0 -> SyncState.SYNCING
        else -> SyncState.IDLE
    }
    SyncStatusIcon(
        syncState = state,
        failedCount = failedSyncCount,
        pendingCount = pendingSyncCount,
        onNavigateToSyncIssues = onNavigateToSyncIssues
    )
}
