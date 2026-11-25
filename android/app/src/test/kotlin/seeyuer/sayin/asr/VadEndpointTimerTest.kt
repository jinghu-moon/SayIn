package seeyuer.sayin.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
        assertFalse(timer.feed(speech = false, milliseconds = 500).ended)
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

    @Test
    fun isolatedNoiseDoesNotCountAsContinuousVoiceAndTimeoutStaysLatched() {
        val timer = VadEndpointTimer()
        repeat(10) {
            timer.feed(speech = true, milliseconds = 32)
            timer.feed(speech = false, milliseconds = 32)
        }
        assertTrue(timer.feed(speech = false, milliseconds = 10_000).noSpeech)
        val afterTimeout = timer.feed(speech = true, milliseconds = 300)
        assertTrue(afterTimeout.noSpeech)
        assertFalse(afterTimeout.ended)
    }

    @Test
    fun holdAndContinuousModeStillEmitSegmentBoundariesWithoutStopping() {
        val timer = VadEndpointTimer(autoFinish = false)
        assertFalse(timer.feed(speech = false, milliseconds = 12_000).noSpeech)
        timer.feed(speech = true, milliseconds = 300)
        assertTrue(timer.feed(speech = false, milliseconds = 580).segmentBoundary)
        assertFalse(timer.feed(speech = false, milliseconds = 12_000).ended)
        timer.feed(speech = true, milliseconds = 300)
        assertTrue(timer.feed(speech = false, milliseconds = 580).segmentBoundary)
    }

    @Test
    fun localSegmentDoesNotResetAnyOfTheThreeStopDelays() {
        for (millis in LocalSilence.options) {
            val timer = VadEndpointTimer(localSilenceMillis = millis)
            repeat(10) { timer.accept(1f, threshold, frame) }
            val framesUntilStop = (millis * 16 + frame - 1) / frame
            var boundaries = 0
            repeat(framesUntilStop - 1) {
                val progress = timer.accept(0f, threshold, frame)
                if (progress.segmentBoundary) boundaries++
                assertFalse(progress.ended)
            }
            assertEquals(1, boundaries)
            assertTrue(timer.accept(0f, threshold, frame).ended)
            assertEquals(10L * frame, timer.lastPossibleVoiceSample)
        }
    }

    @Test
    fun localShortSpeechWithAnUnvoicedFrameArmsBothClocks() {
        val timer = VadEndpointTimer(localSilenceMillis = 2_500)
        for (voiced in listOf(true, true, false, true, true)) {
            timer.accept(if (voiced) 1f else 0f, threshold, frame)
        }
        repeat(18) { assertFalse(timer.accept(0f, threshold, frame).segmentBoundary) }
        assertTrue(timer.accept(0f, threshold, frame).segmentBoundary)
        repeat(59) { assertFalse(timer.accept(0f, threshold, frame).ended) }
        assertTrue(timer.accept(0f, threshold, frame).ended)
    }

    @Test
    fun localIsolatedTapDoesNotExtendSilenceDeadline() {
        val timer = VadEndpointTimer(localSilenceMillis = 2_500)
        timer.feed(true, 300)
        repeat(78) { index ->
            assertFalse(timer.accept(if (index == 73) 1f else 0f, threshold, frame).ended)
        }
        assertTrue(timer.accept(0f, threshold, frame).ended)
    }

    @Test
    fun localCandidateBeforeDeadlineEitherConfirmsOrStopsWithoutResettingClock() {
        for (confirm in listOf(false, true)) {
            val timer = VadEndpointTimer(localSilenceMillis = 2_000)
            timer.feed(true, 320)
            timer.feed(false, 1_952)
            assertFalse(timer.accept(1f, threshold, frame).ended)
            assertFalse(timer.accept(1f, threshold, frame).ended)
            val result = timer.accept(if (confirm) 1f else 0f, threshold, frame)
            assertEquals(!confirm, result.ended)
            if (confirm) assertFalse(timer.feed(false, 1_000).ended)
        }
    }
}
