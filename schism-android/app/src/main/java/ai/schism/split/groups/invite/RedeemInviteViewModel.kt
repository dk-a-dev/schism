package ai.schism.split.groups.invite

import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.groups.data.GroupRepository
import androidx.annotation.StringRes
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

sealed interface RedeemState {
    /** Deep link opened before this device has a session — we wait rather than fail. */
    data object WaitingForSignIn : RedeemState
    data object Loading : RedeemState
    data class Preview(val groupName: String, val participantName: String) : RedeemState
    data object Redeeming : RedeemState
    data class Failed(@StringRes val messageRes: Int) : RedeemState
}

/**
 * Drives `schism://invite/<token>`: waits for a session, previews the invite (names only), then on
 * confirmation redeems it and remembers the group id the server hands back. No group data is fetched
 * or cached before a successful redeem.
 */
@HiltViewModel
class RedeemInviteViewModel @Inject constructor(
    private val invites: ParticipantInviteRepository,
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
            load()
        }
    }

    private suspend fun load() {
        _state.value = RedeemState.Loading
        invites.preview(token)
            .onSuccess { _state.value = RedeemState.Preview(it.groupName, it.participantName) }
            .onFailure { _state.value = RedeemState.Failed(inviteMessageRes(it)) }
    }

    /** Confirm: link this account to the participant, remember the group, then hand off to it. */
    fun confirm(onJoined: (String) -> Unit) {
        if (_state.value !is RedeemState.Preview) return
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
