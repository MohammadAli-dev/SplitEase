package com.splitease.ui.settleup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.SwapHoriz
import com.splitease.ui.components.PersonPicker
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettleUpScreen(
    onNavigateUp: () -> Unit,
    viewModel: SettleUpViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isSettled) {
        if (uiState.isSettled) {
            viewModel.resetSettledState()
            onNavigateUp()
        }
    }

    var showPayerPicker by remember { mutableStateOf(false) }
    var showReceiverPicker by remember { mutableStateOf(false) }

    if (showPayerPicker || showReceiverPicker) {
        ModalBottomSheet(
            onDismissRequest = { 
                showPayerPicker = false 
                showReceiverPicker = false
            },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    text = if (showPayerPicker) "Who paid?" else "To whom?",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                PersonPicker(
                    persons = uiState.availablePersons,
                    selectedPersonIds = if (showPayerPicker) 
                        setOfNotNull(uiState.payerPersonId) 
                    else 
                        setOfNotNull(uiState.receiverPersonId),
                    onToggleSelection = { personId ->
                        if (showPayerPicker) viewModel.setPayer(personId) else viewModel.setReceiver(personId)
                        showPayerPicker = false
                        showReceiverPicker = false
                    },
                    allowMultiple = false,
                    onCreatePerson = null // No creation in settle up for now
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settle Up") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Informational Balance Header
            val balance = uiState.balance
            val isOwedToMe = balance > BigDecimal.ZERO
            val isOwing = balance < BigDecimal.ZERO
            val statusColor = when {
                isOwedToMe -> Color(0xFF00C853)
                isOwing -> Color(0xFFFF5252)
                else -> Color.Gray
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Current Balance",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = when {
                        isOwedToMe -> "${uiState.friendName} owes you ₹$balance"
                        isOwing -> "You owe ${uiState.friendName} ₹${balance.abs()}"
                        else -> "All settled up"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor
                )
            }

            // Explicit Selection Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Payer
                Column(modifier = Modifier.weight(1f)) {
                    Text("Payer", style = MaterialTheme.typography.labelSmall)
                    FilterChip(
                        selected = true,
                        onClick = { showPayerPicker = true },
                        label = { 
                            val name = uiState.availablePersons.find { it.id == uiState.payerPersonId }?.displayName ?: "Select"
                            Text(name) 
                        }
                    )
                }

                IconButton(
                    onClick = viewModel::swapPayerReceiver,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Swap")
                }

                // Receiver
                Column(modifier = Modifier.weight(1f)) {
                    Text("Receiver", style = MaterialTheme.typography.labelSmall)
                    FilterChip(
                        selected = true,
                        onClick = { showReceiverPicker = true },
                        label = { 
                            val name = uiState.availablePersons.find { it.id == uiState.receiverPersonId }?.displayName ?: "Select"
                            Text(name) 
                        }
                    )
                }
            }

            // Amount Input
            OutlinedTextField(
                value = uiState.amountInput,
                onValueChange = viewModel::onAmountChanged,
                label = { Text("Amount") },
                prefix = { Text("₹") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            // Settle Button
            Button(
                onClick = viewModel::onSettleUp,
                enabled = uiState.canSettle && !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (uiState.isLoading) "Processing..." else "Record Payment")
            }
            
            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
