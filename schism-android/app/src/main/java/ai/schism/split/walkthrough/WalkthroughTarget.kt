package ai.schism.split.walkthrough

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.max

/** Every UI element a coach mark or contextual hint can point at. */
enum class WalkthroughTargetId {
    DEMO_GROUP,
    DEMO_RECEIPT,
    DEMO_ASSIGN,
    DEMO_BALANCES,
    DEMO_LIVE_SPLIT,
    SMS_OPT_IN,
    OCR_DOWNLOAD,
    PARTICIPANT_INVITE,
    UPI_SETTLE,
    LIVE_SPLIT_HOST,
}

/**
 * Window-space bounds of the currently composed targets. A target that leaves the composition (a
 * navigation away, a collapsed section) removes itself, so the overlay always falls back to a
 * centred card instead of pointing at stale coordinates.
 */
@Stable
class WalkthroughTargetRegistry {
    private val bounds = mutableStateMapOf<WalkthroughTargetId, Rect>()

    operator fun get(id: WalkthroughTargetId): Rect? = bounds[id]

    fun register(id: WalkthroughTargetId, rect: Rect) {
        bounds[id] = rect
    }

    fun unregister(id: WalkthroughTargetId) {
        bounds.remove(id)
    }
}

val LocalWalkthroughTargets = staticCompositionLocalOf { WalkthroughTargetRegistry() }

/** Publishes this element's window bounds so [WalkthroughOverlay] can highlight it. */
fun Modifier.walkthroughTarget(id: WalkthroughTargetId): Modifier = composed {
    val registry = LocalWalkthroughTargets.current
    DisposableEffect(registry, id) { onDispose { registry.unregister(id) } }
    onGloballyPositioned { registry.register(id, it.boundsInWindow()) }
}

enum class CoachMarkPlacement { ABOVE, BELOW, CENTER }

/**
 * Where the coach-mark card sits, in the same window coordinates as the registered target.
 * [highlight] is null when there is nothing usable to point at.
 */
data class CoachMarkLayout(
    val placement: CoachMarkPlacement,
    val cardTop: Float,
    val highlight: Rect?,
)

/**
 * Pure placement: prefer below the target, else above, else centre. A missing, empty, or off-screen
 * target degrades to a centred card with no highlight, which is also what an RTL or 200%-font layout
 * gets when the card no longer fits beside the target.
 *
 * [safeArea] must already exclude system bars/cutout insets — placement never reasons about them.
 */
fun coachMarkLayout(
    target: Rect?,
    safeArea: Rect,
    cardHeight: Float,
    gap: Float = 0f,
): CoachMarkLayout {
    val centeredTop = (safeArea.top + (safeArea.height - cardHeight) / 2f)
        .coerceIn(safeArea.top, max(safeArea.top, safeArea.bottom - cardHeight))
    val visible = target?.takeIf { !it.isEmpty && it.overlaps(safeArea) }
        ?: return CoachMarkLayout(CoachMarkPlacement.CENTER, centeredTop, null)

    val below = visible.bottom + gap
    if (below + cardHeight <= safeArea.bottom) {
        return CoachMarkLayout(CoachMarkPlacement.BELOW, below, visible)
    }
    val above = visible.top - gap - cardHeight
    if (above >= safeArea.top) {
        return CoachMarkLayout(CoachMarkPlacement.ABOVE, above, visible)
    }
    return CoachMarkLayout(CoachMarkPlacement.CENTER, centeredTop, visible)
}
