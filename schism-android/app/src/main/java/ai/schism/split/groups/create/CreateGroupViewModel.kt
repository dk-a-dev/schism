package ai.schism.split.groups.create

import ai.schism.split.core.net.CreateGroupRequest
import ai.schism.split.core.net.ParticipantRequest
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.groups.data.GroupRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateGroupForm(
    val name: String = "",
    val currency: String = "₹",
    val currencyCode: String = "INR",
    val information: String = "",
    val participants: List<String> = listOf(""),
)

data class CreateGroupUiState(
    val form: CreateGroupForm = CreateGroupForm(),
    val nameError: String? = null,
    val participantsError: String? = null,
    val submitting: Boolean = false,
    val submitError: String? = null,
)

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val repo: GroupRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateGroupUiState())
    val state: StateFlow<CreateGroupUiState> = _state.asStateFlow()

    // The device owner, used to link their participant to their backend user across groups.
    private var youName: String = ""
    private var youUserId: String = ""

    init {
        // Seed the form with the app's default currency and prefill "you" as the first participant.
        viewModelScope.launch {
            val symbol = settings.currencySymbol.first()
            val code = settings.currencyCode.first()
            youName = settings.profileName.first().trim()
            youUserId = settings.userId.first()
            _state.update { s ->
                val participants = if (youName.isNotBlank() && s.form.participants.all { it.isBlank() }) {
                    listOf(youName) + s.form.participants.drop(1)
                } else {
                    s.form.participants
                }
                s.copy(form = s.form.copy(currency = symbol, currencyCode = code, participants = participants))
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
        val next = s.form.participants.toMutableList().also { it[index] = value }
        s.copy(form = s.form.copy(participants = next), participantsError = null)
    }

    fun addParticipant() =
        _state.update { it.copy(form = it.form.copy(participants = it.form.participants + "")) }

    // Phone numbers captured from the contacts picker, keyed by participant name (lowercased).
    // Sent to the backend so the friend is auto-linked when they register with the same number,
    // and used for the post-create SMS invite.
    private val contactPhones = mutableMapOf<String, String>()

    /** Add a participant picked from contacts, remembering their phone for linking + invites. */
    fun addParticipantFromContact(name: String, phone: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (!phone.isNullOrBlank()) contactPhones[trimmed.lowercase()] = phone.trim()
        addParticipantNamed(trimmed)
    }

    /** Phones of contact-added participants still present in the form (for the SMS invite). */
    fun pendingInvitePhones(): List<String> {
        val names = _state.value.form.participants.map { it.trim().lowercase() }.toSet()
        return contactPhones.filterKeys { it in names }.values.toList()
    }

    fun groupNameForInvite(): String = _state.value.form.name.trim()

    /** Add a participant picked from contacts: fills the first empty row, otherwise appends. */
    fun addParticipantNamed(name: String) = _state.update { s ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@update s
        val parts = s.form.participants
        val emptyIdx = parts.indexOfLast { it.isBlank() }
        val next = if (emptyIdx >= 0) {
            parts.toMutableList().also { it[emptyIdx] = trimmed }
        } else {
            parts + trimmed
        }
        s.copy(form = s.form.copy(participants = next), participantsError = null)
    }

    fun removeParticipant(index: Int) = _state.update { s ->
        if (s.form.participants.size <= 1) return@update s
        s.copy(form = s.form.copy(participants = s.form.participants.filterIndexed { i, _ -> i != index }))
    }

    /** Validates locally (mirrors the backend), then creates online. Calls [onSuccess] with the new id. */
    fun submit(onSuccess: (String) -> Unit) {
        val form = _state.value.form
        val name = form.name.trim()
        val names = form.participants.map { it.trim() }.filter { it.isNotEmpty() }
        val hasDuplicates = names.map { it.lowercase() }.toSet().size != names.size

        val nameError = if (name.length < 2) "Name must be at least 2 characters" else null
        val participantsError = when {
            names.isEmpty() -> "Add at least one participant"
            hasDuplicates -> "Participant names must be unique"
            else -> null
        }
        if (nameError != null || participantsError != null) {
            _state.update { it.copy(nameError = nameError, participantsError = participantsError) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(submitting = true, submitError = null) }
            repo.createGroup(
                CreateGroupRequest(
                    name = name,
                    information = form.information.trim(),
                    currency = form.currency,
                    currencyCode = form.currencyCode,
                    participants = names.map { participantName ->
                        // Link the device owner's participant to their backend user.
                        val linkedUserId = youUserId
                            .takeIf { it.isNotBlank() && participantName.equals(youName, ignoreCase = true) }
                        ParticipantRequest(
                            name = participantName,
                            userId = linkedUserId,
                            phone = contactPhones[participantName.lowercase()],
                        )
                    },
                ),
            ).onSuccess { id ->
                _state.update { it.copy(submitting = false) }
                onSuccess(id)
            }.onFailure { e ->
                _state.update { it.copy(submitting = false, submitError = e.message ?: "Couldn't create group") }
            }
        }
    }
}
