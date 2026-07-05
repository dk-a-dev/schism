package ai.schism.split.groups.join

import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.groups.data.GroupRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface JoinState {
    data object Idle : JoinState
    data object Joining : JoinState
    data class Error(val message: String) : JoinState
}

@HiltViewModel
class JoinGroupViewModel @Inject constructor(
    private val repo: GroupRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<JoinState>(JoinState.Idle)
    val state: StateFlow<JoinState> = _state.asStateFlow()

    /** Parse a share link or raw id, fetch the group to confirm it exists, then remember it. */
    fun join(input: String, onJoined: (String) -> Unit) {
        val id = parseGroupId(input)
        if (id.isBlank()) {
            _state.value = JoinState.Error("Enter a group link or ID")
            return
        }
        viewModelScope.launch {
            _state.value = JoinState.Joining
            repo.refreshGroup(id)
                .onSuccess {
                    settings.addKnownGroup(id)
                    _state.value = JoinState.Idle
                    onJoined(id)
                }
                .onFailure {
                    _state.value = JoinState.Error("Couldn't find that group")
                }
        }
    }

    companion object {
        /** `schism://group/<id>`, any `.../group/<id>` URL, or a bare id all resolve to `<id>`. */
        fun parseGroupId(input: String): String {
            val trimmed = input.trim()
            val afterGroup = when {
                trimmed.contains("/group/") -> trimmed.substringAfterLast("/group/")
                else -> trimmed
            }
            return afterGroup.substringBefore('?').substringBefore('/').trim()
        }

        /** The canonical deep link shared to invite others to a group. */
        fun shareLink(id: String): String = "schism://group/$id"
    }
}
