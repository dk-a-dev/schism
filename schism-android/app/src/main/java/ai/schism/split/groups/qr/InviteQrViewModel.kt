package ai.schism.split.groups.qr

import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.GroupRepository
import ai.schism.split.groups.data.Participant
import ai.schism.split.groups.invite.ParticipantInviteRepository
import ai.schism.split.groups.invite.inviteLink
import ai.schism.split.groups.invite.inviteMessageRes
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface InviteLinkState {
    data object Idle : InviteLinkState
    data object Creating : InviteLinkState
    data class Ready(val participant: Participant, val link: String) : InviteLinkState
    data class Failed(@StringRes val messageRes: Int) : InviteLinkState
}

/**
 * Organizer side of participant invitations: the group's still-unlinked participants, and a freshly
 * minted one-time token for whichever one is chosen. A link is only ever created on demand, so the
 * screen can never show a stale or reusable one.
 */
@HiltViewModel
class InviteQrViewModel @Inject constructor(
    private val invites: ParticipantInviteRepository,
    groupRepo: GroupRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle["groupId"]) { "groupId nav arg required" }

    /** The group (from the local cache) so the invite screen can show its name and participants. */
    val group: StateFlow<Group?> =
        groupRepo.observeGroup(groupId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow<InviteLinkState>(InviteLinkState.Idle)
    val state: StateFlow<InviteLinkState> = _state.asStateFlow()

    /** Participants nobody has claimed yet — the only ones an invite can be issued for. */
    fun unlinked(group: Group?): List<Participant> =
        group?.participants.orEmpty().filter { it.userId.isNullOrBlank() }

    fun createInvite(participant: Participant) {
        viewModelScope.launch {
            _state.value = InviteLinkState.Creating
            invites.create(groupId, participant.id)
                .onSuccess { _state.value = InviteLinkState.Ready(participant, inviteLink(it)) }
                .onFailure { _state.value = InviteLinkState.Failed(inviteMessageRes(it)) }
        }
    }

    /** Back to the participant picker (choosing someone else, or after a failure). */
    fun reset() {
        _state.value = InviteLinkState.Idle
    }
}
