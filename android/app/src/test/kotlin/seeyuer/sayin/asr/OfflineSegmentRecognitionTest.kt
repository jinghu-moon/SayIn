package seeyuer.sayin.asr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSegmentRecognitionTest {
    @Test
    fun shortPausePublishesQwenPreviewBeforeStopWithoutCommittingShortContext() = runBlocking {
        val previewed = CountDownLatch(1)
        val source = object : PcmSource {
            private var reads = 0
            override val segmentBoundary get() = reads == 1
            override val speechInBlock get() = reads == 2
            override val retainedSampleEnd get() = if (reads == 1) 8_000 else 16_000
            override suspend fun read(): FloatArray? = when (++reads) {
                1 -> FloatArray(8_000) { 1f }
                2 -> {
                    assertTrue(previewed.await(5, TimeUnit.SECONDS))
                    FloatArray(8_000) { 2f }
                }
                else -> null
            }
            override fun requestStop() { previewed.countDown() }
            override fun close() = Unit
        }
        val lengths = mutableListOf<Int>()
        val visible = mutableListOf<String>()
        val result = recognizeOfflineSegments(source, OfflinePreviewPolicies.forModel("qwen-06"), { samples ->
            lengths += samples.size
            if (samples.size == 8_000) "先说" else "先说完了。"
        }, { text ->
            visible += text
            previewed.countDown()
        }, minSegmentSamples = 8 * 16_000)
        assertEquals(listOf(8_000, 16_000), lengths)
        assertEquals(listOf("先说", "先说完了。"), visible)
        assertEquals("先说完了。", result)
    }

    @Test
    fun continuousSpeechIsDecodedInBoundedSegmentsBeforeEofWithoutVadBoundary() = runBlocking {
        val decoded = CountDownLatch(1)
        val source = object : PcmSource {
            private var reads = 0
            override val speechInBlock get() = true
            override suspend fun read(): FloatArray? = when (++reads) {
                1 -> FloatArray(160_000) { 1f }
                2 -> {
                    assertTrue(decoded.await(5, TimeUnit.SECONDS))
                    FloatArray(16_000) { 2f }
                }
                else -> null
            }
            override fun requestStop() { decoded.countDown() }
            override fun close() = Unit
        }
        val visible = mutableListOf<String>()
        val result = recognizeOfflineSegments(source, null, {
            if (it.first() == 1f) "first" else "tail"
        }, {
            visible += it
            decoded.countDown()
        })
        assertEquals(listOf("first", "first tail"), visible)
        assertEquals("first tail", result)
    }

    @Test
    fun trimsUnconsumedTailAtBoundaryAndManualStop() = runBlocking {
        for (boundary in listOf(false, true)) {
            var read = 0
            val source = object : PcmSource {
                override val segmentBoundary get() = boundary && read == 2
                override val retainedSampleEnd get() = 3
                override suspend fun read(): FloatArray? = when (++read) {
                    1 -> floatArrayOf(1f, 2f, 3f)
                    2 -> floatArrayOf(4f, 5f)
                    else -> null
                }
                override fun requestStop() = Unit
                override fun close() = Unit
            }
            val decoded = mutableListOf<List<Float>>()
            assertEquals("好", recognizeOfflineSegments(source, null, {
                decoded += it.toList()
                "好"
            }, {}))
            assertEquals(listOf(listOf(1f, 2f, 3f)), decoded)
        }
    }

    @Test
    fun onlyDecodesTailAfterVadBoundaryWhenItContainsSpeechOrVadIsUnavailable() = runBlocking {
        for (tailSpeech in listOf(false, true, null)) {
            val first = floatArrayOf(0.6f)
            val noise = FloatArray(1_600) { 0.002f }
            val tail = if (tailSpeech == true) floatArrayOf(0.04f) else noise
            val source = object : PcmSource {
                private var reads = 0
                override val segmentBoundary get() = reads == 1
                override val speechInBlock get() = if (reads == 1) false else tailSpeech

                override suspend fun read(): FloatArray? = when (++reads) {
                    1 -> first
                    2 -> tail
                    else -> null
                }

                override fun requestStop() = Unit
                override fun close() = Unit
            }
            val decoded = mutableListOf<List<Float>>()
            val final = recognizeOfflineSegments(source, null, { audio ->
                decoded += audio.toList()
                if (audio.contentEquals(first)) "测试一下模型的输入效果。" else "嗯。"
            }, {})
            val expectedSegments = if (tailSpeech == false) listOf(first.toList()) else listOf(first.toList(), tail.toList())
            assertEquals(expectedSegments, decoded)
            assertEquals(if (tailSpeech == false) "测试一下模型的输入效果。" else "测试一下模型的输入效果。嗯。", final)
        }
    }

    @Test
    fun commitsBeforeEofAndOnlyDecodesRemainingAudioAtStop() = runBlocking {
        val firstText = CountDownLatch(1)
        val source = object : PcmSource {
            private var reads = 0
            override val segmentBoundary get() = reads == 1

            override suspend fun read(): FloatArray? = when (++reads) {
                1 -> floatArrayOf(1f, 2f)
                2 -> {
                    assertTrue("Expected a stable segment before EOF", firstText.await(5, TimeUnit.SECONDS))
                    floatArrayOf(3f)
                }
                else -> null
            }

            override fun requestStop() { firstText.countDown() }
            override fun close() = Unit
        }
        val decoded = mutableListOf<List<Float>>()
        val visible = mutableListOf<String>()
        val final = recognizeOfflineSegments(source, null, { audio ->
            decoded += audio.toList()
            if (audio.first() == 1f) "first" else "tail"
        }, {
            visible += it
            firstText.countDown()
        })

        assertEquals(listOf(listOf(1f, 2f), listOf(3f)), decoded)
        assertEquals(listOf("first", "first tail"), visible)
        assertEquals("first tail", final)
    }

    @Test
    fun discardsInFlightPreviewWhenFinalArrives() = runBlocking {
        val previewStarted = CountDownLatch(1)
        val captureClosed = CountDownLatch(1)
        val source = object : PcmSource {
            private var reads = 0
            override suspend fun read(): FloatArray? = when (++reads) {
                1 -> floatArrayOf(1f)
                2 -> {
                    assertTrue(previewStarted.await(5, TimeUnit.SECONDS))
                    floatArrayOf(2f)
                }
                else -> null
            }

            override fun requestStop() { previewStarted.countDown() }
            override fun close() { captureClosed.countDown() }
        }
        val visible = mutableListOf<String>()
        val final = recognizeOfflineSegments(source, OfflinePreviewPolicy(1, 1, 1.0, false, 1), { audio ->
            if (audio.size == 1) {
                previewStarted.countDown()
                assertTrue(captureClosed.await(5, TimeUnit.SECONDS))
                "stale"
            } else "final"
        }, visible::add)

        assertEquals(listOf("final"), visible)
        assertEquals("final", final)
    }
}
