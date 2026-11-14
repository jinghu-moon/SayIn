package seeyuer.sayin.asr

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
import org.json.JSONArray
import org.json.JSONObject

/** Separate from pairing/outbox keys. A cloud reset can never destroy the LAN identity. Call on IO. */
internal class CloudConfigurationStore(context: Context, private val suffix: String = "") {
    // AtomicFile itself does not synchronize separate instances in the same process.
    companion object { private val processLock = Any() }
    private val file = AtomicFile(File(context.noBackupFilesDir, "cloud-asr$suffix.bin"))
    private val alias = "sayin.cloud.asr.v1$suffix"
    private val aad = "SayIn/cloud-asr/v1".toByteArray()
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create && !file.baseFile.exists()) { "Cloud key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    fun load(): CloudConfiguration? = synchronized(processLock) {
        val bytes = try { file.openRead().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 24_576)
                output.write(buffer, 0, count)
            }
            output.toByteArray() } } catch (error: java.io.FileNotFoundException) { if (file.baseFile.exists()) throw error else return@synchronized null }
        require(bytes.size in 28..24_576)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        val json = JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        check(json.getInt("version") == 1)
        val words = json.getJSONArray("hotwords")
        CloudConfiguration(DoubaoAuth.valueOf(json.getString("auth")), json.getString("appKey"),
            json.getString("appId"), json.getString("accessToken"), List(words.length()) { words.getString(it) })
    }
    fun save(config: CloudConfiguration) = synchronized(processLock) {
        val json = JSONObject().put("version", 1).put("auth", config.auth.name).put("appKey", config.appKey)
            .put("appId", config.appId).put("accessToken", config.accessToken).put("hotwords", JSONArray(config.hotwords))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad)
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun clear() = synchronized(processLock) {
        file.delete()
        check(!file.baseFile.exists())
    }
}
