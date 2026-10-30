package com.trindade.app.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.AdminSecondaryButton

/**
 * The ERP integration, in the panel and not behind a tap.
 *
 * The section is a fixed card: it is the one place where the plant's address and the operator's own credentials
 * live, and hiding it behind a collapse made the one screen that explains an integration failure the hardest one
 * to open. There is no create action here -- the integration is one row, not a list.
 *
 * The address is shown to everyone and editable only by the owner; the credentials are editable by whoever is
 * holding the phone, because they are theirs.
 */
@Composable
fun AdminIntegrationCard(
    modifier: Modifier = Modifier,
    viewModel: IntegrationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.admin_integration_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = stringResource(R.string.admin_integration_ws_url),
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedTextField(
                value = state.wsUrl,
                onValueChange = viewModel::setWsUrl,
                singleLine = true,
                enabled = state.permissions.canEditWsUrl,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!state.permissions.canEditWsUrl) {
                Text(
                    text = stringResource(R.string.admin_integration_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::setUsername,
                label = { Text(stringResource(R.string.admin_integration_username)) },
                singleLine = true,
                enabled = state.permissions.canEditCredentials,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = { Text(stringResource(R.string.admin_integration_password)) },
                singleLine = true,
                enabled = state.permissions.canEditCredentials,
                visualTransformation = if (passwordVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Text(
                        text = if (passwordVisible) "•••" else "👁",
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable { passwordVisible = !passwordVisible },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.userCode,
                onValueChange = viewModel::setUserCode,
                label = { Text(stringResource(R.string.admin_integration_user_code)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AdminSecondaryButton(
                    text = stringResource(R.string.admin_integration_test),
                    onClick = viewModel::test,
                    enabled = !state.isTesting && state.ready,
                )
                AdminPrimaryButton(
                    text = stringResource(R.string.admin_integration_save),
                    onClick = viewModel::save,
                    enabled = state.ready,
                )
            }
        }
    }
}
