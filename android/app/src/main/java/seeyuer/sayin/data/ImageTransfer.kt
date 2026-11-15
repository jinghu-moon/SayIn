package seeyuer.sayin.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one on-demand WS until the control receipt confirms durable acceptance.
 * Closing it earlier discards the unaccepted remote resource by design. */
internal class ImageTransfer(
    private val open: (WebSocketListener) -> WebSocket,
    private val hostId: String,
    private val deviceId: String,
    private val sessionId: String,
    private val binding: String,
    private val reception: ReceptionScope,
    private val allowed: () -> Boolean,
) : Closeable {
    private sealed interface Incoming {
        data class Open(val socket: WebSocket) : Incoming
        data class Text(val value: String) : Incoming
    }
    private val incoming = Channel<Incoming>(8)
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null

    override fun close() {
        closed.set(true)
        socket?.cancel()
        incoming.close()
    }
    private fun checkScope() { check(!closed.get() && allowed()) { "IMAGE_SCOPE" } }
    private fun json(kind: String, id: String) = JSONObject().put("v", 2).put("t", kind).put("session_id", sessionId).put("id", id)
    private fun send(value: JSONObject) {
        checkScope()
        val text = value.toString()
        check(text.toByteArray().size <= 8192)
        check(checkNotNull(socket).send(text))
    }
    private suspend fun message(kind: String, id: String? = null, timeout: Long = 12000): JSONObject = withTimeout(timeout) {
        checkScope()
        val text = (incoming.receive() as Incoming.Text).value
        val value = strictJson(text)
        require(value.getInt("v") == 2 && value.getString("t") == kind)
        if (id != null) require(value.getString("id") == id)
        checkScope()
        value
    }

    suspend fun upload(metadata: ImageMetadata, bytes: ByteArray, progress: (Int) -> Unit): ImageReference = withTimeout(300000) {
        metadata.verify(bytes)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get() || !incoming.trySend(Incoming.Open(webSocket)).isSuccess) webSocket.cancel()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray().size > 8192 || !incoming.trySend(Incoming.Text(text)).isSuccess) {
                    webSocket.cancel(); incoming.close(IllegalStateException("IMAGE_PROTOCOL"))
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.cancel(); incoming.close(IllegalStateException("IMAGE_PROTOCOL")) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { incoming.close(t) }
        }
        try {
            checkScope()
            socket = open(listener)
            if (closed.get()) { socket?.cancel(); error("IMAGE_SCOPE") }
            val connected = withTimeout(8000) { incoming.receive() as Incoming.Open }
            require(connected.socket === socket)
            // HostClient invokes Open only after full fixed-certificate + SAN
            // verification. The binding is never sent during TLS negotiation.
            send(JSONObject().put("v", 2).put("t", "image_hello").put("session_id", sessionId).put("device_id", deviceId).put("binding", binding))
            val welcome = message("image_welcome")
            require(welcome.getString("host_id") == hostId && welcome.getString("device_id") == deviceId && welcome.getString("session_id") == sessionId)
            val id = UUID.randomUUID().toString()
            send(json("image_begin", id).put("target", reception.target).put("state_rev", reception.revision).put("metadata", metadata.json()))
            message("image_begun", id)
            val window = ImageSendWindow(bytes.size)
            while (!window.complete) {
                while (window.canSend) {
                    checkScope()
                    val range = window.next()
                    val part = bytes.copyOfRange(range.first, range.last + 1)
                    val frame = try { imageChunk(id, range.first.toLong(), part) } finally { part.fill(0) }
                    try {
                        val current = checkNotNull(socket)
                        check(current.queueSize() + frame.size <= 4L * (IMAGE_CHUNK_BYTES + IMAGE_CHUNK_HEADER_BYTES))
                        check(current.send(frame.toByteString()))
                    } finally { frame.fill(0) }
                }
                val received = message("image_progress", id).getLong("received")
                window.acknowledge(received)
                progress(received.toInt())
            }
            send(json("image_finish", id))
            val image = ImageReference.parse(message("image_ready", id, 60000).getJSONObject("image"))
            require(image.metadata == metadata)
            checkScope()
            image
        } catch (error: Throwable) { close(); throw error }
    }
}

/** At most four unacknowledged 48KiB chunks, irrespective of OkHttp's queue. */
internal class ImageSendWindow(private val size: Int) {
    private var sent = 0
    private var acknowledged = 0
    private val ends = ArrayDeque<Int>()
    init { require(size in 1..MAX_IMAGE_BYTES) }
    val canSend: Boolean get() = ends.size < 4 && sent < size
    val complete: Boolean get() = acknowledged == size
    fun next(): IntRange {
        check(canSend)
        val start = sent
        sent = minOf(size, sent + IMAGE_CHUNK_BYTES)
        ends.addLast(sent)
        return start until sent
    }
    fun acknowledge(received: Long) {
        require(ends.isNotEmpty() && received == ends.first().toLong())
        acknowledged = ends.removeFirst()
    }
}
