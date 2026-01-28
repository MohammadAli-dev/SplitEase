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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class SettleUpUiState(
    val friendId: String = "",
    val friendName: String = "",
    val balance: BigDecimal = BigDecimal.ZERO, // Positive = They Owe You, Negative = You Owe Them
    val amountInput: String = "",
    val isLoading: Boolean = false,
    val isSettled: Boolean = false,
    val errorMessage: String? = null
) {
    val canSettle: Boolean
        get() {
            val amount = amountInput.toBigDecimalOrNull() ?: return false
            return amount > BigDecimal.ZERO && amount <= balance.abs()
        }

    val amountError: String?
        get() {
            val amount = amountInput.toBigDecimalOrNull()
            if (amount != null && amount > balance.abs()) return "Amount exceeds balance"
            return null
        }
}

@HiltViewModel
class SettleUpViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val balanceSummaryRepository: BalanceSummaryRepository,
    private val settlementRepository: SettlementRepository,
    private val userDao: UserDao,
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
            // Load friend name (suspend, one-shot)
            val friendName = try {
                userDao.getUser(friendId).first()?.name ?: "Unknown"
            } catch (e: Exception) {
                "Unknown"
            }

            // Load balance (flow)
            balanceSummaryRepository.getBalanceWithFriend(friendId)
                .onEach { balance ->
                    _uiState.update { 
                        it.copy(
                            friendName = friendName,
                            balance = balance,
                            isLoading = false
                        ) 
                    }
                }
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load data") }
                }
                .collect()
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
                val amount = _uiState.value.amountInput.toBigDecimal()
                val balance = _uiState.value.balance
                val currentUserId = userContext.userId.first()

                if (currentUserId.isEmpty()) {
                     _uiState.update { it.copy(isLoading = false, errorMessage = "User context missing") }
                     return@launch
                }
                
                // FAIL-CLOSED: Check balance/history for currency context
                // We fetch the most recent transaction to determine currency.
                val transactions = friendTransactionsRepository.getTransactionsForFriend(friendId).first()
                val contextCurrency = transactions.firstOrNull()?.currency
                
                if (contextCurrency == null) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Cannot settle: No transaction history to determine currency.") }
                    return@launch
                }
                
                // Determine direction
                when {
                    balance.signum() == 0 -> {
                        // Zero balance - nothing to settle
                        _uiState.update { it.copy(isLoading = false, errorMessage = "No balance to settle") }
                        return@launch
                    }
                    balance.signum() < 0 -> {
                        // I owe them. From = Me, To = Friend
                        settlementRepository.createSettlement(
                            fromUserId = currentUserId,
                            toUserId = friendId,
                            amount = amount,
                            currency = contextCurrency
                        )
                    }
                    else -> {
                        // They owe me. From = Friend, To = Me
                        settlementRepository.createSettlement(
                            fromUserId = friendId,
                            toUserId = currentUserId,
                            amount = amount,
                            currency = contextCurrency
                        )
                    }
                }

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