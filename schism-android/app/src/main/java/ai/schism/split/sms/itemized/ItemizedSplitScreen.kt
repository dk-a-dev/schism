@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package ai.schism.split.sms.itemized

import ai.schism.split.R
import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.core.ui.SchismSecondaryButton
import ai.schism.split.core.ui.SplitLoader
import ai.schism.split.groups.data.Group
import ai.schism.split.plus.PlusSheet
import ai.schism.split.groups.data.Participant
import ai.schism.split.sms.receipt.ReceiptLineItem
import ai.schism.split.sms.receipt.engine.parseMinor
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.abs

@Composable
fun ItemizedSplitScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    onClaimSessionCreated: (String) -> Unit = {},
    viewModel: ItemizedSplitViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var addingItem by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // Only a backend PLUS_REQUIRED opens this. Dismissing it leaves the draft, the items and every
    // assignment exactly where they were — splitting this bill by hand never needs Plus.
    state.plusRequired?.let { allowance ->
        PlusSheet(
            onDismiss = viewModel::dismissPlusSheet,
            reason = stringResource(ai.schism.split.R.string.live_split_allowance_none, allowance.limit) +
                "\n" + stringResource(ai.schism.split.R.string.live_split_manual_alternative),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split by items", style = MaterialTheme.typography.headlineSmall) },
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

                            // Live arithmetic, not the scan's one-off `verified` flag: it re-reads
                            // after every item/charge edit, and never overrides either number.
                            if (state.printedTotalMinor > 0L) {
                                val currency = state.draft?.currency ?: "₹"
                                val mismatch = state.totalMismatchMinor
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        if (mismatch == null) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (mismatch == null) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        if (mismatch == null) {
                                            stringResource(R.string.itemized_totals_match)
                                        } else {
                                            stringResource(
                                                if (mismatch > 0) R.string.itemized_totals_mismatch_more
                                                else R.string.itemized_totals_mismatch_less,
                                                formatMinor(state.totalMinor, currency),
                                                formatMinor(state.printedTotalMinor, currency),
                                                formatMinor(abs(mismatch), currency),
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (mismatch == null) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
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

                    // Only relevant to a SCANNED bill (a manual/hand-built one, state.draft == null,
                    // never went through OCR/AI at all, so there's no "couldn't read this" to tip about).
                    if (state.draft != null && !state.parsedByAi) {
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
                                        onEdit = { name, qty, unitPriceMinor ->
                                            viewModel.updateItem(index, name, qty, unitPriceMinor)
                                        },
                                        onRemove = { viewModel.removeItem(index) },
                                    )
                                }
                            }
                            if (state.items.isEmpty()) {
                                Text(
                                    // No scan happened at all (manual entry) vs. a scan that read nothing.
                                    if (state.draft == null) "Add items to split — tap \"Add item\" below."
                                    else "Nothing could be read off this bill — add the dishes by hand below.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SchismSecondaryButton(onClick = { addingItem = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Text("  Add item")
                            }
                            if (state.items.isNotEmpty()) {
                                SchismSecondaryButton(
                                    onClick = {
                                        viewModel.startClaimSession { sid -> onClaimSessionCreated(sid) }
                                    },
                                    enabled = !state.creatingClaimSession,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (state.creatingClaimSession) "Creating…" else "Let everyone claim")
                                }
                                // What's left is stated up front, before the tap — never discovered
                                // only after the backend refuses. Plus hosts without a counter.
                                state.liveSplitAllowance?.takeIf { !state.plus }?.let { allowance ->
                                    Text(
                                        if (allowance.remaining > 0) {
                                            stringResource(
                                                ai.schism.split.R.string.live_split_allowance_left,
                                                allowance.remaining,
                                                allowance.limit,
                                            )
                                        } else {
                                            stringResource(
                                                ai.schism.split.R.string.live_split_allowance_none,
                                                allowance.limit,
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            BillSummaryCard(
                                group = group,
                                perPerson = state.perPersonMinor,
                                charges = state.charges,
                                subtotalMinor = state.subtotalMinor,
                                totalMinor = state.totalMinor,
                                printedTotalMinor = state.printedTotalMinor,
                                currency = state.draft?.currency ?: "₹",
                                onEditCharge = viewModel::updateCharge,
                                onAddCharge = viewModel::addCharge,
                                onRemoveCharge = viewModel::removeCharge,
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
            currency = state.draft?.currency ?: "₹",
            onDismiss = { addingItem = false },
            onSave = { name, qty, unitPriceMinor ->
                viewModel.addItem(name, qty, unitPriceMinor)
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (item.qty > 1) "${item.name}  ×${item.qty}" else item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (item.qty > 1 && item.unitPriceMinor > 0) {
                        Text(
                            "${item.qty} × ${formatMinor(item.unitPriceMinor, currency)} = " +
                                formatMinor(item.amountMinor, currency),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
            currency = currency,
            onDismiss = { editing = false },
            onSave = { name, qty, unitPriceMinor ->
                onEdit(name, qty, unitPriceMinor)
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

/**
 * Add/edit an item: name, quantity, and its PER-UNIT price — the line amount is derived as
 * `qty * unitPrice` (never typed directly), keeping the [ReceiptLineItem] invariant intact so
 * Feature 2's "qty × unit price = amount" card display is accurate for hand-entered items too.
 * [onSave] reports (name, qty, unitPriceMinor); the caller computes/stores the amount.
 */
@Composable
private fun ItemDialog(
    title: String,
    initial: ReceiptLineItem,
    currency: String,
    onDismiss: () -> Unit,
    onSave: (String, Int, Long) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var qty by remember { mutableStateOf(if (initial.qty > 0) initial.qty.toString() else "1") }
    // Prefer the item's own unit price; fall back to deriving one from its lump amount (e.g. an
    // older scanned item that never had unitPriceMinor set) so editing doesn't blank the field.
    val initialUnitMinor = when {
        initial.unitPriceMinor > 0 -> initial.unitPriceMinor
        initial.qty > 0 && initial.amountMinor > 0 -> initial.amountMinor / initial.qty
        else -> 0L
    }
    var unitPrice by remember {
        mutableStateOf(
            if (initialUnitMinor > 0) String.format(java.util.Locale.ROOT, "%.2f", initialUnitMinor / 100.0) else "",
        )
    }
    val qtyInt = qty.trim().toIntOrNull()
    val unitPriceMinor = parseMinor(unitPrice)
    val amountMinor = if (qtyInt != null && unitPriceMinor != null) qtyInt * unitPriceMinor else null
    val valid = name.isNotBlank() && qtyInt != null && qtyInt > 0 && unitPriceMinor != null && unitPriceMinor > 0

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
                        value = unitPrice,
                        onValueChange = { unitPrice = it },
                        label = { Text("Unit price") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(2f),
                    )
                }
                if (amountMinor != null && amountMinor > 0) {
                    Text(
                        "Line total: ${formatMinor(amountMinor, currency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onSave(name, qtyInt!!, unitPriceMinor!!) },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Per-person shares plus the bill's own arithmetic, stated in full: items subtotal, every
 * (editable) tax/charge line, the resulting total, and — when they disagree — the total printed on
 * the bill alongside it. Editing a charge line flows straight back into [perPerson], since the same
 * charge pot is what gets distributed.
 */
@Composable
private fun BillSummaryCard(
    group: Group,
    perPerson: Map<String, Long>,
    charges: List<ChargeLine>,
    subtotalMinor: Long,
    totalMinor: Long,
    printedTotalMinor: Long,
    currency: String,
    onEditCharge: (Int, String, Long) -> Unit,
    onAddCharge: (String, Long) -> Unit,
    onRemoveCharge: (Int) -> Unit,
) {
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }

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
            SummaryRow(stringResource(R.string.itemized_summary_items), formatMinor(subtotalMinor, currency), false)
            charges.forEachIndexed { index, line ->
                ChargeRow(
                    line = line,
                    currency = currency,
                    onEdit = { editingIndex = index },
                    onRemove = { onRemoveCharge(index) },
                )
            }
            TextButton(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  " + stringResource(R.string.itemized_add_charge))
            }
            HorizontalDivider()
            SummaryRow(stringResource(R.string.itemized_summary_total), formatMinor(totalMinor, currency), true)
            // Both numbers stay on screen when they disagree — neither is quietly overwritten.
            if (printedTotalMinor > 0L && printedTotalMinor != totalMinor) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.itemized_summary_printed_total),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        formatMinor(printedTotalMinor, currency),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    editingIndex?.let { index ->
        charges.getOrNull(index)?.let { line ->
            ChargeDialog(
                title = stringResource(R.string.itemized_edit_charge_title),
                initial = line,
                currency = currency,
                onDismiss = { editingIndex = null },
                onSave = { label, amountMinor ->
                    onEditCharge(index, label, amountMinor)
                    editingIndex = null
                },
            )
        }
    }

    if (adding) {
        ChargeDialog(
            title = stringResource(R.string.itemized_add_charge),
            initial = ChargeLine("", 0L),
            currency = currency,
            onDismiss = { adding = false },
            onSave = { label, amountMinor ->
                onAddCharge(label, amountMinor)
                adding = false
            },
        )
    }
}

@Composable
private fun ChargeRow(line: ChargeLine, currency: String, onEdit: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            line.label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(formatMinor(line.amountMinor, currency), fontWeight = FontWeight.Medium)
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.itemized_edit_charge) + ": " + line.label,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.itemized_remove_charge) + ": " + line.label,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Add/edit one tax or charge line: its label and its amount. The amount is typed as a positive
 * magnitude and signed by the adds/reduces toggle — the numeric-decimal IME has no minus key, so a
 * discount or a negative round-off would otherwise be untypeable. Money never leaves minor units:
 * [parseMinor] is the same string→Long parser the item dialog uses, and invalid input simply
 * disables Save.
 */
@Composable
private fun ChargeDialog(
    title: String,
    initial: ChargeLine,
    currency: String,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    var negative by remember { mutableStateOf(initial.amountMinor < 0L) }
    var amount by remember {
        mutableStateOf(
            if (initial.amountMinor != 0L) {
                String.format(java.util.Locale.ROOT, "%.2f", abs(initial.amountMinor) / 100.0)
            } else {
                ""
            },
        )
    }
    val magnitude = parseMinor(amount)?.let { abs(it) }
    val signed = magnitude?.let { if (negative) -it else it }
    val valid = label.isNotBlank() && signed != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.itemized_charge_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.itemized_charge_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = { negative = !negative },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        if (negative) stringResource(R.string.itemized_charge_reduces)
                        else stringResource(R.string.itemized_charge_adds),
                    )
                }
                if (signed != null) {
                    Text(
                        formatMinor(signed, currency),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onSave(label, signed!!) },
                enabled = valid,
            ) { Text(stringResource(R.string.itemized_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.itemized_cancel)) }
        },
    )
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
