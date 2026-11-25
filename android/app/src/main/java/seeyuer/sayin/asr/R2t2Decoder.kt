package seeyuer.sayin.asr

import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import seeyuer.sayin.translation.R2t2Native
import seeyuer.sayin.translation.R2t2Preview

internal class R2t2Decoder(
    directory: File,
    prefillThreads: Int = 4,
    gpuDecoder: Boolean = false,
    gpuProjector: Boolean = false,
    private val firstPreviewSamples: Int = FIRST_DECODE,
    private val onTiming: ((R2t2Timing) -> Unit)? = null,
) : LocalSpeechDecoder {
    private val native = R2t2Native(
        File(directory, "Confucius4-R2T2-Q4_K_M.gguf"),
        File(directory, "mmproj-Confucius4-R2T2-Q8_0.gguf"),
        prefillThreads, gpuDecoder, gpuProjector,
    )

    override fun warmUp() {
        checkNotNull(native.decode(FloatArray(5_120), "", true, 0, FINAL_TOKEN_BUDGET, null))
    }

    override suspend fun recognize(source: PcmSource, onTranscript: (String) -> Unit): String = coroutineScope {
        native.reset()
        val abort = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { native.cancel() }
        }
        try {
            val timings = onTiming?.let { LongArray(8) }
            recognizeR2t2(source, decode = { attempt, preview ->
                val started = onTiming?.let { TimeSource.Monotonic.markNow() }
                val bytes = native.decode(
                    attempt.samples, attempt.prefix, attempt.final, attempt.revision, attempt.tokenBudget, preview, timings,
                )
                if (started != null && timings != null) {
                    onTiming.invoke(R2t2Timing(
                        attempt.final, attempt.samples.size, attempt.queuedSamples, attempt.tokenBudget, bytes == null,
                        started.elapsedNow().inWholeMicroseconds, timings[0], timings[1], timings[2], timings[3], timings[4],
                        timings[5].toInt(), timings[6].toInt(), timings[7].toInt(),
                    ))
                }
                bytes
            }, prioritizeFinal = native::prioritizeFinal, firstPreviewSamples = firstPreviewSamples, onTranscript = onTranscript)
        } finally {
            abort.cancel()
        }
    }

    override fun close() = native.close()
}

internal data class R2t2Timing(
    val final: Boolean,
    val samples: Int,
    val queuedSamples: Int,
    val tokenBudget: Int,
    val interrupted: Boolean,
    val elapsedUs: Long,
    val featuresUs: Long,
    val encodeUs: Long,
    val audioPrefillUs: Long,
    val textPrefillUs: Long,
    val generationUs: Long,
    val sampledTokens: Int,
    val reusedAudioChunks: Int,
    val reusedPositions: Int,
)

internal data class R2t2Attempt(
    val samples: FloatArray,
    val prefix: String,
    val final: Boolean,
    val revision: Int,
    val tokenBudget: Int,
    val queuedSamples: Int,
    val consumedEnd: Int,
)

private const val SAMPLE_RATE = 16_000
private const val FIRST_DECODE = 19_200 // 1.2 seconds of audio
private const val DECODE_STEP = 2 * SAMPLE_RATE
private const val DECODE_WINDOW = 16 * SAMPLE_RATE
private const val MIN_SEGMENT = 3 * SAMPLE_RATE
private const val RECORDING_LIMIT = 120 * SAMPLE_RATE
private const val FINAL_TOKEN_BUDGET = 256
private const val SAMPLES_PER_TOKEN = 1_280
private const val MIN_PREVIEW_TOKENS = 20
private const val MAX_PREVIEW_TOKENS = 64
private const val ASR_TEXT = "<asr_text>"
private val languageHeader = Regex("language [A-Za-z][A-Za-z -]*")

internal suspend fun recognizeR2t2(
    source: PcmSource,
    decode: (R2t2Attempt, R2t2Preview) -> ByteArray?,
    prioritizeFinal: (Int) -> Unit,
    firstPreviewSamples: Int = FIRST_DECODE,
    onTranscript: (String) -> Unit,
): String = coroutineScope {
    val updates = Channel<Unit>(Channel.CONFLATED)
    val lock = Any()
    // Preserve PCM even when inference takes longer than a capture interval.
    val audio = FloatArray(RECORDING_LIMIT)
    data class Boundary(val sample: Int, val retainedEnd: Int?)
    val boundaries = ArrayDeque<Boundary>()
    var captured = 0
    var retainedEnd: Int? = null
    var stopped = false
    var lastBoundary = 0
    var windowStart = 0
    var revision = 0
    val capture = launch(Dispatchers.IO) {
        try {
            source.use {
                while (true) {
                    ensureActive()
                    val chunk = source.read() ?: break
                    synchronized(lock) {
                        if (chunk.size > audio.size - captured) throw OfflineRecordingLimitException()
                        val windowEnd = windowStart + DECODE_WINDOW
                        val hadFullWindow = captured >= windowEnd
                        chunk.copyInto(audio, captured)
                        captured += chunk.size
                        retainedEnd = source.retainedSampleEnd
                        val newBoundary = source.segmentBoundary && captured - maxOf(lastBoundary, windowStart) >= MIN_SEGMENT
                        if (newBoundary) {
                            boundaries.addLast(Boundary(captured, retainedEnd))
                            lastBoundary = captured
                        }
                        if (newBoundary || (!hadFullWindow && captured >= windowEnd)) prioritizeFinal(++revision)
                    }
                    updates.trySend(Unit)
                }
            }
            synchronized(lock) {
                stopped = true
                prioritizeFinal(++revision)
            }
        } finally {
            updates.close()
        }
    }
    val transcript = StreamingTranscript()
    var visible = ""
    var lastDecoded = 0
    var previewAudioEnd = 0
    var header = ""
    var confirmed = ""
    val recognitionContext = coroutineContext
    try {
        while (true) {
            val signal = updates.receiveCatching()
            while (true) {
                coroutineContext.ensureActive()
                val attempt = synchronized(lock) {
                    while (boundaries.firstOrNull()?.let { it.sample <= windowStart } == true) boundaries.removeFirst()
                    if (windowStart == captured) return@synchronized null
                    val boundary = boundaries.firstOrNull()
                    val consumedEnd = minOf(windowStart + DECODE_WINDOW, captured, boundary?.sample ?: captured)
                    val atBoundary = consumedEnd == boundary?.sample
                    val final = stopped || atBoundary || consumedEnd - windowStart == DECODE_WINDOW
                    // A preview started in the last interval is likely to be discarded at the hard boundary.
                    if (!final && windowStart + DECODE_WINDOW - consumedEnd <= DECODE_STEP) return@synchronized null
                    val kept = if (atBoundary) boundary.retainedEnd else retainedEnd
                    val end = (kept ?: consumedEnd).coerceIn(windowStart, consumedEnd)
                    if (end == windowStart && !final) return@synchronized null
                    val newSamples = end - lastDecoded
                    // Capture already advances while this serial decoder is busy. Count that audio
                    // toward the next preview so completed inference adds no extra cooldown.
                    val interval = if (confirmed.isEmpty()) firstPreviewSamples else DECODE_STEP
                    if (!final && newSamples < interval) return@synchronized null
                    val budget = if (final) FINAL_TOKEN_BUDGET else {
                        (newSamples / SAMPLES_PER_TOKEN).coerceIn(MIN_PREVIEW_TOKENS, MAX_PREVIEW_TOKENS)
                    }
                    val prefix = if (end >= previewAudioEnd) header + confirmed else ""
                    R2t2Attempt(audio.copyOfRange(windowStart, end), prefix, final, revision, budget, captured - consumedEnd, consumedEnd)
                } ?: break

                val previousHeader = if (attempt.prefix.isEmpty()) "" else header
                val previousText = if (attempt.prefix.isEmpty()) "" else confirmed
                fun publish(bytes: ByteArray, endpoint: Boolean) {
                    recognitionContext.ensureActive()
                    if (!attempt.final && synchronized(lock) { attempt.revision != revision }) return
                    previewAudioEnd = windowStart + attempt.samples.size
                    val text = bytes.decodeToString().trimEnd('\uFFFD')
                    if (previousHeader.isEmpty()) {
                        header = ""
                        confirmed = ""
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
                if (attempt.samples.isEmpty()) {
                    // A completed VAD segment followed by quiet does not need another native decode.
                    synchronized(lock) { windowStart = attempt.consumedEnd }
                    lastDecoded = windowStart
                    previewAudioEnd = windowStart
                    header = ""
                    confirmed = ""
                    val stable = transcript.update("", false)
                    if (stable != visible) { visible = stable; onTranscript(stable) }
                    continue
                }
                val bytes = decode(attempt) { publish(it, false) }
                coroutineContext.ensureActive()
                // A required segment became ready. Re-read capture state before decoding again.
                if (bytes == null) {
                    check(!attempt.final)
                    check(synchronized(lock) { revision != attempt.revision })
                    continue
                }
                publish(bytes, attempt.final)
                lastDecoded = windowStart + attempt.samples.size
                if (attempt.final) {
                    synchronized(lock) { windowStart = attempt.consumedEnd }
                    lastDecoded = windowStart
                    previewAudioEnd = windowStart
                    header = ""
                    confirmed = ""
                }
            }
            if (signal.isClosed) break
        }
        coroutineContext.ensureActive()
        visible
    } finally {
        source.requestStop()
        capture.cancel()
    }
}
