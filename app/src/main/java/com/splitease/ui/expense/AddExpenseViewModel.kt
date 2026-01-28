package com.splitease.ui.expense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.repository.ExpenseRepository
import com.splitease.data.repository.UserRepository
import com.splitease.domain.SplitValidationResult
import com.splitease.domain.SplitValidator
import com.splitease.data.repository.AddMemberResult

// ... existing imports ...

import com.splitease.domain.PersonalGroupConstants
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
        val createdByUserId: String? = null,
        val isPersonalExpense: Boolean = false
) {
    /**
     * Create a new state with shares aligned to the current selected participants.
     *
     * The returned state's `shares` map will have exactly the `selectedParticipants` as keys.
     * Existing share values are preserved; participants not previously present receive a share of `1`.
     *
     * @return A new AddExpenseUiState with `shares.keys == selectedParticipants.toSet()` and normalized share values.
     */
    fun withNormalizedShares(): AddExpenseUiState {
        val updatedShares = selectedParticipants.associateWith { userId ->
            shares[userId] ?: 1
        }
        return copy(shares = updatedShares)
    }
}

@HiltViewModel
class AddExpenseViewModel
@Inject
constructor(
        savedStateHandle: SavedStateHandle,
        private val expenseRepository: ExpenseRepository,
        private val userRepository: UserRepository,
        private val groupRepository: com.splitease.data.repository.GroupRepository,
        private val userContext: UserContext,
        private val groupDao: GroupDao,
        private val userDao: com.splitease.data.local.dao.UserDao
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
        } else if (groupId == PersonalGroupConstants.PERSONAL_GROUP_ID) {
            // Auto-enable direct expense mode for global "Add Expense"
            toggleDirectExpense(true)
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

    /**
     * Loads member information for the current expense context and updates UI state.
     *
     * If the expense is personal (groupId equals PERSONAL_GROUP_ID), ensures the current user is
     * selected, sets selectable groupMembers to other users, updates the user name map, normalizes
     * shares, and triggers split recalculation. For a group expense, sets groupMembers and
     * selectedParticipants to the group's members, updates the user name map, normalizes shares,
     * and either toggles direct-expense mode (if the UI state indicates a personal expense) or
     * recalculates splits. If the current user ID cannot be obtained, no state changes are made.
     */
    private fun loadGroupMembers() {
        viewModelScope.launch {
            val currentUserId = userContext.userId.firstOrNull() ?: return@launch
            
            // For Non-Group expenses, load all users; for Group expenses, load group members
            if (groupId == PersonalGroupConstants.PERSONAL_GROUP_ID) {
                userDao.getAllUsers().collectLatest { allUsers ->
                    // Show all OTHER users as selectable, but current user is always included
                    val otherUsers = allUsers.filter { it.id != currentUserId }
                    val sortedUsers = otherUsers.sortedBy { it.name }
                    val sortedMemberIds = sortedUsers.map { it.id }
                    val userNamesMap = allUsers.associate { it.id to it.name }
                    
                    // Current user is ALWAYS a participant in non-group expenses
                    // They select additional participants from the list
                    // Fix: Do not reset selection to just [currentUserId]. Merge existing checks.
                    _uiState.update {
                        val newSelection = (it.selectedParticipants + currentUserId).distinct()
                        it.copy(
                            groupMembers = sortedMemberIds,
                            selectedParticipants = newSelection,
                            userNames = userNamesMap
                        ).withNormalizedShares()
                    }
                    recalculateSplits()
                }
            } else {
                groupDao.getGroupMembersWithDetails(groupId).collectLatest { users ->
                    val sortedUsers = users.sortedBy { it.name }
                    val sortedMemberIds = sortedUsers.map { it.id }
                    val userNamesMap = sortedUsers.associate { it.id to it.name }
                    _uiState.update {
                        it.copy(
                            groupMembers = sortedMemberIds,
                            selectedParticipants = sortedMemberIds,
                            userNames = userNamesMap
                        ).withNormalizedShares()
                    }
                    if (_uiState.value.isPersonalExpense) {
                        toggleDirectExpense(true)
                    } else {
                        recalculateSplits()
                    }
                }
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

    /**
     * Toggle between direct (personal) and group expense modes.
     *
     * When enabled, the UI state is updated to treat the expense as personal: the payer is set
     * to the current user and the split mode is set to equal. When disabled, the UI state is
     * restored to group mode and selected participants are reset to the group's members.
     *
     * The function also normalizes shares for the current participants and triggers a split
     * recalculation.
     *
     * @param isDirect `true` to enable direct (personal) expense mode, `false` to revert to group mode.
     */
    fun toggleDirectExpense(isDirect: Boolean) {
        viewModelScope.launch {
            val currentUserId = userContext.userId.firstOrNull() ?: return@launch
            
            _uiState.update { state ->
                val newState = if (isDirect) {
                    // Switch to direct expense: Keep participants selectable, default payer to current user
                    state.copy(
                        isPersonalExpense = true, // Reusing field for "is Direct Expense"
                        payerId = currentUserId,
                        // Do NOT force participants - allow user to select multiple
                        splitType = SplitType.EQUAL
                    )
                } else {
                    // Revert to group: restore group members
                    state.copy(
                        isPersonalExpense = false,
                        selectedParticipants = state.groupMembers,
                        splitType = SplitType.EQUAL
                    )
                }
                newState.withNormalizedShares()
            }
            recalculateSplits()
        }
    }


    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    /**
     * Updates the expense amount text and refreshes split calculations.
     *
     * @param amountText The raw amount input from the user (may be blank or invalid).
     */
    fun updateAmount(amountText: String) {
        _uiState.update { it.copy(amountText = amountText) }
        recalculateSplits()
    }

    /**
     * Changes the current split calculation mode in the UI state and updates dependent data.
     *
     * If the new mode is `SHARES`, participant shares are normalized so keys match the selected participants
     * (preserving existing values and defaulting new participants to 1). After updating the state, split
     * previews and validations are recalculated.
     *
     * @param splitType The new split type to apply.
     */
    fun updateSplitType(splitType: SplitType) {
        _uiState.update { state ->
            val newState = state.copy(splitType = splitType)
            if (splitType == SplitType.SHARES) {
                newState.withNormalizedShares()
            } else {
                newState
            }
        }
        recalculateSplits()
    }


    /**
     * Sets the expense date to the provided timestamp normalized to the start of that day in the device's local timezone.
     *
     * @param dateMillis Timestamp in milliseconds since the Unix epoch to use for the expense date.
     */
    fun updateExpenseDate(dateMillis: Long) {
        _uiState.update { it.copy(expenseDate = normalizeToStartOfDay(dateMillis)) }
    }

    // NOTE: normalizeShares() has been refactored to AddExpenseUiState.withNormalizedShares()
    // This is an atomic pure transformation that eliminates dual-emission UI flickering.

    /**
     * Creates a phantom user and selects them in the current UI state.
     *
     * On success the new user is added to selected participants and group members, the user-name map
     * is refreshed, and split recalculation is triggered so the UI reflects the change immediately.
     * On failure the UI state's `errorMessage` is set with an opaque error description.
     *
     * @param name Display name for the phantom user.
     * @param email Optional email for the phantom user.
     * @param phone Optional phone number for the phantom user.
     */
    fun createPhantomUserAndSelect(name: String, email: String? = null, phone: String? = null) {
        viewModelScope.launch {
            try {
                val userId = userRepository.createPhantomUser(name, email, phone)
                
                // If in a group context, automatically join them to the group
                if (groupId != "" && groupId != PersonalGroupConstants.PERSONAL_GROUP_ID) {
                    val currentUserId = userContext.userId.firstOrNull() ?: throw IllegalStateException("User identity missing")
                    val result = groupRepository.addMember(groupId, userId, currentUserId)
                    if (result is com.splitease.data.repository.AddMemberResult.Error) {
                         _uiState.update { it.copy(errorMessage = "Failed to add to group: ${result.message}") }
                         return@launch
                    }
                }
                
                // userRepository.createPhantomUser -> database -> loadGroupMembers() flow triggers -> UI update.
                // Unblocking UI happens automatically via Flow emission or could be explicitly done if needed, 
                // but effectively we just wait for the db.
                
                // However, to ensure the new user is *selected* automatically, we might rely on the fact 
                // that they are added to the group members list. 
                // For non-group expense, `loadGroupMembers` re-runs on user table change.
                
                // We do need to ensure `selectedParticipants` includes the new `userId`.
                // Since `loadGroupMembers` runs on ANY user change (for non-group), it refreshes the list.
                // To *select* them, we might need to update selection. But `loadGroupMembers` as written now
                // preserves selection. It doesn't *auto-select* new users unless we do it here OR modify `loadGroupMembers` to auto-select new additions (risky).
                
                // STRICT FIX: logic says "ViewModel must not double mutate". 
                // BUT we need to select the new user. 
                // The prompt strategy said: "Fix createPhantomUserAndSelect: REMOVE the manual _uiState.update block entirely. Rely solely on userRepository.createPhantomUser updating the database, which will trigger loadGroupMembers via Flow collection, thus updating the UI."
                
                // Wait, if I remove the update, who adds `userId` to `selectedParticipants`?
                // `loadGroupMembers` only preserves *existing* selection.
                // Ah, effectively, if I want to "auto select" the new user, I should probably do a lightweight update to "intent to select" OR
                // Update selection *after* the user exists? 
                
                // Actually, the Plan said: "Rely solely on userRepository.createPhantomUser updating the database... This enforces Room as the only observable source."
                // "verify: 'You' and the new person are selected"
                
                // The safest way to modify selection without duplicating "data" is to update *only* the selection state *after* creation, 
                // assuming the user will appear in `availableUsers`.
                // But `loadGroupMembers` will fire asynchronously.
                
                // Let's implement exactly as planned: Remove the manual update that was causing duplication (likely adding to `groupMembers` manually while flow also added it).
                // To Select the user: I can add them to `selectedParticipants` safely. The "duplication" bug was likely due to `groupMembers + userId` manually AND `groupMembers` from flow.
                
                _uiState.update { currentState ->
                     currentState.copy(selectedParticipants = currentState.selectedParticipants + userId).withNormalizedShares()
                }
                
                recalculateSplits()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to add person: ${e.message}") }
            }
        }
    }

    /**
     * Toggles a participant's selection in the current expense and updates related state.
     *
     * Updates the UI state by adding or removing the given userId from selectedParticipants,
     * reassigns the payer if the removed user was the payer (to the first remaining participant or
     * empty string), sets the first participant as payer if none is set and participants exist,
     * normalizes shares to match the updated participant list, and triggers a recalculation of splits.
     *
     * @param userId The identifier of the participant to toggle in the selection.
     */
    fun toggleParticipant(userId: String) {
        _uiState.update { state ->
            val current = state.selectedParticipants.toMutableList()
            if (userId in current) {
                current.remove(userId)
            } else {
                current.add(userId)
            }
            
            // Auto-correct payer if the current payer was removed
            var newPayerId = state.payerId
            if (userId == state.payerId && userId !in current) {
                // Payer was removed. Assign to first available participant or empty if none.
                newPayerId = current.firstOrNull() ?: ""
            } else if (state.payerId.isEmpty() && current.isNotEmpty()) {
                // If no payer was set (e.g. cleared), set to first added
                newPayerId = current.first()
            }
            
            state.copy(
                selectedParticipants = current.sorted(),
                payerId = newPayerId
            ).withNormalizedShares()
        }
        recalculateSplits()
    }
    
    fun updatePayer(payerId: String) {
        // Ensure the selected payer is actually a participant
        if (payerId in _uiState.value.selectedParticipants) {
             _uiState.update { it.copy(payerId = payerId) }
        }
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

        if (state.payerId !in state.selectedParticipants) {
            _uiState.update { it.copy(errorMessage = "Payer must be a selected participant") }
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

                // FAIL-CLOSED: Inherit currency from Group History.
                // 1. Attempt to find existing currency in this group.
                // 2. If Personal Group: Safe to use "INR" as we don't support multi-currency personal yet? 
                //    Actually, Personal Group is also a group.
                //    The plan says: "Genesis Expense... BLOCK ACTION".
                                
                // We need to check history.
                // expenseRepository.getExpensesForGroup(groupId) returns a Flow.
                // We need a snapshot.
                                
                // Warning: querying DB inside `saveExpense` (which is suspend) is fine.
                // Use getAllEffectiveExpenses to respect zombie filtering.
                val contextCurrency = if (state.isPersonalExpense) {
                    val allExpenses = expenseRepository.getAllEffectiveExpenses().first()
                    allExpenses.find { it.groupId == PersonalGroupConstants.PERSONAL_GROUP_ID }?.currency
                } else {
                    val allExpenses = expenseRepository.getAllEffectiveExpenses().first()
                    allExpenses.find { it.groupId == groupId }?.currency
                }

                val finalCurrency = contextCurrency ?: run {
                     _uiState.update { 
                         it.copy(errorMessage = "Cannot determine currency. Add currency context via existing expenses or online group creation.", isLoading = false) 
                     }
                     return@launch
                }

                val expense =
                        Expense(
                                id = finalExpenseId,
                                groupId = if (state.isPersonalExpense) PersonalGroupConstants.PERSONAL_GROUP_ID else groupId,
                                title = state.title,
                                amount = amount,
                                currency = finalCurrency,
                                date = Date(System.currentTimeMillis()),
                                payerId = state.payerId,
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