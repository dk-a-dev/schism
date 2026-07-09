package ai.schism.split.sms.itemized.claim

import ai.schism.split.core.net.ClaimDto
import ai.schism.split.core.net.ClaimSessionDto
import ai.schism.split.core.net.ClaimWeightDto
import ai.schism.split.core.net.ResolutionDto
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.groups.data.GroupRepository
import ai.schism.split.groups.data.Participant
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Session statuses reported by the backend. */
private const val STATUS_OPEN = "open"

data class ClaimUiState(
    val loading: Boolean = true,
    val session: ClaimSessionDto? = null,
    val myParticipantId: String = "",
    /** The session's group's participants, for names/avatars (the claims list is id-only). */
    val participants: List<Participant> = emptyList(),
    /** This device's own in-flight weight edits (item index -> weight), applied optimistically. */
    val myWeights: Map<Int, Double> = emptyMap(),
    val error: String? = null,
) {
    val status: String get() = session?.status ?: STATUS_OPEN
    val isCreator: Boolean
        get() = session != null && myParticipantId.isNotBlank() && session.creatorParticipantId == myParticipantId

    /** This device's own weight for [itemIdx]: an in-flight edit if any, else the last-known server claim. */
    fun weightFor(itemIdx: Int): Double =
        myWeights[itemIdx]
            ?: session?.claims?.firstOrNull { it.itemIdx == itemIdx && it.participantId == myParticipantId }?.weight
            ?: 0.0

    /** Live "you owe" total, using this device's in-flight weights so it doesn't wait for a poll. */
    val myOwes: Long
        get() {
            val s = session ?: return 0L
            val others = s.claims.filterNot { it.participantId == myParticipantId }
            val mine = allMyWeights().filter { it.value > 0 }.map { (idx, w) -> ClaimDto(idx, myParticipantId, w) }
            val claims = others + mine
            return previewOwes(s.items, claims, s.taxMinor, s.feesMinor, s.discountMinor, s.roundoffMinor)[myParticipantId] ?: 0L
        }

    /** This device's full weight map: server-known claims for [myParticipantId], overlaid with in-flight edits. */
    fun allMyWeights(): Map<Int, Double> {
        val fromServer = session?.claims.orEmpty()
            .filter { it.participantId == myParticipantId }
            .associate { it.itemIdx to it.weight }
        return fromServer + myWeights
    }

    /** Participants with a positive claimed weight on [itemIdx], for a "claimed by" avatar row. */
    fun claimantsFor(itemIdx: Int): List<Participant> {
        val claimantIds = session?.claims.orEmpty()
            .filter { it.itemIdx == itemIdx && it.weight > 0 }
            .map { it.participantId }
            .toSet()
        return participants.filter { it.id in claimantIds }
    }

    /** Item indices nobody has claimed any weight on — the creator must resolve these to finalize. */
    val unclaimedItemIndices: List<Int>
        get() {
            val claimedIdx = session?.claims.orEmpty().filter { it.weight > 0 }.map { it.itemIdx }.toSet()
            return session?.items.orEmpty().map { it.idx }.filterNot { it in claimedIdx }
        }
}

/**
 * Drives the claim screen: polls the session every 3s (stopping once it's no longer `open`), lets
 * the caller edit their own weights (debounced into a single `PUT` per burst of taps), and maps the
 * server's 409s — `LOCKED` freezes the screen as finalized, `VERSION_STALE` refetches while keeping
 * this device's still-valid in-flight weights.
 */
@HiltViewModel
class ClaimSessionViewModel @Inject constructor(
    private val repo: ClaimSessionRepository,
    private val groupRepo: GroupRepository,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sid: String = checkNotNull(savedStateHandle["sid"]) { "sid nav arg required" }

    private val _state = MutableStateFlow(ClaimUiState())
    val state: StateFlow<ClaimUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var pushJob: Job? = null

    init {
        viewModelScope.launch {
            refresh()
            resolveMyParticipant()
            startPolling()
        }
    }

    /** Resolve "you" within the session's group: the participant linked to this device's user id or
     * the group's already-active participant (see [ai.schism.split.groups.detail.GroupDetailViewModel]). */
    private suspend fun resolveMyParticipant() {
        val groupId = _state.value.session?.groupId ?: return
        val group = groupRepo.observeGroup(groupId).first() ?: return
        val userId = settings.userId.first()
        val byUser = userId.takeIf { it.isNotBlank() }
            ?.let { uid -> group.participants.firstOrNull { it.userId == uid } }
        val active = group.activeParticipantId?.let { id -> group.participants.firstOrNull { it.id == id } }
        val mine = byUser ?: active
        _state.update {
            it.copy(
                myParticipantId = mine?.id ?: it.myParticipantId,
                participants = group.participants,
            )
        }
    }

    private suspend fun refresh() {
        repo.getSession(sid)
            .onSuccess { s -> _state.update { it.copy(loading = false, session = s, error = null) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load session") } }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (_state.value.status != STATUS_OPEN) break
                refresh()
                if (_state.value.status != STATUS_OPEN) break
            }
        }
    }

    /** Adjust this device's weight for an item by [delta] (+0.5 / -0.5, …), clamped at 0. */
    fun adjustWeight(itemIdx: Int, delta: Double) {
        setWeight(itemIdx, (_state.value.weightFor(itemIdx) + delta).coerceAtLeast(0.0))
    }

    /** Set this device's weight for an item directly (typed entry). Weight 0 = no claim. */
    fun setWeight(itemIdx: Int, weight: Double) {
        val clamped = weight.coerceAtLeast(0.0)
        _state.update { it.copy(myWeights = it.myWeights + (itemIdx to clamped)) }
        debouncedPush()
    }

    /** Coalesce a burst of stepper taps into one `PUT`. */
    private fun debouncedPush() {
        pushJob?.cancel()
        pushJob = viewModelScope.launch {
            delay(PUSH_DEBOUNCE_MS)
            submitWeights()
        }
    }

    private suspend fun submitWeights() {
        val s = _state.value
        val session = s.session ?: return
        val dtos = s.allMyWeights().map { (idx, w) -> ClaimWeightDto(idx, w) }
        repo.putClaims(sid, session.version, dtos)
            .onSuccess {
                _state.update { it.copy(myWeights = emptyMap()) }
                refresh()
            }
            .onFailure { e -> handlePutClaimsFailure(e) }
    }

    private suspend fun handlePutClaimsFailure(e: Throwable) {
        when (e) {
            is ClaimError.Stale -> refresh() // keeps this device's in-flight myWeights; only the session is replaced.
            is ClaimError.Locked -> {
                pollJob?.cancel()
                _state.update {
                    it.copy(session = it.session?.copy(status = "finalized"), error = e.message)
                }
            }
            else -> _state.update { it.copy(error = e.message ?: "Couldn't save your claim") }
        }
    }

    /** Creator-only: lock the session into a finalized expense. See [FinalizeSheet]. */
    fun finalize(resolutions: List<ResolutionDto>, onDone: (String) -> Unit) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            repo.finalizeSession(sid, session.version, resolutions)
                .onSuccess { resp ->
                    pollJob?.cancel()
                    _state.update { it.copy(session = it.session?.copy(status = "finalized", expenseId = resp.expenseId)) }
                    onDone(resp.expenseId)
                }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "Couldn't finalize") } }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        pushJob?.cancel()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3000L
        private const val PUSH_DEBOUNCE_MS = 400L
    }
}
