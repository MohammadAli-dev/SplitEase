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
import com.splitease.data.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val displayName: DisplayName
)

data class SyncIssuesUiState(
    val issues: List<SyncIssueUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val pendingActionId: Int? = null
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

    val uiState: StateFlow<SyncIssuesUiState> = combine(
        _issues,
        _pendingActionId,
        _isLoading
    ) { issues, actionId, loading ->
        SyncIssuesUiState(
            issues = issues,
            isLoading = loading,
            pendingActionId = actionId
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SyncIssuesUiState()
    )

    init {
        viewModelScope.launch {
            syncRepository.failedOperations.collect { operations ->
                _isLoading.value = true
                val filteredOps = operations.filter { it.failureType != SyncFailureType.AUTH }
                
                if (filteredOps.isEmpty()) {
                    _issues.value = emptyList()
                    _isLoading.value = false
                    return@collect
                }

                // Partition by entity type for batched queries
                val expenseIds = filteredOps.filter { it.entityType == SyncEntityType.EXPENSE }.map { it.entityId }
                val groupIds = filteredOps.filter { it.entityType == SyncEntityType.GROUP }.map { it.entityId }
                val settlementIds = filteredOps.filter { it.entityType == SyncEntityType.SETTLEMENT }.map { it.entityId }

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
                _issues.value = filteredOps.map { op ->
                    val displayName = when (op.entityType) {
                        SyncEntityType.EXPENSE -> expenseNames[op.entityId]?.let { DisplayName.Text(it) }
                            ?: DisplayName.Resource(R.string.deleted_expense)
                        SyncEntityType.GROUP -> groupNames[op.entityId]?.let { DisplayName.Text(it) }
                            ?: DisplayName.Resource(R.string.deleted_group)
                        SyncEntityType.SETTLEMENT -> settlementAmounts[op.entityId]?.let { 
                            DisplayName.Text("Settlement (₹$it)") 
                        } ?: DisplayName.Resource(R.string.deleted_settlement)
                    }
                    
                    SyncIssueUiModel(
                        id = op.id,
                        entityType = op.entityType.name,
                        entityId = op.entityId,
                        operationType = op.operationType,
                        failureReason = op.failureReason ?: "Unknown error",
                        failureType = op.failureType,
                        canRetry = op.failureType != SyncFailureType.VALIDATION,
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
}
