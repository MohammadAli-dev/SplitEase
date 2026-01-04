package com.splitease.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.viewModelScope
// import com.splitease.data.local.dao.SyncDao
import com.splitease.ui.common.SyncStatusIcon
// import com.splitease.data.local.dao.SyncDao // kept via wildcard or specific
import com.splitease.ui.common.SyncStatusIcon
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.SyncFailureType
import com.splitease.data.repository.SyncRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class GroupListViewModel @Inject constructor(
    groupDao: GroupDao,
    syncDao: SyncDao,
    syncRepository: SyncRepository
) : ViewModel() {

    // Derived sync state
    private val failedSyncCount: Flow<Int> = syncRepository.failedOperations
        .map { ops -> ops.count { it.failureType != SyncFailureType.AUTH } }

    private val pendingSyncCount: Flow<Int> = syncDao.getPendingSyncCount()

    // Combined UI state
    val uiState: StateFlow<GroupListUiState> = combine(
        groupDao.getAllGroups(),
        failedSyncCount,
        pendingSyncCount
    ) { groups, failedCount, pendingCount ->
        GroupListUiState(
            groups = groups,
            failedSyncCount = failedCount,
            pendingSyncCount = pendingCount
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
    val pendingSyncCount: Int = 0
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SplitEase") },
                actions = {
                    SyncStatusIcon(
                        failedSyncCount = uiState.failedSyncCount,
                        pendingSyncCount = uiState.pendingSyncCount,
                        onNavigateToSyncIssues = onNavigateToSyncIssues
                    )
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
                    Text(
                        text = "No groups yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

