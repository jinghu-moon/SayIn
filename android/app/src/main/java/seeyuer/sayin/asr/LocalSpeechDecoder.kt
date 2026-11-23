package seeyuer.sayin.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

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
    private val previewEnabled = artifact.id == "qwen-06"
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

    override suspend fun recognize(source: PcmSource, onTranscript: (String) -> Unit): String = coroutineScope {
        val chunks = mutableListOf<FloatArray>()
        val samples = AtomicInteger(0)
        val lock = Any()
        val capture = async(Dispatchers.IO) {
            source.use {
                while (true) {
                    coroutineContext.ensureActive()
                    val chunk = source.read() ?: break
                    if (samples.addAndGet(chunk.size) > RECORDING_LIMIT) throw OfflineRecordingLimitException()
                    synchronized(lock) { chunks += chunk }
                }
            }
        }
        val transcript = StreamingTranscript()
        if (previewEnabled) {
            while (capture.isActive) {
                delay(PREVIEW_INTERVAL)
                val total = samples.get()
                if (total < PREVIEW_MIN) continue
                val snapshot = synchronized(lock) {
                    val start = (total - PREVIEW_MAX).coerceAtLeast(0)
                    val audio = FloatArray(total - start)
                    var offset = 0
                    var cursor = 0
                    for (chunk in chunks) {
                        val end = cursor + chunk.size
                        if (end > start) {
                            val from = (start - cursor).coerceAtLeast(0)
                            chunk.copyInto(audio, offset, from, chunk.size)
                            offset += chunk.size - from
                        }
                        cursor = end
                    }
                    audio
                }
                coroutineContext.ensureActive()
                val preview = decode(snapshot)
                if (preview.isNotBlank()) onTranscript(transcript.update(preview, endpoint = false))
            }
        }
        capture.await()
        val total = samples.get()
        if (total == 0) return@coroutineScope ""
        val audio = synchronized(lock) {
            FloatArray(total).also { target ->
                var offset = 0
                for (chunk in chunks) {
                    chunk.copyInto(target, offset)
                    offset += chunk.size
                }
            }
        }
        var text = ""
        for (start in audio.indices step DECODE_WINDOW) {
            coroutineContext.ensureActive()
            val end = minOf(start + DECODE_WINDOW, audio.size)
            val segment = decode(audio.copyOfRange(start, end))
            text = transcript.update(segment, endpoint = true)
        }
        coroutineContext.ensureActive()
        onTranscript(text)
        text
    }

    override fun close() = recognizer.release()

    private companion object {
        val QWEN_LANGUAGE_HEADER = Regex("^\\s*language [A-Za-z][A-Za-z -]*<asr_text>")
        const val SAMPLE_RATE = 16000
        const val DECODE_WINDOW = 20 * SAMPLE_RATE
        const val RECORDING_LIMIT = 120 * SAMPLE_RATE
        const val PREVIEW_MIN = 2 * SAMPLE_RATE
        const val PREVIEW_MAX = 20 * SAMPLE_RATE
        const val PREVIEW_INTERVAL = 2_000L
    }
}

internal class OfflineRecordingLimitException : Exception()
