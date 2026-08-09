@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.schism.split.settings

import ai.schism.split.BuildConfig
import ai.schism.split.R
import ai.schism.split.core.ads.AdSlotViewModel
import ai.schism.split.core.theme.ThemeMode
import ai.schism.split.export.ExportViewModel
import ai.schism.split.plus.PlusSheet
import ai.schism.split.plus.PlusViewModel
import ai.schism.split.core.ui.CurrencyPicker
import ai.schism.split.core.ui.InitialAvatar
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.core.ui.SchismSecondaryButton
import ai.schism.split.core.update.GITHUB_REPO_URL
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onReplayTour: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val currentNotes by viewModel.currentNotes.collectAsState()
    val context = LocalContext.current
    val smsPermissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.READ_SMS] == true &&
            grants[Manifest.permission.RECEIVE_SMS] == true
        viewModel.setSmsImportEnabled(granted)
    }

    // Local edit buffers seeded from the persisted state; re-seed when the source changes.
    var name by remember { mutableStateOf(state.profileName) }
    var email by remember { mutableStateOf(state.email) }
    var phone by remember { mutableStateOf(state.phone) }
    var symbol by remember { mutableStateOf(state.currencySymbol) }
    var code by remember { mutableStateOf(state.currencyCode) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmSmsEnable by remember { mutableStateOf(false) }
    var confirmSmsDelete by remember { mutableStateOf(false) }

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
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
                        SchismPrimaryButton(
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
                SchismPrimaryButton(
                    onClick = { viewModel.saveDefaultCurrency(symbol, code) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save currency") }
            }

            // ── About ──────────────────────────────────────────────────────
            SettingsSection("About") {
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        InfoRow("Groups joined", state.groupCount.toString())
                        HorizontalDivider()
                        InfoRow("Identity", if (state.registered) "Registered" else "Local")
                        HorizontalDivider()
                        InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    }
                }
                currentNotes?.let { notes ->
                    Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "WHAT'S NEW IN ${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    SchismSecondaryButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Source code") }
                    SchismSecondaryButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$GITHUB_REPO_URL/releases")))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Release history") }
                }
                UpdateSection(updateState, onCheck = viewModel::checkForUpdates)
            }

            // ── On-device AI ───────────────────────────────────────────────
            AiSection()

            SettingsSection("Bank message import") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic transaction import", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Optional. Supported transaction messages are read and parsed only on this device. " +
                                "Only expenses you choose to share are sent to a group.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = state.smsImportEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) confirmSmsEnable = true else viewModel.setSmsImportEnabled(false)
                        },
                    )
                }
                Text(
                    if (state.smsImportEnabled) {
                        "Enabled. Android SMS permission is requested separately in Inbox and can be revoked in system settings."
                    } else {
                        "Disabled. Schism will not read or scan bank messages. Existing imported transactions are kept."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.smsImportEnabled) {
                    SchismSecondaryButton(
                        onClick = {
                            viewModel.setSmsImportEnabled(false)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                runCatching {
                                    context.revokeSelfPermissionOnKill(Manifest.permission.READ_SMS)
                                    context.revokeSelfPermissionOnKill(Manifest.permission.RECEIVE_SMS)
                                }.onFailure { context.openSchismAppSettings() }
                            } else {
                                context.openSchismAppSettings()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Disable and revoke Android permission") }
                }
                SchismSecondaryButton(
                    onClick = { confirmSmsDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete imported message transactions") }
            }

            // ── Guided tour ────────────────────────────────────────────────
            SettingsSection("Guided tour") {
                ai.schism.split.walkthrough.WalkthroughSettingsSection(onReplayTour = onReplayTour)
            }

            // Labs removed: Live Split claiming is a shipped feature, not an opt-in experiment.

            // ── Schism Plus ────────────────────────────────────────────────
            PlusSection()

            // ── Export ─────────────────────────────────────────────────────
            ExportSection()

            // ── Account ────────────────────────────────────────────────────
            AccountSection()

            // ── Data ───────────────────────────────────────────────────────
            SettingsSection("Data") {
                SchismSecondaryButton(
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
    if (confirmSmsEnable) {
        AlertDialog(
            onDismissRequest = { confirmSmsEnable = false },
            title = { Text("Enable bank message import?") },
            text = {
                Text(
                    "Schism will read transaction SMS after you separately grant Android permission. " +
                        "Parsing stays on this phone; raw messages are never uploaded. Manual and receipt entry work without it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSmsEnable = false
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                        PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        viewModel.setSmsImportEnabled(true)
                    } else {
                        smsPermissionLauncher.launch(
                            arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
                        )
                    }
                }) { Text("Enable") }
            },
            dismissButton = { TextButton(onClick = { confirmSmsEnable = false }) { Text("Not now") } },
        )
    }
    if (confirmSmsDelete) {
        AlertDialog(
            onDismissRequest = { confirmSmsDelete = false },
            title = { Text("Delete imported transactions?") },
            text = {
                Text(
                    "This permanently deletes transactions created from bank messages on this device. " +
                        "Receipt scans and expenses already shared with groups are not deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteImportedSmsData()
                    confirmSmsDelete = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSmsDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun android.content.Context.openSchismAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
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

/**
 * Subscription status plus the two controls Play requires to be reachable: Restore purchases and
 * Manage subscription. Also hosts the UMP "Privacy choices" entry, shown only where it's required.
 */
@Composable
private fun PlusSection(viewModel: PlusViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }

    if (showSheet) PlusSheet(onDismiss = { showSheet = false })

    // Before Plus is live there is nothing to buy, restore or manage. Showing "Restore purchases"
    // and "Manage subscription" anyway would be three dead ends and a purchase surface Play review
    // would reasonably question. Anyone who already holds Plus keeps the controls.
    if (!state.plusEnabled && !state.isPlus) {
        PrivacyChoicesSection()
        return
    }

    SettingsSection(stringResource(R.string.plus_title)) {
        Text(
            when {
                state.cancelledButActive -> stringResource(R.string.plus_active_until, state.expiresAt)
                state.isPlus -> stringResource(R.string.plus_active)
                else -> stringResource(R.string.plus_free_plan)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        state.allowance?.let {
            Text(
                stringResource(R.string.live_split_allowance_left, it.remaining, it.limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!state.isPlus && state.plusEnabled) {
            SchismSecondaryButton(onClick = { showSheet = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plus_see_options))
            }
        }
        SchismSecondaryButton(
            onClick = viewModel::restore,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.plus_restore)) }
        SchismSecondaryButton(
            onClick = { context.startActivity(viewModel.manageSubscriptionIntent()) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.plus_manage)) }

        PrivacyChoicesRow()
    }
}

/** Ads consent lives outside the Plus block, so it survives Plus being switched off. */
@Composable
private fun PrivacyChoicesSection(adSlot: AdSlotViewModel = hiltViewModel()) {
    val required by adSlot.consent.privacyOptionsRequired.collectAsState()
    if (!required) return
    SettingsSection(stringResource(R.string.ads_privacy_choices)) { PrivacyChoicesRow() }
}

@Composable
private fun PrivacyChoicesRow(adSlot: AdSlotViewModel = hiltViewModel()) {
    val consent = adSlot.consent
    val required by consent.privacyOptionsRequired.collectAsState()
    val context = LocalContext.current
    if (!required) return
    SchismSecondaryButton(
        onClick = { (context as? android.app.Activity)?.let(consent::showPrivacyOptions) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.ads_privacy_choices)) }
    Text(
        stringResource(R.string.ads_privacy_choices_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** CSV/PDF export of the on-device ledger. A Plus convenience — the records themselves stay free. */
@Composable
private fun ExportSection(viewModel: ExportViewModel = hiltViewModel()) {
    val plus by viewModel.plus.collectAsState()
    val share by viewModel.share.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(share) {
        share?.let {
            context.startActivity(it)
            viewModel.consumeShare()
        }
    }

    SettingsSection(stringResource(R.string.plus_export_title)) {
        if (!plus) {
            Text(
                stringResource(R.string.plus_export_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SchismSecondaryButton(onClick = viewModel::exportCsv, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plus_export_csv))
            }
            SchismSecondaryButton(onClick = viewModel::exportPdf, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plus_export_pdf))
            }
        }
        if (failed) {
            Text(
                stringResource(R.string.plus_export_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AccountSection(viewModel: AccountSettingsViewModel = hiltViewModel()) {
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    SettingsSection("Account") {
        SchismSecondaryButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Log out")
        }
        SchismSecondaryButton(
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
                LinearWavyProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                InfoRow("Downloading", "${s.percent}%  ·  keeps going if you leave")
            }
            is ai.schism.split.core.ai.ModelManager.State.Ready ->
                InfoRow("Model", "Ready" + (viewModel.sizeMb()?.let { " · $it MB" } ?: ""))
            is ai.schism.split.core.ai.ModelManager.State.Failed ->
                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                downloading -> SchismSecondaryButton(onClick = viewModel::cancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                else -> SchismPrimaryButton(onClick = viewModel::download, modifier = Modifier.weight(1f)) {
                    Text(if (ready) "Re-download" else "Download model")
                }
            }
            if (ready && !downloading) {
                SchismSecondaryButton(onClick = viewModel::delete, modifier = Modifier.weight(1f)) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdateSection(updateState: UpdateState, onCheck: () -> Unit) {
    val context = LocalContext.current
    when (updateState) {
        is UpdateState.Idle -> {
            SchismPrimaryButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
        }
        is UpdateState.Checking -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LoadingIndicator(modifier = Modifier.size(16.dp))
                Text("Checking for updates…", style = MaterialTheme.typography.bodySmall)
            }
        }
        is UpdateState.UpToDate -> {
            Text(
                "You're on the latest version ✓",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SchismSecondaryButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
        }
        is UpdateState.Available -> {
            Text(
                "Update available: ${updateState.release.versionName}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SchismPrimaryButton(
                onClick = {
                    val url = updateState.release.apkUrl ?: updateState.release.releaseUrl
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download") }
        }
        is UpdateState.Failed -> {
            Text(
                "Couldn't check — try again",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            SchismSecondaryButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
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
