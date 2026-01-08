package com.splitease.ui.friends

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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
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

@OptIn(ExperimentalMaterial3Api::class)
/**
 * Renders the friend detail screen with profile header, connection status banner, and transaction list.
 *
 * Collects UI state and one-off events from the provided ViewModel. Shows a centered loading indicator when
 * the UI state indicates loading. Displays the friend's avatar, name, and balance, a connection-status banner,
 * and either a "No transactions yet" message or a list of transactions.
 *
 * The `onNavigateBack` callback is invoked when the user taps the back navigation icon and also when the
 * view model emits a navigation-after-merge event.
 *
 * @param onNavigateBack Callback invoked to navigate back from this screen (also triggered after a merge completes).
 */
@Composable
fun FriendDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: FriendDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect one-off events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is FriendDetailEvent.NavigateBackAfterMerge -> onNavigateBack()
                is FriendDetailEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(uiState.friendName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
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
            // Header with Friend info and Balance
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
                            uiState.balance.compareTo(BigDecimal.ZERO) > 0 -> Color(0xFF4CAF50) // Green
                            uiState.balance.compareTo(BigDecimal.ZERO) < 0 -> Color(0xFFFF9800) // Orange
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // Connection Status Banner
            ConnectionStatusBanner(
                connectionState = uiState.connectionState,
                friendName = uiState.friendName,
                isMerging = uiState.isMerging,
                onCreateInvite = { viewModel.createInvite() },
                onRefresh = { viewModel.refreshConnectionStatus() },
                onFinalize = { viewModel.finalizeConnection() }
            )
            
            if (uiState.transactions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No transactions yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "Transactions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.transactions) { transaction ->
                        TransactionItem(transaction)
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

/**
 * Displays a banner reflecting the connection status between a phantom friend and a real account,
 * and surfaces context-appropriate actions (invite, check status, finalize) for that state.
 *
 * The banner presents different UI for the possible ConnectionUiState values: None, Loading,
 * InviteCreated, Claimed, Merged, and Error. When `isMerging` is true, the finalize action is
 * disabled and indicates merging progress.
 *
 * @param connectionState Current connection state driving the banner's content.
 * @param friendName Display name of the friend referenced in messages and actions.
 * @param isMerging True when a finalize/merge operation is in progress; disables the finalize action.
 * @param onCreateInvite Callback invoked when the user requests creating/sending an invite.
 * @param onRefresh Callback invoked to refresh or check the current invite/claim status.
 * @param onFinalize Callback invoked to finalize the connection once the invite is claimed.
 */
@Composable
private fun ConnectionStatusBanner(
    connectionState: ConnectionUiState,
    friendName: String,
    isMerging: Boolean,
    onCreateInvite: () -> Unit,
    onRefresh: () -> Unit,
    onFinalize: () -> Unit
) {
    when (connectionState) {
        is ConnectionUiState.None -> {
            // Show "Invite" option for phantom users
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Connect $friendName to a real account",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = onCreateInvite) {
                        Text("Invite")
                    }
                }
            }
        }
        is ConnectionUiState.Loading -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Creating invite...")
                }
            }
        }
        is ConnectionUiState.InviteCreated -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Waiting for $friendName to join...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = onRefresh) {
                            Text("Check Status")
                        }
                        // TODO: Add share button for invite link
                    }
                }
            }
        }
        is ConnectionUiState.Claimed -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${connectionState.claimerName} joined — Tap to finalize",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onFinalize,
                        enabled = !isMerging,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isMerging) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Merging...")
                        } else {
                            Text("Finalize Connection")
                        }
                    }
                }
            }
        }
        is ConnectionUiState.Merged -> {
            // Don't show anything - phantom is gone
        }
        is ConnectionUiState.Error -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Error: ${connectionState.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    TextButton(onClick = onRefresh) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

/**
 * Renders a single row representing a friend ledger item, showing an icon, title, subtitle, date, and amount.
 *
 * Displays different visuals based on the transaction subtype:
 * - GroupExpense: home icon and the group's name as subtitle.
 * - DirectExpense: person icon and "Direct Expense" as subtitle.
 * - SettlementItem: check-circle icon, "Settlement" as subtitle, and teal tint for title, icon, and amount.
 *
 * The amount is shown with a "₹" currency symbol and the date is formatted as "MMM dd, yyyy".
 *
 * @param transaction The ledger item to render; supported subtypes are `GroupExpense`, `DirectExpense`, and `SettlementItem`.
@Composable
private fun TransactionItem(transaction: FriendLedgerItem) {
    val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    
    // Determine icon
    val icon = when (transaction) {
        is FriendLedgerItem.GroupExpense -> Icons.Default.Home
        is FriendLedgerItem.DirectExpense -> Icons.Default.Person
        is FriendLedgerItem.SettlementItem -> Icons.Default.CheckCircle
    }
    
    // Determine subtitle
    val subtitle = when (transaction) {
        is FriendLedgerItem.GroupExpense -> transaction.groupName
        is FriendLedgerItem.DirectExpense -> "Direct Expense"
        is FriendLedgerItem.SettlementItem -> "Settlement"
    }
    
    // Determine title color (Teal for settlement)
    val isSettlement = transaction is FriendLedgerItem.SettlementItem
    val titleColor = if (isSettlement) Color(0xFF009688) else Color.Unspecified
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSettlement) Color(0xFF009688) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = transaction.title,
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
                    text = dateFormatter.format(Date(transaction.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Text(
            text = "₹${transaction.amount}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (isSettlement) Color(0xFF009688) else MaterialTheme.colorScheme.primary
        )
    }
}