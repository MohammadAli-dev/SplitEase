package com.splitease.ui.ledger

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.splitease.data.repository.FriendLedgerItem
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Displays a friend's ledger screen with balance, actions, and a list of ledger items.
 *
 * Shows a loading indicator while state is loading, a header with the friend's name and balance,
 * action buttons for settling or reminding, and a list of expenses and settlements. Ledger rows
 * are clickable to edit the underlying expense except for settlement items which are read-only.
 *
 * @param onNavigateBack Invoked when the user requests back navigation (e.g., top app bar back).
 * @param onNavigateToEditExpense Invoked with `groupId` and `expenseId` when the user opens an expense for editing.
 * @param onNavigateToSettleUp Invoked with the friend's id when the user initiates a settle-up flow.
 * @param viewModel Provides UI state for the screen; defaults to the Hilt-provided PersonalLedgerViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalLedgerScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEditExpense: (groupId: String, expenseId: String) -> Unit,
    onNavigateToSettleUp: (friendId: String) -> Unit,
    viewModel: PersonalLedgerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.friendName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* TODO: Add expense with this friend */ },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add expense")
            }
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Balance Header (Read-Only)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.friendName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = uiState.balanceDisplayText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            uiState.balance > BigDecimal.ZERO -> Color(0xFF4CAF50) // Green - owed
                            uiState.balance < BigDecimal.ZERO -> Color(0xFFFF9800) // Orange - owe
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            
            // Action Buttons Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onNavigateToSettleUp(uiState.friendId) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00C853) // Green for Settle Up
                    ),
                    enabled = uiState.balance.compareTo(BigDecimal.ZERO) != 0 // Disable if settled
                ) {
                    Text("Settle up")
                }
                OutlinedButton(
                    onClick = { /* TODO: Remind */ },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Remind")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Ledger List
            if (uiState.ledgerItems.isEmpty()) {
                // Empty State
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No expenses with this person yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "All Expenses",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.ledgerItems) { ledgerItem ->
                        LedgerItemRow(
                            item = ledgerItem,
                            onClick = {
                                // Direct/Group expenses navigate to edit.
                                // Settlements are read-only.
                                if (ledgerItem !is FriendLedgerItem.SettlementItem) {
                                    onNavigateToEditExpense(
                                        ledgerItem.groupId,
                                        ledgerItem.expenseId
                                    )
                                }
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

/**
 * Renders a single ledger row for a FriendLedgerItem with type-specific iconography, colors, and texts.
 *
 * Displays title, a context-specific subtitle, formatted date, amount, and a share/settlement hint.
 * Settlement items are styled distinctly (teal accent) and are non-clickable; other item types are clickable
 * and will invoke the provided onClick callback.
 *
 * @param item The ledger item to display (GroupExpense, DirectExpense, or SettlementItem).
 * @param onClick Action to perform when the row is tapped; not invoked for settlement items.
 */
@Composable
private fun LedgerItemRow(
    item: FriendLedgerItem,
    onClick: () -> Unit
) {
    val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    
    // Determine subtitle based on expense type
    val subtitle = when (item) {
        is FriendLedgerItem.GroupExpense -> item.groupName
        is FriendLedgerItem.DirectExpense -> "${item.payerName} paid ₹${item.amount}"
        is FriendLedgerItem.SettlementItem -> "${item.payerName} paid ₹${item.amount}"
    }
    
    // Determine icon
    val icon = when (item) {
        is FriendLedgerItem.GroupExpense -> Icons.Default.Home
        is FriendLedgerItem.DirectExpense -> Icons.Default.Person
        is FriendLedgerItem.SettlementItem -> Icons.Default.CheckCircle
    }
    
    // Determine share color
    val isSettlement = item is FriendLedgerItem.SettlementItem
    
    // For settlements: if I paid (paidByCurrentUser), it's green (money left me, debt reduced? Wait).
    // Usually: Green = "I got money" or "You owe me reduced".
    // If I paid: I spent money.
    // If Friend paid: They spent money.
    // In Splitwise:
    // "You paid Alice" -> Green? No, neutral. "You paid".
    // "Alice paid you" -> Green.
    // Let's iterate color.
    // Group/Direct color logic:
    // Green = Owed TO me (Lent).
    // Orange = I Owe (Borrowed).
    
    val shareColor = if (item.paidByCurrentUser) Color(0xFF4CAF50) else Color(0xFFFF9800)
    
    val shareText = if (isSettlement) {
        if (item.paidByCurrentUser) "you paid" else "${item.payerName} paid"
    } else {
        if (item.paidByCurrentUser) {
            "you lent ₹${item.amount - item.myShare}"
        } else {
            "you borrowed ₹${item.myShare}"
        }
    }
    
    // Settlement specific styling override
    val titleColor = if (isSettlement) Color(0xFF009688) else Color.Unspecified // Teal for settlement title?
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, enabled = !isSettlement)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSettlement) Color(0xFF009688) else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Title + Subtitle + Date
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = titleColor
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = dateFormatter.format(Date(item.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Amount + Share info
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "₹${item.amount}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (isSettlement) Color(0xFF009688) else Color.Unspecified
            )
            Text(
                text = shareText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSettlement) Color.Gray else shareColor
            )
        }
    }
}