package seeyuer.sayin.asr

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in. The isolated package receives a temporary credential via adb stdin. */
@RunWith(AndroidJUnit4::class)
class QwenLiveInstrumentedTest {
    @Test fun recognizesSpeechAndCompletesTheSettingsProbe() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live Qwen test requires explicit opt-in", args.getString("liveQwen") == "true")
        val app = instrumentation.targetContext
        check(app.packageName == "seeyuer.sayin.imagetest")
        val keyFile = File(app.filesDir, "qwen-live-key")
        val key = try { keyFile.readText().trim() } finally { keyFile.delete() }
        val config = CloudConfiguration(QwenCredentials(key, checkNotNull(args.getString("workspaceId"))), listOf("SayIn"))

        val silence = recognizeCloud(config, ::cloudProbeSource, {}, {})
        assertTrue("Silence must not invent text", silence.text.isBlank())

        val raw = instrumentation.context.assets.open("x-asr-smoke.pcm").use { it.readBytes() }.copyOf(99_200)
        val samples = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var heardSpeech = false
        val speech = recognizeCloud(config, {
            object : PcmSource {
                override fun read(): FloatArray? {
                    if (!samples.hasRemaining()) return null
                    Thread.sleep(100)
                    return FloatArray(minOf(1600, samples.remaining())) { samples.get() / 32768f }
                }
                override fun requestStop() = Unit
                override fun close() = Unit
            }
        }, {}, { if (it.isNotBlank()) heardSpeech = true })
        assertTrue("Expected speech and a final transcript from the fixture", heardSpeech && speech.text.isNotBlank())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("qwenUsage", "input_tokens=${speech.usage?.inputTokens}, output_tokens=${speech.usage?.outputTokens}")
        })
    }
}
