package seeyuer.sayin.data

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import seeyuer.sayin.translation.*

class TranslationPreparationInstrumentedTest {
    @Test fun verifiedAtomicImportRejectsCorruptionAndCancellationRetainsOldModel(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = "GGUF-test-only-public-model".toByteArray()
        val model = TranslationModel("fixture", "fixture.gguf", bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, "fixture", 0, "fixture")
        val suffix = "-test-${UUID.randomUUID()}"
        val store = TranslationModelStore(context, model, suffix)
        val directory = File(context.noBackupFilesDir, "translation$suffix")
        try {
            var copied = 0L
            store.import(ByteArrayInputStream(bytes)) { copied = it.bytes }
            assertEquals(bytes.size.toLong(), copied); assertTrue(store.prepared); assertArrayEquals(bytes, store.file.readBytes())
            try {
                store.import(ByteArrayInputStream(ByteArray(bytes.size) { 1 })) {}
                fail("Corruption must fail")
            } catch (_: ModelIntegrityException) { }
            assertArrayEquals(bytes, store.file.readBytes())
            try {
                store.import(ByteArrayInputStream(bytes)) { if (it.bytes > 0) throw CancellationException() }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
            assertArrayEquals(bytes, store.file.readBytes())
            assertFalse(File(directory, model.fileName + ".pending").exists())
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
