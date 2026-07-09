@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.schism.split.groups.detail

import ai.schism.split.core.money.formatMinor
import ai.schism.split.expense.data.Expense
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Read-only breakdown of any expense — including ones the viewer can't edit: who paid, how it was
 * split (exact per-person amounts for BY_AMOUNT; shares otherwise), notes, date. The creator also
 * gets an Edit button (edit screen owns delete).
 */
@Composable
fun ExpenseDetailSheet(
    expense: Expense,
    participantNames: Map<String, String>,
    currency: String,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(expense.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                formatMinor(expense.amount, currency) + "  ·  " + expense.expenseDate.take(10),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Paid by " + (participantNames[expense.paidById] ?: "someone"),
                style = MaterialTheme.typography.bodyLarge,
            )
            HorizontalDivider()
            Text("Split", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            expense.paidFor.forEach { pf ->
                Row(Modifier.fillMaxWidth()) {
                    Text(participantNames[pf.participantId] ?: pf.participantId, Modifier.weight(1f))
                    Text(
                        when (expense.splitMode) {
                            "BY_AMOUNT" -> formatMinor(pf.shares, currency)
                            "BY_PERCENTAGE" -> "${pf.shares / 100.0}%"
                            else -> "${pf.shares}×"
                        },
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (expense.notes.isNotBlank()) {
                HorizontalDivider()
                val isItemBreakdown = expense.splitMode == "BY_AMOUNT" &&
                    expense.notes.startsWith("Split by items")
                Text(
                    if (isItemBreakdown) "Split by items" else "Notes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(expense.notes, style = MaterialTheme.typography.bodyMedium)
            }
            if (canEdit) {
                Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("Edit expense")
                }
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}
