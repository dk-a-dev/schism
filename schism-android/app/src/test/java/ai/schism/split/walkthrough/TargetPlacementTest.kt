package ai.schism.split.walkthrough

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Placement is pure geometry, so portrait/landscape, RTL, and 200% font all reduce to "the card is
 * this tall and the safe area is this big".
 */
class TargetPlacementTest {
    private val safeArea = Rect(0f, 100f, 1080f, 2200f) // status bar + nav bar already excluded

    @Test
    fun aMissingTargetCentresTheCardWithNoHighlight() {
        val layout = coachMarkLayout(target = null, safeArea = safeArea, cardHeight = 400f)

        assertEquals(CoachMarkPlacement.CENTER, layout.placement)
        assertNull(layout.highlight)
        assertEquals(950f, layout.cardTop, 0.01f)
    }

    @Test
    fun aTargetRemovedDuringNavigationBehavesLikeAMissingTarget() {
        val registry = WalkthroughTargetRegistry()
        registry.register(WalkthroughTargetId.DEMO_GROUP, Rect(0f, 200f, 1080f, 400f))
        registry.unregister(WalkthroughTargetId.DEMO_GROUP)

        assertNull(registry[WalkthroughTargetId.DEMO_GROUP])
        assertNull(
            coachMarkLayout(registry[WalkthroughTargetId.DEMO_GROUP], safeArea, 400f).highlight,
        )
    }

    @Test
    fun anOffscreenOrEmptyTargetIsNotHighlighted() {
        val offscreen = coachMarkLayout(Rect(0f, 4000f, 1080f, 4200f), safeArea, 400f)
        assertEquals(CoachMarkPlacement.CENTER, offscreen.placement)
        assertNull(offscreen.highlight)

        val empty = coachMarkLayout(Rect(10f, 300f, 10f, 300f), safeArea, 400f)
        assertNull(empty.highlight)
    }

    @Test
    fun theCardSitsBelowATopTargetAndAboveABottomTarget() {
        val top = coachMarkLayout(Rect(0f, 200f, 1080f, 400f), safeArea, 400f, gap = 20f)
        assertEquals(CoachMarkPlacement.BELOW, top.placement)
        assertEquals(420f, top.cardTop, 0.01f)

        val bottom = coachMarkLayout(Rect(0f, 1900f, 1080f, 2100f), safeArea, 400f, gap = 20f)
        assertEquals(CoachMarkPlacement.ABOVE, bottom.placement)
        assertEquals(1480f, bottom.cardTop, 0.01f)
    }

    @Test
    fun placementNeverEscapesTheSafeInsets() {
        // Landscape-sized safe area with a 200%-font card that fits neither above nor below.
        val landscape = Rect(0f, 60f, 2200f, 1000f)
        val layout = coachMarkLayout(Rect(0f, 400f, 2200f, 600f), landscape, cardHeight = 800f, gap = 20f)

        assertEquals(CoachMarkPlacement.CENTER, layout.placement)
        assertEquals(130f, layout.cardTop, 0.01f)
        assertTrue(layout.cardTop >= landscape.top)
        assertEquals(Rect(0f, 400f, 2200f, 600f), layout.highlight)
    }

    @Test
    fun aCardTallerThanTheSafeAreaStillStartsInsideIt() {
        val layout = coachMarkLayout(null, safeArea, cardHeight = 5000f)

        assertEquals(safeArea.top, layout.cardTop, 0.01f)
    }
}
