package seeyuer.sayin.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Private encrypted files; callers run all methods on IO, never the UI thread. */
internal class ImageFiles(
    private val directory: File,
    private val wrapKey: (String, String, ByteArray) -> ByteArray,
    private val unwrapKey: (String, String, ByteArray) -> ByteArray,
    private val openLegacy: (String, String, ByteArray) -> ByteArray = unwrapKey,
) {
    constructor(directory: File, store: ProtectedStore) :
        this(directory, store::wrapImageKey, store::unwrapImageKey, store::decryptImage)
    init { check(directory.isDirectory || directory.mkdirs()) }
    private fun path(id: String) = File(directory, "${validId(id)}.image")

    fun write(
        hostId: String, deviceId: String, metadata: ImageMetadata, bytes: ByteArray,
        timing: ((String, Long) -> Unit)? = null,
    ): ImageReference {
        validId(hostId); validId(deviceId)
        measureImageStage(timing, "file_digest_check") { metadata.verify(bytes) }
        val resource = ImageReference(UUID.randomUUID().toString(), metadata)
        val envelope = JSONObject().put("host_id", hostId).put("pair_id", deviceId).put("resource", resource.json())
            .toString().toByteArray(Charsets.UTF_8)
        require(envelope.size <= MAX_ENVELOPE)
        val plaintext = ByteBuffer.allocate(12 + envelope.size + bytes.size).put(MAGIC).putInt(envelope.size).put(envelope).put(bytes).array()
        val encrypted = try {
            measureImageStage(timing, "file_encrypt") { seal(hostId, deviceId, resource, plaintext) }
        } finally { plaintext.fill(0) }
        require(encrypted.size <= MAX_PROTECTED)
        val pending = File(directory, "${resource.resourceId}.part")
        check(pending.createNewFile())
        try {
            FileOutputStream(pending).use { stream ->
                measureImageStage(timing, "file_write") { stream.write(encrypted) }
                measureImageStage(timing, "file_sync") { stream.fd.sync() }
            }
            // Same directory, no replacement. A Room reference is committed only after this returns.
            measureImageStage(timing, "file_rename") {
                Files.move(pending.toPath(), path(resource.resourceId).toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        } finally { pending.delete() }
        return resource
    }

    fun read(hostId: String, deviceId: String, resource: ImageReference): ByteArray {
        validId(hostId); validId(deviceId)
        val protected = path(resource.resourceId).inputStream().use { boundedImageRead(it, MAX_PROTECTED) }
        val plaintext = open(hostId, deviceId, resource, protected)
        try {
            require(plaintext.size >= 12 && plaintext.copyOfRange(0, 8).contentEquals(MAGIC))
            val size = ByteBuffer.wrap(plaintext, 8, 4).int
            require(size in 1..MAX_ENVELOPE && 12 + size <= plaintext.size)
            val envelope = JSONObject(plaintext.copyOfRange(12, 12 + size).toString(Charsets.UTF_8))
            val pairField = if (envelope.has("pair_id")) "pair_id" else "device_id"
            require(envelope.keys().asSequence().toSet() == setOf("host_id", pairField, "resource"))
            require(envelope.getString("host_id") == hostId && envelope.getString(pairField) == deviceId)
            require(ImageReference.parse(envelope.getJSONObject("resource")) == resource)
            val result = plaintext.copyOfRange(12 + size, plaintext.size)
            try { resource.metadata.verify(result) } catch (error: Exception) { result.fill(0); throw error }
            return result
        } finally { plaintext.fill(0) }
    }

    private fun seal(hostId: String, deviceId: String, resource: ImageReference, plaintext: ByteArray): ByteArray {
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            val wrapped = wrapKey(hostId, deviceId, key)
            check(wrapped.size == WRAPPED_KEY_BYTES)
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipher = imageCipher(Cipher.ENCRYPT_MODE, key, nonce, resource)
            val header = FORMAT_V2 + wrapped + nonce
            val result = header.copyOf(header.size + cipher.getOutputSize(plaintext.size))
            check(cipher.doFinal(plaintext, 0, plaintext.size, result, header.size) == result.size - header.size)
            return result
        } finally { key.fill(0) }
    }

    private fun open(hostId: String, deviceId: String, resource: ImageReference, bytes: ByteArray): ByteArray {
        if (!bytes.startsWithFormatV2()) return openLegacy(hostId, deviceId, bytes)
        val nonceAt = FORMAT_V2.size + WRAPPED_KEY_BYTES
        val bodyAt = nonceAt + NONCE_BYTES
        require(bytes.size >= bodyAt + TAG_BYTES)
        val key = unwrapKey(hostId, deviceId, bytes.copyOfRange(FORMAT_V2.size, nonceAt))
        try {
            require(key.size == KEY_BYTES)
            val cipher = imageCipher(Cipher.DECRYPT_MODE, key, bytes.copyOfRange(nonceAt, bodyAt), resource)
            return cipher.doFinal(bytes, bodyAt, bytes.size - bodyAt)
        } finally { key.fill(0) }
    }

    private fun ByteArray.startsWithFormatV2(): Boolean =
        size >= FORMAT_V2.size && FORMAT_V2.indices.all { this[it] == FORMAT_V2[it] }

    private fun imageCipher(mode: Int, key: ByteArray, nonce: ByteArray, resource: ImageReference): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(FORMAT_V2)
            updateAAD(resource.resourceId.toByteArray(Charsets.US_ASCII))
        }

    fun remove(id: String) { val file = path(id); check(!file.exists() || file.delete()) }

    /** Startup only, before any picker/upload is active. Never discards a DB reference. */
    fun cleanOrphans(referenced: Set<String>) {
        referenced.forEach { check(path(it).isFile) }
        checkNotNull(directory.listFiles()).forEach { file ->
            check(file.isFile)
            if (file.extension != "image" || file.nameWithoutExtension !in referenced) check(file.delete())
        }
    }
    companion object {
        private val MAGIC = "SIIMAGE1".toByteArray(Charsets.US_ASCII)
        // Legacy files begin with a random Keystore IV. A 16-byte discriminator
        // distinguishes the outer envelope without changing the protected payload.
        private val FORMAT_V2 = "SAYIN-IMAGE-AE02".toByteArray(Charsets.US_ASCII)
        private val random = SecureRandom()
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val WRAPPED_KEY_BYTES = NONCE_BYTES + KEY_BYTES + TAG_BYTES
        private const val MAX_ENVELOPE = 4096
        private const val MAX_PROTECTED = MAX_IMAGE_BYTES + 65536
    }
}

/** A size hint is not a bound: also enforce it while reading a growing source. */
internal fun boundedImageRead(input: InputStream, limit: Int = MAX_IMAGE_BYTES): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(count > 0 && output.size() <= limit - count)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
