@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package ai.schism.split.sms.itemized

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.core.ui.SchismSecondaryButton
import ai.schism.split.core.ui.SplitLoader
import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.Participant
import ai.schism.split.sms.receipt.ReceiptLineItem
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ItemizedSplitScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ItemizedSplitViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var addingItem by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

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
                    SplitLoader()
                }
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedTextField(
                                value = state.title,
                                onValueChange = viewModel::onTitleChange,
                                label = { Text("Merchant / title") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "You paid. Use − / + to set how much of each dish a person had. Tax " +
                                    "splits by what each person ordered.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            state.draft?.let { d ->
                                val verified = d.verified
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        if (verified) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        if (verified) "Totals verified — the items add up to the bill"
                                        else "Double-check the items — the totals didn't add up",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                }
                            }

                            if (state.groups.isNotEmpty()) {
                                GroupPicker(
                                    groups = state.groups,
                                    selected = state.selectedGroup,
                                    onSelect = viewModel::onGroupChange,
                                )
                            }
                        }
                    }

                    if (!state.parsedByAi) {
                        AiTipCard(aiEnabled = state.aiActive)
                    }

                    if (state.groups.isEmpty()) {
                        Text(
                            "Join or create a group first to split this receipt.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val group = state.selectedGroup
                        if (group != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.items.forEachIndexed { index, item ->
                                    ItemCard(
                                        item = item,
                                        currency = state.draft?.currency ?: "₹",
                                        participants = group.participants,
                                        shares = state.assignments[index].orEmpty(),
                                        onAdjust = { pid, d -> viewModel.adjustShare(index, pid, d) },
                                        onSetShare = { pid, value -> viewModel.setShare(index, pid, value) },
                                        onEdit = { name, qty, amount -> viewModel.updateItem(index, name, qty, amount) },
                                        onRemove = { viewModel.removeItem(index) },
                                    )
                                }
                            }
                            SchismSecondaryButton(onClick = { addingItem = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Text("  Add item")
                            }
                            if (state.items.isEmpty()) {
                                Text(
                                    "Nothing could be read off this bill — add the dishes by hand above.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            PerPersonTotals(
                                group = group,
                                perPerson = state.perPersonMinor,
                                taxMinor = state.taxMinor,
                                currency = state.draft?.currency ?: "₹",
                            )
                        }
                    }

                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }

                    OutlinedTextField(
                        value = state.notes,
                        onValueChange = viewModel::onNotesChange,
                        label = { Text("Notes (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SchismPrimaryButton(
                        onClick = {
                            viewModel.submit(onDone = {
                                Toast.makeText(
                                    context,
                                    "Split added to ${state.selectedGroup?.name ?: "group"}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                onDone()
                            })
                        },
                        enabled = !state.submitting && state.selectedGroupId != null && state.items.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.submitting) {
                            LoadingIndicator(
                                modifier = Modifier.size(20.dp),
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

    if (addingItem) {
        ItemDialog(
            title = "Add item",
            initial = ReceiptLineItem("", 0L, 1),
            onDismiss = { addingItem = false },
            onSave = { name, qty, amount ->
                viewModel.addItem(name, qty, amount)
                addingItem = false
            },
        )
    }
}

@Composable
private fun AiTipCard(aiEnabled: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    if (aiEnabled) "AI couldn't read this bill" else "This bill was read with the basic parser",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    if (aiEnabled) {
                        "The on-device model didn't produce a clean read (try re-downloading it in " +
                            "Settings → On-device AI). Fix or remove items below — everything is editable."
                    } else {
                        "Turn on Settings → On-device AI and download the model for far better reads " +
                            "(names, quantities, tax). Fix or remove items below — everything is editable."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ItemCard(
    item: ReceiptLineItem,
    currency: String,
    participants: List<Participant>,
    shares: Map<String, Long>,
    onAdjust: (String, Long) -> Unit,
    onSetShare: (String, Long) -> Unit,
    onEdit: (String, Int, Long) -> Unit,
    onRemove: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var editingShareFor by remember { mutableStateOf<Pair<String, Long>?>(null) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.qty > 1) "${item.name}  ×${item.qty}" else item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatMinor(item.amountMinor, currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit item", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove item", modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider()
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                participants.forEach { p ->
                    val share = shares[p.id] ?: 0L
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (share > 0) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onAdjust(p.id, -1L) }, enabled = share > 0) {
                            Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Less")
                        }
                        Text(
                            share.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { editingShareFor = p.id to share }
                                .padding(horizontal = 4.dp),
                        )
                        IconButton(onClick = { onAdjust(p.id, +1L) }) {
                            Icon(Icons.Filled.AddCircleOutline, contentDescription = "More")
                        }
                    }
                }
            }
        }
    }

    if (editing) {
        ItemDialog(
            title = "Edit item",
            initial = item,
            onDismiss = { editing = false },
            onSave = { name, qty, amount ->
                onEdit(name, qty, amount)
                editing = false
            },
        )
    }

    editingShareFor?.let { (pid, current) ->
        ShareEntryDialog(
            initial = current,
            onDismiss = { editingShareFor = null },
            onSave = { value ->
                onSetShare(pid, value)
                editingShareFor = null
            },
        )
    }
}

/** Type a participant's share of an item directly, instead of tapping − / + repeatedly. */
@Composable
private fun ShareEntryDialog(
    initial: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
) {
    var value by remember { mutableStateOf(initial.toString()) }
    val parsed = value.trim().toLongOrNull()
    val valid = parsed != null && parsed >= 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set share") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Count") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onSave(parsed!!.coerceAtLeast(0L)) },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Add/edit an item: name, quantity, and the line's total amount. */
@Composable
private fun ItemDialog(
    title: String,
    initial: ReceiptLineItem,
    onDismiss: () -> Unit,
    onSave: (String, Int, Long) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var qty by remember { mutableStateOf(if (initial.qty > 0) initial.qty.toString() else "1") }
    var amount by remember {
        mutableStateOf(if (initial.amountMinor > 0) String.format("%.2f", initial.amountMinor / 100.0) else "")
    }
    val qtyInt = qty.trim().toIntOrNull()
    val amountMinor = amount.trim().toDoubleOrNull()?.let { (it * 100).toLong() }
    val valid = name.isNotBlank() && qtyInt != null && qtyInt > 0 && amountMinor != null && amountMinor > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item name") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(3f)) {
                        IconButton(
                            onClick = { qty = ((qty.toIntOrNull() ?: 1) - 1).coerceAtLeast(1).toString() },
                        ) { Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Less") }
                        OutlinedTextField(
                            value = qty,
                            onValueChange = { qty = it },
                            label = { Text("Qty") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { qty = ((qty.toIntOrNull() ?: 1) + 1).toString() },
                        ) { Icon(Icons.Filled.AddCircleOutline, contentDescription = "More") }
                    }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Line amount") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(2f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onSave(name, qtyInt!!, amountMinor!!) },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PerPersonTotals(group: Group, perPerson: Map<String, Long>, taxMinor: Long, currency: String) {
    val grand = perPerson.values.sum()
    val subtotal = grand - taxMinor
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
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
            HorizontalDivider()
            SummaryRow("Items", formatMinor(subtotal, currency), false)
            if (taxMinor > 0) SummaryRow("Tax & charges", formatMinor(taxMinor, currency), false)
            SummaryRow("Total", formatMinor(grand, currency), true)
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, bold: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(value, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium)
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
