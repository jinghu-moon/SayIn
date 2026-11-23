package seeyuer.sayin.ui

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import seeyuer.sayin.asr.XAsrDecoder
import seeyuer.sayin.asr.LocalVoiceArtifact
import seeyuer.sayin.asr.OfflineSpeechDecoder
import seeyuer.sayin.models.LocalModelCatalog
import seeyuer.sayin.translation.*

/** Explicit opt-in and isolated verification package only. No microphone or LAN use. */
class TranslationNativeInstrumentedTest {
    @Test fun importedOfflineRecognitionModelDecodesReferenceAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = InstrumentationRegistry.getArguments().getString("recognitionModel")
        assumeTrue(id != null)
        val context = instrumentation.targetContext
        check(context.packageName == "seeyuer.sayin.imagetest")
        val catalog = LocalModelCatalog(context, TranslationCatalog.read(context))
        val model = catalog.recognition.single { it.id == id }
        val directory = File(context.getExternalFilesDir(null), "model-validation/${model.id}")
        assertTrue(model.files.all { File(directory, it.path).length() == it.bytes })
        val bytes = instrumentation.context.assets.open("x-asr-smoke.pcm").use { it.readBytes() }
        val pcm = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val audio = FloatArray(pcm.remaining()) { pcm.get() / 32768f }
        OfflineSpeechDecoder(LocalVoiceArtifact(model.id, directory)).use { decoder ->
            decoder.warmUp()
            val result = decoder.decode(audio)
            assertTrue("Empty transcript for ${model.id}", result.isNotBlank())
            assertFalse(result.contains('\uFFFD'))
        }
    }

    @Test fun bundledModelsRunSequentialRequestsWithAsrWeights(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("localTranslation") == "true")
        check(context.packageName == "seeyuer.sayin.imagetest")
        val imported = InstrumentationRegistry.getArguments().getString("importTranslation") == "true"
        val catalog = TranslationCatalog.read(context)
        val models = if (imported) catalog.models.filter {
            File(context.getExternalFilesDir(null), "models/${it.fileName}").isFile
        } else catalog.models.filter { it.bundled }
        assertTrue("Bundled translation assets missing", models.isNotEmpty())
        val translator = SerialTranslator()
        val asr = XAsrDecoder(context.assets)
        val results = JSONArray()
        try {
            asr.warmUp() // Generated silence; no microphone, cloud, recording service or LAN.
            for (model in models) {
                val store = TranslationModelStore(context, model)
                val source = File(context.getExternalFilesDir(null), "models/${model.fileName}")
                val bundled = store.bundled
                if (!imported) assertTrue("Bundled translation asset missing: ${model.id}", bundled)
                val preparationStart = android.os.SystemClock.elapsedRealtime()
                if (imported) store.import(source.inputStream()) {}
                val preparationMillis = android.os.SystemClock.elapsedRealtime() - preparationStart
                val sourceText = if (model.id.startsWith("hy-")) "SayIn 不会自动发送译文。😊" else "会议改到2026年11月14日下午3:05。"
                val result = if (!imported) translator.translate(store.openBundled()!!, model, sourceText)
                             else translator.translate(store.file, model, sourceText)
                assertEquals("${model.id} did not complete", TranslationEnd.Complete, result.end)
                assertTrue("${model.id} returned empty text", result.text.isNotBlank())
                assertFalse("${model.id} returned invalid UTF-8", result.text.contains('\uFFFD'))
                if (model.id == "hy-mt2-1.8b-q4") {
                    assertTrue("${model.id} lost the product name", result.text.contains("SayIn"))
                    assertTrue("${model.id} lost the emoji", result.text.contains("😊"))
                }
                results.put(JSONObject().put("id", model.id).put("sha256", model.sha256)
                    .put("bundled", bundled)
                    .put("preparation", when {
                        imported -> "verified local import"
                        bundled -> "bundled uncompressed APK asset"
                        else -> "verified local import"
                    })
                    .put("preparationMs", preparationMillis)
                    .put("firstMs", result.firstMicros / 1000).put("totalMs", result.totalMicros / 1000))
            }
            translator.release()
            val first = results.getJSONObject(0)
            val report = JSONObject().put("model", first.getString("id")).put("modelSha256", first.getString("sha256"))
                .put("models", results)
                .put("device", android.os.Build.MODEL).put("asrWeightsCoResident", true)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("debuggable", context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                .put("bundledCount", if (imported) 0 else models.size)
                .put("procStatus", File("/proc/self/status").readLines().filter { it.startsWith("Vm") }.joinToString("\n"))
                .put("passed", true)
            File(context.getExternalFilesDir(null), "product-native-report.json").writeText(report.toString(2))
        } finally { translator.close(); asr.close() }
    }
}
