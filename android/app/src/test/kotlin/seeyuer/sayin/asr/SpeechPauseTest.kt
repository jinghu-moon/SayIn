package seeyuer.sayin.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPauseTest {
    @Test fun initialSilenceAndBriefNoiseDoNotFinishRecording() {
        val pause = SpeechPause()
        repeat(30) { assertFalse(pause.update(0f, 1600)) }
        assertFalse(pause.update(0.02f, 1600))
        repeat(15) { assertFalse(pause.update(0f, 1600)) }
    }

    @Test fun resumedSpeechResetsTheFullQuietPeriod() {
        val pause = SpeechPause()
        repeat(2) { assertFalse(pause.update(0.02f, 1600)) }
        repeat(14) { assertFalse(pause.update(0.001f, 1600)) }
        assertFalse(pause.update(0.008f, 1600))
        repeat(14) { assertFalse(pause.update(0f, 1600)) }
        assertFalse(pause.update(0f, 1599))
        assertTrue(pause.update(0f, 1))
        assertFalse(pause.update(0.02f, 1600))
    }
}
