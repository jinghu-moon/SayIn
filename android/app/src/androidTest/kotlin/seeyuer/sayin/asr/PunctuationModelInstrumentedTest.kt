package seeyuer.sayin.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PunctuationModelInstrumentedTest {
    @Test
    fun bundledPunctuationModelAppendsTerminalMarkWithoutChangingText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restorer = PunctuationRestorer(context)
        try {
            val result = restorer.restore("今天天气不错")
            assertTrue(result.startsWith("今天天气不错"))
            assertTrue(result.last() in setOf('。', '！', '？', '.', '!', '?'))
        } finally {
            restorer.close()
        }
    }
}
