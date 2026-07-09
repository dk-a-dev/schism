package ai.schism.split.groups.detail.tabs

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.ui.InitialAvatar
import ai.schism.split.core.ui.UiState
import ai.schism.split.expense.data.Balances
import ai.schism.split.expense.data.Reimbursement
import ai.schism.split.groups.detail.StateSlice
import ai.schism.split.groups.detail.settle.launchUpi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BalancesTab(
    state: UiState<Balances>,
    currency: String,
    participantNames: Map<String, String>,
    youParticipantId: String?,
    onSettle: (from: String, to: String, amount: Long) -> Unit,
) {
    fun label(id: String) = if (id == youParticipantId) "You" else participantNames[id] ?: id

    val context = LocalContext.current
    var settleTarget by remember { mutableStateOf<Reimbursement?>(null) }

    StateSlice(state, emptyMessage = "No balances yet — add an expense to get started.") { balances ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionHeader("Net balances") }
            items(balances.perParticipant.entries.toList(), key = { it.key }) { (id, balance) ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        InitialAvatar(name = label(id), key = id, size = 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(label(id), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    balance.total > 0 -> "gets back"
                                    balance.total < 0 -> "owes"
                                    else -> "settled up"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            formatMinor(balance.total, currency),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = netColor(balance.total),
                        )
                    }
                }
            }

            if (balances.reimbursements.isNotEmpty()) {
                item { SectionHeader("Settle up") }
                items(balances.reimbursements, key = { it.from + it.to + it.amount }) { r ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(label(r.from), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "pays",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    label(r.to),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    formatMinor(r.amount, currency),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            FilledTonalButton(
                                onClick = { settleTarget = r },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text("Settle up")
                            }
                        }
                    }
                }
            }
        }
    }

    settleTarget?.let { r ->
        SettleDialog(
            fromLabel = label(r.from),
            toLabel = label(r.to),
            amountText = formatMinor(r.amount, currency),
            onPayUpi = {
                launchUpi(context, r.amount, "Settle up: ${label(r.from)} to ${label(r.to)}")
                settleTarget = null
            },
            onMarkSettled = {
                onSettle(r.from, r.to, r.amount)
                settleTarget = null
            },
            onDismiss = { settleTarget = null },
        )
    }
}

@Composable
private fun SettleDialog(
    fromLabel: String,
    toLabel: String,
    amountText: String,
    onPayUpi: () -> Unit,
    onMarkSettled: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null) },
        title = { Text("Settle up") },
        text = { Text("$fromLabel pays $toLabel $amountText.\n\nPay in a UPI app, or just mark it settled here.") },
        confirmButton = {
            TextButton(onClick = onPayUpi) { Text("Pay in UPI app") }
        },
        dismissButton = {
            TextButton(onClick = onMarkSettled) { Text("Mark as settled") }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun netColor(total: Long): Color =
    if (total < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
