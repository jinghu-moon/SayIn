package seeyuer.sayin.translationbench

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import seeyuer.sayin.translation.*

/** Timestamp native return before JSON construction, PSS sampling or any report I/O. */
class TranslationCancellationBenchmark {
    @Test fun cancellationStopsBeforeReporting(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = checkNotNull(InstrumentationRegistry.getArguments().getString("modelId"))
        val model = TranslationCatalog.read(context).models.single { it.id == id }
        val file = File(context.getExternalFilesDir(null), "models/${model.fileName}")
        val engine = SerialTranslator()
        val timer = Executors.newSingleThreadScheduledExecutor()
        val report = JSONObject().put("model", id).put("modelSha256", model.sha256)
            .put("device", android.os.Build.MODEL).put("measurement", "cancel request to suspended translate return; before all reporting I/O")
        val source = "我十分钟后到，请先帮我点一杯不加糖的咖啡。"
        try {
            assertEquals(TranslationEnd.Complete, engine.translate(file, model, source).end)
            val cancelAt = AtomicLong()
            val decode = engine.translate(file, model, source, onPartial = {
                if (cancelAt.compareAndSet(0, SystemClock.elapsedRealtimeNanos())) engine.cancel()
            })
            val decodeUs = (SystemClock.elapsedRealtimeNanos() - cancelAt.get()) / 1000
            assertEquals(TranslationEnd.Cancelled, decode.end)
            cancelAt.set(0)
            val prefill = engine.translate(file, model, "这是一个需要完整翻译的测试句子。".repeat(35), onProgress = {
                if (it.stage == TranslationStage.Prefill) timer.schedule({ cancelAt.set(SystemClock.elapsedRealtimeNanos()); engine.cancel() }, 100, TimeUnit.MILLISECONDS)
            })
            val prefillUs = (SystemClock.elapsedRealtimeNanos() - cancelAt.get()) / 1000
            assertTrue(cancelAt.get() > 0); assertEquals(TranslationEnd.Cancelled, prefill.end)
            engine.release(); cancelAt.set(0)
            val load = engine.translate(file, model, source, onProgress = {
                if (it.stage == TranslationStage.Loading && it.fraction > 0.1f && cancelAt.compareAndSet(0, SystemClock.elapsedRealtimeNanos())) engine.cancel()
            })
            val loadUs = (SystemClock.elapsedRealtimeNanos() - cancelAt.get()) / 1000
            assertTrue(cancelAt.get() > 0); assertEquals(TranslationEnd.Cancelled, load.end)
            engine.release()
            assertEquals(TranslationEnd.Complete, engine.translate(file, model, source).end)
            engine.release()
            report.put("decodeUs", decodeUs).put("prefillUs", prefillUs).put("loadUs", loadUs).put("passed", true)
            File(context.getExternalFilesDir(null), "cancellation-$id.json").writeText(report.toString(2))
        } finally { engine.close(); timer.shutdownNow() }
    }
}
