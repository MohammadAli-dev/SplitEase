package com.splitease.ui.account

import android.util.Patterns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Dialog for editing user email.
 * Includes verification warning as required by Sprint 13F.
 */
@Composable
fun EditEmailDialog(
    currentEmail: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var email by remember { mutableStateOf(currentEmail) }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    fun validateEmail(value: String): Boolean {
        return when {
            value.isBlank() -> {
                errorMessage = "Email cannot be empty"
                false
            }
            !Patterns.EMAIL_ADDRESS.matcher(value).matches() -> {
                errorMessage = "Invalid email format"
                false
            }
            value == currentEmail -> {
                errorMessage = "Enter a different email"
                false
            }
            else -> true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Email") },
        text = {
            Column {
                OutlinedTextField(
                    value = email,
                    onValueChange = { 
                        email = it
                        isError = !validateEmail(it)
                    },
                    label = { Text("New Email") },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        { Text(errorMessage) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Verification warning - REQUIRED by Sprint 13F
                Text(
                    text = "A confirmation link will be sent to your new email address. " +
                           "Your email won't change until you verify it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (validateEmail(email)) {
                        onConfirm(email.trim())
                    } else {
                        isError = true
                    }
                },
                enabled = email.isNotBlank() && email != currentEmail
            ) {
                Text("Send Verification")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
