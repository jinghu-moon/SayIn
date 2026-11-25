package seeyuer.sayin.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable

/**
 * Streaming Silero VAD wrapper. All timing lives here; sherpa's internal
 * min-speech/min-silence settings are deliberately not used for product endpoints.
 */
internal class VadAutoStop(
    context: Context,
    model: String = DEFAULT_MODEL,
    autoFinish: Boolean = true,
    private val defaultThreshold: Float = 0.45f,
    localSilenceMillis: Int? = null,
) : Closeable {
    private val vad = Vad(
        assetManager = context.applicationContext.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model,
                threshold = defaultThreshold,
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
    private val timer = VadEndpointTimer(autoFinish = autoFinish, localSilenceMillis = localSilenceMillis)
    val retainedSampleEnd: Int?
        get() = timer.lastPossibleVoiceSample.takeIf { it > 0 }?.let {
            // Keep 200 ms of real context and one VAD window of boundary uncertainty.
            (it + SAMPLE_RATE / 5 + WINDOW_SIZE).toInt()
        }
    private val calibrator = VadNoiseCalibrator(defaultThreshold = defaultThreshold)
    private var threshold = defaultThreshold
    private var frameCount = 0
    fun accept(samples: FloatArray): VadEndpointTimer.State {
        var result = VadEndpointTimer.State()
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(WINDOW_SIZE - frameCount, samples.size - offset)
            samples.copyInto(frame, frameCount, offset, offset + count)
            frameCount += count
            offset += count
            if (frameCount == WINDOW_SIZE) {
                val probability = vad.compute(frame)
                calibrator.observe(probability, WINDOW_SIZE)?.let { threshold = it }
                val next = timer.accept(probability, threshold, WINDOW_SIZE)
                result = next.copy(
                    segmentBoundary = result.segmentBoundary || next.segmentBoundary,
                    speech = result.speech || next.speech,
                )
                frameCount = 0
                if (result.ended || result.noSpeech) break
            }
        }
        return result
    }

    override fun close() = vad.release()

    private companion object {
        const val DEFAULT_MODEL = "vad/silero_vad.int8.onnx"
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512
    }
}
