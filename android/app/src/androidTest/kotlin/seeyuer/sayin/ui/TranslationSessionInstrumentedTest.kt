package seeyuer.sayin.ui

import android.content.ContextWrapper
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.*
import seeyuer.sayin.translation.*

class TranslationSessionInstrumentedTest {
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
        override fun start(cloud: CloudConfiguration?) { starts++; state.value = RecognitionState(session = starts.toLong(), phase = RecognitionPhase.Listening) }
        override fun finish() {}
        override fun cancel() { state.value = RecognitionState() }
        override fun clearResult() {}
        override fun close() {}
    }
    private class Engine : Translator {
        var calls = 0
        var cancelled = false
        var partial: (String) -> Unit = {}
        var result = CompletableDeferred<TranslationResult>()
        var idle = CompletableDeferred<Unit>().apply { complete(Unit) }
        override suspend fun translate(file: File, model: TranslationModel, source: String, threads: Int, outputLimit: Int, timeoutMillis: Int,
            onProgress: (TranslationProgress) -> Unit, onPartial: (String) -> Unit): TranslationResult {
            calls++; idle = CompletableDeferred(); partial = onPartial
            return try { withContext(NonCancellable) { result.await() } } finally { idle.complete(Unit) }
        }
        override fun cancel() { cancelled = true }
        override suspend fun release() { idle.await() }
        override fun close() { result.complete(TranslationResult(TranslationEnd.Cancelled)) }
    }
    private fun session(engine: Engine, recognition: Recognition = Recognition()): SpeakSession {
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("translation-test-${UUID.randomUUID()}-$name", mode)
        }
        return SpeakSession(context, TextFieldValue("原文"), recognition = recognition, translator = engine).apply {
            setVisible(Any(), true)
            updateReceiver(ClientState(connection = Connection.ONLINE, paired = true, address = "127.0.0.1", target = "target", paused = false,
                active = true, revision = "1", connectionEpoch = 1))
        }
    }

    @Test fun partialNeverSendsAndCompletedEditedTranslationPreservesOriginalAndUsesImmutableRequest() {
        val engine = Engine(); lateinit var session: SpeakSession
        main { session = session(engine); session.changeSendMode(SpeakSendMode.Automatic); session.translateDraft() }
        try {
            waitFor { engine.calls == 1 }
            engine.partial("partial")
            main { assertFalse(session.markSubmitted()); assertNull(session.interaction.countdown) }
            engine.result.complete(TranslationResult(TranslationEnd.Complete, "English original"))
            waitFor { session.translation.complete }
            var requestId = ""
            main {
                session.selectSource(SpeakInputSource.Keyboard)
                assertEquals("English original", session.displayedDraft.text)
                session.changeDisplayedDraft(TextFieldValue("Edited English"))
                session.completeInput()
                assertNull(session.interaction.countdown)
                assertTrue(session.markSubmitted())
                assertEquals("Edited English", session.submitted!!.value.text)
                assertTrue(session.submitted!!.translated)
                assertEquals("原文", session.draft.text)
                requestId = session.submitted!!.id
                val item = OutboxItem(requestId, "host", "device", "target", null, 0, "injected", "1", null, 1L)
                session.updateReceiver(session.receiver.copy(history = listOf(item)))
                assertEquals("原文", session.draft.text)
                assertNotEquals(requestId, session.draftId)
                assertEquals("Edited English", session.translation.value.text)
            }
            val suffix = "-translation-test-${UUID.randomUUID()}"
            val file = File(instrumentation.targetContext.noBackupFilesDir, "speaking$suffix.bin")
            try {
                val store = SpeakingDraftStore(instrumentation.targetContext, suffix)
                lateinit var saved: SavedSpeakingDraft
                main { saved = session.savedState }
                store.save(saved)
                val loaded = store.load()!!
                assertEquals(saved, loaded)
                assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("Edited English"))
                main {
                    val restored = session(Engine())
                    try {
                        restored.restore(loaded)
                        assertEquals("原文", restored.draft.text)
                        assertEquals("Edited English", restored.displayedDraft.text)
                        assertNull(restored.interaction.countdown)
                        assertTrue(restored.markSubmitted())
                        assertNotEquals(requestId, restored.submitted!!.id)
                    } finally { restored.close() }
                }
            } finally {
                listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { it.delete() }
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("sayin.speaking.v1$suffix") }
            }
        } finally { main { session.close() } }
    }

    @Test fun editClearAndLateCallbacksCannotOverwriteNewDraftOrRestartAutoSend() {
        val engine = Engine(); lateinit var session: SpeakSession
        main { session = session(engine); session.translateDraft() }
        try {
            waitFor { engine.calls == 1 }
            main { session.changeDraft(TextFieldValue("new")); session.changeDraft(TextFieldValue("原文")); session.clearDraft() }
            engine.partial("obsolete partial")
            engine.result.complete(TranslationResult(TranslationEnd.Complete, "obsolete final"))
            waitFor { !session.translationStopping }
            main {
                assertEquals("", session.draft.text)
                assertNull(session.translation.ticket)
                assertNull(session.interaction.countdown)
                assertTrue(engine.cancelled)
            }
        } finally { main { session.close() } }
    }

    @Test fun newRecordingWaitsForNativeStopBeforeOpeningMicrophone() {
        val engine = Engine(); val recognition = Recognition(); lateinit var session: SpeakSession
        main { session = session(engine, recognition); session.selectSource(SpeakInputSource.Local); session.translateDraft() }
        try {
            waitFor { engine.calls == 1 }
            main { session.start(null); assertEquals(0, recognition.starts); assertTrue(session.preparingToRecord) }
            engine.result.complete(TranslationResult(TranslationEnd.Cancelled, "partial"))
            waitFor { recognition.starts == 1 }
            main { assertFalse(session.translationShown); assertEquals("原文", session.draft.text); assertNull(session.interaction.countdown) }
        } finally { main { session.close() } }
    }

    @Test fun quickHandoverSharesTaskAndTruncationErrorsAndRangeChangesCannotSend() {
        for (end in listOf(TranslationEnd.Truncated, TranslationEnd.Error, TranslationEnd.Cancelled, TranslationEnd.Timeout)) {
            val engine = Engine(); lateinit var session: SpeakSession
            main { session = session(engine); session.translateDraft() }
            try {
                waitFor { engine.calls == 1 }
                main {
                    val mainOwner = Any(); val quickOwner = Any()
                    session.setVisible(mainOwner, true); session.setVisible(quickOwner, true); session.setVisible(mainOwner, false)
                    assertEquals(1, engine.calls)
                    session.updateReceiver(session.receiver.copy(paused = true))
                }
                engine.result.complete(TranslationResult(end, "incomplete"))
                waitFor { !session.translation.running }
                main { assertFalse(session.markSubmitted()); assertEquals("原文", session.draft.text); assertNull(session.interaction.countdown) }
            } finally { main { session.close() } }
        }
    }
}
