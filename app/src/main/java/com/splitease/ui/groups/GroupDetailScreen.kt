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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import com.splitease.ui.common.SyncStatusIcon
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.splitease.data.sync.SyncState
import androidx.hilt.navigation.compose.hiltViewModel
import com.splitease.data.local.entities.Expense
import com.splitease.data.local.entities.User
import com.splitease.data.identity.IdentityConstants
import com.splitease.domain.SettlementMode
import com.splitease.domain.SettlementSuggestion
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import com.splitease.ui.common.EmptyState
import com.splitease.ui.common.Formatters

/**
 * Renders the Group Detail screen UI, showing group metadata, members, balances, settlements, expenses, and available actions.
 *
 * This composable observes the provided view model for UI state and one-off events (snackbars, leave dialogs, navigation)
 * and drives the screen UI accordingly. It also exposes navigation and action callbacks for adding/editing expenses and
 * viewing sync issues.
 *
 * @param onNavigateBack Called when the user requests to navigate back to the previous screen (e.g., back button or navigation event).
 * @param onNavigateToAddExpense Called with the current group ID to navigate to the Add Expense screen.
 * @param onNavigateToEditExpense Called with the group ID and expense ID to navigate to the Edit Expense screen for the selected expense.
 * @param onNavigateToSyncIssues Called to navigate to the sync issues screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAddExpense: (groupId: String) -> Unit,
    onNavigateToEditExpense: (groupId: String, expenseId: String) -> Unit,
    onNavigateToSyncIssues: () -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Expanded settlement row state
    var expandedSettlementKey by remember { mutableStateOf<String?>(null) }
    
    // Leave Group / Remove Member Dialog States
    var showLeaveConfirmation by remember { mutableStateOf(false) }
    var showLeaveBlockedByBalance by remember { mutableStateOf(false) }
    var showLeaveBlockedAsLastMember by remember { mutableStateOf(false) }
    
    // Remove Member (Peer) States
    var showRemoveConfirmation by remember { mutableStateOf<String?>(null) } // targetUserId or null
    var showRemoveBlockedByBalance by remember { mutableStateOf<String?>(null) } // targetUserId
    var showRemoveBlockedAsLastMember by remember { mutableStateOf(false) }

    val isRefreshing = (uiState as? GroupDetailUiState.Success)?.isRefreshing ?: false

    // Handle one-off events
    LaunchedEffect(viewModel.events) {
        viewModel.events.collect { event ->
            when (event) {
                is GroupDetailEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is GroupDetailEvent.ShowLeaveConfirmation -> {
                    showLeaveConfirmation = true
                }
                is GroupDetailEvent.ShowLeaveBlockedByBalance -> {
                    showLeaveBlockedByBalance = true
                }
                is GroupDetailEvent.ShowLeaveBlockedAsLastMember -> {
                    showLeaveBlockedAsLastMember = true
                }
                is GroupDetailEvent.NavigateToDashboard -> {
                    onNavigateBack()
                }
                is GroupDetailEvent.ShowRemoveConfirmation -> {
                     showRemoveConfirmation = event.targetUserId
                }
                is GroupDetailEvent.ShowRemoveBlockedByBalance -> {
                    showRemoveBlockedByBalance = event.targetUserId
                }
                is GroupDetailEvent.ShowRemoveBlockedAsLastMember -> {
                    showRemoveBlockedAsLastMember = true
                }
                is GroupDetailEvent.ShowRemoveSuccess -> {
                     snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    // Confirmation Dialog: User can leave
    if (showLeaveConfirmation) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirmation = false },
            title = { Text("Leave Group") },
            text = { Text("You will leave this group. Past expenses will remain unchanged.") },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirmation = false
                    viewModel.onConfirmLeaveGroup()
                }) {
                    Text("Leave")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirmation = false }) {
                    Text("Cancel")
                }
            },
            icon = { Icon(Icons.Default.Info, contentDescription = null) }
        )
    }

    // Blocked Dialog: Outstanding Balance
    if (showLeaveBlockedByBalance) {
        AlertDialog(
            onDismissRequest = { showLeaveBlockedByBalance = false },
            title = { Text("Cannot Leave Group") },
            text = { Text("You cannot leave this group because you have an outstanding balance. Please settle up first.") },
            confirmButton = {
                TextButton(onClick = { showLeaveBlockedByBalance = false }) {
                    Text("OK")
                }
            },
            icon = { Icon(Icons.Default.Info, contentDescription = null) }
        )
    }

    // Blocked Dialog: Last Member
    if (showLeaveBlockedAsLastMember) {
        AlertDialog(
            onDismissRequest = { showLeaveBlockedAsLastMember = false },
            title = { Text("Cannot Leave Group") },
            text = { Text("You are the last member of this group and cannot leave.") },
            confirmButton = {
                TextButton(onClick = { showLeaveBlockedAsLastMember = false }) {
                    Text("OK")
                }
            },
            icon = { Icon(Icons.Default.Info, contentDescription = null) }
        )
    }

    // --- Remove Member (Peer) Dialogs ---

    // Confirm Removal
    showRemoveConfirmation?.let { targetUserId ->
        val targetUser = (uiState as? GroupDetailUiState.Success)?.members?.find { it.id == targetUserId }
        val targetName = targetUser?.name ?: "this member"
        
        AlertDialog(
            onDismissRequest = { showRemoveConfirmation = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = "Warning") },
            title = { Text("Remove $targetName?") },
            text = { Text("Removing $targetName will not change past expenses. They will lose access to the group.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveConfirmation = null
                        viewModel.onConfirmRemoveMember(targetUserId)
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirmation = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Blocked: Balance
    showRemoveBlockedByBalance?.let { targetUserId ->
        val targetUser = (uiState as? GroupDetailUiState.Success)?.members?.find { it.id == targetUserId }
        val targetName = targetUser?.name ?: "This member"

        AlertDialog(
            onDismissRequest = { showRemoveBlockedByBalance = null },
            icon = { Icon(Icons.Default.Info, contentDescription = "Blocked") },
            title = { Text("Cannot remove $targetName") },
            text = { Text("$targetName has an outstanding balance. They must settle up before being removed.") },
            confirmButton = {
                TextButton(onClick = { showRemoveBlockedByBalance = null }) {
                    Text("OK")
                }
            }
        )
    }

    // Blocked: Last Member
    if (showRemoveBlockedAsLastMember) {
        AlertDialog(
            onDismissRequest = { showRemoveBlockedAsLastMember = false },
            icon = { Icon(Icons.Default.Info, contentDescription = "Blocked") },
            title = { Text("Cannot remove member") },
            text = { Text("This group must have at least one member.") },
            confirmButton = {
                TextButton(onClick = { showRemoveBlockedAsLastMember = false }) {
                    Text("OK")
                }
            }
        )
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
                },
                actions = {
                    // Contextual sync status: Failure (⚠️) overrides Pending (⏳)
                    if (uiState is GroupDetailUiState.Success) {
                        val state = uiState as GroupDetailUiState.Success
                        
                        SyncStatusIcon(
                            syncState = state.groupSyncState,
                            failedCount = state.groupFailedSyncCount,
                            pendingCount = state.pendingGroupSyncCount,
                            onNavigateToSyncIssues = onNavigateToSyncIssues
                        )
                    }
                    
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Leave Group") },
                            onClick = {
                                showMenu = false
                                viewModel.onLeaveGroupClicked()
                            }
                        )
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
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
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
                            val creator = state.members.find { it.id == state.group.createdByUserId }
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
                                        MemberAvatar(
                                            user = member,
                                            isRemovable = member.id != state.currentUserId,
                                            onRemove = { viewModel.onRemoveMemberClicked(member.id) }
                                        )
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
                             Row(
                                 modifier = Modifier
                                     .fillMaxWidth()
                                     .padding(top = 16.dp),
                                 horizontalArrangement = Arrangement.SpaceBetween,
                                 verticalAlignment = Alignment.CenterVertically
                             ) {
                                 Text(
                                     text = "Settle Up",
                                     style = MaterialTheme.typography.titleMedium
                                 )
                                 Row(
                                     verticalAlignment = Alignment.CenterVertically
                                 ) {
                                     Text(
                                         text = "Simplify",
                                         style = MaterialTheme.typography.bodySmall
                                     )
                                     Switch(
                                         checked = state.settlementMode == SettlementMode.SIMPLIFIED,
                                         onCheckedChange = { viewModel.toggleSettlementMode(it) }
                                     )
                                 }
                             }
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
                                 EmptyState(
                                     icon = Icons.Default.Info, 
                                     title = "No expenses yet",
                                     message = "Tap + to add your first expense.", // No generic "nothing here"
                                     modifier = Modifier.padding(vertical = 32.dp),
                                     // No explicit button here as FAB covers it, but message is actionable
                                 )
                             }
                         } else {
                             // Group expenses by date (Non-sticky headers per plan)
                             val groupedExpenses = state.expenses.groupBy { 
                                 Formatters.formatDateHeader(it.date.time) 
                             }
                             
                             groupedExpenses.forEach { (header, expenses) ->
                                 item {
                                     Text(
                                         text = header,
                                         style = MaterialTheme.typography.labelMedium,
                                         color = MaterialTheme.colorScheme.primary,
                                         modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                                     )
                                 }
                                 items(expenses) { expense ->
                                     val isPending = expense.id in state.pendingExpenseIds
                                     val creator = state.members.find { it.id == expense.createdByUserId }
                                     val showAttribution = expense.createdByUserId != IdentityConstants.LEGACY_USER_ID
                                     val attribution = if (expense.createdByUserId == state.currentUserId) "You" else creator?.name ?: "Unknown"
                                     
                                     ExpenseItem(
                                         expense = expense,
                                         isPending = isPending,
                                         onClick = { onNavigateToEditExpense(state.group.id, expense.id) },
                                         showAttribution = showAttribution,
                                         attributionText = "Added by $attribution"
                                     )
                                 }
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
private fun MemberAvatar(
    user: User,
    isRemovable: Boolean = false,
    onRemove: () -> Unit = {}
) {
    Box(modifier = Modifier.width(68.dp)) { // Slightly wider to accommodate badge
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp) // Push down for badge clearance
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
        
        if (isRemovable) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp) // Inset slightly
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete, // Using Delete/Trash icon
                    contentDescription = "Remove member",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}


@Composable
fun ExpenseItem(
    expense: Expense, 
    isPending: Boolean = false,
    showAttribution: Boolean = false,
    attributionText: String = "",
    onClick: () -> Unit
) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = expense.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (isPending) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Pending sync",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (showAttribution) {
                    Text(
                        text = attributionText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
            Text(
                text = Formatters.formatMoney(expense.amount, expense.currency),
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
                text = Formatters.formatMoney(amount),
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
    isPending: Boolean = false,
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
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$fromName → $toName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isExecuting) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) 
                                else MaterialTheme.colorScheme.onSurface
                    )
                    if (isPending) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Pending sync",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isExecuting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        text = Formatters.formatMoney(suggestion.amount),
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

