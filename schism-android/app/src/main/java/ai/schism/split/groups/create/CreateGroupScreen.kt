@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package ai.schism.split.groups.create

import ai.schism.split.core.ui.CurrencyPicker
import ai.schism.split.core.ui.InitialAvatar
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun CreateGroupScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: CreateGroupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val form = state.form

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New group") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Details") {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text("Group name") },
                    isError = state.nameError != null,
                    supportingText = state.nameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                CurrencyPicker(
                    symbol = form.currency,
                    code = form.currencyCode,
                    onPick = { s, c -> viewModel.onCurrencyChange(s, c) },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = form.information,
                    onValueChange = viewModel::onInformationChange,
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(title = "Participants") {
                form.participants.forEachIndexed { index, name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        InitialAvatar(
                            name = name.ifBlank { "?" },
                            key = index.toString(),
                            size = 40.dp,
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { viewModel.onParticipantChange(index, it) },
                            label = { Text("Name") },
                            singleLine = true,
                            isError = state.participantsError != null,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { viewModel.removeParticipant(index) },
                            enabled = form.participants.size > 1,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove participant")
                        }
                    }
                }

                state.participantsError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                val context = LocalContext.current
                // Pick a PHONE entry (not just a contact) so we get name + number in one tap with no
                // READ_CONTACTS permission; the number lets the backend auto-link the friend when
                // they join and powers the SMS invite after the group is created.
                val pickContact = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    result.data?.data?.let { uri ->
                        contactNameAndPhone(context, uri)?.let { (name, phone) ->
                            viewModel.addParticipantFromContact(name, phone)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = viewModel::addParticipant,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Add", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            pickContact.launch(
                                android.content.Intent(android.content.Intent.ACTION_PICK).apply {
                                    type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
                                },
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Contacts", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            state.submitError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val inviteContext = LocalContext.current
            Button(
                onClick = {
                    viewModel.submit { groupId ->
                        // Real invites: prefill an SMS to every contact-added member with the link.
                        val phones = viewModel.pendingInvitePhones()
                        if (phones.isNotEmpty()) {
                            sendSmsInvites(inviteContext, phones, viewModel.groupNameForInvite(), groupId)
                        }
                        onCreated(groupId)
                    }
                },
                enabled = !state.submitting,
                shape = MaterialTheme.shapes.large,
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                if (state.submitting) {
                    LoadingIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text("Create group", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Reads the display name + phone number of the phone entry the user picked. The picker grants
 * temporary read access to this one row, so no READ_CONTACTS permission is needed.
 */
private fun contactNameAndPhone(context: Context, uri: Uri): Pair<String, String?>? =
    context.contentResolver
        .query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        )
        ?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            name to c.getString(1)?.takeIf { it.isNotBlank() }
        }

/** Prefill an SMS to every invited number with the group's join link. */
private fun sendSmsInvites(context: Context, phones: List<String>, groupName: String, groupId: String) {
    val link = ai.schism.split.groups.join.JoinGroupViewModel.shareLink(groupId)
    val name = groupName.ifBlank { "our group" }
    val intent = android.content.Intent(
        android.content.Intent.ACTION_SENDTO,
        Uri.parse("smsto:" + phones.joinToString(";")),
    ).apply {
        putExtra("sms_body", "Join \"$name\" on Schism to split our expenses: $link")
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
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
