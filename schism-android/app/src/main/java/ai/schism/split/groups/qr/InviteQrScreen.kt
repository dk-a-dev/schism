@file:OptIn(ExperimentalMaterial3Api::class)

package ai.schism.split.groups.qr

import ai.schism.split.R
import ai.schism.split.core.ui.MorphLoader
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.core.ui.SchismSecondaryButton
import ai.schism.split.groups.data.Participant
import ai.schism.split.groups.invite.shareGroupInviteLink
import ai.schism.split.groups.invite.shareInviteLink
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * Invite to a group. The shareable group link is up and ready the moment the screen opens — anyone
 * who opens it joins as themselves, so nobody has to be entered in advance. Binding one specific
 * person to a name already in the group stays available underneath it.
 */
@Composable
fun InviteQrScreen(
    onBack: () -> Unit,
    viewModel: InviteQrViewModel = hiltViewModel(),
) {
    val group by viewModel.group.collectAsState()
    val linkState by viewModel.linkState.collectAsState()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val groupName = group?.name ?: ""
    var showPerPerson by rememberSaveable { mutableStateOf(false) }

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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(groupName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)

            GroupLink(viewModel, linkState, groupName, context)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (showPerPerson) {
                PerPersonInvite(viewModel, state, viewModel.unlinked(group), groupName, context)
            } else {
                SchismSecondaryButton(
                    onClick = { showPerPerson = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.invite_person_open))
                }
            }
        }
    }
}

/** Primary path: one link for the whole group, redeemable by whoever holds it. */
@Composable
private fun ColumnScope.GroupLink(
    viewModel: InviteQrViewModel,
    state: GroupLinkState,
    groupName: String,
    context: Context,
) {
    when (state) {
        is GroupLinkState.Creating -> {
            MorphLoader()
            Hint(stringResource(R.string.invite_creating))
        }

        is GroupLinkState.Ready -> {
            Hint(stringResource(R.string.invite_group_link_hint))
            QrCard(state.link)
            SchismPrimaryButton(
                onClick = { shareGroupInviteLink(context, state.link, groupName) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text("  " + stringResource(R.string.invite_group_share))
            }
            SchismSecondaryButton(
                onClick = viewModel::createGroupLink,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.invite_group_regenerate))
            }
            TextButton(onClick = viewModel::revokeGroupLink) {
                Text(stringResource(R.string.invite_group_revoke))
            }
        }

        is GroupLinkState.Revoked -> {
            Hint(stringResource(R.string.invite_group_revoked))
            RegenerateButton(viewModel)
        }

        is GroupLinkState.Failed -> {
            Hint(stringResource(state.messageRes))
            RegenerateButton(viewModel)
        }
    }
}

@Composable
private fun RegenerateButton(viewModel: InviteQrViewModel) {
    SchismPrimaryButton(onClick = viewModel::createGroupLink, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.invite_group_regenerate))
    }
}

/** Secondary path: bind one already-named participant to whoever redeems their one-time link. */
@Composable
private fun ColumnScope.PerPersonInvite(
    viewModel: InviteQrViewModel,
    state: InviteLinkState,
    unlinked: List<Participant>,
    groupName: String,
    context: Context,
) {
    when (state) {
        is InviteLinkState.Creating -> {
            MorphLoader()
            Hint(stringResource(R.string.invite_creating))
        }

        is InviteLinkState.Failed -> {
            Hint(stringResource(state.messageRes))
            SchismSecondaryButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.invite_new_link))
            }
        }

        is InviteLinkState.Ready -> {
            Hint(stringResource(R.string.invite_ready, state.participant.name))
            QrCard(state.link)
            SchismPrimaryButton(
                onClick = { shareInviteLink(context, state.link, groupName, state.participant.name) },
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
            if (unlinked.isNotEmpty()) {
                Hint(stringResource(R.string.invite_pick_participant))
                // ponytail: a plain forEach inside the scrolling Column — a group's participant list
                // is small; swap in a LazyColumn only if one ever gets long enough to matter.
                unlinked.forEach { participant ->
                    ListItem(
                        headlineContent = { Text(participant.name) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.createInvite(participant) },
                    )
                }
            }
            var newName by rememberSaveable { mutableStateOf("") }
            Hint(stringResource(R.string.invite_add_person_hint))
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                label = { Text(stringResource(R.string.invite_add_person_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            SchismSecondaryButton(
                onClick = {
                    viewModel.invitePerson(newName)
                    newName = ""
                },
                enabled = newName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.invite_add_person_action)) }
        }
    }
}

@Composable
private fun QrCard(link: String) {
    val qr = remember(link) { qrBitmap(link).asImageBitmap() }
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
