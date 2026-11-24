package seeyuer.sayin.models

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.sayin.translation.TranslationCatalog

@RunWith(AndroidJUnit4::class)
class ReferenceModelStorageInstrumentedTest {
    @Test
    fun actualExternalModelDirectoryCanBeReferenced() = runBlocking {
        val source = File("/sdcard/0-store/model/ASR/qwen-06")
        org.junit.Assume.assumeTrue("external model directory is not available", source.isDirectory)
        val file = File(source, "conv_frontend.onnx")
        org.junit.Assume.assumeTrue("external model file is not readable", file.isFile && file.canRead())
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val model = LocalModel(
            id = "reference-external-probe",
            category = ModelCategory.Recognition,
            name = "Reference external probe",
            precision = "TEST",
            files = listOf(ModelFile("conv_frontend.onnx", file.length(), sha)),
            huggingFacePath = null,
            modelScopePath = null,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = LocalModelStorage(context, LocalModelCatalog(context, TranslationCatalog.read(context)))
        val candidate = ModelCandidate(model, emptyMap(), sourceDirectory = source)
        try {
            storage.reference(candidate, onPublishing = {}, progress = {})
            assertTrue(storage.isReference(model))
            assertEquals(source, storage.directory(model))
        } finally {
            storage.delete(model)
        }
    }
}
