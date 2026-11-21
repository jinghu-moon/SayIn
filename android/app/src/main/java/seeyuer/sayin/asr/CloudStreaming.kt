package seeyuer.sayin.asr

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal data class CloudUsage(val inputTokens: Long?, val outputTokens: Long?)
internal data class CloudRecognitionResult(val text: String, val usage: CloudUsage? = null)

internal sealed interface CloudFrame {
    data class Json(val text: String) : CloudFrame
    data class Audio(val bytes: ByteString) : CloudFrame
}

internal data class CloudEvent(
    val ready: Boolean = false,
    val transcript: String? = null,
    val finished: Boolean = false,
    val usage: CloudUsage? = null,
)

internal interface CloudStreamingTask {
    val request: Request
    val start: CloudFrame
    val needsReadyEvent: Boolean
    val chunkBytes: Int
    fun audio(chunk: ByteArray): CloudFrame
    fun finish(tail: ByteArray): List<CloudFrame>
    fun receive(frame: CloudFrame): CloudEvent
}

internal val cloudWebSocketClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

internal suspend fun recognizeCloud(
    config: CloudConfiguration,
    openSource: () -> PcmSource,
    onListening: () -> Unit,
    onTranscript: (String) -> Unit,
): CloudRecognitionResult {
    val task = when (val credentials = config.credentials) {
        is DoubaoCredentials -> DoubaoRecognition().task(credentials, config.hotwords)
        is QwenCredentials -> QwenRecognition().task(credentials, config.hotwords)
    }
    return streamCloud(task, cloudWebSocketClient, 10_000, openSource, onListening, onTranscript)
}

internal suspend fun streamCloud(
    task: CloudStreamingTask,
    client: OkHttpClient,
    finishTimeoutMs: Long,
    openSource: () -> PcmSource,
    onListening: () -> Unit,
    onTranscript: (String) -> Unit,
): CloudRecognitionResult = withContext(Dispatchers.IO) {
    val opened = CompletableDeferred<Unit>()
    val ready = CompletableDeferred<Unit>()
    val final = CompletableDeferred<CloudRecognitionResult>()
    val ending = AtomicBoolean(false)
    var transcript = ""
    var usage: CloudUsage? = null
    fun fail(error: CloudRecognitionException) {
        opened.completeExceptionally(error)
        ready.completeExceptionally(error)
        final.completeExceptionally(error)
    }
    val socket = client.newWebSocket(task.request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(CloudFrame.Audio(bytes))
        override fun onMessage(webSocket: WebSocket, text: String) = receive(CloudFrame.Json(text))
        private fun receive(frame: CloudFrame) {
            if (final.isCompleted) return
            try {
                val event = task.receive(frame)
                if (event.ready) ready.complete(Unit)
                event.usage?.let { reported ->
                    usage = CloudUsage(reported.inputTokens ?: usage?.inputTokens, reported.outputTokens ?: usage?.outputTokens)
                }
                if (event.finished && !ending.get()) throw CloudRecognitionException(CloudIssue.Protocol, "final-before-end")
                event.transcript?.let { transcript = it; onTranscript(it) }
                if (event.finished) final.complete(CloudRecognitionResult(transcript, usage))
            } catch (error: Exception) {
                fail(error as? CloudRecognitionException ?: CloudRecognitionException(CloudIssue.Protocol, "response"))
            }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(CloudRecognitionException(when (response?.code) {
                401 -> CloudIssue.Authentication
                403 -> CloudIssue.AccessDenied
                429 -> CloudIssue.Quota
                else -> CloudIssue.Network
            }))
            response?.close()
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!final.isCompleted) fail(CloudRecognitionException(CloudIssue.Network))
            webSocket.close(code, null)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!final.isCompleted) fail(CloudRecognitionException(CloudIssue.Network))
        }
    })
    fun send(frame: CloudFrame) {
        val sent = when (frame) {
            is CloudFrame.Json -> socket.send(frame.text)
            is CloudFrame.Audio -> {
                if (socket.queueSize() + frame.bytes.size > 64_000) throw AudioBacklogException()
                socket.send(frame.bytes)
            }
        }
        if (!sent) throw CloudRecognitionException(CloudIssue.Network)
    }
    suspend fun checkTask() {
        coroutineContext.ensureActive()
        if (final.isCompleted) {
            final.await()
            throw CloudRecognitionException(CloudIssue.Protocol, "unexpected-final")
        }
    }
    var source: PcmSource? = null
    try {
        val sessionReady = withTimeoutOrNull(12_000) {
            opened.await()
            checkTask()
            send(task.start)
            if (task.needsReadyEvent) ready.await()
            true
        }
        if (sessionReady != true) throw CloudRecognitionException(CloudIssue.Timeout)
        checkTask()
        val input = openSource().also { source = it }
        checkTask()
        onListening()
        var pending = ByteArray(0)
        while (true) {
            checkTask()
            val samples = input.read() ?: break
            checkTask()
            require(samples.size <= 3200)
            pending += encodePcm16(samples)
            var offset = 0
            while (pending.size - offset >= task.chunkBytes) {
                send(task.audio(pending.copyOfRange(offset, offset + task.chunkBytes)))
                offset += task.chunkBytes
            }
            if (offset != 0) pending = pending.copyOfRange(offset, pending.size)
        }
        checkTask()
        val frames = task.finish(pending)
        frames.dropLast(1).forEach(::send)
        checkTask()
        ending.set(true)
        send(frames.last())
        withTimeoutOrNull(finishTimeoutMs) { final.await() } ?: throw CloudRecognitionException(CloudIssue.Timeout)
    } finally {
        socket.cancel()
        source?.requestStop()
        source?.close()
    }
}

internal fun encodePcm16(samples: FloatArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
    samples.forEachIndexed { index, value ->
        require(value.isFinite())
        val pcm = (value.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767)
        bytes[index * 2] = pcm.toByte()
        bytes[index * 2 + 1] = (pcm shr 8).toByte()
    }
}
