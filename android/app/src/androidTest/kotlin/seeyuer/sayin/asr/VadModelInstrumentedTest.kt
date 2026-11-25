package seeyuer.sayin.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VadModelInstrumentedTest {
    @Test
    fun bundledSileroModelDetectsSilenceTimeout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vad = VadAutoStop(context)
        try {
            var progress = VadEndpointTimer.State()
            for (index in 0 until 240) {
                progress = vad.accept(FloatArray(1600))
                if (progress.noSpeech) break
            }
            assertTrue(progress.noSpeech)
        } finally {
            vad.close()
        }
    }
}
