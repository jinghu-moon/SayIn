package seeyuer.sayin.data

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

internal data class HistoryExportRecord(
    val id: String,
    val createdAt: Long,
    val state: String,
    val kind: String,
    val application: String?,
    val text: String?,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val imageBytes: Int? = null,
)

/** Text and metadata only: image binaries stay in the encrypted on-device store. */
internal object HistoryExport {
    const val JSON_FORMAT = "sayin-history"
    const val JSON_VERSION = 1
    private val time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)
    private val magic = "SAYINBK1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 120_000

    fun markdown(records: List<HistoryExportRecord>, exportedAt: Long = System.currentTimeMillis()): String = buildString {
        appendLine("# SayIn history")
        appendLine()
        appendLine("Exported: ${time.format(Instant.ofEpochMilli(exportedAt))}")
        appendLine("Records: ${records.size}")
        appendLine()
        records.forEach { record ->
            appendLine("## ${time.format(Instant.ofEpochMilli(record.createdAt))} · ${record.state}")
            appendLine()
            appendLine("- Target: ${record.application ?: "unknown"}")
            appendLine("- Type: ${record.kind}")
            appendLine("- Request: ${record.id}")
            if (record.kind == "image") {
                val width = record.imageWidth ?: 0
                val height = record.imageHeight ?: 0
                val bytes = record.imageBytes ?: 0
                appendLine("- Image: ${width}×${height}, ${(bytes + 1023) / 1024} KiB")
            }
            val text = record.text
            if (text != null) {
                appendLine()
                appendLine("```text")
                appendLine(text)
                appendLine("```")
            }
            appendLine()
        }
    }

    fun json(records: List<HistoryExportRecord>, exportedAt: Long = System.currentTimeMillis()): String {
        val root = JSONObject()
            .put("format", JSON_FORMAT)
            .put("version", JSON_VERSION)
            .put("exported_at", exportedAt)
            .put("includes_images", false)
        val array = JSONArray()
        records.forEach { record ->
            val item = JSONObject()
                .put("id", record.id)
                .put("created_at", record.createdAt)
                .put("state", record.state)
                .put("kind", record.kind)
                .put("application", record.application ?: JSONObject.NULL)
                .put("text", record.text ?: JSONObject.NULL)
            if (record.kind == "image") {
                item.put("image", JSONObject()
                    .put("width", record.imageWidth ?: 0)
                    .put("height", record.imageHeight ?: 0)
                    .put("bytes", record.imageBytes ?: 0))
            }
            array.put(item)
        }
        root.put("records", array)
        return root.toString(2)
    }

    /** Portable passphrase backup: PBKDF2-HMAC-SHA256 + AES-256-GCM, not Keystore-bound. */
    fun encrypted(plain: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "A passphrase is required" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase, salt, ITERATIONS, 256))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.encoded, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain)
        return magic + byteArrayOf(1, salt.size.toByte(), iv.size.toByte()) + salt + iv + encrypted
    }

    fun decrypt(value: ByteArray, passphrase: CharArray): ByteArray {
        require(value.size > magic.size + 2) { "Invalid backup" }
        require(value.copyOfRange(0, magic.size).contentEquals(magic)) { "Invalid backup" }
        var offset = magic.size
        val version = value[offset++].toInt()
        require(version == 1) { "Unsupported backup version" }
        val saltSize = value[offset++].toInt()
        val ivSize = value[offset++].toInt()
        require(saltSize in 8..64 && ivSize in 8..32 && value.size > offset + saltSize + ivSize) { "Invalid backup" }
        val salt = value.copyOfRange(offset, offset + saltSize); offset += saltSize
        val iv = value.copyOfRange(offset, offset + ivSize); offset += ivSize
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase, salt, ITERATIONS, 256))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.encoded, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(value, offset, value.size - offset)
    }
}
