package seeyuer.sayin.asr

import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal interface StreamingDecoder : Closeable {
    fun accept(samples: FloatArray)
    fun decodeReady()
    fun text(): String
    fun isEndpoint(): Boolean
    fun reset()
    fun finishInput()
}

internal interface PcmSource : Closeable {
    /** Null means a normal stop was requested; errors must throw. */
    suspend fun read(): FloatArray?
    /** Whether the most recent PCM block closed a natural speech segment. */
    val segmentBoundary: Boolean get() = false
    /** Voice activity on the most recent block. Null means the source has no working VAD. */
    val speechInBlock: Boolean? get() = null
    /** Conservative absolute end of the latest voiced audio plus tail context. */
    val retainedSampleEnd: Int? get() = null
    /** True once VAD confirms the entire session held no speech and should produce no transcript. */
    val noSpeech: Boolean get() = false
    fun requestStop()
}

internal class AudioBacklogException : Exception()

/** Endpoint results are committed once; partial hypotheses replace only the current segment. */
internal class StreamingTranscript {
    private var committed = ""

    fun update(hypothesis: String, endpoint: Boolean): String {
        val text = join(committed, hypothesis.trim())
        if (endpoint) committed = text
        return text
    }

    private fun join(left: String, right: String): String {
        val previous = left.lastOrNull()
        val space = (previous?.isAsciiWord() == true || previous != null && previous in ".,!?;:)]") &&
            right.firstOrNull()?.isAsciiWord() == true
        return left + (if (space) " " else "") + right
    }

    private fun Char.isAsciiWord() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}

/** Freezes the insertion range so changing partial results never duplicate or erase the old draft. */
internal data class RecognitionInsertion(val original: String, val start: Int, val end: Int) {
    init { require(start in 0..original.length && end in start..original.length) }
    fun text(transcript: String) = original.take(start) + transcript + original.drop(end)
    fun cursor(transcript: String) = start + transcript.length
}

/** Bounded PCM queue: fail visibly instead of silently dropping audio when inference falls behind. */
internal suspend fun recognizeStreaming(
    decoder: StreamingDecoder,
    microphone: PcmSource,
    captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
    onTranscript: (String) -> Unit,
): String = coroutineScope {
    val queue = Channel<FloatArray>(1)
    val capture = launch(captureDispatcher) {
        val quiet = ArrayDeque<FloatArray>()
        var quietSamples = 0
        var captured = 0
        try {
            while (true) {
                coroutineContext.ensureActive()
                val samples = microphone.read() ?: break
                val retained = microphone.retainedSampleEnd
                val keep = retained?.let { (it - captured).coerceIn(0, samples.size) } ?: samples.size
                captured += samples.size
                if (keep > 0) {
                    // A continuation or loss of VAD evidence releases the recent pause verbatim.
                    for (block in quiet) queue.send(block)
                    quiet.clear()
                    quietSamples = 0
                    queue.send(if (keep == samples.size) samples else samples.copyOfRange(0, keep))
                }
                if (keep < samples.size) {
                    val tail = if (keep == 0) samples else samples.copyOfRange(keep, samples.size)
                    quiet.addLast(tail)
                    quietSamples += tail.size
                    // Preserve the streaming endpoint clock during long pauses without decoding known quiet noise.
                    while (quietSamples > 9_600) {
                        val block = quiet.removeFirst()
                        quietSamples -= block.size
                        queue.send(FloatArray(block.size))
                    }
                }
            }
        } catch (error: Throwable) {
            queue.close(error)
            throw error
        } finally {
            microphone.close()
            queue.close()
        }
    }
    val transcript = StreamingTranscript()
    var visible = ""
    fun publish(text: String) {
        if (text != visible) {
            visible = text
            onTranscript(text)
        }
    }
    try {
        for (samples in queue) {
            coroutineContext.ensureActive()
            decoder.accept(samples)
            decoder.decodeReady()
            val endpoint = decoder.isEndpoint()
            publish(transcript.update(decoder.text(), endpoint))
            if (endpoint) decoder.reset()
        }
        coroutineContext.ensureActive()
        decoder.finishInput()
        decoder.decodeReady()
        transcript.update(decoder.text(), endpoint = false).also(::publish)
    } finally {
        microphone.requestStop()
        capture.cancel()
        // coroutineScope waits for capture's finally before the caller can release the decoder.
    }
}
