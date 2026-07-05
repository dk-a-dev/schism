package ai.schism.split.onboarding

import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.AuthRequest
import ai.schism.split.core.settings.SettingsRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class AuthUiState(val submitting: Boolean = false, val error: String? = null)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val api: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /** Create an account (email + password + optional phone), store the session, finish onboarding. */
    fun register(name: String, email: String, password: String, phone: String, onDone: () -> Unit) {
        submit(onDone, phone) { api.authRegister(AuthRequest(email.trim(), password, name.trim(), phone.trim())) }
    }

    /** Log in to an existing account and finish onboarding. */
    fun login(email: String, password: String, onDone: () -> Unit) {
        submit(onDone, "") { api.authLogin(AuthRequest(email.trim(), password)) }
    }

    private fun submit(
        onDone: () -> Unit,
        phone: String,
        call: suspend () -> ai.schism.split.core.net.AuthResponse,
    ) {
        viewModelScope.launch {
            _state.value = AuthUiState(submitting = true)
            runCatching { call() }
                .onSuccess { r ->
                    settings.setIdentity(r.id, r.token)
                    settings.completeOnboarding(r.name, r.email, phone.trim())
                    // Restore groups this account belongs to — including ones friends added them to
                    // by phone number before they ever installed the app.
                    runCatching { api.myGroups() }.onSuccess { mine ->
                        mine.groupIds.forEach { settings.addKnownGroup(it) }
                    }
                    _state.value = AuthUiState()
                    onDone()
                }
                .onFailure { _state.value = AuthUiState(error = messageFor(it)) }
        }
    }

    private fun messageFor(t: Throwable): String = when {
        t is retrofit2.HttpException -> when (t.code()) {
            409 -> "That email is already registered — log in instead."
            401 -> "Invalid email or password."
            400 -> "Check your details (password must be at least 6 characters)."
            else -> "Something went wrong. Please try again."
        }
        t is IOException -> "Can't reach the server. Check your connection."
        else -> "Something went wrong. Please try again."
    }
}
