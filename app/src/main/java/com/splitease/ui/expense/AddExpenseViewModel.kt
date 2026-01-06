package com.splitease.ui.expense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.repository.ExpenseRepository
import com.splitease.domain.SplitValidationResult
import com.splitease.domain.SplitValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SplitType {
    EQUAL,
    EXACT,
    PERCENTAGE,
    SHARES
}

/** Normalizes a timestamp to the start of the day (00:00:00.000) in the user's local timezone. */
private fun normalizeToStartOfDay(millis: Long): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = millis
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

/**
 * UI state for Add Expense screen. splitPreview is derived state, recalculated on any input change.
 */
data class AddExpenseUiState(
        val title: String = "",
        val amountText: String = "",
        val splitType: SplitType = SplitType.EQUAL,
        val payerId: String = "",
        val selectedParticipants: List<String> = emptyList(),
        val groupMembers: List<String> = emptyList(),
        val userNames: Map<String, String> = emptyMap(), // userId -> userName mapping
        // Per-participant input fields
        val exactAmounts: Map<String, String> = emptyMap(),
        val percentages: Map<String, String> = emptyMap(),
        val shares: Map<String, Int> = emptyMap(),
        // Derived state: calculated split amounts per participant
        val splitPreview: Map<String, BigDecimal> = emptyMap(),
        val validationResult: SplitValidationResult = SplitValidationResult.Valid,
        val isLoading: Boolean = false,
        val isSaved: Boolean = false,
        val errorMessage: String? = null,
        val isEditMode: Boolean = false,
        /** Logical date of expense, normalized to start-of-day */
        val expenseDate: Long = normalizeToStartOfDay(System.currentTimeMillis()),
        val createdByUserId: String? = null
)

@HiltViewModel
class AddExpenseViewModel
@Inject
constructor(
        savedStateHandle: SavedStateHandle,
        private val expenseRepository: ExpenseRepository,
        private val userContext: UserContext,
        private val groupDao: GroupDao
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId") ?: ""
    private val expenseId: String? = savedStateHandle.get<String>("expenseId")

    private val _uiState = MutableStateFlow(AddExpenseUiState())
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()

    init {
        loadGroupMembers()
        if (expenseId != null) {
            _uiState.update { it.copy(isEditMode = true) }
            loadExpense(expenseId)
        }
        setDefaultPayer()
    }

    private fun loadExpense(id: String) {
        viewModelScope.launch {
            val expense = expenseRepository.getExpense(id).firstOrNull() ?: return@launch
            val splits = expenseRepository.getSplits(id).first()

            _uiState.update { state ->
                val inferredType = inferSplitType(splits, expense.amount)

                val exactAmounts =
                        if (inferredType == SplitType.EXACT) {
                            splits.associate { it.userId to it.amount.toPlainString() }
                        } else emptyMap()

                state.copy(
                        title = expense.title,
                        amountText = expense.amount.toPlainString(),
                        splitType = inferredType,
                        payerId = expense.payerId,
                        selectedParticipants = splits.map { it.userId }.sorted(),
                        exactAmounts = exactAmounts,
                        isEditMode = true,
                        createdByUserId = expense.createdByUserId
                )
            }
            recalculateSplits()
        }
    }

    private fun loadGroupMembers() {
        viewModelScope.launch {
            groupDao.getGroupMembersWithDetails(groupId).collectLatest { users ->
                val sortedUsers = users.sortedBy { it.name }
                val sortedMemberIds = sortedUsers.map { it.id }
                val userNamesMap = sortedUsers.associate { it.id to it.name }
                _uiState.update { it.copy(
                    groupMembers = sortedMemberIds,
                    selectedParticipants = sortedMemberIds,
                    userNames = userNamesMap,
                    shares = sortedMemberIds.associateWith { 1 } // Default 1 share each
                )}
                recalculateSplits()
            }
        }
    }

    private fun setDefaultPayer() {
        viewModelScope.launch {
            // Idiomatic: firstOrNull() handles empty flow gracefully
            val currentUserId = userContext.userId.firstOrNull()
            if (currentUserId != null) {
                _uiState.update { it.copy(payerId = currentUserId) }
            }
            // If null, leave payerId as default (empty string)
        }
    }

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateAmount(amountText: String) {
        _uiState.update { it.copy(amountText = amountText) }
        recalculateSplits()
    }

    fun updateSplitType(splitType: SplitType) {
        _uiState.update { it.copy(splitType = splitType) }
        recalculateSplits()
    }

    fun updatePayer(payerId: String) {
        _uiState.update { it.copy(payerId = payerId) }
    }

    /** Update expense date, normalized to start-of-day. */
    fun updateExpenseDate(dateMillis: Long) {
        _uiState.update { it.copy(expenseDate = normalizeToStartOfDay(dateMillis)) }
    }

    fun toggleParticipant(userId: String) {
        _uiState.update { state ->
            val current = state.selectedParticipants.toMutableList()
            if (userId in current) {
                current.remove(userId)
            } else {
                current.add(userId)
            }
            state.copy(selectedParticipants = current.sorted())
        }
        recalculateSplits()
    }

    fun updateExactAmount(userId: String, amountText: String) {
        _uiState.update { state ->
            val updated = state.exactAmounts.toMutableMap()
            updated[userId] = amountText
            state.copy(exactAmounts = updated)
        }
        recalculateSplits()
    }

    fun updatePercentage(userId: String, percentageText: String) {
        _uiState.update { state ->
            val updated = state.percentages.toMutableMap()
            updated[userId] = percentageText
            state.copy(percentages = updated)
        }
        recalculateSplits()
    }

    fun updateShares(userId: String, shareCount: Int) {
        _uiState.update { state ->
            val updated = state.shares.toMutableMap()
            updated[userId] = shareCount.coerceAtLeast(1)
            state.copy(shares = updated)
        }
        recalculateSplits()
    }

    /**
     * Recalculates splitPreview and validation based on current state. Soft validation: updates
     * preview, shows warnings.
     */
    private fun recalculateSplits() {
        val state = _uiState.value

        // Validate participants
        val participantValidation = SplitValidator.validateParticipants(state.selectedParticipants)
        if (participantValidation is SplitValidationResult.Invalid) {
            _uiState.update {
                it.copy(validationResult = participantValidation, splitPreview = emptyMap())
            }
            return
        }

        // Parse amount
        val amount =
                try {
                    if (state.amountText.isBlank()) {
                        _uiState.update {
                            it.copy(
                                    validationResult = SplitValidationResult.Valid,
                                    splitPreview = emptyMap()
                            )
                        }
                        return
                    }
                    BigDecimal(state.amountText).setScale(2, RoundingMode.HALF_UP)
                } catch (e: NumberFormatException) {
                    _uiState.update {
                        it.copy(
                                validationResult =
                                        SplitValidationResult.Invalid("Invalid amount format"),
                                splitPreview = emptyMap()
                        )
                    }
                    return
                }

        // Validate amount
        val amountValidation = SplitValidator.validateAmount(amount)
        if (amountValidation is SplitValidationResult.Invalid) {
            _uiState.update {
                it.copy(validationResult = amountValidation, splitPreview = emptyMap())
            }
            return
        }

        // Calculate splits based on type
        val (preview, validation) =
                when (state.splitType) {
                    SplitType.EQUAL -> {
                        val splits =
                                SplitValidator.calculateEqualSplit(
                                        amount,
                                        state.selectedParticipants
                                )
                        splits to SplitValidationResult.Valid
                    }
                    SplitType.EXACT -> {
                        val amounts =
                                parseExactAmounts(state.exactAmounts, state.selectedParticipants)
                        val splits = SplitValidator.calculateExactSplit(amounts)
                        splits to SplitValidator.validateExactSum(amounts, amount)
                    }
                    SplitType.PERCENTAGE -> {
                        val pcts = parsePercentages(state.percentages, state.selectedParticipants)
                        val splits = SplitValidator.calculatePercentageSplit(amount, pcts)
                        splits to SplitValidator.validatePercentageSum(pcts)
                    }
                    SplitType.SHARES -> {
                        val shareMap = state.shares.filterKeys { it in state.selectedParticipants }
                        val splits = SplitValidator.calculateSharesSplit(amount, shareMap)
                        splits to SplitValidator.validateSharesSum(shareMap)
                    }
                }

        _uiState.update { it.copy(splitPreview = preview, validationResult = validation) }
    }

    private fun parseExactAmounts(
            exactAmounts: Map<String, String>,
            participants: List<String>
    ): Map<String, BigDecimal> {
        return participants.associateWith { userId ->
            try {
                BigDecimal(exactAmounts[userId] ?: "0").setScale(2, RoundingMode.HALF_UP)
            } catch (e: NumberFormatException) {
                BigDecimal.ZERO
            }
        }
    }

    private fun parsePercentages(
            percentages: Map<String, String>,
            participants: List<String>
    ): Map<String, BigDecimal> {
        return participants.associateWith { userId ->
            try {
                BigDecimal(percentages[userId] ?: "0").setScale(2, RoundingMode.HALF_UP)
            } catch (e: NumberFormatException) {
                BigDecimal.ZERO
            }
        }
    }

    /** Hard validation and save. Blocks on any invalid state. */
    fun saveExpense() {
        val state = _uiState.value

        // Hard validation
        if (state.validationResult is SplitValidationResult.Invalid) {
            _uiState.update { it.copy(errorMessage = "Please fix validation errors before saving") }
            return
        }

        if (state.title.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Title is required") }
            return
        }

        if (state.selectedParticipants.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one participant") }
            return
        }

        val amount =
                try {
                    BigDecimal(state.amountText).setScale(2, RoundingMode.HALF_UP)
                } catch (e: NumberFormatException) {
                    _uiState.update { it.copy(errorMessage = "Invalid amount") }
                    return
                }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            _uiState.update { it.copy(errorMessage = "Amount must be greater than 0") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Use existing ID if editing, else generate new
                val finalExpenseId = expenseId ?: UUID.randomUUID().toString()

                // Idiomatic: firstOrNull() with explicit error handling
                val currentUserId = userContext.userId.firstOrNull()
                if (currentUserId == null) {
                    _uiState.update {
                        it.copy(errorMessage = "Unable to identify user", isLoading = false)
                    }
                    return@launch
                }

                val creatorId = state.createdByUserId ?: currentUserId

                val expense =
                        Expense(
                                id = finalExpenseId,
                                groupId = groupId,
                                title = state.title,
                                amount = amount,
                                currency = "INR",
                                date = Date(System.currentTimeMillis()),
                                payerId = state.payerId.ifBlank { currentUserId },
                                createdBy = creatorId,
                                createdByUserId = creatorId,
                                lastModifiedByUserId = currentUserId,
                                syncStatus = "PENDING",
                                expenseDate = state.expenseDate
                        )

                val splits =
                        state.splitPreview.map { (userId, splitAmount) ->
                            ExpenseSplit(
                                    expenseId = finalExpenseId,
                                    userId = userId,
                                    amount = splitAmount
                            )
                        }

                if (expenseId != null) {
                    expenseRepository.updateExpense(expense, splits)
                } else {
                    expenseRepository.addExpense(expense, splits)
                }

                _uiState.update { it.copy(isSaved = true, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = e.message ?: "Failed to save expense", isLoading = false)
                }
            }
        }
    }

    fun deleteExpense() {
        val id = expenseId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                expenseRepository.deleteExpense(id)
                _uiState.update {
                    it.copy(isSaved = true, isLoading = false)
                } // isSaved triggers nav back
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message, isLoading = false) }
            }
        }
    }
}
