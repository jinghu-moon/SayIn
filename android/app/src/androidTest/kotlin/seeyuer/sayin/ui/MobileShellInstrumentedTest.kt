package seeyuer.sayin.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.LocalActivityResultRegistryOwner
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.rules.ExternalResource
import java.io.FileInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.OutboxItem
import seeyuer.sayin.designsystem.VoiceLinkTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.File

@RunWith(AndroidJUnit4::class)
class MobileShellInstrumentedTest {
    @Test fun horizontalSwipeAndTabClickShareThePageTransition() {
        val state = ready()
        setTestContent { VoiceLinkTheme { TestVoiceLinkContent(state, TextFieldValue(), {}, {}, { _, _ -> }, {}) } }
        compose.onNodeWithTag("page-Speak").assertExists()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("page-History").assertExists()
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("page-Speak").assertExists()
        compose.onNodeWithTag("nav-Connection").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("page-Connection").assertExists()
        compose.onNodeWithTag("nav-Speak").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("page-Speak").assertExists()
    }

    @Test fun historyUsesOriginalApplicationAndSupportsSearchCopyAndLocalHide() {
        val id = java.util.UUID.randomUUID().toString()
        var state by mutableStateOf(ready().copy(history = listOf(item("injected", id)),
            historyText = mapOf(id to "Original words"),
            historyApplications = mapOf(id to seeyuer.sayin.data.ForegroundApplication("Original Editor")),
            foregroundApplication = seeyuer.sayin.data.ForegroundApplication("Current Browser")))
        val actions = mutableListOf<Pair<String, String>>()
        setTestContent { VoiceLinkTheme { Scaffold {
            MobileHistoryPage(state, action = { request, command ->
                actions += request to command
                if (command == "delete") state = state.copy(history = state.history.map { it.copy(historyHidden = true) })
            })
        } } }
        compose.onNodeWithText("Original Editor").assertExists()
        compose.onNodeWithText("Current Browser").assertDoesNotExist()
        compose.onNodeWithTag("history-search").performTextInput("Original Editor")
        compose.onNodeWithTag("history-text-$id").assertExists()
        compose.onNodeWithTag("history-search").performTextClearance()
        compose.onNodeWithTag("history-copy-$id").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(context.getString(R.string.history_copied)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-more-$id").performClick()
        compose.onNodeWithText(context.getString(R.string.history_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.history_delete_help)).assertExists()
        assertTrue(actions.isEmpty())
        compose.onAllNodesWithText(context.getString(R.string.history_delete)).filter(hasClickAction()).onFirst().performClick()
        compose.onNodeWithTag("history-text-$id").assertDoesNotExist()
        assertEquals(listOf(id to "delete"), actions)
        assertEquals("injected", state.history.single().state)
    }

    @Test fun historyFiltersExposeSelectionStateAndFilterRows() {
        val submitted = java.util.UUID.randomUUID().toString()
        val review = java.util.UUID.randomUUID().toString()
        val state = ready().copy(history = listOf(item("injected", submitted), item("saved", review)),
            historyText = mapOf(submitted to "Submitted words", review to "Review words"))
        setTestContent { VoiceLinkTheme { Scaffold {
            MobileHistoryPage(state, action = { _, _ -> })
        } } }
        compose.onNodeWithTag("history-filter-All").assertIsSelected()
        compose.onNodeWithTag("history-filter-Submitted").assertIsNotSelected()
        compose.onNodeWithTag("history-text-$review").assertExists()
        compose.onNodeWithTag("history-filter-Submitted").performClick()
        compose.onNodeWithTag("history-filter-Submitted").assertIsSelected()
        compose.onNodeWithTag("history-filter-All").assertIsNotSelected()
        compose.onNodeWithTag("history-text-$submitted").assertExists()
        compose.onNodeWithTag("history-text-$review").assertDoesNotExist()
        compose.onNodeWithTag("history-filter-Review").performClick()
        compose.onNodeWithTag("history-filter-Review").assertIsSelected()
        compose.onNodeWithTag("history-text-$review").assertExists()
        compose.onNodeWithTag("history-text-$submitted").assertDoesNotExist()
    }

    @Test fun historyImageFitsCardAndOpensLargerPreviewWithoutSending() {
        val id = java.util.UUID.randomUUID().toString()
        val requests = mutableListOf<Int>()
        val bitmap = Bitmap.createBitmap(1200, 2400, Bitmap.Config.ARGB_8888)
        val state = ready().copy(history = listOf(item("injected", id).copy(kind = "image")))
        setTestContent { VoiceLinkTheme { Scaffold {
            MobileHistoryPage(state, action = { _, _ -> error("Preview cannot send") }, loadImage = { _, edge -> requests += edge; bitmap })
        } } }
        compose.waitUntil(5000) { 256 in requests }
        compose.onNodeWithTag("history-image-$id").assertWidthIsEqualTo(64.dp).assertHeightIsEqualTo(64.dp).performClick()
        compose.waitUntil(5000) { 1600 in requests }
        compose.onNodeWithText(context.getString(R.string.history_submitted_help)).assertExists()
    }
    // MIUI blocks ActivityScenario launches from a background instrumentation process.
    // Bring the debug-only empty host to the foreground through shell before each rule.
    @get:Rule(order = 0) val foregroundHost = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -W -n ${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/androidx.activity.ComponentActivity"
            ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun keepTestActivityVisible() {
        compose.runOnUiThread {
            compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        configureWindow()
    }
    private fun configureWindow() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    @After fun resetKeyboard() {
        compose.runOnUiThread {
            androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var testFocusManager: FocusManager

    @Test fun unconfiguredCloudOpensSetupWithoutRecordingOrSending() {
        var setups = 0
        var sends = 0
        setTestContent {
            VoiceLinkTheme {
                TestSpeakPage(ready(), TextFieldValue("保留草稿"), {}, { sends++ },
                    SpeakInputSource.Cloud, {}, SpeakSendMode.Automatic, {},
                    cloudConfig = null, onConfigureCloud = { setups++ })
            }
        }
        compose.onNodeWithTag("input-source-Cloud").assertDoesNotExist()
        compose.onNodeWithTag("start-cloud-asr").performClick()
        compose.onNodeWithTag("draft").assertTextContains("保留草稿")
        assertEquals(1, setups)
        assertEquals(0, sends)
    }

    @Test fun tabsPreserveDraftAndNeverTriggerTransportActions() {
        var sends = 0
        var actions = 0
        setTestContent {
            var draft by remember { mutableStateOf(TextFieldValue("Draft 原文")) }
            VoiceLinkTheme {
                TestVoiceLinkContent(ClientState(connection = Connection.OFFLINE), draft, { draft = it },
                    { sends++ }, { _, _ -> actions++ }, { Text("Pairing slot") })
            }
        }
        compose.onNodeWithTag("draft").performTextInput(" + edit")
        closeKeyboard()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav-History").fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            screenshot("tabs-after-ime")
            File(context.filesDir, "ui-verification/tabs-after-ime.txt").writeText(
                "keyboardVisible=${keyboardVisible()}\n" + compose.onRoot().printToString())
            throw failure
        }
        for (tab in listOf("History", "Connection", "Settings", "Speak")) {
            compose.onNodeWithTag("nav-$tab").performClick()
            compose.onNodeWithTag("page-$tab").assertIsDisplayed()
        }
        compose.onNodeWithTag("draft").assertTextContains("Draft 原文", substring = true)
        compose.onNodeWithTag("send-draft").assertIsDisplayed().assertIsEnabled()
        assertEquals(0, sends)
        assertEquals(0, actions)
    }

    @Test fun sendRequiresExplicitClickAndReadyReceiver() {
        var sends = 0
        setTestContent {
            VoiceLinkTheme {
                TestVoiceLinkContent(ready(), TextFieldValue("confirm me"), {}, { sends++ }, { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("nav-History").performClick()
        compose.onNodeWithTag("nav-Speak").performClick()
        assertEquals(0, sends)
        compose.onNodeWithTag("send-draft").assertIsDisplayed().performClick()
        assertEquals(1, sends)
    }

    @Test fun uncertainReceiptHasNoRetryAndReviewQueriesOriginalIdentity() {
        val callbacks = mutableListOf<Pair<String, String>>()
        val unknown = item("review", "review-id")
        val uncertain = item("uncertain", "uncertain-id")
        setTestContent {
            VoiceLinkTheme {
                TestVoiceLinkContent(ready().copy(history = listOf(unknown, uncertain)), TextFieldValue(), {}, {},
                    { id, action -> callbacks += id to action }, {})
            }
        }
        compose.onNodeWithTag("nav-History").performClick()
        compose.onAllNodesWithText(context.getString(R.string.retry_same_request)).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.speak_query)).performScrollTo().performClick()
        assertEquals(listOf("review-id" to "query"), callbacks)
        compose.onNodeWithText(context.getString(R.string.uncertain_warning)).performScrollTo().assertIsDisplayed()
    }

    @Test fun productionPagesRenderInBothLanguagesAndThemesWithLargeText() {
        var locale by mutableStateOf("zh-Hans")
        var dark by mutableStateOf(false)
        var fontScale by mutableFloatStateOf(1f)
        setTestContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList.forLanguageTags(locale)) }
            val localized = context.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, fontScale)) {
                VoiceLinkTheme(dark) {
                    TestVoiceLinkContent(ready().copy(history = listOf(item("injected", "12345678"))),
                        TextFieldValue("在手机说话，文字直达电脑。"), {}, {}, { _, _ -> },
                        { Action(R.string.scan_qr, primary = true) {}; Action(R.string.use_pair_code) {} })
                }
            }
        }
        val output = File(context.filesDir, "ui-verification").apply { mkdirs() }
        for (language in listOf("zh-Hans", "en")) {
            for (night in listOf(false, true)) {
                compose.runOnIdle { locale = language; dark = night }
                for (page in listOf("Speak", "History", "Connection", "Settings")) {
                    compose.onNodeWithTag("nav-$page").performClick()
                    compose.onNodeWithTag("page-$page").assertIsDisplayed()
                    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                    File(output, "$language-${if (night) "dark" else "light"}-$page.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
        compose.runOnIdle { fontScale = 1.5f }
        for (page in listOf("Speak", "History", "Connection", "Settings")) {
            compose.onNodeWithTag("nav-$page").performClick()
            compose.onNodeWithTag("page-$page").assertIsDisplayed()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(output, "en-dark-large-$page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (page == "Speak") {
                openRecognitionSettings()
                for (source in listOf(SpeakInputSource.Local, SpeakInputSource.Cloud)) {
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    compose.onNodeWithTag("input-source-label-${source.name}", useUnmergedTree = true)
                        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue("Vertical engine rows may wrap to two complete lines at large text", layouts.single().lineCount in 1..2)
                    val label = layouts.single().layoutInput.text.text
                    for (line in 0 until layouts.single().lineCount - 1) {
                        val end = layouts.single().getLineEnd(line, visibleEnd = true)
                        assertTrue("Wrap only between words", end == label.length || label[end].isWhitespace() || label[end - 1].isWhitespace())
                    }
                    val layout = layouts.single()
                    // Compose may retain a wider paragraph constraint than the final glyph-size box.
                    // Check the actual line ink and ellipsis instead of that spare paragraph width.
                    for (line in 0 until layout.lineCount) {
                        assertTrue("Source label glyphs must stay inside their rendered width",
                            layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                        assertTrue("Source label lines must not be clipped or ellipsized",
                            layout.getLineBottom(line) <= layout.size.height + 1f && !layout.isLineEllipsized(line))
                    }
                    assertTrue("Source labels must fit vertically", !layout.didOverflowHeight)
                }
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
            }
        }
    }

    @Test fun connectionScenariosKeepDraftAndOnlyExplicitReconnectCallsTransport() {
        var state by mutableStateOf(ClientState(connection = Connection.OFFLINE))
        var reconnects = 0
        var sends = 0
        setTestContent {
            var draft by remember { mutableStateOf(TextFieldValue("Offline draft")) }
            VoiceLinkTheme {
                TestVoiceLinkContent(state, draft, { draft = it }, { sends++ }, { _, _ -> }, {},
                    onReconnect = { reconnects++ })
            }
        }
        compose.onNodeWithTag("draft").performTextInput(" edit")
        closeKeyboard()
        compose.onNodeWithTag("send-draft").performClick()
        compose.onNodeWithTag("connection-sheet").assertIsDisplayed()
        assertEquals(0, reconnects)
        assertEquals(0, sends)
        compose.onNodeWithTag("close-connection").performClick()
        compose.runOnIdle { state = ready().copy(connection = Connection.OFFLINE) }
        compose.onNodeWithTag("speak-connect-action").performClick()
        assertEquals(0, reconnects)
        compose.onNodeWithText(context.getString(R.string.reconnect)).performClick()
        assertEquals(1, reconnects)
        compose.runOnIdle { state = ready() }
        compose.onNodeWithTag("close-connection").performClick()
        compose.onNodeWithTag("draft").assertTextContains("Offline draft", substring = true)
        for (blocked in listOf(ready().copy(paused = true), ready().copy(active = false),
            ready().copy(target = null), ready().copy(recovering = true), ready().copy(storageHealthy = false))) {
            compose.runOnIdle { state = blocked }
            compose.onNodeWithTag("send-draft").performClick()
            compose.onNodeWithTag("connection-sheet").assertIsDisplayed()
            assertEquals(0, sends)
            compose.onNodeWithTag("close-connection").performClick()
        }
    }

    @Test fun keyboardKeepsEditorAndSendVisibleWithoutSending() = verifyKeyboardLayout("speak-keyboard")

    @Test fun landscapeKeyboardKeepsEditorAndSendVisibleWithoutSending() {
        compose.runOnUiThread {
            compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        configureWindow()
        verifyKeyboardLayout("speak-keyboard-landscape")
    }

    private fun verifyKeyboardLayout(evidence: String) {
        var sends = 0
        setTestContent {
            // A committed sentence/cursor boundary avoids AOSP LatinIME
            // reopening an existing word as an active composing span on focus.
            // Composition blocking is exercised by the speaking state tests.
            var draft by remember { mutableStateOf(TextFieldValue("Review this text.", TextRange(17))) }
            VoiceLinkTheme {
                TestVoiceLinkContent(ready(), draft, { draft = it }, { sends++ }, { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("open-keyboard").performClick()
        compose.waitUntil(10_000) { keyboardVisible() }
        // Platform visibility changes before animated Compose insets settle.
        // Assert the actual app layout after it has consumed those insets.
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav-Speak").fetchSemanticsNodes().isEmpty() }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            screenshot("$evidence-failure")
            File(context.filesDir, "ui-verification/$evidence-failure.txt").writeText(
                "ime=" + ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime()) +
                    "\n" + compose.onRoot().printToString())
            throw error
        }
        compose.waitForIdle()
        screenshot(evidence)
        File(context.filesDir, "ui-verification/$evidence.txt").writeText(
            "ime=" + ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime()) +
                "\n" + compose.onRoot().printToString())
        compose.onNodeWithTag("nav-Speak").assertDoesNotExist()
        compose.onNodeWithTag("draft").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("send-draft").assertIsDisplayed().assertIsEnabled()
        assertTrue("Editor must fit at least one complete text line with the IME open",
            compose.onNodeWithTag("draft").fetchSemanticsNode().boundsInRoot.height >= 34 * compose.activity.resources.displayMetrics.density)
        assertTrue(compose.onNodeWithTag("draft").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("send-draft").fetchSemanticsNode().boundsInRoot.top)
        screenshot(evidence)
        assertEquals(0, sends)
        compose.onNodeWithTag("send-draft").performClick()
        assertEquals(1, sends)
        closeKeyboard()
        compose.onNodeWithTag("nav-Speak").assertIsDisplayed()
        compose.onNodeWithTag("draft").assertTextEquals("Review this text.")
    }

    @Test fun compactLargeTextAndNormalizationPreviewKeepSendVisible() {
        val original = "first line\n" + "long draft ".repeat(200)
        setTestContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.size(width = 320.dp, height = 440.dp)) {
                    VoiceLinkTheme {
                        TestVoiceLinkContent(ready(), TextFieldValue(original), {}, {}, { _, _ -> }, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("send-draft").assertIsDisplayed().assertIsEnabled()
        screenshot("speak-compact-large-before-preview")
        val editorHeight = compose.onNodeWithTag("draft").fetchSemanticsNode().boundsInRoot.height
        val lineHeight = 34 * 1.5f * compose.activity.resources.displayMetrics.density
        assertTrue("Compact editor must fit a full line at 150% text size ($editorHeight >= $lineHeight)", editorHeight >= lineHeight)
        // At 320dp the fifth tool leaves no room for the optional normalization toggle; the same
        // preview action stays reachable through the existing overflow menu.
        compose.onNodeWithTag("speak-more").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.speak_preview)).performClick()
        compose.onNodeWithTag("send-draft").assertIsDisplayed()
        compose.onNodeWithTag("draft-preview").assertTextContains(original.replace('\n', ' '))
        compose.onNodeWithTag("speak-more").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.speak_edit)).performClick()
        compose.onNodeWithTag("draft").assertTextContains(original)
        assertTrue(compose.onNodeWithTag("draft").fetchSemanticsNode().boundsInRoot.height > 0)
        screenshot("speak-compact-large-preview")
    }

    @Test fun emptyAndOversizedDraftCannotSendButRemainEditable() {
        var draft by mutableStateOf(TextFieldValue())
        setTestContent {
            VoiceLinkTheme { TestVoiceLinkContent(ready(), draft, { draft = it }, {}, { _, _ -> }, {}) }
        }
        compose.onNodeWithTag("draft").assertIsDisplayed()
        compose.onNodeWithTag("send-draft").assertDoesNotExist()
        compose.runOnIdle { draft = TextFieldValue("a".repeat(4001)) }
        compose.onNodeWithTag("send-draft").assertIsNotEnabled()
        compose.runOnIdle { draft = TextFieldValue("a".repeat(4000)) }
        compose.onNodeWithTag("send-draft").assertIsEnabled()
    }

    @Test fun bothSourcesShareDraftSelectionAndSendingWithoutStartingRecording() {
        val original = TextFieldValue("共同的草稿", TextRange(4, 2))
        var draft by mutableStateOf(original)
        var sends = 0
        setTestContent {
            VoiceLinkTheme { TestVoiceLinkContent(ready(), draft, { draft = it }, { sends++ }, { _, _ -> }, {}) }
        }
        compose.onNodeWithTag("input-source-Keyboard").assertDoesNotExist()
        openRecognitionSettings()
        compose.onNodeWithTag("input-source-Local").performClick()
        compose.onNodeWithTag("start-local-asr").assertIsDisplayed()
        compose.onNodeWithTag("draft").assertTextContains(original.text)
        compose.onNodeWithTag("nav-Settings").performClick()
        compose.onNodeWithTag("nav-Speak").performClick()
        openRecognitionSettings()
        compose.onNodeWithTag("input-source-Local").assertIsSelected()
        compose.runOnIdle { currentTestSession.selectSource(SpeakInputSource.Keyboard) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(original, draft); assertEquals(0, sends) }
        compose.onNodeWithTag("send-draft").performClick()
        assertEquals(1, sends)
    }

    @Test fun smartSendRequiresFinishAndDisconnectCancelsWithoutRearming() {
        var state by mutableStateOf(ready())
        var sends = 0
        setTestContent {
            var draft by remember { mutableStateOf(TextFieldValue("运行测试")) }
            VoiceLinkTheme { TestVoiceLinkContent(state, draft, { draft = it }, { sends++ }, { _, _ -> }, {}) }
        }
        compose.onNodeWithTag("speak-more").performClick()
        compose.onNodeWithText(context.getString(R.string.speak_auto_send)).performClick()
        compose.onNodeWithTag("cancel-auto-send").assertDoesNotExist()
        compose.runOnIdle { currentTestSession.completeInput() }
        compose.onNodeWithTag("cancel-auto-send").assertIsDisplayed()
        compose.runOnIdle { state = ready().copy(connection = Connection.OFFLINE) }
        compose.onNodeWithTag("cancel-auto-send").assertDoesNotExist()
        compose.runOnIdle { state = ready() }
        compose.onNodeWithTag("cancel-auto-send").assertDoesNotExist()
        assertEquals(0, sends)
    }

    @Test fun connectionSheetTracksDesktopGateWithoutRemoteResumeOrSending() {
        var state by mutableStateOf(ready())
        var sends = 0
        var reconnects = 0
        setTestContent {
            VoiceLinkTheme {
                TestVoiceLinkContent(state, TextFieldValue("保留草稿"), {}, { sends++ }, { _, _ -> }, {}, { reconnects++ })
            }
        }
        compose.onNodeWithTag("speak-connect-action").performClick()
        compose.onNodeWithTag("connection-sheet").assertIsDisplayed()
        screenshot("connection-ready")
        assertEquals(0, reconnects)
        compose.runOnIdle { state = ready().copy(paused = true, pauseReason = "outcome_review_required") }
        compose.onNodeWithTag("connection-receive-status").assertTextEquals(context.getString(R.string.connection_review_label))
        screenshot("connection-review")
        compose.onNodeWithText(context.getString(R.string.resume_input)).assertDoesNotExist()
        compose.onNodeWithTag("close-connection").performClick()
        compose.onNodeWithTag("draft").assertTextContains("保留草稿")
        assertEquals(0, sends)
    }

    @Test fun pairingCodeIsAlternativeAndWaitsForDesktopApproval() {
        var state by mutableStateOf(ClientState(connection = Connection.OFFLINE))
        var scans = 0
        var codeRequests = 0
        setTestContent {
            VoiceLinkTheme {
                TestVoiceLinkContent(state, TextFieldValue(), {}, {}, { _, _ -> }, {
                    ConnectionActions(state, { scans++ }, { address, port, code ->
                        assertEquals("192.168.1.23", address); assertEquals("7788", port); assertEquals("12345678", code)
                        codeRequests++; state = state.copy(connection = Connection.APPROVAL)
                    }, {}, { _, _ -> }, {})
                })
            }
        }
        compose.onNodeWithTag("speak-connect-action").performClick()
        compose.onNodeWithText(context.getString(R.string.scan_qr)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.use_pair_code)).performClick()
        screenshot("connection-code")
        compose.onNodeWithTag("computer-address").performTextInput("192.168.1.23:7788")
        compose.onNodeWithTag("pairing-code").performTextInput("12345678")
        closeKeyboard()
        compose.onNodeWithText(context.getString(R.string.pair_computer)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.approve_on_computer)).assertIsDisplayed()
        screenshot("connection-approval")
        assertEquals(1, codeRequests); assertEquals(0, scans)
        assertEquals(Connection.APPROVAL, state.connection)
    }

    @Test fun openingConnectionCancelsAutomaticSending() {
        var sends = 0
        setTestContent {
            VoiceLinkTheme { TestVoiceLinkContent(ready(), TextFieldValue("运行测试"), {}, { sends++ }, { _, _ -> }, {}) }
        }
        compose.onNodeWithTag("speak-more").performClick()
        compose.onNodeWithText(context.getString(R.string.speak_auto_send)).performClick()
        compose.runOnIdle { currentTestSession.completeInput() }
        compose.onNodeWithTag("cancel-auto-send").assertIsDisplayed()
        compose.onNodeWithTag("speak-connect-action").performClick()
        compose.onNodeWithTag("close-connection").performClick()
        compose.onNodeWithTag("cancel-auto-send").assertDoesNotExist()
        assertEquals(0, sends)
    }

    private fun openRecognitionSettings() {
        compose.onNodeWithTag("speak-more").performClick()
        compose.onNode(hasText("识别设置") or hasText("Recognition settings")).performClick()
    }

    private lateinit var currentTestSession: SpeakSession

    @Composable
    private fun TestVoiceLinkContent(state: ClientState, draft: TextFieldValue,
        onDraftChange: (TextFieldValue) -> Unit, onSend: () -> Unit,
        onHistoryAction: (String, String) -> Unit, connectionActions: @Composable () -> Unit,
        onReconnect: () -> Unit = {}) {
        val context = LocalContext.current
        val callback by rememberUpdatedState(onDraftChange)
        val session = remember { SpeakSession(context, draft, { callback(it) }).also { it.selectSource(SpeakInputSource.Keyboard) } }
        currentTestSession = session
        val externalDraft by rememberUpdatedState(draft)
        LaunchedEffect(draft) { session.changeDraft(externalDraft) }
        SideEffect { session.onSendReady = onSend }
        DisposableEffect(session) { onDispose { session.close() } }
        val phrases = rememberTestPhraseLibrary()
        VoiceLinkContent(state, session, phrases, onSend, onHistoryAction, connectionActions, onReconnect)
    }

    @Composable
    private fun TestSpeakPage(state: ClientState, draft: TextFieldValue, onDraftChange: (TextFieldValue) -> Unit,
        onSend: () -> Unit, source: SpeakInputSource, onSourceChange: (SpeakInputSource) -> Unit,
        sendMode: SpeakSendMode, onConnect: () -> Unit,
        cloudConfig: seeyuer.sayin.asr.CloudConfiguration? = null, onConfigureCloud: () -> Unit = {}) {
        val context = LocalContext.current
        val session = remember { SpeakSession(context, draft, onDraftChange).also { it.selectSource(source); it.changeSendMode(sendMode) } }
        SideEffect { session.updateReceiver(state); session.onSendReady = onSend }
        DisposableEffect(session) { onDispose { session.close() } }
        SpeakPage(state, session, rememberTestPhraseLibrary(), onSend, onConnect, cloudConfig, onConfigureCloud)
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        compose.setContent {
            testFocusManager = LocalFocusManager.current
            // UI preferences belong to an isolated test namespace, never the user settings.
            val isolated = remember {
                object : android.content.ContextWrapper(compose.activity) {
                    override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("ui-test-$name", mode)
                }.also { it.getSharedPreferences("presentation", 0).edit().clear().commit() }
            }
            CompositionLocalProvider(LocalContext provides isolated,
                LocalActivityResultRegistryOwner provides compose.activity, content = content)
        }
    }

    private fun keyboardVisible() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun closeKeyboard() {
        // A text-input action can enqueue IME show after the test becomes idle.
        // Observe that show before requesting hide, so the two requests cannot race.
        compose.waitUntil(10_000) { keyboardVisible() }
        compose.runOnIdle { testFocusManager.clearFocus(force = true) }
        compose.runOnUiThread {
            androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) { !keyboardVisible() }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val output = File(context.filesDir, "ui-verification").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun ready() = ClientState(connection = Connection.ONLINE, paired = true, address = "192.168.1.23:7788",
        paused = false, target = "scope", active = true)
    private fun item(state: String, id: String) = OutboxItem(id, "host", "device", "scope", null, 0,
        state, null, null, 1794480000000L)
}
