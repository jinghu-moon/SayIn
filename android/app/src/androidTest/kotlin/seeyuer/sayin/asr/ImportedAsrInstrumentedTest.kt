package seeyuer.sayin.asr

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.sayin.BuildConfig
import seeyuer.sayin.models.LocalModelCatalog
import seeyuer.sayin.models.LocalModelStorage
import seeyuer.sayin.translation.TranslationCatalog

/** Opt-in benchmark for locally supplied model files on an isolated device build. */
@RunWith(AndroidJUnit4::class)
class ImportedAsrInstrumentedTest {
    @Test fun recognizesMixedSpeechFromModelFolder() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val id = arguments.getString("asrModelId")
        assumeTrue(BuildConfig.LOCAL_MODEL_FIXTURES && id != null)
        val context = instrumentation.targetContext
        val catalog = LocalModelCatalog(context, TranslationCatalog.read(context))
        val model = catalog.recognition.single { it.id == id }
        val storage = LocalModelStorage(context, catalog)
        assertTrue("Model did not pass SayIn import: $id", model.id in storage.installed())
        val directory = storage.directory(model)

        val raw = instrumentation.context.assets.open("x-asr-smoke.pcm").use { it.readBytes() }
        val pcm = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(pcm.remaining()) { pcm.get() / 32768f }
        withContext(Dispatchers.Default) {
            val setupStarted = SystemClock.elapsedRealtime()
            val decoder = LocalVoiceArtifact(model.id, directory).openDecoder()
            decoder.use {
                decoder.warmUp()
                val loadMs = SystemClock.elapsedRealtime() - setupStarted
                val short = arguments.getString("asrShort") == "true"
                val input = when {
                    short -> samples.copyOf(5 * 16_000)
                    arguments.getString("asrLong") == "true" -> samples + samples
                    else -> samples
                }
                val source = TimedPcm(input)
                val updates = mutableListOf<Pair<Long, String>>()
                val transcript = decoder.recognize(source) { text ->
                    if (text.isNotBlank() && text != updates.lastOrNull()?.second) {
                        updates += SystemClock.elapsedRealtime() - source.startMs to text
                    }
                }
                val tailMs = SystemClock.elapsedRealtime() - source.endMs
                val peakRss = File("/proc/self/status").useLines { lines ->
                    lines.first { it.startsWith("VmHWM:") }.substringAfter(':').trim()
                }
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("asr_id", model.id)
                    putString("asr_audio_ms", (input.size / 16).toString())
                    putString("asr_load_ms", loadMs.toString())
                    putString("asr_first_text_ms", updates.firstOrNull()?.first?.toString() ?: "none")
                    putString("asr_updates", updates.joinToString(" | ") { (ms, text) -> "$ms $text" })
                    putString("asr_tail_ms", tailMs.toString())
                    putString("asr_peak_rss", peakRss)
                    putString("asr_transcript", transcript)
                })
                if (model.id == "r2t2") {
                    assertTrue("R2T2 never emitted text while recording", updates.any { it.first < source.endMs - source.startMs })
                    assertTrue("Confirmed text changed", (updates.map { it.second } + transcript)
                        .zipWithNext().all { (before, after) -> after.startsWith(before) })
                    assertTrue("Internal control text escaped", "<asr_text>" !in transcript && '|' !in transcript)
                }
                if (model.id == "qwen-06" || model.id == "qwen-17") {
                    assertTrue("Offline Qwen emitted a partial transcript", updates.isEmpty())
                    assertTrue("Qwen language header escaped: $transcript", "<asr_text>" !in transcript)
                }
                assertTrue("Missing English reference word: $transcript", transcript.contains("Monday", ignoreCase = true))
                if (!short) assertTrue("Missing Chinese reference word: $transcript", transcript.contains("\u661f\u671f\u4e09"))
            }
        }
    }

    private class TimedPcm(private val samples: FloatArray) : PcmSource {
        val startMs = SystemClock.elapsedRealtime()
        @Volatile var endMs = startMs
            private set
        private var offset = 0

        override fun read(): FloatArray? {
            if (offset == samples.size) {
                endMs = SystemClock.elapsedRealtime()
                return null
            }
            val end = minOf(offset + 1600, samples.size)
            val delayMs = startMs + end / 16 - SystemClock.elapsedRealtime()
            if (delayMs > 0) Thread.sleep(delayMs)
            return samples.copyOfRange(offset, end).also { offset = end }
        }

        override fun requestStop() = Unit
        override fun close() = Unit
    }
}
