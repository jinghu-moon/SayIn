package seeyuer.sayin.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import seeyuer.sayin.R
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.*
import seeyuer.sayin.designsystem.VoiceLinkTheme

class SpeakInformationInstrumentedTest {
    private lateinit var session: SpeakSession
    @get:Rule(order = 0) val foreground = object : ExternalResource() {
        override fun before() {
            val i = InstrumentationRegistry.getInstrumentation()
            i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/androidx.activity.ComponentActivity")
                .use { d -> FileInputStream(d.fileDescriptor).use { it.readBytes() } }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private class Recognition : RecognitionEngine {
        override val state = MutableStateFlow(RecognitionState())
        override val model = MutableStateFlow(LocalModelPhase.Ready)
        override fun prepareLocal() {}
        override fun start(cloud: CloudConfiguration?, autoFinish: Boolean, localSilenceMillis: Int) {}
        override fun finish() {}
        override fun cancel() {}
        override fun clearResult() {}
        override fun close() {}
    }
    @Test fun informationSelectionAndSafetyRemainAccessibleAcrossSizesAndThemes() {
        // Start with enough horizontal space for selection metadata alongside both icons;
        // the variants below separately verify hiding secondary metadata on narrow layouts.
        var width by mutableStateOf(480)
        var height by mutableStateOf(640)
        var scale by mutableStateOf(1f)
        var dark by mutableStateOf(false)
        var quick by mutableStateOf(false)
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        val decoded = ForegroundApplication.from(ForegroundApplicationHint(1, "Word", encoded))!!
        assertEquals(48, decoded.icon!!.width)
        assertEquals(android.graphics.Color.BLUE, decoded.icon.getPixel(24, 24))
        assertNull(ForegroundApplication.from(ForegroundApplicationHint(2, "Word", "invalid"))!!.icon)
        val truncated = java.util.Base64.getEncoder().encodeToString(bytes.copyOf(33))
        assertNotNull(ForegroundApplicationHint.iconBytes(truncated))
        assertNull(ForegroundApplication.from(ForegroundApplicationHint(3, "Word", truncated))!!.icon)
        val state = ClientState(connection = Connection.ONLINE, paired = true, paused = false, active = true,
            target = "opaque-scope", foregroundApplication = decoded, revision = "1")
        var receiver by mutableStateOf(state)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "information-ui-${UUID.randomUUID()}-"
        try {
            compose.setContent {
                session = remember {
                    val isolated = object : ContextWrapper(compose.activity) {
                        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(namespace + name, mode)
                    }
                    SpeakSession(isolated, TextFieldValue("中😀a"), recognition = Recognition()).apply { updateReceiver(state) }
                }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    VoiceLinkTheme(dark = dark) {
                        Box(Modifier.size(width.dp, height.dp)) {
                            SpeakPage(receiver, session, rememberTestPhraseLibrary(), {}, {}, quick = quick)
                        }
                    }
                }
            }
            // Ordinary total length already appears in the voice bar. Header count is reserved
            // for a selection or the limit warning, avoiding duplicate information.
            compose.onNodeWithTag("draft-character-count").assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.speak_count_short, 3)).assertIsDisplayed()
            compose.onNodeWithTag("foreground-application-icon").assertIsDisplayed()
            compose.runOnIdle { session.changeDisplayedDraft(session.displayedDraft.copy(selection = TextRange(3, 1))) }
            compose.onNodeWithTag("draft-character-count").assertTextEquals(context.resources.getQuantityString(R.plurals.speak_selected_count, 1, 1))
            compose.runOnIdle { session.changeDisplayedDraft(TextFieldValue("😀".repeat(3600))) }
            // The long limit count may be hidden first on narrow layouts.
            compose.onAllNodesWithTag("draft-character-count").fetchSemanticsNodes().firstOrNull()?.let {
                compose.onNodeWithTag("draft-character-count").assertTextEquals(context.getString(R.string.speak_limit_count, 3600, 4000))
            }
            compose.runOnIdle { session.changeDisplayedDraft(TextFieldValue("中😀a")) }
            val variants = listOf(
                Triple(360, 640, 1f), Triple(320, 640, 1f), Triple(360, 640, 1.5f),
                Triple(360, 400, 1f), Triple(360, 220, 1.5f), Triple(320, 160, 1f))
            for ((index, variant) in variants.withIndex()) {
                compose.runOnIdle { width = variant.first; height = variant.second; scale = variant.third; dark = index % 2 == 1; quick = index >= 3 }
                compose.onNodeWithTag("foreground-application").assertContentDescriptionContains(
                    context.getString(R.string.speak_computer_current, "Word"), substring = true)
                val undo = compose.onNodeWithTag("undo-draft").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val redo = compose.onNodeWithTag("redo-draft").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val editor = compose.onNodeWithTag("draft").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val bar = compose.onNodeWithTag("voice-bar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(undo.width >= 48 * compose.activity.resources.displayMetrics.density - 1)
                assertTrue(redo.width >= 48 * compose.activity.resources.displayMetrics.density - 1)
                assertTrue(editor.height > 0 && editor.top >= undo.bottom - 1 && editor.bottom <= bar.top + 1)
                if (variant.second >= 240 * variant.third) {
                    // The full page wraps every enabled tool; the quick card renders the first five.
                    val tools = if (quick) listOf("open-assist", "choose-image", "translate-draft", "polish-draft", "copy-draft")
                    else listOf(
                        "open-assist", "choose-image", "translate-draft", "polish-draft", "copy-draft", "clear-draft",
                        "send-mode", "engine", "save-phrase", "share-draft",
                    )
                    for (tag in tools) {
                        val tool = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                        assertTrue(tool.width >= 48 * compose.activity.resources.displayMetrics.density - 1)
                    }
                    if (quick) compose.onNodeWithTag("clear-draft").assertDoesNotExist()
                }
                val output = File(context.filesDir, "ui-verification/54-information-$index.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            // Rendering must also reject a stale upstream bitmap while control is disabled.
            for (changed in listOf(state.copy(connection = Connection.OFFLINE),
                state.copy(paused = true), state.copy(active = false), state.copy(foregroundApplication = null))) {
                compose.runOnIdle { receiver = changed; session.updateReceiver(changed) }
                val resource = when { changed.connection != Connection.ONLINE -> R.string.speak_computer_offline
                    changed.paused -> R.string.speak_computer_paused; else -> R.string.speak_computer_unknown }
                compose.onNodeWithTag("foreground-application").assertContentDescriptionContains(context.getString(resource), substring = true)
                compose.onNodeWithTag("foreground-application-icon").assertDoesNotExist()
                compose.onNodeWithTag("foreground-application-fallback").assertIsDisplayed()
            }
            compose.runOnIdle { receiver = state.copy(foregroundApplication = ForegroundApplication("No icon")) }
            compose.onNodeWithTag("foreground-application-icon").assertDoesNotExist()
            compose.onNodeWithTag("foreground-application-fallback").assertIsDisplayed()
        } finally { compose.runOnIdle { if (::session.isInitialized) session.close() } }
    }
}
