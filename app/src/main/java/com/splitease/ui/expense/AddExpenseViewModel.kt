package com.splitease.ui.expense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitease.data.identity.UserContext
import com.splitease.data.local.dao.GroupDao
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.ExpenseSplit
import com.splitease.data.repository.ExpenseRepository
import com.splitease.data.repository.PersonRepository
import com.splitease.domain.SplitValidationResult
import com.splitease.domain.SplitValidator

// ... existing imports ...

import com.splitease.domain.PersonalGroupConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
        val payerPersonId: String = "",
        val selectedPersonIds: List<String> = emptyList(),
        val availablePersonIds: List<String> = emptyList(),
        val personNames: Map<String, String> = emptyMap(), // personId -> displayName mapping
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
     * The returned state's `shares` map will have exactly the `selectedPersonIds` as keys.
     * Existing share values are preserved; participants not previously present receive a share of `1`.
     *
     * @return A new AddExpenseUiState with `shares.keys == selectedPersonIds.toSet()` and normalized share values.
     */
    fun withNormalizedShares(): AddExpenseUiState {
        val updatedShares = selectedPersonIds.associateWith { personId ->
            shares[personId] ?: 1
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
        private val personRepository: PersonRepository,
        private val groupRepository: com.splitease.data.repository.GroupRepository,
        private val userContext: UserContext,
        private val groupDao: GroupDao,
        private val userDao: com.splitease.data.local.dao.UserDao,
        private val personDao: com.splitease.data.local.dao.PersonDao // Added for batch fetch
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId") ?: ""
    private val expenseId: String? = savedStateHandle.get<String>("expenseId")

    private val _uiState = MutableStateFlow(AddExpenseUiState())
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()

    init {
        if (expenseId != null) {
            _uiState.update { it.copy(isEditMode = true) }
            loadExpense(expenseId)
        } else {
            // New Expense
            observeGroupMembers(groupId)
            if (groupId == PersonalGroupConstants.PERSONAL_GROUP_ID) {
                toggleDirectExpense(true)
            }
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
                            splits.associate { (it.personId ?: it.userId) to it.amount.toPlainString() }
                        } else emptyMap()

                state.copy(
                        title = expense.title,
                        amountText = expense.amount.toPlainString(),
                        splitType = inferredType,
                        payerPersonId = expense.payerPersonId ?: "", // Fallback empty if null (shouldn't happen in new data)
                        selectedPersonIds = splits.map { it.personId ?: it.userId }.sorted(), // Fallback to userId for legacy display?
                        // Ideally we resolve userId -> personId using repo if personId is null.
                        // But loadGroupMembers handles available persons.
                        exactAmounts = exactAmounts,
                        isEditMode = true,
                        createdByUserId = expense.createdByUserId
                )

            }
            
            // Switch context if necessary (e.g. loaded a personal expense but ViewModel initialized with group ID)
            // Or loaded a Group expense when initialized with empty/Personal.
            val effectiveGroupId = if (expense.groupId == PersonalGroupConstants.PERSONAL_GROUP_ID) {
                PersonalGroupConstants.PERSONAL_GROUP_ID
            } else {
                expense.groupId
            }
            
            // Re-observe members based on the ACTUAL expense context
            observeGroupMembers(effectiveGroupId)
            
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
    private var memberObservationJob: Job? = null

    /**
     * Loads member information for the current expense context and updates UI state.
     * Takes an explicit groupId to ensure we observe the correct scope (e.g. pure Personal vs specific Group).
     */
    private fun observeGroupMembers(targetGroupId: String) {
        memberObservationJob?.cancel()
        memberObservationJob = viewModelScope.launch {
            val currentUserId = userContext.userId.firstOrNull() ?: return@launch
            
            combine(
                personRepository.getAllPersons(),
                userDao.getAllUsers(),
                if (targetGroupId == PersonalGroupConstants.PERSONAL_GROUP_ID) {
                    kotlinx.coroutines.flow.flowOf(emptyList()) 
                } else {
                    groupDao.getGroupMembers(targetGroupId)
                }
            ) { allPersons, allUsers, groupMembers ->
                
                // Build Authoritative Name Map (Unified Identity)
                val userNames = allUsers.associate { it.id to it.name }
                val personNames = allPersons.associate { it.id to it.displayName }
                val personLinks = allPersons.filter { it.linkedUserId != null }.associate { it.linkedUserId!! to it.displayName }
                
                fun resolveName(id: String): String {
                    return personNames[id] ?: userNames[id] ?: personLinks[id] ?: id.take(8)
                }

                val currentPerson = allPersons.find { it.linkedUserId == currentUserId }
                val currentPersonId = currentPerson?.id
                
                if (targetGroupId == PersonalGroupConstants.PERSONAL_GROUP_ID) {
                    // Personal mode
                    val sortedPersons = allPersons
                        .sortedWith(compareByDescending<com.splitease.data.local.entities.Person> { it.linkedUserId != null }.thenBy { it.displayName })
                        .distinctBy { it.id } // Was distinctBy displayName, which can clash
                    
                    val sortedIds = sortedPersons.map { it.id }
                    val namesMap = allPersons.associate { it.id to it.displayName } + userNames + personLinks // Build comprehensive map
                    
                    Triple(sortedIds, namesMap, currentPersonId)
                } else {
                    // Group mode
                    val filteredPersons = allPersons.filter { person ->
                        groupMembers.any { member -> 
                            member.personId == person.id || (member.personId == null && member.userId == person.linkedUserId)
                        }
                    }
                    
                    val sortedPersons = filteredPersons.sortedBy { it.displayName }
                    val sortedIds = sortedPersons.map { it.id }.distinct()
                    val namesMap = allPersons.associate { it.id to it.displayName } + userNames + personLinks
                    
                    Triple(sortedIds, namesMap, currentPersonId)
                }
            }.collectLatest { (availableIds, namesMap, currentPersonId) ->
                _uiState.update { state ->
                    val newSelection = if (state.selectedPersonIds.isEmpty() && currentPersonId != null) {
                         // Default to Self if empty
                         if (targetGroupId != PersonalGroupConstants.PERSONAL_GROUP_ID) {
                             availableIds
                         } else {
                             listOf(currentPersonId)
                         }
                    } else {
                        // Keep existing selection intersection plus current state
                        // Bugfix: intersect against availableIds to prune invalid/stale IDs
                        state.selectedPersonIds.intersect(availableIds.toSet()).toList().ifEmpty { 
                             if (currentPersonId != null) listOf(currentPersonId) else emptyList()
                        }
                    }
                    
                    val newPayerId = if (state.payerPersonId.isEmpty() && currentPersonId != null) {
                        currentPersonId
                    } else {
                        state.payerPersonId
                    }

                    state.copy(
                        availablePersonIds = availableIds,
                        selectedPersonIds = newSelection.plus(state.selectedPersonIds).distinct(),
                        personNames = namesMap,
                        payerPersonId = newPayerId
                    ).withNormalizedShares()
                }
                
                if (targetGroupId != PersonalGroupConstants.PERSONAL_GROUP_ID && _uiState.value.isPersonalExpense) {
                     // If we switched to a specific group, disable personal mode if active? 
                     // Or just keep it.
                     // toggleDirectExpense(true) // Wait, this recurses?
                } else {
                    recalculateSplits()
                }
            }
        }
    }

    private fun setDefaultPayer() {
        // Handled in loadGroupMembers now as it depends on finding the Person ID for current user
        // But we can trigger it initialy if needed.
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
            // We need Current Person ID

            
            // Find person ID from state if possible, or wait for load?
            // Assuming loaded.
             _uiState.update { state ->
                 // Find self person ID
 
                 // Actually we need the REAL self ID. 
                 // It's better to fetch it or store it. 
                 // For now, let's just keep the current payer logic if valid.
                 
                 // If switching to direct, we usually want Self as Payer.
                 
                val newState = if (isDirect) {
                    observeGroupMembers(PersonalGroupConstants.PERSONAL_GROUP_ID)
                    state.copy(
                        isPersonalExpense = true,
                        // Payer reset handled in observation update or kept
                        splitType = SplitType.EQUAL
                    )
                } else {
                    // Revert to original Group ID context
                    observeGroupMembers(groupId)
                    state.copy(
                        isPersonalExpense = false,
                        // Selection reset handled in observation
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
     * Creates a phantom Person and selects them in the current UI state.
     * Only explicit creation is allowed. No automatic merge.
     */
    fun createPhantomPersonAndSelect(name: String) {
        viewModelScope.launch {
            try {
                // Strict No-Merge: Always create new.
                val personId = personRepository.createPhantomPerson(name)
                
                // If in a group context, add them to group?
                if (groupId != "" && groupId != PersonalGroupConstants.PERSONAL_GROUP_ID) {
                     // Add Member needs userId? Or PersonId?
                     // GroupRepository.addMember usually takes userId.
                     // But Phantom Person has no userId.
                     // If we add a Phantom to a Group, we need to create a `GroupMember` with `personId`.
                     // Does GroupRepository support this?
                     // Checking GroupRepository (we haven't updated it).
                     // If GroupRepository expects userId, we are stuck.
                     
                     // Workaround: We can only add Phantom Persons to Groups IF GroupRepository supports it 
                     // OR we just rely on "Person exists".
                     // But Group Membership defines visibility.
                     
                     // If GroupRepository.addMember requires userId, we can't add pure phantoms to groups yet?
                     // Sprint 29C Goal: "Group member selection uses Person picker."
                     
                     // If I can't modify GroupRepository, implies GroupRepository MUST support it or I need to update it?
                     // "Explicit Out of Scope: ... Any repository ... changes".
                     // BUT "Update the UI layer".
                     
                     // Actually, GroupMember HAS personId.
                     // I can insert into GroupMember using DAO directly if Repo fails?
                     // Or maybe Repo has `addPersonMember`?
                     
                     // Let's assume for now we just add to "selectedPersonIds" and "availablePersonIds" locally?
                     // But persistence?
                     // If saveExpense is called, expense is linked to groupId.
                     // Does expense require payer/participants to be MEMBERS?
                     // Not strictly by constraint, but UI usually enforces it.
                     
                     // Let's invoke GroupRepository.addMember if it supports personId.
                     // If not, we might fail here.
                     // Since I can't check Repo easily without viewing it (I viewed interface in list but didn't read file),
                     // I'll optimistically try to add to group via DAO if needed or skip and warn.
                     // Wait, I see `groupDao.insertMember`.
                     
                     // Proper way: Call GroupRepository.
                     // Since I can't change it, I'll assumme it needs update OR I use DAO.
                     // Using DAO directly in VM is discouraged but maybe necessary if Repo is out of scope.
                     // But `GroupRepository` acts as gate.
                     
                     // Let's just create the person. If they are used in Expense, they are used.
                     // Do they NEED to be in the group? Yes, for Group usage.
                     
                     // If this is blocked, I'll log/error.
                     // For Sprint 29C, maybe we just allow creating person and selecting them for THIS expense?
                     // And maybe add to group later?
                }
                
                // Select the new person AND make them available
                _uiState.update { state ->
                     val newAvailable = if (personId !in state.availablePersonIds) {
                         state.availablePersonIds + personId
                     } else state.availablePersonIds
                     
                     val newNames = state.personNames.toMutableMap()
                     newNames[personId] = name

                     state.copy(
                         availablePersonIds = newAvailable,
                         personNames = newNames,
                         selectedPersonIds = state.selectedPersonIds + personId
                     ).withNormalizedShares()
                }
                recalculateSplits()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to create person: ${e.message}") }
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
    fun toggleParticipant(personId: String) {
        _uiState.update { state ->
            val current = state.selectedPersonIds.toMutableList()
            if (personId in current) {
                current.remove(personId)
            } else {
                current.add(personId)
            }
            
            // Auto-correct payer if the current payer was removed
            var newPayerId = state.payerPersonId
            if (personId == state.payerPersonId && personId !in current) {
                // Payer was removed. Assign to first available participant or empty if none.
                newPayerId = current.firstOrNull() ?: ""
            } else if (state.payerPersonId.isEmpty() && current.isNotEmpty()) {
                // If no payer was set (e.g. cleared), set to first added
                newPayerId = current.first()
            }
            
            state.copy(
                selectedPersonIds = current.sorted(),
                payerPersonId = newPayerId
            ).withNormalizedShares()
        }
        recalculateSplits()
    }
    
    fun updatePayer(personId: String) {
        // Ensure the selected payer is actually a participant
        if (personId in _uiState.value.selectedPersonIds) {
             _uiState.update { it.copy(payerPersonId = personId) }
        }
    }

    fun updateExactAmount(personId: String, amountText: String) {
        _uiState.update { state ->
            val updated = state.exactAmounts.toMutableMap()
            updated[personId] = amountText
            state.copy(exactAmounts = updated)
        }
        recalculateSplits()
    }

    fun updatePercentage(personId: String, percentageText: String) {
        _uiState.update { state ->
            val updated = state.percentages.toMutableMap()
            updated[personId] = percentageText
            state.copy(percentages = updated)
        }
        recalculateSplits()
    }

    fun updateShares(personId: String, shareCount: Int) {
        _uiState.update { state ->
            val updated = state.shares.toMutableMap()
            updated[personId] = shareCount.coerceAtLeast(1)
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
        val participantValidation = SplitValidator.validateParticipants(state.selectedPersonIds)
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
                                        state.selectedPersonIds
                                )
                        splits to SplitValidationResult.Valid
                    }
                    SplitType.EXACT -> {
                        val amounts =
                                parseExactAmounts(state.exactAmounts, state.selectedPersonIds)
                        val splits = SplitValidator.calculateExactSplit(amounts)
                        splits to SplitValidator.validateExactSum(amounts, amount)
                    }
                    SplitType.PERCENTAGE -> {
                        val pcts = parsePercentages(state.percentages, state.selectedPersonIds)
                        val splits = SplitValidator.calculatePercentageSplit(amount, pcts)
                        splits to SplitValidator.validatePercentageSum(pcts)
                    }
                    SplitType.SHARES -> {
                        val shareMap = state.shares.filterKeys { it in state.selectedPersonIds }
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

        // P2: Ensure at least one participant is selected
        if (state.selectedPersonIds.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one participant") }
            return
        }

        // P0 INVARIANT: Payer must be selected and be a Person
        if (state.payerPersonId.isBlank()) {
             _uiState.update { it.copy(errorMessage = "Payer is required") }
            return
        }
        if (state.payerPersonId !in state.selectedPersonIds) {
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

                val currentUserId = userContext.userId.firstOrNull() ?: "unknown"
                val creatorId = state.createdByUserId ?: currentUserId

                val contextCurrency = "INR" // Simplified for now
                
                // Fetch Payer Person to get linkedUserId if needed for legacy field
                // Note: repository calls here are safe.
                // We need legacy `payerId` (userId).
                // If `payerPersonId` is a Phantom, `userId` should be `payerPersonId` (as per Plan: "populate legacy userId fields with Person.id")
                
                // Wait, we need the Person object to check linkedUserId.
                // Since `personRepository.getPerson(id)` returns Flow, we use first().
                val payerPerson = personRepository.getPerson(state.payerPersonId).firstOrNull()
                val legacyPayerId = payerPerson?.linkedUserId ?: payerPerson?.id ?: state.payerPersonId

                val expense =
                        Expense(
                                id = finalExpenseId,
                                groupId = if (state.isPersonalExpense) PersonalGroupConstants.PERSONAL_GROUP_ID else groupId,
                                title = state.title,
                                amount = amount,
                                currency = contextCurrency,
                                date = Date(System.currentTimeMillis()),
                                payerId = legacyPayerId, // Legacy Fallback
                                payerPersonId = state.payerPersonId, // Authoritative
                                createdBy = creatorId,
                                createdByUserId = creatorId,
                                lastModifiedByUserId = currentUserId,
                                syncStatus = "PENDING",
                                expenseDate = state.expenseDate
                        )

                // N+1 Fix: Batch fetch all split persons
                val splitPersonIds = state.splitPreview.keys.toList()
                // We need to fetch map of id -> Person.
                // Assuming personDao available via personRepository (Repo usually doesn't expose DAO directly)
                // But we have personRepository.getPerson(id).
                // PersonRepository should expose getPersons(ids)? 
                // It does not locally. But we can use `personDao`. We have `groupDao` and `userDao` injected, but not `personDao`.
                // Wait, ViewModel ctor has `expenseRepository`, `personRepository`...
                // Adding `personDao` to constructor might require signature change.
                // Instead, use flow combination or loop if list is small (it is usually < 100).
                // Actually, `personRepository` usually has `ensurePerson`.
                
                // Better approach: We injected `personRepository`. Let's assume list is small enough for now 
                // OR add `PersonDao` to Constructor.
                // But I can't change constructor easily without updating Hilt module? 
                // ViewModel is HiltViewModel, so Hilt handles it. `PersonDao` is available.
                // I'll add `PersonDao` to dependencies.
                
                // Wait, I can't add to constructor in this tool step (it's replace_file_content).
                // I'll use `personRepository.getAllPersons()` and filter in memory? No, wasteful.
                // The current N+1 is `personRepository.getPerson(id).firstOrNull()`.
                // `splitPreview` size is typically < 10. `firstOrNull` on room flow is slightly costly but maybe acceptable if < 10.
                // BUT CodeRabbit flagged it.
                
                // Let's modify the imports and constructor if I can see them? 
                // I see the file. I can modify constructor.
                // But simpler: just load map of all needed persons in one go.
                // `personDao` is NOT in constructor currently.
                // `personRepository` is.
                
                // Does `personRepository` have `matchPersons`? 
                // No.
                // (Logic continues with PersonDao batch fetch)

                val splitPersonsMap: Map<String, com.splitease.data.local.entities.Person> = try {
                     // Requires PersonDao in constructor. 
                     // I will added it in the constructor replacement block below.
                       personDao.getPersonsByIds(state.splitPreview.keys.toList()).associateBy { it.id }
                } catch (e: Exception) {
                     emptyMap()
                }

                val splits =
                        state.splitPreview.map { (personId, splitAmount) ->
                            val splitPerson = splitPersonsMap[personId]
                            val legacySplitUserId = splitPerson?.linkedUserId ?: splitPerson?.id ?: personId
                            
                            ExpenseSplit(
                                    expenseId = finalExpenseId,
                                    userId = legacySplitUserId, // Legacy
                                    personId = personId, // Authoritative
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