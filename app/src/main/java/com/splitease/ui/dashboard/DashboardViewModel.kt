package com.splitease.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.dao.PersonDao
import com.splitease.data.local.entities.Group
import com.splitease.data.local.entities.Person
import com.splitease.data.local.entities.User
import com.splitease.data.repository.BalanceSummaryRepository
import com.splitease.data.repository.SyncRepository
import com.splitease.data.repository.UserRepository
import com.splitease.data.sync.SyncConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
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
    val ledgerBalances: List<FriendBalanceUi> = emptyList(), // Balances derived from expenses/settlements
    val knownUserCount: Int = 0, // Total known users (excluding self), derived from users table
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val isRefreshing: Boolean = false
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val balanceSummaryRepository: BalanceSummaryRepository,
    private val groupDao: GroupDao,
    private val userContext: UserContext,
    private val syncRepository: SyncRepository,
    private val userRepository: com.splitease.data.repository.UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboardData()
    }

    /**
     * Start a manual synchronization with a cosmetic UI acknowledgment delay.
     */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            
            // Trigger the sync work (fire-and-forget)
            syncRepository.triggerManualSync()
            
            // Artificial delay for UI acknowledgement
            kotlinx.coroutines.delay(SyncConstants.REFRESH_ACK_UI_DELAY_MS)
            
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * Create a phantom user record for a contact using the provided name and optional contact details.
     *
     * The dashboard UI will reflect this change automatically via existing data flows; no explicit UI refresh is performed here.
     *
     * @param name The display name for the phantom user.
     * @param email Optional email address for the phantom user.
     * @param phone Optional phone number for the phantom user.
     */
    fun createPhantomUser(name: String, email: String? = null, phone: String? = null) {
        viewModelScope.launch {
            userRepository.createPhantomUser(name, email, phone)
            // No need to manually refresh UI; Flow observation in loadDashboardData will handle it
        }
    }

    /**
     * Observes repository streams and updates the dashboard UI state.
     *
     * Launches a coroutine that combines balance summary, groups, and users to produce a
     * DashboardUiState with resolved friend display names and formatted balance text, then
     * publishes the resulting state to `_uiState`.
     */
    private fun loadDashboardData() {
        viewModelScope.launch {
            combine(
                balanceSummaryRepository.getDashboardSummary(),
                groupDao.getAllGroups(),
                userContext.userId
            ) { summary, groups, _ ->
                
                // Map ledger balances to UI model (Names are already resolved by Repository)
                val ledgerBalancesUi = summary.friendBalances.map { fb ->
                    // TEMPORARY: Assume INR. TODO: Plumb currency.
                    val currency = "INR"
                    val formattedBalance = com.splitease.ui.common.Formatters.formatMoney(fb.balance.abs(), currency)
                    
                    val displayText = if (fb.balance > BigDecimal.ZERO) {
                        "owes you $formattedBalance"
                    } else {
                        "you owe $formattedBalance"
                    }
                    FriendBalanceUi(
                        friendId = fb.friendId,
                        friendName = fb.friendName, // Pre-resolved by Repository
                        balance = fb.balance,
                        displayText = displayText
                    )
                }
                
                // Known User Count - Previously counted Persons. 
                // Now irrelevant for pure dashboard view, or we can use friends count.
                // Setting to ledgerBalancesUi.size for now as a valid approximation of "Active Friends".
                val knownUserCount = ledgerBalancesUi.size
                
                DashboardUiState(
                    totalOwed = summary.totalOwed,
                    totalOwing = summary.totalOwing,
                    groups = groups,
                    ledgerBalances = ledgerBalancesUi,
                    knownUserCount = knownUserCount,
                    isLoading = false,
                    isSyncing = false,
                    isRefreshing = false
                )
            }.collectLatest { repoState ->
                _uiState.update { currentState ->
                    repoState.copy(
                        isRefreshing = currentState.isRefreshing,
                        isSyncing = currentState.isSyncing
                    )
                }
            }
        }
    }
}
