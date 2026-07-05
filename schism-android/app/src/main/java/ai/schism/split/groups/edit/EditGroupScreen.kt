@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.schism.split.groups.edit

import ai.schism.split.core.ui.WavyProgress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun EditGroupScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: EditGroupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val form = state.form

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit group") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { WavyProgress() }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Group name") },
                singleLine = true,
                isError = state.nameError != null,
                supportingText = state.nameError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.information,
                onValueChange = viewModel::onInformationChange,
                label = { Text("Note (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    label = { Text("ISO code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Participants", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            state.participantsError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            form.participants.forEachIndexed { index, p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = p.name,
                        onValueChange = { viewModel.onParticipantChange(index, it) },
                        label = { Text("Participant ${index + 1}") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.removeParticipant(index) }, enabled = form.participants.size > 1) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove")
                    }
                }
            }
            OutlinedButton(onClick = viewModel::addParticipant, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Add participant")
            }

            state.submitError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { viewModel.submit(onSaved) },
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save changes", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
