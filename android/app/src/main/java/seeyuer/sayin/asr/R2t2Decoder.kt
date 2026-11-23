package seeyuer.sayin.asr

import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import seeyuer.sayin.translation.R2t2Native
import seeyuer.sayin.translation.R2t2Preview

internal class R2t2Decoder(directory: File) : LocalSpeechDecoder {
    private val native = R2t2Native(
        File(directory, "Confucius4-R2T2-Q4_K_M.gguf"),
        File(directory, "mmproj-Confucius4-R2T2-f16.gguf"),
    )

    override fun warmUp() {
        checkNotNull(native.decode(FloatArray(5_120), "", final = true, preview = null))
    }

    override suspend fun recognize(source: PcmSource, onTranscript: (String) -> Unit): String = coroutineScope {
        native.reset()
        val abort = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { native.cancel() }
        }
        try {
            recognizeR2t2(source, native::decode, native::finishInput, onTranscript)
        } finally {
            abort.cancel()
        }
    }

    override fun close() = native.close()
}

private const val SAMPLE_RATE = 16_000
private const val FIRST_DECODE = 19_200 // 1.2 seconds of audio
private const val DECODE_STEP = 2 * SAMPLE_RATE
private const val DECODE_WINDOW = 16 * SAMPLE_RATE
private const val RECORDING_LIMIT = 120 * SAMPLE_RATE
private const val ASR_TEXT = "<asr_text>"
private val languageHeader = Regex("language [A-Za-z][A-Za-z -]*")

internal suspend fun recognizeR2t2(
    source: PcmSource,
    decode: (FloatArray, String, Boolean, R2t2Preview) -> ByteArray?,
    finishInput: () -> Unit,
    onTranscript: (String) -> Unit,
): String = coroutineScope {
    val updates = Channel<Unit>(Channel.CONFLATED)
    val lock = Any()
    // Preserve PCM even when inference takes longer than a capture interval.
    val audio = FloatArray(RECORDING_LIMIT)
    var captured = 0
    var stopped = false
    val capture = launch(Dispatchers.IO) {
        try {
            source.use {
                while (true) {
                    ensureActive()
                    val chunk = source.read() ?: break
                    synchronized(lock) {
                        if (chunk.size > audio.size - captured) throw OfflineRecordingLimitException()
                        chunk.copyInto(audio, captured)
                        captured += chunk.size
                    }
                    updates.trySend(Unit)
                }
            }
            finishInput()
            synchronized(lock) { stopped = true }
        } finally {
            updates.close()
        }
    }
    val transcript = StreamingTranscript()
    var visible = ""
    var windowStart = 0
    var lastDecoded = 0
    var header = ""
    var confirmed = ""
    val recognitionContext = coroutineContext
    try {
        while (true) {
            val signal = updates.receiveCatching()
            val (available, eof) = synchronized(lock) { captured to stopped }
            while (windowStart < available) {
                coroutineContext.ensureActive()
                val end = minOf(windowStart + DECODE_WINDOW, available)
                val final = eof || end - windowStart == DECODE_WINDOW
                val interval = if (confirmed.isEmpty()) FIRST_DECODE else DECODE_STEP
                if (!final && end - lastDecoded < interval) break

                val samples = synchronized(lock) { audio.copyOfRange(windowStart, end) }
                val previousHeader = header
                val previousText = confirmed
                fun publish(bytes: ByteArray, endpoint: Boolean) {
                    recognitionContext.ensureActive()
                    val text = bytes.decodeToString().trimEnd('\uFFFD')
                    if (previousHeader.isEmpty()) {
                        val tag = text.indexOf(ASR_TEXT)
                        if (tag >= 0 && languageHeader.matches(text.substring(0, tag))) {
                            header = text.substring(0, tag + ASR_TEXT.length)
                            confirmed = text.substring(tag + ASR_TEXT.length).trimStart()
                        }
                    } else {
                        confirmed = previousText + text
                    }
                    val next = transcript.update(confirmed, endpoint)
                    if (next != visible) {
                        visible = next
                        onTranscript(visible)
                    }
                }
                val bytes = decode(samples, previousHeader + previousText, final) { publish(it, false) }
                coroutineContext.ensureActive()
                // Capture has ended during a partial decode. The next pass uses all remaining PCM.
                if (bytes == null) {
                    check(!final)
                    break
                }
                publish(bytes, final)
                lastDecoded = end
                if (final) {
                    windowStart = end
                    header = ""
                    confirmed = ""
                }
            }
            if (eof || signal.isClosed) break
        }
        coroutineContext.ensureActive()
        visible
    } finally {
        source.requestStop()
        capture.cancel()
    }
}
