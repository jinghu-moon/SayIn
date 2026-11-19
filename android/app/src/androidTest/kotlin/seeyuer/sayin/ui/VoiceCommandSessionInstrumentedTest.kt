package seeyuer.sayin.ui

import android.content.ContextWrapper
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection

class VoiceCommandSessionInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline) { "State did not settle" }; Thread.sleep(10) }
    }

    private class Recognition : RecognitionEngine {
        override val state = MutableStateFlow(RecognitionState())
        override val model = MutableStateFlow(LocalModelPhase.Ready)
        var starts = 0
        override fun prepareLocal() {}
        override fun start(cloud: CloudConfiguration?) {
            starts++
            state.value = RecognitionState(session = starts.toLong(), phase = RecognitionPhase.Listening)
        }
        override fun finish() { state.value = state.value.copy(phase = RecognitionPhase.Finishing) }
        override fun cancel() { state.value = RecognitionState() }
        override fun clearResult() {}
        override fun close() {}
    }

    private fun session(recognition: Recognition): SpeakSession {
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("voice-command-test-${UUID.randomUUID()}-$name", mode)
        }
        return SpeakSession(context, recognition = recognition).apply {
            setVisible(Any(), true)
            updateReceiver(ClientState(connection = Connection.ONLINE, paired = true, address = "127.0.0.1",
                target = "target", paused = false, active = true, revision = "1", connectionEpoch = 1))
            selectSource(SpeakInputSource.Local)
            changeVoiceCommands(true)
        }
    }

    private fun finishWith(session: SpeakSession, recognition: Recognition, transcript: String) {
        main { session.start(null) }
        waitFor { recognition.starts == 1 }
        main {
            recognition.state.value = RecognitionState(session = 1, phase = RecognitionPhase.Listening, transcript = transcript)
        }
        waitFor { session.asr.phase == RecognitionPhase.Listening }
        main { session.finishRecording() }
        waitFor { session.asr.phase == RecognitionPhase.Finishing }
        main {
            recognition.state.value = RecognitionState(session = 1, phase = RecognitionPhase.Idle, transcript = transcript, finished = true)
        }
    }

    @Test fun deletePreviousSentenceEditsTheFinalDraft() {
        val recognition = Recognition(); lateinit var session: SpeakSession
        main { session = session(recognition) }
        try {
            finishWith(session, recognition, "第一句。第二句，删除上一句")
            waitFor { session.draft.text == "第一句。" }
            main { assertEquals("第一句。", session.draft.text) }
        } finally { main { session.close() } }
    }

    @Test fun clearCommandEmptiesTheDraft() {
        val recognition = Recognition(); lateinit var session: SpeakSession
        main { session = session(recognition); session.changeDraft(TextFieldValue("旧草稿")) }
        try {
            finishWith(session, recognition, "清空")
            waitFor { session.draft.text.isEmpty() }
            main { assertEquals("", session.draft.text) }
        } finally { main { session.close() } }
    }

    @Test fun sendCommandUsesTheExistingSendTicketOnlyOnceVisibleAndReady() {
        val recognition = Recognition(); lateinit var session: SpeakSession
        var sends = 0
        main {
            session = session(recognition)
            session.onSendReady = { sends++; session.markSubmitted() }
        }
        try {
            finishWith(session, recognition, "你好，发送")
            waitFor { sends == 1 }
            main {
                assertEquals("你好", session.draft.text)
                assertEquals(1, sends)
                assertTrue(session.submitted != null)
            }
        } finally { main { session.close() } }
    }

    @Test fun commandWordsInsideSentencesStayText() {
        val recognition = Recognition(); lateinit var session: SpeakSession
        main { session = session(recognition) }
        try {
            finishWith(session, recognition, "请解释换行符")
            waitFor { session.draft.text == "请解释换行符" }
            main { assertEquals("请解释换行符", session.draft.text) }
        } finally { main { session.close() } }
    }
}
