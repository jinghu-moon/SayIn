package seeyuer.sayin.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/** Created, called and released only on the recognition worker, never on the UI thread. */
internal class XAsrDecoder(assets: AssetManager) : StreamingDecoder {
    private val recognizer = OnlineRecognizer(
        assetManager = assets,
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL/encoder.onnx",
                    decoder = "$MODEL/decoder.onnx",
                    joiner = "$MODEL/joiner.onnx",
                ),
                tokens = "$MODEL/tokens.txt",
                modelType = "zipformer2",
                numThreads = 2,
                provider = "cpu",
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )
    private var stream = try {
        recognizer.createStream()
    } catch (error: Throwable) {
        recognizer.release()
        throw error
    }

    override fun accept(samples: FloatArray) = stream.acceptWaveform(samples, sampleRate = 16000)
    override fun decodeReady() {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }
    override fun text() = recognizer.getResult(stream).text
    override fun isEndpoint() = recognizer.isEndpoint(stream)
    override fun reset() = recognizer.reset(stream)
    /** Warm inference with a separate silent stream; never touches microphone or user draft. */
    fun warmUp() {
        val warmup = recognizer.createStream()
        try {
            warmup.acceptWaveform(FloatArray(16000), sampleRate = 16000)
            while (recognizer.isReady(warmup)) recognizer.decode(warmup)
        } finally { warmup.release() }
    }
    /** A finished stream cannot accept a new recording. Keep weights, replace only its stream. */
    fun restart() {
        val fresh = recognizer.createStream()
        stream.release()
        stream = fresh
    }
    override fun finishInput() {
        // Supply right context before closing the stream, including a short last word.
        accept(FloatArray(16000 * 8 / 10))
        stream.inputFinished()
    }
    override fun close() {
        try { stream.release() } finally { recognizer.release() }
    }

    companion object { const val MODEL = "models/x-asr-480ms" }
}
