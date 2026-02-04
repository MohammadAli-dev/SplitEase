package com.splitease.ui.expense

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.splitease.domain.SplitValidationResult
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.font.FontWeight

import com.splitease.ui.components.PersonPicker
import com.splitease.data.local.entities.Person

/**
 * Renders the Add/Edit Expense screen UI and connects user interactions to the AddExpenseViewModel.
 *
 * Shows inputs for title, amount, expense type, payer selection, date, split type, participants,
 * and the appropriate split input section; manages save/update, delete, add-person, and date/payer
 * pickers, and invokes navigation callbacks when appropriate.
 *
 * @param onNavigateBack Invoked when the user requests to navigate back.
 * @param onExpenseSaved Invoked after the expense has been saved successfully.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
        onNavigateBack: () -> Unit,
        onExpenseSaved: () -> Unit,
        viewModel: AddExpenseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onExpenseSaved()
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showPayerSelector by remember { mutableStateOf(false) }

    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }

    if (showDeleteDialog) {
        AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete Expense?") },
                text = { Text("This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                            onClick = {
                                showDeleteDialog = false
                                viewModel.deleteExpense()
                            },
                            colors =
                                    ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                    )
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
                }
        )
    }
    

    
    // Construct Person objects for Picker
    // We only have names, so we create lightweight objects. 
    // Ideally ViewModel should provide List<Person>.
    val availablePersons = remember(uiState.availablePersonIds, uiState.personNames) {
        uiState.availablePersonIds.map { id ->
            Person(
                id = id,
                displayName = uiState.personNames[id] ?: "Unknown",
                linkedUserId = null, // Unknown in this view
                createdAt = 0
            )
        }
    }
    
    // Payer Selector (Single Select)
    if (showPayerSelector) {
        ModalBottomSheet(
            onDismissRequest = { showPayerSelector = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    text = "Who paid?",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                // Filter participants only? Usually Payer must be a participant.
                val participantPersons = availablePersons.filter { it.id in uiState.selectedPersonIds }
                
                PersonPicker(
                    persons = participantPersons,
                    selectedPersonIds = setOf(uiState.payerPersonId),
                    onToggleSelection = { personId ->
                        viewModel.updatePayer(personId)
                        showPayerSelector = false
                    },
                    allowMultiple = false,
                    onCreatePerson = null // No creating new people from Payer selector, must be participant
                )
            }
        }
    }

    // Participant Selector (Multi Select + Create)
    // Launched via "Add/Edit" button in chips row
    var showParticipantPicker by remember { mutableStateOf(false) }

    if (showParticipantPicker) {
        ModalBottomSheet(
            onDismissRequest = { showParticipantPicker = false },
            sheetState = rememberModalBottomSheetState()
        ) {
             Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    text = "Select Participants",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                PersonPicker(
                    persons = availablePersons,
                    selectedPersonIds = uiState.selectedPersonIds.toSet(),
                    onToggleSelection = { personId ->
                        viewModel.toggleParticipant(personId)
                    },
                    allowMultiple = true,
                    onCreatePerson = { name, email, phone ->
                        viewModel.createPhantomPersonAndSelect(name, email, phone)
                        // Keep picker open so they can see it added/selected
                    }
                )
            }
        }
    }



    Scaffold(
            topBar = {
                TopAppBar(
                        title = {
                            Text(text = if (uiState.isEditMode) "Edit Expense" else "Add Expense")
                        },
                        navigationIcon = {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                )
                            }
                        },
                        actions = {
                            if (uiState.isEditMode) {
                                IconButton(onClick = { showDeleteDialog = true }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                )
            }
    ) { innerPadding ->
        Column(
                modifier =
                        Modifier.fillMaxSize()
                                .padding(innerPadding)
                                .padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title
            OutlinedTextField(
                    value = uiState.title,
                    onValueChange = { viewModel.updateTitle(it) },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true
            )

            // Amount
            OutlinedTextField(
                    value = uiState.amountText,
                    onValueChange = { viewModel.updateAmount(it) },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { 
                        // Optional: Hide keyboard or move focus 
                    }),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
            )

            // Expense Type Toggle
            Text("Expense Type", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !uiState.isPersonalExpense,
                    onClick = { viewModel.toggleDirectExpense(false) },
                    label = { Text("Group Expense") },
                    leadingIcon = { 
                         if (!uiState.isPersonalExpense) Icon(Icons.Filled.Home, null) 
                    }
                )
                FilterChip(
                    selected = uiState.isPersonalExpense,
                    onClick = { viewModel.toggleDirectExpense(true) },
                    label = { Text("Non-Group Expense") },
                    leadingIcon = {
                        if (uiState.isPersonalExpense) Icon(Icons.Filled.Person, null) 
                    }
                )
            }
            if (uiState.isPersonalExpense) {
                Text(
                    "Non-Group expenses are shared costs with people, without creating a group.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            
            // Paid By Section
            if (uiState.selectedPersonIds.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPayerSelector = true },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Paid by",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.personNames[uiState.payerPersonId] ?: "Select Payer",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select")
                    }
                }
            }

            // Split Type Selector
            // Expense Date Picker
            Text("Expense Date", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                    value = dateFormatter.format(Date(uiState.expenseDate)),
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Select Date")
                        }
                    }
            )

            if (showDatePicker) {
                val datePickerState =
                        rememberDatePickerState(initialSelectedDateMillis = uiState.expenseDate)
                DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(
                                    onClick = {
                                        datePickerState.selectedDateMillis?.let {
                                            viewModel.updateExpenseDate(it)
                                        }
                                        showDatePicker = false
                                    }
                            ) { Text("OK") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
                        }
                ) { DatePicker(state = datePickerState) }
            }

            // Split Type Selector (for group expenses) or always for direct expense splits
            Text("Split Type", style = MaterialTheme.typography.labelMedium)
            Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SplitType.values().forEach { type ->
                    FilterChip(
                            selected = uiState.splitType == type,
                            onClick = { viewModel.updateSplitType(type) },
                            label = { Text(type.name) }
                    )
                }
            }

            // Participant Selection
            Text("Participants", style = MaterialTheme.typography.labelMedium)
            
            Column {
                // Info text for personal
                if (uiState.isPersonalExpense) {
                    Text(
                        "Choose people to split this expense with",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    // Manage/Add Button (First)
                    FilterChip(
                        selected = false,
                        onClick = { showParticipantPicker = true },
                        label = { Text("Edit Participants") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
                    )
                    
                    // Selected Chips
                    uiState.selectedPersonIds.forEach { personId ->
                        FilterChip(
                            selected = true,
                            onClick = { viewModel.toggleParticipant(personId) },
                            label = { Text(uiState.personNames[personId] ?: "Person") },
                            trailingIcon = { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }

            // Split Input Section - show for any expense with participants selected
            if (uiState.selectedPersonIds.isNotEmpty()) {
                when (uiState.splitType) {
                    SplitType.EQUAL -> {
                        SplitPreviewSection(
                                splitPreview = uiState.splitPreview,
                                personNames = uiState.personNames
                        )
                    }
                    SplitType.EXACT -> {
                        ExactAmountInputSection(
                                participants = uiState.selectedPersonIds,
                                amounts = uiState.exactAmounts,
                                personNames = uiState.personNames,
                                onAmountChange = { personId, amount ->
                                    viewModel.updateExactAmount(personId, amount)
                                }
                        )
                    }
                    SplitType.PERCENTAGE -> {
                        PercentageInputSection(
                                participants = uiState.selectedPersonIds,
                                percentages = uiState.percentages,
                                personNames = uiState.personNames,
                                onPercentageChange = { personId, pct ->
                                    viewModel.updatePercentage(personId, pct)
                                }
                        )
                    }
                    SplitType.SHARES -> {
                        SharesInputSection(
                                participants = uiState.selectedPersonIds,
                                shares = uiState.shares,
                                personNames = uiState.personNames,
                                onSharesChange = { personId, count ->
                                    viewModel.updateShares(personId, count)
                                }
                        )
                    }
                }
            }

            // Split Preview (for non-EQUAL types)
            if (uiState.splitType != SplitType.EQUAL && uiState.splitPreview.isNotEmpty()) {
                Text("Preview", style = MaterialTheme.typography.labelMedium)
                SplitPreviewSection(uiState.splitPreview, personNames = uiState.personNames)
            }

            // Validation feedback
            if (uiState.validationResult is SplitValidationResult.Invalid) {
                Text(
                        text = (uiState.validationResult as SplitValidationResult.Invalid).reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                )
            }

            // Error message
            uiState.errorMessage?.let { error ->
                Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                    onClick = { viewModel.saveExpense() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = !uiState.isLoading &&
                            uiState.validationResult is SplitValidationResult.Valid &&
                            uiState.title.isNotBlank() &&
                            uiState.amountText.isNotBlank()
            ) { 
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (uiState.isEditMode) "Updating..." else "Saving...")
                } else {
                    Text(text = if (uiState.isEditMode) "Update Expense" else "Save Expense") 
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SplitPreviewSection(
        splitPreview: Map<String, BigDecimal>,
        personNames: Map<String, String>
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        splitPreview.entries.forEach { (personId, amount) ->
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                        personNames[personId] ?: personId,
                        style = MaterialTheme.typography.bodyMedium
                )
                Text("₹$amount", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ExactAmountInputSection(
        participants: List<String>,
        amounts: Map<String, String>,
        personNames: Map<String, String>,
        onAmountChange: (String, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        participants.forEach { personId ->
            OutlinedTextField(
                    value = amounts[personId] ?: "",
                    onValueChange = { onAmountChange(personId, it) },
                    label = { Text(personNames[personId] ?: personId) },
                    suffix = { Text("₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PercentageInputSection(
        participants: List<String>,
        percentages: Map<String, String>,
        personNames: Map<String, String>,
        onPercentageChange: (String, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        participants.forEach { personId ->
            OutlinedTextField(
                    value = percentages[personId] ?: "",
                    onValueChange = { onPercentageChange(personId, it) },
                    label = { Text(personNames[personId] ?: personId) },
                    suffix = { Text("%") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SharesInputSection(
        participants: List<String>,
        shares: Map<String, Int>,
        personNames: Map<String, String>,
        onSharesChange: (String, Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        participants.forEach { personId ->
            val currentShares = shares[personId] ?: 1
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                        personNames[personId] ?: personId,
                        style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                            onClick = {
                                onSharesChange(personId, (currentShares - 1).coerceAtLeast(1))
                            }
                    ) { Text("-", style = MaterialTheme.typography.titleLarge) }
                    Text(
                            text = currentShares.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(32.dp)
                    )
                    IconButton(onClick = { onSharesChange(personId, currentShares + 1) }) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}