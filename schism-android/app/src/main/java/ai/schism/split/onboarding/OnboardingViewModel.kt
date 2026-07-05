package ai.schism.split.onboarding

import ai.schism.split.core.settings.SettingsRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    /** Persist the identity and mark onboarding done; the app gate then swaps to the main UI. */
    fun complete(name: String, email: String, phone: String) {
        viewModelScope.launch { settings.completeOnboarding(name, email, phone) }
    }
}
