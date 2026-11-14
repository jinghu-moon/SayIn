package seeyuer.sayin.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android JNI/assets/CPU inference. No Activity, microphone, transport or user data access. */
@RunWith(AndroidJUnit4::class)
class XAsrInstrumentedTest {
    @Test fun preparationWarmsModelWithoutOpeningMicrophoneOrStartingSession() = runBlocking {
        val recognition = withContext(Dispatchers.Main) { SpeechRecognition(InstrumentationRegistry.getInstrumentation().targetContext) }
        try {
            withContext(Dispatchers.Main) { recognition.prepareLocal() }
            withTimeout(30000) { while (recognition.model.value !in setOf(LocalModelPhase.Ready, LocalModelPhase.Failed)) delay(20) }
            assertEquals(LocalModelPhase.Ready, recognition.model.value)
            assertEquals(RecognitionPhase.Idle, recognition.state.value.phase)
            assertEquals(0L, recognition.state.value.session)
            assertEquals("", recognition.state.value.transcript)
            withContext(Dispatchers.Main) { recognition.prepareLocal() }
            assertEquals(LocalModelPhase.Ready, recognition.model.value)
        } finally { withContext(Dispatchers.Main) { recognition.close() } }
    }

    @Test fun realStreamingModelRecognizesMixedSpeechAndSilence() = runBlocking {
        withContext(Dispatchers.Default) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val raw = instrumentation.context.assets.open("x-asr-smoke.pcm").use { it.readBytes() }
            val pcm = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = FloatArray(pcm.remaining()) { pcm.get() / 32768f }
            val hypotheses = mutableListOf<String>()
            XAsrDecoder(instrumentation.targetContext.assets).use { decoder ->
                decoder.warmUp()
                assertEquals("", decoder.text())
                val transcript = StreamingTranscript()
                for (offset in samples.indices step 1600) {
                    decoder.accept(samples.copyOfRange(offset, minOf(offset + 1600, samples.size)))
                    decoder.decodeReady()
                    val endpoint = decoder.isEndpoint()
                    hypotheses += transcript.update(decoder.text(), endpoint)
                    if (endpoint) decoder.reset()
                }
                decoder.finishInput()
                decoder.decodeReady()
                val final = transcript.update(decoder.text(), false)
                assertTrue("Chinese final word lost: $final", final.contains("星期三"))
                assertTrue("English recognition missing: $final", final.contains("Monday"))
                assertTrue("Punctuation missing: $final", final.contains("，"))
                assertTrue("Expected changing partial results", hypotheses.filter { it.isNotBlank() }.distinct().size >= 3)
                // A new stream on the same warmed weights must not retain the prior transcript.
                decoder.restart()
                repeat(30) { decoder.accept(FloatArray(1600)); decoder.decodeReady() }
                decoder.finishInput()
                decoder.decodeReady()
                assertEquals("", decoder.text().trim())
            }
            // A second recognizer also checks explicit disposal and reloading after a session.
            XAsrDecoder(instrumentation.targetContext.assets).use { decoder ->
                repeat(30) { decoder.accept(FloatArray(1600)); decoder.decodeReady() }
                decoder.finishInput()
                decoder.decodeReady()
                assertEquals("", decoder.text().trim())
            }
        }
    }
}
