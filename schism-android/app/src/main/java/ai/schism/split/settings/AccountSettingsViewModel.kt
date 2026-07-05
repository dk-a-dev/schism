package ai.schism.split.settings

import ai.schism.split.core.net.ApiService
import ai.schism.split.core.settings.SettingsRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Logout / delete-account actions. Clearing local settings resets `onboarded`, so the app's root
 *  gate returns to onboarding automatically — no navigation needed. */
@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val api: ApiService,
) : ViewModel() {

    /** End the session on this device (keeps the account on the server). */
    fun logout() {
        viewModelScope.launch { settings.clear() }
    }

    /** Delete the account on the server, then clear the device. */
    fun deleteAccount() {
        viewModelScope.launch {
            runCatching { api.deleteAccount() }
            settings.clear()
        }
    }
}
