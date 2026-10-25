package com.trindade.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R

/** Worker enrollment requests approval; a successful request never signs the user in. */
@Composable
fun RegisterScreen(
    state: RegisterViewModel.UiState,
    onUsernameChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBackToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.register_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.register_worker_role_notice))
            Spacer(Modifier.height(16.dp))
            if (state.successMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // The glyph is its own node, not a prefix glued to the title: a screen reader
                        // otherwise reads "check mark" into the sentence, and the title stops being the
                        // exact string the app declares.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = stringResource(R.string.register_success_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Text(
                            text = state.successMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onBackToLogin,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.register_back_to_login),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                OutlinedTextField(
                    state.username,
                    onUsernameChange,
                    label = { Text(stringResource(R.string.register_username)) },
                    singleLine = true,
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    state.displayName,
                    onDisplayNameChange,
                    label = { Text(stringResource(R.string.register_display_name)) },
                    singleLine = true,
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    state.password,
                    onPasswordChange,
                    label = { Text(stringResource(R.string.register_password)) },
                    supportingText = { Text(stringResource(R.string.register_password_helper)) },
                    singleLine = true,
                    enabled = !state.submitting,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    state.confirmPassword,
                    onConfirmPasswordChange,
                    label = { Text(stringResource(R.string.register_confirm_password)) },
                    singleLine = true,
                    enabled = !state.submitting,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.message?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "⚠",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = when (message) {
                                    is RegisterMessage.Validation -> message.text
                                    is RegisterMessage.FromServer -> message.text
                                    is RegisterMessage.RateLimited -> if (message.retryAfterSeconds != null)
                                        stringResource(R.string.register_rate_limit_wait, message.retryAfterSeconds)
                                    else message.text
                                    RegisterMessage.Timeout -> stringResource(R.string.login_timeout)
                                    RegisterMessage.Tls -> stringResource(R.string.login_tls)
                                    RegisterMessage.UnreadableBody -> stringResource(R.string.login_unreadable_body)
                                    RegisterMessage.Unreachable -> stringResource(R.string.login_unreachable)
                                    RegisterMessage.Unknown -> stringResource(R.string.register_unknown)
                                },
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                ) {
                    if (state.submitting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text(
                                text = stringResource(R.string.register_submit),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.register_submit),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onBackToLogin,
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.register_back_to_login),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
fun RegisterRoute(onBackToLogin: () -> Unit, viewModel: RegisterViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    RegisterScreen(state, viewModel::onUsernameChange, viewModel::onDisplayNameChange,
        viewModel::onPasswordChange, viewModel::onConfirmPasswordChange, viewModel::submit, onBackToLogin)
}
