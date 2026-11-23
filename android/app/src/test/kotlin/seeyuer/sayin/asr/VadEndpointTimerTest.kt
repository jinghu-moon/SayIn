package seeyuer.sayin.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadEndpointTimerTest {
    private val frame = 512
    private val threshold = 0.45f

    private fun VadEndpointTimer.feed(speech: Boolean, milliseconds: Int): VadEndpointTimer.State {
        val frames = ((milliseconds * 16 + frame - 1) / frame).coerceAtLeast(1)
        var state = VadEndpointTimer.State()
        repeat(frames) { state = accept(if (speech) 1f else 0f, threshold, frame) }
        return state
    }

    @Test
    fun shortCommandUsesShortFinalize() {
        val timer = VadEndpointTimer()
        timer.feed(speech = true, milliseconds = 300)
        assertFalse(timer.feed(speech = false, milliseconds = 700).ended)
        assertTrue(timer.feed(speech = false, milliseconds = 150).ended)
    }

    @Test
    fun longSpeechUsesLongerFinalize() {
        val timer = VadEndpointTimer()
        timer.feed(speech = true, milliseconds = 2_000)
        assertFalse(timer.feed(speech = false, milliseconds = 1_000).ended)
        assertTrue(timer.feed(speech = false, milliseconds = 300).ended)
    }

    @Test
    fun resumedSpeechCancelsTrailing() {
        val timer = VadEndpointTimer()
        timer.feed(speech = true, milliseconds = 300)
        val trailing = timer.feed(speech = false, milliseconds = 500)
        assertTrue(trailing.ending)
        timer.feed(speech = true, milliseconds = 300)
        assertFalse(timer.feed(speech = false, milliseconds = 500).ended)
    }

    @Test
    fun silenceBeforeSpeechDoesNotEnd() {
        val timer = VadEndpointTimer()
        val state = timer.feed(speech = false, milliseconds = 3_000)
        assertFalse(state.ended)
        assertFalse(state.noSpeech)
    }

    @Test
    fun noSpeechTimesOut() {
        val timer = VadEndpointTimer()
        val state = timer.feed(speech = false, milliseconds = 10_000)
        assertTrue(state.noSpeech)
    }
}
