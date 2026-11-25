package seeyuer.sayin.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Private encrypted files; callers run all methods on IO, never the UI thread. */
internal class ImageFiles(
    private val directory: File,
    private val protect: (String, String, ByteArray) -> ByteArray,
    private val unprotect: (String, String, ByteArray) -> ByteArray,
) {
    constructor(directory: File, store: ProtectedStore) : this(directory, store::encryptImage, store::decryptImage)
    init { check(directory.isDirectory || directory.mkdirs()) }
    private fun path(id: String) = File(directory, "${validId(id)}.image")

    fun write(hostId: String, deviceId: String, metadata: ImageMetadata, bytes: ByteArray): ImageReference {
        validId(hostId); validId(deviceId); metadata.verify(bytes)
        val resource = ImageReference(UUID.randomUUID().toString(), metadata)
        val envelope = JSONObject().put("host_id", hostId).put("pair_id", deviceId).put("resource", resource.json())
            .toString().toByteArray(Charsets.UTF_8)
        require(envelope.size <= MAX_ENVELOPE)
        val plaintext = ByteBuffer.allocate(12 + envelope.size + bytes.size).put(MAGIC).putInt(envelope.size).put(envelope).put(bytes).array()
        val encrypted = try { protect(hostId, deviceId, plaintext) } finally { plaintext.fill(0) }
        require(encrypted.size <= MAX_PROTECTED)
        val pending = File(directory, "${resource.resourceId}.part")
        check(pending.createNewFile())
        try {
            FileOutputStream(pending).use { stream -> stream.write(encrypted); stream.fd.sync() }
            // Same directory, no replacement. A Room reference is committed only after this returns.
            Files.move(pending.toPath(), path(resource.resourceId).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
        return resource
    }

    fun read(hostId: String, deviceId: String, resource: ImageReference): ByteArray {
        validId(hostId); validId(deviceId)
        val protected = path(resource.resourceId).inputStream().use { boundedImageRead(it, MAX_PROTECTED) }
        val plaintext = unprotect(hostId, deviceId, protected)
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
