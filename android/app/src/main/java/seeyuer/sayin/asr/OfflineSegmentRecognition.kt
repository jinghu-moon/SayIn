package seeyuer.sayin.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val SAMPLE_RATE = 16_000
private const val SEGMENT_LIMIT = 10 * SAMPLE_RATE
private const val QUEUE_LIMIT = 6

internal suspend fun recognizeOfflineSegments(
    source: PcmSource,
    previewPolicy: OfflinePreviewPolicy?,
    decode: (FloatArray) -> String,
    onTranscript: (String) -> Unit,
    minSegmentSamples: Int = 0,
): String = coroutineScope {
    val updates = Channel<Unit>(Channel.CONFLATED)
    val lock = Any()
    val ready = ArrayDeque<FloatArray>()
    val pending = FloatArray(SEGMENT_LIMIT)
    var size = 0
    var captured = 0
    var retainedEnd: Int? = null
    var eof = false
    var pendingCanContainSpeech = true
    var pausedPreviewSize = 0

    // Both required segment boundaries and their PCM are retained under the same lock.
    fun retainedSize() = retainedEnd?.let { (it - (captured - size)).coerceIn(0, size) } ?: size

    fun enqueueSegment() {
        if (size == 0) return
        val length = retainedSize()
        if (pendingCanContainSpeech && length > 0) {
            if (ready.size == QUEUE_LIMIT) throw AudioBacklogException()
            ready.addLast(pending.copyOf(length))
        }
        size = 0
        pausedPreviewSize = 0
    }

    val capture = launch(Dispatchers.IO) {
        try {
            source.use {
                while (true) {
                    ensureActive()
                    val chunk = source.read() ?: break
                    synchronized(lock) {
                        val speechInBlock = source.speechInBlock
                        retainedEnd = source.retainedSampleEnd
                        if (speechInBlock != false) pendingCanContainSpeech = true
                        if (speechInBlock == true) pausedPreviewSize = 0
                        var offset = 0
                        while (offset < chunk.size) {
                            val count = minOf(chunk.size - offset, pending.size - size)
                            chunk.copyInto(pending, size, offset, offset + count)
                            size += count
                            captured += count
                            offset += count
                            if (size == pending.size) enqueueSegment()
                        }
                        if (source.segmentBoundary) {
                            if (retainedSize() >= minSegmentSamples) {
                                enqueueSegment()
                                // After a natural boundary, quiet audio alone must not start another decode.
                                pendingCanContainSpeech = speechInBlock != false
                            } else if (previewPolicy != null && pendingCanContainSpeech) {
                                // Qwen 0.6B keeps short phrases together for context, but can display one at a pause.
                                pausedPreviewSize = retainedSize()
                            }
                        }
                    }
                    updates.trySend(Unit)
                }
                synchronized(lock) {
                    // Exact digital silence carries no remaining speech.
                    if ((0 until size).any { pending[it] != 0f }) enqueueSegment()
                    eof = true
                }
            }
        } finally {
            updates.close()
        }
    }

    val transcript = StreamingTranscript()
    var visible = ""
    var preview = previewPolicy
    var misses = 0
    var lastPreviewSize = 0
    fun publish(text: String, endpoint: Boolean) {
        val next = transcript.update(text, endpoint)
        if (next != visible) {
            visible = next
            onTranscript(next)
        }
    }

    try {
        while (true) {
            ensureActive()
            // Let required work preempt any preview that has not started.
            val segment = synchronized(lock) { ready.removeFirstOrNull() }
            if (segment != null) {
                lastPreviewSize = 0
                val text = decode(segment)
                ensureActive()
                publish(text, endpoint = true)
                continue
            }
            val policy = preview
            val snapshot = synchronized(lock) {
                val length = retainedSize()
                if (eof || ready.isNotEmpty() || !pendingCanContainSpeech) return@synchronized null
                when {
                    pausedPreviewSize > lastPreviewSize -> pending.copyOf(pausedPreviewSize)
                    policy != null && length >= policy.minSamples &&
                        length - lastPreviewSize >= policy.intervalSamples -> pending.copyOf(length)
                    else -> null
                }
            }
            if (snapshot != null) {
                lastPreviewSize = snapshot.size
                val started = System.nanoTime()
                val text = decode(snapshot)
                ensureActive()
                if (policy != null) {
                    val budgetNanos = snapshot.size.toDouble() / SAMPLE_RATE * 1_000_000_000 * policy.rtfBudget
                    misses = if (System.nanoTime() - started > budgetNanos) misses + 1 else 0
                    if (misses >= policy.maxBudgetMisses) preview = null
                }
                if (text.isNotBlank() && synchronized(lock) { !eof && ready.isEmpty() }) {
                    publish(text, endpoint = false)
                }
                continue
            }
            // A closed channel wakes the worker after capture has queued the final tail.
            if (synchronized(lock) { eof && ready.isEmpty() }) break
            if (updates.receiveCatching().isClosed && synchronized(lock) { !eof }) break
        }
        visible
    } finally {
        source.requestStop()
        capture.cancel()
    }
}
