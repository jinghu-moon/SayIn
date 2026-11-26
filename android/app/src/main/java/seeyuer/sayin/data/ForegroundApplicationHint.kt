package seeyuer.sayin.data

import org.json.JSONObject
import java.util.Base64

/** Ephemeral presentation channel. Connection generation is owned by the repository. */
internal data class ForegroundApplicationHint(val sequence: Long = 0, val name: String? = null, val iconWebp: String? = null) {
    fun receive(value: JSONObject): ForegroundApplicationHint {
        val serial = (value.opt("seq") as? String)?.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }?.toLongOrNull()
            ?: return copy(name = null, iconWebp = null)
        if (serial <= sequence) return this
        val raw = value.opt("name")
        val label = (raw as? String)?.takeIf {
            it.isNotBlank() && it.codePointCount(0, it.length) <= 96 && it.none(Char::isISOControl)
        }
        return if (value.has("name") && (raw == JSONObject.NULL || label != null))
            ForegroundApplicationHint(serial, label, (value.opt("icon_webp") as? String)?.takeIf { label != null && iconBytes(it) != null })
        else copy(name = null, iconWebp = null)
    }

    fun confirmed(value: JSONObject): Boolean = sequence > 0 && (value.opt("foreground_seq") as? String) == sequence.toString()

    companion object {
        /** Bound the lossless WebP envelope before invoking the platform decoder. */
        fun iconBytes(encoded: String): ByteArray? {
            if (encoded.length !in 36..5464 || encoded.length % 4 != 0) return null
            val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
            if (bytes.size !in 26..4096 || Base64.getEncoder().encodeToString(bytes) != encoded) return null
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (buffer.getInt(0) != 0x46464952 || buffer.getInt(4) != bytes.size - 8 ||
                buffer.getInt(8) != 0x50424557 || buffer.getInt(12) != 0x4c385056 || bytes[20] != 0x2f.toByte()) return null
            val chunkSize = buffer.getInt(16)
            if (chunkSize < 5 || chunkSize.toLong() + (chunkSize and 1) + 20 != bytes.size.toLong()) return null
            val dimensions = buffer.getInt(21)
            return bytes.takeIf {
                dimensions ushr 29 == 0 && (dimensions and 0x3fff) + 1 == 48 &&
                    ((dimensions ushr 14) and 0x3fff) + 1 == 48
            }
        }
    }
}
