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
    @Test fun candidateSurvivesReopenWithoutEnteringSelectionUntilConfirmed() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "seeyuer.sayin.imagetest")
        val namespace = "candidate-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences(namespace + name, mode)
        }
        val host = UUID.randomUUID().toString()
        val pair = UUID.randomUUID().toString()
        val rejected = UUID.randomUUID().toString()
        val value = JSONObject().put("host_id", host).put("pair_id", pair)
            .put("token", UUID.randomUUID().toString())
        val preferences = context.getSharedPreferences("paired_host", Context.MODE_PRIVATE)
        try {
            ProtectedStore(context).saveCandidate(value)
            val reopened = ProtectedStore(context)
            assertNull(reopened.load())
            assertTrue(reopened.loadAll().isEmpty())
            assertFalse(preferences.all.values.any { it.toString().contains(value.getString("token")) })
            assertEquals(value.getString("token"), reopened.loadCandidate()!!.getString("token"))
            assertThrows(IllegalStateException::class.java) { reopened.activateCandidate(host, rejected) }
            reopened.activateCandidate(host, pair)
            reopened.activateCandidate(host, pair)
            assertNull(ProtectedStore(context).loadCandidate())
            assertEquals(pair, ProtectedStore(context).load()!!.getString("pair_id"))

            val otherHost = UUID.randomUUID().toString()
            try {
                reopened.saveCandidate(JSONObject().put("host_id", otherHost).put("pair_id", rejected))
                reopened.removeCandidate(otherHost, rejected)
                assertNull(ProtectedStore(context).loadCandidate())
                assertEquals(pair, ProtectedStore(context).load()!!.getString("pair_id"))
            } finally {
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                keys.deleteEntry("voicelink.private.v3.${otherHost.replace('-', '_')}.${rejected.replace('-', '_')}")
            }

            val originalHistory = byteArrayOf(3, 2, 1)
            val encryptedHistory = reopened.encrypt(host, pair, originalHistory)
            val replacement = UUID.randomUUID().toString()
            try {
                reopened.saveCandidate(JSONObject().put("host_id", host).put("pair_id", replacement)
                    .put("replaces_pair_id", pair))
                assertEquals(pair, reopened.load()!!.getString("pair_id"))
                assertFalse(reopened.activateCandidate(host, replacement).has("replaces_pair_id"))
                assertEquals(replacement, reopened.load()!!.getString("pair_id"))
                assertEquals(1, reopened.loadAll().size)
                assertNull(reopened.load(host, pair))
                assertArrayEquals(originalHistory, reopened.decrypt(host, pair, encryptedHistory))
            } finally {
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                keys.deleteEntry("voicelink.private.v3.${host.replace('-', '_')}.${replacement.replace('-', '_')}")
            }
        } finally {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.deleteEntry("voicelink.private.v3.${host.replace('-', '_')}.${pair.replace('-', '_')}")
            target.deleteSharedPreferences(namespace + "paired_host")
        }
    }

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
