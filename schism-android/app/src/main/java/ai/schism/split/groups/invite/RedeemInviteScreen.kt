package ai.schism.split.groups.invite

import ai.schism.split.R
import ai.schism.split.core.ui.MorphLoader
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.core.ui.SchismSecondaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Landing screen for `schism://invite/<token>`. Shows only who the invite makes you, in which group,
 * until the user confirms; every failure carries its own explicit message.
 */
@Composable
fun RedeemInviteScreen(
    onJoined: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: RedeemInviteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                is RedeemState.WaitingForSignIn -> {
                    MorphLoader()
                    Message(stringResource(R.string.redeem_waiting_sign_in))
                }

                is RedeemState.Loading, is RedeemState.Redeeming -> {
                    MorphLoader()
                    Message(stringResource(R.string.redeem_loading))
                }

                is RedeemState.Preview -> {
                    Text(
                        stringResource(R.string.redeem_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Message(stringResource(R.string.redeem_prompt, s.groupName, s.participantName))
                    SchismPrimaryButton(
                        onClick = { viewModel.confirm(onJoined) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.redeem_confirm, s.participantName))
                    }
                    SchismSecondaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.redeem_cancel))
                    }
                }

                is RedeemState.Failed -> {
                    Text(
                        stringResource(R.string.redeem_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Message(stringResource(s.messageRes))
                    SchismPrimaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.redeem_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
