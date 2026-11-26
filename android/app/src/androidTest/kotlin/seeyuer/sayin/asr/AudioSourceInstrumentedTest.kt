package seeyuer.sayin.asr

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioSourceInstrumentedTest {
    @Test
    fun coldCaptureCanStopBeforeModelPreparationCompletes() = withForegroundMicrophone {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val unblock = CompletableDeferred<Unit>()
            val engine = withContext(Dispatchers.Main) {
                SpeechRecognition(context).apply { beforeLocalPrepare = { unblock.await() } }
            }
            try {
                withContext(Dispatchers.Main) { engine.start(autoFinish = false) }
                withTimeout(5_000) { engine.state.first { it.phase == RecognitionPhase.Listening } }
                assertTrue(engine.model.value == LocalModelPhase.Loading)
                delay(180)
                withContext(Dispatchers.Main) { engine.finish() }
                assertTrue(engine.state.value.phase == RecognitionPhase.Finishing)
                unblock.complete(Unit)
                val result = withTimeout(15_000) { engine.state.first { !it.busy } }
                assertTrue("Short recording was not drained: $result", result.end == RecordingEnd.Manual && result.finished)
            } finally {
                withContext(Dispatchers.Main) { engine.close() }
            }
        }
    }

    @Test
    fun configuredAudioSourcesCanBeOpenedAndRead() = withForegroundMicrophone {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            runBlocking { AndroidMicrophone(context, audioSource = source).use { microphone ->
                assertTrue("Audio source $source returned no PCM", microphone.read()?.isNotEmpty() == true)
            } }
        }
    }

    @Test
    fun realMicrophoneCanStopWithoutWaitingForPartial() = withForegroundMicrophone {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val engine = withContext(Dispatchers.Main) { SpeechRecognition(context) }
            try {
                withContext(Dispatchers.Main) { engine.prepareLocal() }
                val model = withTimeout(30_000) { engine.model.first { it == LocalModelPhase.Ready || it == LocalModelPhase.Failed } }
                assertTrue("Bundled X-ASR failed to prepare", model == LocalModelPhase.Ready)
                withContext(Dispatchers.Main) { engine.start(autoFinish = false) }
                withTimeout(5_000) { engine.state.first { it.phase == RecognitionPhase.Listening } }
                withContext(Dispatchers.Main) { engine.finish() }
                val result = withTimeout(10_000) { engine.state.first { !it.busy } }
                assertTrue("Manual stop failed: $result", result.end == RecordingEnd.Manual && result.finished)
            } finally {
                withContext(Dispatchers.Main) { engine.close() }
            }
        }
    }

    private fun withForegroundMicrophone(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n $packageName/androidx.activity.ComponentActivity"
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val context = instrumentation.targetContext
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var focused: Boolean
            var granted: Boolean
            do {
                focused = false
                scenario.onActivity { focused = it.hasWindowFocus() }
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (focused && granted) break
                SystemClock.sleep(20)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertTrue("Microphone fixture not ready: focused=$focused, granted=$granted", focused && granted)
            block()
        }
    }
}
