package com.splitease.ui.sync

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.repository.SyncRepository
import com.splitease.data.sync.DiffStats
import com.splitease.data.sync.EntitySnapshot
import com.splitease.data.sync.ExpenseSnapshotAdapter
import com.splitease.data.sync.FieldSection
import com.splitease.data.sync.SnapshotField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ReconciliationUiState {
    data object Loading : ReconciliationUiState
    data class Loaded(
        val expenseTitle: String,
        val fields: List<SnapshotField>,
        val remoteExists: Boolean
    ) : ReconciliationUiState
    data class Error(val message: String) : ReconciliationUiState
}

sealed interface ReconciliationEvent {
    data class ShowSnackbar(val message: String) : ReconciliationEvent
    data object NavigateBack : ReconciliationEvent
}

@HiltViewModel
class ReconciliationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val expenseDao: ExpenseDao,
    private val syncRepository: SyncRepository
) : ViewModel() {

    private val expenseId: String = savedStateHandle["expenseId"] ?: ""
    private val syncOpId: Int = savedStateHandle["syncOpId"] ?: 0

    private val _uiState = MutableStateFlow<ReconciliationUiState>(ReconciliationUiState.Loading)
    val uiState: StateFlow<ReconciliationUiState> = _uiState.asStateFlow()

    private val _event = MutableStateFlow<ReconciliationEvent?>(null)
    val event: StateFlow<ReconciliationEvent?> = _event.asStateFlow()

    private var localExpense: Expense? = null
    private var localSplits: List<ExpenseSplit> = emptyList()
    private var remoteExpense: Expense? = null
    private var remoteSplits: List<ExpenseSplit>? = null

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                // Load local data
                localExpense = expenseDao.getExpenseById(expenseId)
                localSplits = expenseDao.getSplitsSync(expenseId)

                if (localExpense == null) {
                    _uiState.value = ReconciliationUiState.Error("Local expense not found")
                    return@launch
                }

                // Load remote data (one-shot)
                try {
                    val remoteResult = syncRepository.fetchRemoteExpense(expenseId)
                    remoteExpense = remoteResult?.first
                    remoteSplits = remoteResult?.second
                } catch (e: Exception) {
                    Log.w("ReconciliationVM", "Failed to fetch remote: ${e.message}")
                    // Remote fetch failed - show error
                    _uiState.value = ReconciliationUiState.Error("Could not fetch server version: ${e.message}")
                    return@launch
                }

                // Compute diff
                val snapshot = ExpenseSnapshotAdapter.compare(
                    local = localExpense!!,
                    localSplits = localSplits,
                    remote = remoteExpense,
                    remoteSplits = remoteSplits
                )

                // Log telemetry
                val stats = ExpenseSnapshotAdapter.computeDiffStats(snapshot)
                logTelemetry(stats)

                _uiState.value = ReconciliationUiState.Loaded(
                    expenseTitle = snapshot.displayTitle,
                    fields = snapshot.fields,
                    remoteExists = remoteExpense != null
                )
            } catch (e: Exception) {
                Log.e("ReconciliationVM", "Error loading data", e)
                _uiState.value = ReconciliationUiState.Error("Error: ${e.message}")
            }
        }
    }

    fun keepServerVersion() {
        val expense = remoteExpense ?: return
        val splits = remoteSplits ?: return

        viewModelScope.launch {
            try {
                syncRepository.applyServerExpense(expense, splits, syncOpId)
                Log.d("ReconciliationVM", "Kept server version for $expenseId")
                _event.value = ReconciliationEvent.ShowSnackbar("Local changes replaced")
                _event.value = ReconciliationEvent.NavigateBack
            } catch (e: Exception) {
                Log.e("ReconciliationVM", "Failed to apply server version", e)
                _uiState.value = ReconciliationUiState.Error("Failed to apply: ${e.message}")
            }
        }
    }

    fun keepLocalVersion() {
        viewModelScope.launch {
            try {
                syncRepository.retryLocalVersion(syncOpId)
                Log.d("ReconciliationVM", "Kept local version, retrying sync for $expenseId")
                _event.value = ReconciliationEvent.ShowSnackbar("Retrying your changes")
                _event.value = ReconciliationEvent.NavigateBack
            } catch (e: Exception) {
                Log.e("ReconciliationVM", "Failed to retry local version", e)
                _uiState.value = ReconciliationUiState.Error("Failed to retry: ${e.message}")
            }
        }
    }

    fun clearEvent() {
        _event.value = null
    }

    private fun logTelemetry(stats: DiffStats) {
        Log.d("ReconciliationTelemetry", "Expense $expenseId: ${stats.changedFields}/${stats.totalFields} fields changed, sections: ${stats.sectionsChanged}")
    }
}
