package seeyuer.sayin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Only text, selection and request identity survive a restart; never microphone or auto-send consent. */
internal data class SavedSpeakingDraft(
    val id: String, val text: String, val start: Int, val end: Int,
    val requestId: String? = null, val requestText: String? = null, val requestState: String? = null,
    val translationText: String? = null, val translationModelSha256: String? = null,
    val translationTemplate: String? = null, val translationShown: Boolean = false,
    val requestTranslated: Boolean = false,
    val requestHostId: String? = null, val requestPairId: String? = null,
)

internal class SpeakingDraftStore(context: Context, suffix: String = "") {
    private val file = AtomicFile(File(context.noBackupFilesDir, "speaking$suffix.bin"))
    private val alias = "sayin.speaking.v1$suffix"
    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create && !file.baseFile.exists())
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    fun readLegacy(): ByteArray? = try {
        file.openRead().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1_048_576)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (error: java.io.FileNotFoundException) {
        if (file.baseFile.exists()) throw error else null
    }

    fun deleteLegacy() = file.delete()

    fun open(encrypted: ByteArray): SavedSpeakingDraft {
        require(encrypted.size in 28..1_048_576)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        cipher.updateAAD("SayIn/speaking/v1".toByteArray())
        val plaintext = cipher.doFinal(encrypted, 12, encrypted.size - 12)
        val value = try { JSONObject(plaintext.toString(Charsets.UTF_8)) } finally { plaintext.fill(0) }
        check(value.getInt("version") == 1)
        val text = value.getString("text")
        val start = value.getInt("start"); val end = value.getInt("end")
        require(text.length <= 64_000 && start in 0..text.length && end in 0..text.length)
        val translated = value.optString("translationText").takeIf { value.has("translationText") && !value.isNull("translationText") }
        val modelHash = value.optString("translationModelSha256").takeIf { it.isNotEmpty() }
        val template = value.optString("translationTemplate").takeIf { it.isNotEmpty() }
        require(translated == null || translated.length <= 64_000 && modelHash?.matches(Regex("[0-9a-f]{64}")) == true && !template.isNullOrBlank())
        val hostId = value.optString("requestHostId").takeIf { it.isNotEmpty() }?.let(::validId)
        val pairId = value.optString("requestPairId").takeIf { it.isNotEmpty() }?.let(::validId)
        require((hostId == null) == (pairId == null))
        return SavedSpeakingDraft(validId(value.getString("id")), text, start, end,
            value.optString("requestId").takeIf { it.isNotEmpty() }?.let(::validId),
            value.optString("requestText").takeIf { it.isNotEmpty() }, value.optString("requestState").takeIf { it.isNotEmpty() },
            translated, modelHash, template, value.optBoolean("translationShown"), value.optBoolean("requestTranslated"),
            hostId, pairId)
    }
    fun seal(value: SavedSpeakingDraft, createKey: Boolean): ByteArray {
        require(value.text.length <= 64_000 && (value.translationText?.length ?: 0) <= 64_000)
        val json = JSONObject().put("version", 1).put("id", value.id).put("text", value.text).put("start", value.start).put("end", value.end)
            .put("requestId", value.requestId).put("requestText", value.requestText).put("requestState", value.requestState)
            .put("requestHostId", value.requestHostId).put("requestPairId", value.requestPairId)
            .put("translationText", value.translationText).put("translationModelSha256", value.translationModelSha256)
            .put("translationTemplate", value.translationTemplate).put("translationShown", value.translationShown).put("requestTranslated", value.requestTranslated)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(createKey))
        cipher.updateAAD("SayIn/speaking/v1".toByteArray())
        val plaintext = json.toString().toByteArray(Charsets.UTF_8)
        try {
            val bytes = cipher.iv + cipher.doFinal(plaintext)
            require(bytes.size <= 1_048_576)
            return bytes
        } finally { plaintext.fill(0) }
    }
}
