package ai.schism.split.groups.detail

import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.core.ui.UiState
import ai.schism.split.expense.data.Activity
import ai.schism.split.expense.data.Balances
import ai.schism.split.expense.data.Expense
import ai.schism.split.expense.data.ExpenseRepository
import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.GroupRepository
import ai.schism.split.groups.detail.settle.buildSettlementRequest
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    private val groupRepo: GroupRepository,
    private val expenseRepo: ExpenseRepository,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"]) { "groupId nav arg required" }

    /** The group with participants; also carries the resolved "you" via [Group.activeParticipantId]. */
    val group: StateFlow<Group?> =
        groupRepo.observeGroup(groupId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val expenses: StateFlow<UiState<List<Expense>>> =
        expenseRepo.observeExpenses(groupId)
            .map { list -> if (list.isEmpty()) UiState.Empty else UiState.Data(list) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    private val _balances = MutableStateFlow<UiState<Balances>>(UiState.Loading)
    val balances: StateFlow<UiState<Balances>> = _balances.asStateFlow()

    private val _activities = MutableStateFlow<UiState<List<Activity>>>(UiState.Loading)
    val activities: StateFlow<UiState<List<Activity>>> = _activities.asStateFlow()

    init {
        // Resolve "you" once, as soon as the group (with participants) is known — no network needed.
        viewModelScope.launch {
            resolveYou(groupRepo.observeGroup(groupId).filterNotNull().first())
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { groupRepo.refreshGroup(groupId) }
        viewModelScope.launch { expenseRepo.refreshExpenses(groupId) }
        viewModelScope.launch { loadBalances() }
        viewModelScope.launch { loadActivities() }
    }

    private suspend fun loadBalances() {
        expenseRepo.getBalances(groupId)
            .onSuccess { b ->
                _balances.value = if (b.perParticipant.isEmpty()) UiState.Empty else UiState.Data(b)
            }
            .onFailure { _balances.value = UiState.Error(it.message ?: "Couldn't load balances") }
    }

    private suspend fun loadActivities() {
        expenseRepo.getActivities(groupId)
            .onSuccess { a -> _activities.value = if (a.isEmpty()) UiState.Empty else UiState.Data(a) }
            .onFailure { _activities.value = UiState.Error(it.message ?: "Couldn't load activity") }
    }

    /**
     * If no participant is chosen yet, auto-select "you": prefer the participant linked to this
     * device's backend user id (robust across groups), falling back to a profile-name match.
     */
    private suspend fun resolveYou(g: Group?) {
        if (g == null || g.activeParticipantId != null) return
        val userId = settings.userId.first()
        val byUser = userId.takeIf { it.isNotBlank() }
            ?.let { uid -> g.participants.firstOrNull { it.userId == uid } }
        val profile = settings.profileName.first().trim()
        val byName = profile.takeIf { it.isNotEmpty() }
            ?.let { p -> g.participants.firstOrNull { it.name.trim().equals(p, ignoreCase = true) } }
        val match = byUser ?: byName
        if (match != null) groupRepo.setActiveParticipant(groupId, match.id)
    }

    fun setActiveParticipant(participantId: String) {
        viewModelScope.launch { groupRepo.setActiveParticipant(groupId, participantId) }
    }

    /**
     * Records a settle-up ([fromParticipantId] pays [toParticipantId] [amountMinor]) as a reimbursement
     * expense so the pair's balance clears, then refreshes. [onDone] runs after a successful record.
     */
    fun settle(fromParticipantId: String, toParticipantId: String, amountMinor: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            val currency = group.value?.currency ?: ""
            val request = buildSettlementRequest(fromParticipantId, toParticipantId, amountMinor, currency)
            expenseRepo.createExpense(groupId, request)
                .onSuccess {
                    refresh()
                    onDone()
                }
        }
    }

    /** Leave the group on this device: drop it from the known set (the group itself is untouched). */
    fun leave(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.removeKnownGroup(groupId)
            onDone()
        }
    }
}
