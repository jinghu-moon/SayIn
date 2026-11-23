package seeyuer.sayin.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QwenPreviewInstrumentedTest {
    @Test
    fun qwenPublishesTextBeforeCaptureFinishes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "local-models/qwen-06")
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val pcm = testAssets.open("x-asr-smoke.pcm").use { it.readBytes() }
        val samples = FloatArray(pcm.size / 2) { index ->
            val low = pcm[index * 2].toInt() and 0xff
            val high = pcm[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort() / 32768f
        }
        val source = object : PcmSource {
            var index = 0
            var closed = false
            override fun read(): FloatArray? {
                if (index >= samples.size) return null
                val end = minOf(index + 1600, samples.size)
                // Emulate real-time capture so a preview can be observed before EOF.
                Thread.sleep(100)
                return samples.copyOfRange(index, end).also { index = end }
            }
            override fun requestStop() = Unit
            override fun close() { closed = true }
        }
        val decoder = OfflineSpeechDecoder(LocalVoiceArtifact("qwen-06", directory))
        val previews = mutableListOf<String>()
        var previewBeforeEnd = false
        try {
            val final = decoder.recognize(source) { text ->
                previews += text
                if (source.index < samples.size) previewBeforeEnd = true
            }
            assertTrue("Qwen 录音中应至少产生一次预览", previews.isNotEmpty())
            assertTrue("预览应发生在采集结束前", previewBeforeEnd)
            assertTrue("最终转写不能为空", final.isNotBlank())
            assertTrue(source.closed)
        } finally {
            decoder.close()
        }
    }
}
