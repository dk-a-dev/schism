package ai.schism.split.settings

import ai.schism.split.BuildConfig
import ai.schism.split.core.theme.ThemeMode
import ai.schism.split.core.ui.CurrencyPicker
import ai.schism.split.core.ui.InitialAvatar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
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
    var email by remember { mutableStateOf(state.email) }
    var phone by remember { mutableStateOf(state.phone) }
    var symbol by remember { mutableStateOf(state.currencySymbol) }
    var code by remember { mutableStateOf(state.currencyCode) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(state.profileName) { name = state.profileName }
    LaunchedEffect(state.email) { email = state.email }
    LaunchedEffect(state.phone) { phone = state.phone }
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
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // ── Profile ────────────────────────────────────────────────────
            SettingsSection("Profile", initiallyExpanded = true) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            InitialAvatar(name = name.ifBlank { "?" }, size = 52.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name.ifBlank { "You" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (state.registered) "Synced with backend" else "This device only",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Phone") },
                            leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { viewModel.saveProfile(name, email, phone) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Save profile") }
                    }
                }
            }

            // ── Appearance ─────────────────────────────────────────────────
            SettingsSection("Appearance") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = ThemeMode.entries
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.themeMode == mode.name,
                            onClick = { viewModel.saveThemeMode(mode.name) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(mode.label) }
                    }
                }
            }

            // ── Default currency ───────────────────────────────────────────
            SettingsSection("Default currency") {
                Text(
                    "Used for new groups. Current: ${state.currencySymbol} · ${state.currencyCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CurrencyPicker(
                    symbol = symbol,
                    code = code,
                    onPick = { s, c -> symbol = s; code = c },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.saveDefaultCurrency(symbol, code) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save currency") }
            }

            // ── About ──────────────────────────────────────────────────────
            SettingsSection("About") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        InfoRow("Groups joined", state.groupCount.toString())
                        HorizontalDivider()
                        InfoRow("Identity", if (state.registered) "Registered" else "Local")
                        HorizontalDivider()
                        InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    }
                }
            }

            // ── On-device AI ───────────────────────────────────────────────
            AiSection()

            // ── Account ────────────────────────────────────────────────────
            AccountSection()

            // ── Data ───────────────────────────────────────────────────────
            SettingsSection("Data") {
                OutlinedButton(
                    onClick = { confirmReset = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset app data") }
                Text(
                    "Clears your profile, currency, theme and joined groups on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset app data?") },
            text = { Text("This removes your profile, preferences and the list of groups you've joined on this device. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAll()
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun AccountSection(viewModel: AccountSettingsViewModel = hiltViewModel()) {
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    SettingsSection("Account") {
        OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Log out")
        }
        OutlinedButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("Delete account") }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("You'll return to sign-in. Your account and groups stay on the server.") },
            confirmButton = { TextButton(onClick = { viewModel.logout(); confirmLogout = false }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete account?") },
            text = { Text("This permanently deletes your account on the server and clears this device. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAccount(); confirmDelete = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AiSection(viewModel: AiSettingsViewModel = hiltViewModel()) {
    val state by viewModel.modelState.collectAsState()
    val enabled by viewModel.aiEnabled.collectAsState()

    SettingsSection("On-device AI") {
        Text(
            "Use a small on-device LLM (Qwen 2.5) for smarter voice & receipt parsing instead of " +
                "keyword rules. It's optional and runs fully offline once downloaded (~1.5 GB). The " +
                "download continues in the background even if you close the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Use on-device AI", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.Switch(checked = enabled, onCheckedChange = viewModel::setEnabled)
        }

        val downloading = state is ai.schism.split.core.ai.ModelManager.State.Downloading
        val ready = state is ai.schism.split.core.ai.ModelManager.State.Ready
        when (val s = state) {
            is ai.schism.split.core.ai.ModelManager.State.Absent -> InfoRow("Model", "Not downloaded")
            is ai.schism.split.core.ai.ModelManager.State.Downloading -> {
                LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                InfoRow("Downloading", "${s.percent}%  ·  keeps going if you leave")
            }
            is ai.schism.split.core.ai.ModelManager.State.Ready ->
                InfoRow("Model", "Ready" + (viewModel.sizeMb()?.let { " · $it MB" } ?: ""))
            is ai.schism.split.core.ai.ModelManager.State.Failed ->
                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                downloading -> OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                else -> Button(onClick = viewModel::download, modifier = Modifier.weight(1f)) {
                    Text(if (ready) "Re-download" else "Download model")
                }
            }
            if (ready && !downloading) {
                OutlinedButton(onClick = viewModel::delete, modifier = Modifier.weight(1f)) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
