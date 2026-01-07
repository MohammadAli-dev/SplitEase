package com.splitease.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.math.BigDecimal
import kotlinx.coroutines.launch


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import com.splitease.data.local.entities.Group
import com.splitease.domain.PersonalGroupConstants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToAddExpense: (String) -> Unit,
    onNavigateToCreateGroup: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    
    // Bottom sheet states
    var showFabOptionsSheet by remember { mutableStateOf(false) }
    var showGroupPickerSheet by remember { mutableStateOf(false) }
    val fabSheetState = rememberModalBottomSheetState()
    val groupSheetState = rememberModalBottomSheetState()
    
    // Dialog state for no groups
    var showNoGroupsDialog by remember { mutableStateOf(false) }

    // FAB Options Bottom Sheet
    if (showFabOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFabOptionsSheet = false },
            sheetState = fabSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Add Expense",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // Group Expense option
                Text(
                    text = "Group Expense",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                fabSheetState.hide()
                                showFabOptionsSheet = false
                                if (uiState.groups.isEmpty()) {
                                    showNoGroupsDialog = true
                                } else {
                                    showGroupPickerSheet = true
                                }
                            }
                        }
                        .padding(vertical = 16.dp)
                )
                Text(
                    text = "Split expenses with your group members",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                // Non-Group Expense option
                Text(
                    text = "Non-Group Expense",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                fabSheetState.hide()
                                showFabOptionsSheet = false
                                onNavigateToAddExpense(PersonalGroupConstants.PERSONAL_GROUP_ID)
                            }
                        }
                        .padding(vertical = 16.dp)
                )
                Text(
                    text = "Private IOU with specific people, without a group",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Group Picker Bottom Sheet
    if (showGroupPickerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showGroupPickerSheet = false },
            sheetState = groupSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Group",
                        style = MaterialTheme.typography.titleLarge
                    )
                    androidx.compose.material3.IconButton(
                        onClick = {
                            scope.launch {
                                groupSheetState.hide()
                                showGroupPickerSheet = false
                                onNavigateToCreateGroup()
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Create New Group",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                LazyColumn {
                    items(uiState.groups) { group ->
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        groupSheetState.hide()
                                        showGroupPickerSheet = false
                                        onNavigateToAddExpense(group.id)
                                    }
                                }
                                .padding(vertical = 16.dp)
                        )
                        HorizontalDivider()
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // No Groups Dialog (keep as AlertDialog for blocking action)
    if (showNoGroupsDialog) {
        AlertDialog(
            onDismissRequest = { showNoGroupsDialog = false },
            title = { Text("No Groups") },
            text = { Text("You don't have any groups yet. Create one to add group expenses.") },
            confirmButton = {
                TextButton(onClick = {
                    showNoGroupsDialog = false
                    onNavigateToCreateGroup()
                }) {
                    Text("Create Group")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoGroupsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showFabOptionsSheet = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Expense")
            }
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text(
                text = "Dashboard",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Total Balance Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Total Balance",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    if (uiState.totalOwed == BigDecimal.ZERO && uiState.totalOwing == BigDecimal.ZERO) {
                        Text(
                            text = "All settled up!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "You owe",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray
                                )
                                Text(
                                    text = "₹${uiState.totalOwing}",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.Red
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "You are owed",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray
                                )
                                Text(
                                    text = "₹${uiState.totalOwed}",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.Green
                                )
                            }
                        }
                    }
                }
            }
            
            // Friends Balance Section
            if (uiState.friendBalances.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Friends",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        uiState.friendBalances.forEach { friendBalance ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        // TODO: Navigate to FriendDetailScreen(friendBalance.friendId)
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = friendBalance.friendName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = friendBalance.displayText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (friendBalance.balance > java.math.BigDecimal.ZERO) 
                                            Color(0xFF4CAF50) else Color(0xFFFF9800)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "View details",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (friendBalance != uiState.friendBalances.last()) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            } else if (uiState.totalOwed == java.math.BigDecimal.ZERO && uiState.totalOwing == java.math.BigDecimal.ZERO) {
                // No balances at all - show empty state
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "No outstanding balances",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
