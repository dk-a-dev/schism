@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ai.schism.split.walkthrough

import ai.schism.split.R
import ai.schism.split.core.money.formatMinor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** Route constants for the guided tour. Register these in `AppNav`/`Routes`. */
object WalkthroughRoutes {
    const val DEMO = "walkthrough/demo"
}

private data class StepCopy(val titleRes: Int, val bodyRes: Int, val target: WalkthroughTargetId)

private val stepCopy = mapOf(
    WalkthroughStep.GROUP to StepCopy(
        R.string.walkthrough_step_group_title,
        R.string.walkthrough_step_group_body,
        WalkthroughTargetId.DEMO_GROUP,
    ),
    WalkthroughStep.RECEIPT to StepCopy(
        R.string.walkthrough_step_receipt_title,
        R.string.walkthrough_step_receipt_body,
        WalkthroughTargetId.DEMO_RECEIPT,
    ),
    WalkthroughStep.ASSIGN to StepCopy(
        R.string.walkthrough_step_assign_title,
        R.string.walkthrough_step_assign_body,
        WalkthroughTargetId.DEMO_ASSIGN,
    ),
    WalkthroughStep.BALANCES to StepCopy(
        R.string.walkthrough_step_balances_title,
        R.string.walkthrough_step_balances_body,
        WalkthroughTargetId.DEMO_BALANCES,
    ),
    WalkthroughStep.LIVE_SPLIT to StepCopy(
        R.string.walkthrough_step_live_split_title,
        R.string.walkthrough_step_live_split_body,
        WalkthroughTargetId.DEMO_LIVE_SPLIT,
    ),
)

/**
 * The two-minute tour. Everything on screen comes from [DemoRepository]; no repository, network,
 * permission, camera, OCR download, or share intent is reachable from here. Leaving the screen for
 * any reason (Skip, close, system back, completion) returns the user to their real app state.
 */
@Composable
fun DemoTourScreen(
    onExit: () -> Unit,
    viewModel: WalkthroughViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val demo by viewModel.demoSnapshot.collectAsState()
    val registry = remember { WalkthroughTargetRegistry() }
    val scroll = rememberScrollState()
    val step = state.currentStep

    // This screen activates its own tour rather than trusting whoever navigated here to have done
    // it. hiltViewModel() is scoped per nav destination, so Settings holds a DIFFERENT
    // WalkthroughViewModel instance; its Replay only reaches this one via DataStore, asynchronously.
    LaunchedEffect(Unit) {
        if (state.status != WalkthroughStatus.ACTIVE) viewModel.replayTour()
    }

    // Skip/complete/sign-out all land here; the demo data is dropped on the way out. Gated on
    // having actually been active: without that, the default (not-yet-loaded) state reads as
    // "finished" and the screen pops itself the instant it opens.
    var wasActive by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.status) {
        if (state.status == WalkthroughStatus.ACTIVE) {
            wasActive = true
        } else if (wasActive) {
            viewModel.endDemo()
            onExit()
        }
    }
    // Instant, not animated, so reduced-motion users get the same behaviour.
    LaunchedEffect(step) { scroll.scrollTo(scroll.maxValue) }

    BackHandler(enabled = true) {
        if (step != null && step.ordinal > 0) viewModel.back() else viewModel.skipTour()
    }

    CompositionLocalProvider(LocalWalkthroughTargets provides registry) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.walkthrough_demo_title)) },
                        actions = {
                            IconButton(onClick = viewModel::skipTour) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.walkthrough_demo_close),
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.walkthrough_demo_banner),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val reached = step?.ordinal ?: WalkthroughStep.entries.lastIndex

                    GroupCard(demo)
                    if (reached >= WalkthroughStep.RECEIPT.ordinal) ReceiptCard(demo)
                    if (reached >= WalkthroughStep.ASSIGN.ordinal) {
                        AssignCard(demo, onClaim = viewModel::assignItem)
                    }
                    if (reached >= WalkthroughStep.BALANCES.ordinal) BalancesCard(demo)
                    if (reached >= WalkthroughStep.LIVE_SPLIT.ordinal) LiveSplitCard(demo)
                }
            }

            val copy = step?.let(stepCopy::getValue)
            if (copy != null) {
                WalkthroughOverlay(
                    targetId = copy.target,
                    title = stringResource(copy.titleRes),
                    body = stringResource(copy.bodyRes),
                    stepIndex = step.ordinal,
                    stepCount = WalkthroughStep.entries.size,
                    // ASSIGN has no Continue: it advances only when an item is really claimed.
                    confirmLabel = stringResource(
                        if (step == WalkthroughStep.LIVE_SPLIT) {
                            R.string.walkthrough_finish
                        } else {
                            R.string.walkthrough_continue
                        },
                    ),
                    onConfirm = { viewModel.completeStep(step) },
                    onBack = if (step.ordinal > 0) ({ viewModel.back() }) else null,
                    onSkip = viewModel::skipTour,
                )
            }
        }
    }
}

@Composable
private fun DemoCard(
    target: WalkthroughTargetId,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth().walkthroughTarget(target)) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun GroupCard(demo: DemoSnapshot) = DemoCard(WalkthroughTargetId.DEMO_GROUP) {
    Text(demo.group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        demo.group.participants.joinToString { it.name },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReceiptCard(demo: DemoSnapshot) = DemoCard(WalkthroughTargetId.DEMO_RECEIPT) {
    val symbol = demo.group.currencySymbol
    Text(demo.receipt.merchant, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    demo.receipt.items.forEach { AmountRow(it.name, formatMinor(it.amountMinor, symbol)) }
    AmountRow(
        stringResource(R.string.walkthrough_demo_receipt_subtotal),
        formatMinor(demo.receipt.subtotalMinor, symbol),
    )
    AmountRow(
        stringResource(R.string.walkthrough_demo_receipt_tax),
        formatMinor(demo.receipt.taxMinor, symbol),
    )
    AmountRow(
        stringResource(R.string.walkthrough_demo_receipt_total),
        formatMinor(demo.receipt.totalMinor, symbol),
        bold = true,
    )
}

@Composable
private fun AssignCard(
    demo: DemoSnapshot,
    onClaim: (String, Set<String>) -> Unit,
) = DemoCard(WalkthroughTargetId.DEMO_ASSIGN) {
    Text(
        stringResource(R.string.walkthrough_demo_assign_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    demo.receipt.items.forEach { item ->
        Text(
            "${item.name} · ${formatMinor(item.amountMinor, demo.group.currencySymbol)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            demo.group.participants.forEach { participant ->
                val claimed = participant.id in item.assignedParticipantIds
                FilterChip(
                    selected = claimed,
                    onClick = {
                        val next = if (claimed) {
                            item.assignedParticipantIds - participant.id
                        } else {
                            item.assignedParticipantIds + participant.id
                        }
                        // Every item must stay claimed by someone for the totals to reconcile.
                        if (next.isNotEmpty()) onClaim(item.id, next)
                    },
                    label = { Text(participant.name) },
                )
            }
        }
    }
    demo.group.participants.forEach { participant ->
        AmountRow(
            participant.name,
            formatMinor(demo.participantTotalsMinor.getValue(participant.id), demo.group.currencySymbol),
        )
    }
}

@Composable
private fun BalancesCard(demo: DemoSnapshot) = DemoCard(WalkthroughTargetId.DEMO_BALANCES) {
    val symbol = demo.group.currencySymbol
    demo.group.participants.forEach { participant ->
        val total = demo.balances.perParticipant.getValue(participant.id).total
        val verb = when {
            total > 0 -> stringResource(R.string.walkthrough_demo_gets_back)
            total < 0 -> stringResource(R.string.walkthrough_demo_owes)
            else -> stringResource(R.string.walkthrough_demo_settled)
        }
        AmountRow("${participant.name} $verb", formatMinor(total, symbol))
    }
    demo.balances.reimbursements.forEach { reimbursement ->
        val from = demo.group.participants.first { it.id == reimbursement.fromParticipantId }.name
        // Informational only: the tour never opens a UPI app or records a real settlement.
        AssistChip(
            onClick = {},
            label = {
                Text(
                    stringResource(
                        R.string.walkthrough_demo_settle_action,
                        formatMinor(reimbursement.amount, symbol),
                        from,
                    ),
                )
            },
        )
    }
}

@Composable
private fun LiveSplitCard(demo: DemoSnapshot) = DemoCard(WalkthroughTargetId.DEMO_LIVE_SPLIT) {
    Text(
        stringResource(R.string.walkthrough_demo_live_split),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        stringResource(
            R.string.walkthrough_demo_live_split_progress,
            demo.liveSplitPreview.claimedItemCount,
            demo.liveSplitPreview.totalItemCount,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    demo.group.participants.forEach { participant ->
        AmountRow(
            participant.name,
            formatMinor(
                demo.liveSplitPreview.participantTotalsMinor.getValue(participant.id),
                demo.group.currencySymbol,
            ),
        )
    }
}

@Composable
private fun AmountRow(label: String, amount: String, bold: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val weight = if (bold) FontWeight.Bold else FontWeight.Normal
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
        Text(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
    }
}
