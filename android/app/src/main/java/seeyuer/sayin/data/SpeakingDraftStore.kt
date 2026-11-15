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
    fun load(): SavedSpeakingDraft? {
        val encrypted = try { file.openRead().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1_048_576)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } } catch (error: java.io.FileNotFoundException) { if (file.baseFile.exists()) throw error else return null }
        require(encrypted.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        cipher.updateAAD("SayIn/speaking/v1".toByteArray())
        val value = JSONObject(cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)).toString(Charsets.UTF_8))
        check(value.getInt("version") == 1)
        val text = value.getString("text")
        val start = value.getInt("start"); val end = value.getInt("end")
        require(text.length <= 64_000 && start in 0..text.length && end in 0..text.length)
        val translated = value.optString("translationText").takeIf { value.has("translationText") && !value.isNull("translationText") }
        val modelHash = value.optString("translationModelSha256").takeIf { it.isNotEmpty() }
        val template = value.optString("translationTemplate").takeIf { it.isNotEmpty() }
        require(translated == null || translated.length <= 64_000 && modelHash?.matches(Regex("[0-9a-f]{64}")) == true && !template.isNullOrBlank())
        return SavedSpeakingDraft(validId(value.getString("id")), text, start, end,
            value.optString("requestId").takeIf { it.isNotEmpty() }?.let(::validId),
            value.optString("requestText").takeIf { it.isNotEmpty() }, value.optString("requestState").takeIf { it.isNotEmpty() },
            translated, modelHash, template, value.optBoolean("translationShown"), value.optBoolean("requestTranslated"))
    }
    fun save(value: SavedSpeakingDraft) {
        require(value.text.length <= 64_000 && (value.translationText?.length ?: 0) <= 64_000)
        val json = JSONObject().put("version", 1).put("id", value.id).put("text", value.text).put("start", value.start).put("end", value.end)
            .put("requestId", value.requestId).put("requestText", value.requestText).put("requestState", value.requestState)
            .put("translationText", value.translationText).put("translationModelSha256", value.translationModelSha256)
            .put("translationTemplate", value.translationTemplate).put("translationShown", value.translationShown).put("requestTranslated", value.requestTranslated)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD("SayIn/speaking/v1".toByteArray())
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
