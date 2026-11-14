package seeyuer.sayin.asr

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class DoubaoProtocolTest {
    @Test fun audioIsLittleEndianAndLastFrameHasNoSequence() {
        val pcm = DoubaoProtocol.pcm16(floatArrayOf(-1f, 0f, 0.5f, 1f))
        assertArrayEquals(byteArrayOf(0, -128, 0, 0, 0, 64, -1, 127), pcm)
        val frame = DoubaoProtocol.audio(pcm, true)
        assertEquals(0x22, frame[1].toInt())
        assertEquals(0, frame[2].toInt())
        assertEquals(8, ByteBuffer.wrap(frame, 4, 4).int)
        assertArrayEquals(pcm, frame.copyOfRange(8, frame.size))
    }

    @Test fun serverFramesSupportExtensionsGzipAndNegativeFinalSequence() {
        val text = "{\"result\":{\"text\":\"你好。\"}}"
        val compressed = gzip(text.toByteArray())
        val bytes = ByteBuffer.allocate(20 + compressed.size).put(0x12).put(0x93.toByte()).put(0x11).put(0)
            .putInt(0).putInt(-5).putInt(compressed.size).put(compressed).array().copyOf(16 + compressed.size)
        // Four-byte extension, negative sequence, size and gzip payload.
        val packet = DoubaoProtocol.parse(bytes)
        assertEquals(text, packet.payload)
        assertTrue(packet.final)
        assertNull(packet.errorCode)
    }

    @Test fun errorsAreNotResultsAndPlainUtf8IsSupported() {
        val message = "service unavailable".toByteArray()
        val bytes = ByteBuffer.allocate(12 + message.size).put(0x11).put(0xf0.toByte()).put(0).put(0)
            .putInt(55_000_031).putInt(message.size).put(message).array()
        val result = DoubaoProtocol.parse(bytes)
        assertEquals(55_000_031, result.errorCode)
        assertFalse(result.final)
    }

    @Test fun finalityComesFromFlagsIndependentlyOfSequenceSign() {
        for (sequence in listOf(-5, 5)) {
            for (flags in listOf(1, 3)) {
                val packet = DoubaoProtocol.parse(sequencedServerPacket("{}", flags, sequence))
                assertEquals(flags == 3, packet.final)
                assertEquals("{}", packet.payload)
            }
        }
    }

    @Test fun rejectsTruncationOversizeInvalidFlagsAndInvalidUtf8() {
        val valid = serverPacket("{}", false)
        for (length in 0 until valid.size) assertProtocolError(valid.copyOf(length))
        assertProtocolError(valid + byteArrayOf(0))
        assertProtocolError(valid.clone().apply { this[0] = 0x21 })
        assertProtocolError(valid.clone().apply { this[1] = 0x98.toByte() })
        assertProtocolError(valid.clone().apply { this[2] = 0x12 })
        assertProtocolError(valid.clone().apply { this[8] = 0xff.toByte() })
        assertProtocolError(valid.clone().apply { ByteBuffer.wrap(this, 4, 4).putInt(Int.MAX_VALUE) })
        assertProtocolError(ByteBuffer.allocate(8).put(0x11).put(0x93.toByte()).put(0x10).put(0).putInt(1).array())
    }

    @Test fun gzipExpansionHasStrictBound() {
        val compressed = gzip(ByteArray(DoubaoProtocol.MAX_PAYLOAD + 1) { 32 })
        val bytes = ByteBuffer.allocate(8 + compressed.size).put(0x11).put(0x90.toByte()).put(0x11).put(0)
            .putInt(compressed.size).put(compressed).array()
        assertProtocolError(bytes)
    }

    @Test fun configRejectsHeaderInjectionAndSecretsAreRedacted() {
        val config = config()
        assertFalse(config.toString().contains("test-secret"))
        assertThrows(IllegalArgumentException::class.java) { CloudConfiguration(DoubaoAuth.AppKey, "secret\r\nX:1", "", "", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { CloudConfiguration(DoubaoAuth.AppIdToken, "stray", "id", "token", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { parseHotwords("很".repeat(81)) }
        assertEquals(listOf("SayIn", "你好"), parseHotwords("SayIn\n\n你好\nSayIn"))
    }

    private fun assertProtocolError(bytes: ByteArray) {
        val error = assertThrows(CloudRecognitionException::class.java) { DoubaoProtocol.parse(bytes) }
        assertEquals(CloudIssue.Protocol, error.issue)
    }
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()
}

internal fun config() = CloudConfiguration(DoubaoAuth.AppKey, "test-secret", "", "", listOf("SayIn"))
internal fun serverPacket(json: String, final: Boolean): ByteArray {
    val payload = json.toByteArray()
    return ByteBuffer.allocate(8 + payload.size).put(0x11).put(if (final) 0x92.toByte() else 0x90.toByte())
        .put(0x10).put(0).putInt(payload.size).put(payload).array()
}

internal fun sequencedServerPacket(json: String, flags: Int, sequence: Int): ByteArray {
    val payload = json.toByteArray()
    return ByteBuffer.allocate(12 + payload.size).put(0x11).put((0x90 or flags).toByte())
        .put(0x10).put(0).putInt(sequence).putInt(payload.size).put(payload).array()
}
