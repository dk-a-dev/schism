package ai.schism.split.groups.detail.tabs

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.ui.InitialAvatar
import ai.schism.split.core.ui.UiState
import ai.schism.split.expense.data.Balances
import ai.schism.split.groups.detail.StateSlice
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BalancesTab(
    state: UiState<Balances>,
    currency: String,
    participantNames: Map<String, String>,
    youParticipantId: String?,
) {
    fun label(id: String) = if (id == youParticipantId) "You" else participantNames[id] ?: id

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
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
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
                    }
                }
            }
        }
    }
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
