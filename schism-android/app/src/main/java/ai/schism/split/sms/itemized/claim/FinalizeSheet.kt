@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.schism.split.sms.itemized.claim

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.net.ClaimSessionDto
import ai.schism.split.core.net.ResolutionDto
import ai.schism.split.core.ui.SchismFilterChip
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.core.ui.SchismSecondaryButton
import ai.schism.split.groups.data.Participant
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shown to the creator when finalizing a session that still has items nobody claimed: each such
 * item needs a resolution — assign it to one person, split it evenly across everyone, or have the
 * creator cover it themselves — before the split can lock. "Split the rest evenly" resolves every
 * still-unresolved item in one tap. Finalize is enabled only once every unclaimed item has a choice.
 */
@Composable
fun FinalizeSheet(
    session: ClaimSessionDto,
    participants: List<Participant>,
    unclaimedItemIndices: List<Int>,
    onResolveFinalize: (List<ResolutionDto>) -> Unit,
    onDismiss: () -> Unit,
) {
    var resolutions by remember { mutableStateOf<Map<Int, ResolutionDto>>(emptyMap()) }
    val itemsByIdx = session.items.associateBy { it.idx }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
        ) {
            Text("Finalize split", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (unclaimedItemIndices.isEmpty()) {
                Text(
                    "Everything has been claimed — lock it in.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Nobody claimed these — decide how they're handled before locking the split.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SchismSecondaryButton(
                    onClick = {
                        resolutions = unclaimedItemIndices.associateWith { idx ->
                            resolutions[idx] ?: ResolutionDto(itemIdx = idx, mode = "split")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Split the rest evenly") }
                Spacer(Modifier.height(12.dp))
                unclaimedItemIndices.forEach { idx ->
                    val item = itemsByIdx[idx]
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(item?.name ?: "Item", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(
                                    formatMinor(item?.amountMinor ?: 0L, session.currency),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            ItemResolutionChips(
                                itemIdx = idx,
                                participants = participants,
                                creatorParticipantId = session.creatorParticipantId,
                                resolution = resolutions[idx],
                                onResolve = { r -> resolutions = resolutions + (idx to r) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SchismSecondaryButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                SchismPrimaryButton(
                    onClick = { onResolveFinalize(unclaimedItemIndices.mapNotNull { resolutions[it] }) },
                    enabled = unclaimedItemIndices.all { it in resolutions },
                    modifier = Modifier.weight(1f),
                ) { Text("Finalize") }
            }
        }
    }
}

@Composable
private fun ItemResolutionChips(
    itemIdx: Int,
    participants: List<Participant>,
    creatorParticipantId: String,
    resolution: ResolutionDto?,
    onResolve: (ResolutionDto) -> Unit,
) {
    var showAssignPicker by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SchismFilterChip(
            selected = resolution?.mode == "split",
            onClick = { onResolve(ResolutionDto(itemIdx = itemIdx, mode = "split")) },
            label = { Text("Split evenly") },
        )
        SchismFilterChip(
            selected = resolution?.mode == "cover",
            onClick = { onResolve(ResolutionDto(itemIdx = itemIdx, mode = "cover", participantId = creatorParticipantId)) },
            label = { Text("I'll cover it") },
        )
        SchismFilterChip(
            selected = resolution?.mode == "assign",
            onClick = { showAssignPicker = true },
            label = { Text(if (resolution?.mode == "assign") assignedName(resolution, participants) else "Assign to…") },
        )
    }
    if (showAssignPicker) {
        AssignPickerDialog(
            participants = participants,
            onDismiss = { showAssignPicker = false },
            onPick = { pid ->
                onResolve(ResolutionDto(itemIdx = itemIdx, mode = "assign", participantId = pid))
                showAssignPicker = false
            },
        )
    }
}

private fun assignedName(resolution: ResolutionDto, participants: List<Participant>): String =
    participants.firstOrNull { it.id == resolution.participantId }?.name ?: "Assigned"

@Composable
private fun AssignPickerDialog(
    participants: List<Participant>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign to") },
        text = {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Person") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
                    participants.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = { onPick(p.id) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
