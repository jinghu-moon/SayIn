package seeyuer.sayin.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.*
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.translation.*

class TranslationUiInstrumentedTest {
    private lateinit var session: SpeakSession
    @get:Rule(order = 0) val foreground = object : ExternalResource() {
        override fun before() {
            val i = InstrumentationRegistry.getInstrumentation()
            i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/androidx.activity.ComponentActivity")
                .use { d -> FileInputStream(d.fileDescriptor).use { it.readBytes() } }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private class Engine : Translator {
        val result = CompletableDeferred<TranslationResult>()
        var partial: (String) -> Unit = {}
        var calls = 0
        override suspend fun translate(file: File, model: TranslationModel, source: String, threads: Int, outputLimit: Int, timeoutMillis: Int,
            onProgress: (TranslationProgress) -> Unit, onPartial: (String) -> Unit): TranslationResult {
            calls++; partial = onPartial
            return withContext(NonCancellable) { result.await() }
        }
        override fun cancel() { result.complete(TranslationResult(TranslationEnd.Cancelled)) }
        override suspend fun release() {}
        override fun close() { cancel() }
    }
    private class Recognition : RecognitionEngine {
        override val state = MutableStateFlow(RecognitionState())
        override val model = MutableStateFlow(LocalModelPhase.Ready)
        override fun prepareLocal() {}
        override fun start(cloud: CloudConfiguration?) {}
        override fun finish() {}
        override fun cancel() {}
        override fun clearResult() {}
        override fun close() {}
    }
    @Test fun fullAndQuickCardShareEditableTranslationAndManualSendKeepsOriginal() {
        val engine = Engine()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = "-ui-${UUID.randomUUID()}"
        val model = TranslationModel("fixture", "fixture.gguf", 3, "0".repeat(64), "fixture", 0, "fixture")
        val store = TranslationModelStore(context, model, suffix)
        store.file.parentFile!!.mkdirs(); store.file.writeBytes(byteArrayOf(1, 2, 3))
        var quick by mutableStateOf(false)
        var sends = 0
        try {
            compose.setContent {
                val retained = remember {
                    val isolated = object : ContextWrapper(compose.activity) {
                        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("translation-ui-$name", mode)
                    }
                    SpeakSession(isolated, TextFieldValue("会议改到周六。\n请不要自动发送。😊"), recognition = Recognition(),
                        translator = engine, translationModel = model, modelStore = store).also {
                        session = it; it.selectSource(SpeakInputSource.Keyboard)
                        it.updateReceiver(ClientState(connection = Connection.ONLINE, address = "127.0.0.1", active = true, paused = false, target = "target"))
                    }
                }
                VoiceLinkTheme {
                    Box(Modifier.size(360.dp, if (quick) 400.dp else 680.dp)) {
                        SpeakPage(retained.receiver, retained, { if (retained.markSubmitted()) sends++ }, {}, quick = quick)
                    }
                }
            }
            compose.onNodeWithTag("translate-draft").performClick()
            compose.waitUntil(5000) { engine.calls == 1 }
            compose.runOnIdle { engine.partial("The meeting") }
            compose.onNodeWithTag("send-draft").assertIsNotEnabled()
            engine.result.complete(TranslationResult(TranslationEnd.Complete, "The meeting has been moved to Saturday.\nPlease do not send automatically. 😊"))
            compose.waitUntil(5000) { session.translation.complete }
            compose.onNodeWithTag("draft").assertTextContains("The meeting", substring = true)
            screenshot("translation-full")
            compose.runOnIdle { quick = true }
            compose.onNodeWithTag("draft").assertTextContains("The meeting", substring = true)
            compose.runOnIdle { assertEquals(1, engine.calls); assertEquals(0, sends) }
            screenshot("translation-quick")
            compose.onNodeWithTag("translation-original").performClick()
            compose.onNodeWithTag("draft").assertTextContains("会议改到周六", substring = true)
            compose.onNodeWithTag("translation-english").performClick()
            compose.onNodeWithTag("draft").performTextReplacement("Edited English for manual sending.")
            compose.runOnIdle { assertEquals("会议改到周六。\n请不要自动发送。😊", session.draft.text); assertEquals(0, sends) }
            compose.onNodeWithTag("send-draft").performClick()
            compose.runOnIdle { assertEquals(1, sends); assertEquals("Edited English for manual sending.", session.submitted!!.value.text) }
        } finally {
            compose.runOnIdle { if (::session.isInitialized) session.close() }
            store.file.delete(); store.file.parentFile!!.delete()
        }
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.filesDir, "ui-verification/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
