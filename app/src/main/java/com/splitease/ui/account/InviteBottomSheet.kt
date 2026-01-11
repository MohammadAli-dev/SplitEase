package com.splitease.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet for sharing an invite link.
 * Auto-dismisses after Share or Copy action.
 */
/**
 * Displays a modal bottom sheet that shows an invite deep link and provides actions to share or copy it.
 *
 * The sheet presents the provided `deepLink`, a "Share" button that invokes a share action then dismisses the sheet,
 * and a "Copy link" button that copies the link, invokes `onShowSnackbar` with a confirmation message, then dismisses.
 *
 * @param deepLink The invite link to display and act upon.
 * @param onDismiss Callback invoked to dismiss the bottom sheet.
 * @param onShowSnackbar Callback used to show a snackbar message; called with the confirmation text when the link is copied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteBottomSheet(
    deepLink: String,
    onDismiss: () -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Share this link to connect:",
                style = MaterialTheme.typography.titleMedium
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = deepLink,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    context.shareInviteLink(deepLink)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Share")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    context.copyToClipboard(deepLink)
                    onShowSnackbar("Link copied")
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Copy link")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}