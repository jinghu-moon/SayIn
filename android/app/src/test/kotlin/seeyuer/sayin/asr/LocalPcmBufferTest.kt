package seeyuer.sayin.asr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalPcmBufferTest {
    @Test fun drainsColdStartFramesInOrderAfterCaptureAlreadyStopped() = runBlocking {
        var stopped = false
        val source = LocalPcmBuffer({ stopped = true }, capacity = 2)
        source.offer(floatArrayOf(1f), segmentBoundary = false, speech = true, retainedSampleEnd = 2)
        source.offer(floatArrayOf(2f), segmentBoundary = true, speech = false, retainedSampleEnd = 2)
        source.requestStop()
        source.finish()
        assertTrue(stopped)
        assertArrayEquals(floatArrayOf(1f), source.read(), 0f)
        assertEquals(true, source.speechInBlock)
        assertArrayEquals(floatArrayOf(2f), source.read(), 0f)
        assertTrue(source.segmentBoundary)
        assertEquals(2, source.retainedSampleEnd)
        assertNull(source.read())
    }

    @Test fun fullQueuePreservesFirstFrameAndFailsTheRecording() = runBlocking {
        val source = LocalPcmBuffer({}, capacity = 1)
        source.offer(floatArrayOf(1f), false, null, null)
        val failure = assertThrows(AudioBacklogException::class.java) {
            source.offer(floatArrayOf(2f), false, null, null)
        }
        source.finish(failure)
        assertArrayEquals(floatArrayOf(1f), source.read(), 0f)
        assertSame(failure, runCatching { source.read() }.exceptionOrNull())
    }
}
