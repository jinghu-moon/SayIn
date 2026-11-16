package seeyuer.sayin.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.sayin.R
import seeyuer.sayin.asr.*
import seeyuer.sayin.data.*
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.ui.assist.*
import top.yukonga.miuix.kmp.basic.Scaffold

/** Production panel, generated records, counted fake module; never opens the daily Root socket. */
@RunWith(AndroidJUnit4::class)
class RootClipboardPanelStateInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var loads = 0
    private var reads = 0
    private var nextLoad: CompletableDeferred<Unit>? = null
    private var show by mutableStateOf(true)
    private lateinit var session: SpeakSession

    private fun mount() {
        val client = object : RootClipboardAccess {
            override suspend fun list(): RootClips {
                loads++
                nextLoad?.await()
                delay(150)
                return RootClips("1:1", true, List(50) { RootClip(it + 1L, it + 1L, "SAYIN_TEST_menu_$it") })
            }
            override suspend fun get(snapshot: RootClips, id: Long): String {
                reads++
                delay(50)
                return "SAYIN_TEST_menu full text"
            }
            override suspend fun clear(snapshot: RootClips) = error("Unexpected CLEAR")
        }
        compose.setContent {
            val context = remember {
                object : ContextWrapper(compose.activity) {
                    override fun getSharedPreferences(name: String, mode: Int) =
                        super.getSharedPreferences("SAYIN_TEST_panel_state_$name", mode)
                }
            }
            CompositionLocalProvider(LocalContext provides context, LocalRootClipboardClient provides client) {
                VoiceLinkTheme {
                    val owner = remember { Any() }
                    session = remember { SpeakSession(context, recognition = PanelStateRecognition()).also { it.setVisible(owner, true) } }
                    DisposableEffect(session) { onDispose { session.close() } }
                    var ticket by remember { mutableStateOf(session.beginAssistInsertion()) }
                    val phrases = rememberTestPhraseLibrary()
                    Scaffold {
                        Box(Modifier.fillMaxSize()) {
                            AssistPanel(show, 1, session, phrases, ticket, AssistTab.Clipboard,
                                { ticket = it }, {}, { show = false })
                        }
                    }
                }
            }
        }
        waitFor("root-clipboard-list")
        assertEquals(1, loads)
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun menu(action: Int) {
        compose.onAllNodesWithTag("assist-clipboard-menu")[0].performClick()
        compose.onNodeWithText(compose.activity.getString(action)).performClick()
    }

    @RootHelperDeviceTest
    @Test fun pasteKeepsTheLoadedList() {
        mount()
        menu(R.string.assist_paste)
        compose.waitUntil(5_000) { session.draft.text == "SAYIN_TEST_menu full text" }
        compose.waitForIdle()
        compose.onNodeWithTag("root-clipboard-list").assertIsDisplayed()
        compose.onNodeWithTag("root-clipboard-loading").assertDoesNotExist()
        assertEquals("Pasting must not reload the module", 1, loads)
        assertEquals(1, reads)
    }

    @RootHelperDeviceTest
    @Test fun viewerBackKeepsTheLoadedList() {
        mount()
        compose.onNodeWithTag("root-clipboard-list").performScrollToIndex(20)
        val row = compose.onNodeWithTag("root-clipboard-21").fetchSemanticsNode().boundsInRoot
        menu(R.string.assist_view_full)
        waitFor("assist-viewer-text")
        compose.onNodeWithTag("assist-back").performClick()
        waitFor("root-clipboard-list")
        assertEquals("Returning from full text must reuse the loaded list", 1, loads)
        assertEquals(row.top, compose.onNodeWithTag("root-clipboard-21").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(1, reads)
    }

    @RootHelperDeviceTest
    @Test fun editorBackKeepsTheLoadedList() {
        mount()
        menu(R.string.assist_save_as_phrase)
        waitFor("assist-editor-field")
        compose.onNodeWithTag("assist-back").performClick()
        waitFor("assist-confirm-cancel")
        compose.onNodeWithTag("assist-confirm-action").performClick()
        waitFor("root-clipboard-list")
        assertEquals("Returning from editing must reuse the loaded list", 1, loads)
        assertEquals(1, reads)
    }

    @RootHelperDeviceTest
    @Test fun copyRefreshKeepsTheVisibleListWhileLoading() {
        mount()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        val original = clipboard.primaryClip
        val reply = CompletableDeferred<Unit>()
        nextLoad = reply
        try {
            menu(R.string.assist_copy)
            compose.waitUntil(5_000) { loads == 2 }
            compose.onNodeWithTag("root-clipboard-list").assertIsDisplayed()
            compose.onNodeWithTag("root-clipboard-loading").assertDoesNotExist()
            compose.onNodeWithTag("assist-panel").assertIsDisplayed()
            assertEquals("", session.draft.text)
            assertEquals(1, reads)
        } finally {
            reply.complete(Unit)
            if (original == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(original)
        }
    }

    @RootHelperDeviceTest
    @Test fun switchingTabsStillReloadsTheModule() {
        mount()
        compose.onNodeWithTag("assist-tab-phrases").performClick()
        compose.onNodeWithTag("assist-tab-clipboard").performClick()
        waitFor("root-clipboard-list")
        assertEquals(2, loads)
    }

    @RootHelperDeviceTest
    @Test fun closingAndReopeningThePanelReadsFreshRecords() {
        mount()
        compose.runOnIdle { show = false }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { show = true }
        waitFor("root-clipboard-list")
        assertEquals(2, loads)
    }

    @RootHelperDeviceTest
    @Test fun backgroundingTheViewerClearsTheCachedList() {
        mount()
        menu(R.string.assist_view_full)
        waitFor("assist-viewer-text")
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("root-clipboard-list")
        compose.onNodeWithTag("assist-viewer-text").assertDoesNotExist()
        assertEquals("Returning from the background must read a fresh list", 2, loads)
    }
}

private class PanelStateRecognition : RecognitionEngine {
    override val state = MutableStateFlow(RecognitionState())
    override val model = MutableStateFlow<LocalModelPhase>(LocalModelPhase.Ready)
    override fun prepareLocal() = Unit
    override fun start(cloud: CloudConfiguration?) = error("No recording in menu tests")
    override fun finish() = Unit
    override fun cancel() = Unit
    override fun clearResult() = Unit
    override fun close() = Unit
}
