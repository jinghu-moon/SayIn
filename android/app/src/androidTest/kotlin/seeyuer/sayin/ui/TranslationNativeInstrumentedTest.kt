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
import seeyuer.sayin.translation.*

/** Explicit opt-in and isolated verification package only. No microphone or LAN use. */
class TranslationNativeInstrumentedTest {
    @Test fun bundledModelsRunSequentialRequestsWithAsrWeights(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("localTranslation") == "true")
        check(context.packageName == "seeyuer.sayin.imagetest")
        val download = InstrumentationRegistry.getArguments().getString("downloadTranslation") == "true"
        val catalog = TranslationCatalog.read(context)
        val models = if (download) listOf(catalog.defaultModel) else catalog.models.filter { it.bundled }
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
                if (!download) assertTrue("Bundled translation asset missing: ${model.id}", bundled)
                val preparationStart = android.os.SystemClock.elapsedRealtime()
                if (download) store.download {}
                else if (!bundled) store.import(source.inputStream()) {}
                val preparationMillis = android.os.SystemClock.elapsedRealtime() - preparationStart
                // The real product path reads the uncompressed APK asset directly; do not pre-import a copy.
                val sourceText = if (model.id.startsWith("hy-")) "SayIn 不会自动发送译文。😊" else "会议改到2026年11月14日下午3:05。"
                val result = if (bundled && !download) translator.translate(store.openBundled()!!, model, sourceText)
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
                        download -> "fixed-revision HTTPS download"
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
                .put("bundledCount", if (download) 0 else models.size)
                .put("procStatus", File("/proc/self/status").readLines().filter { it.startsWith("Vm") }.joinToString("\n"))
                .put("passed", true)
            File(context.getExternalFilesDir(null), "product-native-report.json").writeText(report.toString(2))
        } finally { translator.close(); asr.close() }
    }
}
