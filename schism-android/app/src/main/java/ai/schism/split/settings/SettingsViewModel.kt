package ai.schism.split.settings

import ai.schism.split.core.settings.SettingsRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Snapshot of the device-local settings surfaced by [SettingsScreen]. (Backend URL is build/env config.) */
data class SettingsUi(
    val profileName: String,
    val currencySymbol: String,
    val currencyCode: String,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<SettingsUi> = combine(
        settings.profileName,
        settings.currencySymbol,
        settings.currencyCode,
    ) { profileName, currencySymbol, currencyCode ->
        SettingsUi(profileName = profileName, currencySymbol = currencySymbol, currencyCode = currencyCode)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUi("", SettingsRepository.DEFAULT_CURRENCY_SYMBOL, SettingsRepository.DEFAULT_CURRENCY_CODE),
    )

    fun saveProfileName(name: String) {
        viewModelScope.launch { settings.setProfileName(name) }
    }

    fun saveDefaultCurrency(symbol: String, code: String) {
        viewModelScope.launch { settings.setDefaultCurrency(symbol, code) }
    }
}
