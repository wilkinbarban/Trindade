package com.trindade.app.assai

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
 * The Assaí order, typed standing at a shelf.
 *
 * The screen draws and hands the typing over; every judgement comes from the ViewModel and, behind it,
 * from the pure rule. The warning of a product appears only when its field has been left, which is the
 * same decision the web makes, and the share button stays off while the order is inexact: an order that
 * cannot be served is worse than no order.
 */
@Composable
fun AssaiCalculatorScreen(viewModel: AssaiViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lines = viewModel.lines()
    val visibleErrors = viewModel.visibleErrors()
    val link = viewModel.whatsappLink()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Calculadora de Assaí", style = MaterialTheme.typography.titleLarge)
        Text(
            "Informe as unidades de cada produto: a quantidade tem de fechar caixas inteiras.",
            style = MaterialTheme.typography.bodySmall,
        )

        Card {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(line.product, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${line.unitsPerBox} por caixa" +
                                    if (line.filled && line.exact) "  ·  ${line.boxes} caixas" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (visibleErrors.contains(line.product)) {
                                Text(
                                    "Quantidade incorreta de unidades",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = state.quantities[line.product] ?: "",
                            onValueChange = { viewModel.onQuantityChange(line.product, it) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .width(110.dp)
                                // El foco se escucha en el modificador: dentro del campo solo se
                                // informa lo que se escribe, y quien decide si el cartel va o no es
                                // el ViewModel, que sabe si el campo fue dejado.
                                .onFocusChanged { focus ->
                                    if (focus.isFocused) viewModel.onFocus(line.product) else viewModel.onBlur(line.product)
                                },
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total do pedido")
            Text("${viewModel.totalBoxes()} caixas", style = MaterialTheme.typography.titleMedium)
        }

        OutlinedTextField(
            value = state.freeNumber,
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
                // The link is the canonical endpoint, and the intent only names the address: which
                // application answers it stays the system's decision.
                val target = link
                if (target == null) {
                    viewModel.onNote("Este número não serve para abrir uma conversa.")
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    viewModel.onNote("Abrindo a conversa…")
                }
            },
            enabled = !viewModel.hasError() && link != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Enviar por WhatsApp")
        }

        if (state.note.isNotEmpty()) {
            Text(state.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}
