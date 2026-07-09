@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.schism.split.sms.itemized.claim

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.net.ClaimItemDto
import ai.schism.split.core.ui.InitialAvatar
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.core.ui.SchismSecondaryButton
import ai.schism.split.core.ui.SplitLoader
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
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * "Claim what you ate": each item gets a −/+/typed weight stepper (0.5 supported) bound to this
 * device's own claim; a sticky footer shows the live "you owe" total. The creator additionally sees
 * a Finalize entry (full unclaimed-item resolution UI is [FinalizeSheet], Task 14). Alpha feature —
 * gated behind Settings › Labs (see [ai.schism.split.settings.SettingsRepository.claimLinksAlpha]).
 */
@Composable
fun ClaimScreen(
    onBack: () -> Unit,
    onFinalized: () -> Unit,
    viewModel: ClaimSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showFinalizeSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            state.session?.title?.ifBlank { "Claim links" } ?: "Claim links",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        AlphaBadge()
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (state.session != null && state.status == "open") {
                OwesFooter(
                    owesMinor = state.myOwes,
                    currency = state.session?.currency ?: "₹",
                    isCreator = state.isCreator,
                    myReady = state.myReady,
                    readyCount = state.readyCount,
                    memberCount = state.memberCount,
                    onToggleReady = { viewModel.toggleReady() },
                    // A creator with unclaimed items resolves them via FinalizeSheet; with nothing
                    // left unclaimed, finalize directly.
                    onFinalize = {
                        if (state.unclaimedItemIndices.isEmpty()) {
                            viewModel.finalize(emptyList()) { onFinalized() }
                        } else {
                            showFinalizeSheet = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SplitLoader() }
                state.session == null && state.error != null -> LoadErrorState(message = state.error!!, onRetry = { viewModel.refresh() })
                state.status != "open" -> LockedState(onDone = onFinalized)
                else -> {
                    val session = state.session
                    if (session != null) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                formatMinor(sessionTotal(session), session.currency),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val claimedCount = state.myClaimedItemCount
                            if (claimedCount > 0) {
                                Text(
                                    "You've claimed $claimedCount item${if (claimedCount == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "Tap the number for halves like 0.5",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (session.taxes.isNotEmpty()) {
                                TaxesCard(taxes = session.taxes, currency = session.currency)
                            }
                            session.items.forEach { item ->
                                ClaimItemCard(
                                    item = item,
                                    currency = session.currency,
                                    weight = state.weightFor(item.idx),
                                    claimants = state.claimantsFor(item.idx),
                                    myParticipantId = state.myParticipantId,
                                    onAdjust = { delta -> viewModel.adjustWeight(item.idx, delta) },
                                    onSet = { w -> viewModel.setWeight(item.idx, w) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFinalizeSheet) {
        val session = state.session
        if (session != null) {
            FinalizeSheet(
                session = session,
                participants = state.participants,
                unclaimedItemIndices = state.unclaimedItemIndices,
                onResolveFinalize = { resolutions ->
                    viewModel.finalize(resolutions) { showFinalizeSheet = false; onFinalized() }
                },
                onDismiss = { showFinalizeSheet = false },
            )
        }
    }
}

private fun sessionTotal(session: ai.schism.split.core.net.ClaimSessionDto): Long =
    session.items.sumOf { it.amountMinor } + session.taxMinor + session.feesMinor -
        session.discountMinor + session.roundoffMinor

@Composable
private fun AlphaBadge() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            "ALPHA",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun LockedState(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(40.dp))
                Text(
                    "The creator locked this split",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SchismPrimaryButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

@Composable
private fun LoadErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Couldn't load this split",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SchismPrimaryButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun OwesFooter(
    owesMinor: Long,
    currency: String,
    isCreator: Boolean,
    myReady: Boolean,
    readyCount: Int,
    memberCount: Int,
    onToggleReady: () -> Unit,
    onFinalize: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("You owe", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatMinor(owesMinor, currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (isCreator && memberCount > 0) {
                    Text(
                        "$readyCount of $memberCount ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isCreator) {
                SchismPrimaryButton(onClick = onFinalize) { Text("Finalize") }
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    if (myReady) {
                        SchismSecondaryButton(onClick = onToggleReady) { Text("Marked done — tap to undo") }
                    } else {
                        SchismPrimaryButton(onClick = onToggleReady) { Text("I'm done ✓") }
                    }
                    Text(
                        "Waiting for the creator to finalize",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Small "Taxes" section listing each labelled tax/charge line (e.g. SGST/CGST) and its amount. Only
 * shown when the session has a labelled breakdown (session.taxes non-empty). */
@Composable
private fun TaxesCard(taxes: List<ai.schism.split.core.net.TaxLineDto>, currency: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Taxes",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            taxes.forEach { tax ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tax.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatMinor(tax.amountMinor, currency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClaimItemCard(
    item: ClaimItemDto,
    currency: String,
    weight: Double,
    claimants: List<ai.schism.split.groups.data.Participant>,
    myParticipantId: String,
    onAdjust: (Double) -> Unit,
    onSet: (Double) -> Unit,
) {
    var editingWeight by remember { mutableStateOf(false) }
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
            }
            if (claimants.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    claimants.forEach { p -> InitialAvatar(name = p.name, key = p.id, size = 28.dp) }
                }
            } else {
                Text(
                    "No one's claimed this yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Your claim",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onAdjust(-1.0) }, enabled = weight > 0) {
                    Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Less")
                }
                Text(
                    if (weight == weight.toLong().toDouble()) weight.toLong().toString() else weight.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { editingWeight = true }
                        .padding(horizontal = 4.dp),
                )
                IconButton(onClick = { onAdjust(1.0) }) {
                    Icon(Icons.Filled.AddCircleOutline, contentDescription = "More")
                }
            }
        }
    }

    if (editingWeight) {
        WeightEntryDialog(
            initial = weight,
            onDismiss = { editingWeight = false },
            onSave = { w -> onSet(w); editingWeight = false },
        )
    }
}

/** Type a weight directly (supports halves, e.g. 0.5), instead of tapping − / + repeatedly. */
@Composable
private fun WeightEntryDialog(initial: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var value by remember { mutableStateOf(if (initial == initial.toLong().toDouble()) initial.toLong().toString() else initial.toString()) }
    val parsed = value.trim().toDoubleOrNull()
    val valid = parsed != null && parsed >= 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set your claim") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Weight (e.g. 0.5, 1, 2)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onSave(parsed!!.coerceAtLeast(0.0)) }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
