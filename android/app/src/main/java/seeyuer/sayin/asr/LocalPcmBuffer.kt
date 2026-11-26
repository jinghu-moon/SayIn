package seeyuer.sayin.asr

import kotlinx.coroutines.channels.Channel

/** One ordered queue between local microphone capture and the selected decoder. */
internal class LocalPcmBuffer(private val stop: () -> Unit, capacity: Int = 120) : PcmSource {
    private data class Block(
        val samples: FloatArray,
        val segmentBoundary: Boolean,
        val speech: Boolean?,
        val retainedSampleEnd: Int?,
        val noSpeech: Boolean,
    )

    // At 100 ms per read this covers the 12-second model readiness deadline.
    private val blocks = Channel<Block>(capacity)
    private var current: Block? = null
    @Volatile private var noSpeechDetected = false
    override val segmentBoundary get() = current?.segmentBoundary == true
    override val speechInBlock get() = current?.speech
    override val retainedSampleEnd get() = current?.retainedSampleEnd
    override val noSpeech get() = noSpeechDetected || current?.noSpeech == true

    fun offer(
        samples: FloatArray,
        segmentBoundary: Boolean,
        speech: Boolean?,
        retainedSampleEnd: Int?,
        noSpeech: Boolean = false,
    ) {
        if (noSpeech) noSpeechDetected = true
        if (!blocks.trySend(Block(samples, segmentBoundary, speech, retainedSampleEnd, noSpeech)).isSuccess) {
            throw AudioBacklogException()
        }
    }

    fun finish(error: Throwable? = null) { blocks.close(error) }

    override suspend fun read(): FloatArray? {
        val result = blocks.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        current = result.getOrNull()
        return current?.samples
    }

    override fun requestStop() = stop()
    override fun close() { requestStop() }
}
