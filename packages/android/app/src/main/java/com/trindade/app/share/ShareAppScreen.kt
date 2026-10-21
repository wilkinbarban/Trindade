package com.trindade.app.share

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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

@Composable
fun ShareAppScreen(
    viewModel: ShareAppViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var usersMenuExpanded by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Compartilhar Acesso", style = MaterialTheme.typography.titleLarge)
            ModuleHelpButton(onClick = { showHelpDialog = true })
        }
        Text(
            "Compartilhe o link oficial e acessos do Trindade Massas via WhatsApp.",
            style = MaterialTheme.typography.bodySmall,
        )

        // Platform selection card
        Card {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Plataforma", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = state.platform == SharePlatform.ANDROID,
                        onClick = { viewModel.onPlatformSelect(SharePlatform.ANDROID) },
                        label = { Text("Android") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = state.platform == SharePlatform.IOS,
                        onClick = { viewModel.onPlatformSelect(SharePlatform.IOS) },
                        label = { Text("iPhone") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = state.platform == SharePlatform.BOTH,
                        onClick = { viewModel.onPlatformSelect(SharePlatform.BOTH) },
                        label = { Text("Ambos") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Credentials card (Administrator only)
        if (state.isAdmin) {
            Card {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Credenciais de Acesso", style = MaterialTheme.typography.titleSmall)
                        Switch(
                            checked = state.includeCredentials,
                            onCheckedChange = { viewModel.onIncludeCredentialsToggle(it) },
                        )
                    }

                    if (state.includeCredentials) {
                        // User selection
                        Text("Selecionar usuário", style = MaterialTheme.typography.bodySmall)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { usersMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val label = if (state.displayName.isNotBlank()) {
                                    state.displayName
                                } else {
                                    "Selecione um usuário"
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
                            label = { Text("Usuário") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Editable password with initial value 123456789
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = { viewModel.onPasswordChange(it) },
                            label = { Text("Senha inicial") },
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
            onValueChange = { viewModel.onPhoneChange(it) },
            visualTransformation = BrazilianPhoneVisualTransformation(),
            label = { Text("WhatsApp de destino") },
            placeholder = { Text("(11) 9XXXX-XXXX") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Preview of the message
        Card {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pré-visualização da Mensagem", style = MaterialTheme.typography.titleSmall)
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
                    viewModel.onNote("Texto copiado para a área de transferência!")
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Copiar texto")
            }

            Button(
                onClick = {
                    val link = viewModel.whatsappLink()
                    if (link == null) {
                        viewModel.onNote("Informe um número de WhatsApp válido.")
                    } else {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        viewModel.onNote("Abrindo WhatsApp…")
                    }
                },
                enabled = viewModel.whatsappLink() != null,
                modifier = Modifier.weight(1f),
            ) {
                Text("Enviar WhatsApp")
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
                        title = "Links por Plataforma",
                        description = "Gere mensagens personalizadas para usuários de Android, iPhone ou acesso Web com ou sem credenciais.",
                    ),
                    HelpSection(
                        title = "Envio WhatsApp",
                        description = "Dispare a mensagem diretamente para o contato informado com formatação amigável e links seguros.",
                    ),
                ),
                onDismissRequest = { showHelpDialog = false },
            )
        }
    }
}
