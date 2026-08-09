package ai.schism.split.walkthrough

const val CURRENT_WALKTHROUGH_SCHEMA_VERSION = 1

enum class WalkthroughStatus {
    ELIGIBLE,
    OFFERED,
    ACTIVE,
    COMPLETED,
    SKIPPED,
}

enum class WalkthroughStep {
    GROUP,
    RECEIPT,
    ASSIGN,
    BALANCES,
    LIVE_SPLIT,
}

/**
 * Persistable walkthrough progress. The account key belongs to [WalkthroughRepository], not this
 * value, so reducer transitions cannot leak progress between signed-in users.
 */
data class WalkthroughState(
    val version: Int = CURRENT_WALKTHROUGH_SCHEMA_VERSION,
    val status: WalkthroughStatus = WalkthroughStatus.ELIGIBLE,
    val currentStep: WalkthroughStep? = null,
    val dismissedHintIds: Set<String> = emptySet(),
)

sealed interface WalkthroughAction {
    data object Offer : WalkthroughAction
    data object Accept : WalkthroughAction
    data object Skip : WalkthroughAction
    data object Continue : WalkthroughAction
    data object Back : WalkthroughAction
    data class RealActionCompleted(val step: WalkthroughStep) : WalkthroughAction
    data object Complete : WalkthroughAction
    data object Replay : WalkthroughAction
    data class DismissHint(val hintId: String) : WalkthroughAction
    data object ResetTips : WalkthroughAction
    data object SignOut : WalkthroughAction
    data class Restore(val state: WalkthroughState?) : WalkthroughAction
}
