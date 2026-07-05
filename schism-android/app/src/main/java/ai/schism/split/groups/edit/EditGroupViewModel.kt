package ai.schism.split.groups.edit

import ai.schism.split.core.net.CreateGroupRequest
import ai.schism.split.core.net.ParticipantRequest
import ai.schism.split.groups.data.GroupRepository
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A participant being edited; [id] is null for a newly added one (the backend will insert it). */
data class EditParticipant(val id: String?, val name: String, val userId: String?)

data class EditGroupForm(
    val name: String = "",
    val currency: String = "₹",
    val currencyCode: String = "INR",
    val information: String = "",
    val participants: List<EditParticipant> = emptyList(),
)

data class EditGroupUiState(
    val loading: Boolean = true,
    val form: EditGroupForm = EditGroupForm(),
    val nameError: String? = null,
    val participantsError: String? = null,
    val submitting: Boolean = false,
    val submitError: String? = null,
)

/**
 * Edits an existing group's name, note, currency and participants. Participants keep their id so the
 * backend reconciles in place (rename/keep balances); added rows have a null id (inserted) and
 * removed rows are simply dropped (deleted server-side).
 */
@HiltViewModel
class EditGroupViewModel @Inject constructor(
    private val repo: GroupRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"]) { "groupId nav arg required" }

    private val _state = MutableStateFlow(EditGroupUiState())
    val state: StateFlow<EditGroupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val g = repo.observeGroup(groupId).filterNotNull().first()
            _state.update {
                it.copy(
                    loading = false,
                    form = EditGroupForm(
                        name = g.name,
                        currency = g.currency,
                        currencyCode = g.currencyCode,
                        information = g.information,
                        participants = g.participants.map { p -> EditParticipant(p.id, p.name, p.userId) },
                    ),
                )
            }
        }
    }

    fun onNameChange(value: String) =
        _state.update { it.copy(form = it.form.copy(name = value), nameError = null) }

    fun onInformationChange(value: String) =
        _state.update { it.copy(form = it.form.copy(information = value)) }

    fun onCurrencyChange(symbol: String, code: String) =
        _state.update { it.copy(form = it.form.copy(currency = symbol, currencyCode = code)) }

    fun onParticipantChange(index: Int, value: String) = _state.update { s ->
        val next = s.form.participants.toMutableList()
            .also { it[index] = it[index].copy(name = value) }
        s.copy(form = s.form.copy(participants = next), participantsError = null)
    }

    fun addParticipant() = _state.update {
        it.copy(form = it.form.copy(participants = it.form.participants + EditParticipant(null, "", null)))
    }

    fun removeParticipant(index: Int) = _state.update { s ->
        if (s.form.participants.size <= 1) return@update s
        s.copy(form = s.form.copy(participants = s.form.participants.filterIndexed { i, _ -> i != index }))
    }

    fun submit(onDone: () -> Unit) {
        val form = _state.value.form
        val name = form.name.trim()
        val kept = form.participants.map { it.copy(name = it.name.trim()) }.filter { it.name.isNotEmpty() }
        val hasDuplicates = kept.map { it.name.lowercase() }.toSet().size != kept.size

        val nameError = if (name.length < 2) "Name must be at least 2 characters" else null
        val participantsError = when {
            kept.isEmpty() -> "Add at least one participant"
            hasDuplicates -> "Participant names must be unique"
            else -> null
        }
        if (nameError != null || participantsError != null) {
            _state.update { it.copy(nameError = nameError, participantsError = participantsError) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(submitting = true, submitError = null) }
            repo.updateGroup(
                groupId,
                CreateGroupRequest(
                    name = name,
                    information = form.information.trim(),
                    currency = form.currency,
                    currencyCode = form.currencyCode,
                    participants = kept.map { ParticipantRequest(id = it.id, name = it.name, userId = it.userId) },
                ),
            ).onSuccess {
                _state.update { it.copy(submitting = false) }
                onDone()
            }.onFailure { e ->
                _state.update { it.copy(submitting = false, submitError = e.message ?: "Couldn't save changes") }
            }
        }
    }
}
