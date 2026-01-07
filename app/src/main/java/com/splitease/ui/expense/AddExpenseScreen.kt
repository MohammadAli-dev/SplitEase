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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Divider
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
    
    if (showPayerSelector) {
        ModalBottomSheet(
            onDismissRequest = { showPayerSelector = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Who paid?",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.selectedParticipants) { userId ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updatePayer(userId)
                                    showPayerSelector = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.userNames[userId] ?: "User",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (userId == uiState.payerId) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (userId != uiState.selectedParticipants.last()) {
                            Divider()
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
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
                    modifier = Modifier.fillMaxWidth()
            )

            // Amount
            OutlinedTextField(
                    value = uiState.amountText,
                    onValueChange = { viewModel.updateAmount(it) },
                    label = { Text("Amount (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
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
            if (uiState.selectedParticipants.isNotEmpty()) {
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
                                text = uiState.userNames[uiState.payerId] ?: "Select Payer",
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
            if (uiState.isPersonalExpense) {
                // Non-Group expense: show "You" + all other users to select
                Text("Select Participants", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Choose people to split this expense with",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // "You" chip - always selected, cannot be removed
                    FilterChip(
                            selected = true,
                            onClick = { /* Cannot deselect self */ },
                            label = { Text("You") },
                            enabled = false // Visually indicate it's locked
                    )
                    
                    // Other users - selectable
                    uiState.groupMembers.forEach { userId ->
                        FilterChip(
                                selected = userId in uiState.selectedParticipants,
                                onClick = { viewModel.toggleParticipant(userId) },
                                label = { Text(uiState.userNames[userId] ?: "User ${userId.take(4)}") }
                        )
                    }
                }
                
                if (uiState.groupMembers.isEmpty()) {
                    Text(
                        "No other users found. Add users from a group first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Group expense: show group members
                Text("Participants", style = MaterialTheme.typography.labelMedium)
                Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.groupMembers.forEach { userId ->
                        FilterChip(
                                selected = userId in uiState.selectedParticipants,
                                onClick = { viewModel.toggleParticipant(userId) },
                                label = { Text(uiState.userNames[userId] ?: "User ${userId.take(4)}") }
                        )
                    }
                }
            }

            // Split Input Section - show for any expense with participants selected
            if (uiState.selectedParticipants.isNotEmpty()) {
                when (uiState.splitType) {
                    SplitType.EQUAL -> {
                        SplitPreviewSection(
                                splitPreview = uiState.splitPreview,
                                userNames = uiState.userNames
                        )
                    }
                    SplitType.EXACT -> {
                        ExactAmountInputSection(
                                participants = uiState.selectedParticipants,
                                amounts = uiState.exactAmounts,
                                userNames = uiState.userNames,
                                onAmountChange = { userId, amount ->
                                    viewModel.updateExactAmount(userId, amount)
                                }
                        )
                    }
                    SplitType.PERCENTAGE -> {
                        PercentageInputSection(
                                participants = uiState.selectedParticipants,
                                percentages = uiState.percentages,
                                userNames = uiState.userNames,
                                onPercentageChange = { userId, pct ->
                                    viewModel.updatePercentage(userId, pct)
                                }
                        )
                    }
                    SplitType.SHARES -> {
                        SharesInputSection(
                                participants = uiState.selectedParticipants,
                                shares = uiState.shares,
                                userNames = uiState.userNames,
                                onSharesChange = { userId, count ->
                                    viewModel.updateShares(userId, count)
                                }
                        )
                    }
                }
            }

            // Split Preview (for non-EQUAL types)
            if (uiState.splitType != SplitType.EQUAL && uiState.splitPreview.isNotEmpty()) {
                Text("Preview", style = MaterialTheme.typography.labelMedium)
                SplitPreviewSection(uiState.splitPreview, userNames = uiState.userNames)
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

            if (uiState.isLoading) {
                CircularProgressIndicator()
            } else {
                Button(
                        onClick = { viewModel.saveExpense() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled =
                                uiState.validationResult is SplitValidationResult.Valid &&
                                        uiState.title.isNotBlank() &&
                                        uiState.amountText.isNotBlank()
                ) { Text(text = if (uiState.isEditMode) "Update Expense" else "Save Expense") }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SplitPreviewSection(
        splitPreview: Map<String, BigDecimal>,
        userNames: Map<String, String>
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        splitPreview.entries.forEach { (userId, amount) ->
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                        userNames[userId] ?: "User ${userId.take(4)}",
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
        userNames: Map<String, String>,
        onAmountChange: (String, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        participants.forEach { userId ->
            OutlinedTextField(
                    value = amounts[userId] ?: "",
                    onValueChange = { onAmountChange(userId, it) },
                    label = { Text(userNames[userId] ?: "User ${userId.take(4)}") },
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
        userNames: Map<String, String>,
        onPercentageChange: (String, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        participants.forEach { userId ->
            OutlinedTextField(
                    value = percentages[userId] ?: "",
                    onValueChange = { onPercentageChange(userId, it) },
                    label = { Text(userNames[userId] ?: "User ${userId.take(4)}") },
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
        userNames: Map<String, String>,
        onSharesChange: (String, Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        participants.forEach { userId ->
            val currentShares = shares[userId] ?: 1
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                        userNames[userId] ?: "User ${userId.take(4)}",
                        style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                            onClick = {
                                onSharesChange(userId, (currentShares - 1).coerceAtLeast(1))
                            }
                    ) { Text("-", style = MaterialTheme.typography.titleLarge) }
                    Text(
                            text = currentShares.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(32.dp)
                    )
                    IconButton(onClick = { onSharesChange(userId, currentShares + 1) }) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}
