package com.zucham.qbsmarter.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * Tiny wrapper around AlertDialog so destructive actions look uniform.
 *
 * Color contract:
 *   - Cancel: `colorScheme.onSurfaceVariant` – Material3's documented role
 *     for "secondary text". Reads as "neutral, not the action you want"
 *     across every theme seed without us picking a hardcoded shade.
 *   - Confirm: theme `error` red, bold – the action that destroys data.
 *
 * The "destructive" framing fits everywhere this dialog is used today
 * (Forget cube, Delete solve, Delete profile). If we ever need a non-
 * destructive confirm (e.g. "Reset settings to defaults"), we'd add a
 * separate flag here rather than swap the colors dynamically.
 */
@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(
                    text = confirmLabel,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = cancelLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
