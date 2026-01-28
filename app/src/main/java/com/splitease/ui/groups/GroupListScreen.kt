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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.splitease.data.sync.SyncConstants
import com.splitease.data.local.entities.SyncFailureType
import com.splitease.data.repository.SyncRepository
import com.splitease.data.sync.SyncHealth
import com.splitease.data.sync.SyncState
import android.util.Log
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import com.splitease.ui.common.SyncStatusIcon
import com.splitease.ui.common.EmptyState

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

    private val _isRefreshing = MutableStateFlow(false)

    // Derived SyncState from SyncHealth (FAILED > PAUSED > SYNCING > IDLE)
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
        syncHealth,
        syncRepository.observeManualSyncWork(),
        _isRefreshing
    ) { groups, health, isWorkFinished, isRefreshing ->
        val derivedSyncState = deriveSyncState(health)
        // If work manager says work is running (not finished), override IDLE state to SYNCING for UI
        val finalSyncState = if (derivedSyncState == SyncState.IDLE && !isWorkFinished) {
             SyncState.SYNCING 
        } else {
             derivedSyncState
        }
        
        GroupListUiState(
            groups = groups,
            failedSyncCount = health.failedCount,
            pendingSyncCount = health.pendingCount,
            syncState = finalSyncState,
            // Expose explicit running flag for pull-to-refresh
            isManualSyncRunning = !isWorkFinished,
            isRefreshing = isRefreshing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GroupListUiState()
    )



    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            syncRepository.triggerManualSync()
            kotlinx.coroutines.delay(SyncConstants.REFRESH_ACK_UI_DELAY_MS)
            _isRefreshing.value = false
        }
    }
}

data class GroupListUiState(
    val groups: List<Group> = emptyList(),
    val failedSyncCount: Int = 0,
    val pendingSyncCount: Int = 0,
    val syncState: SyncState = SyncState.IDLE,
    val isManualSyncRunning: Boolean = false,
    val isRefreshing: Boolean = false
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
                                    viewModel.refresh()
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
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Groups",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (groups.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Info, // Or a dedicated "Groups" icon if available like Group/People
                        title = "No groups yet",
                        message = "Create a group to start splitting expenses with friends!",
                        actionLabel = "Create Group",
                        onActionClick = onNavigateToCreateGroup
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
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

