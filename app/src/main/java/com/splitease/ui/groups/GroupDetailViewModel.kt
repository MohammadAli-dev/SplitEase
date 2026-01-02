package com.splitease.ui.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.SettlementDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.User
import com.splitease.data.repository.SettlementRepository
import com.splitease.domain.BalanceCalculator
import com.splitease.domain.SettlementCalculator
import com.splitease.domain.SettlementSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
        val executingSettlements: Set<String> = emptySet()
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
    private val settlementRepository: SettlementRepository
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId") ?: ""

    private val _retryTrigger = MutableStateFlow(0)
    private val _executingSettlements = MutableStateFlow<Set<String>>(emptySet())

    private val _eventChannel = Channel<GroupDetailEvent>()
    val events = _eventChannel.receiveAsFlow()

    val uiState: StateFlow<GroupDetailUiState> = combine(
        groupDao.getGroup(groupId),
        groupDao.getGroupMembersWithDetails(groupId),
        expenseDao.getExpensesForGroup(groupId),
        expenseDao.getAllExpenseSplitsForGroup(groupId),
        settlementDao.getSettlementsForGroup(groupId),
        _executingSettlements,
        _retryTrigger
    ) { args ->
        val group = args[0] as? Group
        val members = args[1] as List<User>
        val expenses = args[2] as List<Expense>
        val splits = args[3] as List<ExpenseSplit>
        val persistedSettlements = args[4] as List<com.splitease.data.local.entities.Settlement>
        val executingSettlements = args[5] as Set<String>

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
            // Always recomputed—no local mutation of suggestion list
            val settlements = SettlementCalculator.calculate(balances)

            GroupDetailUiState.Success(
                group,
                sortedMembers,
                expenses,
                balances,
                settlements,
                executingSettlements
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
