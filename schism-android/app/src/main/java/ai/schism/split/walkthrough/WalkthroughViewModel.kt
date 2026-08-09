package ai.schism.split.walkthrough

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns walkthrough progress and the throw-away demo data.
 *
 * The only injected dependencies are the progress store and the account id. The demo repository is
 * constructed here, so nothing in the tour can reach Room, the backend, permissions, OCR, billing,
 * ads, or a share intent — those objects are not in scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WalkthroughViewModel @Inject constructor(
    private val repository: WalkthroughRepository,
    @WalkthroughUserId userIds: Flow<String>,
) : ViewModel() {

    private val demo = DemoRepository()
    private var accountId = DataStoreWalkthroughRepository.ANONYMOUS_ACCOUNT

    private val _state = MutableStateFlow(WalkthroughState())
    val state: StateFlow<WalkthroughState> = _state.asStateFlow()

    private val _demoSnapshot = MutableStateFlow(demo.snapshot)
    val demoSnapshot: StateFlow<DemoSnapshot> = _demoSnapshot.asStateFlow()

    init {
        viewModelScope.launch {
            userIds
                .onEach { accountId = it.ifBlank { DataStoreWalkthroughRepository.ANONYMOUS_ACCOUNT } }
                .flatMapLatest { repository.observe(accountId) }
                .collect { stored -> _state.value = reduce(_state.value, WalkthroughAction.Restore(stored)) }
        }
    }

    fun dispatch(action: WalkthroughAction) {
        val next = reduce(_state.value, action)
        if (next == _state.value) return
        _state.value = next
        viewModelScope.launch { repository.save(accountId, next) }
    }

    fun offerTour() = dispatch(WalkthroughAction.Offer)

    fun acceptTour() = dispatch(WalkthroughAction.Accept)

    /** Always available: the offer, every coach mark, and the demo screen all route here. */
    fun skipTour() {
        dispatch(WalkthroughAction.Skip)
        endDemo()
    }

    fun back() = dispatch(WalkthroughAction.Back)

    fun replayTour() = dispatch(WalkthroughAction.Replay)

    fun resetTips() = dispatch(WalkthroughAction.ResetTips)

    fun dismissHint(hint: FeatureHint) = dispatch(WalkthroughAction.DismissHint(hint.id))

    /** Called when the user really performed [step]'s action, not merely tapped Continue. */
    fun completeStep(step: WalkthroughStep) {
        dispatch(WalkthroughAction.RealActionCompleted(step))
        if (_state.value.status != WalkthroughStatus.ACTIVE) endDemo()
    }

    fun assignItem(itemId: String, participantIds: Set<String>) {
        _demoSnapshot.value = demo.assign(itemId, participantIds)
        completeStep(WalkthroughStep.ASSIGN)
    }

    /** Sign-out clears this account's progress; a different account starts from its own record. */
    fun signOut() {
        dispatch(WalkthroughAction.SignOut)
        endDemo()
    }

    /** Drops every demo mutation so the user returns to their genuine app state. */
    fun endDemo() {
        _demoSnapshot.value = demo.reset()
    }
}
