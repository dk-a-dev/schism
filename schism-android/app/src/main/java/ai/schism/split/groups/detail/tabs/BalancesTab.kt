package ai.schism.split.groups.detail.tabs

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.ui.UiState
import ai.schism.split.expense.data.Balances
import ai.schism.split.groups.detail.StateSlice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BalancesTab(
    state: UiState<Balances>,
    currency: String,
    participantNames: Map<String, String>,
    youParticipantId: String?,
) {
    fun name(id: String) = participantNames[id] ?: id
    fun youLabel(id: String) = if (id == youParticipantId) "You" else name(id)

    StateSlice(state, emptyMessage = "No balances yet — add an expense to get started.") { balances ->
        LazyColumn(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
            item {
                SectionHeader("Net balances")
            }
            items(balances.perParticipant.entries.toList(), key = { it.key }) { (id, balance) ->
                val amount = formatMinor(balance.total, currency)
                val subtitle = when {
                    balance.total > 0 -> "is owed"
                    balance.total < 0 -> "owes"
                    else -> "settled up"
                }
                ListItem(
                    headlineContent = { Text(youLabel(id)) },
                    supportingContent = { Text(subtitle) },
                    trailingContent = {
                        Text(
                            amount,
                            fontWeight = FontWeight.SemiBold,
                            color = netColor(balance.total),
                        )
                    },
                )
            }

            if (balances.reimbursements.isNotEmpty()) {
                item { HorizontalDivider() }
                item { SectionHeader("Settle up") }
                items(balances.reimbursements, key = { it.from + it.to + it.amount }) { r ->
                    ListItem(
                        headlineContent = { Text("${youLabel(r.from)} → ${youLabel(r.to)}") },
                        trailingContent = { Text(formatMinor(r.amount, currency)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun netColor(total: Long) =
    if (total < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

// keep Row import used for potential future layout tweaks without breaking compilation
private val unusedRow: @Composable (Row: Any) -> Unit = {}
