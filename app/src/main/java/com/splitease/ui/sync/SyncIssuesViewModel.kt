package com.splitease.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.R
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.SyncEntityType
import com.splitease.data.local.entities.SyncFailureType
import com.splitease.data.local.entities.SyncOperation
import com.splitease.data.local.entities.SyncStatus
import com.splitease.data.repository.SyncRepository
import com.splitease.data.sync.SyncConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI model for displaying sync issues.
 * Includes computed properties for retry eligibility and human-readable display name.
 */
data class SyncIssueUiModel(
    val id: Int,
    val entityType: String,
    val entityId: String,
    val operationType: String,
    val failureReason: String,
    val failureType: SyncFailureType?,
    val canRetry: Boolean,
    val canReviewDiff: Boolean, // true IFF: UPDATE + EXPENSE (Sprint 10C)
    val isError: Boolean,
    val displayName: DisplayName
)

data class SyncIssuesUiState(
    val issues: List<SyncIssueUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val pendingActionId: Int? = null,
    val isSyncEnabled: Boolean = true
)

/**
 * ViewModel for the Sync Issues screen.
 * Exposes failed operations (excluding AUTH) with retry/delete actions.
 * Uses batched queries for entity names to prevent N+1 performance issues.
 */
@HiltViewModel
class SyncIssuesViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val syncDao: SyncDao,
    private val expenseDao: ExpenseDao,
    private val groupDao: GroupDao,
    private val settlementDao: SettlementDao
) : ViewModel() {

    private val _pendingActionId = MutableStateFlow<Int?>(null)
    private val _issues = MutableStateFlow<List<SyncIssueUiModel>>(emptyList())
    private val _isLoading = MutableStateFlow(true)
    private val _isSyncEnabled = MutableStateFlow(true)

    val uiState: StateFlow<SyncIssuesUiState> = combine(
        _issues,
        _pendingActionId,
        _isLoading,
        _isSyncEnabled
    ) { issues, actionId, loading, syncEnabled ->
        SyncIssuesUiState(
            issues = issues,
            isLoading = loading,
            pendingActionId = actionId,
            isSyncEnabled = syncEnabled
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SyncIssuesUiState()
    )

    init {
        viewModelScope.launch {
            combine(
                syncRepository.failedOperations,
                syncRepository.pendingOperations
            ) { failed, pending ->
                val filteredFailed = failed.filter { it.failureType != SyncFailureType.AUTH }
                filteredFailed to pending
            }.collect { (filteredFailed, pending) ->
                _isLoading.value = true
                
                val allOps = filteredFailed + pending
                
                if (allOps.isEmpty()) {
                    _issues.value = emptyList()
                    _isLoading.value = false
                    return@collect
                }

                // Partition by entity type for batched queries
                val expenseIds = allOps.filter { it.entityType == SyncEntityType.EXPENSE }.map { it.entityId }
                val groupIds = allOps.filter { it.entityType == SyncEntityType.GROUP }.map { it.entityId }
                val settlementIds = allOps.filter { it.entityType == SyncEntityType.SETTLEMENT }.map { it.entityId }

                // Parallel batch fetch (max 3 queries)
                val (expenseNames, groupNames, settlementAmounts) = coroutineScope {
                    val expenseDeferred = async { 
                        if (expenseIds.isNotEmpty()) expenseDao.getTitlesByIds(expenseIds).associate { it.id to it.value } else emptyMap() 
                    }
                    val groupDeferred = async { 
                        if (groupIds.isNotEmpty()) groupDao.getNamesByIds(groupIds).associate { it.id to it.value } else emptyMap() 
                    }
                    val settlementDeferred = async { 
                        if (settlementIds.isNotEmpty()) settlementDao.getAmountsByIds(settlementIds).associate { it.id to it.value } else emptyMap() 
                    }
                    Triple(expenseDeferred.await(), groupDeferred.await(), settlementDeferred.await())
                }

                // Map with display names
                _issues.value = allOps.map { op ->
                    val displayName = when (op.entityType) {
                        SyncEntityType.EXPENSE -> expenseNames[op.entityId]?.let { DisplayName.Text(it) }
                            ?: DisplayName.Resource(R.string.deleted_expense)
                        SyncEntityType.GROUP -> groupNames[op.entityId]?.let { DisplayName.Text(it) }
                            ?: DisplayName.Resource(R.string.deleted_group)
                        SyncEntityType.SETTLEMENT -> settlementAmounts[op.entityId]?.let { 
                            DisplayName.Text("Settlement (₹$it)") 
                        } ?: DisplayName.Resource(R.string.deleted_settlement)
                    }
                    
                    val isError = op.status == SyncStatus.FAILED
                    
                    SyncIssueUiModel(
                        id = op.id,
                        entityType = op.entityType.name,
                        entityId = op.entityId,
                        operationType = op.operationType,
                        failureReason = if (isError) (op.failureReason ?: "Unknown error") else "Sync in progress...",
                        failureType = op.failureType,
                        canRetry = isError && op.failureType != SyncFailureType.VALIDATION,
                        canReviewDiff = isError && op.operationType == "UPDATE" && op.entityType == SyncEntityType.EXPENSE,
                        isError = isError,
                        displayName = displayName
                    )
                }
                _isLoading.value = false
            }
        }
    }

    fun retry(id: Int) {
        if (_pendingActionId.value != null) return
        
        viewModelScope.launch {
            _pendingActionId.value = id
            try {
                syncRepository.retryOperation(id)
            } finally {
                _pendingActionId.value = null
            }
        }
    }

    fun deleteChange(id: Int) {
        if (_pendingActionId.value != null) return
        
        viewModelScope.launch {
            _pendingActionId.value = id
            try {
                syncRepository.acknowledgeFailure(id)
            } finally {
                _pendingActionId.value = null
            }
        }
    }

    /**
     * Trigger manual sync with debounce protection.
     * Disables button for MANUAL_SYNC_DEBOUNCE_MS after trigger.
     */
    fun triggerManualSync() {
        if (!_isSyncEnabled.value) return
        
        viewModelScope.launch {
            _isSyncEnabled.value = false
            syncRepository.triggerManualSync()
            delay(SyncConstants.MANUAL_SYNC_DEBOUNCE_MS)
            _isSyncEnabled.value = true
        }
    }
}
