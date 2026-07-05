@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.schism.split.expense.edit

import ai.schism.split.core.ui.InitialAvatar
import ai.schism.split.expense.edit.voice.rememberVoiceInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ExpenseEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ExpenseEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val startVoice = rememberVoiceInput { transcript -> viewModel.applyVoice(transcript) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEdit) "Edit expense" else "Add expense") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = startVoice) {
                        Icon(Icons.Filled.Mic, contentDescription = "Add by voice")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                WavyProgress()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Details") {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::onTitleChange,
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.amountText,
                    onValueChange = viewModel::onAmountChange,
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                CategoryDropdown(state, viewModel)
                PaidBySelector(state, viewModel)
            }

            SectionCard(title = "How to split") {
                SplitModeChips(state, viewModel)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                state.rows.forEach { row ->
                    ParticipantRowItem(row, state.splitMode, viewModel)
                }
            }

            SectionCard(title = "More") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Reimbursement",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Settle up rather than add a shared cost",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.isReimbursement,
                        onCheckedChange = viewModel::onReimbursementChange,
                    )
                }

                OutlinedTextField(
                    value = state.notes,
                    onValueChange = viewModel::onNotesChange,
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { viewModel.submit(onSaved) },
                enabled = !state.submitting,
                shape = MaterialTheme.shapes.large,
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text("Save", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun SplitMode.label(): String = when (this) {
    SplitMode.EVENLY -> "Evenly"
    SplitMode.BY_SHARES -> "Shares"
    SplitMode.BY_PERCENTAGE -> "Percentage"
    SplitMode.BY_AMOUNT -> "Amount"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitModeChips(state: ExpenseEditUiState, viewModel: ExpenseEditViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SplitMode.entries.forEach { mode ->
            FilterChip(
                selected = state.splitMode == mode,
                onClick = { viewModel.onSplitModeChange(mode) },
                label = { Text(mode.label()) },
            )
        }
    }
}

@Composable
private fun CategoryDropdown(state: ExpenseEditUiState, viewModel: ExpenseEditViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = state.categories.firstOrNull { it.id == state.categoryId }?.name ?: "None"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text("${category.grouping} · ${category.name}") },
                    onClick = {
                        viewModel.onCategoryChange(category.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PaidBySelector(state: ExpenseEditUiState, viewModel: ExpenseEditViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = state.participants.firstOrNull { it.id == state.paidById }?.name ?: "Select"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Paid by") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.participants.forEach { participant ->
                DropdownMenuItem(
                    text = { Text(participant.name) },
                    onClick = {
                        viewModel.onPaidByChange(participant.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ParticipantRowItem(row: ParticipantRow, mode: SplitMode, viewModel: ExpenseEditViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        InitialAvatar(name = row.name, key = row.participantId, size = 36.dp)
        Checkbox(
            checked = row.selected,
            onCheckedChange = { viewModel.onToggleParticipant(row.participantId) },
        )
        Text(
            row.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (row.selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        when (mode) {
            SplitMode.EVENLY -> Unit
            SplitMode.BY_SHARES -> OutlinedTextField(
                value = row.weightText,
                onValueChange = { viewModel.onWeightChange(row.participantId, it) },
                label = { Text("Shares") },
                enabled = row.selected,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            SplitMode.BY_PERCENTAGE -> OutlinedTextField(
                value = row.percentText,
                onValueChange = { viewModel.onPercentChange(row.participantId, it) },
                label = { Text("%") },
                enabled = row.selected,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(120.dp),
            )
            SplitMode.BY_AMOUNT -> OutlinedTextField(
                value = row.amountText,
                onValueChange = { viewModel.onParticipantAmountChange(row.participantId, it) },
                label = { Text("Amount") },
                enabled = row.selected,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(120.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}
