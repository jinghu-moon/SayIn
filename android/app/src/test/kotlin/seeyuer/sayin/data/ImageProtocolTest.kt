package seeyuer.sayin.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class ImageProtocolTest {
    private val id = "12345678-1234-4234-8234-123456789abc"
    private val bytes = byteArrayOf(1, 2, 3)
    private fun metadata() = ImageMetadata(3, ImageMime.PNG, 3, 2, imageDigest(bytes))

    @Test fun binaryChunkMatchesRustGoldenBytesAndDoesNotBase64Encode() {
        val hex = imageChunk(id, 0, bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("53494d4712345678123442348234123456789abc000000000000000000000003010203", hex)
        for ((offset, value) in listOf(-1L to bytes, Long.MAX_VALUE to bytes, 0L to byteArrayOf(), 0L to ByteArray(IMAGE_CHUNK_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) { imageChunk(id, offset, value) }
        }
        assertEquals(IMAGE_CHUNK_HEADER_BYTES + IMAGE_CHUNK_BYTES, imageChunk(id, 0, ByteArray(IMAGE_CHUNK_BYTES)).size)
    }

    @Test fun descriptorRoundTripAndStrictDimensionsHashMimeAndTypes() {
        val resource = ImageReference(id, metadata())
        assertEquals(resource, ImageReference.parse(resource.json()))
        for (value in listOf(
            metadata().json().put("bytes", "3"), metadata().json().put("bytes", 3.0),
            metadata().json().put("bytes", MAX_IMAGE_BYTES + 1), metadata().json().put("width", Int.MAX_VALUE),
            metadata().json().put("width", 4001).put("height", 4000), metadata().json().put("mime", "image/gif"),
            metadata().json().put("sha256", imageDigest(bytes).uppercase()), metadata().json().put("extra", true),
        )) { assertThrows(Exception::class.java) { ImageMetadata.parse(value) } }
        metadata().verify(bytes)
        assertThrows(IllegalArgumentException::class.java) { metadata().verify(byteArrayOf(3, 2, 1)) }
        assertThrows(IllegalArgumentException::class.java) { ImageReference("../image", metadata()) }
    }

    @Test fun boundedReadRejectsOverflowAndDoesNotTrustLengthHints() {
        assertArrayEquals(bytes, boundedImageRead(ByteArrayInputStream(bytes), 3))
        assertThrows(IllegalArgumentException::class.java) { boundedImageRead(ByteArrayInputStream(bytes), 2) }
        assertArrayEquals(byteArrayOf(), boundedImageRead(ByteArrayInputStream(byteArrayOf()), 1))
    }

    @Test fun protectedResourceSurvivesReopenButRejectsWrongIdentitySwapAndTamper() {
        val root = Files.createTempDirectory("sayin-image-unit-").toFile()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val protect = { value: ByteArray ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD("test/image".toByteArray()) }
            cipher.iv + cipher.doFinal(value)
        }
        val unprotect = { value: ByteArray ->
            Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0, 12))); updateAAD("test/image".toByteArray()); doFinal(value.copyOfRange(12, value.size)) }
        }
        val host = UUID.randomUUID().toString()
        val device = UUID.randomUUID().toString()
        try {
            val store = ImageFiles(root, protect, unprotect)
            val resource = store.write(host, device, metadata(), bytes)
            val file = root.resolve("${resource.resourceId}.image")
            val encrypted = file.readBytes()
            assertFalse(encrypted.contentEquals(bytes))
            val reopened = ImageFiles(root, protect, unprotect)
            assertArrayEquals(bytes, reopened.read(host, device, resource))
            assertThrows(Exception::class.java) { reopened.read(UUID.randomUUID().toString(), device, resource) }
            assertThrows(Exception::class.java) { reopened.read(host, UUID.randomUUID().toString(), resource) }
            val other = store.write(host, device, metadata(), bytes)
            file.writeBytes(root.resolve("${other.resourceId}.image").readBytes())
            assertThrows(Exception::class.java) { reopened.read(host, device, resource) }
            val corrupt = encrypted.copyOf().apply { this[size / 2] = (this[size / 2].toInt() xor 1).toByte() }
            file.writeBytes(corrupt)
            assertThrows(Exception::class.java) { reopened.read(host, device, resource) }
            file.writeBytes(encrypted)
            root.resolve("interrupted.part").writeBytes(byteArrayOf(7))
            store.cleanOrphans(setOf(resource.resourceId))
            assertEquals(1, root.listFiles()!!.size)
            store.remove(resource.resourceId)
            assertThrows(Exception::class.java) { reopened.read(host, device, resource) }
        } finally { root.deleteRecursively() }
    }
}
