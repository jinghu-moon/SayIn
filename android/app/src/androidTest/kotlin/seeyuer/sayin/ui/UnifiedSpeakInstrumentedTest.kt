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
                SpeakSession(context, initial, recognition = engine).also { it.selectSource(SpeakInputSource.Local); session = it; it.onSendReady = { sends++ } }
            }
            DisposableEffect(retained) { onDispose { retained.close() } }
            val phrases = rememberTestPhraseLibrary()
            CompositionLocalProvider(LocalContext provides context) {
                VoiceLinkTheme {
                    Box(Modifier.size(360.dp, 680.dp)) {
                        VoiceLinkContent(receiver, retained, phrases, { sends++ }, { _, _ -> }, {}, quick = quick)
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
        compose.onNodeWithTag("clear-draft").performClick()
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

    @Test fun continuousDictationDoesNotFinishOnSilence() {
        mount()
        compose.runOnIdle { session.changeContinuousDictation(true); session.start(null) }
        compose.runOnIdle { engine.emit(RecognitionPhase.Listening, "partial", speechPause = true) }
        compose.runOnIdle {
            assertEquals(RecognitionPhase.Listening, session.asr.phase)
            assertTrue(session.continuousDictation)
        }
        finish("final text")
        compose.onNodeWithTag("draft").assertTextEquals("final text")
    }

    @Test fun tapRecordingFinishesWithoutPartialButHoldWaitsForRelease() {
        mount()
        compose.runOnIdle {
            session.setAppSendPolicy("Test editor", AppSendPolicy.Automatic)
            session.start(null)
        }
        compose.runOnIdle { engine.emit(RecognitionPhase.Listening, "", speechPause = true) }
        compose.runOnIdle {
            assertEquals(RecognitionPhase.Finishing, session.asr.phase)
            assertNull(session.interaction.countdown)
            engine.emit(RecognitionPhase.Idle, "final text", finished = true)
        }
        compose.onNodeWithTag("cancel-auto-send").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("final text", session.draft.text)
            session.start(null, holdToSpeak = true)
        }
        compose.runOnIdle { engine.emit(RecognitionPhase.Listening, "held", speechPause = true) }
        compose.runOnIdle {
            assertEquals(RecognitionPhase.Listening, session.asr.phase)
            assertNull(session.interaction.countdown)
        }
        finish("released")
        compose.onNodeWithTag("cancel-auto-send").assertIsDisplayed()
        assertEquals(0, sends)
    }

    @Test fun interruptedCaptureKeepsLateTextWithoutAutomaticSend() {
        mount()
        compose.runOnIdle { session.changeSendMode(SpeakSendMode.Automatic) }
        for (end in listOf(RecordingEnd.NoSpeech, RecordingEnd.Limit, RecordingEnd.CaptureStalled)) {
            listen("")
            compose.runOnIdle { engine.emit(RecognitionPhase.Finishing, "", end = end) }
            compose.runOnIdle {
                engine.emit(RecognitionPhase.Idle, "late result", finished = end != RecordingEnd.CaptureStalled)
            }
            compose.runOnIdle {
                assertTrue(session.draft.text.endsWith("late result"))
                assertNull(session.interaction.countdown)
                assertEquals(0, sends)
            }
        }
    }

    @Test fun tappingDuringFinalizingQueuesOneResumeAndCancelsAutomaticSend() {
        mount()
        compose.runOnIdle { session.changeSendMode(SpeakSendMode.Automatic) }
        listen("first")
        compose.runOnIdle { engine.emit(RecognitionPhase.Finishing, "first", end = RecordingEnd.Silence) }
        repeat(2) { compose.onNodeWithTag("start-local-asr").assertIsEnabled().performClick() }
        compose.runOnIdle { engine.emit(RecognitionPhase.Idle, "first final", finished = true) }
        compose.waitUntil(5000) { engine.starts == 2 }
        compose.runOnIdle {
            assertEquals("first final", session.draft.text)
            assertNull(session.interaction.countdown)
            assertEquals(0, sends)
            engine.emit(RecognitionPhase.Listening, " second")
        }
        finish(" second")
        compose.runOnIdle { assertEquals("first final second", session.draft.text) }
    }

    @Test fun changingEntryRevokesPendingResume() {
        mount()
        listen("first")
        compose.runOnIdle { engine.emit(RecognitionPhase.Finishing, "first", end = RecordingEnd.Silence) }
        compose.onNodeWithTag("start-local-asr").performClick()
        compose.onNodeWithTag("nav-History").performClick()
        compose.runOnIdle { engine.emit(RecognitionPhase.Idle, "first final", finished = true) }
        compose.runOnIdle {
            assertEquals(1, engine.starts)
            assertEquals("first final", session.draft.text)
        }
    }

    @Test fun keyboardWaitsForImeCommitAndForegroundChangeCancels() {
        mount(TextFieldValue("ni", TextRange(2), TextRange(0, 2)))
        compose.runOnIdle { session.setAppSendPolicy("Test editor", AppSendPolicy.Automatic) }
        compose.onNodeWithTag("send-draft").performClick()
        compose.runOnIdle {
            assertTrue(session.waitingForImeCommit)
            assertNull(session.interaction.countdown)
            session.changeDisplayedDraft(TextFieldValue("你", TextRange(1)))
        }
        compose.onNodeWithTag("cancel-auto-send").assertIsDisplayed()
        compose.runOnIdle { receiver = ready().copy(foregroundApplication = ForegroundApplication("Browser", sequence = 2)) }
        compose.onNodeWithTag("cancel-auto-send").assertDoesNotExist()
        compose.runOnIdle { receiver = ready().copy(foregroundApplication = ForegroundApplication("Test editor", sequence = 3)) }
        compose.onNodeWithTag("cancel-auto-send").assertDoesNotExist()
        compose.runOnIdle { assertEquals("你", session.draft.text); assertEquals(0, sends) }
        compose.onNodeWithTag("send-draft").performClick()
        compose.waitUntil(5000) { sends == 1 }
        compose.onNodeWithTag("cancel-auto-send").assertDoesNotExist()
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
        val serviceRequests = mutableListOf<Long>()
        compose.runOnIdle {
            engine.model.value = LocalModelPhase.Loading
            session.requestMicrophoneService = { serviceRequests += it }
            session.start(null)
            assertTrue(session.preparingToRecord)
            assertEquals(1, serviceRequests.size)
        }
        compose.onNodeWithTag("nav-History").performClick()
        compose.runOnIdle { engine.model.value = LocalModelPhase.Ready }; compose.waitForIdle()
        compose.onNodeWithTag("nav-Speak").performClick()
        compose.runOnIdle {
            assertFalse(session.startPreparedRecording(serviceRequests.single()))
            assertEquals(0, engine.starts)
            assertEquals("existing draft", session.draft.text)
            assertEquals(0, sends)
            session.start(null)
            assertFalse(session.startPreparedRecording(serviceRequests.first()))
            assertTrue(session.startPreparedRecording(serviceRequests.last()))
            assertEquals(1, engine.starts)
            session.cancelRecording()
        }
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

    @Test fun anotherComputerCanEditAndSendWhileThePriorReceiptIsPending() {
        mount(TextFieldValue("first draft"))
        val a = PairedTarget(java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(), "127.0.0.1", 7788)
        val b = PairedTarget(java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(), "127.0.0.1", 7789)
        compose.runOnIdle {
            val first = ready().copy(pairedTargets = listOf(a, b), selectedHostId = a.hostId)
            session.updateReceiver(first)
            assertTrue(session.markSubmitted())
            val original = session.draftId
            val held = OutboxItem(original, a.hostId, a.pairId, "target", byteArrayOf(1), 1,
                "held", "1", null, 1)
            session.updateReceiver(first.copy(history = listOf(held)))
            val second = first.copy(selectedHostId = b.hostId, port = b.port, connectionEpoch = 2)
            session.updateReceiver(second.copy(history = listOf(held)))
            assertFalse(session.requestLocked)
            assertNotEquals(original, session.draftId)
            session.changeDraft(TextFieldValue("second draft"))
            assertTrue(session.markSubmitted())
            val secondId = session.draftId
            session.updateReceiver(second.copy(history = listOf(held.copy(state = "injected"), held.copy(id = secondId, state = "injected"))))
            assertEquals("second draft", session.draft.text)
            assertEquals("saved", session.submitted?.state)
            session.updateReceiver(second.copy(history = listOf(held.copy(id = secondId, hostId = b.hostId, deviceId = b.pairId, state = "injected"))))
            assertEquals("", session.draft.text)
        }
    }

    @Test fun entireUtteranceUndoRedoAndCancellationIgnoreLateFinal() {
        val original = TextFieldValue("Keep: ", TextRange(6))
        mount(original); listen("partial")
        android.os.SystemClock.sleep(30)
        compose.runOnIdle { engine.emit(RecognitionPhase.Listening, "new whole sentence") }
        finish("new whole sentence")
        var duration = 0L
        compose.runOnIdle { duration = session.recordingDurationMillis; assertTrue(duration > 0L) }
        compose.onNodeWithTag("undo-draft").performClick()
        compose.runOnIdle { assertEquals(original, session.draft); assertEquals(0L, session.recordingDurationMillis) }
        compose.onNodeWithTag("redo-draft").performClick()
        compose.runOnIdle { assertEquals("Keep: new whole sentence", session.draft.text); assertEquals(duration, session.recordingDurationMillis) }
        listen("discarded")
        compose.onNodeWithTag("cancel-recording").performClick()
        compose.runOnIdle { engine.emit(RecognitionPhase.Idle, "late final", finished = true) }
        compose.runOnIdle {
            assertEquals("Keep: new whole sentence", session.draft.text)
            assertNull(session.interaction.countdown); assertEquals(0, sends)
        }
        compose.onNodeWithTag("undo-draft").performClick()
        compose.runOnIdle { assertEquals(original, session.draft) }
        compose.runOnIdle {
            session.redo(); assertEquals(duration, session.recordingDurationMillis)
            session.clearDraft(); assertEquals(0L, session.recordingDurationMillis)
            session.undo(); assertEquals(duration, session.recordingDurationMillis)
            assertTrue(session.markSubmitted())
            val item = OutboxItem(session.draftId, "host", "device", "target", null, 0, "injected", "1", null, 1L)
            session.updateReceiver(ready().copy(history = listOf(item)))
            assertEquals("", session.draft.text); assertEquals(0L, session.recordingDurationMillis); assertFalse(session.canUndo)
        }
    }

    @Test fun explicitEndSendWaitsForFinalAndDoesNotChangeAutomaticPreference() {
        mount(); listen("partial")
        compose.onNodeWithTag("send-draft").performClick()
        compose.runOnIdle {
            assertEquals(RecognitionPhase.Finishing, session.asr.phase)
            assertEquals(0, sends); assertEquals(SpeakSendMode.Manual, session.sendMode)
            engine.emit(RecognitionPhase.Idle, "final text", finished = true)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, sends); assertNull(session.interaction.countdown); assertEquals("final text", session.draft.text) }
        // A repeated provider final cannot consume the intent twice.
        compose.runOnIdle { engine.emit(RecognitionPhase.Idle, "duplicate final", finished = true) }
        compose.runOnIdle { assertEquals(1, sends) }
        // A simultaneous VAD stop already owns the capture. It cannot inherit a later end-and-send action.
        listen("second")
        compose.runOnIdle { engine.finishReason = RecordingEnd.Silence }
        compose.onNodeWithTag("send-draft").performClick()
        compose.runOnIdle { engine.emit(RecognitionPhase.Idle, "second final", finished = true) }
        compose.runOnIdle { assertEquals(1, sends); assertTrue(session.draft.text.endsWith("second final")) }
    }

    @Test fun endSendIsRevokedByScopeChangeAndByOpeningConnection() {
        mount()
        for (openPanel in listOf(false, true)) {
            compose.runOnIdle { session.changeSendMode(SpeakSendMode.Automatic) }
            listen("partial")
            compose.onNodeWithTag("send-draft").performClick()
            if (openPanel) compose.onNodeWithTag("speak-connect-action").performClick()
            else compose.runOnIdle { receiver = ready().copy(connectionEpoch = receiver.connectionEpoch + 1) }
            compose.runOnIdle { engine.emit(RecognitionPhase.Idle, "safe final", finished = true) }
            compose.runOnIdle { assertEquals(0, sends); assertNull(session.interaction.countdown) }
            if (openPanel) compose.onNodeWithTag("close-connection").performClick()
        }
    }

    @Test fun heldMicrophoneSwipeUpCancelsAndPhysicalTapRetainsDraft() {
        mount(TextFieldValue("original"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, android.Manifest.permission.RECORD_AUDIO)
        compose.onNodeWithTag("start-local-asr").performTouchInput { click() }
        compose.waitUntil(5000) { engine.starts == 1 }
        compose.runOnIdle { engine.emit(RecognitionPhase.Listening, "first") }
        compose.onNodeWithTag("start-local-asr").performTouchInput { click() }
        compose.runOnIdle { assertEquals(RecognitionPhase.Finishing, session.asr.phase); engine.emit(RecognitionPhase.Idle, "first", finished = true) }
        compose.waitForIdle()
        val retained = session.draft
        compose.onNodeWithTag("start-local-asr").performTouchInput { down(center); advanceEventTime(350) }
        compose.waitUntil(5000) { engine.starts == 2 }
        compose.runOnIdle { engine.emit(RecognitionPhase.Listening, "discard") }
        compose.onNodeWithTag("start-local-asr").performTouchInput {
            moveTo(center.copy(y = -80.dp.toPx())); advanceEventTime(60); up()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(retained, session.draft); assertEquals(0, sends); assertNull(session.interaction.countdown) }
    }

    @Test fun voiceBarKeepsSameBoundsAndDoesNotSendAnOldDraftWhileNewRecordingIsEmpty() {
        mount(TextFieldValue("old draft"))
        val before = compose.onNodeWithTag("voice-bar").fetchSemanticsNode().boundsInRoot
        listen("")
        compose.onNodeWithTag("send-draft").assertIsNotEnabled()
        compose.runOnIdle { engine.emit(RecognitionPhase.Listening, "new text") }
        compose.onNodeWithTag("send-draft").assertIsEnabled()
        compose.runOnIdle { session.finishRecording() }
        val tail = compose.onNodeWithTag("voice-bar").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { engine.emit(RecognitionPhase.Idle, "new text", finished = true) }
        val after = compose.onNodeWithTag("voice-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(before, tail); assertEquals(before, after)
        compose.runOnIdle { assertEquals(0, sends) }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "ui-verification").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    companion object {
        private fun ready() = ClientState(connection = Connection.ONLINE, paired = true, address = "127.0.0.1",
            active = true, paused = false, target = "target", revision = "1", autoTextSupported = true,
            foregroundApplication = ForegroundApplication("Test editor", sequence = 1))
    }
}

private class FakeRecognition : RecognitionEngine {
    override val state = MutableStateFlow(RecognitionState())
    override val model = MutableStateFlow(LocalModelPhase.Ready)
    var starts = 0
    override fun prepareLocal() { if (model.value != LocalModelPhase.Ready) model.value = LocalModelPhase.Loading }
    private var automaticEndpoint = false
    var finishReason = RecordingEnd.Manual
    override fun start(cloud: CloudConfiguration?, autoFinish: Boolean, localSilenceMillis: Int) {
        automaticEndpoint = autoFinish
        starts++
        state.value = RecognitionState(starts.toLong(), RecognitionPhase.Loading)
    }
    fun emit(
        phase: RecognitionPhase,
        text: String,
        finished: Boolean = false,
        speechPause: Boolean = false,
        end: RecordingEnd? = state.value.end,
    ) {
        val stopped = speechPause && automaticEndpoint && phase == RecognitionPhase.Listening
        state.value = RecognitionState(
            starts.toLong(), if (stopped) RecognitionPhase.Finishing else phase, text,
            finished = finished, end = if (stopped) RecordingEnd.Silence else end,
        )
    }
    override fun finish() {
        state.value = state.value.copy(phase = RecognitionPhase.Finishing, end = finishReason)
    }
    override fun cancel() { state.value = state.value.copy(phase = RecognitionPhase.Idle, finished = false) }
    override fun clearResult() { state.value = RecognitionState() }
    override fun close() = Unit
}
