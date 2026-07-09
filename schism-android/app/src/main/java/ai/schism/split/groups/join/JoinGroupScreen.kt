package ai.schism.split.groups.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import ai.schism.split.groups.qr.scanQrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun JoinGroupScreen(
    onBack: () -> Unit,
    onJoined: (String) -> Unit,
    viewModel: JoinGroupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val joining = state is JoinState.Joining

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join a group") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Paste an invite link (schism://group/…) or enter a group ID.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Invite link or group ID") },
                singleLine = true,
                isError = state is JoinState.Error,
                supportingText = (state as? JoinState.Error)?.let { { Text(it.message) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.join(input, onJoined) },
                enabled = !joining && input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (joining) LoadingIndicator(modifier = Modifier.padding(4.dp).size(20.dp)) else Text("Join")
            }
            OutlinedButton(
                onClick = {
                    scanQrCode(context) { value ->
                        val id = value?.let { JoinGroupViewModel.parseGroupId(it) }
                        if (id.isNullOrBlank()) {
                            viewModel.onScanError()
                        } else {
                            onJoined(id)
                        }
                    }
                },
                enabled = !joining,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Text("  Scan QR code")
            }
        }
    }
}
