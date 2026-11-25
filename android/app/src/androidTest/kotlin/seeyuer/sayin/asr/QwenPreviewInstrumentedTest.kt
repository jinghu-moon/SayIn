package seeyuer.sayin.asr

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QwenPreviewInstrumentedTest {
    @Test
    fun qwenPublishesTextBeforeCaptureFinishes() = runBlocking { verifyOfflineSegments("qwen-06") }
}

internal suspend fun verifyOfflineSegments(modelId: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val directory = File(context.noBackupFilesDir, "local-models/$modelId")
    assumeTrue("The isolated package needs the $modelId model fixture", directory.isDirectory)
    val raw = instrumentation.context.assets.open("x-asr-smoke.pcm").use { it.readBytes() }
    val pcm = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    // One mixed-language utterance followed by a separately paced silence interval.
    val audio = FloatArray(20 * 16_000)
    for (index in 0 until pcm.remaining()) audio[index] = pcm.get() / 32768f

    OfflineSpeechDecoder(LocalVoiceArtifact(modelId, directory)).use { decoder ->
        decoder.warmUp()
        var firstTextAt = 0L
        var textBeforeEof = ""
        val started = SystemClock.elapsedRealtime()
        val source = object : PcmSource {
            private val vad = VadAutoStop(context, autoFinish = false)
            private var position = 0
            @Volatile var eofAt = 0L
                private set
            override var segmentBoundary = false
                private set

            override suspend fun read(): FloatArray? {
                if (position == audio.size) {
                    eofAt = SystemClock.elapsedRealtime()
                    return null
                }
                val end = minOf(position + 1600, audio.size)
                val wait = started + end / 16 - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
                val samples = audio.copyOfRange(position, end)
                position = end
                segmentBoundary = vad.accept(samples).segmentBoundary
                return samples
            }
            override fun requestStop() = Unit
            override fun close() = vad.close()
        }
        val final = decoder.recognize(source) { text ->
            if (text.isNotBlank() && firstTextAt == 0L) firstTextAt = SystemClock.elapsedRealtime()
            if (source.eofAt == 0L && text.isNotBlank()) textBeforeEof = text
        }
        val tailMs = SystemClock.elapsedRealtime() - source.eofAt
        val referenceStarted = SystemClock.elapsedRealtime()
        val reference = decoder.decode(audio)
        val referenceMs = SystemClock.elapsedRealtime() - referenceStarted
        instrumentation.sendStatus(2, Bundle().apply {
            putString("segment_model", modelId)
            putLong("capture_ms", source.eofAt - started)
            putLong("first_text_ms", if (firstTextAt == 0L) -1 else firstTextAt - started)
            putLong("tail_ms", tailMs)
            putLong("whole_decode_ms", referenceMs)
            putString("reference", reference)
            putString("transcript", final)
        })
        assertTrue("Expected nonblank text before EOF", textBeforeEof.isNotBlank() && firstTextAt < source.eofAt)
        assertTrue("Missing mixed-language reference words: $final", final.contains("Monday", ignoreCase = true) && final.contains("星期三"))
    }
}
