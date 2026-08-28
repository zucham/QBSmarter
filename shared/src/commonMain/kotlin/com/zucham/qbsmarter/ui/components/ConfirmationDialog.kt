package com.zucham.qbsmarter.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Tiny wrapper around AlertDialog so destructive actions look uniform.
 *
 * Both actions are [DialogButton]s, which carry the app-wide dialog
 * button look (thin outline in the button's own content colour):
 *   - Confirm: [DialogButtonEmphasis.DESTRUCTIVE] – theme error red,
 *     bold, red outline. The action that destroys data.
 *   - Cancel: [DialogButtonEmphasis.NEUTRAL] – Material3's documented
 *     role for secondary text. Reads as "not the action you want"
 *     across every theme seed without us picking a hardcoded shade.
 *
 * The "destructive" framing fits everywhere this dialog is used today
 * (Forget cube, Delete solve, Delete profile). If we ever need a non-
 * destructive confirm (e.g. "Reset settings to defaults"), we'd pass a
 * different emphasis in rather than swap the colors dynamically.
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
            DialogButton(
                label = confirmLabel,
                onClick = { onConfirm(); onDismiss() },
                emphasis = DialogButtonEmphasis.DESTRUCTIVE,
            )
        },
        dismissButton = {
            DialogButton(
                label = cancelLabel,
                onClick = onDismiss,
                emphasis = DialogButtonEmphasis.NEUTRAL,
            )
        },
    )
}
