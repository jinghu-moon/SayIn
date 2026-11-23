package seeyuer.sayin.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable

/** Result of one audio block; only [ended] may finish the capture session. */
internal data class VadProgress(
    val heardSpeech: Boolean = false,
    val ending: Boolean = false,
    val ended: Boolean = false,
    val noSpeech: Boolean = false,
)

/**
 * Streaming Silero VAD wrapper. All timing lives here; sherpa's internal
 * min-speech/min-silence settings are deliberately not used for product endpoints.
 */
internal class VadAutoStop(
    context: Context,
    model: String = DEFAULT_MODEL,
    private val threshold: Float = 0.45f,
) : Closeable {
    private val vad = Vad(
        assetManager = context.applicationContext.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model,
                threshold = threshold,
                // compute() is called per window; these internal durations are not
                // the product endpoint timer.
                minSilenceDuration = 0.01f,
                minSpeechDuration = 0.01f,
                windowSize = WINDOW_SIZE,
                maxSpeechDuration = 120f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )
    private val frame = FloatArray(WINDOW_SIZE)
    private val timer = VadEndpointTimer()
    private var frameCount = 0
    private var done = false

    fun accept(samples: FloatArray): VadProgress {
        if (done) return VadProgress(heardSpeech = true, ended = true)
        var result = VadProgress()
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(WINDOW_SIZE - frameCount, samples.size - offset)
            samples.copyInto(frame, frameCount, offset, offset + count)
            frameCount += count
            offset += count
            if (frameCount == WINDOW_SIZE) {
                result = acceptFrame()
                frameCount = 0
                if (done) break
            }
        }
        return result
    }

    private fun acceptFrame(): VadProgress {
        val state = timer.accept(vad.compute(frame), threshold, WINDOW_SIZE)
        if (state.ended || state.noSpeech) done = true
        return VadProgress(state.heardSpeech, state.ending, state.ended, state.noSpeech)
    }

    override fun close() = vad.release()

    private companion object {
        const val DEFAULT_MODEL = "vad/silero_vad.int8.onnx"
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512
    }
}
