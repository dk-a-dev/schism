package ai.schism.split.settings

import ai.schism.split.R
import ai.schism.split.core.billing.EntitlementRepository
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.core.ui.SchismSecondaryButton
import ai.schism.split.sms.receipt.cloud.ReceiptEngine
import ai.schism.split.sms.receipt.cloud.ReceiptProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReceiptEngineViewModel @Inject constructor(
    private val settings: SettingsRepository,
    entitlements: EntitlementRepository,
) : ViewModel() {

    val engine: StateFlow<ReceiptEngine> = settings.receiptEngine
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptEngine.ON_DEVICE)

    val provider: StateFlow<ReceiptProvider> = settings.receiptProvider
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptProvider.GEMINI)

    val keyPresent: StateFlow<Boolean> = settings.receiptApiKeyPresent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Same backend switch mechanism as ads/Plus — the option isn't offered until the server says so. */
    val schismCloudOffered: StateFlow<Boolean> = entitlements.config
        .map { it.receiptCloudEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val consents: StateFlow<Set<String>> = settings.receiptCloudConsents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** True when [engine] may be selected without showing the disclosure sheet again. */
    fun alreadyConsented(engine: ReceiptEngine): Boolean =
        !engine.leavesDevice || consents.value.contains(engine.name)

    /**
     * Applies a choice. A cloud engine is refused unless consent has been granted first — the sheet
     * is the only thing that can grant it, so the "off-device" state is unreachable by accident.
     */
    fun select(choice: ReceiptEngine) {
        if (choice.leavesDevice && !alreadyConsented(choice)) return
        viewModelScope.launch { settings.setReceiptEngine(choice) }
    }

    /** Records the accepted disclosure, then switches. Only ever called from the sheet's accept button. */
    fun acceptConsentAndSelect(choice: ReceiptEngine) {
        viewModelScope.launch {
            settings.grantReceiptCloudConsent(choice)
            settings.setReceiptEngine(choice)
        }
    }

    fun setProvider(choice: ReceiptProvider) {
        viewModelScope.launch { settings.setReceiptProvider(choice) }
    }

    fun saveKey(key: String) = settings.setReceiptApiKey(key)

    /** Clearing the key also drops back to on-device — an own-key engine with no key can't work. */
    fun clearKey() {
        settings.clearReceiptApiKey()
        if (engine.value == ReceiptEngine.OWN_KEY) select(ReceiptEngine.ON_DEVICE)
    }
}

/**
 * The "Receipt reading" settings body: the three-way engine choice, and — only for
 * [ReceiptEngine.OWN_KEY] — a provider picker and a key field. Selecting either cloud engine opens
 * the disclosure sheet first; nothing is switched until it is accepted.
 */
@Composable
fun ReceiptEngineSettingsSection(viewModel: ReceiptEngineViewModel = hiltViewModel()) {
    val engine by viewModel.engine.collectAsState()
    val provider by viewModel.provider.collectAsState()
    val keyPresent by viewModel.keyPresent.collectAsState()
    val schismOffered by viewModel.schismCloudOffered.collectAsState()
    var pendingConsent by remember { mutableStateOf<ReceiptEngine?>(null) }
    var key by remember { mutableStateOf("") }

    // The Schism-cloud option only exists when the backend switches it on — but a user who is
    // already on it keeps the control, so the switch flipping off can't strand them.
    val choices = ReceiptEngine.entries.filter {
        it != ReceiptEngine.SCHISM_CLOUD || schismOffered || engine == ReceiptEngine.SCHISM_CLOUD
    }

    Text(
        stringResource(R.string.receiptai_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = engine == choice,
                onClick = {
                    if (viewModel.alreadyConsented(choice)) viewModel.select(choice) else pendingConsent = choice
                },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
            ) { Text(choice.label()) }
        }
    }
    Text(
        stringResource(
            when (engine) {
                ReceiptEngine.ON_DEVICE -> R.string.receiptai_engine_on_device_body
                ReceiptEngine.SCHISM_CLOUD -> R.string.receiptai_engine_schism_body
                ReceiptEngine.OWN_KEY -> R.string.receiptai_engine_own_key_body
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (engine == ReceiptEngine.OWN_KEY) {
        Text(stringResource(R.string.receiptai_provider), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ReceiptProvider.entries.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = provider == choice,
                    onClick = { viewModel.setProvider(choice) },
                    shape = SegmentedButtonDefaults.itemShape(index, ReceiptProvider.entries.size),
                ) { Text(choice.label()) }
            }
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.receiptai_key_label)) },
            singleLine = true,
            // The stored key is never read back into this field; masking stops shoulder-surfing while typing.
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(if (keyPresent) R.string.receiptai_key_saved else R.string.receiptai_key_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SchismPrimaryButton(
                onClick = { viewModel.saveKey(key); key = "" },
                enabled = key.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.receiptai_key_save)) }
            if (keyPresent) {
                SchismSecondaryButton(
                    onClick = { viewModel.clearKey(); key = "" },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.receiptai_key_clear)) }
            }
        }
        Text(
            stringResource(R.string.receiptai_key_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    pendingConsent?.let { choice ->
        CloudConsentSheet(
            engine = choice,
            provider = provider,
            onDismiss = { pendingConsent = null },
            onAccept = { viewModel.acceptConsentAndSelect(choice); pendingConsent = null },
        )
    }
}

/**
 * The one-time disclosure before a receipt photo may ever leave the device: what happens, which
 * company receives it, and that on-device stays available. Dismissing it changes nothing — the only
 * way out that switches engines is the explicit accept button, and no box is pre-ticked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudConsentSheet(
    engine: ReceiptEngine,
    provider: ReceiptProvider,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
) {
    val recipient = when {
        engine == ReceiptEngine.SCHISM_CLOUD -> R.string.receiptai_consent_recipient_schism
        provider == ReceiptProvider.GEMINI -> R.string.receiptai_consent_recipient_gemini
        else -> R.string.receiptai_consent_recipient_groq
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.receiptai_consent_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.receiptai_consent_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(recipient), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.receiptai_consent_reversible),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SchismPrimaryButton(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.receiptai_consent_accept))
            }
            SchismSecondaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.receiptai_consent_decline))
            }
        }
    }
}

@Composable
private fun ReceiptEngine.label(): String = stringResource(
    when (this) {
        ReceiptEngine.ON_DEVICE -> R.string.receiptai_engine_on_device
        ReceiptEngine.SCHISM_CLOUD -> R.string.receiptai_engine_schism
        ReceiptEngine.OWN_KEY -> R.string.receiptai_engine_own_key
    },
)

@Composable
private fun ReceiptProvider.label(): String = stringResource(
    when (this) {
        ReceiptProvider.GEMINI -> R.string.receiptai_provider_gemini
        ReceiptProvider.GROQ -> R.string.receiptai_provider_groq
    },
)
