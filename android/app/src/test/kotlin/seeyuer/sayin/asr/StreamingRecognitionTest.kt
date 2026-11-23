package seeyuer.sayin.asr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StreamingRecognitionTest {
    @Test fun r2t2FinishesAtExactDecodeBoundaryWithoutReplacingConfirmedText() = runBlocking {
        val end = CountDownLatch(1)
        val updates = mutableListOf<String>()
        val prefixes = mutableListOf<Pair<String, Boolean>>()
        val microphone = object : PcmSource {
            private var delivered = false
            override fun read(): FloatArray? {
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
            decode = { pcm, prefix, final, preview ->
                assertEquals(32_000, pcm.size)
                prefixes += prefix to final
                if (final) " world.".encodeToByteArray()
                else {
                    val bytes = "language English<asr_text>Hello\uFFFD".encodeToByteArray()
                    preview.publish(bytes)
                    assertEquals(listOf("Hello"), updates)
                    bytes
                }
            },
            finishInput = {},
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
            override fun read(): FloatArray? = when (reads++) {
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
        val result = recognizeR2t2(microphone, decode = { pcm, prefix, final, preview ->
            if (final) {
                assertEquals("language Chinese<asr_text>你好", prefix)
                assertEquals(33_600, pcm.size)
                assertEquals(1f, pcm[31_999], 0f)
                assertEquals(2f, pcm[32_000], 0f)
                "。".encodeToByteArray()
            } else {
                assertEquals("", prefix)
                preview.publish("language Chinese<asr_text>你好".encodeToByteArray())
                started.countDown()
                check(finished.await(5, TimeUnit.SECONDS))
                null
            }
        }, finishInput = { finished.countDown() }, onTranscript = { updates += it })
        assertEquals("你好。", result)
        assertEquals(listOf("你好", result), updates)
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
            override fun read(): FloatArray? {
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
            override fun read(): FloatArray? = throw AudioBacklogException()
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
        override fun read(): FloatArray? = if (remaining-- > 0) FloatArray(1600) else null
        override fun requestStop() { stopped = true }
        override fun close() { closed = true }
    }
}
