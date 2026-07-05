@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.schism.split.groups.detail.tabs

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.ui.InitialAvatar
import ai.schism.split.core.ui.UiState
import ai.schism.split.expense.data.Expense
import ai.schism.split.groups.detail.StateSlice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ExpensesTab(
    state: UiState<List<Expense>>,
    currency: String,
    participantNames: Map<String, String>,
    youParticipantId: String?,
    onEditExpense: (expenseId: String) -> Unit,
) {
    StateSlice(state, emptyMessage = "No expenses yet. Add one with the button below.") { expenses ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(expenses, key = { it.id }) { expense ->
                ExpenseCard(expense, currency, participantNames, youParticipantId, onEditExpense)
            }
        }
    }
}

@Composable
private fun ExpenseCard(
    expense: Expense,
    currency: String,
    participantNames: Map<String, String>,
    youParticipantId: String?,
    onEditExpense: (String) -> Unit,
) {
    fun label(id: String) = if (id == youParticipantId) "You" else participantNames[id] ?: "someone"
    val payer = label(expense.paidById)
    // You can only edit expenses you added. Legacy rows (no creator recorded) stay editable.
    val editable = expense.addedBy.isBlank() || expense.addedBy == youParticipantId
    val subtitle = buildString {
        append("Paid by $payer")
        if (expense.addedBy.isNotBlank() && expense.addedBy != expense.paidById) {
            append(" · added by ${label(expense.addedBy)}")
        }
    }
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            InitialAvatar(name = payer, key = expense.paidById, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    expense.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatMinor(expense.amount, currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (!editable) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "Only the person who added this can edit it",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
    if (editable) {
        Card(onClick = { onEditExpense(expense.id) }, colors = cardColors, modifier = Modifier.fillMaxWidth()) { body() }
    } else {
        Card(colors = cardColors, modifier = Modifier.fillMaxWidth()) { body() }
    }
}
