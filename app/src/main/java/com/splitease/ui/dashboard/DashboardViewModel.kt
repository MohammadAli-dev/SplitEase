package com.splitease.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.Group
import com.splitease.data.repository.BalanceSummaryRepository
import com.splitease.data.repository.FriendBalance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Friend balance with resolved name for UI display.
 */
data class FriendBalanceUi(
    val friendId: String,
    val friendName: String,
    val balance: BigDecimal, // positive = they owe you, negative = you owe them
    val displayText: String // "owes you ₹X" or "you owe ₹X"
)

data class DashboardUiState(
    val totalOwed: BigDecimal = BigDecimal.ZERO,
    val totalOwing: BigDecimal = BigDecimal.ZERO,
    val groups: List<Group> = emptyList(),
    val friendBalances: List<FriendBalanceUi> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val balanceSummaryRepository: BalanceSummaryRepository,
    private val groupDao: GroupDao,
    private val userDao: UserDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboardData()
    }

    private fun loadDashboardData() {
        viewModelScope.launch {
            combine(
                balanceSummaryRepository.getDashboardSummary(),
                groupDao.getAllGroups(),
                userDao.getAllUsers()
            ) { summary, groups, allUsers ->
                // Build name lookup
                val userNameMap = allUsers.associate { it.id to it.name }
                
                // Map friend balances to UI model with resolved names
                val friendBalancesUi = summary.friendBalances.map { fb ->
                    val name = userNameMap[fb.friendId] ?: fb.friendId.take(8)
                    val displayText = if (fb.balance > BigDecimal.ZERO) {
                        "owes you ₹${fb.balance}"
                    } else {
                        "you owe ₹${fb.balance.abs()}"
                    }
                    FriendBalanceUi(
                        friendId = fb.friendId,
                        friendName = name,
                        balance = fb.balance,
                        displayText = displayText
                    )
                }
                
                DashboardUiState(
                    totalOwed = summary.totalOwed,
                    totalOwing = summary.totalOwing,
                    groups = groups,
                    friendBalances = friendBalancesUi,
                    isLoading = false
                )
            }.collectLatest { state ->
                _uiState.value = state
            }
        }
    }
}
