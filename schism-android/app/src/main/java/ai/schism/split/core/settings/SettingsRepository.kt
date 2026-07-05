package ai.schism.split.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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

    suspend fun setProfileName(name: String) {
        ds.edit { it[KEY_NAME] = name.trim() }
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

    /** Wipe all device-local settings (used by "reset" and to isolate tests). */
    suspend fun clear() {
        ds.edit { it.clear() }
    }

    companion object {
        const val DEFAULT_CURRENCY_SYMBOL = "₹"
        const val DEFAULT_CURRENCY_CODE = "INR"
        const val DEFAULT_THEME_MODE = "SYSTEM"
        private val KEY_NAME = stringPreferencesKey("profile_name")
        private val KEY_EMAIL = stringPreferencesKey("profile_email")
        private val KEY_PHONE = stringPreferencesKey("profile_phone")
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        private val KEY_GROUPS = stringSetPreferencesKey("known_group_ids")
        private val KEY_CUR_SYMBOL = stringPreferencesKey("currency_symbol")
        private val KEY_CUR_CODE = stringPreferencesKey("currency_code")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
    }
}
