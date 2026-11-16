package seeyuer.sayin.ui

import android.content.ContextWrapper
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.*

/** Exercises the single-owner insertion transaction on the real Android session (no UI, no transport). */
class AssistInsertionInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private class Recognition : RecognitionEngine {
        override val state = MutableStateFlow(RecognitionState())
        override val model = MutableStateFlow(LocalModelPhase.Ready)
        var starts = 0
        override fun prepareLocal() {}
        override fun start(cloud: CloudConfiguration?) { starts++; state.value = RecognitionState(starts.toLong(), RecognitionPhase.Listening) }
        override fun finish() { state.value = state.value.copy(phase = RecognitionPhase.Finishing) }
        override fun cancel() { state.value = RecognitionState() }
        override fun clearResult() {}
        override fun close() {}
    }

    private fun ready() = ClientState(connection = Connection.ONLINE, paired = true, address = "127.0.0.1",
        target = "target", paused = false, active = true, revision = "1", connectionEpoch = 1)

    private fun openSession(initial: TextFieldValue = TextFieldValue(), recognition: Recognition = Recognition()): SpeakSession {
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("assist-test-${UUID.randomUUID()}-$name", mode)
        }
        return SpeakSession(context, initial, recognition = recognition).apply {
            setVisible(Any(), true)
            updateReceiver(ready())
        }
    }

    private fun runSession(initial: TextFieldValue = TextFieldValue(), block: (SpeakSession) -> Unit) {
        lateinit var subject: SpeakSession
        main { subject = openSession(initial) }
        try { main { block(subject) } } finally { main { subject.close() } }
    }

    /** A complete, displayed translation restored from the encrypted-draft shape (no native inference). */
    private fun translatedSession(shown: Boolean): SpeakSession = openSession().apply {
        restore(SavedSpeakingDraft(UUID.randomUUID().toString(), "original", 0, 0,
            translationText = "translated", translationModelSha256 = "a".repeat(64),
            translationTemplate = "template", translationShown = shown))
    }

    @Test fun emptyDraftInsertLandsAtEndAndIsOneUndoStep() = runSession { subject ->
        val ticket = subject.beginAssistInsertion()!!
        assertEquals(DraftRepresentation.Original, ticket.representation)
        assertEquals(0, ticket.start)
        assertEquals(0, ticket.end)
        assertEquals(AssistInsertResult.Inserted(2), subject.insertAssist(ticket, "你好"))
        assertEquals("你好", subject.draft.text)
        assertEquals(TextRange(2), subject.draft.selection)
        assertTrue(subject.canUndo)
        subject.undo(); assertEquals("", subject.draft.text)
        subject.redo(); assertEquals("你好", subject.draft.text)
    }

    @Test fun midCursorInsertAndReverseSelectionReplacementReplaceExactly() {
        lateinit var subject: SpeakSession
        main { subject = openSession(TextFieldValue("abcdef", TextRange(3, 3))) }
        try {
            main {
                val ticket = subject.beginAssistInsertion()!!
                assertEquals(3, ticket.start); assertEquals(3, ticket.end)
                assertEquals(AssistInsertResult.Inserted(6), subject.insertAssist(ticket, " XY"))
                assertEquals("abc XYdef", subject.draft.text)
                assertEquals(TextRange(6), subject.draft.selection)
            }
            main {
                subject.changeDraft(TextFieldValue("abcdef", TextRange(5, 2)))
                val ticket = subject.beginAssistInsertion()!!
                assertEquals(2, ticket.start); assertEquals(5, ticket.end)
                assertEquals(AssistInsertResult.Inserted(3), subject.insertAssist(ticket, "Z"))
                assertEquals("abZf", subject.draft.text)
                assertEquals(TextRange(3), subject.draft.selection)
            }
            main {
                subject.changeDraft(TextFieldValue("abcdef", TextRange(0, 6)))
                val ticket = subject.beginAssistInsertion()!!
                assertEquals(AssistInsertResult.Inserted(1), subject.insertAssist(ticket, "Z"))
                assertEquals("Z", subject.draft.text)
            }
        } finally { main { subject.close() } }
    }

    @Test fun invalidSelectionFallsBackToEndAtOpenAndEmojiBoundariesStayIntact() {
        lateinit var subject: SpeakSession
        main { subject = openSession(TextFieldValue("a😀b", TextRange(2, 2))) }
        try {
            main {
                val ticket = subject.beginAssistInsertion()!!
                assertEquals("a😀b".length, ticket.start)
                assertEquals(ticket.start, ticket.end)
                assertEquals(AssistInsertResult.Inserted(5), subject.insertAssist(ticket, "!"))
                assertEquals("a😀b!", subject.draft.text)
            }
            main {
                subject.changeDraft(TextFieldValue("a😀b", TextRange(3, 3)))
                val ticket = subject.beginAssistInsertion()!!
                assertEquals(3, ticket.start)
                assertEquals(AssistInsertResult.Inserted(4), subject.insertAssist(ticket, "X"))
                assertEquals("a😀Xb", subject.draft.text)
                assertEquals(TextRange(4), subject.draft.selection)
            }
        } finally { main { subject.close() } }
    }

    @Test fun translationInsertPreservesOriginalAndDraftIdentity() {
        lateinit var subject: SpeakSession
        main { subject = translatedSession(shown = true) }
        try {
            main {
                assertTrue(subject.assistEditable)
                val draftId = subject.draftId
                val version = subject.interaction.version
                val revision = subject.translationRevision
                val ticket = subject.beginAssistInsertion()!!
                assertEquals(DraftRepresentation.Translation, ticket.representation)
                assertEquals(revision, ticket.revision)
                assertEquals(AssistInsertResult.Inserted(2), subject.insertAssist(ticket, " !"))
                assertEquals("original", subject.draft.text)
                assertEquals(" !translated", subject.translation.value.text)
                assertEquals(" !translated", subject.displayedDraft.text)
                assertEquals(TextRange(2), subject.displayedDraft.selection)
                assertEquals(draftId, subject.draftId)
                assertEquals(version, subject.interaction.version)
                assertTrue(subject.translationShown)
                assertTrue(subject.translation.complete)
                assertTrue(subject.canSendRepresentation)
                assertTrue(subject.canUndo)
            }
        } finally { main { subject.close() } }
    }

    @Test fun originalInsertInvalidatesTranslationAndRotatesDraftIdentity() {
        lateinit var subject: SpeakSession
        main { subject = translatedSession(shown = false) }
        try {
            main {
                val draftId = subject.draftId
                val version = subject.interaction.version
                val ticket = subject.beginAssistInsertion()!!
                assertEquals(DraftRepresentation.Original, ticket.representation)
                assertEquals(version, ticket.revision)
                assertEquals(AssistInsertResult.Inserted(2), subject.insertAssist(ticket, "!!"))
                assertEquals("!!original", subject.draft.text)
                assertEquals(TextRange(2), subject.draft.selection)
                assertNotEquals(draftId, subject.draftId)
                assertEquals(version + 1, subject.interaction.version)
                assertNull(subject.translation.ticket)
                assertFalse(subject.translationShown)
                assertEquals("!!original", subject.displayedDraft.text)
            }
        } finally { main { subject.close() } }
    }

    @Test fun staleTicketsAreRejectedAfterTextChangeSecondBeginAndReopen() {
        lateinit var subject: SpeakSession
        main { subject = openSession(TextFieldValue("draft", TextRange(5, 5))) }
        try {
            main {
                val ticket = subject.beginAssistInsertion()!!
                subject.changeDraft(TextFieldValue("draft changed"))
                assertEquals(AssistInsertResult.Stale, subject.insertAssist(ticket, "x"))
                assertEquals("draft changed", subject.draft.text)
            }
            main {
                subject.changeDraft(TextFieldValue("draft", TextRange(5, 5)))
                val first = subject.beginAssistInsertion()!!
                val second = subject.beginAssistInsertion()!!
                assertEquals(AssistInsertResult.Stale, subject.insertAssist(first, "x"))
                assertEquals("draft", subject.draft.text)
                assertEquals(AssistInsertResult.Inserted(6), subject.insertAssist(second, "!"))
                assertEquals("draft!", subject.draft.text)
            }
            main {
                val owner = Any()
                subject.setVisible(owner, true)
                val ticket = subject.beginAssistInsertion()!!
                subject.setVisible(owner, false)
                subject.setVisible(owner, true)
                assertEquals(AssistInsertResult.Stale, subject.insertAssist(ticket, "?"))
                assertEquals("draft!", subject.draft.text)
            }
        } finally { main { subject.close() } }
    }

    @Test fun recordingBlocksInsertion() {
        lateinit var subject: SpeakSession
        main { subject = openSession(TextFieldValue("draft")) }
        try {
            main {
                val ticket = subject.beginAssistInsertion()!!
                subject.selectSource(SpeakInputSource.Local)
                // Hold the service-acceptance boundary explicitly. A ready fake
                // recognizer may otherwise start synchronously before this assertion.
                subject.requestMicrophoneService = {}
                subject.start(null)
                assertTrue(subject.preparingToRecord)
                assertFalse(subject.assistEditable)
                assertNull(subject.beginAssistInsertion())
                assertEquals(AssistInsertResult.NotEditable, subject.insertAssist(ticket, "x"))
                assertEquals("draft", subject.draft.text)
                subject.startPreparedRecording()
            }
            main {
                assertEquals(RecognitionPhase.Listening, subject.asr.phase)
                assertFalse(subject.assistEditable)
                assertNull(subject.beginAssistInsertion())
                subject.cancelRecording(restore = false)
            }
        } finally { main { subject.close() } }
    }

    @Test fun frozenRequestAndDraftRestoreBlockInsertion() {
        lateinit var subject: SpeakSession
        main { subject = openSession(TextFieldValue("request text", TextRange(12, 12))) }
        try {
            main {
                val ticket = subject.beginAssistInsertion()!!
                assertTrue(subject.markSubmitted())
                assertTrue(subject.requestLocked)
                assertFalse(subject.assistEditable)
                assertNull(subject.beginAssistInsertion())
                assertEquals(AssistInsertResult.NotEditable, subject.insertAssist(ticket, "x"))
                assertEquals("request text", subject.draft.text)
            }
            main {
                val id = subject.submitted!!.id
                subject.updateReceiver(ready().copy(rejectedDraft = id))
                assertFalse(subject.requestLocked)
                assertTrue(subject.assistEditable)
                assertEquals(AssistInsertResult.Inserted(14), subject.insertAssist(subject.beginAssistInsertion()!!, "!!"))
                assertEquals("request text!!", subject.draft.text)
                assertNull(subject.submitted)
            }
            main {
                val ticket = subject.beginAssistInsertion()!!
                subject.draftRestoring = true
                assertNull(subject.beginAssistInsertion())
                assertEquals(AssistInsertResult.NotEditable, subject.insertAssist(ticket, "x"))
                subject.draftRestoring = false
                subject.draftStorageIssue = true
                assertEquals(AssistInsertResult.NotEditable, subject.insertAssist(ticket, "x"))
                subject.draftStorageIssue = false
                assertEquals("request text!!", subject.draft.text)
            }
        } finally { main { subject.close() } }
    }

    @Test fun rejectedResultKeepsDraftAndSelectionAndNeverTruncates() {
        lateinit var subject: SpeakSession
        main { subject = openSession(TextFieldValue("a".repeat(3999), TextRange(3999, 3999))) }
        try {
            main {
                val before = subject.draft
                assertEquals(AssistInsertResult.Rejected, subject.insertAssist(subject.beginAssistInsertion()!!, "bc"))
                assertEquals(before, subject.draft)
                assertFalse(subject.canUndo)
            }
            main {
                subject.clearDraft()
                assertEquals(AssistInsertResult.Rejected, subject.insertAssist(subject.beginAssistInsertion()!!, "a".repeat(4001)))
                assertEquals("", subject.draft.text)
                assertEquals(AssistInsertResult.Inserted(4000), subject.insertAssist(subject.beginAssistInsertion()!!, "a".repeat(4000)))
                assertEquals(4000, subject.draft.text.length)
            }
        } finally { main { subject.close() } }
    }

    @Test fun insertCancelsAutoSendAndClearsEditableRequestWithoutSending() {
        lateinit var subject: SpeakSession
        var sends = 0
        main {
            subject = openSession(TextFieldValue("auto send draft", TextRange(15, 15)))
            subject.onSendReady = { sends++ }
            subject.changeSendMode(SpeakSendMode.Automatic)
            subject.completeInput()
            assertNotNull(subject.interaction.countdown)
            assertEquals(AssistInsertResult.Inserted(16), subject.insertAssist(subject.beginAssistInsertion()!!, "!"))
            assertEquals("auto send draft!", subject.draft.text)
            assertNull(subject.interaction.countdown)
            assertEquals(0, sends)
            assertTrue(subject.markSubmitted())
            val id = subject.submitted!!.id
            subject.updateReceiver(ready().copy(rejectedDraft = id))
            // A rejected request keeps its editable terminal row so the user can retry it;
            // the insertion below must clear that association without resubmitting it.
            assertEquals("failed", subject.submitted?.state)
            assertEquals(AssistInsertResult.Inserted(17), subject.insertAssist(subject.beginAssistInsertion()!!, "?"))
            assertEquals("auto send draft!?", subject.draft.text)
            assertNull(subject.submitted)
            assertEquals(0, sends)
            assertTrue(subject.receiver.history.isEmpty())
        }
        try {
            main { assertNull(subject.interaction.countdown) }
        } finally { main { subject.close() } }
    }

    @Test fun typingBeforeAndAfterInsertAreSeparateUndoSteps() {
        lateinit var subject: SpeakSession
        main { subject = openSession(TextFieldValue("base", TextRange(4, 4))) }
        try {
            main {
                subject.changeDraft(TextFieldValue("base typed", TextRange(10, 10)))
                assertEquals(AssistInsertResult.Inserted(11), subject.insertAssist(subject.beginAssistInsertion()!!, "!"))
                subject.changeDraft(TextFieldValue("base typed! more", TextRange(16, 16)))
                assertTrue(subject.canUndo)
                subject.undo(); assertEquals("base typed!", subject.draft.text)
                subject.undo(); assertEquals("base typed", subject.draft.text); assertEquals(TextRange(10, 10), subject.draft.selection)
                subject.undo(); assertEquals("base", subject.draft.text)
                subject.redo(); assertEquals("base typed", subject.draft.text)
                subject.redo(); assertEquals("base typed!", subject.draft.text)
                subject.redo(); assertEquals("base typed! more", subject.draft.text)
            }
        } finally { main { subject.close() } }
    }
}
