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
import androidx.compose.material.icons.filled.ArrowBack
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
import java.math.BigDecimal

/**
 * Composable screen for settling a monetary balance with a friend.
 *
 * Displays the friend's name and current balance status, provides an amount input and a "Settle Up"
 * action when a non-zero balance exists, and offers a "Return to Ledger" action when the balance
 * is zero. Observes the view model's UI state to update the UI and react to loading/error states.
 *
 * When the view model reports that settlement completed, this screen resets the settled state on
 * the view model and invokes the navigation callback.
 *
 * @param onNavigateUp Callback invoked to navigate back; also invoked automatically after a
 * successful settlement.
 * @param viewModel View model that supplies UI state and actions for settling up. Defaults to a
 * Hilt-provided instance.
 */
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settle Up") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = "Settling with ${uiState.friendName}",
                style = MaterialTheme.typography.headlineSmall
            )

            // Balance Status
            val balance = uiState.balance
            val isOwedToMe = balance > BigDecimal.ZERO
            val isOwing = balance < BigDecimal.ZERO
            
            val statusText = when {
                isOwedToMe -> "${uiState.friendName} owes you ₹$balance"
                isOwing -> "You owe ${uiState.friendName} ₹${balance.abs()}"
                else -> "All settled up! 🎉"
            }
            
            val statusColor = when {
                isOwedToMe -> Color(0xFF00C853) // Green 
                isOwing -> Color(0xFFFF5252) // Red
                else -> Color.Gray
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                color = statusColor
            )

            if (balance.abs().compareTo(BigDecimal.ZERO) > 0) {                // Input
                OutlinedTextField(
                    value = uiState.amountInput,
                    onValueChange = viewModel::onAmountChanged,
                    label = { Text("Amount") },
                    prefix = { Text("₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = uiState.amountError != null,
                    supportingText = {
                        if (uiState.amountError != null) {
                            Text(uiState.amountError!!)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Action Button
                Button(
                    onClick = viewModel::onSettleUp,
                    enabled = uiState.canSettle && !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.isLoading) "Processing..." else "Settle Up")
                }
                
                if (uiState.errorMessage != null) {
                    Text(
                        text = uiState.errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                // Empty State (Zero Balance)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onNavigateUp) {
                    Text("Return to Ledger")
                }
            }
        }
    }
}