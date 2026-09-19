package com.trindade.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R

/**
 * The account screen: who is signed in, the display name, the password, and the way out.
 *
 * Stateless, like the other screens: state in, events out, so the screen can be rendered without a view
 * model, an activity or a server. The two blocks are the two writes this screen has, and each has its
 * own action -- a name and a password are not the same kind of change, and the password change ends the
 * session.
 */
@Composable
fun ProfileScreen(
    state: ProfileViewModel.UiState,
    onBack: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onChangePassword: () -> Unit,
    onSignOut: () -> Unit,
    onBackToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The signed-out panel replaces the whole screen, back action included. The session is already gone
    // at that point, so "Voltar" would land on a surface that can read nothing, and the panel's single
    // action is the only thing left to do.
    if (state.signedOut) {
        SignedOutPanel(
            message = state.message,
            onBackToLogin = onBackToLogin,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.report_back)) }
        Text(text = stringResource(R.string.profile_title), style = MaterialTheme.typography.titleLarge)

        // One line for what went wrong, in the convention the other screens use.
        state.message?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Drawn in the confirmation tone rather than in the error one, which is why it is not the message
        // above: this is the answer to a tap that worked. The report generator's saved-report line makes
        // the same distinction for the same reason.
        if (state.nameSaved) {
            Text(
                text = stringResource(R.string.profile_name_saved),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        val profile = state.profile
        when {
            state.loading -> CircularProgressIndicator()

            // Nothing to show and nothing to edit: a form drawn over a profile that was never read would
            // offer a save that would send a name the operator never chose. The message above says why.
            profile == null -> Unit

            else -> {
                Text(text = stringResource(R.string.profile_account), style = MaterialTheme.typography.titleMedium)

                // The username is the account's handle and the server's to change, so it is shown as text
                // rather than as a disabled field: there is nothing to type into it and a dead control
                // invites a tap.
                ReadOnlyField(label = stringResource(R.string.profile_username), value = profile.username)

                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text(stringResource(R.string.profile_display_name)) },
                    singleLine = true,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.profile_save))
                    }
                }

                // The role as a word, from the string the server sent rather than from a control.
                ReadOnlyField(label = stringResource(R.string.profile_role), value = roleLabel(profile.role))

                Text(
                    text = stringResource(R.string.profile_change_password),
                    style = MaterialTheme.typography.titleMedium,
                )

                OutlinedTextField(
                    value = state.currentPassword,
                    onValueChange = onCurrentPasswordChange,
                    label = { Text(stringResource(R.string.profile_current_password)) },
                    singleLine = true,
                    enabled = !state.changing,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.newPassword,
                    onValueChange = onNewPasswordChange,
                    label = { Text(stringResource(R.string.profile_new_password)) },
                    singleLine = true,
                    enabled = !state.changing,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    // The rule is stated here, where the operator is typing, instead of being discovered as
                    // a 400 after typing the password twice. The number comes from the view model's own
                    // constant, which is the same one its gate uses, so the sentence and the rule cannot
                    // drift apart.
                    supportingText = {
                        Text(stringResource(R.string.profile_password_rule, ProfileViewModel.MIN_PASSWORD_LENGTH))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onChangePassword,
                    enabled = state.canChangePassword,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.changing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.profile_change_password))
                    }
                }

                // No confirmation on this one, and that is the difference between it and the report
                // deletion, which asks twice. Closing a session is undone by signing in again with the
                // credentials the operator already has; deleting a report is undone by nobody.
                TextButton(onClick = onSignOut) { Text(stringResource(R.string.profile_sign_out)) }
            }
        }
    }
}

/**
 * What is shown once the session has ended: why, and the one way forward.
 *
 * The sentence is the session's own account of itself -- a password change says so, because that is
 * something the operator did not ask to be signed out by -- and it falls back to this app's words for a
 * plain sign-out, which needs no explanation.
 */
@Composable
private fun SignedOutPanel(
    message: String?,
    onBackToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message ?: stringResource(R.string.profile_signed_out),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = onBackToLogin,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(stringResource(R.string.profile_back_to_login))
        }
    }
}

/** One read-only fact about the account: its label above, its value below. */
@Composable
private fun ReadOnlyField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The role as a word the operator reads.
 *
 * The contract types `role` as a bare string while the database has exactly two values, which is the
 * same looseness `task_type` had before it became an enum. This does not invent an enum for it, and it
 * does not drop a value it does not recognise either: the two known roles get their own words from this
 * app's resources, and anything else is shown exactly as the server sent it. Printing an unknown role
 * as one of the two would be a claim about the operator's permissions that nothing supports.
 */
@Composable
private fun roleLabel(role: String): String = when (role) {
    ROLE_ADMINISTRATOR -> stringResource(R.string.profile_role_administrator)
    ROLE_WORKER -> stringResource(R.string.profile_role_worker)
    else -> role
}

private const val ROLE_ADMINISTRATOR = "Administrador"
private const val ROLE_WORKER = "Trabalhador"

/**
 * The screen with its view model attached.
 *
 * [onSignedOut] is the activity's, not this screen's: leaving the app is not something a view model
 * does, and it is reached from the panel's action rather than from an effect on the flag. The difference
 * matters here -- an effect would fire the instant the flag rose, and the sentence explaining the
 * sign-out would be on screen for a single frame before the login form replaced it.
 */
@Composable
fun ProfileRoute(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.open() }

    ProfileScreen(
        state = state,
        onBack = onBack,
        onDisplayNameChange = viewModel::onDisplayNameChange,
        onSave = viewModel::save,
        onCurrentPasswordChange = viewModel::onCurrentPasswordChange,
        onNewPasswordChange = viewModel::onNewPasswordChange,
        onChangePassword = viewModel::changePassword,
        onSignOut = viewModel::signOut,
        onBackToLogin = onSignedOut,
    )
}
