package ai.schism.split.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ai.schism.split.core.theme.ThemeMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Local edit buffers seeded from the persisted state; re-seed when the source changes.
    var name by remember { mutableStateOf(state.profileName) }
    var symbol by remember { mutableStateOf(state.currencySymbol) }
    var code by remember { mutableStateOf(state.currencyCode) }

    LaunchedEffect(state.profileName) { name = state.profileName }
    LaunchedEffect(state.currencySymbol, state.currencyCode) {
        symbol = state.currencySymbol
        code = state.currencyCode
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Appearance / theme.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = ThemeMode.entries
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.themeMode == mode.name,
                            onClick = { viewModel.saveThemeMode(mode.name) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) {
                            Text(mode.label)
                        }
                    }
                }
            }

            // Headline: the current app-wide default currency.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Default currency", style = MaterialTheme.typography.titleMedium)
                    Text("${state.currencySymbol}  ·  ${state.currencyCode}", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Used for new groups.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Profile name.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your name", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Used to resolve \"you\" in groups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { viewModel.saveProfileName(name) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save name")
                }
            }

            HorizontalDivider()

            // Default currency editor.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Change default currency", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = symbol,
                        onValueChange = { symbol = it },
                        label = { Text("Symbol") },
                        placeholder = { Text("₹") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("ISO code") },
                        placeholder = { Text("INR") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(onClick = { viewModel.saveDefaultCurrency(symbol, code) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save currency")
                }
            }
        }
    }
}
