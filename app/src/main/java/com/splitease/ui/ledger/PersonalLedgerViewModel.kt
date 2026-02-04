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
    private val userDao: UserDao,
    private val personDao: com.splitease.data.local.dao.PersonDao
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
                personDao.getPersonByIdFlow(friendId),
                personDao.getPersonByLinkedUserIdFlow(friendId),
                friendTransactionsRepository.getTransactionsForFriend(friendId),
                balanceSummaryRepository.getDashboardSummary()
            ) { user, personById, personByLink, ledgerItems, dashboardSummary ->
                val resolvedName = user?.name ?: personById?.displayName ?: personByLink?.displayName ?: friendId.take(8)

                // Find friend's balance from dashboard summary
                // Note: BalanceSummaryRepository aggregates by canonical ID, so friendId (if canonical) matches directly.
                // If friendId is legacy UserID, we depend on DashboardSummary having looked it up? 
                // Wait, Dashboard uses Canonical ID. 
                // If I am viewing with 'friendId' (Legacy), I need to know my Canonical ID?
                // The DashboardSummary contains Canonical IDs.
                // We should match against resolved canonical if possible?
                // For now, assume friendId provided IS the ID used in Dashboard (or effectively matches).
                
                // Better: Check both if we can resolve.
                val canonicalId = personById?.id ?: personByLink?.id ?: friendId
                val friendBalance = dashboardSummary.friendBalances.find { it.friendId == canonicalId || it.friendId == friendId }
                
                val balance = friendBalance?.balance ?: BigDecimal.ZERO
                
                val currency = ledgerItems.firstOrNull()?.currency ?: "INR"
                val formattedBalance = com.splitease.ui.common.Formatters.formatMoney(balance.abs(), currency)
                
                val balanceText = when {
                    balance > BigDecimal.ZERO -> "owes you $formattedBalance"
                    balance < BigDecimal.ZERO -> "you owe $formattedBalance"
                    else -> "settled up"
                }
                
                Quintuple(resolvedName, balance, balanceText, ledgerItems, false)
            }.collectLatest { (name, balance, balanceText, items, isLoading) ->
                _uiState.value = PersonalLedgerUiState(
                    friendId = friendId,
                    friendName = name,
                    balance = balance,
                    balanceDisplayText = balanceText,
                    ledgerItems = items,
                    isLoading = isLoading
                )
            }
        }
    }
    
    private data class Quintuple<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}

