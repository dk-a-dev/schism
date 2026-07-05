package ai.schism.split.groups.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Group name") },
                isError = state.nameError != null,
                supportingText = state.nameError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.currency,
                    onValueChange = { viewModel.onCurrencyChange(it, form.currencyCode) },
                    label = { Text("Symbol") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp),
                )
                OutlinedTextField(
                    value = form.currencyCode,
                    onValueChange = { viewModel.onCurrencyChange(form.currency, it) },
                    label = { Text("Currency code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = form.information,
                onValueChange = viewModel::onInformationChange,
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Participants", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            form.participants.forEachIndexed { index, name ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { viewModel.onParticipantChange(index, it) },
                        label = { Text("Name") },
                        singleLine = true,
                        isError = state.participantsError != null,
                        modifier = Modifier.fillMaxWidth().weight(1f),
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
                Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = viewModel::addParticipant) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add participant")
            }

            state.submitError?.let {
                Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { viewModel.submit(onCreated) },
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp))
                } else {
                    Text("Create group")
                }
            }
        }
    }
}
