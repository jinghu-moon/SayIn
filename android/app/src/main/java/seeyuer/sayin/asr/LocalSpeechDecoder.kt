package seeyuer.sayin.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.Closeable
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

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

/** These sherpa model exports decode after the recording finishes. */
internal class OfflineSpeechDecoder(artifact: LocalVoiceArtifact) : LocalSpeechDecoder {
    private val qwen = artifact.id == "qwen-06" || artifact.id == "qwen-17"
    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(numThreads = 2, provider = "cpu").apply {
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

    override suspend fun recognize(source: PcmSource, onTranscript: (String) -> Unit): String {
        val chunks = mutableListOf<FloatArray>()
        var samples = 0
        source.use {
            while (true) {
                coroutineContext.ensureActive()
                val chunk = source.read() ?: break
                samples += chunk.size
                if (samples > RECORDING_LIMIT) throw OfflineRecordingLimitException()
                chunks += chunk
            }
        }
        if (samples == 0) return ""
        val audio = FloatArray(samples)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(audio, offset)
            offset += chunk.size
        }
        val transcript = StreamingTranscript()
        var text = ""
        for (start in audio.indices step DECODE_WINDOW) {
            coroutineContext.ensureActive()
            val end = minOf(start + DECODE_WINDOW, audio.size)
            val segment = decode(audio.copyOfRange(start, end))
            text = transcript.update(segment, endpoint = true)
        }
        coroutineContext.ensureActive()
        return text
    }

    override fun close() = recognizer.release()

    private companion object {
        val QWEN_LANGUAGE_HEADER = Regex("^\\s*language [A-Za-z][A-Za-z -]*<asr_text>")
        const val SAMPLE_RATE = 16000
        const val DECODE_WINDOW = 20 * SAMPLE_RATE
        const val RECORDING_LIMIT = 120 * SAMPLE_RATE
    }
}

internal class OfflineRecordingLimitException : Exception()
