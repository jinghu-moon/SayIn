package seeyuer.sayin.data

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RootClip(val id: Long, val timestamp: Long, val preview: String)
internal data class RootClips(val revision: String, val captureReady: Boolean, val entries: List<RootClip>)
internal class RootClipboardFailure(val code: String) : IOException(code)

internal interface RootClipboardAccess {
    suspend fun list(): RootClips
    suspend fun get(snapshot: RootClips, id: Long): String
    suspend fun clear(snapshot: RootClips)
}

/** No Root grant, shell, background service, disk cache, or clipboard reads in the App. */
internal class RootClipboardClient internal constructor(private val endpoint: String = ENDPOINT) : RootClipboardAccess {
    init { require(endpoint == ENDPOINT || endpoint.startsWith("SAYIN_TEST_") && endpoint.length <= 80) }

    override suspend fun list(): RootClips = RootClipboardProtocol.list(exchange("LIST"))
    override suspend fun get(snapshot: RootClips, id: Long): String {
        require(snapshot.entries.any { it.id == id })
        val reply = exchange("GET ${snapshot.revision} $id")
        if (reply.getString("revision") != snapshot.revision) throw RootClipboardFailure("invalid_response")
        val text = reply.getString("text")
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > 16 * 1024 || '\u0000' in text) throw RootClipboardFailure("invalid_response")
        return text
    }
    override suspend fun clear(snapshot: RootClips) {
        val reply = exchange("CLEAR ${snapshot.revision}")
        if (!reply.getBoolean("cleared")) throw RootClipboardFailure("invalid_response")
    }

    private suspend fun exchange(request: String): JSONObject = withTimeout(4_000L) { exchangeFrame(request) }

    private suspend fun exchangeFrame(request: String): JSONObject = suspendCancellableCoroutine { continuation ->
        val socket = LocalSocket()
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        CoroutineScope(continuation.context + Dispatchers.IO).launch {
            try {
                socket.use {
                    if (!continuation.isActive) return@launch
                    socket.connect(LocalSocketAddress(endpoint, LocalSocketAddress.Namespace.ABSTRACT))
                    socket.soTimeout = 4_000
                    if (!continuation.isActive) return@launch
                    if (socket.peerCredentials.uid != 0) throw RootClipboardFailure("untrusted_server")
                    val bytes = request.toByteArray(Charsets.US_ASCII)
                    socket.outputStream.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
                    socket.outputStream.write(bytes)
                    val input = DataInputStream(socket.inputStream)
                    val length = Integer.reverseBytes(input.readInt())
                    if (length !in 1..128 * 1024) throw RootClipboardFailure("invalid_response")
                    val response = ByteArray(length).also { input.readFully(it) }
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response)).toString()
                    val json = RootClipboardProtocol.response(text)
                    if (continuation.isActive) continuation.resume(json)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }

    companion object { const val ENDPOINT = "seeyuer.sayin.clipboard.v1" }
}

/** Strict bounded metadata parsing is shared by the socket client and JVM tests. */
internal object RootClipboardProtocol {
    fun response(text: String): JSONObject {
        val json = JSONObject(text)
        if (json.has("error")) throw RootClipboardFailure(json.getString("error"))
        if (json.getInt("protocol") != 1) throw RootClipboardFailure("invalid_response")
        return json
    }
    fun list(json: JSONObject): RootClips {
        val revision = json.getString("revision")
        if (!revision.matches(Regex("[1-9][0-9]{0,19}:[1-9][0-9]{0,19}"))) throw RootClipboardFailure("invalid_response")
        val array = json.getJSONArray("entries")
        if (array.length() > 50) throw RootClipboardFailure("invalid_response")
        val entries = (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            val id = entry.getLong("id")
            val timestamp = entry.getLong("timestamp")
            val preview = entry.getString("preview")
            if (id <= 0 || timestamp <= 0 || preview.isEmpty() || preview.toByteArray(Charsets.UTF_8).size > 160 || '\u0000' in preview) throw RootClipboardFailure("invalid_response")
            RootClip(id, timestamp, preview)
        }
        if (entries.map { it.id }.distinct().size != entries.size || entries.zipWithNext().any { (a,b) -> a.id <= b.id }) throw RootClipboardFailure("invalid_response")
        return RootClips(revision, json.getBoolean("capture_ready"), entries)
    }
}
