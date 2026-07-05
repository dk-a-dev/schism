package ai.schism.split.groups.qr

import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.GroupRepository
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class InviteQrViewModel @Inject constructor(
    groupRepo: GroupRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val groupId: String = checkNotNull(savedStateHandle["groupId"]) { "groupId nav arg required" }

    /** The group (from the local cache) so the invite screen can show its name. */
    val group: StateFlow<Group?> =
        groupRepo.observeGroup(groupId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
