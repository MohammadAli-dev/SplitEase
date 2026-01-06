package com.splitease.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.splitease.R
import kotlinx.coroutines.launch as coroutineLaunch
import androidx.lifecycle.ViewModel
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.entities.Group
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.MoreVert
import androidx.lifecycle.viewModelScope
import com.splitease.ui.common.SyncStatusIcon
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.SyncFailureType
import com.splitease.data.repository.SyncRepository
import com.splitease.data.sync.SyncConstants
import com.splitease.data.sync.SyncHealth
import com.splitease.data.sync.SyncState
import android.util.Log
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupListViewModel @Inject constructor(
    groupDao: GroupDao,
    private val syncRepository: SyncRepository
) : ViewModel() {

    // Observe derived sync health
    private val syncHealth: StateFlow<SyncHealth> = syncRepository.observeSyncHealth()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SyncHealth(0, 0, null)
        )

    // Derive SyncState from SyncHealth (FAILED > PAUSED > SYNCING > IDLE)
    val syncState: StateFlow<SyncState> = syncHealth
        .map { health -> deriveSyncState(health) }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SyncState.IDLE
        )

    init {
        // Telemetry: Log when PAUSED state first appears
        viewModelScope.launch {
            var previousState = SyncState.IDLE
            syncState.collect { currentState ->
                if (previousState != SyncState.PAUSED && currentState == SyncState.PAUSED) {
                    val age = syncHealth.value.oldestPendingAgeMillis
                    Log.w("SyncHealth", "Sync paused: oldest pending age = ${age}ms")
                }
                previousState = currentState
            }
        }
    }

    private fun deriveSyncState(health: SyncHealth): SyncState {
        return when {
            health.failedCount > 0 -> SyncState.FAILED
            health.pendingCount > 0 && (health.oldestPendingAgeMillis ?: 0) > SyncConstants.PAUSED_THRESHOLD_MS -> SyncState.PAUSED
            health.pendingCount > 0 -> SyncState.SYNCING
            else -> SyncState.IDLE
        }
    }

    fun triggerManualSync() {
        syncRepository.triggerManualSync()
    }

    // Combined UI state
    val uiState: StateFlow<GroupListUiState> = combine(
        groupDao.getAllGroups(),
        syncHealth
    ) { groups, health ->
        GroupListUiState(
            groups = groups,
            failedSyncCount = health.failedCount,
            pendingSyncCount = health.pendingCount,
            syncState = deriveSyncState(health)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GroupListUiState()
    )
}

data class GroupListUiState(
    val groups: List<Group> = emptyList(),
    val failedSyncCount: Int = 0,
    val pendingSyncCount: Int = 0,
    val syncState: SyncState = SyncState.IDLE
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(
    onNavigateToGroupDetail: (groupId: String) -> Unit,
    onNavigateToCreateGroup: () -> Unit,
    onNavigateToSyncIssues: () -> Unit,
    viewModel: GroupListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val groups = uiState.groups
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val syncStartedMessage = stringResource(R.string.sync_started)
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("SplitEase") },
                actions = {
                    SyncStatusIcon(
                        syncState = uiState.syncState,
                        failedCount = uiState.failedSyncCount,
                        pendingCount = uiState.pendingSyncCount,
                        onNavigateToSyncIssues = onNavigateToSyncIssues
                    )
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Sync Now") },
                                onClick = {
                                    showMenu = false
                                    viewModel.triggerManualSync()
                                    scope.coroutineLaunch {
                                        snackbarHostState.showSnackbar(syncStartedMessage)
                                    }
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateGroup) {
                Icon(Icons.Default.Add, contentDescription = "Create Group")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text(
                text = "Groups",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No groups yet",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Create a group to start splitting!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(groups) { group ->
                        GroupItem(
                            group = group,
                            onClick = { onNavigateToGroupDetail(group.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GroupItem(
    group: Group,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = group.type,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

