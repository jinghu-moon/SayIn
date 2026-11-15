package seeyuer.sayin.data

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistStorageInstrumentedTest {
    // Clipboard reads need the app window to be focused; MIUI also blocks a plain
    // ActivityScenario launch from a background instrumentation process, so first bring
    // the debug-only empty host forward through shell, exactly like MobileShellInstrumentedTest.
    // Every clipboard read below is explicitly one-shot and passes the verified focus flag.
    @get:Rule(order = 0)
    val foregroundHost = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -W -n ${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/androidx.activity.ComponentActivity",
            ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboardManager get() = context.getSystemService(ClipboardManager::class.java)

    @After
    fun clearClipboard() {
        clipboardManager.clearPrimaryClip()
    }

    @Test
    fun readsPlainHtmlEmptyImageUriIntentMultipleAndUnfocused() {
        val clipboard = AndroidClipboard(context)

        clipboardManager.setPrimaryClip(ClipData.newPlainText("text", "hello\r\nworld"))
        assertEquals(ClipboardRead.Text("hello\r\nworld", sensitive = false, multiple = false), clipboard.read(true))
        assertEquals(ClipboardRead.Unavailable, clipboard.read(false))

        clipboardManager.setPrimaryClip(ClipData.newHtmlText("html", "styled text", "<b>styled text</b>"))
        assertEquals(ClipboardRead.Text("styled text", sensitive = false, multiple = false), clipboard.read(true))

        clipboardManager.setPrimaryClip(ClipData.newPlainText("empty", ""))
        assertEquals(ClipboardRead.Empty, clipboard.read(true))

        val imageUri = Uri.parse("content://${context.packageName}.fixtures/missing-image.png")
        clipboardManager.setPrimaryClip(ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(imageUri)))
        assertEquals(ClipboardRead.NoText, clipboard.read(true))

        clipboardManager.setPrimaryClip(ClipData.newRawUri("uri", Uri.parse("https://example.com/private")))
        assertEquals(ClipboardRead.NoText, clipboard.read(true))

        clipboardManager.setPrimaryClip(
            ClipData.newIntent("intent", Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
        )
        assertEquals(ClipboardRead.NoText, clipboard.read(true))

        val multiple = ClipData.newPlainText("first", "first")
        multiple.addItem(ClipData.Item("second"))
        clipboardManager.setPrimaryClip(multiple)
        assertEquals(ClipboardRead.Text("first", sensitive = false, multiple = true), clipboard.read(true))

        clipboardManager.clearPrimaryClip()
        assertEquals(ClipboardRead.Unavailable, clipboard.read(true))
    }

    @Test
    fun reportsAndroidSensitiveFlagWhenThePlatformSupportsIt() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val description = ClipDescription("sensitive", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN))
        description.setExtras(PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) })
        clipboardManager.setPrimaryClip(ClipData(description, ClipData.Item("secret")))
        assertEquals(
            ClipboardRead.Text("secret", sensitive = true, multiple = false),
            AndroidClipboard(context).read(true),
        )
    }

    @Test
    fun encryptedLibraryRoundTripsAndRefusesTamperAndWrongKey() {
        val suffix = "-assist-${UUID.randomUUID()}"
        val otherSuffix = "-assist-${UUID.randomUUID()}"
        val file = File(context.noBackupFilesDir, EncryptedPhraseStorage.storageFileName(suffix))
        val otherFile = File(context.noBackupFilesDir, EncryptedPhraseStorage.storageFileName(otherSuffix))
        try {
            val storage = EncryptedPhraseStorage(context, suffix)
            assertNull(storage.load())
            val state = PhraseLibraryState(
                4,
                listOf(
                    StoredPhrase(UUID.randomUUID().toString(), "私有 phrase\r\nsecond"),
                    StoredPhrase(UUID.randomUUID().toString(), "😀"),
                ),
            )
            storage.save(state)
            assertEquals(state, EncryptedPhraseStorage(context, suffix).load())
            val bytes = file.readBytes()
            assertFalse(bytes.toString(Charsets.UTF_8).contains("私有"))

            // An existing file without its key must refuse rather than create a new identity.
            file.copyTo(otherFile)
            assertThrows(Exception::class.java) { EncryptedPhraseStorage(context, otherSuffix).load() }

            // A different existing key must fail authentication instead of returning empty data.
            otherFile.delete()
            EncryptedPhraseStorage(context, otherSuffix).save(
                PhraseLibraryState(1, listOf(StoredPhrase(UUID.randomUUID().toString(), "other"))),
            )
            file.copyTo(otherFile, overwrite = true)
            assertThrows(Exception::class.java) { EncryptedPhraseStorage(context, otherSuffix).load() }

            val tampered = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            file.writeBytes(tampered)
            assertThrows(Exception::class.java) { EncryptedPhraseStorage(context, suffix).load() }

            // A corrupt library is reported, never treated as empty and never overwritten.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val library = PhraseLibrary(EncryptedPhraseStorage(context, suffix), scope)
                library.start()
                runBlocking { withTimeout(10_000) { library.ready.first { it } } }
                assertTrue(library.issue.value)
                assertEquals(PhraseWriteResult.UNAVAILABLE, runBlocking { library.add("new") })
                assertArrayEquals(tampered, file.readBytes())
            } finally {
                scope.cancel()
            }
        } finally {
            cleanup(suffix, otherSuffix)
        }
    }

    @Test
    fun concurrentAddsAreSerializedAndAllPersist() = runBlocking {
        val suffix = "-assist-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val library = PhraseLibrary(EncryptedPhraseStorage(context, suffix), scope)
            library.start()
            val results = coroutineScope {
                (1..16).map { index -> async(Dispatchers.Default) { library.add("phrase $index") } }.awaitAll()
            }
            assertTrue(results.all { it == PhraseWriteResult.SAVED })
            assertEquals(16, library.state.value.phrases.size)
            assertEquals(16L, library.state.value.revision)
            assertEquals(library.state.value, EncryptedPhraseStorage(context, suffix).load())
        } finally {
            scope.cancel()
            cleanup(suffix)
        }
    }

    private fun cleanup(vararg suffixes: String) {
        for (suffix in suffixes) {
            val base = File(context.noBackupFilesDir, EncryptedPhraseStorage.storageFileName(suffix))
            listOf(base, File(base.path + ".bak"), File(base.path + ".new")).forEach { it.delete() }
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(EncryptedPhraseStorage.storageKeyAlias(suffix))
            }
        }
    }
}
