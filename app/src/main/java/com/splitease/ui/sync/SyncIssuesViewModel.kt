package com.splitease.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.SyncFailureType
import com.splitease.data.local.entities.SyncOperation
import com.splitease.data.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI model for displaying sync issues.
 * Includes computed properties for retry eligibility.
 */
data class SyncIssueUiModel(
    val id: Int,
    val entityType: String,
    val entityId: String,
    val operationType: String,
    val failureReason: String,
    val failureType: SyncFailureType?,
    val canRetry: Boolean
)

data class SyncIssuesUiState(
    val issues: List<SyncIssueUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val pendingActionId: Int? = null // ID being acted upon (for button disable)
)

/**
 * ViewModel for the Sync Issues screen.
 * Exposes failed operations (excluding AUTH) with retry/delete actions.
 */
@HiltViewModel
class SyncIssuesViewModel @Inject constructor(
    private val syncRepository: SyncRepository,
    private val syncDao: SyncDao
) : ViewModel() {

    private val _pendingActionId = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<SyncIssuesUiState> = combine(
        syncRepository.failedOperations,
        _pendingActionId
    ) { operations, actionId ->
        val issues = operations
            // Filter out AUTH failures (handled at system level)
            .filter { it.failureType != SyncFailureType.AUTH }
            .map { op ->
                SyncIssueUiModel(
                    id = op.id,
                    entityType = op.entityType.name,
                    entityId = op.entityId,
                    operationType = op.operationType,
                    failureReason = op.failureReason ?: "Unknown error",
                    failureType = op.failureType,
                    // VALIDATION failures are not retryable
                    canRetry = op.failureType != SyncFailureType.VALIDATION
                )
            }
        
        SyncIssuesUiState(
            issues = issues,
            isLoading = false,
            pendingActionId = actionId
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SyncIssuesUiState()
    )

    /**
     * Retry a failed operation.
     * Resets status to PENDING and triggers immediate sync.
     */
    fun retry(id: Int) {
        if (_pendingActionId.value != null) return // Guard against duplicate actions
        
        viewModelScope.launch {
            _pendingActionId.value = id
            try {
                syncRepository.retryOperation(id)
            } finally {
                _pendingActionId.value = null
            }
        }
    }

    /**
     * Delete an unsynced change.
     * For INSERT operations, also deletes the local entity (zombie elimination).
     * This action is PERMANENT and cannot be undone.
     */
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
