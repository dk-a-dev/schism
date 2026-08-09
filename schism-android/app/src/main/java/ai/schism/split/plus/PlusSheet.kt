@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.schism.split.plus

import ai.schism.split.R
import ai.schism.split.core.billing.PlusPlan
import ai.schism.split.core.billing.PurchasePhase
import ai.schism.split.core.ui.SchismPrimaryButton
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * The Schism Plus purchase sheet. Deliberately calm: what you get, Play's own localized prices and
 * renewal terms, and an equally prominent way out. Neither plan is preselected, nothing is
 * countdown-timed, and dismissing it leaves every free feature exactly where it was.
 *
 * It is only ever opened from the backend's `PLUS_REQUIRED` answer to *hosting* a Live Split, or
 * from an explicit tap in Settings/Insights — never from an invite, a join, or an existing session.
 */
@Composable
fun PlusSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Optional context line, e.g. "You've used all 3 free Live Splits this month". */
    reason: String? = null,
    viewModel: PlusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activity = LocalContext.current.findActivity()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.plus_title), style = MaterialTheme.typography.headlineSmall)
            reason?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                stringResource(R.string.plus_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Benefit(stringResource(R.string.plus_benefit_unlimited))
            Benefit(stringResource(R.string.plus_benefit_no_ads))
            Benefit(stringResource(R.string.plus_benefit_insights))
            Benefit(stringResource(R.string.plus_benefit_export))

            Text(
                stringResource(R.string.plus_free_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                state.pending -> Text(stringResource(R.string.plus_pending), style = MaterialTheme.typography.bodyMedium)
                state.phase == PurchasePhase.Verifying ->
                    Text(stringResource(R.string.plus_verifying), style = MaterialTheme.typography.bodyMedium)
                state.phase == PurchasePhase.Failed -> Text(
                    stringResource(R.string.plus_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.plans.isEmpty() || !state.purchasesEnabled || activity == null) {
                Text(
                    stringResource(R.string.plus_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // No plan is preselected and neither is styled as "recommended" — both buttons are
                // the same weight, in the order Play returns them.
                state.plans.forEach { plan ->
                    SchismPrimaryButton(
                        onClick = { viewModel.purchase(activity, plan) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(plan.priceLabel())
                    }
                }
                Text(
                    stringResource(R.string.plus_renewal_terms),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.plus_not_now)) }
                TextButton(onClick = viewModel::restore, enabled = !state.busy) {
                    Text(stringResource(R.string.plus_restore))
                }
            }
        }
    }
}

@Composable
private fun Benefit(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Play's own localized price plus the renewal period it belongs to. */
@Composable
private fun PlusPlan.priceLabel(): String = when (billingPeriod) {
    "P1M" -> stringResource(R.string.plus_per_month, formattedPrice)
    "P1Y" -> stringResource(R.string.plus_per_year, formattedPrice)
    else -> stringResource(R.string.plus_per_period, formattedPrice, billingPeriod)
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
