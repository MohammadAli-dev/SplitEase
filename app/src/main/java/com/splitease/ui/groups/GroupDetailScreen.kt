package com.splitease.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.User
import com.splitease.domain.SettlementSuggestion
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAddExpense: (groupId: String) -> Unit,
    onNavigateToEditExpense: (groupId: String, expenseId: String) -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Expanded settlement row state
    var expandedSettlementKey by remember { mutableStateOf<String?>(null) }

    // Handle one-off events (Snackbar)
    LaunchedEffect(viewModel.events) {
        viewModel.events.collect { event ->
            when (event) {
                is GroupDetailEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (val state = uiState) {
                            is GroupDetailUiState.Success -> state.group.name
                            else -> "Group Details"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState is GroupDetailUiState.Success) {
                val groupId = (uiState as GroupDetailUiState.Success).group.id
                FloatingActionButton(onClick = { onNavigateToAddExpense(groupId) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Expense")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is GroupDetailUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is GroupDetailUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = viewModel::retry) {
                            Text("Retry")
                        }
                    }
                }
                is GroupDetailUiState.Success -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(8.dp)) }

                        // Group Type Chip
                        item {
                            FilterChip(
                                selected = true,
                                onClick = { },
                                label = { Text(state.group.type) },
                                enabled = false
                            )
                        }

                        // Group Metadata
                        item {
                            val creator = state.members.find { it.id == state.group.createdBy }
                            Text(
                                text = "Created by ${creator?.name ?: "Unknown"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Members Section
                        item {
                            Text(
                                text = "Members (${state.members.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        item {
                            if (state.members.isEmpty()) {
                                Text(
                                    text = "No members found",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(state.members) { member ->
                                        MemberAvatar(user = member)
                                    }
                                }
                            }
                        }

                        // Balances Section
                        item {
                            Text(
                                text = "Balances",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }

                        item {
                            val nonZeroBalances = state.balances
                                .filterValues { it.compareTo(java.math.BigDecimal.ZERO) != 0 }
                                .toList()
                                .sortedByDescending { it.second.abs() }

                            if (nonZeroBalances.isEmpty()) {
                                Text(
                                    text = "All settled 🎉",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    nonZeroBalances.forEach { (userId, amount) ->
                                        val user = state.members.find { it.id == userId }
                                        val isOwed = amount.signum() > 0
                                        BalanceRow(
                                            userName = user?.name ?: "Unknown",
                                            amount = amount,
                                            isOwed = isOwed
                                        )
                                    }
                                }
                            }
                        }

                        // Settle Up Section
                        item {
                            Text(
                                text = "Settle Up",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }

                        item {
                            if (state.settlements.isEmpty()) {
                                Text(
                                    text = "No settlements needed 🎉",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.settlements.forEach { settlement ->
                                        val fromUser = state.members.find { it.id == settlement.fromUserId }
                                        val toUser = state.members.find { it.id == settlement.toUserId }
                                        val isExecuting = state.executingSettlements.contains(settlement.key)
                                        val isExpanded = expandedSettlementKey == settlement.key
                                        
                                        ExpandableSettlementCard(
                                            suggestion = settlement,
                                            fromName = fromUser?.name ?: "Unknown",
                                            toName = toUser?.name ?: "Unknown",
                                            isExpanded = isExpanded,
                                            isExecuting = isExecuting,
                                            onExpandToggle = {
                                                expandedSettlementKey = if (isExpanded) null else settlement.key
                                            },
                                            onSettle = { amount ->
                                                viewModel.executeSettlement(settlement, amount)
                                                expandedSettlementKey = null
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Expenses Section
                        item {
                            Text(
                                text = "Expenses (${state.expenses.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }

                        if (state.expenses.isEmpty()) {
                            item {
                                Text(
                                    text = "No expenses yet. Tap + to add one.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            items(state.expenses) { expense ->
                                ExpenseItem(
                                    expense = expense,
                                    onClick = { onNavigateToEditExpense(state.group.id, expense.id) }
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(80.dp)) } // FAB clearance
                    }
                }
            }
        }
    }


}

@Composable
private fun MemberAvatar(user: User) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = user.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = user.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ExpenseItem(expense: Expense, onClick: () -> Unit) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = dateFormat.format(expense.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${expense.currency} ${expense.amount}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun BalanceRow(
    userName: String,
    amount: java.math.BigDecimal,
    isOwed: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = userName,
            style = MaterialTheme.typography.bodyMedium
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = com.splitease.domain.MoneyFormatter.format(amount),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isOwed) {
                    androidx.compose.ui.graphics.Color(0xFF2E7D32) // Green
                } else {
                    androidx.compose.ui.graphics.Color(0xFFC62828) // Red
                }
            )
            Text(
                text = if (isOwed) "Gets back" else "Pays",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExpandableSettlementCard(
    suggestion: SettlementSuggestion,
    fromName: String,
    toName: String,
    isExpanded: Boolean,
    isExecuting: Boolean,
    onExpandToggle: () -> Unit,
    onSettle: (BigDecimal) -> Unit
) {
    var amountText by remember(suggestion.key) { mutableStateOf(suggestion.amount.toPlainString()) }
    val parsedAmount = amountText.toBigDecimalOrNull()
    val isValidAmount = parsedAmount != null && 
        parsedAmount > BigDecimal.ZERO && 
        parsedAmount <= suggestion.amount
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row (always visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isExecuting, onClick = onExpandToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$fromName → $toName",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = if (isExecuting) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) 
                            else MaterialTheme.colorScheme.onSurface
                )
                if (isExecuting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        text = com.splitease.domain.MoneyFormatter.format(suggestion.amount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // Expanded content
            if (isExpanded && !isExecuting) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { newValue ->
                            // Clamp to 2 decimal places
                            val filtered = newValue.filter { it.isDigit() || it == '.' }
                            val parts = filtered.split(".")
                            amountText = if (parts.size > 1) {
                                parts[0] + "." + parts[1].take(2)
                            } else {
                                filtered
                            }
                        },
                        label = { Text("Amount") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = amountText.isNotEmpty() && !isValidAmount
                    )
                    
                    Button(
                        onClick = { parsedAmount?.let { onSettle(it) } },
                        enabled = isValidAmount
                    ) {
                        Text("Settle")
                    }
                }
                
                if (amountText.isNotEmpty() && !isValidAmount) {
                    Text(
                        text = "Amount must be between ₹0.01 and ₹${suggestion.amount.toPlainString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}


