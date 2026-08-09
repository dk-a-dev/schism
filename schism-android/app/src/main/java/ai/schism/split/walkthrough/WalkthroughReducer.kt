package ai.schism.split.walkthrough

/** Side-effect-free walkthrough state transitions. */
fun reduce(state: WalkthroughState, action: WalkthroughAction): WalkthroughState = when (action) {
    WalkthroughAction.Offer -> if (state.status == WalkthroughStatus.ELIGIBLE) {
        state.copy(status = WalkthroughStatus.OFFERED, currentStep = null)
    } else {
        state
    }

    WalkthroughAction.Accept -> if (
        state.status == WalkthroughStatus.OFFERED || state.status == WalkthroughStatus.ELIGIBLE
    ) {
        state.copy(status = WalkthroughStatus.ACTIVE, currentStep = WalkthroughStep.GROUP)
    } else {
        state
    }

    WalkthroughAction.Skip -> state.copy(status = WalkthroughStatus.SKIPPED, currentStep = null)

    WalkthroughAction.Continue -> advance(state)

    WalkthroughAction.Back -> moveBack(state)

    is WalkthroughAction.RealActionCompleted -> if (
        state.status == WalkthroughStatus.ACTIVE && state.currentStep == action.step
    ) {
        advance(state)
    } else {
        state
    }

    WalkthroughAction.Complete -> if (state.status == WalkthroughStatus.ACTIVE) {
        state.copy(status = WalkthroughStatus.COMPLETED, currentStep = null)
    } else {
        state
    }

    WalkthroughAction.Replay -> state.copy(
        version = CURRENT_WALKTHROUGH_SCHEMA_VERSION,
        status = WalkthroughStatus.ACTIVE,
        currentStep = WalkthroughStep.GROUP,
    )

    is WalkthroughAction.DismissHint -> if (action.hintId.isBlank()) {
        state
    } else {
        state.copy(dismissedHintIds = state.dismissedHintIds + action.hintId)
    }

    WalkthroughAction.ResetTips -> state.copy(dismissedHintIds = emptySet())

    WalkthroughAction.SignOut -> WalkthroughState()

    is WalkthroughAction.Restore -> restore(action.state)
}

object WalkthroughReducer {
    fun reduce(state: WalkthroughState, action: WalkthroughAction): WalkthroughState =
        ai.schism.split.walkthrough.reduce(state, action)
}

private fun advance(state: WalkthroughState): WalkthroughState {
    if (state.status != WalkthroughStatus.ACTIVE) return state
    val step = state.currentStep ?: return state
    val nextIndex = step.ordinal + 1
    return if (nextIndex > WalkthroughStep.entries.lastIndex) {
        state.copy(status = WalkthroughStatus.COMPLETED, currentStep = null)
    } else {
        state.copy(currentStep = WalkthroughStep.entries[nextIndex])
    }
}

private fun moveBack(state: WalkthroughState): WalkthroughState {
    if (state.status != WalkthroughStatus.ACTIVE) return state
    val step = state.currentStep ?: return state
    val previousIndex = (step.ordinal - 1).coerceAtLeast(0)
    return state.copy(currentStep = WalkthroughStep.entries[previousIndex])
}

private fun restore(restored: WalkthroughState?): WalkthroughState {
    if (restored == null) return WalkthroughState()
    if (restored.version != CURRENT_WALKTHROUGH_SCHEMA_VERSION) {
        return WalkthroughState(status = WalkthroughStatus.OFFERED)
    }
    return when {
        restored.status == WalkthroughStatus.ACTIVE && restored.currentStep == null ->
            restored.copy(status = WalkthroughStatus.OFFERED)

        restored.status != WalkthroughStatus.ACTIVE && restored.currentStep != null ->
            restored.copy(currentStep = null)

        else -> restored
    }
}
