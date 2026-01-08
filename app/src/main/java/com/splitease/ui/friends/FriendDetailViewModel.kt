package com.splitease.ui.friends

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.connection.ClaimStatus
import com.splitease.data.connection.ConnectionManager
import com.splitease.data.connection.InviteResult
import com.splitease.data.connection.MergeResult
import com.splitease.data.local.dao.UserDao
import com.splitease.data.local.entities.ConnectionStatus
import com.splitease.data.repository.BalanceSummaryRepository
import com.splitease.data.repository.FriendLedgerItem
import com.splitease.data.repository.FriendTransactionsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Connection status for UI display.
 */
sealed class ConnectionUiState {
    object None : ConnectionUiState()
    object Loading : ConnectionUiState()
    data class InviteCreated(val inviteToken: String) : ConnectionUiState()
    data class Claimed(val claimerName: String) : ConnectionUiState()
    object Merged : ConnectionUiState()
    data class Error(val message: String) : ConnectionUiState()
}

/**
 * One-off navigation events emitted by ViewModel.
 */
sealed class FriendDetailEvent {
    /**
     * Navigate back after phantom merge completes.
     * The phantom user no longer exists.
     */
    object NavigateBackAfterMerge : FriendDetailEvent()
}

data class FriendDetailUiState(
    val friendId: String = "",
    val friendName: String = "",
    val balance: BigDecimal = BigDecimal.ZERO, // Positive = they owe you, Negative = you owe them
    val balanceDisplayText: String = "",
    val transactions: List<FriendLedgerItem> = emptyList(),
    val isLoading: Boolean = true,
    val connectionState: ConnectionUiState = ConnectionUiState.None,
    val isMerging: Boolean = false
)

@HiltViewModel
class FriendDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val friendTransactionsRepository: FriendTransactionsRepository,
    private val balanceSummaryRepository: BalanceSummaryRepository,
    private val userDao: UserDao,
    private val connectionManager: ConnectionManager
) : ViewModel() {
    
    private val friendId: String = savedStateHandle.get<String>("friendId") ?: ""
    
    private val _uiState = MutableStateFlow(FriendDetailUiState(friendId = friendId))
    val uiState: StateFlow<FriendDetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FriendDetailEvent>()
    val events: SharedFlow<FriendDetailEvent> = _events.asSharedFlow()
    
    init {
        loadFriendDetails()
        observeConnectionState()
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
                
                Triple(friend, transactions, Pair(balance, balanceText))
            }.collectLatest { (friend, transactions, balanceData) ->
                _uiState.update { current ->
                    current.copy(
                        friendId = friendId,
                        friendName = friend?.name ?: friendId.take(8),
                        balance = balanceData.first,
                        balanceDisplayText = balanceData.second,
                        transactions = transactions,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionManager.observeConnectionState(friendId).collectLatest { entity ->
                val connectionUiState = when (entity?.status) {
                    ConnectionStatus.INVITE_CREATED -> ConnectionUiState.InviteCreated(entity.inviteToken)
                    ConnectionStatus.CLAIMED -> ConnectionUiState.Claimed(entity.claimedByName ?: "Someone")
                    ConnectionStatus.MERGED -> ConnectionUiState.Merged
                    null -> ConnectionUiState.None
                }
                _uiState.update { it.copy(connectionState = connectionUiState) }
            }
        }
    }

    /**
     * Create an invite for this phantom user.
     */
    fun createInvite() {
        viewModelScope.launch {
            _uiState.update { it.copy(connectionState = ConnectionUiState.Loading) }
            when (val result = connectionManager.createInvite(friendId)) {
                is InviteResult.Success -> {
                    _uiState.update { it.copy(connectionState = ConnectionUiState.InviteCreated(result.inviteToken)) }
                }
                is InviteResult.Error -> {
                    _uiState.update { it.copy(connectionState = ConnectionUiState.Error(result.message)) }
                }
            }
        }
    }

    /**
     * Refresh connection status from server.
     * Called when user opens Friend Detail screen or manually refreshes.
     */
    fun refreshConnectionStatus() {
        viewModelScope.launch {
            when (val result = connectionManager.checkInviteStatus(friendId)) {
                is ClaimStatus.Claimed -> {
                    _uiState.update { it.copy(connectionState = ConnectionUiState.Claimed(result.name)) }
                }
                is ClaimStatus.Error -> {
                    // Don't overwrite current state on error
                }
                else -> {
                    // Pending, NotFound, Expired - handled by observeConnectionState
                }
            }
        }
    }

    /**
     * Finalize connection by merging phantom into real user.
     * Only works when status is CLAIMED.
     *
     * After successful merge, emits [FriendDetailEvent.NavigateBackAfterMerge]
     * because the phantom user is deleted.
     */
    fun finalizeConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isMerging = true) }
            when (val result = connectionManager.mergeIfClaimed(friendId)) {
                is MergeResult.Success -> {
                    // Phantom user is deleted - update state and emit navigation event
                    _uiState.update { it.copy(connectionState = ConnectionUiState.Merged, isMerging = false) }
                    _events.emit(FriendDetailEvent.NavigateBackAfterMerge)
                }
                is MergeResult.NotClaimed -> {
                    _uiState.update {
                        it.copy(
                            connectionState = ConnectionUiState.Error("Cannot merge - invite not claimed"),
                            isMerging = false
                        )
                    }
                }
                is MergeResult.Error -> {
                    _uiState.update {
                        it.copy(
                            connectionState = ConnectionUiState.Error(result.message),
                            isMerging = false
                        )
                    }
                }
            }
        }
    }
}
