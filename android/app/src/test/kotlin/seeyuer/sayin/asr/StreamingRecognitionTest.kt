package seeyuer.sayin.asr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StreamingRecognitionTest {
    @Test fun xAsrReleasesQuietBeforeResumptionAndDropsUnsentQuietAtEof() = runBlocking {
        var blocksRead = 0
        val source = object : PcmSource {
            override val retainedSampleEnd get() = if (blocksRead < 3) 2 else 5
            override suspend fun read(): FloatArray? = when (++blocksRead) {
                1 -> floatArrayOf(1f, 2f)
                2 -> floatArrayOf(3f, 4f)
                3 -> floatArrayOf(5f)
                4 -> floatArrayOf(6f)
                else -> null
            }
            override fun requestStop() = Unit
            override fun close() = Unit
        }
        val heard = mutableListOf<Float>()
        val decoder = object : StreamingDecoder by FakeDecoder() {
            override fun accept(samples: FloatArray) { heard += samples.toList() }
        }
        val visible = mutableListOf<String>()
        recognizeStreaming(decoder, source, onTranscript = visible::add)
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), heard)
        assertEquals(listOf("尾", "尾字。"), visible)
    }

    @Test fun r2t2DoesNotDecodeAQuietTailAfterAVadSegment() = runBlocking {
        var reads = 0
        val source = object : PcmSource {
            override val segmentBoundary get() = reads == 1
            override val retainedSampleEnd get() = 46_000
            override suspend fun read(): FloatArray? = when (++reads) {
                1 -> FloatArray(48_000) { 1f }
                2 -> FloatArray(3_000) { 0.002f }
                else -> null
            }
            override fun requestStop() = Unit
            override fun close() = Unit
        }
        val decoded = mutableListOf<Int>()
        val result = recognizeR2t2(source, decode = { attempt, _ ->
            assertTrue(attempt.final)
            decoded += attempt.samples.size
            "language Chinese<asr_text>好".encodeToByteArray()
        }, prioritizeFinal = {}, onTranscript = {})
        assertEquals("好", result)
        assertEquals(listOf(46_000), decoded)
    }

    @Test fun r2t2FinishesAtExactDecodeBoundaryWithoutReplacingConfirmedText() = runBlocking {
        val end = CountDownLatch(1)
        val updates = mutableListOf<String>()
        val prefixes = mutableListOf<Pair<String, Boolean>>()
        val microphone = object : PcmSource {
            private var delivered = false
            override suspend fun read(): FloatArray? {
                if (delivered) {
                    check(end.await(5, TimeUnit.SECONDS))
                    return null
                }
                delivered = true
                return FloatArray(32_000)
            }
            override fun requestStop() { end.countDown() }
            override fun close() = Unit
        }
        val result = recognizeR2t2(
            microphone,
            decode = { attempt, preview ->
                assertEquals(32_000, attempt.samples.size)
                prefixes += attempt.prefix to attempt.final
                if (attempt.final) " world.".encodeToByteArray()
                else {
                    val bytes = "language English<asr_text>Hello\uFFFD".encodeToByteArray()
                    preview.publish(bytes)
                    assertEquals(listOf("Hello"), updates)
                    bytes
                }
            },
            prioritizeFinal = {},
        ) {
            updates += it
            end.countDown()
        }
        assertEquals(listOf("" to false, "language English<asr_text>Hello" to true), prefixes)
        assertEquals(listOf("Hello", "Hello world."), updates)
        assertEquals("Hello world.", result)
    }

    @Test fun r2t2NormalStopSupersedesPartialDecodeAndKeepsAllPcm() = runBlocking {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val microphone = object : PcmSource {
            private var reads = 0
            override suspend fun read(): FloatArray? = when (reads++) {
                0 -> FloatArray(32_000) { 1f }
                1 -> {
                    check(started.await(5, TimeUnit.SECONDS))
                    FloatArray(1_600) { 2f }
                }
                else -> null
            }
            override fun requestStop() = Unit
            override fun close() = Unit
        }
        val updates = mutableListOf<String>()
        val result = recognizeR2t2(microphone, decode = { attempt, preview ->
            if (attempt.final) {
                assertEquals("language Chinese<asr_text>你好", attempt.prefix)
                assertEquals(33_600, attempt.samples.size)
                assertEquals(1f, attempt.samples[31_999], 0f)
                assertEquals(2f, attempt.samples[32_000], 0f)
                "。".encodeToByteArray()
            } else {
                assertEquals("", attempt.prefix)
                preview.publish("language Chinese<asr_text>你好".encodeToByteArray())
                started.countDown()
                check(finished.await(5, TimeUnit.SECONDS))
                null
            }
        }, prioritizeFinal = { finished.countDown() }, onTranscript = { updates += it })
        assertEquals("你好。", result)
        assertEquals(listOf("你好", result), updates)
    }

    @Test fun r2t2CommitsAVadBoundaryBeforeEofAndDecodesOnlyTheTailAfterIt() = runBlocking {
        val committed = CountDownLatch(1)
        val microphone = object : PcmSource {
            private var reads = 0
            override val segmentBoundary get() = reads == 1

            override suspend fun read(): FloatArray? = when (++reads) {
                1 -> FloatArray(48_000) { 1f }
                2 -> {
                    assertTrue(committed.await(5, TimeUnit.SECONDS))
                    FloatArray(3_000) { 2f }
                }
                else -> null
            }

            override fun requestStop() { committed.countDown() }
            override fun close() = Unit
        }
        val sizes = mutableListOf<Int>()
        val final = recognizeR2t2(microphone, decode = { attempt, _ ->
            assertTrue(attempt.final)
            assertEquals("", attempt.prefix)
            sizes += attempt.samples.size
            val text = if (attempt.samples[0] == 1f) "hello" else "world"
            "language English<asr_text>$text".encodeToByteArray()
        }, prioritizeFinal = {}) { text ->
            if (text == "hello") committed.countDown()
        }
        assertEquals(listOf(48_000, 3_000), sizes)
        assertEquals("hello world", final)
    }

    @Test fun r2t2RequiredSegmentInterruptsPreviewAndReceivesFullBudget() = runBlocking {
        for (vad in listOf(true, false)) {
            val previewStarted = CountDownLatch(1)
            val finalReady = CountDownLatch(1)
            val committed = CountDownLatch(1)
            val segmentSize = if (vad) 48_000 else 256_000
            val microphone = object : PcmSource {
                private var reads = 0
                override val segmentBoundary get() = vad && reads == 2
                override suspend fun read(): FloatArray? = when (++reads) {
                    1 -> FloatArray(19_200) { 1f }
                    2 -> {
                        check(previewStarted.await(5, TimeUnit.SECONDS))
                        FloatArray(segmentSize - 19_200) { 2f }
                    }
                    3 -> {
                        check(committed.await(5, TimeUnit.SECONDS))
                        FloatArray(1_600) { 3f }
                    }
                    else -> null
                }
                override fun requestStop() { committed.countDown() }
                override fun close() = Unit
            }
            val attempts = mutableListOf<Triple<Boolean, Int, Int>>()
            val final = recognizeR2t2(microphone, decode = { attempt, preview ->
                attempts += Triple(attempt.final, attempt.samples.size, attempt.tokenBudget)
                when {
                    !attempt.final -> {
                        assertEquals(0, attempt.revision)
                        preview.publish("language English<asr_text>Hello".encodeToByteArray())
                        previewStarted.countDown()
                        check(finalReady.await(5, TimeUnit.SECONDS))
                        null
                    }
                    attempt.samples.size == segmentSize -> {
                        assertEquals(1, attempt.revision)
                        assertEquals("language English<asr_text>Hello", attempt.prefix)
                        assertEquals(1f, attempt.samples.first(), 0f)
                        assertEquals(2f, attempt.samples.last(), 0f)
                        ".".encodeToByteArray()
                    }
                    else -> {
                        assertEquals(2, attempt.revision)
                        assertEquals("", attempt.prefix)
                        assertTrue(attempt.samples.all { it == 3f })
                        "language English<asr_text>World.".encodeToByteArray()
                    }
                }
            }, prioritizeFinal = { finalReady.countDown() }) {
                if (it == "Hello.") committed.countDown()
            }
            assertEquals(listOf(Triple(false, 19_200, 20), Triple(true, segmentSize, 256), Triple(true, 1_600, 256)), attempts)
            assertEquals("Hello. World.", final)
        }
    }

    @Test fun r2t2SkipsPreviewJustBeforeTheHardBoundaryAndFinalizesAllSamples() = runBlocking {
        val audio = Channel<FloatArray>(Channel.UNLIMITED)
        val previewStarted = CountDownLatch(1)
        val lateAudioRead = CountDownLatch(1)
        val source = object : PcmSource {
            private var reads = 0

            override suspend fun read(): FloatArray? {
                if (++reads == 3) lateAudioRead.countDown()
                return audio.receiveCatching().getOrNull()
            }

            override fun requestStop() { audio.cancel() }
            override fun close() = Unit
        }
        val attempts = mutableListOf<Pair<Boolean, Int>>()
        // Feed 15.4 seconds while the first preview is in flight, as in the K40 replay.
        val capture = launch(Dispatchers.IO) {
            audio.send(FloatArray(19_200) { 1f })
            check(previewStarted.await(5, TimeUnit.SECONDS))
            audio.send(FloatArray(227_200) { 2f })
        }
        val final = recognizeR2t2(source, decode = { attempt, _ ->
            attempts += attempt.final to attempt.samples.size
            if (attempt.final) {
                assertEquals(256_000, attempt.samples.size)
                assertEquals(1f, attempt.samples[19_199], 0f)
                assertEquals(2f, attempt.samples[246_399], 0f)
                assertEquals(3f, attempt.samples.last(), 0f)
                " world.".encodeToByteArray()
            } else {
                assertEquals(19_200, attempt.samples.size)
                previewStarted.countDown()
                check(lateAudioRead.await(5, TimeUnit.SECONDS))
                "language English<asr_text>Hello".encodeToByteArray()
            }
        }, prioritizeFinal = {}, onTranscript = {
            // The same dispatcher resumes this sender after the decoder has considered the queued PCM.
            if (it == "Hello") launch {
                audio.send(FloatArray(9_600) { 3f })
                audio.close()
            }
        })
        capture.join()
        assertEquals(listOf(false to 19_200, true to 256_000), attempts)
        assertEquals("Hello world.", final)
    }

    @Test fun r2t2SkipsDecodeWhenVadConfirmsNoSpeech() = runBlocking {
        var reads = 0
        val source = object : PcmSource {
            override var noSpeech = false
            override suspend fun read(): FloatArray? = when (++reads) {
                1 -> FloatArray(16_000) { 0.001f }
                2 -> {
                    noSpeech = true
                    FloatArray(16_000) { 0.001f }
                }
                else -> null
            }
            override fun requestStop() = Unit
            override fun close() = Unit
        }
        var decodeCalls = 0
        val result = recognizeR2t2(source, decode = { _, _ ->
            decodeCalls += 1
            "language Chinese<asr_text>误识别".encodeToByteArray()
        }, prioritizeFinal = {}, onTranscript = {})
        assertEquals("", result)
        assertEquals(0, decodeCalls)
    }

    @Test fun r2t2PacesPreviewsByPreviousInferenceDurationAndBoundaryGuard() = runBlocking {
        val audio = Channel<FloatArray>(Channel.UNLIMITED)
        val previewStarted = CountDownLatch(1)
        val lateRead = CountDownLatch(1)
        var reads = 0
        val source = object : PcmSource {
            override suspend fun read(): FloatArray? {
                if (++reads == 3) lateRead.countDown()
                return audio.receiveCatching().getOrNull()
            }
            override fun requestStop() { audio.cancel() }
            override fun close() = Unit
        }
        var clockMs = 0L
        val attempts = mutableListOf<Pair<Boolean, Int>>()
        val capture = launch(Dispatchers.IO) {
            audio.send(FloatArray(32_000) { 1f }) // 2.0s triggers initial preview
            check(previewStarted.await(5, TimeUnit.SECONDS))
            audio.send(FloatArray(64_000) { 2f }) // +4.0s -> 6.0s total (within 4.5s guard of 8s window? 8-6=2s <= 4.5s)
        }
        val final = recognizeR2t2(
            source,
            decode = { attempt, _ ->
                attempts += attempt.final to attempt.samples.size
                if (!attempt.final) {
                    previewStarted.countDown()
                    check(lateRead.await(5, TimeUnit.SECONDS))
                    clockMs += 4_500L // Simulate 4.5s preview duration (> 2s step)
                    "language English<asr_text>Hello".encodeToByteArray()
                } else {
                    " world.".encodeToByteArray()
                }
            },
            prioritizeFinal = {},
            decodeWindowSamples = 8 * 16_000,
            nowNanos = { clockMs * 1_000_000L },
            onTranscript = {
                if (it == "Hello") launch {
                    audio.send(FloatArray(32_000) { 3f }) // reaches 8.0s hard window
                    audio.close()
                }
            },
        )
        capture.join()
        assertEquals(listOf(false to 32_000, true to 128_000), attempts)
        assertEquals("Hello world.", final)
    }

    @Test fun partialRevisionsReplaceOnlyCurrentSegment() {
        val transcript = StreamingTranscript()
        assertEquals("你", transcript.update("你", false))
        assertEquals("你好。", transcript.update("你好。", true))
        assertEquals("你好。世界", transcript.update("世界", false))
        assertEquals("你好。世界！", transcript.update("世界！", true))
        assertEquals("你好。世界！", transcript.update("", false))
    }

    @Test fun englishWordsAcrossEndpointsKeepBoundary() {
        val transcript = StreamingTranscript()
        transcript.update("hello", true)
        assertEquals("hello world", transcript.update("world", false))
        assertEquals("hello中文", transcript.update(" 中文", false))
        val punctuated = StreamingTranscript()
        punctuated.update("Hello.", true)
        assertEquals("Hello. World!", punctuated.update("World!", false))
    }

    @Test fun insertionPreservesExistingDraftAroundSelection() {
        val insertion = RecognitionInsertion("前面旧词后面", 2, 4)
        assertEquals("前面新后面", insertion.text("新"))
        assertEquals("前面新的识别后面", insertion.text("新的识别"))
        assertEquals(6, insertion.cursor("新的识别"))
        assertEquals("原稿补充", RecognitionInsertion("原稿", 2, 2).text("补充"))
    }

    @Test fun normalStopDrainsTailBeforeFinalTranscript() = runBlocking {
        val decoder = FakeDecoder()
        val microphone = FakeMicrophone(3)
        val seen = mutableListOf<String>()
        val final = recognizeStreaming(decoder, microphone, onTranscript = { seen += it })
        assertEquals("尾字。", final)
        assertEquals("尾字。", seen.last())
        assertTrue(decoder.finished)
        assertTrue(microphone.closed)
        assertTrue(microphone.stopped)
    }

    @Test fun cancellationStopsMicrophoneWithoutFinalizingInput() = runBlocking {
        val reading = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val decoder = FakeDecoder()
        val microphone = object : PcmSource {
            override suspend fun read(): FloatArray? {
                reading.countDown()
                check(stopped.await(5, TimeUnit.SECONDS))
                return null
            }
            override fun requestStop() { stopped.countDown() }
            override fun close() { stopped.countDown() }
        }
        val job = launch(Dispatchers.Default) { recognizeStreaming(decoder, microphone) {} }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertFalse(decoder.finished)
        assertEquals(0, stopped.count)
    }

    @Test fun captureFailureDoesNotReturnSuccessfulFinalText() = runBlocking {
        val decoder = FakeDecoder()
        val microphone = object : PcmSource {
            override suspend fun read(): FloatArray? = throw AudioBacklogException()
            override fun requestStop() = Unit
            override fun close() = Unit
        }
        try {
            recognizeStreaming(decoder, microphone) {}
            fail("Audio loss must not be reported as success")
        } catch (_: AudioBacklogException) {
            assertFalse(decoder.finished)
        }
    }

    private class FakeDecoder : StreamingDecoder {
        var finished = false
        override fun accept(samples: FloatArray) = Unit
        override fun decodeReady() = Unit
        override fun text() = if (finished) "尾字。" else "尾"
        override fun isEndpoint() = false
        override fun reset() = Unit
        override fun finishInput() { finished = true }
        override fun close() = Unit
    }

    private class FakeMicrophone(private var remaining: Int) : PcmSource {
        var closed = false
        var stopped = false
        override suspend fun read(): FloatArray? = if (remaining-- > 0) FloatArray(1600) else null
        override fun requestStop() { stopped = true }
        override fun close() { closed = true }
    }
}
