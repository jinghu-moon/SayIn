package seeyuer.sayin.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEndpointPolicyTest {
    @Test
    fun pointRecordingFinishesOnSilence() {
        assertTrue(RecordingEndpointPolicy.finishOnSilence(holdToSpeak = false, continuousDictation = false))
    }

    @Test
    fun holdToSpeakWaitsForRelease() {
        assertFalse(RecordingEndpointPolicy.finishOnSilence(holdToSpeak = true, continuousDictation = false))
    }

    @Test
    fun continuousDictationWaitsForUserStop() {
        assertFalse(RecordingEndpointPolicy.finishOnSilence(holdToSpeak = false, continuousDictation = true))
    }
}
