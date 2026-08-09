package ai.schism.split.core.settings

import android.content.Context
import ai.schism.split.core.security.SecureTokenStore
import ai.schism.split.sms.receipt.cloud.ReceiptEngine
import ai.schism.split.sms.receipt.cloud.ReceiptProvider
import ai.schism.split.sms.ingest.SmsIngestWorker
import ai.schism.split.sms.settings.SmsImportPreference
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

/**
 * Device-local settings: the device profile name (used to resolve "you"), the app-wide default
 * currency, and the set of group ids this device has joined/created. The backend URL is NOT here —
 * it comes from build/env config (see [ai.schism.split.core.net.BackendUrlProvider]).
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val ds = context.dataStore
    private val appContext = context.applicationContext
    private val smsPreference = SmsImportPreference(appContext)
    private val secureTokenStore = SecureTokenStore(appContext)
    private val receiptKeyStore = SecureTokenStore(appContext, fileName = "secure_receipt_ai", entryKey = "provider_key_v1")
    private val receiptKeyVersion = MutableStateFlow(0L)
    private val tokenVersion = MutableStateFlow(0L)
    private val tokenMigrationMutex = Mutex()

    /** Optional automatic bank-message import. Disabled on every fresh install. */
    val smsImportEnabled: Flow<Boolean> = smsPreference.enabled

    fun setSmsImportEnabled(enabled: Boolean) {
        smsPreference.setEnabled(enabled)
        if (!enabled) WorkManager.getInstance(appContext).cancelAllWorkByTag(SmsIngestWorker.WORK_TAG)
    }

    val profileName: Flow<String> = ds.data.map { it[KEY_NAME] ?: "" }
    /** The backend user id for this device's identity (empty until registered). */
    val userId: Flow<String> = ds.data.map { it[KEY_USER_ID] ?: "" }
    /** Bearer auth token issued at registration (empty until registered). */
    val authToken: Flow<String> = flow {
        migrateLegacyToken()
        emitAll(tokenVersion.map { secureTokenStore.read() })
    }
    val email: Flow<String> = ds.data.map { it[KEY_EMAIL] ?: "" }
    val phone: Flow<String> = ds.data.map { it[KEY_PHONE] ?: "" }
    /** Whether the one-time onboarding (identity capture) has been completed on this device. */
    val onboarded: Flow<Boolean> = ds.data.map { it[KEY_ONBOARDED] ?: false }
    val knownGroupIds: Flow<Set<String>> = ds.data.map { it[KEY_GROUPS] ?: emptySet() }

    /** App-wide default currency for new groups (symbol + ISO code). Defaults to Indian Rupee. */
    val currencySymbol: Flow<String> = ds.data.map { it[KEY_CUR_SYMBOL] ?: DEFAULT_CURRENCY_SYMBOL }
    val currencyCode: Flow<String> = ds.data.map { it[KEY_CUR_CODE] ?: DEFAULT_CURRENCY_CODE }

    /** Preferred UI theme: "SYSTEM" (default), "LIGHT", or "DARK". */
    val themeMode: Flow<String> = ds.data.map { it[KEY_THEME] ?: DEFAULT_THEME_MODE }

    /** URL of the on-device LLM (.task) used to parse voice/receipts; empty until the user sets one. */
    val aiModelUrl: Flow<String> = ds.data.map { it[KEY_AI_MODEL_URL] ?: "" }

    suspend fun setAiModelUrl(url: String) {
        ds.edit { it[KEY_AI_MODEL_URL] = url.trim() }
    }

    /** Optional bearer token for the model download (e.g. a Hugging Face token for gated models). */
    val aiModelToken: Flow<String> = ds.data.map { it[KEY_AI_MODEL_TOKEN] ?: "" }

    suspend fun setAiModelToken(token: String) {
        ds.edit { it[KEY_AI_MODEL_TOKEN] = token.trim() }
    }

    /** Whether on-device AI parsing is used (off by default; needs a downloaded model). */
    val aiEnabled: Flow<Boolean> = ds.data.map { it[KEY_AI_ENABLED] ?: false }

    suspend fun setAiEnabled(enabled: Boolean) {
        ds.edit { it[KEY_AI_ENABLED] = enabled }
    }

    /**
     * Which engine reads receipt photos. Defaults to [ReceiptEngine.ON_DEVICE] — nothing leaves the
     * phone — and an unrecognised stored value falls back to it rather than to a cloud engine.
     */
    val receiptEngine: Flow<ReceiptEngine> = ds.data.map { prefs ->
        ReceiptEngine.entries.firstOrNull { it.name == prefs[KEY_RECEIPT_ENGINE] } ?: ReceiptEngine.ON_DEVICE
    }

    /** Which third-party provider the [ReceiptEngine.OWN_KEY] engine calls. */
    val receiptProvider: Flow<ReceiptProvider> = ds.data.map { prefs ->
        ReceiptProvider.entries.firstOrNull { it.name == prefs[KEY_RECEIPT_PROVIDER] } ?: ReceiptProvider.GEMINI
    }

    /**
     * The set of cloud engines the user has explicitly consented to send receipt photos to. Consent
     * is per-engine because "Google gets my photo" and "Schism's server gets my photo" are different
     * decisions, and it is stored as an allow-list so a new engine can never inherit an old consent.
     */
    val receiptCloudConsents: Flow<Set<String>> = ds.data.map { it[KEY_RECEIPT_CONSENTS] ?: emptySet() }

    suspend fun setReceiptEngine(engine: ReceiptEngine) {
        ds.edit { it[KEY_RECEIPT_ENGINE] = engine.name }
    }

    suspend fun setReceiptProvider(provider: ReceiptProvider) {
        ds.edit { it[KEY_RECEIPT_PROVIDER] = provider.name }
    }

    /** Records that the user accepted the "this photo leaves your device" sheet for [engine]. */
    suspend fun grantReceiptCloudConsent(engine: ReceiptEngine) {
        ds.edit { it[KEY_RECEIPT_CONSENTS] = (it[KEY_RECEIPT_CONSENTS] ?: emptySet()) + engine.name }
    }

    /**
     * The user's own provider API key. It is a credential, so it lives ONLY in the Keystore-encrypted
     * store — never in this DataStore, never in a log line, and never in any exported/crash payload.
     */
    fun receiptApiKey(): String = receiptKeyStore.read()

    fun setReceiptApiKey(key: String) {
        receiptKeyStore.write(key.trim())
        receiptKeyVersion.value += 1
    }

    fun clearReceiptApiKey() {
        receiptKeyStore.clear()
        receiptKeyVersion.value += 1
    }

    /** Whether a key is stored, for the UI — the key itself is never surfaced back to the screen. */
    val receiptApiKeyPresent: Flow<Boolean> = receiptKeyVersion.map { receiptKeyStore.read().isNotBlank() }

    /**
     * Alpha "Let everyone claim" links (Settings › Labs). Off by default — gates the entry point on
     * [ai.schism.split.sms.itemized.ItemizedSplitScreen]; backend endpoints are always live.
     */
    val claimLinksAlpha: Flow<Boolean> = ds.data.map { it[KEY_CLAIM_LINKS_ALPHA] ?: false }

    suspend fun setClaimLinksAlpha(enabled: Boolean) {
        ds.edit { it[KEY_CLAIM_LINKS_ALPHA] = enabled }
    }

    suspend fun setProfileName(name: String) {
        ds.edit { it[KEY_NAME] = name.trim() }
    }

    /** Update the full editable profile (name, email, phone) from the Settings screen. */
    suspend fun setProfile(name: String, email: String, phone: String) {
        ds.edit {
            it[KEY_NAME] = name.trim()
            it[KEY_EMAIL] = email.trim()
            it[KEY_PHONE] = phone.trim()
        }
    }

    /** Persist the device identity and mark onboarding complete. */
    suspend fun completeOnboarding(name: String, email: String, phone: String) {
        ds.edit {
            it[KEY_NAME] = name.trim()
            it[KEY_EMAIL] = email.trim()
            it[KEY_PHONE] = phone.trim()
            it[KEY_ONBOARDED] = true
        }
    }

    suspend fun setIdentity(id: String, token: String) {
        if (token.isNotBlank()) secureTokenStore.write(token)
        ds.edit {
            it[KEY_USER_ID] = id
            it.remove(KEY_TOKEN)
        }
        tokenVersion.value++
    }

    /**
     * Clears just the bearer token — used when the backend reports our session is no longer valid
     * (a 401 on a request that carried it), so the device stops sending a dead token. The rest of the
     * device identity (name/email/phone/userId) is left alone; signing back in mints a fresh token.
     */
    suspend fun clearAuthToken() {
        secureTokenStore.clear()
        ds.edit { it.remove(KEY_TOKEN) }
        tokenVersion.value++
    }

    suspend fun addKnownGroup(id: String) {
        ds.edit { prefs ->
            prefs[KEY_GROUPS] = (prefs[KEY_GROUPS] ?: emptySet()) + id
        }
    }

    suspend fun removeKnownGroup(id: String) {
        ds.edit { prefs ->
            prefs[KEY_GROUPS] = (prefs[KEY_GROUPS] ?: emptySet()) - id
        }
    }

    suspend fun setDefaultCurrency(symbol: String, code: String) {
        ds.edit {
            it[KEY_CUR_SYMBOL] = symbol.trim()
            it[KEY_CUR_CODE] = code.trim()
        }
    }

    suspend fun setThemeMode(mode: String) {
        ds.edit { it[KEY_THEME] = mode }
    }

    /**
     * Merchant → preferred expense title. Set when the user renames a transaction while splitting it
     * to a group, then reused for the same merchant next time (Fold-style tagging). Stored as
     * "merchantLower\ttitle" entries.
     */
    suspend fun merchantAlias(merchant: String): String? {
        val key = merchant.trim().lowercase()
        return ds.data.first()[KEY_MERCHANT_ALIASES].orEmpty()
            .firstOrNull { it.substringBefore('\t') == key }
            ?.substringAfter('\t')
    }

    suspend fun setMerchantAlias(merchant: String, title: String) {
        val key = merchant.trim().lowercase()
        ds.edit { prefs ->
            val others = (prefs[KEY_MERCHANT_ALIASES] ?: emptySet())
                .filterNot { it.substringBefore('\t') == key }.toSet()
            prefs[KEY_MERCHANT_ALIASES] = others + "$key\t${title.trim()}"
        }
    }

    /** Wipe all device-local settings (used by "reset" and to isolate tests). */
    suspend fun clear() {
        secureTokenStore.clear()
        clearReceiptApiKey()
        ds.edit { it.clear() }
        smsPreference.setEnabled(false)
        tokenVersion.value++
    }

    private suspend fun migrateLegacyToken() = tokenMigrationMutex.withLock {
        val legacy = ds.data.first()[KEY_TOKEN].orEmpty()
        if (legacy.isNotBlank()) secureTokenStore.write(legacy)
        if (legacy.isNotEmpty()) ds.edit { it.remove(KEY_TOKEN) }
    }

    companion object {
        const val DEFAULT_CURRENCY_SYMBOL = "₹"
        const val DEFAULT_CURRENCY_CODE = "INR"
        const val DEFAULT_THEME_MODE = "SYSTEM"
        private val KEY_NAME = stringPreferencesKey("profile_name")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_TOKEN = stringPreferencesKey("auth_token")
        private val KEY_EMAIL = stringPreferencesKey("profile_email")
        private val KEY_PHONE = stringPreferencesKey("profile_phone")
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        private val KEY_GROUPS = stringSetPreferencesKey("known_group_ids")
        private val KEY_CUR_SYMBOL = stringPreferencesKey("currency_symbol")
        private val KEY_CUR_CODE = stringPreferencesKey("currency_code")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_MERCHANT_ALIASES = stringSetPreferencesKey("merchant_aliases")
        private val KEY_AI_MODEL_URL = stringPreferencesKey("ai_model_url")
        private val KEY_AI_MODEL_TOKEN = stringPreferencesKey("ai_model_token")
        private val KEY_AI_ENABLED = booleanPreferencesKey("ai_enabled")
        private val KEY_CLAIM_LINKS_ALPHA = booleanPreferencesKey("claim_links_alpha")
        private val KEY_RECEIPT_ENGINE = stringPreferencesKey("receipt_engine")
        private val KEY_RECEIPT_PROVIDER = stringPreferencesKey("receipt_provider")
        private val KEY_RECEIPT_CONSENTS = stringSetPreferencesKey("receipt_cloud_consents")
    }
}
