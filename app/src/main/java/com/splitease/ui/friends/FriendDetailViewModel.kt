package com.splitease.ui.friends

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.UserDao
import com.splitease.data.repository.BalanceSummaryRepository
import com.splitease.data.repository.FriendTransactionItem
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

data class FriendDetailUiState(
    val friendId: String = "",
    val friendName: String = "",
    val balance: BigDecimal = BigDecimal.ZERO, // Positive = they owe you, Negative = you owe them
    val balanceDisplayText: String = "",
    val transactions: List<FriendTransactionItem> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class FriendDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val friendTransactionsRepository: FriendTransactionsRepository,
    private val balanceSummaryRepository: BalanceSummaryRepository,
    private val userDao: UserDao
) : ViewModel() {
    
    private val friendId: String = savedStateHandle.get<String>("friendId") ?: ""
    
    private val _uiState = MutableStateFlow(FriendDetailUiState(friendId = friendId))
    val uiState: StateFlow<FriendDetailUiState> = _uiState.asStateFlow()
    
    init {
        loadFriendDetails()
    }
    
    private fun loadFriendDetails() {
        viewModelScope.launch {
            combine(
                userDao.getUser(friendId),
                friendTransactionsRepository.getTransactionsForFriend(friendId),
                balanceSummaryRepository.getDashboardSummary()
            ) { friend, transactions, dashboardSummary ->
                // Find friend's balance from dashboard summary
                val friendBalance = dashboardSummary.friendBalances.find { it.friendId == friendId }
                val balance = friendBalance?.balance ?: BigDecimal.ZERO
                
                val balanceText = when {
                    balance > BigDecimal.ZERO -> "owes you ₹${balance}"
                    balance < BigDecimal.ZERO -> "you owe ₹${balance.abs()}"
                    else -> "settled up"
                }
                
                FriendDetailUiState(
                    friendId = friendId,
                    friendName = friend?.name ?: friendId.take(8),
                    balance = balance,
                    balanceDisplayText = balanceText,
                    transactions = transactions,
                    isLoading = false
                )
            }.collectLatest { state ->
                _uiState.value = state
            }
        }
    }
}
