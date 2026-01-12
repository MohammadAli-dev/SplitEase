package com.splitease.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import com.splitease.R

/**
 * Dumb Google Sign-In button component.
 * 
 * Per FIX 15: This button does NOT start the Google SDK flow.
 * The parent screen owns all side-effects via the onClick callback.
 * 
 * Benefits:
 * - Reusable
 * - Previewable
 * - Testable
 * - No lifecycle leaks
 */
@Composable
fun GoogleSignInButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        border = BorderStroke(1.dp, Color.LightGray)
    ) {
        // Note: You'll need to add a Google "G" icon resource
        // For now using text only
        Text(
            text = "Continue with Google",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}


/**
 * OR divider for auth screens.
 */
@Composable
fun AuthDivider(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = "  OR  ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
