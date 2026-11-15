package seeyuer.sayin.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.*
import seeyuer.sayin.designsystem.VoiceLinkTheme
import java.io.File
import java.io.FileInputStream
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap

class UnifiedSpeakInstrumentedTest {
    @get:Rule(order = 0) val foreground = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -W -n ${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/androidx.activity.ComponentActivity"
            ).use { d -> FileInputStream(d.fileDescriptor).use { it.readBytes() } }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var session: SpeakSession
    private lateinit var engine: FakeRecognition
    private var quick by mutableStateOf(false)
    private var receiver by mutableStateOf(ready())
    private var sends = 0

    private fun mount(initial: TextFieldValue = TextFieldValue()) {
        compose.setContent {
            val context = remember { object : ContextWrapper(compose.activity) {
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("unified-test-$name", mode)
            }.also { it.getSharedPreferences("presentation", 0).edit().clear().commit() } }
            val retained = remember {
                engine = FakeRecognition()
                SpeakSession(context, initial, recognition = engine).also { it.selectSource(SpeakInputSource.Local); session = it; it.onAutoSend = { sends++ } }
            }
            DisposableEffect(retained) { onDispose { retained.close() } }
            CompositionLocalProvider(LocalContext provides context) {
                VoiceLinkTheme {
                    Box(Modifier.size(360.dp, 680.dp)) {
                        VoiceLinkContent(receiver, retained, { sends++ }, { _, _ -> }, {}, quick = quick)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun listen(text: String) {
        compose.runOnIdle { session.start(null) }; compose.waitForIdle()
        compose.runOnIdle { engine.emit(RecognitionPhase.Listening, text) }; compose.waitForIdle()
    }
    private fun finish(text: String) {
        compose.runOnIdle { session.finishRecording() }; compose.waitForIdle()
        compose.runOnIdle { engine.emit(RecognitionPhase.Idle, text, finished = true) }; compose.waitForIdle()
    }

    @Test fun sharedPresentationKeepsRecordingAndAbortRestoresOriginalSelection() {
        val original = TextFieldValue("keep selected words", TextRange(13, 5))
        mount(original); listen("替换内容")
        compose.runOnIdle { quick = true }; compose.waitForIdle()
        compose.onNodeWithTag("draft").assertTextContains("替换内容", substring = true)
        compose.runOnIdle { assertEquals(1, engine.starts); assertTrue(session.asr.busy) }
        screenshot("quick-recording")
        compose.runOnIdle { quick = false }; compose.waitForIdle()
        compose.runOnIdle { session.cancelRecording(); assertEquals(original, session.draft); assertEquals(0, sends) }
        compose.onNodeWithTag("draft").assertTextEquals(original.text)
    }

    @Test fun clearUndoPreservesDraftAndSelection() {
        val original = TextFieldValue("do not lose this draft", TextRange(3, 8))
        mount(original)
        compose.onNodeWithTag("speak-more").performClick()
        compose.onNodeWithText(compose.activity.getString(seeyuer.sayin.R.string.speak_clear_draft)).performClick()
        compose.onNodeWithTag("draft").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.runOnIdle { assertEquals("", session.draft.text) }
        compose.onNodeWithTag("undo-clear").performClick()
        compose.runOnIdle { assertEquals(original, session.draft); assertEquals(0, sends) }
    }

    @Test fun finalOnlyArmsCountdownForLongEnglishAndEntrySwitchCancels() {
        mount()
        compose.runOnIdle { session.changeSendMode(SpeakSendMode.Automatic) }
        val text = "Please preserve this long English technical draft instead of guessing whether it should be sent."
        listen(text)
        compose.runOnIdle { assertNull(session.interaction.countdown); assertEquals(0, sends) }
        finish(text)
        compose.runOnIdle { assertNotNull(session.interaction.countdown) }
        compose.runOnIdle { quick = true }; compose.waitForIdle()
        compose.runOnIdle { assertNull(session.interaction.countdown); assertEquals(text, session.draft.text); assertEquals(0, sends) }
    }

    @Test fun endingFromBackgroundDoesNotArmWhenUiReturnsBeforeFinal() {
        mount(); compose.runOnIdle { session.changeSendMode(SpeakSendMode.Automatic) }
        listen("background draft")
        // Going to another tab removes the only speaking surface without cancelling the engine.
        compose.onNodeWithTag("nav-History").performClick()
        compose.runOnIdle { session.finishRecording() }
        compose.onNodeWithTag("nav-Speak").performClick()
        compose.runOnIdle { engine.emit(RecognitionPhase.Idle, "background draft", finished = true) }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(session.interaction.countdown); assertEquals("background draft", session.draft.text); assertEquals(0, sends) }
    }

    @Test fun cancelledPreparationCannotStartOnReturn() {
        mount(TextFieldValue("existing draft"))
        compose.runOnIdle { engine.model.value = LocalModelPhase.Unloaded }; compose.waitForIdle()
        compose.runOnIdle { session.start(null); assertTrue(session.preparingToRecord) }
        compose.onNodeWithTag("nav-History").performClick()
        compose.runOnIdle { engine.model.value = LocalModelPhase.Ready }; compose.waitForIdle()
        compose.onNodeWithTag("nav-Speak").performClick()
        compose.runOnIdle { assertEquals(0, engine.starts); assertEquals("existing draft", session.draft.text); assertEquals(0, sends) }
    }

    @Test fun uncertainNeverRetriesAndNewDraftRequiresDesktopReview() {
        mount(TextFieldValue("uncertain original"))
        var id = ""
        compose.runOnIdle { assertTrue(session.markSubmitted()); id = session.draftId }
        val item = OutboxItem(id, "host", "device", "target", null, 0, "uncertain", "1", null, System.currentTimeMillis())
        compose.runOnIdle { receiver = ready().copy(paused = true, pauseReason = "outcome_review_required", history = listOf(item)) }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(session.markSubmitted()); session.newDraft(); assertEquals("uncertain original", session.draft.text) }
        compose.runOnIdle {
            val isolated = object : ContextWrapper(compose.activity) {
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("restore-test-$name", mode)
            }
            val restoredEngine = FakeRecognition()
            val restored = SpeakSession(isolated, recognition = restoredEngine)
            try {
                restored.restore(session.savedState)
                restored.setVisible(Any(), true)
                restored.updateReceiver(receiver)
                assertEquals(id, restored.draftId)
                assertEquals(id, restored.submitted?.id)
                assertFalse(restored.markSubmitted())
                assertNull(restored.interaction.countdown)
                assertEquals(0, restoredEngine.starts)
                // An authoritative final receipt may clear a restored draft even
                // when its saved selection differs from the request snapshot.
                restored.updateReceiver(ready().copy(history = listOf(item.copy(state = "injected"))))
                assertEquals("", restored.draft.text)
            } finally { restored.close() }
        }
        compose.runOnIdle { receiver = ready().copy(history = listOf(item)) }; compose.waitForIdle()
        compose.runOnIdle { session.newDraft(); assertEquals("", session.draft.text); assertEquals("uncertain", receiver.history.single().state); assertEquals(0, sends) }
    }

    @Test fun definiteLocalRejectionAllowsExplicitNewIdentity() {
        mount(TextFieldValue("not accepted"))
        var first = ""
        compose.runOnIdle { assertTrue(session.markSubmitted()); first = session.draftId; receiver = ready().copy(rejectedDraft = first) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("not accepted", session.draft.text)
            assertTrue(session.markSubmitted()); assertNotEquals(first, session.draftId); assertEquals(0, sends)
        }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "ui-verification").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    companion object {
        private fun ready() = ClientState(connection = Connection.ONLINE, paired = true, address = "127.0.0.1", active = true, paused = false, target = "target", revision = "1")
    }
}

private class FakeRecognition : RecognitionEngine {
    override val state = MutableStateFlow(RecognitionState())
    override val model = MutableStateFlow(LocalModelPhase.Ready)
    var starts = 0
    override fun prepareLocal() { if (model.value != LocalModelPhase.Ready) model.value = LocalModelPhase.Loading }
    override fun start(cloud: CloudConfiguration?) { starts++; state.value = RecognitionState(starts.toLong(), RecognitionPhase.Loading) }
    fun emit(phase: RecognitionPhase, text: String, finished: Boolean = false) { state.value = RecognitionState(starts.toLong(), phase, text, finished = finished) }
    override fun finish() { state.value = state.value.copy(phase = RecognitionPhase.Finishing) }
    override fun cancel() { state.value = state.value.copy(phase = RecognitionPhase.Idle, finished = false) }
    override fun clearResult() { state.value = RecognitionState() }
    override fun close() = Unit
}
