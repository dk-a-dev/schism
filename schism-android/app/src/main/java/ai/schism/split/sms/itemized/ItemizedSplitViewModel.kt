package ai.schism.split.sms.itemized

import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.expense.data.ExpenseRepository
import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.GroupRepository
import ai.schism.split.sms.receipt.ReceiptDraft
import ai.schism.split.sms.receipt.ReceiptLineItem
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
import java.util.UUID
import javax.inject.Inject

data class ItemizedSplitUiState(
    val loading: Boolean = true,
    /** True when the on-device AI model is downloaded + enabled (so parsing was the smart path). */
    val aiActive: Boolean = false,
    val draft: ReceiptDraft? = null,
    val title: String = "",
    val items: List<ReceiptLineItem> = emptyList(),
    val groups: List<Group> = emptyList(),
    val selectedGroupId: String? = null,
    val paidById: String = "",
    // Item index -> the participant ids currently sharing that item.
    val assignments: Map<Int, Set<String>> = emptyMap(),
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val selectedGroup: Group? get() = groups.firstOrNull { it.id == selectedGroupId }
    val taxMinor: Long get() = draft?.taxMinor ?: 0L

    /** Live per-participant owed totals in minor units (item shares + proportional tax). */
    val perPersonMinor: Map<String, Long>
        get() {
            val owed = LinkedHashMap<String, Long>()
            items.forEachIndexed { index, item ->
                val assignees = assignments[index].orEmpty().toList()
                if (assignees.isEmpty()) return@forEachIndexed
                val base = item.amountMinor / assignees.size
                var remainder = item.amountMinor - base * assignees.size
                for (id in assignees) {
                    val extra = if (remainder > 0) 1L else 0L
                    if (remainder > 0) remainder--
                    owed[id] = (owed[id] ?: 0L) + base + extra
                }
            }
            val subtotal = owed.values.sum()
            if (taxMinor > 0 && subtotal > 0) {
                var remaining = taxMinor
                val entries = owed.toMap().entries.toList()
                entries.forEachIndexed { i, (pid, sub) ->
                    val share = if (i == entries.lastIndex) remaining else taxMinor * sub / subtotal
                    remaining -= share
                    owed[pid] = (owed[pid] ?: 0L) + share
                }
            }
            return owed
        }
}

@HiltViewModel
class ItemizedSplitViewModel @Inject constructor(
    private val pending: PendingReceipt,
    private val groupRepo: GroupRepository,
    private val expenseRepo: ExpenseRepository,
    private val settings: SettingsRepository,
    private val llmParser: ai.schism.split.core.ai.LlmExpenseParser,
) : ViewModel() {

    private val _state = MutableStateFlow(ItemizedSplitUiState())
    val state: StateFlow<ItemizedSplitUiState> = _state.asStateFlow()

    init {
        val draft = pending.draft
        _state.update {
            it.copy(draft = draft, items = draft?.lineItems.orEmpty(), title = draft?.merchant ?: "Receipt")
        }

        viewModelScope.launch {
            val aiActive = llmParser.isAvailable && settings.aiEnabled.first()
            _state.update { it.copy(aiActive = aiActive) }
        }

        viewModelScope.launch {
            // Only groups this device has joined/created are eligible targets.
            combine(groupRepo.observeGroups(), settings.knownGroupIds) { groups, known ->
                groups.filter { it.id in known }
            }.collect { known ->
                _state.update { s ->
                    val selectedId = s.selectedGroupId?.takeIf { id -> known.any { it.id == id } }
                        ?: known.firstOrNull()?.id
                    val selected = known.firstOrNull { it.id == selectedId }
                    s.copy(
                        loading = false,
                        groups = known,
                        selectedGroupId = selectedId,
                        paidById = s.paidById.ifBlank { defaultPaidBy(selected) },
                        assignments = s.assignments.ifEmpty { everyoneAssigned(s.items, selected) },
                    )
                }
            }
        }
    }

    /** Default: every item is shared by everyone in the selected group. */
    private fun everyoneAssigned(items: List<ReceiptLineItem>, group: Group?): Map<Int, Set<String>> {
        val everyone = group?.participants?.map { it.id }?.toSet() ?: emptySet()
        return items.indices.associateWith { everyone }
    }

    private fun defaultPaidBy(group: Group?): String {
        group ?: return ""
        return group.activeParticipantId ?: group.participants.firstOrNull()?.id ?: ""
    }

    fun onGroupChange(groupId: String) {
        _state.update { s ->
            val group = s.groups.firstOrNull { it.id == groupId }
            s.copy(
                selectedGroupId = groupId,
                paidById = defaultPaidBy(group),
                assignments = everyoneAssigned(s.items, group),
                error = null,
            )
        }
    }

    fun onTitleChange(value: String) {
        _state.update { it.copy(title = value, error = null) }
    }

    /** Toggle whether [participantId] shares the item at [itemIndex]. */
    fun toggleAssignment(itemIndex: Int, participantId: String) {
        _state.update { s ->
            val current = s.assignments[itemIndex].orEmpty()
            val updated = if (participantId in current) current - participantId else current + participantId
            s.copy(assignments = s.assignments + (itemIndex to updated), error = null)
        }
    }

    fun submit(onDone: () -> Unit) {
        val s = _state.value
        val group = s.selectedGroup
        if (group == null) {
            _state.update { it.copy(error = "Pick a group to split into") }
            return
        }
        // You're adding it, so you paid: default to your participant, else the first one.
        val payerId = s.paidById.ifBlank { group.activeParticipantId ?: group.participants.firstOrNull()?.id.orEmpty() }
        val assigned = s.items.mapIndexed { index, item ->
            AssignedItem(amountMinor = item.amountMinor, participantIds = s.assignments[index].orEmpty().toList())
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }

            // Stamp the creator as your user id when the active participant in this group is you.
            val userId = settings.userId.first()
            val youParticipant = group.participants.firstOrNull { it.id == group.activeParticipantId }
            val addedBy = userId.takeIf { it.isNotBlank() && youParticipant?.userId == it }

            val request = buildItemizedExpenseRequest(
                items = assigned,
                group = group,
                paidById = payerId,
                addedBy = addedBy,
                title = s.title.trim().ifBlank { s.draft?.merchant ?: "Receipt" },
                currency = s.draft?.currency ?: "₹",
                dateIso = s.draft?.date,
                taxMinor = s.taxMinor,
            )
            if (request == null) {
                _state.update { it.copy(submitting = false, error = "Assign at least one item to someone") }
                return@launch
            }
            expenseRepo.createExpense(group.id, request, idempotencyKey = UUID.randomUUID().toString()).fold(
                onSuccess = {
                    pending.draft = null
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
