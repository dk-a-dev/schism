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
    val email: String,
    val phone: String,
    val userId: String,
    val currencySymbol: String,
    val currencyCode: String,
    val themeMode: String,
    val groupCount: Int,
) {
    /** True once this device has a backend identity (registered during onboarding). */
    val registered: Boolean get() = userId.isNotBlank()
}

private data class Profile(val name: String, val email: String, val phone: String, val userId: String)
private data class Prefs(val symbol: String, val code: String, val theme: String, val groups: Int)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    // combine() tops out at 5 typed flows, so fold the eight sources into two groups first.
    private val profile = combine(
        settings.profileName, settings.email, settings.phone, settings.userId,
    ) { name, email, phone, userId -> Profile(name, email, phone, userId) }

    private val prefs = combine(
        settings.currencySymbol, settings.currencyCode, settings.themeMode, settings.knownGroupIds,
    ) { symbol, code, theme, groups -> Prefs(symbol, code, theme, groups.size) }

    val state: StateFlow<SettingsUi> = combine(profile, prefs) { p, pf ->
        SettingsUi(
            profileName = p.name,
            email = p.email,
            phone = p.phone,
            userId = p.userId,
            currencySymbol = pf.symbol,
            currencyCode = pf.code,
            themeMode = pf.theme,
            groupCount = pf.groups,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUi(
            profileName = "",
            email = "",
            phone = "",
            userId = "",
            currencySymbol = SettingsRepository.DEFAULT_CURRENCY_SYMBOL,
            currencyCode = SettingsRepository.DEFAULT_CURRENCY_CODE,
            themeMode = SettingsRepository.DEFAULT_THEME_MODE,
            groupCount = 0,
        ),
    )

    fun saveProfileName(name: String) {
        viewModelScope.launch { settings.setProfileName(name) }
    }

    fun saveProfile(name: String, email: String, phone: String) {
        viewModelScope.launch { settings.setProfile(name, email, phone) }
    }

    fun saveDefaultCurrency(symbol: String, code: String) {
        viewModelScope.launch { settings.setDefaultCurrency(symbol, code) }
    }

    fun saveThemeMode(mode: String) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    /** Wipe all device-local settings (profile, currency, theme, joined groups). */
    fun resetAll() {
        viewModelScope.launch { settings.clear() }
    }
}
