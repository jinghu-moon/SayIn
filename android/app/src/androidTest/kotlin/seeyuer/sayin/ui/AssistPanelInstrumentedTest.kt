package seeyuer.sayin.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import seeyuer.sayin.ui.assist.LocalRootClipboardClient
import seeyuer.sayin.data.RootClipboardClient
import seeyuer.sayin.R
import seeyuer.sayin.asr.CloudConfiguration
import seeyuer.sayin.asr.LocalModelPhase
import seeyuer.sayin.asr.RecognitionEngine
import seeyuer.sayin.asr.RecognitionPhase
import seeyuer.sayin.asr.RecognitionState
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.OutboxItem
import seeyuer.sayin.data.PhraseLibrary
import seeyuer.sayin.data.PhraseLibraryState
import seeyuer.sayin.data.StoredPhrase
import seeyuer.sayin.designsystem.VoiceLinkTheme

/**
 * Behavioral device tests for the clipboard/phrase panel. They mount the production
 * VoiceLinkContent path with an isolated preferences namespace, an in-memory encrypted-store
 * substitute and a fake recognition engine. No daily app package, network or computer is used.
 */
@RunWith(AndroidJUnit4::class)
class AssistPanelInstrumentedTest {
    @get:Rule(order = 0) val foreground = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -W -n ${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/androidx.activity.ComponentActivity"
            ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var session: SpeakSession
    private lateinit var engine: TestRecognition
    private lateinit var library: PhraseLibrary
    private lateinit var storage: MemoryPhraseStorage
    private var originalClip: ClipData? = null
    private var receiver by mutableStateOf(ready())
    private var quick by mutableStateOf(false)
    private var mountGeneration by mutableIntStateOf(0)
    private var sends = 0
    private var helperEndpoint = "SAYIN_TEST_Disabled"
    private var failClipboard = false
    private var layoutWidth = 360.dp
    private var layoutHeight = 680.dp
    private var fontScale = 1f

    @Before fun prepare() {
        receiver = ready()
        quick = false
        mountGeneration = 0
        sends = 0
        failClipboard = false
        layoutWidth = 360.dp
        layoutHeight = 680.dp
        fontScale = 1f
        storage = MemoryPhraseStorage()
        val manager = compose.activity.getSystemService(ClipboardManager::class.java)
        originalClip = manager.primaryClip
        compose.activity.getSharedPreferences("assist-test-presentation", 0).edit().clear().commit()
    }

    @After fun restoreClipboard() {
        val manager = compose.activity.getSystemService(ClipboardManager::class.java) ?: return
        val clip = originalClip
        if (clip == null) manager.clearPrimaryClip() else manager.setPrimaryClip(clip)
    }

    @RootHelperDeviceTest
    @Test fun rootHistoryFullPagePastesWithoutSending() = checkRootHistory(false)
    @RootHelperDeviceTest
    @Test fun rootHistoryQuickCardPastesWithoutSending() = checkRootHistory(true)

    private fun checkRootHistory(asQuick: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("helperSocket").orEmpty()
        assumeTrue(endpoint.startsWith("SAYIN_TEST_"))
        helperEndpoint = endpoint
        quick = asQuick
        mount(TextFieldValue("base:", TextRange(5)))
        openPanel()
        waitForTag("root-clipboard-list")
        compose.onAllNodesWithTag("assist-clipboard-menu").assertCountEquals(3)
        val prefix = args.getString("helperPrefix").orEmpty()
        compose.onAllNodesWithText(prefix, substring = true)[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            assertEquals("base:" + prefix + "3 中文🙂", session.draft.text)
            assertEquals(0, sends)
        }
        openPanel()
        waitForTag("root-clipboard-list")
        val previousLength = session.draft.text.length
        compose.onAllNodesWithTag("assist-clipboard-menu")[0].performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_paste)).performClick()
        compose.waitUntil(5_000) { session.draft.text.length > previousLength }
        compose.onNodeWithTag("assist-panel").assertExists()
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @RootHelperDeviceTest
    @Test fun rootHistoryClearRequiresConfirmation() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("helperSocket").orEmpty()
        assumeTrue(endpoint.startsWith("SAYIN_TEST_"))
        helperEndpoint = endpoint
        mount(TextFieldValue("unchanged"))
        openPanel()
        waitForTag("root-clipboard-list")
        compose.onNodeWithTag("root-clipboard-clear").performClick()
        waitForTag("assist-confirm-cancel")
        compose.onNodeWithTag("assist-confirm-cancel").performClick()
        compose.onAllNodesWithTag("assist-clipboard-menu").assertCountEquals(3)
        compose.onNodeWithTag("root-clipboard-clear").performClick()
        waitForTag("assist-confirm-action")
        compose.onNodeWithTag("assist-confirm-action").performClick()
        waitForTag("root-clipboard-empty")
        compose.runOnIdle { assertEquals("unchanged", session.draft.text); assertEquals(0, sends) }
    }

    @Test fun editorSaveStaysAboveTheKeyboard() {
        mount()
        openPanel()
        compose.onNodeWithTag("assist-tab-phrases").performClick()
        waitForTag("assist-phrases-empty")
        compose.onNodeWithTag("assist-add").performClick()
        waitForFocus("assist-editor-field")
        val decor = compose.activity.window.decorView
        compose.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        val ime = androidx.core.view.ViewCompat.getRootWindowInsets(decor)!!
            .getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
        val rootBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val save = compose.onNodeWithTag("assist-save").fetchSemanticsNode().boundsInRoot
        assertTrue("Save action must stay above the soft keyboard (save=${save.bottom}, imeTop=${rootBottom - ime})",
            save.bottom <= rootBottom - ime)
    }

    @Test fun entryReflectsRecordingAndFrozenDraft() {
        mount(TextFieldValue("ready"))
        compose.waitUntil(5_000) { session.assistEditable }
        compose.onNodeWithTag("open-assist").assertIsEnabled()
        compose.runOnIdle { session.selectSource(SpeakInputSource.Local); session.start(null) }
        compose.waitUntil(5_000) { session.busy }
        compose.onNodeWithTag("open-assist").assertIsNotEnabled()
        compose.runOnIdle { session.cancelRecording() }
        compose.waitUntil(5_000) { !session.busy }
        compose.onNodeWithTag("open-assist").assertIsEnabled()
        compose.runOnIdle { assertTrue(session.markSubmitted()) }
        compose.waitForIdle()
        compose.onNodeWithTag("open-assist").assertIsNotEnabled()
        compose.runOnIdle {
            val id = session.submitted!!.id
            receiver = receiver.copy(history = listOf(OutboxItem(id, "host", "device", "target", null, 0, "failed", null, null, 1L)))
            session.updateReceiver(receiver)
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { session.assistEditable }
        compose.runOnIdle { session.draftRestoring = true }
        compose.waitForIdle()
        compose.onNodeWithTag("open-assist").assertIsNotEnabled()
        compose.runOnIdle { session.draftRestoring = false }
        compose.waitForIdle()
        compose.onNodeWithTag("open-assist").assertIsEnabled()
        assertEquals(0, sends)
    }

    @Test fun clipboardMenuSavesAsPhraseWithoutInserting() {
        mount(TextFieldValue("draft stays"))
        setClip("from clipboard")
        openPanel()
        waitForClipboardRow()
        compose.onNodeWithTag("assist-clipboard-menu").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_save_as_phrase)).performClick()
        waitForFocus("assist-editor-field")
        waitForKeyboard()
        compose.onNodeWithTag("assist-editor-field").assertTextEquals("from clipboard")
        compose.onNodeWithTag("assist-save").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { library.state.value.phrases.any { it.text == "from clipboard" } }
        compose.onNodeWithTag("assist-tab-phrases").assertIsSelected()
        compose.runOnIdle {
            assertEquals("draft stays", session.draft.text)
            assertEquals(0, sends)
        }
    }

    @Test fun openCloseBackScrimAndFocusReturn() {
        mount(TextFieldValue("keep this draft", TextRange(2, 4)))
        openPanel()
        compose.onNodeWithTag("assist-panel").assertExists()
        screenshot("assist-panel-clipboard")
        // Back never edits or sends, and returns accessibility focus to the entry.
        pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("open-assist").fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true }
        }
        compose.onNodeWithTag("open-assist").assertIsFocused()
        compose.runOnIdle { assertEquals(TextFieldValue("keep this draft", TextRange(2, 4)), session.draft); assertEquals(0, sends) }
        // The scrim path closes too.
        openPanel()
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.onAllNodes(isRoot())[roots.lastIndex].performTouchInput { click(Offset(center.x, 6f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(TextFieldValue("keep this draft", TextRange(2, 4)), session.draft) }
    }

    @Test fun clipboardTabMemoryPersistsAcrossOpen() {
        mount()
        openPanel()
        compose.onNodeWithTag("assist-tab-phrases").performClick()
        waitForTag("assist-phrases-empty")
        pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        openPanel()
        compose.onNodeWithTag("assist-tab-phrases").assertIsSelected()
    }

    @Test fun clipboardTextInsertsOnceAndCloses() {
        mount(TextFieldValue("ab", TextRange(1)))
        setClip("建议")
        openPanel()
        waitForClipboardRow()
        compose.onNodeWithTag("assist-clipboard-row").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            assertEquals("a建议b", session.draft.text)
            assertEquals(3, session.draft.selection.start)
            assertEquals(0, sends)
        }
    }

    @Test fun clipboardTextReplacesSelection() {
        mount(TextFieldValue("hello world", TextRange(0, 5)))
        setClip("Hi")
        openPanel()
        waitForClipboardRow()
        compose.onNodeWithTag("assist-clipboard-row").performClick()
        compose.runOnIdle { assertEquals("Hi world", session.draft.text) }
    }

    @Test fun clipboardEmptyNoTextTooLongAndMultiItemStates() {
        mount()
        setClip("")
        openPanel()
        waitForTag("assist-clipboard-empty")
        compose.onNodeWithTag("assist-clear").assertIsNotEnabled()
        pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        setRawClip(ClipData.newRawUri("", Uri.parse("content://seeyuer.sayin.test/image/1")))
        openPanel()
        waitForTag("assist-clipboard-notext")
        pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        setClip("a".repeat(64_001))
        openPanel()
        waitForTag("assist-clipboard-toolong")
        pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        val multiple = ClipData.newPlainText("", "first text")
        multiple.addItem(ClipData.Item("second text"))
        setRawClip(multiple)
        openPanel()
        waitForClipboardRow()
        compose.onNodeWithText("first text").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_multiple_note)).assertExists()
    }

    @Test fun invalidClipboardTextIsRejectedWithReason() {
        mount()
        setClip("bad\u0001text")
        openPanel()
        waitForTag("assist-clipboard-invalid")
        compose.onNodeWithText(compose.activity.getString(R.string.assist_invalid_characters)).assertExists()
        compose.onNodeWithTag("assist-clear").assertIsNotEnabled()
        pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun clipboardClearConfirmCancelAndConfirmStaysInPanel() {
        mount(TextFieldValue("draft stays"))
        setClip("clear me")
        openPanel()
        waitForClipboardRow()
        compose.onNodeWithTag("assist-clear").performClick()
        waitForTag("assist-confirm-cancel")
        compose.onNodeWithTag("assist-confirm-cancel").performClick()
        waitForTag("assist-clipboard-row")
        compose.runOnIdle {
            assertEquals("clear me",
                compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.onNodeWithTag("assist-clear").performClick()
        waitForTag("assist-confirm-action")
        compose.onNodeWithTag("assist-confirm-action").performClick()
        waitForTag("assist-clipboard-empty")
        compose.onNodeWithTag("assist-panel").assertExists()
        compose.runOnIdle { assertEquals("draft stays", session.draft.text); assertEquals(0, sends) }
    }

    @Test fun sensitiveClipboardStaysMaskedUntilExplicitlyShown() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        mount()
        setClip("secret words", sensitive = true)
        openPanel()
        waitForTag("assist-clipboard-sensitive")
        compose.onNodeWithText("secret words").assertDoesNotExist()
        compose.onNodeWithTag("assist-show-sensitive").performClick()
        compose.onNodeWithText("secret words").assertExists()
        compose.onNodeWithTag("assist-clipboard-menu").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_save_as_phrase)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_copy)).assertExists()
    }

    @Test fun unavailableClipboardOffersRetryWithoutChangingDraft() {
        mount(TextFieldValue("unchanged"))
        failClipboard = true
        openPanel()
        waitForTag("assist-clipboard-unavailable")
        compose.onNodeWithTag("assist-empty-action").performClick()
        compose.waitForIdle()
        waitForTag("assist-clipboard-unavailable")
        compose.runOnIdle { assertEquals(TextFieldValue("unchanged"), session.draft) }
    }

    @Test fun pasteKeepsPanelAndEachPasteIsOneUndoStep() {
        mount()
        setClip("X")
        openPanel()
        waitForClipboardRow()
        pasteFromClipboardMenu()
        compose.waitUntil(5_000) { session.draft.text == "X" }
        compose.onNodeWithTag("assist-panel").assertExists()
        pasteFromClipboardMenu()
        compose.waitUntil(5_000) { session.draft.text == "XX" }
        pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { session.undo(); assertEquals("X", session.draft.text); session.undo(); assertEquals("", session.draft.text) }
        compose.onNodeWithTag("draft").assertTextEquals("")
    }

    @Test fun copyKeepsPanelAndDoesNotTouchDraft() {
        mount(TextFieldValue("draft stays"))
        setClip("copy me")
        openPanel()
        waitForClipboardRow()
        compose.onNodeWithTag("assist-clipboard-menu").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_copy)).performClick()
        compose.waitUntil(5_000) {
            compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() == "copy me"
        }
        compose.onNodeWithTag("assist-panel").assertExists()
        compose.runOnIdle { assertEquals("draft stays", session.draft.text) }
    }

    @Test fun phraseAddDuplicateEditDeleteUndoAndClearAll() {
        storage.stored = PhraseLibraryState(3, listOf(StoredPhrase(PHRASE_ID_1, "第一句"), StoredPhrase(PHRASE_ID_2, "第二句")))
        mount()
        openPanel()
        compose.onNodeWithTag("assist-tab-phrases").performClick()
        waitForPhrase("第一句")
        // Add.
        compose.onNodeWithTag("assist-add").performClick()
        waitForFocus("assist-editor-field")
        waitForKeyboard()
        compose.onNodeWithTag("assist-editor-field").performTextInput("第三句")
        compose.onNodeWithTag("assist-save").assertIsEnabled()
        // Geometry above the IME is asserted by editorSaveStaysAboveTheKeyboard. Assertions like
        // isDisplayed are unreliable for the overlay window, so invoke the same action directly.
        compose.onNodeWithTag("assist-save").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { library.state.value.phrases.any { it.text == "第三句" } }
        compose.onNodeWithText("第三句").assertExists()
        // Duplicate keeps the editor and disables save.
        compose.onNodeWithTag("assist-add").performClick()
        waitForFocus("assist-editor-field")
        waitForKeyboard()
        compose.onNodeWithTag("assist-editor-field").performTextInput("第三句")
        compose.onNodeWithTag("assist-save").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_phrase_duplicate)).assertExists()
        compose.onNodeWithTag("assist-back").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("assist-confirm-action")
        compose.onNodeWithTag("assist-confirm-action").performClick()
        compose.waitForIdle()
        // Edit keeps the id and position.
        compose.onAllNodesWithTag("assist-phrase-menu")[0].performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_edit)).performClick()
        waitForFocus("assist-editor-field")
        compose.onNodeWithTag("assist-editor-field").performTextClearance()
        compose.onNodeWithTag("assist-editor-field").performTextInput("改过的第一句")
        compose.onNodeWithTag("assist-save").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { library.state.value.phrases.first().text == "改过的第一句" }
        compose.onNodeWithText("改过的第一句").assertExists()
        // Delete + six-second undo.
        compose.onAllNodesWithTag("assist-phrase-menu")[0].performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_delete)).performClick()
        compose.waitUntil(5_000) { library.state.value.phrases.none { it.text == "改过的第一句" } }
        compose.onNodeWithTag("assist-undo-delete").assertExists()
        compose.onNodeWithTag("assist-undo-delete").performClick()
        compose.waitUntil(5_000) { library.state.value.phrases.any { it.text == "改过的第一句" } }
        // Clear all: cancel (default focus) then confirm.
        compose.onNodeWithTag("assist-clear").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("assist-confirm-cancel").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Focused) == true
            }
        }
        systemBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-confirm-cancel").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("assist-panel").assertExists()
        assertTrue(library.state.value.phrases.isNotEmpty())
        compose.onNodeWithTag("assist-clear").performClick()
        compose.onNodeWithTag("assist-confirm-action").performClick()
        compose.waitUntil(5_000) { library.state.value.phrases.isEmpty() }
        compose.onNodeWithTag("assist-phrases-empty").assertExists()
        compose.runOnIdle { assertEquals("", session.draft.text); assertEquals(0, sends) }
    }

    @Test fun phraseLimitBlocksAddWithoutOpeningEditor() {
        val phrases = (0 until 100).map { StoredPhrase(phraseUuid(it), "phrase $it") }
        storage.stored = PhraseLibraryState(1, phrases)
        mount()
        openPanel()
        compose.onNodeWithTag("assist-tab-phrases").performClick()
        compose.waitUntil(10_000) { library.state.value.phrases.size == 100 }
        compose.onNodeWithTag("assist-add").performClick()
        compose.onNodeWithTag("assist-editor-field").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_phrase_full)).assertExists()
        compose.runOnIdle { assertEquals(100, library.state.value.phrases.size) }
    }

    @Test fun stalePhraseClearRevisionIsRejected() {
        storage.stored = PhraseLibraryState(4, listOf(StoredPhrase(PHRASE_ID_1, "第一句")))
        mount()
        openPanel()
        compose.onNodeWithTag("assist-tab-phrases").performClick()
        waitForPhrase("第一句")
        compose.onNodeWithTag("assist-clear").performClick()
        compose.runOnIdle { runBlocking { library.add("外部新增") } }
        compose.onNodeWithTag("assist-confirm-action").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_changed)).assertExists()
        assertTrue(library.state.value.phrases.any { it.text == "外部新增" })
        compose.onNodeWithTag("assist-phrase-list").assertExists()
    }

    @Test fun pointerAndKeyboardReorderPersistAcrossRemount() {
        storage.stored = PhraseLibraryState(2, listOf(StoredPhrase(PHRASE_ID_1, "One"), StoredPhrase(PHRASE_ID_2, "Two"), StoredPhrase(PHRASE_ID_3, "Three")))
        mount()
        openPanel()
        compose.onNodeWithTag("assist-tab-phrases").performClick()
        waitForPhrase("One")
        compose.onAllNodesWithTag("assist-phrase-grip")[0].performTouchInput {
            down(center)
            moveBy(Offset(0f, 90.dp.toPx()))
            up()
        }
        compose.waitUntil(5_000) { library.state.value.phrases.first().text != "One" }
        val afterPointer = library.state.value.phrases.map { it.text }
        assertNotEquals(listOf("One", "Two", "Three"), afterPointer)
        // A drag that starts on the row body must not reorder anything.
        compose.onAllNodesWithTag("assist-phrase-row")[0].performTouchInput {
            down(center)
            moveBy(Offset(0f, 90.dp.toPx()))
            up()
        }
        compose.waitForIdle()
        assertEquals(afterPointer, library.state.value.phrases.map { it.text })
        // Keyboard up/down from the focused grip.
        val firstGrip = compose.onAllNodesWithTag("assist-phrase-grip")[0]
        firstGrip.performSemanticsAction(SemanticsActions.RequestFocus)
        firstGrip.performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitUntil(5_000) { library.state.value.phrases.map { it.text } != afterPointer }
        val afterKeyboard = library.state.value.phrases.map { it.text }
        // Remount reads the persisted order back through the same storage.
        compose.runOnIdle { mountGeneration++ }
        compose.waitUntil(10_000) { storage.stored?.phrases?.map { it.text } == afterKeyboard }
        compose.waitUntil(10_000) { library.ready.value && library.state.value.phrases.map { it.text } == afterKeyboard }
    }

    @Test fun panelControlsMeet48DpAndHaveDescriptions() {
        storage.stored = PhraseLibraryState(1, listOf(StoredPhrase(PHRASE_ID_1, "One")))
        mount()
        compose.onNodeWithTag("open-assist").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        openPanel()
        compose.onNodeWithTag("assist-tab-clipboard").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("assist-close").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("assist-tab-phrases").performClick()
        waitForPhrase("One")
        compose.onNodeWithTag("assist-add").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithTag("assist-phrase-grip")[0].assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithTag("assist-phrase-grip")[0].assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        compose.onAllNodesWithTag("assist-phrase-menu")[0].assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithTag("assist-phrase-menu")[0].performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_copy)).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(compose.activity.getString(R.string.assist_delete)).assertHeightIsAtLeast(48.dp)
    }

    @Test fun narrowAndLargeFontLayoutKeepsControlsReachable() {
        layoutWidth = 320.dp
        layoutHeight = 640.dp
        fontScale = 2f
        mount()
        openPanel()
        compose.onNodeWithTag("assist-close").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("assist-tab-clipboard").assertIsDisplayed()
        compose.onNodeWithTag("assist-clear").assertIsDisplayed()
        val panel = compose.onNodeWithTag("assist-panel").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(panel.bottom <= root.bottom)
        assertTrue(panel.top >= root.top)
    }

    @Test fun quickCardUsesTheSamePanel() {
        quick = true
        layoutHeight = 400.dp
        mount()
        setClip("quick")
        openPanel()
        waitForClipboardRow()
        compose.onNodeWithTag("assist-clipboard-row").performClick()
        compose.runOnIdle { assertEquals("quick", session.draft.text) }
    }

    @Test fun openingConnectionSheetClosesPanel() {
        mount()
        setClip("text")
        openPanel()
        compose.onNodeWithTag("speak-connect-action").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("connection-sheet").assertExists()
    }

    @Test fun openingImageSheetClosesPanel() {
        mount()
        setClip("text")
        openPanel()
        compose.onNodeWithTag("choose-image").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("image-input").assertExists()
    }

    @Test fun draftChangedWhilePanelOpenRejectsStaleInsertion() {
        mount(TextFieldValue("old"))
        setClip("insert")
        openPanel()
        waitForClipboardRow()
        compose.runOnIdle { session.changeDraft(TextFieldValue("changed elsewhere")) }
        compose.onNodeWithTag("assist-clipboard-row").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isEmpty() }
        waitForTag("assist-dismiss-notice")
        compose.runOnIdle { assertEquals("changed elsewhere", session.draft.text) }
    }

    @Test fun fallbackMenuItemOpensPanelWhenToolsAreHidden() {
        // Double text size pushes the page into the minimal layout where the secondary tool row is
        // gone, so only the overflow fallback can reach the panel.
        layoutHeight = 300.dp
        fontScale = 2f
        mount()
        compose.waitUntil(5_000) { session.assistEditable }
        compose.onNodeWithTag("open-assist").assertDoesNotExist()
        compose.onNodeWithTag("speak-more").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_open)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun mount(initial: TextFieldValue = TextFieldValue()) {
        val size = layoutWidth to layoutHeight
        val scale = fontScale
        // Match the production host: MainActivity is edge-to-edge, so the IME is reported as a
        // window inset rather than only resizing the window.
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.waitForIdle()
        compose.setContent {
            val baseDensity = LocalDensity.current
            val isolated = remember {
                object : ContextWrapper(compose.activity) {
                    override fun getSharedPreferences(name: String, mode: Int) =
                        super.getSharedPreferences("assist-test-$name", mode)

                    override fun getSystemService(name: String): Any? {
                        if (failClipboard && name == Context.CLIPBOARD_SERVICE) throw IllegalStateException("clipboard unavailable")
                        return super.getSystemService(name)
                    }
                }
            }
            CompositionLocalProvider(LocalRootClipboardClient provides RootClipboardClient(helperEndpoint),
                LocalContext provides isolated,
                LocalDensity provides Density(baseDensity.density, scale),
            ) {
                VoiceLinkTheme {
                    Box(Modifier.size(size.first, size.second)) {
                        key(mountGeneration) {
                            val fake = remember { TestRecognition().also { engine = it } }
                            val retained = remember {
                                SpeakSession(isolated, initial, recognition = fake).also {
                                    session = it
                                    it.selectSource(SpeakInputSource.Keyboard)
                                    it.updateReceiver(receiver)
                                    it.onSendReady = { sends++ }
                                }
                            }
                            DisposableEffect(retained) { onDispose { retained.close() } }
                            val phraseLibrary = rememberTestPhraseLibrary(storage)
                            SideEffect { library = phraseLibrary }
                            VoiceLinkContent(receiver, retained, phraseLibrary, { sends++ }, { _, _ -> }, {}, quick = quick)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openPanel() {
        compose.waitUntil(5_000) { session.assistEditable }
        // The previous overlay's window must hand input focus back before touch injection reaches
        // the entry; otherwise the synthetic tap is dropped with no error.
        compose.waitUntil(5_000) { compose.activity.window.decorView.hasWindowFocus() }
        compose.waitForIdle()
        android.os.SystemClock.sleep(150)
        compose.waitForIdle()
        compose.onNodeWithTag("open-assist").assertIsEnabled()
        compose.onNodeWithTag("open-assist").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForKeyboard() {
        val decor = compose.activity.window.decorView
        compose.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        compose.waitForIdle()
    }

    private fun waitForFocus(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true }
        }
    }

    private fun waitForClipboardRow() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("assist-clipboard-row").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForPhrase(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("assist-phrase-row").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Text)?.joinToString("")?.contains(text) == true
            }
        }
    }

    private fun pasteFromClipboardMenu() {
        compose.onNodeWithTag("assist-clipboard-menu").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.assist_paste)).performClick()
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
    }

    private fun setClip(text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText("", text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        setRawClip(clip)
    }

    private fun setRawClip(clip: ClipData) {
        compose.runOnIdle {
            compose.activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }
        compose.waitForIdle()
        android.os.SystemClock.sleep(30)
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "ui-verification").apply { mkdirs() }
        val target = if (compose.onAllNodesWithTag("assist-panel").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("assist-panel")
        } else {
            compose.onRoot()
        }
        File(dir, "$name.png").outputStream().use {
            target.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    companion object {
        private val PHRASE_ID_1 = phraseUuid(1)
        private val PHRASE_ID_2 = phraseUuid(2)
        private val PHRASE_ID_3 = phraseUuid(3)

        private fun phraseUuid(@Suppress("UNUSED_PARAMETER") seed: Int): String =
            java.util.UUID.randomUUID().toString()

        private fun ready() = ClientState(
            connection = Connection.ONLINE, paired = true, address = "127.0.0.1:7788",
            paused = false, target = "target", active = true, revision = "1",
        )
    }
}

private class TestRecognition : RecognitionEngine {
    override val state = MutableStateFlow(RecognitionState())
    override val model = MutableStateFlow(LocalModelPhase.Ready)
    var starts = 0

    override fun prepareLocal() {
        if (model.value != LocalModelPhase.Ready) model.value = LocalModelPhase.Loading
    }

    override fun start(cloud: CloudConfiguration?) {
        starts++
        state.value = RecognitionState(starts.toLong(), RecognitionPhase.Loading)
    }

    fun emit(phase: RecognitionPhase, text: String, finished: Boolean = false) {
        state.value = RecognitionState(starts.toLong(), phase, text, finished = finished)
    }

    override fun finish() { state.value = state.value.copy(phase = RecognitionPhase.Finishing) }
    override fun cancel() { state.value = state.value.copy(phase = RecognitionPhase.Idle, finished = false) }
    override fun clearResult() { state.value = RecognitionState() }
    override fun close() = Unit
}
