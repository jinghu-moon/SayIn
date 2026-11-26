package seeyuer.sayin.asr

import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.sayin.BuildConfig
import seeyuer.sayin.models.LocalModelCatalog
import seeyuer.sayin.models.LocalModelStorage
import seeyuer.sayin.translation.R2t2Native
import seeyuer.sayin.translation.TranslationCatalog

/** Opt-in benchmark for locally supplied model files on an isolated device build. */
@RunWith(AndroidJUnit4::class)
class ImportedAsrInstrumentedTest {
    @Test fun r2t2ReusesIdenticalAudioAndInvalidatesChangedAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(BuildConfig.LOCAL_MODEL_FIXTURES &&
            InstrumentationRegistry.getArguments().getString("asrModelId") == "r2t2")
        val context = instrumentation.targetContext
        val catalog = LocalModelCatalog(context, TranslationCatalog.read(context))
        val model = catalog.recognition.single { it.id == "r2t2" }
        val directory = LocalModelStorage(context, catalog).directory(model)
        val raw = instrumentation.context.assets.open("x-asr-smoke.pcm").use { it.readBytes() }
        val pcm = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val audio = FloatArray(32_000) { pcm.get() / 32768f }
        val changed = audio.map { it * 0.2f }.toFloatArray()
        val timings = LongArray(8)
        R2t2Native(
            directory.resolve("Confucius4-R2T2-Q4_K_M.gguf"),
            directory.resolve("mmproj-Confucius4-R2T2-Q8_0.gguf"),
        ).use { decoder ->
            fun decode(samples: FloatArray, final: Boolean) =
                checkNotNull(decoder.decode(samples, "", final, 0, 256, null, timings))

            decode(audio, false)
            val reused = decode(audio, true)
            assertEquals("Identical features should reuse the audio window", 1L, timings[6])
            assertTrue("Identical features should retain audio KV positions", timings[7] > 9)
            val cold = decode(audio, true)
            assertTrue(cold.decodeToString().contains("<asr_text>"))
            assertArrayEquals(cold, reused)

            decode(audio, false)
            val invalidated = decode(changed, true)
            assertEquals("A changed waveform must re-encode its changed Mel", 0L, timings[6])
            assertArrayEquals(decode(changed, true), invalidated)
        }
    }

    @Test fun recognizesMixedSpeechFromModelFolder() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val id = arguments.getString("asrModelId")
        assumeTrue(BuildConfig.LOCAL_MODEL_FIXTURES && id != null)
        val context = instrumentation.targetContext
        val catalog = LocalModelCatalog(context, TranslationCatalog.read(context))
        val model = if (id == "x-asr-bundled") null else catalog.recognition.single { it.id == id }
        val storage = LocalModelStorage(context, catalog)
        val directory = model?.let {
            assertTrue("Model did not pass SayIn import: $id", it.id in storage.installed())
            storage.directory(it)
        }

        val raw = instrumentation.context.assets.open("x-asr-smoke.pcm").use { it.readBytes() }
        val pcm = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(pcm.remaining()) { pcm.get() / 32768f }
        withContext(Dispatchers.Default) {
            val setupStarted = SystemClock.elapsedRealtime()
            val rounds = mutableListOf<Pair<Long, R2t2Timing>>()
            val prefillThreads = arguments.getString("r2t2PrefillThreads")?.toInt() ?: DEFAULT_PREFILL_THREADS
            val gpuDecoder = arguments.getString("r2t2GpuDecoder") == "true"
            val gpuProjector = arguments.getString("r2t2GpuProjector") == "true"
            if (gpuDecoder) {
                val options = mapOf(
                    "no-fa" to listOf("SAYIN_R2T2_DISABLE_FLASH_ATTENTION"),
                    "no-fusion" to listOf("GGML_VK_DISABLE_FUSION"),
                    "no-f16" to listOf("GGML_VK_DISABLE_F16"),
                    "no-coopmat" to listOf("GGML_VK_DISABLE_COOPMAT", "GGML_VK_DISABLE_COOPMAT2"),
                    "no-dot" to listOf("GGML_VK_DISABLE_INTEGER_DOT_PRODUCT"),
                )
                arguments.getString("r2t2VulkanDisable")?.split(',')?.forEach { option ->
                    options.getValue(option).forEach { Os.setenv(it, "1", true) }
                }
            }
            val decoder = when (id) {
                "r2t2" -> {
                    val firstPreviewMs = arguments.getString("r2t2FirstPreviewMs")?.toInt() ?: 1_200
                    val windowMs = arguments.getString("r2t2WindowMs")?.toInt() ?: 16_000
                    val stepMs = arguments.getString("r2t2PreviewStepMs")?.toInt() ?: 2_000
                    val previewMode = arguments.getString("r2t2PreviewMode") ?: "paced"
                    val encodeThreads = arguments.getString("r2t2EncodeThreads")?.toInt() ?: DEFAULT_ENCODE_THREADS
                    val genThreads = arguments.getString("r2t2GenThreads")?.toInt() ?: DEFAULT_GEN_THREADS
                    require(firstPreviewMs in 100..2_000 && firstPreviewMs % 100 == 0)
                    require(windowMs in 4_000..16_000 && windowMs % 1_000 == 0)
                    require(stepMs in 500..8_000)
                    R2t2Decoder(
                        checkNotNull(directory), prefillThreads, gpuDecoder, gpuProjector,
                        encodeThreads = encodeThreads, genThreads = genThreads,
                        firstPreviewSamples = firstPreviewMs * 16,
                        decodeWindowSamples = windowMs * 16,
                        previewStepSamples = stepMs * 16,
                        previewsEnabled = previewMode != "off",
                        pacedPreview = previewMode == "paced",
                    ) { rounds += SystemClock.elapsedRealtime() to it }
                }
                "x-asr-bundled" -> XAsrDecoder(context.assets)
                else -> LocalVoiceArtifact(checkNotNull(id), checkNotNull(directory)).openDecoder()
            }
            decoder.use {
                decoder.warmUp()
                val loadMs = SystemClock.elapsedRealtime() - setupStarted
                val corpus = arguments.getString("asrCorpus")
                if (corpus != null) {
                    replayCorpus(context, decoder, corpus.split(','), loadMs, arguments.getString("asrContinuous") == "true")
                    return@withContext
                }
                val short = arguments.getString("asrShort") == "true"
                val input = when {
                    short -> samples.copyOf(5 * 16_000)
                    arguments.getString("asrLong") == "true" -> samples + samples
                    else -> samples
                }
                if (arguments.getString("asrQuietOnset") == "true") {
                    for (i in 0 until minOf(input.size, 19_200)) input[i] *= 0.2f
                }
                val useVad = arguments.getString("asrVad") == "true"
                val vad = if (useVad) VadAutoStop(context, autoFinish = false) else null
                val source = TimedPcm(input, vad)
                val updates = mutableListOf<Pair<Long, String>>()
                val transcript = decoder.recognize(source) { text ->
                    if (text.isNotBlank() && text != updates.lastOrNull()?.second) {
                        updates += SystemClock.elapsedRealtime() - source.startMs to text
                    }
                }
                val tailMs = SystemClock.elapsedRealtime() - source.endMs
                rounds.forEach { (at, round) ->
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("r2t2_round", "at_ms=${at - source.startMs} final=${round.final} " +
                            "audio_ms=${round.samples / 16} queued_ms=${round.queuedSamples / 16} " +
                            "budget=${round.tokenBudget} tokens=${round.sampledTokens} interrupted=${round.interrupted} " +
                            "elapsed_us=${round.elapsedUs} features_us=${round.featuresUs} encode_us=${round.encodeUs} " +
                            "audio_prefill_us=${round.audioPrefillUs} text_prefill_us=${round.textPrefillUs} generate_us=${round.generationUs} " +
                            "reused_audio=${round.reusedAudioChunks} reused_positions=${round.reusedPositions}")
                    })
                }
                val peakRss = File("/proc/self/status").useLines { lines ->
                    lines.first { it.startsWith("VmHWM:") }.substringAfter(':').trim()
                }
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("asr_id", id)
                    putString("asr_audio_ms", (input.size / 16).toString())
                    putString("asr_load_ms", loadMs.toString())
                    putString("asr_vad", useVad.toString())
                    putString("asr_first_text_ms", updates.firstOrNull()?.first?.toString() ?: "none")
                    putString("asr_updates", updates.joinToString(" | ") { (ms, text) -> "$ms $text" })
                    putString("asr_tail_ms", tailMs.toString())
                    putString("asr_peak_rss", peakRss)
                    putString("asr_transcript", transcript)
                })
                if (id == "r2t2") {
                    if (arguments.getString("r2t2PreviewMode") != "off") {
                        val whileListening = updates.count { it.first < source.endMs - source.startMs }
                        assertTrue("R2T2 never emitted text while recording", whileListening > 0)
                        if (arguments.getString("r2t2CheckCadence") == "true") {
                            assertTrue("Expected repeated text updates while recording, got $whileListening", whileListening >= 2)
                        }
                    }
                    assertTrue("Confirmed text changed", (updates.map { it.second } + transcript)
                        .zipWithNext().all { (before, after) -> after.startsWith(before) })
                    assertTrue("Internal control text escaped", "<asr_text>" !in transcript && '|' !in transcript)
                    assertTrue("Every round must respect the sampling budget", rounds.isNotEmpty() &&
                        rounds.all { (_, round) -> round.sampledTokens <= round.tokenBudget })
                    if (arguments.getString("asrLong") == "true") {
                        assertTrue("Missing repeated English phrase", Regex("(?i)Monday").findAll(transcript).count() == 2)
                        assertTrue("Missing repeated Chinese phrase", Regex("\u661f\u671f\u4e09").findAll(transcript).count() == 2)
                    }
                }
                if (id == "qwen-06" || id == "qwen-17") {
                    assertTrue("Qwen language header escaped: $transcript", "<asr_text>" !in transcript)
                }
                assertTrue("Missing English reference word: $transcript", transcript.contains("Monday", ignoreCase = true))
                if (!short) assertTrue("Missing Chinese reference word: $transcript", transcript.contains("\u661f\u671f\u4e09"))
            }
        }
    }

    private suspend fun replayCorpus(
        context: Context,
        decoder: LocalSpeechDecoder,
        fixtures: List<String>,
        loadMs: Long,
        continuous: Boolean,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val polish = FinalTextPolish.load(context.assets)
        PunctuationRestorer(context).use { ct ->
            ct.prepare()
            for (id in fixtures) {
                require(id.matches(Regex("[A-Z][0-9]?-[A-E][0-9]{2}")))
                val pcm = File(context.filesDir, "voice-corpus/$id.pcm").readBytes()
                val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val manual = id.endsWith("-E10")
                val tailSeconds = when {
                    manual -> 0
                    continuous -> 5
                    else -> 3
                }
                val audio = FloatArray(samples.remaining() + tailSeconds * 16_000) {
                    if (samples.hasRemaining()) samples.get() / 32768f else 0f
                }
                for (v3 in listOf(false, true)) {
                    val source = TimedPcm(audio, VadAutoStop(context, autoFinish = !manual && !continuous,
                        localSilenceMillis = LocalSilence.DefaultMillis.takeIf { v3 }))
                    val updates = mutableListOf<Pair<Long, String>>()
                    val raw = decoder.recognize(source) {
                        if (it.isNotBlank() && it != updates.lastOrNull()?.second) {
                            updates += SystemClock.elapsedRealtime() - source.startMs to it
                        }
                    }
                    val firstTextMs = updates.firstOrNull()?.first ?: -1L
                    val itn = if (v3) polish.apply(raw) else raw
                    val final = when {
                        v3 && source.noSpeech -> ""
                        v3 || decoder is XAsrDecoder -> ct.restore(itn)
                        else -> raw
                    }
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("fixture", "$id ${if (v3) "v3" else "legacy-policy"}")
                        putLong("model_load_ms", loadMs)
                        putLong("first_text_ms", firstTextMs)
                        putString("text_while_listening", (firstTextMs in 0 until (source.endMs - source.startMs)).toString())
                        putString("update_ms", updates.joinToString(",") { it.first.toString() })
                        putInt("captured_ms", source.capturedMs)
                        putLong("final_after_stop_ms", SystemClock.elapsedRealtime() - source.endMs)
                        putString("end", if (source.noSpeech) "no-speech" else if (source.autoEnded) "silence" else "manual/fixture-end")
                        putString("raw", raw)
                        putString("itn", itn)
                        putString("final", final)
                    })
                    if (v3 && !continuous && id.endsWith("-E09")) {
                        assertTrue("Noise-only fixture produced output", source.noSpeech && final.isEmpty())
                    }
                    if (v3 && id.endsWith("-E05")) assertTrue("Stopped inside the utterance", source.capturedMs >= 5_080)
                    if (v3 && continuous && id == "Q-E02") {
                        assertTrue("No short-phrase text while listening", firstTextMs in 0 until (source.endMs - source.startMs))
                        assertTrue("Short phrase was lost: $final", final.contains("发送"))
                    }
                }
            }
        }
    }

    private class TimedPcm(private val samples: FloatArray, private val vad: VadAutoStop?) : PcmSource {
        val startMs = SystemClock.elapsedRealtime()
        @Volatile var endMs = startMs
            private set
        private var offset = 0
        val capturedMs get() = offset / 16
        override var noSpeech = false
            private set
        var autoEnded = false
            private set
        override var segmentBoundary = false
            private set
        override var speechInBlock: Boolean? = null
            private set
        override val retainedSampleEnd get() = vad?.retainedSampleEnd

        override suspend fun read(): FloatArray? {
            if (offset == samples.size || autoEnded || noSpeech) {
                endMs = SystemClock.elapsedRealtime()
                return null
            }
            val end = minOf(offset + 1600, samples.size)
            val delayMs = startMs + end / 16 - SystemClock.elapsedRealtime()
            if (delayMs > 0) Thread.sleep(delayMs)
            return samples.copyOfRange(offset, end).also {
                offset = end
                val progress = vad?.accept(it)
                segmentBoundary = progress?.segmentBoundary == true
                speechInBlock = progress?.speech
                noSpeech = progress?.noSpeech == true
                autoEnded = progress?.ended == true
            }
        }

        override fun requestStop() = Unit
        override fun close() { vad?.close() }
    }
}

