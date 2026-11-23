package seeyuer.sayin.asr

import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioSourceInstrumentedTest {
    @Test
    fun configuredAudioSourcesCanBeOpenedAndRead() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
        var opened = 0
        for (source in sources) {
            val read = runCatching {
                AndroidMicrophone(context, audioSource = source) { _, _, _ -> }.use { microphone ->
                    microphone.read() != null
                }
            }.getOrDefault(false)
            if (read) opened++
        }
        assertTrue("至少应有一个音频源可初始化并读取", opened > 0)
    }
}
