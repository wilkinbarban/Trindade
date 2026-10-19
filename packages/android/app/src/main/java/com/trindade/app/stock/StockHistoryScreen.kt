package com.trindade.app.stock

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * A past day of counting, as a place of its own.
 *
 * The counter answers what is on the shelf now; this answers what was counted that day, which is a different
 * question asked at a different moment. Only what was counted is listed, because a table of twenty-five rows
 * with twenty-four zeroes hides the one row somebody came for.
 *
 * The role arrives as a parameter and defaults to nothing, which is read-only: if nobody told this screen who
 * is looking, it shows the day and offers no correction. The server enforces the same boundary with its 403;
 * this one exists so an operator is not taught about permissions by being refused after trying.
 */
@Composable
fun StockHistoryScreen(
    role: String? = null,
    viewModel: StockHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val counted = viewModel.counted()
    val visibleErrors = viewModel.visibleErrors()
    val canEdit = viewModel.canEdit()

    LaunchedEffect(role) {
        viewModel.onRoleKnown(role)
        viewModel.load()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Histórico de Contagem", style = MaterialTheme.typography.titleLarge)
        Text(
            if (canEdit) "Dias anteriores, com o que foi contado em cada um." else "Somente leitura para o seu perfil.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = state.date,
            onValueChange = { viewModel.onDateChange(it) },
            label = { Text("Dia da contagem") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.days.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.days.take(6).forEach { day ->
                    Button(
                        onClick = { viewModel.onDateChange(day.date) },
                        enabled = day.date != state.date,
                    ) {
                        Text("${day.date.takeLast(5)} · ${day.units}")
                    }
                }
            }
        }

        if (state.failed) {
            Text("Não foi possível carregar o dia da contagem.", color = MaterialTheme.colorScheme.error)
        }

        if (state.day != null && counted.isEmpty()) {
            Text("Nenhum produto contado neste dia.", style = MaterialTheme.typography.bodyMedium)
        }

        counted.forEach { product ->
            Card {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(product.name, style = MaterialTheme.typography.bodyLarge)
                        Text(product.code, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(product.category, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Caixas: ${product.boxes}   Unidades: ${product.units}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (canEdit) {
                        if (visibleErrors.contains(product.code)) {
                            Text(
                                "Quantidade incorreta de caixas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = state.quantities[product.code] ?: "",
                                onValueChange = { viewModel.onQuantityChange(product.code, it) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .width(110.dp)
                                    .onFocusChanged { focus ->
                                        if (focus.isFocused) viewModel.onFocus(product.code) else viewModel.onBlur(product.code)
                                    },
                            )
                            Button(
                                onClick = { viewModel.addEntry(product) },
                                enabled = !addDisabled(state.quantities[product.code] ?: ""),
                            ) {
                                Text("Adicionar")
                            }
                            Button(onClick = { viewModel.resetProduct(product) }) {
                                Text("Resetar")
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total do dia")
            Text(
                "${counted.sumOf { it.boxes }} caixas · ${counted.sumOf { it.units }} unidades",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        OutlinedTextField(
            value = state.number,
            onValueChange = { viewModel.onNumberChange(it) },
            label = { Text("Coloque o WhatsApp") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val target = viewModel.whatsappLink()
                if (target == null) {
                    viewModel.onNote("Este número não serve para abrir uma conversa.")
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    viewModel.onNote("Abrindo a conversa…")
                }
            },
            enabled = counted.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Enviar por WhatsApp")
        }

        Button(
            onClick = {
                // El destino es el buzon de la planta; el correo del autor no se usa aca.
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(viewModel.mailtoLink())))
                viewModel.onNote("Abrindo o e-mail…")
            },
            enabled = counted.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Enviar por e-mail")
        }

        if (state.note.isNotEmpty()) {
            Text(state.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}
