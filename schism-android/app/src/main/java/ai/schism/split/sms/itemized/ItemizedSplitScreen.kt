@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ai.schism.split.sms.itemized

import ai.schism.split.core.money.formatMinor
import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.Participant
import ai.schism.split.sms.receipt.ReceiptLineItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import ai.schism.split.core.ui.WavyProgress
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ItemizedSplitScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ItemizedSplitViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split by items") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    WavyProgress()
                }
                state.items.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("This receipt has no line items to split.")
                }
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    state.draft?.let { draft ->
                        Text(
                            draft.merchant,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    if (state.groups.isEmpty()) {
                        Text(
                            "Join or create a group first to split this receipt.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val group = state.selectedGroup
                        GroupPicker(
                            groups = state.groups,
                            selected = group,
                            onSelect = viewModel::onGroupChange,
                        )
                        if (group != null) {
                            PaidByPicker(
                                group = group,
                                paidById = state.paidById,
                                onSelect = viewModel::onPaidByChange,
                            )
                            state.items.forEachIndexed { index, item ->
                                ItemCard(
                                    item = item,
                                    currency = state.draft?.currency ?: "₹",
                                    participants = group.participants,
                                    assigned = state.assignments[index].orEmpty(),
                                    onToggle = { pid -> viewModel.toggleAssignment(index, pid) },
                                )
                            }
                            PerPersonTotals(
                                group = group,
                                perPerson = state.perPersonMinor,
                                currency = state.draft?.currency ?: "₹",
                            )
                        }
                    }

                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }

                    Button(
                        onClick = { viewModel.submit(onDone) },
                        enabled = !state.submitting && state.selectedGroupId != null && state.paidById.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Create expense")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemCard(
    item: ReceiptLineItem,
    currency: String,
    participants: List<Participant>,
    assigned: Set<String>,
    onToggle: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatMinor(item.amountMinor, currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                participants.forEach { p ->
                    FilterChip(
                        selected = p.id in assigned,
                        onClick = { onToggle(p.id) },
                        label = { Text(p.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PerPersonTotals(group: Group, perPerson: Map<String, Long>, currency: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Each person owes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            group.participants.forEach { p ->
                val owed = perPerson[p.id] ?: 0L
                if (owed > 0L) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(p.name, modifier = Modifier.weight(1f))
                        Text(formatMinor(owed, currency), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupPicker(groups: List<Group>, selected: Group?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Group") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.name) },
                    onClick = {
                        onSelect(group.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PaidByPicker(group: Group, paidById: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = group.participants.firstOrNull { it.id == paidById }?.name ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Paid by") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            group.participants.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    onClick = {
                        onSelect(p.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
