package seeyuer.sayin.translationbench

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import seeyuer.sayin.translation.*

/** Opt-in, synthetic file inference only. Its APK has no network/microphone permission. */
class TranslationBenchmark {
    @Test fun compareAndExerciseLifecycle(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val id = arguments.getString("modelId") ?: error("Pass -e modelId")
        val model = TranslationCatalog.read(context).models.single { it.id == id }
        val file = File(context.getExternalFilesDir(null), "models/${model.fileName}")
        val engine = SerialTranslator()
        val report = JSONObject().put("model", id).put("modelSha256", model.sha256).put("template", model.templateRevision)
            .put("manufacturer", Build.MANUFACTURER).put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT).put("threads", 4).put("nCtx", 4096).put("sourceLimit", 1024).put("outputLimit", 2048)
            .put("pageCache", "not dropped; model pushed immediately before run; not storage-cold")
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        report.put("physicalTotalBytes", memory.totalMem)
        val rows = JSONArray(); report.put("samples", rows)
        val timer = Executors.newSingleThreadScheduledExecutor()
        fun save() {
            File(context.filesDir, "translation-$id.json").writeText(report.toString(2))
            File(context.getExternalFilesDir(null), "translation-$id.json").writeText(report.toString(2))
        }
        fun record(label: String, input: String, result: TranslationResult): JSONObject {
            val row = JSONObject().put("id", label).put("source", input).put("translation", result.text).put("end", result.end.name)
                .put("sourceTokens", result.sourceTokens).put("promptTokens", result.promptTokens).put("generatedTokens", result.generatedTokens)
                .put("verifyMs", result.verifyMillis).put("loadMs", result.loadMillis).put("prefillUs", result.prefillMicros)
                .put("firstUs", result.firstMicros).put("totalUs", result.totalMicros).put("pssKb", Debug.getPss())
                .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            rows.put(row); save(); return row
        }
        try {
            val externalCorpus = arguments.getString("externalCorpus") == "true"
            val cases = if (externalCorpus) {
                JSONArray(File(context.getExternalFilesDir(null), "translation-cases.json").readText())
            } else {
                instrumentation.context.assets.open("translation-cases.json").bufferedReader().use { JSONArray(it.readText()) }
            }
            require(cases.length() > 0)
            val incompleteCases = mutableListOf<String>()
            for (i in 0 until cases.length()) {
                val sample = cases.getJSONObject(i)
                val label = sample.getString("id")
                val input = sample.getString("text")
                val result = engine.translate(file, model, input)
                record(label, input, result)
                if (result.end != TranslationEnd.Complete || result.text.isBlank() || result.text.contains('\uFFFD')) {
                    incompleteCases += "$label: ${result.end}"
                }
            }
            assertTrue("Incomplete or invalid translations: $incompleteCases", incompleteCases.isEmpty())
            if (externalCorpus) {
                report.put("passed", true)
                return@runBlocking
            }
            // Repeated identical requests must start from an empty KV/context; greedy outputs must agree.
            val input = cases.getJSONObject(0).getString("text")
            repeat(4) {
                val result = engine.translate(file, model, input)
                record("warm-$it", input, result)
                assertEquals(rows.getJSONObject(0).getString("translation"), result.text)
                assertEquals(TranslationEnd.Complete, result.end)
            }
            val truncated = engine.translate(file, model, input, outputLimit = 1)
            record("output-limit", input, truncated)
            assertEquals(TranslationEnd.Truncated, truncated.end)
            val tooLong = engine.translate(file, model, "这是长度边界测试。".repeat(700))
            record("input-limit", "synthetic repeated input (omitted)", tooLong)
            assertEquals(TranslationEnd.InputTooLong, tooLong.end)

            var cancelAt = 0L
            val cancelled = engine.translate(file, model, input, onPartial = {
                if (cancelAt == 0L) { cancelAt = SystemClock.elapsedRealtimeNanos(); engine.cancel() }
            })
            val decodeCancelUs = (SystemClock.elapsedRealtimeNanos() - cancelAt) / 1000
            record("cancel-decode", input, cancelled).put("cancelUs", decodeCancelUs)
            assertEquals(TranslationEnd.Cancelled, cancelled.end)
            cancelAt = 0L
            val cancelledPrefill = engine.translate(file, model, "这是一个需要完整翻译的测试句子。".repeat(35), onProgress = {
                if (it.stage == TranslationStage.Prefill) timer.schedule({ cancelAt = SystemClock.elapsedRealtimeNanos(); engine.cancel() }, 100, TimeUnit.MILLISECONDS)
            })
            val prefillCancelUs = (SystemClock.elapsedRealtimeNanos() - cancelAt) / 1000
            record("cancel-prefill", "synthetic repeated input (omitted)", cancelledPrefill).put("cancelUs", prefillCancelUs)
            assertEquals(TranslationEnd.Cancelled, cancelledPrefill.end)
            val timed = engine.translate(file, model, input, timeoutMillis = 1)
            record("deadline", input, timed)
            assertEquals(TranslationEnd.Timeout, timed.end)
            val recovered = engine.translate(file, model, input)
            record("after-cancel", input, recovered)
            assertEquals(TranslationEnd.Complete, recovered.end)
            assertEquals(rows.getJSONObject(0).getString("translation"), recovered.text)
            val releaseStart = SystemClock.elapsedRealtimeNanos()
            engine.release()
            report.put("releaseUs", (SystemClock.elapsedRealtimeNanos() - releaseStart) / 1000)
            val missing = engine.translate(File(file.parentFile, "absent.gguf"), model, input)
            assertEquals(TranslationEnd.ModelMissing, missing.end)
            val bad = File(context.filesDir, "bad.gguf").apply { writeText("GGUF bad") }
            try { assertEquals(TranslationEnd.Integrity, engine.translate(bad, model, input).end) } finally { bad.delete() }
            var loadingCancelled = false
            val interrupted = engine.translate(file, model, input, onProgress = {
                if (!loadingCancelled && it.stage == TranslationStage.Loading && it.fraction > 0.1f) {
                    loadingCancelled = true; cancelAt = SystemClock.elapsedRealtimeNanos(); engine.cancel()
                }
            })
            val loadCancelUs = (SystemClock.elapsedRealtimeNanos() - cancelAt) / 1000
            record("cancel-load", input, interrupted).put("cancelUs", loadCancelUs)
            assertTrue(loadingCancelled)
            assertEquals(TranslationEnd.Cancelled, interrupted.end)
            engine.release()
            report.put("passed", true)
        } finally {
            engine.close(); timer.shutdownNow()
            report.put("procStatus", File("/proc/self/status").readLines().filter { it.startsWith("Vm") }.joinToString("\n"))
            save()
        }
    }
}
