package seeyuer.sayin.data

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

internal const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
internal const val MAX_IMAGE_PIXELS = 16_000_000L
internal const val MAX_IMAGE_SIDE = 8192
internal const val IMAGE_CHUNK_BYTES = 48 * 1024
internal const val IMAGE_CHUNK_HEADER_BYTES = 32

internal enum class ImageMime(val wire: String) { PNG("image/png"), JPEG("image/jpeg") }

internal fun imageDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal data class ImageMetadata(val bytes: Int, val mime: ImageMime, val width: Int, val height: Int, val sha256: String) {
    init {
        require(bytes in 1..MAX_IMAGE_BYTES)
        require(width in 1..MAX_IMAGE_SIDE && height in 1..MAX_IMAGE_SIDE && width.toLong() * height <= MAX_IMAGE_PIXELS)
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' })
    }
    fun verify(value: ByteArray) { require(value.size == bytes && imageDigest(value) == sha256) }
    fun json(): JSONObject = JSONObject().put("bytes", bytes).put("mime", mime.wire)
        .put("width", width).put("height", height).put("sha256", sha256)
    companion object {
        fun parse(value: JSONObject): ImageMetadata {
            require(value.keys().asSequence().toSet() == setOf("bytes", "mime", "width", "height", "sha256"))
            fun integer(name: String): Int {
                val number = value.get(name)
                require(number is Int || number is Long)
                val result = (number as Number).toLong()
                require(result in 0..Int.MAX_VALUE.toLong())
                return result.toInt()
            }
            return ImageMetadata(integer("bytes"), ImageMime.entries.single { it.wire == value.getString("mime") },
                integer("width"), integer("height"), value.getString("sha256"))
        }
    }
}

internal data class ImageReference(val resourceId: String, val metadata: ImageMetadata) {
    init { validId(resourceId) }
    fun json(): JSONObject = JSONObject().put("resource_id", resourceId).put("metadata", metadata.json())
    companion object {
        fun parse(value: JSONObject): ImageReference {
            require(value.keys().asSequence().toSet() == setOf("resource_id", "metadata"))
            return ImageReference(value.getString("resource_id"), ImageMetadata.parse(value.getJSONObject("metadata")))
        }
    }
}

/** One bounded binary WS message, only on /v2/images. Never Base64 or JSON. */
internal fun imageChunk(uploadId: String, offset: Long, bytes: ByteArray): ByteArray {
    val id = UUID.fromString(validId(uploadId))
    require(bytes.size in 1..IMAGE_CHUNK_BYTES && offset >= 0 && offset <= MAX_IMAGE_BYTES.toLong() - bytes.size)
    return ByteBuffer.allocate(IMAGE_CHUNK_HEADER_BYTES + bytes.size).put("SIMG".toByteArray(Charsets.US_ASCII))
        .putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).putLong(offset).putInt(bytes.size).put(bytes).array()
}
