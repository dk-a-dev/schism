package ai.schism.split.sms.split

import ai.schism.split.core.net.ExpenseRequest
import ai.schism.split.core.net.PaidForDto
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.expense.data.ExpenseRepository
import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.GroupRepository
import ai.schism.split.sms.data.SmsRepository
import ai.schism.split.sms.data.Transaction
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * Turns the transaction date (epoch millis) into an ISO `yyyy-MM-dd` date in the device's zone,
 * matching the backend's expense-date format.
 */
private fun isoDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/**
 * Builds a group expense from a parsed transaction: the [title] (editable, defaults to the merchant)
 * and amount in minor units, split EVENLY (one share each) across only the [participantIds] the user
 * chose to include — never assumed to be everyone. Pure and network-free so it can be unit-tested;
 * the idempotency key (the transaction id) is applied by the caller when creating the expense.
 */
fun buildPushToSplitRequest(
    transaction: Transaction,
    paidById: String,
    addedBy: String?,
    title: String,
    participantIds: List<String>,
): ExpenseRequest = ExpenseRequest(
    title = title.trim().ifBlank { transaction.merchant },
    amount = transaction.amountMinor,
    expenseDate = isoDate(transaction.timestamp),
    paidById = paidById,
    splitMode = "EVENLY",
    isReimbursement = false,
    addedBy = addedBy,
    paidFor = participantIds.map { PaidForDto(participantId = it, shares = 1L) },
)

data class PushToSplitUiState(
    val loading: Boolean = true,
    val transaction: Transaction? = null,
    val title: String = "",
    val groups: List<Group> = emptyList(),
    val selectedGroupId: String? = null,
    val paidById: String = "",
    /** Participants included in the split (defaults to everyone, but editable). */
    val includedIds: Set<String> = emptySet(),
    val submitting: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class PushToSplitViewModel @Inject constructor(
    private val smsRepo: SmsRepository,
    private val groupRepo: GroupRepository,
    private val expenseRepo: ExpenseRepository,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val transactionId: String = checkNotNull(savedStateHandle["transactionId"])

    private val _state = MutableStateFlow(PushToSplitUiState())
    val state: StateFlow<PushToSplitUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val txn = smsRepo.getById(transactionId)
            // Fold-style: if this merchant was renamed before, reuse that title next time.
            val defaultTitle = txn?.let { settings.merchantAlias(it.merchant) ?: it.merchant }.orEmpty()
            _state.update { it.copy(transaction = txn, title = defaultTitle) }

            // Only groups this device has joined/created are eligible targets.
            combine(groupRepo.observeGroups(), settings.knownGroupIds) { groups, known ->
                groups.filter { it.id in known }
            }.collect { known ->
                _state.update { s ->
                    val selected = s.selectedGroupId?.takeIf { id -> known.any { it.id == id } }
                        ?: known.firstOrNull()?.id
                    val group = known.firstOrNull { it.id == selected }
                    s.copy(
                        loading = false,
                        groups = known,
                        selectedGroupId = selected,
                        paidById = s.paidById.ifBlank { defaultPaidBy(known, selected) },
                        includedIds = s.includedIds.ifEmpty { group?.participants?.map { it.id }?.toSet().orEmpty() },
                    )
                }
            }
        }
    }

    /** The group's active participant ("you") if set, else the first participant. */
    private fun defaultPaidBy(groups: List<Group>, groupId: String?): String {
        val group = groups.firstOrNull { it.id == groupId } ?: return ""
        return group.activeParticipantId ?: group.participants.firstOrNull()?.id ?: ""
    }

    fun onTitleChange(value: String) {
        _state.update { it.copy(title = value, error = null) }
    }

    fun onGroupChange(groupId: String) {
        _state.update { s ->
            val group = s.groups.firstOrNull { it.id == groupId }
            s.copy(
                selectedGroupId = groupId,
                paidById = defaultPaidBy(s.groups, groupId),
                includedIds = group?.participants?.map { it.id }?.toSet().orEmpty(),
                error = null,
            )
        }
    }

    fun onPaidByChange(participantId: String) {
        _state.update { it.copy(paidById = participantId, error = null) }
    }

    /** Toggle whether a participant is included in the split. */
    fun toggleIncluded(participantId: String) {
        _state.update { s ->
            val next = if (participantId in s.includedIds) s.includedIds - participantId else s.includedIds + participantId
            s.copy(includedIds = next, error = null)
        }
    }

    /** Build the request and create the expense; the transaction id is the idempotency key. */
    fun submit(onDone: () -> Unit) {
        val s = _state.value
        val txn = s.transaction
        val group = s.groups.firstOrNull { it.id == s.selectedGroupId }
        if (txn == null || group == null || s.paidById.isBlank()) {
            _state.update { it.copy(error = "Pick a group and who paid") }
            return
        }
        val included = group.participants.map { it.id }.filter { it in s.includedIds }
        if (included.isEmpty()) {
            _state.update { it.copy(error = "Include at least one person in the split") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            // Stamp the creator as your user id when the active participant in this group is you.
            val userId = settings.userId.first()
            val youParticipant = group.participants.firstOrNull { it.id == group.activeParticipantId }
            val addedBy = userId.takeIf { it.isNotBlank() && youParticipant?.userId == it }

            val request = buildPushToSplitRequest(txn, s.paidById, addedBy, s.title, included)
            expenseRepo.createExpense(group.id, request, idempotencyKey = txn.id).fold(
                onSuccess = { expense ->
                    // Remember a manual rename so the same merchant is tagged next time.
                    if (s.title.trim().isNotBlank() && !s.title.trim().equals(txn.merchant, ignoreCase = true)) {
                        settings.setMerchantAlias(txn.merchant, s.title.trim())
                    }
                    smsRepo.markPushed(txn.id, group.id, expense.id)
                    _state.update { it.copy(submitting = false) }
                    onDone()
                },
                onFailure = { e ->
                    _state.update { it.copy(submitting = false, error = e.message ?: "Couldn't add to group") }
                },
            )
        }
    }
}
