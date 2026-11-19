package seeyuer.sayin.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishAutoTest {
    @Test fun `only a new voice-only segment bound to the current config runs`() {
        assertTrue(shouldAutoPolish(recordingStartedEmpty = true, typedSinceVoice = false, bound = true))
        assertFalse(shouldAutoPolish(false, false, true))
        assertFalse(shouldAutoPolish(true, true, true))
        assertFalse(shouldAutoPolish(true, false, false))
    }
}
