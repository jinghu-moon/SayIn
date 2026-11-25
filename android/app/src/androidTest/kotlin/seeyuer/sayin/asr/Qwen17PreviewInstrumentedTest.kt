package seeyuer.sayin.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Qwen17PreviewInstrumentedTest {
    @Test
    fun qwen17PublishesCompletedSegmentsBeforeCaptureFinishes() = runBlocking { verifyOfflineSegments("qwen-17") }
}
