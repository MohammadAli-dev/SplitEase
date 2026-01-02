package com.splitease.ui.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.ExpenseDao
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.User
import com.splitease.domain.BalanceCalculator
import com.splitease.domain.SettlementCalculator
import com.splitease.domain.SettlementSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

sealed interface GroupDetailUiState {
    data object Loading : GroupDetailUiState
    data class Success(
        val group: Group,
        val members: List<User>,
        val expenses: List<Expense>,
        val balances: Map<String, BigDecimal>,
        val settlements: List<SettlementSuggestion>
    ) : GroupDetailUiState
    data class Error(val message: String) : GroupDetailUiState
}

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupDao: GroupDao,
    private val expenseDao: ExpenseDao
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId") ?: ""

    private val _retryTrigger = MutableStateFlow(0)

    val uiState: StateFlow<GroupDetailUiState> = combine(
        groupDao.getGroup(groupId),
        groupDao.getGroupMembersWithDetails(groupId),
        expenseDao.getExpensesForGroup(groupId),
        expenseDao.getAllExpenseSplitsForGroup(groupId),
        _retryTrigger
    ) { group, members, expenses, splits, _ ->
        if (group == null) {
            GroupDetailUiState.Error("Group not found")
        } else {
            // Sort members: creator first, then alphabetically
            val sortedMembers = members.sortedWith(
                compareBy<User> { it.id != group.createdBy }.thenBy { it.name }
            )

            // Compute balances as derived state (no caching)
            val balances = if (expenses.isEmpty()) {
                emptyMap()
            } else {
                BalanceCalculator.calculate(expenses, splits)
            }

            // Compute settlements as derived state from balances
            val settlements = SettlementCalculator.calculate(balances)

            GroupDetailUiState.Success(group, sortedMembers, expenses, balances, settlements)
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
}

