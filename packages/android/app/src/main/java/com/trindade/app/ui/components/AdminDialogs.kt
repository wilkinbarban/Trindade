package com.trindade.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.trindade.app.R
import com.trindade.app.ui.UiText
import com.trindade.app.ui.resolve

data class AdminOutcome(val isSuccess: Boolean, val message: UiText)

data class AdminConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val isDestructive: Boolean = false,
    val onConfirm: () -> Unit,
)

/**
 * The one confirmation every administrative mutation passes through.
 *
 * The dialog does not dismiss itself: closing belongs to the caller, which has to clear its own pending
 * confirmation anyway. A dialog that closed on its own would leave the caller's state pointing at a
 * confirmation that is no longer on screen, and the next unrelated recomposition would show it again.
 *
 * `isDestructive` decides the confirm button, and only that: an earlier version also compared the label
 * against the word "Delete", which meant a caller that wrote "Excluir usuário" got a primary-filled button
 * on a delete. A red button on a destructive action is a promise the label cannot keep.
 */
@Composable
fun AdminConfirmDialog(
    confirmation: AdminConfirmation?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (confirmation == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(confirmation.title) },
        text = { Text(confirmation.message) },
        confirmButton = {
            if (confirmation.isDestructive) {
                AdminDestructiveButton(
                    onClick = confirmation.onConfirm,
                    text = confirmation.confirmLabel,
                )
            } else {
                AdminPrimaryButton(
                    onClick = confirmation.onConfirm,
                    text = confirmation.confirmLabel,
                )
            }
        },
        dismissButton = {
            AdminSecondaryButton(
                onClick = onDismiss,
                text = stringResource(R.string.admin_action_cancel),
            )
        },
    )
}

@Composable
fun AdminResultDialog(
    outcome: AdminOutcome?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (outcome == null) return

    val symbol = if (outcome.isSuccess) "✅" else "❌"
    val titleRes = if (outcome.isSuccess) R.string.admin_dialog_success else R.string.admin_dialog_error
    val body = if (outcome.isSuccess) {
        outcome.message.resolve()
    } else {
        stringResource(R.string.admin_dialog_failed, outcome.message.resolve())
    }

    AlertDialog(
        onDismissRequest = onClose,
        modifier = modifier,
        title = { Text("$symbol ${stringResource(titleRes)}") },
        text = { Text(body) },
        confirmButton = {
            AdminPrimaryButton(
                onClick = onClose,
                text = stringResource(R.string.admin_dialog_close),
            )
        },
    )
}
