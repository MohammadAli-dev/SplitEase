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
    object Pending : ConnectionUiState()
    object NotFound : ConnectionUiState()
    object Expired : ConnectionUiState()
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

    /**
     * Show transient error message (snackbar/toast).
     */
    data class ShowError(val message: String) : FriendDetailEvent()
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
    
    /**
     * Loads and observes friend details, transactions, and dashboard summary then updates the UI state accordingly.
     *
     * Observes the friend entity, their transactions, and the dashboard summary; computes the friend's numeric balance and a
     * human-readable balance display string ("owes you ₹<amount>", "you owe ₹<amount>", or "settled up"); and updates
     * the ViewModel's internal `_uiState` with `friendId`, `friendName` (falls back to the first 8 chars of the id if the
     * user record is missing), `balance`, `balanceDisplayText`, `transactions`, and sets `isLoading` to false.
     */
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

    /**
     * Observes connection status changes for the current friend and updates the UI state accordingly.
     *
     * Collects updates from the connection manager, maps remote connection statuses to `ConnectionUiState`
     * variants (e.g., `InviteCreated`, `Claimed`, `Merged`, `None`), and applies the mapped value to the
     * view model's `uiState.connectionState`.
     */
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
     * Initiates creation of an invite for the current friend and updates the UI connection state.
     *
     * Sets the connection state to `Loading`, then to `InviteCreated(inviteToken)` on success
     * or to `Error(message)` on failure.
     */
    fun createInvite() {
        viewModelScope.launch {
            _uiState.update { it.copy(connectionState = ConnectionUiState.Loading) }
            when (val result = connectionManager.createInvite(friendId)) {
                is InviteResult.Success -> {
                    // Observer will pick up the new state from database
                    // No explicit state update needed here
                }
                is InviteResult.Error -> {
                    _uiState.update { it.copy(connectionState = ConnectionUiState.Error(result.message)) }
                }
            }
        }
    }

    /**
     * Refreshes the connection status for the current friend and updates UI state when the invite is claimed.
     *
     * If the server reports the invite as claimed, the `connectionState` is updated to `ConnectionUiState.Claimed`.
     * On error the existing UI state is preserved; other statuses (pending, not found, expired) are handled by the connection observer.
     */
    fun refreshConnectionStatus() {
        viewModelScope.launch {
            // Capture previous state to restore on error
            val previousState = _uiState.value.connectionState
            // Set loading state at start
            _uiState.update { it.copy(connectionState = ConnectionUiState.Loading) }

            when (val result = connectionManager.checkInviteStatus(friendId)) {
                is ClaimStatus.Claimed -> {
                    _uiState.update { it.copy(connectionState = ConnectionUiState.Claimed(result.name)) }
                }
                is ClaimStatus.Pending -> {
                    _uiState.update { it.copy(connectionState = ConnectionUiState.Pending) }
                }
                is ClaimStatus.NotFound -> {
                    _uiState.update { it.copy(connectionState = ConnectionUiState.NotFound) }
                    // Optionally notify user
                    _events.emit(FriendDetailEvent.ShowError("Invite not found."))
                }
                is ClaimStatus.Expired -> {
                    _uiState.update { it.copy(connectionState = ConnectionUiState.Expired) }
                    _events.emit(FriendDetailEvent.ShowError("Invite expired."))
                }
                is ClaimStatus.Error -> {
                    // Restore previous state on error (don't leave it as Loading)
                    _uiState.update { it.copy(connectionState = previousState) }
                    _events.emit(FriendDetailEvent.ShowError("Status check failed: ${result.message}"))
                }
            }
        }
    }


    /**
     * Merge the phantom friend into its claimed real user when the invite has been claimed.
     *
     * While running, sets the UI to a merging state. On success, updates the connection state to
     * `Merged`, clears the merging flag, and emits FriendDetailEvent.NavigateBackAfterMerge.
     * If the invite is not claimed or an error occurs, updates the connection state to an `Error`
     * with an explanatory message and clears the merging flag.
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