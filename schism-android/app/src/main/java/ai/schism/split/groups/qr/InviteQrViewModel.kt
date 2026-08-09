package ai.schism.split.groups.qr

import ai.schism.split.R
import ai.schism.split.core.net.CreateGroupRequest
import ai.schism.split.core.net.ParticipantRequest
import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.GroupRepository
import ai.schism.split.groups.data.Participant
import ai.schism.split.groups.invite.GroupInviteRepository
import ai.schism.split.groups.invite.ParticipantInviteRepository
import ai.schism.split.groups.invite.groupInviteLink
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The group's shareable link — the primary way to invite, needing no participant to exist first. */
sealed interface GroupLinkState {
    data object Creating : GroupLinkState
    data class Ready(val link: String) : GroupLinkState
    data object Revoked : GroupLinkState
    data class Failed(@StringRes val messageRes: Int) : GroupLinkState
}

/** One person's invite, bound to one existing participant (e.g. "Rohan"). */
sealed interface InviteLinkState {
    data object Idle : InviteLinkState
    data object Creating : InviteLinkState
    data class Ready(val participant: Participant, val link: String) : InviteLinkState
    data class Failed(@StringRes val messageRes: Int) : InviteLinkState
}

/**
 * Organizer side of invitations. Two paths, deliberately:
 *
 * - the group link (primary): share once, and everyone who opens it joins as themselves. Minting one
 *   revokes the previous, so this screen mints on open — the server stores only token hashes, so an
 *   already-shared link can never be re-displayed, only replaced.
 * - a per-person invite (secondary): binds one named participant to whoever redeems it, which is
 *   still what you want to hand "Rohan" the row that is already his.
 */
@HiltViewModel
class InviteQrViewModel @Inject constructor(
    private val invites: ParticipantInviteRepository,
    private val groupInvites: GroupInviteRepository,
    private val groups: GroupRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle["groupId"]) { "groupId nav arg required" }

    /** The group (from the local cache) so the invite screen can show its name and participants. */
    val group: StateFlow<Group?> =
        groups.observeGroup(groupId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _linkState = MutableStateFlow<GroupLinkState>(GroupLinkState.Creating)
    val linkState: StateFlow<GroupLinkState> = _linkState.asStateFlow()

    private val _state = MutableStateFlow<InviteLinkState>(InviteLinkState.Idle)
    val state: StateFlow<InviteLinkState> = _state.asStateFlow()

    init {
        // Reuse the link this device already minted rather than rotating on every visit — otherwise
        // reopening this screen silently invalidates the link the organizer just shared in a chat.
        val existing = groupInvites.cached(groupId)
        if (existing != null) {
            _linkState.value = GroupLinkState.Ready(groupInviteLink(existing))
        } else {
            createGroupLink()
        }
    }

    /** Mint (or regenerate) the group link. Any previously shared link stops working. */
    fun createGroupLink() {
        viewModelScope.launch {
            _linkState.value = GroupLinkState.Creating
            groupInvites.create(groupId)
                .onSuccess { _linkState.value = GroupLinkState.Ready(groupInviteLink(it)) }
                .onFailure { _linkState.value = GroupLinkState.Failed(inviteMessageRes(it)) }
        }
    }

    fun revokeGroupLink() {
        viewModelScope.launch {
            groupInvites.revoke(groupId)
                .onSuccess { _linkState.value = GroupLinkState.Revoked }
                .onFailure { _linkState.value = GroupLinkState.Failed(inviteMessageRes(it)) }
        }
    }

    /** Participants nobody has claimed yet — the only ones a per-person invite can be issued for. */
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

    /**
     * Add a brand-new person to the group and immediately mint their personal invite — for binding
     * someone to a name you choose. Anyone who can just join themselves should get the group link.
     */
    fun invitePerson(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.value = InviteLinkState.Creating
            val current = group.value
            if (current == null) {
                _state.value = InviteLinkState.Failed(R.string.invite_add_person_failed)
                return@launch
            }
            val participants = current.participants.map {
                ParticipantRequest(id = it.id, name = it.name, userId = it.userId)
            } + ParticipantRequest(name = trimmed)
            groups.updateGroup(
                current.id,
                CreateGroupRequest(
                    name = current.name,
                    currency = current.currency,
                    participants = participants,
                ),
            ).onFailure {
                _state.value = InviteLinkState.Failed(inviteMessageRes(it))
                return@launch
            }
            // Re-read the group so the server-assigned participant id is available to invite.
            val added = groups.observeGroup(current.id).first()
                ?.participants
                ?.lastOrNull { it.name == trimmed && it.userId.isNullOrBlank() }
            if (added == null) {
                _state.value = InviteLinkState.Failed(R.string.invite_add_person_failed)
                return@launch
            }
            invites.create(groupId, added.id)
                .onSuccess { _state.value = InviteLinkState.Ready(added, inviteLink(it)) }
                .onFailure { _state.value = InviteLinkState.Failed(inviteMessageRes(it)) }
        }
    }

    /** Back to the participant picker (choosing someone else, or after a failure). */
    fun reset() {
        _state.value = InviteLinkState.Idle
    }
}
