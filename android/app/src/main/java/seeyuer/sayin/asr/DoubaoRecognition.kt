package seeyuer.sayin.asr

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal class DoubaoRecognition(
    private val client: OkHttpClient = sharedClient,
    private val endpoint: String = ENDPOINT,
    private val finishTimeoutMs: Long = 10_000,
) {
    companion object {
        const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        const val RESOURCE = "volc.seedasr.sauc.duration"
        private val sharedClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }

    internal fun request(config: CloudConfiguration): Request {
        val builder = Request.Builder().url(endpoint)
            .header("X-Api-Resource-Id", RESOURCE)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
        when (config.auth) {
            DoubaoAuth.AppKey -> builder.header("X-Api-Key", config.appKey)
            DoubaoAuth.AppIdToken -> builder.header("X-Api-App-Key", config.appId).header("X-Api-Access-Key", config.accessToken)
        }
        return builder.build()
    }

    internal fun parameters(config: CloudConfiguration): String {
        val params = JSONObject().put("model_name", "bigmodel").put("enable_itn", true)
            .put("enable_punc", true).put("result_type", "full")
        if (config.hotwords.isNotEmpty()) {
            val words = JSONArray().apply { config.hotwords.forEach { put(JSONObject().put("word", it)) } }
            params.put("corpus", JSONObject().put("context", JSONObject().put("hotwords", words).toString()))
        }
        return JSONObject().put("user", JSONObject().put("uid", "sayin"))
            .put("audio", JSONObject().put("format", "pcm").put("rate", 16000).put("bits", 16).put("channel", 1))
            .put("request", params).toString()
    }

    /** Returns only after a final server packet following our last audio packet. No reconnect/replay. */
    suspend fun recognize(
        config: CloudConfiguration,
        openMicrophone: () -> PcmSource,
        onListening: () -> Unit,
        onTranscript: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val opened = CompletableDeferred<Unit>()
        val final = CompletableDeferred<String>()
        val ending = AtomicBoolean(false)
        var transcript = "" // Accessed only by OkHttp's serialized listener callbacks.
        fun fail(issue: CloudIssue, detail: String? = null) {
            val error = CloudRecognitionException(issue, detail)
            opened.completeExceptionally(error)
            final.completeExceptionally(error)
        }
        val socket = client.newWebSocket(request(config), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (final.isCompleted) return
                try {
                    val packet = DoubaoProtocol.parse(bytes.toByteArray())
                    packet.errorCode?.let { fail(serviceIssue(it)); return }
                    val json = JSONObject(packet.payload)
                    val code = if (json.has("code")) json.getInt("code") else 0
                    if (code != 0 && code != 20_000_000) { fail(serviceIssue(code)); return }
                    val result = json.optJSONObject("result")
                    if (result != null && result.has("text")) {
                        val text = result.get("text")
                        if (text !is String || text.length > 65_536) throw CloudRecognitionException(CloudIssue.Protocol)
                        transcript = text
                        onTranscript(text) // Full snapshot replaces, never appends to the previous snapshot.
                    }
                    if (packet.final) {
                        if (!ending.get()) fail(CloudIssue.Protocol, "final-before-end") else final.complete(transcript)
                    }
                } catch (error: Exception) { fail(CloudIssue.Protocol, (error as? CloudRecognitionException)?.detail ?: "response-json") }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { fail(CloudIssue.Protocol) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(when (response?.code) {
                    401 -> CloudIssue.Authentication
                    403 -> CloudIssue.AccessDenied
                    429 -> CloudIssue.Quota
                    else -> CloudIssue.Network
                })
                response?.close()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!final.isCompleted) fail(CloudIssue.Network)
                webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!final.isCompleted) fail(CloudIssue.Network)
            }
        })
        fun send(bytes: ByteArray) {
            // 2 seconds PCM queued is already too slow for live dictation. Never silently drop audio.
            if (socket.queueSize() + bytes.size > 64_000) throw AudioBacklogException()
            if (!socket.send(bytes.toByteString())) throw CloudRecognitionException(CloudIssue.Network)
        }
        var microphone: PcmSource? = null
        try {
            if (withTimeoutOrNull(12_000) { opened.await(); true } != true) throw CloudRecognitionException(CloudIssue.Timeout)
            coroutineContext.ensureActive()
            send(DoubaoProtocol.request(parameters(config)))
            val source = openMicrophone().also { microphone = it }
            coroutineContext.ensureActive()
            onListening()
            var pending = ByteArray(0)
            while (true) {
                coroutineContext.ensureActive()
                if (final.isCompleted) { final.await(); throw CloudRecognitionException(CloudIssue.Protocol) }
                val samples = source.read() ?: break
                coroutineContext.ensureActive()
                require(samples.size <= 3200)
                pending += DoubaoProtocol.pcm16(samples)
                if (pending.size >= 6400) {
                    send(DoubaoProtocol.audio(pending.copyOfRange(0, 6400), last = false))
                    pending = pending.copyOfRange(6400, pending.size)
                }
            }
            coroutineContext.ensureActive()
            if (final.isCompleted) { final.await(); throw CloudRecognitionException(CloudIssue.Protocol) }
            ending.set(true)
            send(DoubaoProtocol.audio(pending, last = true))
            withTimeoutOrNull(finishTimeoutMs) { final.await() } ?: throw CloudRecognitionException(CloudIssue.Timeout)
        } finally {
            microphone?.requestStop()
            microphone?.close()
            // Cancel also releases a stalled connection on page/lifecycle cancellation.
            socket.cancel()
        }
    }
}

private fun serviceIssue(code: Int) = when (code) {
    45_000_030, 45_000_031 -> CloudIssue.Authentication
    45_000_081 -> CloudIssue.Timeout
    else -> CloudIssue.Service
}
