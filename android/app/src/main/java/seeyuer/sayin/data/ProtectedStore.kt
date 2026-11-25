package seeyuer.sayin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ProtectedStore(context: Context) {
    private val preferences = context.getSharedPreferences("paired_host", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val legacyAlias = "voicelink.private.v1"

    private fun alias(hostId: String, pairId: String): String {
        validId(hostId); validId(pairId)
        return "voicelink.private.v3.${hostId.replace('-', '_')}.${pairId.replace('-', '_')}"
    }

    @Synchronized
    private fun key(alias: String, create: Boolean): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    private fun keyFor(hostId: String, pairId: String, create: Boolean): SecretKey =
        key(alias(hostId, pairId), create)

    private fun legacyKey(): SecretKey =
        (keyStore.getKey(legacyAlias, null) as? SecretKey) ?: error("Missing legacy encryption key")

    private fun encrypt(value: ByteArray, domain: String, secretKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(domain.toByteArray())
        return cipher.iv + cipher.doFinal(value)
    }
    private fun decrypt(value: ByteArray, domain: String, secretKey: SecretKey): ByteArray {
        require(value.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, value.copyOfRange(0, 12)))
        cipher.updateAAD(domain.toByteArray())
        return cipher.doFinal(value.copyOfRange(12, value.size))
    }

    private fun identities(): MutableSet<String> = preferences.getStringSet("identities", emptySet())?.toMutableSet() ?: mutableSetOf()
    private fun key(hostId: String, pairId: String) = "$hostId/$pairId"
    private fun hostOf(item: String): String = item.substringBefore('/').also(::validId)
    private fun pairOf(item: String): String = item.substringAfter('/', "").also(::validId)

    /** Upgrade the single v1 identity before exposing the v3 credential directory. */
    @Synchronized
    private fun migrateLegacyIfNeeded() {
        if (preferences.contains("identities") || !preferences.contains("identity")) return
        val encoded = preferences.getString("identity", null) ?: error("Missing legacy identity")
        val legacy = strictJson(decrypt(decode(encoded), "VoiceLink/private/v1", legacyKey()).toString(Charsets.UTF_8))
        val hostId = legacy.getString("host_id")
        val pairId = legacy.optString("pair_id").ifEmpty { legacy.getString("device_id") }
        val item = key(hostId, pairId)
        val encrypted = encrypt(legacy.toString().toByteArray(), "VoiceLink/private/v3/$item", keyFor(hostId, pairId, true))
        check(preferences.edit().remove("identity").putString("legacy_identity", item)
            .putString("identity:$item", encode(encrypted)).putStringSet("identities", setOf(item))
            .putString("selected", item).putString("selected_host_id", hostId).putBoolean("key_created:$item", true).commit())
    }

    /** Selection is a host choice. The pair id is resolved from the host's credential directory. */
    @Synchronized
    private fun selectedHostId(): String? {
        migrateLegacyIfNeeded()
        val stored = preferences.getString("selected_host_id", null)
        if (stored != null) return runCatching { validId(stored) }.getOrNull()
        val legacy = preferences.getString("selected", null) ?: return null
        return runCatching { hostOf(legacy) }.getOrNull()
    }

    @Synchronized
    private fun selectedKey(): String? {
        val hostId = selectedHostId() ?: return null
        val ids = identities()
        preferences.getString("selected", null)?.takeIf { it in ids && runCatching { hostOf(it) == hostId }.getOrDefault(false) }?.let { return it }
        return ids.filter { runCatching { hostOf(it) == hostId }.getOrDefault(false) }
            .sorted().firstOrNull()
    }

    @Synchronized
    fun loadAll(): List<JSONObject> {
        migrateLegacyIfNeeded()
        if (preferences.contains("legacy_identity")) legacyKey()
        return identities().sorted().map { item ->
            readIdentity(item)
        }
    }

    private fun readIdentity(item: String): JSONObject {
        val value = preferences.getString("identity:$item", null) ?: error("Missing credential")
        return strictJson(decrypt(decode(value), "VoiceLink/private/v3/$item",
            keyFor(hostOf(item), pairOf(item), false)).toString(Charsets.UTF_8))
    }

    @Synchronized
    fun load(hostId: String, pairId: String): JSONObject? {
        migrateLegacyIfNeeded()
        val item = key(hostId, pairId)
        if (item !in identities()) return null
        return readIdentity(item)
    }

    /** The last selected target is routing state, not credential identity. */
    fun loadHost(hostId: String): JSONObject? = loadAll().firstOrNull { it.optString("host_id") == hostId }
    @Synchronized
    fun select(hostId: String, pairId: String) {
        migrateLegacyIfNeeded()
        val item = key(hostId, pairId)
        check(item in identities())
        check(preferences.edit().putString("selected", item).putString("selected_host_id", hostId).commit())
    }
    @Synchronized
    fun save(value: JSONObject) {
        migrateLegacyIfNeeded()
        val hostId = value.getString("host_id")
        val pairId = value.optString("pair_id").ifEmpty { value.getString("device_id") }
        val item = key(hostId, pairId)
        val ids = identities().apply { add(item) }
        val create = !preferences.contains("key_created:$item") && !preferences.contains("identity:$item")
        val encrypted = encrypt(value.toString().toByteArray(), "VoiceLink/private/v3/$item", keyFor(hostId, pairId, create))
        check(preferences.edit().putString("identity:$item", encode(encrypted)).putStringSet("identities", ids)
            .putString("selected", item).putString("selected_host_id", hostId)
            .putBoolean("key_created:$item", true).commit())
    }
    fun load(): JSONObject? = selectedKey()?.let { load(hostOf(it), pairOf(it)) }
    @Synchronized
    fun forget(hostId: String? = null, pairId: String? = null) {
        val item = if (hostId != null && pairId != null) key(hostId, pairId) else selectedKey() ?: return
        val ids = identities().apply { remove(item) }
        val next = selectedKey()?.takeIf { it in ids } ?: ids.minOrNull()
        check(preferences.edit().remove("identity:$item").putStringSet("identities", ids)
            .apply {
                if (next == null) {
                    remove("selected"); remove("selected_host_id")
                } else {
                    putString("selected", next); putString("selected_host_id", hostOf(next))
                }
            }.commit())
    }

    fun encrypt(hostId: String, pairId: String, value: ByteArray): ByteArray =
        encrypt(value, "VoiceLink/private/v3/${key(hostId, pairId)}", keyFor(hostId, pairId, false))

    fun decrypt(hostId: String, pairId: String, value: ByteArray): ByteArray =
        decryptRecord(hostId, pairId, value, "", "VoiceLink/private/v1")

    fun encryptImage(hostId: String, pairId: String, value: ByteArray): ByteArray =
        encrypt(value, "VoiceLink/private/v3/${key(hostId, pairId)}/image", keyFor(hostId, pairId, false))

    fun decryptImage(hostId: String, pairId: String, value: ByteArray): ByteArray =
        decryptRecord(hostId, pairId, value, "/image", "SayIn/image/v1")

    private fun decryptRecord(hostId: String, pairId: String, value: ByteArray, suffix: String, legacyDomain: String): ByteArray {
        val item = key(hostId, pairId)
        check(preferences.contains("key_created:$item"))
        val secret = keyFor(hostId, pairId, false)
        try {
            return decrypt(value, "VoiceLink/private/v3/$item$suffix", secret)
        } catch (failure: AEADBadTagException) {
            if (preferences.getString("legacy_identity", null) != item) throw failure
            // The upgraded ledger can still contain ciphertext produced by the v1 key.
        }
        return decrypt(value, legacyDomain, legacyKey())
    }
}
