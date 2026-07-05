package ai.schism.split.groups.detail.tabs

import ai.schism.split.core.ui.UiState
import ai.schism.split.expense.data.Activity
import ai.schism.split.groups.detail.StateSlice
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun ActivityTab(
    state: UiState<List<Activity>>,
    participantNames: Map<String, String>,
) {
    StateSlice(state, emptyMessage = "No activity yet.") { activities ->
        LazyColumn(Modifier.fillMaxSize()) {
            items(activities, key = { it.id }) { activity ->
                val who = activity.participantId?.let { participantNames[it] ?: it }
                val (verb, icon) = action(activity.activityType)
                val detail = activity.data.takeIf { it.isNotBlank() }
                ListItem(
                    leadingContent = { ActionAvatar(icon) },
                    headlineContent = { Text(if (detail != null) "$verb “$detail”" else verb) },
                    supportingContent = who?.let { { Text("by $it") } },
                    trailingContent = activity.time.let { t ->
                        prettyDate(t)?.let { { Text(it, style = MaterialTheme.typography.labelMedium) } }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
}

@Composable
private fun ActionAvatar(icon: ImageVector) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp).padding(0.dp),
            )
        }
    }
}

private fun action(type: String): Pair<String, ImageVector> = when (type) {
    "CREATE_EXPENSE", "EXPENSE_CREATED" -> "Added" to Icons.Filled.Add
    "UPDATE_EXPENSE", "EXPENSE_UPDATED" -> "Updated" to Icons.Filled.Edit
    "DELETE_EXPENSE", "EXPENSE_DELETED" -> "Removed" to Icons.Filled.Delete
    "UPDATE_GROUP", "GROUP_UPDATED" -> "Updated the group" to Icons.Filled.Group
    else -> type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } to
        Icons.AutoMirrored.Filled.ReceiptLong
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2026-07-05T00:00:00Z" -> "Jul 5". Returns null if the string isn't a recognizable date. */
private fun prettyDate(time: String): String? {
    val date = time.take(10) // yyyy-MM-dd
    val parts = date.split("-")
    if (parts.size != 3) return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    if (month !in 1..12) return null
    return "${MONTHS[month - 1]} $day"
}
