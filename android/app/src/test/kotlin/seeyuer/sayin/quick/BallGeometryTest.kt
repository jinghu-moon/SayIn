package seeyuer.sayin.quick

import org.junit.Assert.assertEquals
import org.junit.Test

class BallGeometryTest {
    @Test fun smallBallsKeepAtLeastTheMinimumVisibleWidth() {
        assertEquals(24f / 44f, BallGeometry.effectivePeekFraction(44f, 0.30f), 0.0001f)
        assertEquals(24f / 56f, BallGeometry.effectivePeekFraction(56f, 0.30f), 0.0001f)
        assertEquals(24f / 72f, BallGeometry.effectivePeekFraction(72f, 0.30f), 0.0001f)
    }

    @Test fun requestedRevealIsReturnedWhenItAlreadyLeavesEnoughRoom() {
        assertEquals(0.41f, BallGeometry.effectivePeekFraction(72f, 0.41f), 0.0001f)
        assertEquals(24f, BallGeometry.visibleDp(44f, 0.41f), 0.0001f)
    }

    @Test fun revealIsClampedToTheSupportedRange() {
        assertEquals(24f / 72f, BallGeometry.effectivePeekFraction(72f, 0.10f), 0.0001f)
        assertEquals(0.70f, BallGeometry.effectivePeekFraction(72f, 0.95f), 0.0001f)
    }

    @Test fun expandedIconUsesTheRequestedFraction() {
        assertEquals(56f * 0.58f, BallGeometry.iconDp(56f, 0.58f, 0.41f, peeked = false, keepWhole = true), 0.0001f)
        assertEquals(56f * 0.70f, BallGeometry.iconDp(56f, 0.90f, 0.41f, peeked = false, keepWhole = false), 0.0001f)
        assertEquals(56f * 0.40f, BallGeometry.iconDp(56f, 0.10f, 0.41f, peeked = true, keepWhole = false), 0.0001f)
    }

    @Test fun containedPeekIconFitsInsideTheVisibleStrip() {
        // 44dp at 30% would leave 13.2dp; the minimum reveal raises it to 24dp, leaving 16dp inside.
        val icon = BallGeometry.iconDp(44f, 0.58f, 0.30f, peeked = true, keepWhole = true)
        assertEquals(16f, icon, 0.0001f)
        assertEquals(24f, BallGeometry.visibleDp(44f, 0.30f), 0.0001f)
    }

    @Test fun unconstrainedPeekIconKeepsItsBaseSize() {
        val icon = BallGeometry.iconDp(56f, 0.58f, 0.41f, peeked = true, keepWhole = false)
        assertEquals(56f * 0.58f, icon, 0.0001f)
    }

    @Test fun containedIconShrinksOnlyWhenNeeded() {
        // 72dp at 70% leaves 50.4dp; the 41.76dp base icon already fits.
        assertEquals(72f * 0.58f, BallGeometry.iconDp(72f, 0.58f, 0.70f, peeked = true, keepWhole = true), 0.0001f)
        // 44dp at 55% leaves 24.2dp; the base icon is shrunk to the available 16.2dp.
        assertEquals(16.2f, BallGeometry.iconDp(44f, 0.58f, 0.55f, peeked = true, keepWhole = true), 0.001f)
    }
}
