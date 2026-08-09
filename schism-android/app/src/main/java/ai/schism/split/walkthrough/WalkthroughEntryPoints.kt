package ai.schism.split.walkthrough

import ai.schism.split.R
import ai.schism.split.core.ui.SchismSecondaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Post-auth offer. Drop it inside the signed-in scaffold; [onAccept] should navigate to
 * [WalkthroughRoutes.DEMO]. Declining or dismissing skips the tour permanently for this account.
 */
@Composable
fun WalkthroughOffer(
    onAccept: () -> Unit,
    viewModel: WalkthroughViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.status) {
        if (state.status == WalkthroughStatus.ELIGIBLE) viewModel.offerTour()
    }
    if (state.status != WalkthroughStatus.OFFERED) return

    AlertDialog(
        onDismissRequest = viewModel::skipTour,
        title = { Text(stringResource(R.string.walkthrough_offer_title)) },
        text = { Text(stringResource(R.string.walkthrough_offer_body)) },
        confirmButton = {
            TextButton(onClick = { viewModel.acceptTour(); onAccept() }) {
                Text(stringResource(R.string.walkthrough_offer_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::skipTour) {
                Text(stringResource(R.string.walkthrough_offer_decline))
            }
        },
    )
}

/**
 * Settings block for replaying the tour and un-dismissing the one-time tips. Drop it inside a
 * Settings section; [onReplayTour] should navigate to [WalkthroughRoutes.DEMO].
 */
@Composable
fun WalkthroughSettingsSection(
    onReplayTour: () -> Unit,
    onResetTips: () -> Unit = {},
    viewModel: WalkthroughViewModel = hiltViewModel(),
) {
    var confirmReplay by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.walkthrough_settings_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SchismSecondaryButton(
            onClick = { confirmReplay = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.walkthrough_settings_replay)) }
        SchismSecondaryButton(
            onClick = { confirmReset = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.walkthrough_settings_reset_tips)) }
    }

    if (confirmReplay) {
        ConfirmDialog(
            titleRes = R.string.walkthrough_settings_replay_confirm_title,
            bodyRes = R.string.walkthrough_settings_replay_confirm_body,
            confirmRes = R.string.walkthrough_settings_replay,
            onDismiss = { confirmReplay = false },
            onConfirm = {
                confirmReplay = false
                viewModel.replayTour()
                onReplayTour()
            },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            titleRes = R.string.walkthrough_settings_reset_confirm_title,
            bodyRes = R.string.walkthrough_settings_reset_confirm_body,
            confirmRes = R.string.walkthrough_settings_reset_tips,
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                viewModel.resetTips()
                onResetTips()
            },
        )
    }
}

@Composable
private fun ConfirmDialog(
    titleRes: Int,
    bodyRes: Int,
    confirmRes: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(bodyRes)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirmRes)) } },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.walkthrough_settings_cancel))
            }
        },
    )
}
