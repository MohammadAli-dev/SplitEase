package com.splitease.ui.groups

import com.splitease.data.local.entities.SyncEntityType

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.dao.SyncDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.User
import com.splitease.data.repository.SettlementRepository
import com.splitease.data.repository.SyncRepository
import com.splitease.data.local.entities.SyncFailureType
import com.splitease.data.local.entities.SyncOperation
import com.splitease.domain.BalanceCalculator
import com.splitease.domain.SettlementCalculator
import com.splitease.domain.SettlementMode
import com.splitease.domain.SettlementSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

sealed interface GroupDetailUiState {
    data object Loading : GroupDetailUiState
    data class Success(
        val group: Group,
        val members: List<User>,
        val expenses: List<Expense>,
        val balances: Map<String, BigDecimal>,
        val settlements: List<SettlementSuggestion>,
        val executingSettlements: Set<String> = emptySet(),
        val pendingExpenseIds: Set<String> = emptySet(),
        val pendingSettlementIds: Set<String> = emptySet(),
        val pendingGroupSyncCount: Int = 0,
        val groupFailedSyncCount: Int, // Derived from filtered failures
        val settlementMode: SettlementMode = SettlementMode.SIMPLIFIED
    ) : GroupDetailUiState
    data class Error(val message: String) : GroupDetailUiState
}

sealed interface GroupDetailEvent {
    data class ShowSnackbar(val message: String) : GroupDetailEvent
}

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupDao: GroupDao,
    private val expenseDao: ExpenseDao,
    private val settlementDao: SettlementDao,
    private val settlementRepository: SettlementRepository,
    private val syncDao: SyncDao,
    private val syncRepository: SyncRepository
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId") ?: ""

    private val _retryTrigger = MutableStateFlow(0)
    private val _executingSettlements = MutableStateFlow<Set<String>>(emptySet())

    private val _eventChannel = Channel<GroupDetailEvent>()
    val events = _eventChannel.receiveAsFlow()

    /**
     * Settlement mode is UI-only and not persisted.
     * Defaults to SIMPLIFIED on every screen entry.
     */
    private val _settlementMode = MutableStateFlow(SettlementMode.SIMPLIFIED)

    // Derive pending IDs from sync_operations (source of truth)
    private val pendingExpenseIds = syncDao.getPendingEntityIds(SyncEntityType.EXPENSE)
        .map { it.toSet() }
    private val pendingSettlementIds = syncDao.getPendingEntityIds(SyncEntityType.SETTLEMENT)
        .map { it.toSet() }

    // Combine data sources
    private data class GroupData(
        val group: Group?,
        val members: List<User>,
        val expenses: List<Expense>,
        val splits: List<ExpenseSplit>,
        val settlements: List<com.splitease.data.local.entities.Settlement>
    )

    private val groupData = combine(
        groupDao.getGroup(groupId),
        groupDao.getGroupMembersWithDetails(groupId),
        expenseDao.getExpensesForGroup(groupId),
        expenseDao.getAllExpenseSplitsForGroup(groupId),
        settlementDao.getSettlementsForGroup(groupId)
    ) { group: Group?, members: List<User>, expenses: List<Expense>, splits: List<ExpenseSplit>, settlements: List<com.splitease.data.local.entities.Settlement> ->
        GroupData(group, members, expenses, splits, settlements)
    }

    // Combine sync state
    private data class SyncState(
        val pendingExpenseIds: Set<String>,
        val pendingSettlementIds: Set<String>,
        val failedOperations: List<SyncOperation>
    )

    private val syncState = combine(
        pendingExpenseIds,
        pendingSettlementIds,
        syncRepository.failedOperations
    ) { expenseIds: Set<String>, settlementIds: Set<String>, failedOps: List<SyncOperation> ->
        SyncState(expenseIds, settlementIds, failedOps.filter { it.failureType != SyncFailureType.AUTH })
    }

    val uiState: StateFlow<GroupDetailUiState> = combine(
        groupData,
        syncState,
        _executingSettlements,
        _retryTrigger,
        _settlementMode
    ) { data: GroupData, sync: SyncState, executingSettlements: Set<String>, _: Int, settlementMode: SettlementMode ->
        val group = data.group
        val members = data.members
        val expenses = data.expenses
        val splits = data.splits
        val persistedSettlements = data.settlements

        if (group == null) {
            GroupDetailUiState.Error("Group not found")
        } else {
            // Sort members: creator first, then alphabetically
            val sortedMembers = members.sortedWith(
                compareBy<User> { it.id != group.createdBy }.thenBy { it.name }
            )

            // Compute balances as derived state (no caching)
            // Includes persisted settlements in calculation
            val balances = if (expenses.isEmpty() && persistedSettlements.isEmpty()) {
                emptyMap()
            } else {
                BalanceCalculator.calculate(expenses, splits, persistedSettlements)
            }

            // Compute settlements as derived state from balances
            // Uses current settlement mode (UI-only, defaults to SIMPLIFIED)
            val settlements = SettlementCalculator.calculate(balances, settlementMode)

            // Compute group-scoped pending count
            val groupExpenseIds = expenses.map { it.id }.toSet()
            val groupSettlementIds = persistedSettlements.map { it.id }.toSet()
            val pendingGroupSyncCount = 
                (sync.pendingExpenseIds intersect groupExpenseIds).size + 
                (sync.pendingSettlementIds intersect groupSettlementIds).size

            // Compute group-scoped failure status
            val groupFailedSyncCount = sync.failedOperations.count { op ->
                 when (op.entityType) {
                     SyncEntityType.GROUP -> op.entityId == group.id
                     SyncEntityType.EXPENSE -> groupExpenseIds.contains(op.entityId)
                     SyncEntityType.SETTLEMENT -> groupSettlementIds.contains(op.entityId)
                 }
            }

            GroupDetailUiState.Success(
                group = group,
                members = sortedMembers,
                expenses = expenses,
                balances = balances,
                settlements = settlements,
                executingSettlements = executingSettlements,
                pendingExpenseIds = sync.pendingExpenseIds,
                pendingSettlementIds = sync.pendingSettlementIds,
                pendingGroupSyncCount = pendingGroupSyncCount,
                groupFailedSyncCount = groupFailedSyncCount,
                settlementMode = settlementMode
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GroupDetailUiState.Loading
    )

    fun retry() {
        viewModelScope.launch {
            _retryTrigger.value++
        }
    }

    /**
     * Toggle settlement mode between SIMPLIFIED and PROPORTIONAL.
     * This is UI-only and resets on screen re-entry.
     */
    fun toggleSettlementMode(simplify: Boolean) {
        _settlementMode.value = if (simplify) SettlementMode.SIMPLIFIED else SettlementMode.PROPORTIONAL
    }


    /**
     * Executes a settlement with optional custom amount.
     * Amount is normalized to 2 decimal places for consistency.
     */
    fun executeSettlement(suggestion: SettlementSuggestion, amount: BigDecimal = suggestion.amount) {
        val key = suggestion.key
        if (_executingSettlements.value.contains(key)) return

        viewModelScope.launch {
            _executingSettlements.value = _executingSettlements.value + key
            try {
                // Normalize amount to 2 decimals (defensive against weird inputs)
                val normalized = amount.setScale(2, RoundingMode.HALF_UP)
                
                settlementRepository.executeSettlement(
                    groupId = groupId,
                    fromUserId = suggestion.fromUserId,
                    toUserId = suggestion.toUserId,
                    amount = normalized
                )
                _eventChannel.send(GroupDetailEvent.ShowSnackbar("Settlement recorded"))
            } catch (e: Exception) {
                _eventChannel.send(GroupDetailEvent.ShowSnackbar("Failed to record settlement"))
            } finally {
                _executingSettlements.value = _executingSettlements.value - key
            }
        }
    }
}
