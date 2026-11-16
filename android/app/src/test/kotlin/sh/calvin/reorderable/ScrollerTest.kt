package sh.calvin.reorderable

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollerTest {
    @Test fun immediateLaunchReceivesTheFirstScrollCommand() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = BoundaryScrollState()
            val scroller = Scroller(state, scope) { 100f }
            var calls = 0
            scroller.start(Scroller.Direction.FORWARD, onScroll = {
                calls++
                state.canScroll = false
            })
            assertEquals(1, calls)
        } finally {
            scope.cancel()
        }
    }

    @Test fun completedBoundaryScrollRestartsInTheOppositeDirection() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = BoundaryScrollState()
            val scroller = Scroller(state, scope) { 100f }
            val directions = mutableListOf<Scroller.Direction>()
            for (direction in listOf(Scroller.Direction.FORWARD, Scroller.Direction.BACKWARD)) {
                state.canScroll = true
                scroller.start(direction, onScroll = {
                    directions += direction
                    state.canScroll = false
                })
            }
            assertEquals(listOf(Scroller.Direction.FORWARD, Scroller.Direction.BACKWARD), directions)
        } finally {
            scope.cancel()
        }
    }

    // The callback reaches the boundary before any animation, isolating launch/channel ordering
    // from the frame clock. Unconfined executes launch immediately, as the UI dispatcher may do.
    private class BoundaryScrollState : ScrollableState {
        var canScroll = true
        override val isScrollInProgress = false
        override val canScrollForward get() = canScroll
        override val canScrollBackward get() = canScroll
        override suspend fun scroll(scrollPriority: MutatePriority, block: suspend ScrollScope.() -> Unit) =
            error("Already at the boundary after onScroll")
        override fun dispatchRawDelta(delta: Float): Float = error("No animation expected")
    }
}
