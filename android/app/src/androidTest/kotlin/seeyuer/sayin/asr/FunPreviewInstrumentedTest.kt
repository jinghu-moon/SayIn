package seeyuer.sayin.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FunPreviewInstrumentedTest {
    @Test
    fun funAsrPublishesCompletedSegmentsBeforeCaptureFinishes() = runBlocking { verifyOfflineSegments("fun-int8") }
}
