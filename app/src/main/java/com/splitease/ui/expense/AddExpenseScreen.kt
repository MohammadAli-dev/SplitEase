package com.splitease.ui.expense

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.splitease.domain.SplitValidationResult

@Composable
fun AddExpenseScreen(
    groupId: String,
    onExpenseSaved: () -> Unit,
    viewModel: AddExpenseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Add Expense",
            style = MaterialTheme.typography.headlineMedium
        )

        OutlinedTextField(
            value = uiState.title,
            onValueChange = { viewModel.updateTitle(it) },
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = uiState.amountText,
            onValueChange = { viewModel.updateAmount(it) },
            label = { Text("Amount (₹)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        // Split Type Selector (simplified for Sprint 5)
        Text(
            text = "Split Type: Equal",
            style = MaterialTheme.typography.bodyMedium
        )

        // Participants (showing count for now)
        Text(
            text = "Participants: ${uiState.selectedParticipants.size}",
            style = MaterialTheme.typography.bodyMedium
        )

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
                onClick = { 
                    viewModel.saveExpense()
                    // TODO: Navigate back on success
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.validationResult is SplitValidationResult.Valid
            ) {
                Text("Save Expense")
            }
        }
    }
}
