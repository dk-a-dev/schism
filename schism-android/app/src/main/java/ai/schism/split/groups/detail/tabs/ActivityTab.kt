package ai.schism.split.groups.detail.tabs

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.ui.SchismFilterChip
import ai.schism.split.core.ui.UiState
import ai.schism.split.expense.data.Activity
import ai.schism.split.groups.detail.StateSlice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

private data class ActionStyle(val verb: String, val icon: ImageVector, val tone: Tone)
private enum class Tone { Positive, Neutral, Warn, Negative }

private enum class ActivityFilter(val label: String, val matches: (String) -> Boolean) {
    All("All", { true }),
    Added("Added", {
        it == "CREATE_EXPENSE" || it == "EXPENSE_CREATED" || it == "GROUP_CREATED" ||
            it == "MEMBER_ADDED" || it == "CLAIM_SESSION_CREATED"
    }),
    Updated("Updated", {
        it == "UPDATE_EXPENSE" || it == "EXPENSE_UPDATED" || it == "GROUP_UPDATED" || it == "GROUP_RENAMED" ||
            it == "CLAIM_SUBMITTED" || it == "CLAIM_ITEMS_EDITED" || it == "CLAIM_SESSION_FINALIZED"
    }),
    Removed("Removed", {
        it == "DELETE_EXPENSE" || it == "EXPENSE_DELETED" || it == "MEMBER_REMOVED" || it == "CLAIM_SESSION_CANCELLED"
    }),
}

@Composable
fun ActivityTab(
    state: UiState<List<Activity>>,
    participantNames: Map<String, String>,
    currency: String,
) {
    val today = remember { LocalDate.now() }
    var filter by remember { mutableStateOf(ActivityFilter.All) }
    StateSlice(state, emptyMessage = "No activity yet.") { activities ->
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActivityFilter.entries.forEach { f ->
                    SchismFilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) },
                    )
                }
            }
            val shown = activities.filter { filter.matches(it.activityType) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { activity ->
                val who = activity.participantId?.let { participantNames[it] ?: it }
                val style = action(activity.activityType)
                val detail = activity.data.takeIf { it.isNotBlank() }
                val headline = buildString {
                    if (who != null) append("$who ")
                    append(style.verb)
                    if (detail != null) append(" “$detail”")
                }
                val amount = activity.amountMinor?.let { formatMinor(it, currency) }
                ListItem(
                    leadingContent = { ActionAvatar(style.icon, style.tone) },
                    headlineContent = { Text(headline, fontWeight = FontWeight.Medium) },
                    supportingContent = prettyWhen(activity.time, today)?.let {
                        { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    },
                    trailingContent = amount?.let {
                        {
                            Text(
                                it,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (style.tone == Tone.Negative) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
                }
            }
        }
    }
}

@Composable
private fun ActionAvatar(icon: ImageVector, tone: Tone) {
    val (bg, fg) = toneColors(tone)
    Surface(shape = CircleShape, color = bg, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp).padding(0.dp))
        }
    }
}

@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> = with(MaterialTheme.colorScheme) {
    when (tone) {
        Tone.Positive -> primaryContainer to onPrimaryContainer
        Tone.Neutral -> secondaryContainer to onSecondaryContainer
        Tone.Warn -> tertiaryContainer to onTertiaryContainer
        Tone.Negative -> errorContainer to onErrorContainer
    }
}

private fun action(type: String): ActionStyle = when (type) {
    "CREATE_EXPENSE", "EXPENSE_CREATED" -> ActionStyle("added", Icons.Filled.Add, Tone.Positive)
    "UPDATE_EXPENSE", "EXPENSE_UPDATED" -> ActionStyle("updated", Icons.Filled.Edit, Tone.Warn)
    "DELETE_EXPENSE", "EXPENSE_DELETED" -> ActionStyle("removed", Icons.Filled.Delete, Tone.Negative)
    "SETTLE", "REIMBURSEMENT" -> ActionStyle("settled up", Icons.Filled.SwapHoriz, Tone.Positive)
    "UPDATE_GROUP", "GROUP_UPDATED" -> ActionStyle("updated the group", Icons.Filled.Group, Tone.Neutral)
    "GROUP_CREATED" -> ActionStyle("created the group", Icons.Filled.Group, Tone.Positive)
    "GROUP_RENAMED" -> ActionStyle("renamed the group", Icons.Filled.Edit, Tone.Neutral)
    "MEMBER_ADDED" -> ActionStyle("added", Icons.Filled.PersonAdd, Tone.Positive)
    "MEMBER_REMOVED" -> ActionStyle("removed", Icons.Filled.PersonRemove, Tone.Warn)
    "CLAIM_SESSION_CREATED" -> ActionStyle("started a claim", Icons.AutoMirrored.Filled.ReceiptLong, Tone.Positive)
    "CLAIM_SUBMITTED" -> ActionStyle("claimed items", Icons.Filled.Checklist, Tone.Neutral)
    "CLAIM_SESSION_FINALIZED" -> ActionStyle("locked the split", Icons.Filled.Lock, Tone.Positive)
    "CLAIM_SESSION_CANCELLED" -> ActionStyle("cancelled a claim", Icons.Filled.Cancel, Tone.Warn)
    "CLAIM_ITEMS_EDITED" -> ActionStyle("edited the bill", Icons.Filled.Edit, Tone.Neutral)
    else -> ActionStyle(
        type.lowercase().replace('_', ' '),
        Icons.AutoMirrored.Filled.ReceiptLong,
        Tone.Neutral,
    )
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Relative day label: "Today" / "Yesterday" / "Jul 5", from an ISO timestamp. */
private fun prettyWhen(time: String, today: LocalDate): String? {
    val parts = time.take(10).split("-")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    if (month !in 1..12) return null
    val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
    return when (today.toEpochDay() - d.toEpochDay()) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> "${MONTHS[month - 1]} $day"
    }
}
