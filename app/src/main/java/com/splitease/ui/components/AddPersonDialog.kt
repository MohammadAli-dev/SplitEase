package com.splitease.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Reusable dialog/bottom sheet for adding a new phantom user.
 * 
 * Includes:
 * - Name (Required)
 * - Email (Optional metadata)
 * - Phone (Optional metadata)
 * 
 * On confirmation, invokes `onConfirm` with the name and optional email/phone values.
 */
/**
 * Shows a modal bottom sheet that collects a required name and optional email and phone from the user.
 *
 * The UI validates that the name is non-blank and displays inline error text when empty. If the user
 * submits a valid name, `onConfirm` is invoked with the trimmed name and trimmed optional fields,
 * where empty email or phone are normalized to `null`. `onDismiss` is invoked when the sheet is
 * dismissed or when the user cancels; it is also called after a successful confirmation.
 *
 * @param onDismiss Callback invoked to dismiss the dialog.
 * @param onConfirm Callback invoked on successful submission with the collected values:
 * - `name`: trimmed, non-blank name.
 * - `email`: trimmed email or `null` if blank.
 * - `phone`: trimmed phone or `null` if blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPersonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String?, String?) -> Unit // name, email, phone
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var email by remember { mutableStateOf(TextFieldValue("")) }
    var phone by remember { mutableStateOf(TextFieldValue("")) }
    
    var isError by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Add new person",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Name
            OutlinedTextField(
                value = name,
                onValueChange = { 
                    name = it
                    if (it.text.isNotBlank()) isError = false
                },
                label = { Text("Name") },
                isError = isError,
                supportingText = {
                    if (isError) Text("Name is required")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            // Email
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email (Optional)") },
                supportingText = { Text("Helps you remember who this is") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Phone
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone (Optional)") },
                supportingText = { Text("Helps you remember who this is") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Button(
                    onClick = {
                        val nameText = name.text.trim()
                        if (nameText.isBlank()) {
                            isError = true
                        } else {
                            onConfirm(
                                nameText,
                                email.text.trim().ifBlank { null },
                                phone.text.trim().ifBlank { null }
                            )
                            onDismiss()
                        }
                    }
                ) {
                    Text("Add Person")
                }
            }
        }
    }
}