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
 * Builds a group expense from a parsed transaction: the merchant becomes the title, the amount is
 * carried in minor units, and the cost is split EVENLY (one share each) across every participant.
 * Pure and network-free so it can be unit-tested; the idempotency key (the transaction id) is
 * applied by the caller when creating the expense.
 */
fun buildPushToSplitRequest(
    transaction: Transaction,
    group: Group,
    paidById: String,
    addedBy: String?,
): ExpenseRequest = ExpenseRequest(
    title = transaction.merchant,
    amount = transaction.amountMinor,
    expenseDate = isoDate(transaction.timestamp),
    paidById = paidById,
    splitMode = "EVENLY",
    isReimbursement = false,
    addedBy = addedBy,
    paidFor = group.participants.map { PaidForDto(participantId = it.id, shares = 1L) },
)

data class PushToSplitUiState(
    val loading: Boolean = true,
    val transaction: Transaction? = null,
    val groups: List<Group> = emptyList(),
    val selectedGroupId: String? = null,
    val paidById: String = "",
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
            _state.update { it.copy(transaction = txn) }

            // Only groups this device has joined/created are eligible targets.
            combine(groupRepo.observeGroups(), settings.knownGroupIds) { groups, known ->
                groups.filter { it.id in known }
            }.collect { known ->
                _state.update { s ->
                    val selected = s.selectedGroupId?.takeIf { id -> known.any { it.id == id } }
                        ?: known.firstOrNull()?.id
                    s.copy(
                        loading = false,
                        groups = known,
                        selectedGroupId = selected,
                        paidById = s.paidById.ifBlank { defaultPaidBy(known, selected) },
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

    fun onGroupChange(groupId: String) {
        _state.update { s ->
            s.copy(
                selectedGroupId = groupId,
                paidById = defaultPaidBy(s.groups, groupId),
                error = null,
            )
        }
    }

    fun onPaidByChange(participantId: String) {
        _state.update { it.copy(paidById = participantId, error = null) }
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
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            // Stamp the creator as your user id when the active participant in this group is you.
            val userId = settings.userId.first()
            val youParticipant = group.participants.firstOrNull { it.id == group.activeParticipantId }
            val addedBy = userId.takeIf { it.isNotBlank() && youParticipant?.userId == it }

            val request = buildPushToSplitRequest(txn, group, s.paidById, addedBy)
            expenseRepo.createExpense(group.id, request, idempotencyKey = txn.id).fold(
                onSuccess = { expense ->
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
