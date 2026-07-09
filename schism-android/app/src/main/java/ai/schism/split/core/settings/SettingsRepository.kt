package ai.schism.split.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    val profileName: Flow<String> = ds.data.map { it[KEY_NAME] ?: "" }
    /** The backend user id for this device's identity (empty until registered). */
    val userId: Flow<String> = ds.data.map { it[KEY_USER_ID] ?: "" }
    /** Bearer auth token issued at registration (empty until registered). */
    val authToken: Flow<String> = ds.data.map { it[KEY_TOKEN] ?: "" }
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
        ds.edit {
            it[KEY_USER_ID] = id
            if (token.isNotBlank()) it[KEY_TOKEN] = token
        }
    }

    /**
     * Clears just the bearer token — used when the backend reports our session is no longer valid
     * (a 401 on a request that carried it), so the device stops sending a dead token. The rest of the
     * device identity (name/email/phone/userId) is left alone; signing back in mints a fresh token.
     */
    suspend fun clearAuthToken() {
        ds.edit { it[KEY_TOKEN] = "" }
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
        ds.edit { it.clear() }
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
    }
}
