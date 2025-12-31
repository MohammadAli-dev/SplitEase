package com.splitease.ui.expense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.repository.AuthRepository
import com.splitease.data.repository.ExpenseRepository
import com.splitease.domain.SplitValidationResult
import com.splitease.domain.SplitValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import java.util.UUID
import javax.inject.Inject

enum class SplitType {
    EQUAL, EXACT, PERCENTAGE, SHARES
}

data class AddExpenseUiState(
    val title: String = "",
    val amountText: String = "",
    val splitType: SplitType = SplitType.EQUAL,
    val payerId: String = "",
    val selectedParticipants: List<String> = emptyList(), // Sorted list of userIds
    val groupMembers: List<String> = emptyList(), // Available members
    val validationResult: SplitValidationResult = SplitValidationResult.Valid,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val expenseRepository: ExpenseRepository,
    private val authRepository: AuthRepository,
    private val groupDao: GroupDao
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId") ?: ""
    
    private val _uiState = MutableStateFlow(AddExpenseUiState())
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()

    init {
        loadGroupMembers()
        setDefaultPayer()
    }

    private fun loadGroupMembers() {
        viewModelScope.launch {
            groupDao.getGroupMembers(groupId).collectLatest { members ->
                // Sort for deterministic ordering
                val sortedMemberIds = members.map { it.userId }.sorted()
                _uiState.value = _uiState.value.copy(
                    groupMembers = sortedMemberIds,
                    selectedParticipants = sortedMemberIds // Default: all members
                )
            }
        }
    }

    private fun setDefaultPayer() {
        val currentUserId = authRepository.getCurrentUserId()
        if (currentUserId != null) {
            _uiState.value = _uiState.value.copy(payerId = currentUserId)
        }
    }

    fun updateTitle(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
    }

    fun updateAmount(amountText: String) {
        _uiState.value = _uiState.value.copy(amountText = amountText)
        calculateSplits()
    }

    fun updateSplitType(splitType: SplitType) {
        _uiState.value = _uiState.value.copy(splitType = splitType)
        calculateSplits()
    }

    fun updatePayer(payerId: String) {
        _uiState.value = _uiState.value.copy(payerId = payerId)
    }

    fun updateParticipants(participants: List<String>) {
        // Ensure deterministic ordering
        _uiState.value = _uiState.value.copy(selectedParticipants = participants.sorted())
        calculateSplits()
    }

    private fun calculateSplits() {
        val state = _uiState.value
        
        // Safe amount parsing
        val amount = try {
            if (state.amountText.isBlank()) {
                _uiState.value = state.copy(validationResult = SplitValidationResult.Valid)
                return
            }
            BigDecimal(state.amountText).setScale(2, RoundingMode.HALF_EVEN)
        } catch (e: NumberFormatException) {
            _uiState.value = state.copy(
                validationResult = SplitValidationResult.Invalid("Invalid amount format")
            )
            return
        }

        if (state.selectedParticipants.isEmpty()) {
            _uiState.value = state.copy(
                validationResult = SplitValidationResult.Invalid("No participants selected")
            )
            return
        }

        // For Sprint 5, we only implement EQUAL split
        // Other split types can be added later
        when (state.splitType) {
            SplitType.EQUAL -> {
                val splits = SplitValidator.calculateEqualSplit(amount, state.selectedParticipants.size)
                val validationResult = SplitValidator.validateSplit(amount, splits)
                _uiState.value = state.copy(validationResult = validationResult)
            }
            else -> {
                _uiState.value = state.copy(
                    validationResult = SplitValidationResult.Invalid("Split type not yet implemented")
                )
            }
        }
    }

    fun saveExpense() {
        val state = _uiState.value

        // Validation-before-save: Only proceed if validation is Valid
        if (state.validationResult !is SplitValidationResult.Valid) {
            _uiState.value = state.copy(errorMessage = "Please fix validation errors before saving")
            return
        }

        if (state.title.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Title is required")
            return
        }

        val amount = try {
            BigDecimal(state.amountText).setScale(2, RoundingMode.HALF_EVEN)
        } catch (e: NumberFormatException) {
            _uiState.value = state.copy(errorMessage = "Invalid amount")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true)
            
            try {
                val expenseId = UUID.randomUUID().toString()
                val expense = Expense(
                    id = expenseId,
                    groupId = groupId,
                    title = state.title,
                    amount = amount,
                    currency = "INR",
                    date = Date(),
                    payerId = state.payerId,
                    createdBy = state.payerId,
                    syncStatus = "PENDING"
                )

                val splits = SplitValidator.calculateEqualSplit(amount, state.selectedParticipants.size)
                val expenseSplits = state.selectedParticipants.zip(splits).map { (userId, splitAmount) ->
                    ExpenseSplit(
                        expenseId = expenseId,
                        userId = userId,
                        amount = splitAmount
                    )
                }

                expenseRepository.addExpense(expense, expenseSplits)
                
                // Success - navigate back or show success message
                _uiState.value = state.copy(isLoading = false, errorMessage = null)
            } catch (e: Exception) {
                _uiState.value = state.copy(
                    isLoading = false,
                    errorMessage = "Failed to save expense: ${e.message}"
                )
            }
        }
    }
}
