package com.trindade.app.share

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.ui.BrazilianPhoneVisualTransformation
import com.trindade.app.ui.components.HelpSection
import com.trindade.app.ui.components.ModuleHelpButton
import com.trindade.app.ui.components.ModuleHelpDialog
import com.trindade.app.ui.filterPhoneDigits

@Composable
fun ShareAppScreen(
    viewModel: ShareAppViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var usersMenuExpanded by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    val noteCopied = stringResource(R.string.share_note_copied)
    val noteInvalidPhone = stringResource(R.string.share_note_invalid_phone)
    val noteOpeningWhatsapp = stringResource(R.string.share_note_opening_whatsapp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModuleHelpButton(onClick = { showHelpDialog = true })
        }
        Text(
            stringResource(R.string.share_subtitle),
            style = MaterialTheme.typography.bodySmall,
        )

        // Platform selection card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.share_platform_label), style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = state.platform == SharePlatform.ANDROID,
                        onClick = { viewModel.onPlatformSelect(SharePlatform.ANDROID) },
                        label = { Text(stringResource(R.string.share_platform_android)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                    )
                    FilterChip(
                        selected = state.platform == SharePlatform.IOS,
                        onClick = { viewModel.onPlatformSelect(SharePlatform.IOS) },
                        label = { Text(stringResource(R.string.share_platform_iphone)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                    )
                    FilterChip(
                        selected = state.platform == SharePlatform.BOTH,
                        onClick = { viewModel.onPlatformSelect(SharePlatform.BOTH) },
                        label = { Text(stringResource(R.string.share_platform_both)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                    )
                }
            }
        }

        // Credentials card (Administrator only)
        if (state.isAdmin) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.share_credentials_title), style = MaterialTheme.typography.titleSmall)
                        Switch(
                            checked = state.includeCredentials,
                            onCheckedChange = { viewModel.onIncludeCredentialsToggle(it) },
                        )
                    }

                    if (state.includeCredentials) {
                        // User selection
                        Text(stringResource(R.string.share_select_user), style = MaterialTheme.typography.bodySmall)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { usersMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                            ) {
                                val label = if (state.displayName.isNotBlank()) {
                                    state.displayName
                                } else {
                                    stringResource(R.string.share_select_user_placeholder)
                                }
                                Text(label)
                            }
                            DropdownMenu(
                                expanded = usersMenuExpanded,
                                onDismissRequest = { usersMenuExpanded = false },
                            ) {
                                state.users.forEach { user ->
                                    DropdownMenuItem(
                                        text = { Text("${user.displayName} (${user.username})") },
                                        onClick = {
                                            viewModel.onUserSelect(user.id)
                                            usersMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        // Read-only username
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.profile_username)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Editable password with initial value 123456789
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = { viewModel.onPasswordChange(it) },
                            label = { Text(stringResource(R.string.share_initial_password)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // Destination phone field with mask
        OutlinedTextField(
            value = state.phone,
            onValueChange = { viewModel.onPhoneChange(filterPhoneDigits(it)) },
            visualTransformation = BrazilianPhoneVisualTransformation(),
            label = { Text(stringResource(R.string.share_target_whatsapp)) },
            placeholder = { Text(stringResource(R.string.whatsapp_phone_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Preview of the message
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.share_preview_label), style = MaterialTheme.typography.titleSmall)
                Text(viewModel.message(), style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider()

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(viewModel.message()))
                    viewModel.onNote(noteCopied)
                },
                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.export_copy),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Button(
                onClick = {
                    val link = viewModel.whatsappLink()
                    if (link == null) {
                        viewModel.onNote(noteInvalidPhone)
                    } else {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        viewModel.onNote(noteOpeningWhatsapp)
                    }
                },
                enabled = viewModel.whatsappLink() != null,
                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.share_send_whatsapp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (state.note.isNotEmpty()) {
            Text(state.note, style = MaterialTheme.typography.bodySmall)
        }

        if (showHelpDialog) {
            ModuleHelpDialog(
                title = stringResource(R.string.help_share_title),
                description = stringResource(R.string.help_share_desc),
                sections = listOf(
                    HelpSection(
                        title = stringResource(R.string.help_share_platforms_title),
                        description = stringResource(R.string.help_share_platforms_desc),
                    ),
                    HelpSection(
                        title = stringResource(R.string.help_share_whatsapp_title),
                        description = stringResource(R.string.help_share_whatsapp_desc),
                    ),
                ),
                onDismissRequest = { showHelpDialog = false },
            )
        }
    }
}
