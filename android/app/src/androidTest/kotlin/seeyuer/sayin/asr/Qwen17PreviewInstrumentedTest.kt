package seeyuer.sayin.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Qwen17PreviewInstrumentedTest {
    @Test
    fun qwen17CanProduceGuardedPreviewAndFinalText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "local-models/qwen-17")
        assumeTrue("Qwen 1.7B model is not imported on this device", directory.isDirectory)
        val pcm = InstrumentationRegistry.getInstrumentation().context.assets
            .open("x-asr-smoke.pcm").use { it.readBytes() }
            .copyOfRange(0, 16_000 * 4 * 2)
        val samples = FloatArray(pcm.size / 2) { index ->
            val low = pcm[index * 2].toInt() and 0xff
            val high = pcm[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort() / 32768f
        }
        val source = object : PcmSource {
            var index = 0
            override fun read(): FloatArray? {
                if (index >= samples.size) return null
                val end = minOf(index + 1600, samples.size)
                Thread.sleep(100)
                return samples.copyOfRange(index, end).also { index = end }
            }
            override fun requestStop() = Unit
            override fun close() = Unit
        }
        val decoder = OfflineSpeechDecoder(LocalVoiceArtifact("qwen-17", directory))
        val previews = mutableListOf<String>()
        try {
            val final = decoder.recognize(source) { previews += it }
            assertTrue("Qwen 1.7B 应至少产生一次预览", previews.isNotEmpty())
            assertTrue("最终转写不能为空", final.isNotBlank())
        } finally {
            decoder.close()
        }
    }
}
