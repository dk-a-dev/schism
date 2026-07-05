package ai.schism.split.core.settings

import android.content.Context
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
 * Device-local settings: the backend base URL, the device profile name (used to resolve "you"),
 * and the set of group ids this device has joined/created.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val ds = context.dataStore

    val backendUrl: Flow<String> = ds.data.map { it[KEY_URL] ?: DEFAULT_BACKEND_URL }
    val profileName: Flow<String> = ds.data.map { it[KEY_NAME] ?: "" }
    val knownGroupIds: Flow<Set<String>> = ds.data.map { it[KEY_GROUPS] ?: emptySet() }

    suspend fun setBackendUrl(url: String) {
        ds.edit { it[KEY_URL] = url.trim() }
    }

    suspend fun setProfileName(name: String) {
        ds.edit { it[KEY_NAME] = name.trim() }
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

    companion object {
        const val DEFAULT_BACKEND_URL = "http://10.0.2.2:8080"
        private val KEY_URL = stringPreferencesKey("backend_url")
        private val KEY_NAME = stringPreferencesKey("profile_name")
        private val KEY_GROUPS = stringSetPreferencesKey("known_group_ids")
    }
}
