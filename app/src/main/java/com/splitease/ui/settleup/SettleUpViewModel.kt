package com.splitease.ui.settleup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.UserDao
import com.splitease.data.repository.BalanceSummaryRepository
import com.splitease.data.repository.SettlementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import com.splitease.data.repository.PersonRepository
import com.splitease.data.local.entities.Person
import javax.inject.Inject

data class SettleUpUiState(
    val friendId: String = "",
    val friendName: String = "",
    val availablePersons: List<Person> = emptyList(),
    val payerPersonId: String? = null,
    val receiverPersonId: String? = null,
    val balance: BigDecimal = BigDecimal.ZERO, // Informational only
    val amountInput: String = "",
    val isLoading: Boolean = false,
    val isSettled: Boolean = false,
    val errorMessage: String? = null
) {
    val canSettle: Boolean
        get() {
            val amount = amountInput.toBigDecimalOrNull() ?: return false
            return amount > BigDecimal.ZERO && 
                   payerPersonId != null && 
                   receiverPersonId != null && 
                   payerPersonId != receiverPersonId
        }

    val amountError: String?
        get() = null // Removed balance restriction for settling
}

@HiltViewModel
class SettleUpViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val balanceSummaryRepository: BalanceSummaryRepository,
    private val settlementRepository: SettlementRepository,
    private val personRepository: PersonRepository,
    private val userContext: UserContext,
    private val friendTransactionsRepository: com.splitease.data.repository.FriendTransactionsRepository
) : ViewModel() {

    private val friendId: String = checkNotNull(savedStateHandle["friendId"])
    
    private val _uiState = MutableStateFlow(SettleUpUiState(friendId = friendId))
    val uiState: StateFlow<SettleUpUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            // Observe Identity for Name & Canonical ID
            launch {
                friendTransactionsRepository.getFriendIdentity(friendId).collect { identity ->
                    _uiState.update { it.copy(friendName = identity.displayName) }
                    
                    // Once identity is resolved, check available persons to auto-select
                    // We need `availablePersons` loaded first? Or combine?
                    // Let's rely on PersonRepository flow below.
                }
            }
            
            // Parallel load: Persons
            launch {
                // We need the Resolved Identity to pick the right friend in the list
                // So strict dependency: Identity -> then pick from Persons.
                // Or combine Persons + Identity.
                
                combine(
                    personRepository.getAllPersons(),
                    friendTransactionsRepository.getFriendIdentity(friendId),
                    userContext.userId
                ) { persons: List<Person>, identity: com.splitease.data.identity.model.ResolvedParticipant, currentUserId: String ->
                    Triple(persons, identity, currentUserId)
                }.collect { (persons, identity, currentUserId) ->
                    _uiState.update { it.copy(availablePersons = persons) }
                    
                    // Initial Participant Setup (only if not set)
                    if (_uiState.value.payerPersonId == null) {
                         setupInitialParticipants(persons, identity, currentUserId)
                    }
                }
            }

            // Load informational balance
            balanceSummaryRepository.getBalanceWithFriend(friendId)
                .onEach { balance ->
                    _uiState.update { it.copy(balance = balance) }
                }
                .catch { /* ignore */ }
                .collect()
        }
    }

    private fun setupInitialParticipants(
        persons: List<Person>, 
        identity: com.splitease.data.identity.model.ResolvedParticipant,
        currentUserId: String?
    ) {
        val me = persons.find { it.linkedUserId == currentUserId }
        // Use Stable ID from resolver to finding friend
        val friend = persons.find { it.id == identity.stableId }
        
        // If friend not in the list (e.g. data desync), we rely on identity.stableId
        val receiverId = friend?.id ?: identity.stableId

        _uiState.update { state ->
            state.copy(
                friendName = identity.displayName,
                payerPersonId = me?.id, 
                receiverPersonId = receiverId, 
                isLoading = false
            )
        }
    }
    
    fun setPayer(personId: String) {
        _uiState.update { it.copy(payerPersonId = personId) }
    }

    fun setReceiver(personId: String) {
        _uiState.update { it.copy(receiverPersonId = personId) }
    }
    
    fun swapPayerReceiver() {
         _uiState.update { 
             it.copy(
                 payerPersonId = it.receiverPersonId,
                 receiverPersonId = it.payerPersonId
             )
         }
    }

    fun onAmountChanged(newAmount: String) {
        // Simple regex validation for currency
        if (newAmount.isEmpty() || newAmount.matches(Regex("^\\d*\\.?\\d{0,2}\$"))) {
             _uiState.update { it.copy(amountInput = newAmount, errorMessage = null) }
        }
    }

    /**
     * Initiates a settlement for the amount entered in the UI and updates the view state to reflect progress and outcome.
     *
     * If the UI state does not allow settling, the call returns immediately. Otherwise the function sets `isLoading`
     * to true, reads the entered amount, the current balance with the friend, and the current user id from the user
     * context. If the user id is missing, it sets `errorMessage` to "User context missing" and stops. If the balance is
     * zero, it sets `errorMessage` to "No balance to settle" and stops. For a negative balance the current user pays the
     * friend; for a positive balance the friend pays the current user. A settlement is created via the repository with
     * currency "INR". On success the UI state is updated to `isSettled = true` and `isLoading = false`. On failure the
     * UI state is updated with the exception message and `isLoading = false`.
     */
    fun onSettleUp() {
        if (!_uiState.value.canSettle) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val state = _uiState.value
                val amount = state.amountInput.toBigDecimal()


                if (state.payerPersonId == null || state.receiverPersonId == null) {
                     _uiState.update { it.copy(isLoading = false, errorMessage = "Select payer and receiver") }
                     return@launch
                }
                
                // FAIL-CLOSED: Check balance/history for currency context
                val transactions = friendTransactionsRepository.getTransactionsForFriend(friendId).first()
                val contextCurrency = transactions.firstOrNull()?.currency
                
                if (contextCurrency == null) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Cannot settle: No transaction history to determine currency.") }
                    return@launch
                }
                
                // Explicit Settlement
                settlementRepository.createSettlement(
                    fromUserId = state.payerPersonId, // Mapping Person ID as User ID for patched Repo
                    toUserId = state.receiverPersonId, // Mapping Person ID as User ID for patched Repo
                    amount = amount,
                    currency = contextCurrency
                )

                _uiState.update { it.copy(isLoading = false, isSettled = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }
    
    fun resetSettledState() {
        _uiState.update { it.copy(isSettled = false) }
    }
}