package seeyuer.sayin.asr

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses unique cloud-only paths/aliases. Never reads or clears real credentials, pairing or Outbox. */
@RunWith(AndroidJUnit4::class)
class CloudConfigurationInstrumentedTest {
    @Test fun roundTripIsEncryptedAndIndependentFromOtherCloudConfigurations() = isolated { context, suffix, store ->
        assertNull(store.load())
        val config = CloudProfiles(doubao = DoubaoCredentials(DoubaoAuth.AppIdToken, "", "test-app", "secret-round-trip"), hotwords = listOf("SayIn", "火山"))
        store.save(config)
        val file = File(context.noBackupFilesDir, "cloud-asr$suffix.bin")
        val first = file.readBytes()
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("secret-round-trip"))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("test-app"))
        val loaded = CloudConfigurationStore(context, suffix).load()!!
        assertEquals(DoubaoAuth.AppIdToken, loaded.doubao!!.auth)
        assertEquals(config.doubao!!.accessToken, loaded.doubao!!.accessToken)
        assertEquals(config.hotwords, loaded.hotwords)
        store.save(config)
        assertFalse(first.contentEquals(file.readBytes())) // Randomized GCM IV.
        store.clear()
        assertNull(store.load())
        store.save(CloudProfiles(doubao = DoubaoCredentials(DoubaoAuth.AppKey, "new-secret", "", ""), hotwords = emptyList()))
        assertEquals("new-secret", store.load()!!.doubao!!.appKey)
    }

    @Test fun tamperingIsRejectedAndNeverReturnsPlaintext() = isolated { context, suffix, store ->
        store.save(CloudProfiles(doubao = DoubaoCredentials(DoubaoAuth.AppKey, "tamper-secret", "", ""), hotwords = emptyList()))
        val file = File(context.noBackupFilesDir, "cloud-asr$suffix.bin")
        val bytes = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertThrows(Exception::class.java) { store.load() }
        store.clear()
        assertNull(store.load())
    }

    @Test fun providersRetainIndependentCredentialsAcrossSelectionChanges() = isolated { context, suffix, store ->
        val hotwords = listOf("SayIn", "语音")
        store.save(CloudProfiles(
            selected = CloudProvider.Qwen,
            doubao = DoubaoCredentials(DoubaoAuth.AppKey, "doubao-test-secret", "", ""),
            qwen = QwenCredentials("qwen-test-secret", "ws-example"),
            hotwords = hotwords,
        ))
        val bytes = File(context.noBackupFilesDir, "cloud-asr$suffix.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("doubao-test-secret"))
        assertFalse(bytes.contains("qwen-test-secret"))
        assertFalse(bytes.contains("ws-example"))
        val reopened = CloudConfigurationStore(context, suffix)
        val qwenProfile = checkNotNull(reopened.load())
        val qwen = qwenProfile.credentials as QwenCredentials
        assertEquals("qwen-test-secret", qwen.apiKey)
        assertEquals("ws-example", qwen.workspaceId)
        assertEquals(hotwords, qwenProfile.hotwords)

        reopened.save(qwenProfile.copy(selected = CloudProvider.Doubao))
        val doubaoProfile = checkNotNull(store.load())
        assertEquals("doubao-test-secret", (doubaoProfile.credentials as DoubaoCredentials).appKey)
        assertEquals("qwen-test-secret", doubaoProfile.qwen?.apiKey)
        assertEquals(hotwords, doubaoProfile.hotwords)
    }

    @Test fun missingKeystoreKeyDoesNotSilentlyOverwriteStoredCredentials() = isolated { context, suffix, store ->
        val config = CloudProfiles(doubao = DoubaoCredentials(DoubaoAuth.AppKey, "key-loss-secret", "", ""), hotwords = emptyList())
        store.save(config)
        val file = File(context.noBackupFilesDir, "cloud-asr$suffix.bin")
        val original = file.readBytes()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry("sayin.cloud.asr.v1$suffix")
        assertThrows(IllegalStateException::class.java) { store.load() }
        assertThrows(IllegalStateException::class.java) { store.save(config) }
        assertArrayEquals(original, file.readBytes())
        // Only an explicit cloud reset permits a new key/configuration.
        store.clear()
        store.save(config)
        assertEquals(config.doubao!!.appKey, store.load()!!.doubao!!.appKey)
    }

    private fun isolated(block: (android.content.Context, String, CloudConfigurationStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = ".test-${UUID.randomUUID()}"
        val store = CloudConfigurationStore(context, suffix)
        try { block(context, suffix, store) } finally {
            store.clear()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("sayin.cloud.asr.v1$suffix") }
        }
    }
}
