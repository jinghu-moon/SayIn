package seeyuer.sayin.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.Closeable
import java.io.File

internal data class LocalVoiceArtifact(val id: String, val directory: File)

internal fun LocalVoiceArtifact.openDecoder(): LocalSpeechDecoder = when (id) {
    "x-asr" -> XAsrDecoder(null, directory.absolutePath)
    "r2t2" -> R2t2Decoder(directory)
    else -> OfflineSpeechDecoder(this)
}

internal interface LocalSpeechDecoder : Closeable {
    fun warmUp() {}
    suspend fun recognize(source: PcmSource, onTranscript: (String) -> Unit): String
}

/** Sherpa models that decode complete audio segments. */
internal class OfflineSpeechDecoder(artifact: LocalVoiceArtifact) : LocalSpeechDecoder {
    private val qwen = artifact.id == "qwen-06" || artifact.id == "qwen-17"
    // 4 threads measured faster than 2 on K40 for the two heavy offline models.
    // Qwen 0.6B keeps 2 to avoid unnecessary contention.
    private val decodeThreads = if (artifact.id == "qwen-17" || artifact.id == "fun-int8") 4 else 2
    private val preview = OfflinePreviewPolicies.forModel(artifact.id)
    // Short isolated clauses caused Qwen 0.6B to translate words in mixed-language speech.
    private val minSegmentSamples = if (artifact.id == "qwen-06") 8 * SAMPLE_RATE else 0
    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(numThreads = decodeThreads, provider = "cpu").apply {
                fun path(name: String) = File(artifact.directory, name).absolutePath
                when (artifact.id) {
                    "qwen-06", "qwen-17" -> {
                        qwen3Asr = OfflineQwen3AsrModelConfig(
                            convFrontend = path("conv_frontend.onnx"),
                            encoder = path("encoder.int8.onnx"),
                            decoder = path("decoder.int8.onnx"),
                            tokenizer = path("tokenizer"),
                        )
                    }
                    "fun-int8" -> {
                        funasrNano = OfflineFunAsrNanoModelConfig(
                            encoderAdaptor = path("encoder_adaptor.int8.onnx"),
                            llm = path("llm.int8.onnx"),
                            embedding = path("embedding.int8.onnx"),
                            tokenizer = path("Qwen3-0.6B"),
                            itn = true,
                        )
                    }
                    else -> error("Unsupported local recognition model")
                }
            },
        ),
    )

    override fun warmUp() {
        if (preview?.warmupOnPrepare != true) return
        // Warm the offline graph on silence so the first live preview is not cold.
        runCatching { decode(FloatArray(SAMPLE_RATE)) }
    }

    internal fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text
            return if (qwen) text.replaceFirst(QWEN_LANGUAGE_HEADER, "").trim() else text
        } finally {
            stream.release()
        }
    }

    override suspend fun recognize(source: PcmSource, onTranscript: (String) -> Unit): String =
        recognizeOfflineSegments(source, preview, ::decode, onTranscript, minSegmentSamples)

    override fun close() = recognizer.release()

    private companion object {
        val QWEN_LANGUAGE_HEADER = Regex("^\\s*language [A-Za-z][A-Za-z -]*<asr_text>")
        const val SAMPLE_RATE = 16000
    }
}

internal class OfflineRecordingLimitException : Exception()
