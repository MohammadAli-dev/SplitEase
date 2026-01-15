package com.splitease.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.entities.Group
import com.splitease.data.repository.BalanceSummaryRepository
import com.splitease.data.repository.SyncRepository
import com.splitease.data.repository.UserRepository
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
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val balanceSummaryRepository: BalanceSummaryRepository,
    private val groupDao: GroupDao,
    private val userRepository: UserRepository,
    private val syncRepository: SyncRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboardData()
    }

    /**
     * Trigger a manual sync (pull-to-refresh).
     * Observes actual WorkManager completion instead of using a hardcoded delay.
     */
    fun triggerSync() {
        if (_uiState.value.isSyncing) return // Prevent double-tap
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            
            // Trigger the sync work
            syncRepository.triggerManualSync()
            
            // Observe the work completion
            syncRepository.observeManualSyncWork()
                .collect { isFinished ->
                    if (isFinished) {
                        _uiState.value = _uiState.value.copy(isSyncing = false)
                        return@collect
                    }
                }
        }
    }

    /**
     * Create a new phantom user.
     */
    fun createPhantomUser(name: String, email: String? = null, phone: String? = null) {
        viewModelScope.launch {
            userRepository.createPhantomUser(name, email, phone)
            // No need to manually refresh UI; Flow observation in loadDashboardData will handle it
        }
    }

    private fun loadDashboardData() {
        viewModelScope.launch {
            combine(
                balanceSummaryRepository.getDashboardSummary(),
                groupDao.getAllGroups(),
                userRepository.getAllUsers()
            ) { summary, groups, allUsers ->
                // Explicitly sort users by name for consistent UI display (Repository contract)
                val sortedUsers = allUsers.sortedBy { it.name }
                
                // Build name lookup
                val userNameMap = sortedUsers.associate { it.id to it.name }
                
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
                    isLoading = false,
                    isSyncing = _uiState.value.isSyncing
                )
            }.collectLatest { state ->
                _uiState.value = state
            }
        }
    }
}

