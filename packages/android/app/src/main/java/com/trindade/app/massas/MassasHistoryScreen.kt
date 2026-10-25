package com.trindade.app.massas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trindade.app.R
import com.trindade.app.ui.resolve
import com.trindade.app.ui.components.HelpSection
import com.trindade.app.ui.components.ModuleHelpButton
import com.trindade.app.ui.components.ModuleHelpDialog
import com.trindade.app.ui.components.NavigationActionButton

@Composable
fun MassasHistoryScreen(
    viewModel: MassasHistoryViewModel,
    onBack: () -> Unit,
    onSelectDate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    val helpSections = listOf(
        HelpSection(
            title = "Consulta por Data",
            description = "Toque em qualquer dia da lista para visualizar a discriminação completa de caixas, unidades e operadores.",
        ),
        HelpSection(
            title = "Totais Consolidados",
            description = "Cada cartão exibe a soma de caixas e unidades produzidas na fábrica naquele dia.",
        ),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Encabezado: "Voltar" con el título a la izquierda y la ayuda en la esquina de cierre. No hay
        // botón "Atualizar": este histórico se relee solo al entrar -- y cuando cambia el día elegido --
        // así que un botón para forzar la misma lectura era un segundo camino al mismo sitio, y en una
        // pantalla que ya dice que está cargando, uno que además puede fallar por su cuenta.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NavigationActionButton(onClick = onBack, text = stringResource(R.string.report_back))
                Column {
                    Text(
                        text = stringResource(R.string.massas_history_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = stringResource(R.string.massas_history_days_count, state.days.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ModuleHelpButton(onClick = { viewModel.toggleHelpDialog(true) })
        }

        if (state.errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "⚠ " + (state.errorMessage?.resolve() ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { viewModel.loadHistory() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.report_retry),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.days.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nenhum histórico de massas encontrado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.days, key = { it.date }) { day ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDate(day.date) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "📅 " + brazilianDate(day.date),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    text = "${day.totalBoxes} caixas · ${day.totalUnits} unidades",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "${day.productsCount} produtos lançados · ${day.operatorsCount} operadores",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "Abrir ›",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.showHelpDialog) {
        ModuleHelpDialog(
            title = stringResource(R.string.help_massas_history_title),
            description = stringResource(R.string.help_massas_history_desc),
            sections = helpSections,
            onDismissRequest = { viewModel.toggleHelpDialog(false) },
        )
    }
}
