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

    /** Surface an error when a QR scan yields no usable group link (cancelled, failed, or unrelated). */
    fun onScanError() {
        _state.value = JoinState.Error("Couldn't read a group QR code")
    }

    companion object {
        /**
         * Resolve a group id from any invite form: the https link `<backend>/g/<id>`, the
         * `schism://group/<id>` deep link, any `.../group/<id>` URL, or a bare id.
         */
        fun parseGroupId(input: String): String {
            val trimmed = input.trim()
            val afterGroup = when {
                trimmed.contains("/group/") -> trimmed.substringAfterLast("/group/")
                trimmed.contains("/g/") -> trimmed.substringAfterLast("/g/")
                else -> trimmed
            }
            return afterGroup.substringBefore('?').substringBefore('#').substringBefore('/').trim()
        }

        /**
         * The invite link shared to others. Uses the backend's https base so it renders as a
         * tappable link in messengers (WhatsApp etc.); the backend page bounces into the app.
         */
        fun shareLink(id: String): String {
            val base = ai.schism.split.BuildConfig.BACKEND_URL.trimEnd('/')
            return "$base/g/$id"
        }
    }
}
