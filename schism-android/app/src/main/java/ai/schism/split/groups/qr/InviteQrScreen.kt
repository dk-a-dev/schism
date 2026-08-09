@file:OptIn(ExperimentalMaterial3Api::class)

package ai.schism.split.groups.qr

import ai.schism.split.R
import ai.schism.split.core.ui.MorphLoader
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.core.ui.SchismSecondaryButton
import ai.schism.split.groups.invite.shareInviteLink
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Invite one person: pick the participant they are in this group, then share the one-time link/QR
 * minted for them. Nothing is shown until a participant is chosen, because a link is bound to one.
 */
@Composable
fun InviteQrScreen(
    onBack: () -> Unit,
    viewModel: InviteQrViewModel = hiltViewModel(),
) {
    val group by viewModel.group.collectAsState()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val groupName = group?.name ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.invite_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(groupName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)

            when (val s = state) {
                is InviteLinkState.Creating -> {
                    MorphLoader()
                    Hint(stringResource(R.string.invite_creating))
                }

                is InviteLinkState.Failed -> {
                    Hint(stringResource(s.messageRes))
                    SchismPrimaryButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.invite_new_link))
                    }
                }

                is InviteLinkState.Ready -> {
                    val qr = remember(s.link) { qrBitmap(s.link).asImageBitmap() }
                    Hint(stringResource(R.string.invite_ready, s.participant.name))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Image(
                            bitmap = qr,
                            contentDescription = stringResource(R.string.invite_qr_description),
                            modifier = Modifier.padding(20.dp).size(240.dp),
                        )
                    }
                    SchismPrimaryButton(
                        onClick = { shareInviteLink(context, s.link, groupName, s.participant.name) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Text("  " + stringResource(R.string.invite_share))
                    }
                    SchismSecondaryButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.invite_new_link))
                    }
                }

                is InviteLinkState.Idle -> {
                    val unlinked = viewModel.unlinked(group)
                    if (unlinked.isEmpty()) {
                        Hint(stringResource(R.string.invite_everyone_joined))
                    } else {
                        Hint(stringResource(R.string.invite_pick_participant))
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(unlinked, key = { it.id }) { participant ->
                                ListItem(
                                    headlineContent = { Text(participant.name) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.createInvite(participant) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
