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
 * The counter, held in one hand at a shelf.
 *
 * The screen draws and hands the typing over; every judgement comes from the ViewModel and, behind it, from
 * the pure rule and the server. Nothing is computed here, not even a total: the numbers shown are the ones
 * the server answered with, which is what keeps two devices from disagreeing about the same shelf.
 *
 * Below a certain width the products are cards rather than columns, because this is used standing up with a
 * phone and a table of five columns is a table nobody can read there.
 */
@Composable
fun StockCalculatorScreen(
    role: String? = null,
    viewModel: StockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val groups = viewModel.groups()
    val visibleErrors = viewModel.visibleErrors()
    val canEdit = viewModel.canEdit()
    // La pestaña que se está mostrando, y sus productos: la primera si todavía no se eligió ninguna,
    // porque el servidor ya dijo cuál viene primero en el catálogo.
    val shown = state.tab ?: groups.keys.firstOrNull()
    val active = shown?.let { groups[it] } ?: groups.values.firstOrNull()

    LaunchedEffect(role) { viewModel.onRoleKnown(role) }
    LaunchedEffect(Unit) { viewModel.load() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Calculador de Estoque", style = MaterialTheme.typography.titleLarge)
        Text(
            if (canEdit) "Conte as caixas: o sistema acumula e calcula as unidades."
            else "Modo de leitura: apenas administradores podem atualizar o estoque.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = state.date,
            onValueChange = { viewModel.onDateChange(it) },
            label = { Text("Dia da contagem") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.failed) {
            Text("Não foi possível carregar o dia da contagem.", color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.keys.forEach { category ->
                Button(
                    onClick = { viewModel.onTabChange(category) },
                    enabled = category != shown,
                ) {
                    val label = if (category == "Massas") "Massas" else "Terceirizados"
                    Text("$label (${groups[category]?.size ?: 0})")
                }
            }
        }

        active?.forEach { product ->
            Card {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(product.name, style = MaterialTheme.typography.bodyLarge)
                        Text(product.code, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Caixas: ${product.boxes}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Unidades: ${product.units}  (${product.unitsPerBox}/cx)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
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
                            enabled = canEdit,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .width(110.dp)
                                // El foco se escucha en el modificador, que es donde Compose lo ofrece.
                                .onFocusChanged { focus ->
                                    if (focus.isFocused) viewModel.onFocus(product.code) else viewModel.onBlur(product.code)
                                },
                        )
                        Button(
                            onClick = { viewModel.addEntry(product) },
                            enabled = canEdit && !addDisabled(state.quantities[product.code] ?: ""),
                        ) {
                            Text("Adicionar")
                        }
                        Button(
                            onClick = { viewModel.resetProduct(product) },
                            enabled = canEdit,
                        ) {
                            Text("Resetar")
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total do dia")
            Text(
                "${state.day?.totalBoxes ?: 0} caixas · ${state.day?.totalUnits ?: 0} unidades",
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

        Card {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("Mensagem", style = MaterialTheme.typography.bodySmall)
                Text(viewModel.message(), style = MaterialTheme.typography.bodySmall)
            }
        }

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
            enabled = viewModel.hasSomethingToSend() && state.number.isNotBlank(),
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
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Enviar por e-mail")
        }

        if (state.note.isNotEmpty()) {
            Text(state.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}
