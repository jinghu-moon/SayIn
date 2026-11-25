package seeyuer.sayin.data

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class ProtectedStoreInstrumentedTest {
    @Test fun migratedRecordsStayBoundToTheirPairAndMissingKeysFailClosed() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "seeyuer.sayin.imagetest")
        val namespace = "v3-store-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences(namespace + name, mode)
        }
        val preferences = context.getSharedPreferences("paired_host", Context.MODE_PRIVATE)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val legacyAlias = "voicelink.private.v1"
        val hadLegacyKey = keys.containsAlias(legacyAlias)
        val legacyKey = keys.getKey(legacyAlias, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(legacyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
        fun sealLegacy(domain: String, payload: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, legacyKey)
            cipher.updateAAD(domain.toByteArray())
            return cipher.iv + cipher.doFinal(payload)
        }
        val host = UUID.randomUUID().toString()
        val pair = UUID.randomUUID().toString()
        val secondHost = UUID.randomUUID().toString()
        val secondPair = UUID.randomUUID().toString()
        fun alias(hostId: String, pairId: String) =
            "voicelink.private.v3.${hostId.replace('-', '_')}.${pairId.replace('-', '_')}"
        try {
            val identity = JSONObject().put("host_id", host).put("device_id", pair)
            val original = byteArrayOf(1, 2, 3)
            val oldText = sealLegacy("VoiceLink/private/v1", original)
            val oldImage = sealLegacy("SayIn/image/v1", original)
            assertTrue(preferences.edit().putString("identity",
                encode(sealLegacy("VoiceLink/private/v1", identity.toString().toByteArray()))).commit())
            val store = ProtectedStore(context)
            assertEquals(pair, store.load()!!.getString("device_id"))
            val second = JSONObject().put("host_id", secondHost).put("pair_id", secondPair)
            store.save(second)
            assertArrayEquals(original, store.decrypt(host, pair, oldText))
            assertArrayEquals(original, store.decryptImage(host, pair, oldImage))
            assertThrows(Exception::class.java) { store.decrypt(secondHost, secondPair, oldText) }
            val currentText = store.encrypt(host, pair, original)
            store.forget(host, pair)
            assertEquals(secondPair, store.load()!!.getString("pair_id"))
            assertArrayEquals(original, store.decrypt(host, pair, currentText))
            keys.deleteEntry(alias(secondHost, secondPair))
            assertThrows(IllegalStateException::class.java) { store.loadAll() }
            assertThrows(IllegalStateException::class.java) { store.save(second) }
        } finally {
            keys.deleteEntry(alias(host, pair))
            keys.deleteEntry(alias(secondHost, secondPair))
            if (!hadLegacyKey) keys.deleteEntry(legacyAlias)
            target.deleteSharedPreferences(namespace + "paired_host")
        }
    }
}
