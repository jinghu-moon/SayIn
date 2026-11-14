package seeyuer.sayin.asr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

/** Independent implementation of Volcengine's v1 binary protocol, not copied from SayIt. */
internal object DoubaoProtocol {
    const val MAX_PAYLOAD = 256 * 1024
    class Packet(val payload: String, val final: Boolean, val errorCode: Int?)

    // PCM is intentionally uncompressed: at 32 KB/s it also makes the socket queue budget measurable.
    fun request(json: String) = frame(0x10, 0x10, json.toByteArray(Charsets.UTF_8))
    fun audio(pcm: ByteArray, last: Boolean) = frame(if (last) 0x22 else 0x20, 0, pcm)
    private fun frame(kind: Int, format: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD)
        return ByteBuffer.allocate(8 + payload.size).put(0x11).put(kind.toByte()).put(format.toByte())
            .put(0).putInt(payload.size).put(payload).array()
    }

    fun parse(bytes: ByteArray): Packet {
        fun invalid(detail: String = "frame-structure"): Nothing = throw CloudRecognitionException(CloudIssue.Protocol, detail)
        if (bytes.size !in 8..(MAX_PAYLOAD + 72)) invalid()
        val input = ByteBuffer.wrap(bytes)
        val first = input.get().toInt() and 255
        val headerSize = (first and 15) * 4
        if (first ushr 4 != 1 || headerSize < 4 || headerSize > bytes.size - 4) invalid()
        val kindFlags = input.get().toInt() and 255
        val kind = kindFlags ushr 4
        val flags = kindFlags and 15
        val format = input.get().toInt() and 255
        input.get()
        if (kind != 9 && kind != 15 || flags > 3 ||
            (kind == 9 && format ushr 4 != 1) || (kind == 15 && format ushr 4 !in 0..1) || format and 15 !in 0..1) invalid()
        input.position(headerSize)
        val errorCode = if (kind == 15) { if (input.remaining() < 8) invalid(); input.int } else null
        if (kind == 9 && flags and 1 != 0) {
            if (input.remaining() < 8) invalid()
            // Presence and finality are separate flag bits. The live service also sends positive
            // sequence values on final packets (as does the official message-flow example).
            input.int
        }
        if (input.remaining() < 4) invalid()
        val size = input.int
        if (size < 0 || size > MAX_PAYLOAD || size != input.remaining()) invalid()
        val payload = ByteArray(size).also(input::get)
        val decoded = try {
            if (format and 15 == 0) payload else GZIPInputStream(ByteArrayInputStream(payload)).use { gzip ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val count = gzip.read(chunk)
                    if (count == -1) break
                    if (out.size() + count > MAX_PAYLOAD) invalid()
                    out.write(chunk, 0, count)
                }
                out.toByteArray()
            }
        } catch (_: Exception) { invalid() }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString()
        } catch (_: Exception) { invalid() }
        return Packet(text, kind == 9 && flags and 2 != 0, errorCode)
    }

    fun pcm16(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            require(value.isFinite())
            val pcm = (value.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767)
            bytes[index * 2] = pcm.toByte()
            bytes[index * 2 + 1] = (pcm shr 8).toByte()
        }
        return bytes
    }
}
