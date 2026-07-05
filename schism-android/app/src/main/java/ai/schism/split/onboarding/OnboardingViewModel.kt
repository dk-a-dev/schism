package ai.schism.split.onboarding

import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.UserRequest
import ai.schism.split.core.settings.SettingsRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val api: ApiService,
) : ViewModel() {

    /**
     * Persist the identity, mark onboarding done, and register with the backend (best-effort — the
     * user is onboarded even offline; the backend user id is stored when registration succeeds).
     */
    fun complete(name: String, email: String, phone: String) {
        viewModelScope.launch {
            settings.completeOnboarding(name, email, phone)
            runCatching { api.registerUser(UserRequest(name.trim(), email.trim(), phone.trim())) }
                .onSuccess { settings.setIdentity(it.id, it.token.orEmpty()) }
        }
    }
}
