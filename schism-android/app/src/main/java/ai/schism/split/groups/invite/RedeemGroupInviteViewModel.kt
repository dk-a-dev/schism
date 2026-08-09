package ai.schism.split.groups.invite

import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.groups.data.GroupRepository
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives `schism://group-invite/<token>`: same shape as [RedeemInviteViewModel] — wait for a session,
 * preview, confirm — but redeeming creates the caller's own participant rather than claiming a
 * pre-named one. Redeeming twice is a no-op on the server, so a re-tap simply lands on the group.
 */
@HiltViewModel
class RedeemGroupInviteViewModel @Inject constructor(
    private val invites: GroupInviteRepository,
    private val groups: GroupRepository,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val token: String = checkNotNull(savedStateHandle["token"]) { "token nav arg required" }

    private val _state = MutableStateFlow<RedeemState>(RedeemState.WaitingForSignIn)
    val state: StateFlow<RedeemState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Suspends until onboarding/sign-in stores a token, then the link continues on its own.
            settings.authToken.first { it.isNotBlank() }
            _state.value = RedeemState.Loading
            invites.preview(token)
                .onSuccess { _state.value = RedeemState.GroupPreview(it.groupName, it.memberCount) }
                .onFailure { _state.value = RedeemState.Failed(inviteMessageRes(it)) }
        }
    }

    fun confirm(onJoined: (String) -> Unit) {
        if (_state.value !is RedeemState.GroupPreview) return
        viewModelScope.launch {
            _state.value = RedeemState.Redeeming
            invites.redeem(token)
                .onSuccess { groupId ->
                    settings.addKnownGroup(groupId)
                    groups.refreshGroup(groupId)
                    onJoined(groupId)
                }
                .onFailure { _state.value = RedeemState.Failed(inviteMessageRes(it)) }
        }
    }
}
