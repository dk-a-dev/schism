package ai.schism.split.groups.detail.tabs

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.ui.UiState
import ai.schism.split.expense.data.Expense
import ai.schism.split.groups.detail.StateSlice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ExpensesTab(
    state: UiState<List<Expense>>,
    currency: String,
    participantNames: Map<String, String>,
    onEditExpense: (expenseId: String) -> Unit,
) {
    StateSlice(state, emptyMessage = "No expenses yet. Add one with +.") { expenses ->
        LazyColumn(Modifier.fillMaxSize()) {
            items(expenses, key = { it.id }) { expense ->
                val payer = participantNames[expense.paidById] ?: "someone"
                ListItem(
                    headlineContent = { Text(expense.title) },
                    supportingContent = { Text("Paid by $payer") },
                    trailingContent = { Text(formatMinor(expense.amount, currency)) },
                    modifier = Modifier.clickable { onEditExpense(expense.id) },
                )
            }
        }
    }
}
