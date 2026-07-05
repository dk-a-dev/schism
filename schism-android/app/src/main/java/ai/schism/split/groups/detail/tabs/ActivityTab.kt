package ai.schism.split.groups.detail.tabs

import ai.schism.split.core.ui.UiState
import ai.schism.split.expense.data.Activity
import ai.schism.split.groups.detail.StateSlice
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ActivityTab(
    state: UiState<List<Activity>>,
    participantNames: Map<String, String>,
) {
    StateSlice(state, emptyMessage = "No activity yet.") { activities ->
        LazyColumn(Modifier.fillMaxSize()) {
            items(activities, key = { it.id }) { activity ->
                val who = activity.participantId?.let { participantNames[it] ?: it }
                ListItem(
                    headlineContent = { Text(describe(activity.activityType, who)) },
                    supportingContent = activity.data.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                    trailingContent = activity.time.takeIf { it.isNotBlank() }?.let { { Text(it.take(10)) } },
                )
            }
        }
    }
}

private fun describe(type: String, who: String?): String {
    val action = when (type) {
        "CREATE_EXPENSE", "EXPENSE_CREATED" -> "Added an expense"
        "UPDATE_EXPENSE", "EXPENSE_UPDATED" -> "Updated an expense"
        "DELETE_EXPENSE", "EXPENSE_DELETED" -> "Deleted an expense"
        "UPDATE_GROUP", "GROUP_UPDATED" -> "Updated the group"
        else -> type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    return if (who != null) "$who · $action" else action
}
