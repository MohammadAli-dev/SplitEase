package com.splitease.ui.ledger

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.UserDao
import com.splitease.data.repository.BalanceSummaryRepository
import com.splitease.data.repository.FriendLedgerItem
import com.splitease.data.repository.FriendTransactionsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class PersonalLedgerUiState(
    val friendId: String = "",
    val friendName: String = "",
    val balance: BigDecimal = BigDecimal.ZERO, // Positive = they owe you, Negative = you owe them
    val balanceDisplayText: String = "",
    val ledgerItems: List<FriendLedgerItem> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * ViewModel for Friend Ledger screen.
 * 
 * Displays ALL expenses between current user and selected friend:
 * - Group expenses from shared groups
 * - Direct (non-group) expenses
 * 
 * The ledger is read-only. Navigation to edit happens via EditExpenseScreen.
 */
@HiltViewModel
class PersonalLedgerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val friendTransactionsRepository: FriendTransactionsRepository,
    private val balanceSummaryRepository: BalanceSummaryRepository,
    private val userDao: UserDao
) : ViewModel() {
    
    private val friendId: String = savedStateHandle.get<String>("friendId") ?: ""
    
    private val _uiState = MutableStateFlow(PersonalLedgerUiState(friendId = friendId))
    val uiState: StateFlow<PersonalLedgerUiState> = _uiState.asStateFlow()
    
    init {
        loadLedger()
    }
    
    private fun loadLedger() {
        viewModelScope.launch {
            combine(
                userDao.getUser(friendId),
                friendTransactionsRepository.getTransactionsForFriend(friendId),
                balanceSummaryRepository.getDashboardSummary()
            ) { friend, ledgerItems, dashboardSummary ->
                // Find friend's balance from dashboard summary
                val friendBalance = dashboardSummary.friendBalances.find { it.friendId == friendId }
                val balance = friendBalance?.balance ?: BigDecimal.ZERO
                
                val balanceText = when {
                    balance > BigDecimal.ZERO -> "owes you ₹${balance}"
                    balance < BigDecimal.ZERO -> "you owe ₹${balance.abs()}"
                    else -> "settled up"
                }
                
                PersonalLedgerUiState(
                    friendId = friendId,
                    friendName = friend?.name ?: friendId.take(8),
                    balance = balance,
                    balanceDisplayText = balanceText,
                    ledgerItems = ledgerItems,
                    isLoading = false
                )
            }.collectLatest { state ->
                _uiState.value = state
            }
        }
    }
}

