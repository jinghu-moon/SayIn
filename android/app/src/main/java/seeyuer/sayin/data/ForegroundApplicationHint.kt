package seeyuer.sayin.data

import org.json.JSONObject
import java.util.Base64

/** Ephemeral presentation channel. Connection generation is owned by the repository. */
internal data class ForegroundApplicationHint(val sequence: Long = 0, val name: String? = null, val iconPng: String? = null) {
    fun receive(value: JSONObject): ForegroundApplicationHint {
        val serial = (value.opt("seq") as? String)?.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }?.toLongOrNull()
            ?: return copy(name = null, iconPng = null)
        if (serial <= sequence) return this
        val raw = value.opt("name")
        val label = (raw as? String)?.takeIf {
            it.isNotBlank() && it.codePointCount(0, it.length) <= 96 && it.none(Char::isISOControl)
        }
        return if (value.has("name") && (raw == JSONObject.NULL || label != null))
            ForegroundApplicationHint(serial, label, (value.opt("icon_png") as? String)?.takeIf { label != null && iconBytes(it) != null })
        else copy(name = null, iconPng = null)
    }

    fun confirmed(value: JSONObject): Boolean = sequence > 0 && (value.opt("foreground_seq") as? String) == sequence.toString()

    companion object {
        /** Bound bytes and PNG IHDR before passing any data to the platform decoder. */
        fun iconBytes(encoded: String): ByteArray? {
            if (encoded.length !in 44..16384 || encoded.length % 4 != 0) return null
            val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
            if (bytes.size !in 33..12288 || Base64.getEncoder().encodeToString(bytes) != encoded) return null
            val header = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82)
            if (!header.indices.all { bytes[it] == header[it] }) return null
            fun dimension(offset: Int): Int = (0..3).fold(0) { n, i -> (n shl 8) or (bytes[offset + i].toInt() and 255) }
            return bytes.takeIf { dimension(16) == 48 && dimension(20) == 48 }
        }
    }
}
