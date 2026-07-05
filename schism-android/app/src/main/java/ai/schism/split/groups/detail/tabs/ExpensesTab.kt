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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
    val isYou = expense.paidById == youParticipantId
    val payer = if (isYou) "You" else participantNames[expense.paidById] ?: "someone"
    Card(
        onClick = { onEditExpense(expense.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                    "Paid by $payer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatMinor(expense.amount, currency),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
