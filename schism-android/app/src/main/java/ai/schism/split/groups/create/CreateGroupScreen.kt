@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package ai.schism.split.groups.create

import ai.schism.split.core.ui.CurrencyPicker
import ai.schism.split.core.ui.InitialAvatar
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.core.ui.SchismSecondaryButton
import ai.schism.split.groups.sendSmsInvites
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
                    SchismSecondaryButton(
                        onClick = viewModel::addParticipant,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Add", modifier = Modifier.padding(start = 8.dp))
                    }
                    SchismSecondaryButton(
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
            SchismPrimaryButton(
                onClick = {
                    viewModel.submit { groupId ->
                        // Nudge every contact-added member by SMS; their personal invite link is
                        // minted per participant from the invite screen.
                        val phones = viewModel.pendingInvitePhones()
                        if (phones.isNotEmpty()) {
                            sendSmsInvites(inviteContext, phones, viewModel.groupNameForInvite())
                        }
                        onCreated(groupId)
                    }
                },
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) {
                    LoadingIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text("Create group")
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
            shape = MaterialTheme.shapes.large,
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
