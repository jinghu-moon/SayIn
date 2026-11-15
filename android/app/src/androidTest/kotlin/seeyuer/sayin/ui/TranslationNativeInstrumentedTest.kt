package seeyuer.sayin.ui

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import seeyuer.sayin.asr.XAsrDecoder
import seeyuer.sayin.translation.*

/** Explicit opt-in and isolated verification package only. No microphone or LAN use. */
class TranslationNativeInstrumentedTest {
    @Test fun importedModelRunsSequentialRequestsWithAsrWeights(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("localTranslation") == "true")
        check(context.packageName == "seeyuer.sayin.translationverification")
        val model = TranslationCatalog.read(context).defaultModel
        val source = File(context.getExternalFilesDir(null), "models/${model.fileName}")
        val store = TranslationModelStore(context, model)
        val translator = SerialTranslator()
        val asr = XAsrDecoder(context.assets)
        try {
            val download = InstrumentationRegistry.getArguments().getString("downloadTranslation") == "true"
            val preparationStart = android.os.SystemClock.elapsedRealtime()
            if (download) store.download {} else store.import(source.inputStream()) {}
            val preparationMillis = android.os.SystemClock.elapsedRealtime() - preparationStart
            asr.warmUp() // Generated silence; no microphone, cloud, recording service or LAN.
            val first = translator.translate(store.file, model, "SayIn 不会自动发送译文。😊")
            assertEquals(TranslationEnd.Complete, first.end)
            assertTrue(first.text.contains("SayIn")); assertTrue(first.text.contains("😊"))
            assertFalse(first.text.contains('\uFFFD'))
            val second = translator.translate(store.file, model, "会议改到2026年11月14日下午3:05。")
            assertEquals(TranslationEnd.Complete, second.end)
            assertFalse(second.text.contains("😊"))
            translator.release()
            val report = JSONObject().put("model", model.id).put("modelSha256", model.sha256)
                .put("device", android.os.Build.MODEL).put("asrWeightsCoResident", true)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("debuggable", context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                .put("preparation", if (download) "fixed-revision HTTPS download" else "verified local import")
                .put("preparationMs", preparationMillis)
                .put("firstMs", first.firstMicros / 1000).put("totalMs", first.totalMicros / 1000)
                .put("procStatus", File("/proc/self/status").readLines().filter { it.startsWith("Vm") }.joinToString("\n"))
                .put("passed", true)
            File(context.getExternalFilesDir(null), "product-native-report.json").writeText(report.toString(2))
        } finally { translator.close(); asr.close() }
    }
}
