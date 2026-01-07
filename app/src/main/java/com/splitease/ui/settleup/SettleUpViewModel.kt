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
    private val userContext: UserContext
) : ViewModel() {

    private val friendId: String = checkNotNull(savedStateHandle["friendId"])
    
    private val _uiState = MutableStateFlow(SettleUpUiState(friendId = friendId))
    val uiState: StateFlow<SettleUpUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    /**
     * Loads the friend's display name and current balance and updates the UI state.
     *
     * Sets `isLoading` while data is being fetched, updates `friendName` and `balance`
     * when values are available, and sets `errorMessage` if loading fails.
     */
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

    /**
     * Updates the current amount input when the provided string is empty or a numeric value with up to two decimal places.
     *
     * If the input is valid, updates the UI state's `amountInput` and clears `errorMessage`. Invalid input is ignored.
     *
     * @param newAmount The new amount text entered by the user; allowed formats: empty or digits with optional decimal and up to two fractional digits (e.g., "123", "12.34").
     */
    fun onAmountChanged(newAmount: String) {
        // Simple regex validation for currency
        if (newAmount.isEmpty() || newAmount.matches(Regex("^\\d*\\.?\\d{0,2}\$"))) {
             _uiState.update { it.copy(amountInput = newAmount, errorMessage = null) }
        }
    }

    /**
     * Initiates a settlement using the current amount input and updates the UI state.
     *
     * If the UI state's `canSettle` is false, the call is ignored. Otherwise the function:
     * - sets `isLoading` while working,
     * - reads the amount from `amountInput` and the current user ID from the user context,
     * - if the user context is missing sets `errorMessage` to `"User context missing"` and clears loading,
     * - creates a settlement with direction determined by `balance`: if `balance < 0` the settlement is from the current user to the friend; otherwise it is from the friend to the current user,
     * - on success sets `isSettled` to `true` and clears loading,
     * - on failure sets `errorMessage` to the exception message and clears loading.
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
                
                // Determine direction
                // If balance is positive (> 0), Friend owes Me. Friend pays Me.
                // If balance is negative (< 0), I owe Friend. I pay Friend.
                if (balance < BigDecimal.ZERO) {
                    // I owe them. From = Me, To = Friend
                    settlementRepository.createSettlement(
                        fromUserId = currentUserId,
                        toUserId = friendId,
                        amount = amount
                    )
                } else {
                    // They owe me. From = Friend, To = Me
                    settlementRepository.createSettlement(
                        fromUserId = friendId,
                        toUserId = currentUserId,
                        amount = amount
                    )
                }

                _uiState.update { it.copy(isLoading = false, isSettled = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }
    
    /**
     * Clears the settled flag in the UI state.
     *
     * Updates the ViewModel state so `isSettled` becomes `false`, allowing the UI to return to a non-settled state.
     */
    fun resetSettledState() {
        _uiState.update { it.copy(isSettled = false) }
    }
}