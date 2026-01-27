package com.splitease.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.entities.Group
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
    private val userRepository: UserRepository,
    private val userContext: UserContext,
    private val syncRepository: SyncRepository
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
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            
            // Trigger the sync work (fire-and-forget)
            syncRepository.triggerManualSync()
            
            // Artificial delay for UI acknowledgement
            kotlinx.coroutines.delay(SyncConstants.REFRESH_ACK_UI_DELAY_MS)
            
            _uiState.value = _uiState.value.copy(isRefreshing = false)
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
                userRepository.getAllUsers(),
                userContext.userId
            ) { summary, groups, allUsers, currentUserId ->
                // Explicitly sort users by name for consistent UI display (Repository contract)
                val sortedUsers = allUsers.sortedBy { it.name }
                
                // Build name lookup
                val userNameMap = sortedUsers.associate { it.id to it.name }
                
                // Count known users (all users except self) — this is the "friend existence" check
                val knownUserCount = sortedUsers.count { it.id != currentUserId }
                
                // Map ledger balances to UI model with resolved names
                val ledgerBalancesUi = summary.friendBalances.map { fb ->
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
                    ledgerBalances = ledgerBalancesUi,
                    knownUserCount = knownUserCount,
                    isLoading = false,
                    isSyncing = _uiState.value.isSyncing,
                    isRefreshing = _uiState.value.isRefreshing
                )
            }.collectLatest { state ->
                _uiState.value = state
            }
        }
    }
}
