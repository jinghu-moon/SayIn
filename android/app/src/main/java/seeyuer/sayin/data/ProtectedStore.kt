package seeyuer.sayin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ProtectedStore(context: Context) {
    private val preferences = context.getSharedPreferences("paired_host", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "voicelink.private.v1"

    @Synchronized
    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        // Never replace a missing key while encrypted identity/outbox data exists.
        check(!preferences.contains("identity") && !preferences.contains("key_created"))
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey().also { check(preferences.edit().putBoolean("key_created", true).commit()) }
        }
    }
    fun encrypt(value: ByteArray): ByteArray = encrypt(value, "VoiceLink/private/v1")
    fun encryptImage(value: ByteArray): ByteArray = encrypt(value, "SayIn/image/v1")
    private fun encrypt(value: ByteArray, domain: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(domain.toByteArray())
        return cipher.iv + cipher.doFinal(value)
    }
    fun decrypt(value: ByteArray): ByteArray = decrypt(value, "VoiceLink/private/v1")
    fun decryptImage(value: ByteArray): ByteArray = decrypt(value, "SayIn/image/v1")
    private fun decrypt(value: ByteArray, domain: String): ByteArray {
        require(value.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12)))
        cipher.updateAAD(domain.toByteArray())
        return cipher.doFinal(value.copyOfRange(12, value.size))
    }
    fun load(): JSONObject? = preferences.getString("identity", null)?.let { strictJson(decrypt(decode(it)).toString(Charsets.UTF_8)) }
    fun save(value: JSONObject) {
        check(preferences.edit().putString("identity", encode(encrypt(value.toString().toByteArray()))).commit())
    }
    fun forget() { check(preferences.edit().remove("identity").commit()) }
}
