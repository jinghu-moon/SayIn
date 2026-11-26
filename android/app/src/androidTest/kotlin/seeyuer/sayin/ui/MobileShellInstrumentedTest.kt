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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.LocalActivityResultRegistryOwner
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
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
import seeyuer.sayin.asr.CloudConfigurationStore
import seeyuer.sayin.asr.CloudProvider
import seeyuer.sayin.asr.CloudSettings
import seeyuer.sayin.asr.CloudSettingsStatus
import seeyuer.sayin.data.OutboxKey
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.ForegroundApplication
import seeyuer.sayin.data.OutboxItem
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.ui.settings.DraftToolbarPage
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import java.io.File

@RunWith(AndroidJUnit4::class)
class MobileShellInstrumentedTest {
    @Test fun cloudServiceDropdownSwitchesCredentialFieldsAndPersistsSelection() {
        val store = CloudConfigurationStore(context)
        val original = store.load()
        val settings = compose.runOnUiThread { CloudSettings(context.applicationContext as android.app.Application) }
        try {
            setTestContent {
                val state by settings.state.collectAsState()
                val editor = remember(state.profiles) { CloudEditorState(state.profiles) }
                VoiceLinkTheme { CloudSettingsCard(editor, settings, state) }
            }
            compose.waitUntil(5_000) { settings.state.value.status != CloudSettingsStatus.Loading }
            compose.runOnIdle { settings.select(CloudProvider.Doubao) }
            compose.waitUntil(5_000) { !settings.state.value.busy && settings.state.value.profiles.selected == CloudProvider.Doubao }
            compose.onNodeWithTag("cloud-app-key").assertExists()
            compose.onNodeWithTag("cloud-provider").performClick()
            compose.onNode(hasText(context.getString(R.string.cloud_qwen)) and hasClickAction()).performClick()
            compose.waitUntil(5_000) { settings.state.value.profiles.selected == CloudProvider.Qwen }
            compose.onNodeWithTag("cloud-qwen-workspace").assertExists()
            compose.onNodeWithTag("cloud-qwen-key").assertExists()
            compose.onNodeWithTag("cloud-app-key").assertDoesNotExist()
            assertEquals(CloudProvider.Qwen, store.load()?.selected)
        } finally {
            if (original == null) store.clear() else store.save(original)
        }
    }

    @Test fun tabClicksSwitchPagesWithoutTransportActions() {
        val state = ready()
        setTestContent { VoiceLinkTheme { TestVoiceLinkContent(state, TextFieldValue(), {}, {}, { _, _ -> }, {}) } }
        compose.onNodeWithTag("page-Speak").assertExists()
        compose.onNodeWithTag("nav-History").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("page-History").assertExists()
        compose.onNodeWithTag("nav-Speak").performClick()
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
            historyText = mapOf(OutboxKey("host", "device", id) to "Original words"),
            historyApplications = mapOf(OutboxKey("host", "device", id) to seeyuer.sayin.data.ForegroundApplication("Original Editor")),
            foregroundApplication = seeyuer.sayin.data.ForegroundApplication("Current Browser")))
        val actions = mutableListOf<Pair<OutboxKey, String>>()
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
        compose.onNodeWithTag("history-confirm-delete").performClick()
        compose.onNodeWithTag("history-text-$id").assertDoesNotExist()
        assertEquals(listOf(OutboxKey("host", "device", id) to "delete"), actions)
        assertEquals("injected", state.history.single().state)
    }

    @Test fun historyFiltersExposeSelectionStateAndFilterRowsWithTheSameRequestId() {
        val id = java.util.UUID.randomUUID().toString()
        val submitted = item("injected", id)
        val review = item("saved", id).copy(hostId = "second-host", deviceId = "second-device")
        val state = ready().copy(history = listOf(submitted, review),
            historyText = mapOf(submitted.key to "Submitted words", review.key to "Review words"))
        setTestContent { VoiceLinkTheme { Scaffold {
            MobileHistoryPage(state, action = { _, _ -> })
        } } }
        fun tab(label: Int) = compose.onNode(hasText(context.getString(label)) and hasClickAction())
        tab(R.string.history_all).assertIsSelected()
        tab(R.string.history_submitted).assertIsNotSelected()
        compose.onNodeWithText("Submitted words").assertExists()
        compose.onNodeWithText("Review words").assertExists()
        tab(R.string.history_submitted).performClick()
        tab(R.string.history_submitted).assertIsSelected()
        tab(R.string.history_all).assertIsNotSelected()
        compose.onNodeWithText("Submitted words").assertExists()
        compose.onNodeWithText("Review words").assertDoesNotExist()
        tab(R.string.history_attention).performClick()
        tab(R.string.history_attention).assertIsSelected()
        compose.onNodeWithText("Review words").assertExists()
        compose.onNodeWithText("Submitted words").assertDoesNotExist()
    }

    @Test fun historyDateAndApplicationFiltersComposeWithBulkDeleteAndExport() {
        val now = System.currentTimeMillis()
        val todayId = java.util.UUID.randomUUID().toString()
        val oldId = java.util.UUID.randomUUID().toString()
        val state = ready().copy(
            history = listOf(
                item("injected", todayId).copy(createdAt = now),
                item("injected", oldId).copy(createdAt = now - 10L * 24 * 60 * 60 * 1000),
            ),
            historyText = mapOf(OutboxKey("host", "device", todayId) to "Today words", OutboxKey("host", "device", oldId) to "Old words"),
            historyApplications = mapOf(
                OutboxKey("host", "device", todayId) to seeyuer.sayin.data.ForegroundApplication("Chat"),
                OutboxKey("host", "device", oldId) to seeyuer.sayin.data.ForegroundApplication("Editor"),
            ),
        )
        val hidden = mutableListOf<List<OutboxKey>>()
        setTestContent { VoiceLinkTheme { Scaffold {
            MobileHistoryPage(state, action = { _, _ -> }, onHideHistory = { hidden += it })
        } } }
        compose.onNodeWithTag("history-filter-open").performClick()
        compose.onNodeWithTag("history-filter-sheet").assertExists()
        compose.onNodeWithTag("history-app-Editor").performClick()
        compose.onNodeWithTag("history-filter-apply").performClick()
        compose.onNodeWithTag("history-active-app").assertExists()
        compose.onNodeWithTag("history-text-$oldId").assertExists()
        compose.onNodeWithTag("history-text-$todayId").assertDoesNotExist()
        compose.onNodeWithTag("history-filter-open").performClick()
        compose.onNodeWithTag("history-app-all").performClick()
        compose.onNode(hasText(context.getString(R.string.history_date_today)) and hasClickAction()).performClick()
        compose.onNodeWithTag("history-filter-apply").performClick()
        compose.onNodeWithTag("history-active-date").assertExists()
        compose.onNodeWithTag("history-text-$todayId").assertExists()
        compose.onNodeWithTag("history-text-$oldId").assertDoesNotExist()
        compose.onNodeWithTag("history-filter-open").performClick()
        compose.onNodeWithTag("history-app-all").performClick()
        compose.onNode(hasText(context.getString(R.string.history_date_all)) and hasClickAction()).performClick()
        compose.onNodeWithTag("history-filter-apply").performClick()
        compose.onNodeWithTag("history-top-more").performClick()
        compose.onNodeWithText(context.getString(R.string.history_manage)).performClick()
        compose.onNodeWithTag("history-select-all").performClick()
        compose.onNodeWithTag("history-delete-selected").performClick()
        compose.onNodeWithText(context.getString(R.string.history_delete_multiple_help, 2)).assertExists()
        compose.onNodeWithTag("history-confirm-delete").performClick()
        assertEquals(listOf(listOf(OutboxKey("host", "device", todayId), OutboxKey("host", "device", oldId))), hidden)
    }

    @Test fun historyExportSheetRequiresALongEnoughBackupPassphrase() {
        val id = java.util.UUID.randomUUID().toString()
        val state = ready().copy(history = listOf(item("injected", id)), historyText = mapOf(OutboxKey("host", "device", id) to "Export me"))
        setTestContent { VoiceLinkTheme { Scaffold {
            MobileHistoryPage(state, action = { _, _ -> })
        } } }
        compose.onNodeWithTag("history-top-more").performClick()
        compose.onNodeWithText(context.getString(R.string.history_export)).performClick()
        compose.onNodeWithText(context.getString(R.string.history_export_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.history_export_encrypted)).performClick()
        compose.onNodeWithTag("history-passphrase").assertExists()
        compose.onNodeWithText(context.getString(R.string.history_export_encrypted_start)).performClick()
        compose.onNodeWithText(context.getString(R.string.history_export_passphrase_short)).assertExists()
    }

    @Test fun settingsExposeModelManagementAndFloatingButtonOptions() {
        val modelRoot = File(context.cacheDir, "model-management-${java.util.UUID.randomUUID()}")
        val unbundled = seeyuer.sayin.translation.TranslationCatalog.read(context).defaultModel.copy(bundled = false)
        setTestContent(modelRoot) {
            VoiceLinkTheme { TestVoiceLinkContent(ready(), TextFieldValue(), {}, {}, { _, _ -> }, {}, translationModel = unbundled) }
        }
        compose.onNodeWithTag("nav-Settings").performClick()
        compose.onNodeWithText(context.getString(R.string.settings_voice_recognition)).performClick()
        selectRecognitionEngine(SpeakInputSource.Local)
        compose.onNodeWithTag("local-model-card-x-asr").assertExists()
        compose.onNodeWithTag("local-model-files-toggle-x-asr").assertDoesNotExist()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithText(context.getString(R.string.settings_translation)).performClick()
        compose.onNodeWithTag("local-model-card-hy-mt2-1.8b-q4").assertDoesNotExist()
        compose.onNodeWithTag("local-model-get").performClick()
        compose.onNodeWithTag("settings-page-LocalModels").assertExists()
        compose.onNodeWithTag("local-model-manage-hy-mt2-1.8b-q4").assertIsDisplayed().performClick()
        compose.onNodeWithTag("local-model-files-hy-mt2-1.8b-q4").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_model_scope)).assertExists()
        compose.onNodeWithTag("local-model-files-toggle-hy-mt2-1.8b-q4").performClick()
        compose.onNodeWithTag("local-model-files-hy-mt2-1.8b-q4").assertTextContains("Hy-MT2-1.8B-Q4_K_M.gguf", substring = true)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("local-model-manage-x-asr").performScrollTo().performClick()
        compose.onNodeWithTag("local-model-files-toggle-x-asr").performClick()
        compose.onNodeWithTag("local-model-files-x-asr").assertTextContains("encoder.onnx", substring = true)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag("settings-page-Translation").assertExists()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithText(context.getString(R.string.settings_floating_ball)).performClick()
        compose.onNodeWithTag("quick-ball").assertExists()
        compose.onNodeWithTag("ball-size").assertExists()
        compose.onNodeWithTag("ball-opacity").assertExists()
    }

    @Test fun modelSettingsSwitchOnlyAmongImportedModels() {
        val modelRoot = File(context.cacheDir, "model-selection-${java.util.UUID.randomUUID()}")
        val catalog = seeyuer.sayin.translation.TranslationCatalog.read(context)
        val default = catalog.defaultModel
        val other = catalog.models.first { it.id != default.id }
        try {
            // Sparse files exercise the installed-model inventory without loading native weights.
            for (model in listOf(default, other)) {
                val directory = File(modelRoot, "local-models/${model.id}").apply { mkdirs() }
                java.io.RandomAccessFile(File(directory, model.fileName), "rw").use { it.setLength(model.bytes) }
            }
            setTestContent(modelRoot) {
                VoiceLinkTheme { TestVoiceLinkContent(ready(), TextFieldValue(), {}, {}, { _, _ -> }, {}) }
            }
            compose.onNodeWithTag("nav-Settings").performClick()
            compose.onNodeWithText(context.getString(R.string.settings_translation)).performClick()
            compose.onNodeWithTag("local-model-card-${default.id}").assertExists()
            compose.onNodeWithTag("local-model-catalog").performClick()
            compose.onNodeWithTag("local-model-use-${default.id}").assertExists()
            compose.onNodeWithTag("local-model-use-${other.id}").assertExists()
            catalog.models.filter { it != default && it != other }.forEach {
                compose.onNodeWithTag("local-model-use-${it.id}").assertDoesNotExist()
            }
            compose.onNodeWithTag("local-model-use-${other.id}").performClick()
            compose.onNodeWithTag("local-model-card-${other.id}").assertExists()
            compose.onNodeWithTag("local-model-card-${default.id}").assertDoesNotExist()
        } finally {
            modelRoot.deleteRecursively()
        }
    }

    @Test fun sharedTextLoadsIntoAnEmptyDraft() {
        var draft by mutableStateOf(TextFieldValue())
        setTestContent {
            VoiceLinkTheme { TestVoiceLinkContent(ready(), draft, { draft = it }, {}, { _, _ -> }, {},
                sharedText = "来自浏览器的分享", shareVersion = 1) }
        }
        compose.waitUntil(5_000) { draft.text == "来自浏览器的分享" }
        compose.onNodeWithTag("draft").assertTextContains("来自浏览器的分享")
    }

    @Test fun sharedTextAsksBeforeReplacingExistingDraft() {
        var draft by mutableStateOf(TextFieldValue("现有草稿"))
        setTestContent {
            VoiceLinkTheme { TestVoiceLinkContent(ready(), draft, { draft = it }, {}, { _, _ -> }, {},
                sharedText = "新的分享文本", shareVersion = 1) }
        }
        compose.onNodeWithText(context.getString(R.string.share_text_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.share_text_replace)).performClick()
        compose.waitUntil(5_000) { draft.text == "新的分享文本" }
        compose.onNodeWithTag("draft").assertTextContains("新的分享文本")
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
        val callbacks = mutableListOf<Pair<OutboxKey, String>>()
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
        assertEquals(listOf(unknown.key to "query"), callbacks)
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
                compose.onNodeWithTag("settings-page-Recognition").assertIsDisplayed()
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
        compose.waitForIdle()
        screenshot(evidence)
        File(context.filesDir, "ui-verification/$evidence.txt").writeText(
            "ime=" + ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime()) +
                "\n" + compose.onRoot().printToString())
        compose.onNodeWithTag("nav-Speak").assertExists()
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

    @Test fun compactLargeTextKeepsSendVisibleAndEditable() {
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
        val editorHeight = compose.onNodeWithTag("draft").fetchSemanticsNode().boundsInRoot.height
        val lineHeight = 34 * 1.5f * compose.activity.resources.displayMetrics.density
        assertTrue("Compact editor must fit a full line at 150% text size ($editorHeight >= $lineHeight)", editorHeight >= lineHeight)
        screenshot("speak-compact-large")
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
        selectRecognitionEngine(SpeakInputSource.Local)
        compose.onNodeWithText(context.getString(R.string.settings_engine_local)).assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-Speak").performClick()
        compose.onNodeWithTag("start-local-asr").assertIsDisplayed()
        compose.onNodeWithTag("draft").assertTextContains(original.text)
        openRecognitionSettings()
        compose.onNodeWithText(context.getString(R.string.settings_engine_local)).assertExists()
        compose.runOnIdle { currentTestSession.selectSource(SpeakInputSource.Keyboard) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-Speak").performClick()
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
        enableAutomaticSending()
        compose.onNodeWithTag("cancel-auto-send").assertDoesNotExist()
        compose.onNodeWithTag("send-draft").performClick()
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
        enableAutomaticSending()
        compose.onNodeWithTag("send-draft").performClick()
        compose.onNodeWithTag("cancel-auto-send").assertIsDisplayed()
        compose.onNodeWithTag("speak-connect-action").performClick()
        compose.onNodeWithTag("close-connection").performClick()
        compose.onNodeWithTag("cancel-auto-send").assertDoesNotExist()
        assertEquals(0, sends)
    }

    @Test fun draftToolbarUsesSavedOrderAndVisibility() {
        val original = DraftTools.entriesFor(DraftToolSurface.Page).value
        try {
            val order = listOf(DraftTool.Clear) + original.map { it.tool }.filter { it != DraftTool.Clear }
            DraftTools.replace(context, DraftToolSurface.Page, order.map { DraftToolEntry(it, it != DraftTool.Image) })
            setTestContent {
                VoiceLinkTheme {
                    TestSpeakPage(ready(), TextFieldValue("保留草稿"), {}, {}, SpeakInputSource.Keyboard, {},
                        SpeakSendMode.Manual, {})
                }
            }
            compose.onNodeWithTag("choose-image").assertDoesNotExist()
            compose.onNodeWithTag("clear-draft").assertExists()
            val clearLeft = compose.onNodeWithTag("clear-draft").fetchSemanticsNode().boundsInRoot.left
            val assistLeft = compose.onNodeWithTag("open-assist").fetchSemanticsNode().boundsInRoot.left
            assertTrue("saved order must place Clear left of Assist", clearLeft < assistLeft)
            compose.onNodeWithTag("draft").assertTextContains("保留草稿")
        } finally {
            DraftTools.replace(context, DraftToolSurface.Page, original)
        }
    }

    @Test fun shortDraftToolRowFollowsTheSideChoice() {
        val originalEntries = DraftTools.entriesFor(DraftToolSurface.Page).value
        val originalSide = DraftTools.sideFor(DraftToolSurface.Page).value
        try {
            DraftTools.replace(context, DraftToolSurface.Page, DraftTools.entriesFor(DraftToolSurface.Page).value.map {
                DraftToolEntry(it.tool, it.tool in setOf(DraftTool.Assist, DraftTool.Image, DraftTool.Clear))
            })
            DraftTools.setSide(context, DraftToolSurface.Page, DraftToolSide.Start)
            setTestContent {
                VoiceLinkTheme {
                    TestSpeakPage(ready(), TextFieldValue("保留草稿"), {}, {}, SpeakInputSource.Keyboard, {},
                        SpeakSendMode.Manual, {})
                }
            }
            val startLeft = compose.onNodeWithTag("open-assist").fetchSemanticsNode().boundsInRoot.left
            screenshot("draft-toolbar-short-start")
            compose.runOnIdle { DraftTools.setSide(context, DraftToolSurface.Page, DraftToolSide.End) }
            compose.waitForIdle()
            val endLeft = compose.onNodeWithTag("open-assist").fetchSemanticsNode().boundsInRoot.left
            screenshot("draft-toolbar-short-end")
            assertTrue("an end-aligned short row moves the first tool to the other edge", endLeft > startLeft + 100f)
        } finally {
            DraftTools.replace(context, DraftToolSurface.Page, originalEntries)
            DraftTools.setSide(context, DraftToolSurface.Page, originalSide)
        }
    }

    @Test fun draftToolbarSettingsTogglesVisibilityAndRestoresDefaults() {
        val original = DraftTools.entriesFor(DraftToolSurface.Page).value
        val originalSide = DraftTools.sideFor(DraftToolSurface.Page).value
        val originalQuick = DraftTools.entriesFor(DraftToolSurface.Quick).value
        val originalQuickSide = DraftTools.sideFor(DraftToolSurface.Quick).value
        try {
            DraftTools.reset(context, DraftToolSurface.Page)
            setTestContent { VoiceLinkTheme { DraftToolbarPage(Modifier.fillMaxSize()) } }
            compose.onNodeWithTag("settings-draft-toolbar-side").assertDoesNotExist()
            fun toggleOff(id: String) {
                val tag = "draft-tool-visible-$id"
                compose.onNodeWithTag("settings-draft-toolbar").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).performClick()
            }
            // Ten tools are visible after reset, so seven switches must go off to reach three.
            listOf("copy", "translate", "polish", "send_mode", "engine", "save_phrase", "share").forEach(::toggleOff)
            compose.waitForIdle()
            assertEquals(3, DraftTools.entriesFor(DraftToolSurface.Page).value.count { it.visible })
            assertFalse(DraftTools.entriesFor(DraftToolSurface.Page).value.first { it.tool == DraftTool.Copy }.visible)
            compose.onNodeWithTag("settings-draft-toolbar").performScrollToNode(hasTestTag("settings-draft-toolbar-side"))
            compose.onNodeWithTag("settings-draft-toolbar-side").assertExists()
            screenshot("draft-toolbar-settings")
            compose.onNodeWithText(context.getString(R.string.settings_draft_toolbar_side_right)).performClick()
            compose.waitForIdle()
            assertEquals(DraftToolSide.End, DraftTools.sideFor(DraftToolSurface.Page).value)
            compose.onNodeWithTag("settings-draft-toolbar").performScrollToNode(hasTestTag("settings-draft-toolbar-reset"))
            compose.onNodeWithTag("settings-draft-toolbar-reset").performClick()
            compose.waitForIdle()
            assertTrue(DraftTools.entriesFor(DraftToolSurface.Page).value.all { it.visible })
            assertEquals(DraftTool.entries.map { it.id }, DraftTools.entriesFor(DraftToolSurface.Page).value.map { it.tool.id })
            assertEquals(DraftToolSide.Start, DraftTools.sideFor(DraftToolSurface.Page).value)
            compose.onNodeWithTag("settings-draft-toolbar-side").assertDoesNotExist()

            // The quick card keeps its own profile; editing it must not touch the page list.
            val pageBeforeQuickEdit = DraftTools.entriesFor(DraftToolSurface.Page).value
            compose.onNodeWithTag("settings-draft-toolbar").performScrollToNode(hasTestTag("settings-draft-toolbar-surface"))
            compose.onNodeWithText(context.getString(R.string.settings_draft_toolbar_surface_quick)).performClick()
            compose.waitForIdle()
            assertEquals(5, DraftTools.entriesFor(DraftToolSurface.Quick).value.count { it.visible })
            compose.waitForIdle()
            compose.onNodeWithTag("settings-draft-toolbar").performScrollToNode(hasTestTag("draft-tool-visible-copy"))
            compose.onNodeWithTag("draft-tool-visible-copy").performClick()
            compose.waitForIdle()
            assertFalse(DraftTools.entriesFor(DraftToolSurface.Quick).value.first { it.tool == DraftTool.Copy }.visible)
            assertEquals(pageBeforeQuickEdit, DraftTools.entriesFor(DraftToolSurface.Page).value)
            compose.onNodeWithTag("settings-draft-toolbar").performScrollToNode(hasTestTag("settings-draft-toolbar-reset"))
            compose.onNodeWithTag("settings-draft-toolbar-reset").performClick()
            compose.waitForIdle()
            assertEquals(defaultQuickDraftTools(), DraftTools.entriesFor(DraftToolSurface.Quick).value)
            assertEquals(DraftToolSide.Start, DraftTools.sideFor(DraftToolSurface.Quick).value)
            screenshot("draft-toolbar-quick-settings")
        } finally {
            DraftTools.replace(context, DraftToolSurface.Page, original)
            DraftTools.setSide(context, DraftToolSurface.Page, originalSide)
            DraftTools.replace(context, DraftToolSurface.Quick, originalQuick)
            DraftTools.setSide(context, DraftToolSurface.Quick, originalQuickSide)
        }
    }

    @Test fun sendModeShortcutChangesTheSessionPolicy() {
        val original = DraftTools.entriesFor(DraftToolSurface.Page).value
        try {
            DraftTools.reset(context, DraftToolSurface.Page)
            setTestContent {
                VoiceLinkTheme {
                    Scaffold {
                        TestSpeakPage(ready(), TextFieldValue("保留草稿"), {}, {}, SpeakInputSource.Keyboard, {},
                            SpeakSendMode.Manual, {})
                    }
                }
            }
            compose.onNodeWithTag("send-mode").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("send-mode-tabs").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("send-mode-tabs").assertExists()
            compose.onNodeWithText(context.getString(R.string.settings_policy_auto)).performClick()
            compose.waitForIdle()
            assertEquals(SpeakSendMode.Automatic, currentTestSession.sendMode)
            screenshot("draft-toolbar-send-mode")
        } finally {
            DraftTools.replace(context, DraftToolSurface.Page, original)
        }
    }

    @Test fun engineShortcutSwitchesTheRecognitionSource() {
        val original = DraftTools.entriesFor(DraftToolSurface.Page).value
        try {
            DraftTools.reset(context, DraftToolSurface.Page)
            setTestContent {
                VoiceLinkTheme {
                    Scaffold {
                        TestSpeakPage(ready(), TextFieldValue("保留草稿"), {}, {}, SpeakInputSource.Keyboard, {},
                            SpeakSendMode.Manual, {})
                    }
                }
            }
            compose.onNodeWithTag("engine").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("engine-tabs").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("engine-tabs").assertExists()
            compose.onNodeWithText(context.getString(R.string.settings_engine_cloud)).performClick()
            compose.waitForIdle()
            assertEquals(SpeakInputSource.Cloud, currentTestSession.source)
            screenshot("draft-toolbar-engine")
        } finally {
            DraftTools.replace(context, DraftToolSurface.Page, original)
        }
    }

    @Test fun savePhraseShortcutStoresTheDraftAndReportsDuplicates() {
        val original = DraftTools.entriesFor(DraftToolSurface.Page).value
        try {
            DraftTools.reset(context, DraftToolSurface.Page)
            setTestContent {
                VoiceLinkTheme {
                    Scaffold {
                        TestSpeakPage(ready(), TextFieldValue("这句要留着"), {}, {}, SpeakInputSource.Keyboard, {},
                            SpeakSendMode.Manual, {})
                    }
                }
            }
            compose.waitUntil(5_000) {
                val nodes = compose.onAllNodesWithTag("save-phrase").fetchSemanticsNodes()
                nodes.isNotEmpty() && !nodes.first().config.contains(SemanticsProperties.Disabled)
            }
            compose.onNodeWithTag("save-phrase").performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.draft_tool_phrase_saved)).assertExists()
            compose.onNodeWithText(context.getString(R.string.assist_phrase_duplicate)).assertDoesNotExist()
            compose.onNodeWithTag("save-phrase").performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.assist_phrase_duplicate)).assertExists()
            screenshot("draft-toolbar-save-phrase")
        } finally {
            DraftTools.replace(context, DraftToolSurface.Page, original)
        }
    }

    @Test fun draftToolbarSettingsDragReordersAndPersists() {
        val original = DraftTools.entriesFor(DraftToolSurface.Page).value
        try {
            DraftTools.reset(context, DraftToolSurface.Page)
            setTestContent { VoiceLinkTheme { DraftToolbarPage(Modifier.fillMaxSize()) } }
            val before = DraftTools.entriesFor(DraftToolSurface.Page).value.map { it.tool.id }
            val grip = compose.onNodeWithTag("draft-tool-grip-assist")
            val rowHeight = grip.fetchSemanticsNode().boundsInRoot.height
            grip.performTouchInput {
                swipe(start = center, end = center + androidx.compose.ui.geometry.Offset(0f, rowHeight * 2f),
                    durationMillis = 400)
            }
            compose.waitForIdle()
            val after = DraftTools.entriesFor(DraftToolSurface.Page).value.map { it.tool.id }
            assertTrue("dragging must persist a new order", after != before)
            assertTrue("the dragged tool leaves its first slot", after.indexOf(DraftTool.Assist.id) > 0)
            screenshot("draft-toolbar-reordered")
        } finally {
            DraftTools.replace(context, DraftToolSurface.Page, original)
        }
    }

    private fun openRecognitionSettings() {
        compose.onNodeWithTag("nav-Settings").performClick()
        compose.onNode(
            (hasText("语音识别") or hasText("Voice recognition")) and hasClickAction()
        ).performClick()
    }

    private fun enableAutomaticSending() {
        compose.runOnIdle { currentTestSession.changeSendMode(SpeakSendMode.Automatic) }
    }

    private fun selectRecognitionEngine(source: SpeakInputSource) {
        val label = if (source == SpeakInputSource.Cloud) R.string.settings_engine_cloud else R.string.settings_engine_local
        compose.onNode(hasText(context.getString(label)) and hasClickAction()).performClick()
    }

    private lateinit var currentTestSession: SpeakSession

    @Composable
    private fun TestVoiceLinkContent(state: ClientState, draft: TextFieldValue,
        onDraftChange: (TextFieldValue) -> Unit, onSend: () -> Unit,
        onHistoryAction: (OutboxKey, String) -> Unit, connectionActions: @Composable () -> Unit,
        onReconnect: () -> Unit = {}, sharedText: String? = null, shareVersion: Int = 0,
        translationModel: seeyuer.sayin.translation.TranslationModel? = null) {
        val context = LocalContext.current
        val callback by rememberUpdatedState(onDraftChange)
        val session = remember {
            (if (translationModel == null) SpeakSession(context, draft, { callback(it) })
            else SpeakSession(context, draft, { callback(it) }, translationModel = translationModel))
                .also { it.selectSource(SpeakInputSource.Keyboard) }
        }
        currentTestSession = session
        val externalDraft by rememberUpdatedState(draft)
        LaunchedEffect(draft) { session.changeDraft(externalDraft) }
        SideEffect { session.onSendReady = { onSend() } }
        DisposableEffect(session) { onDispose { session.close() } }
        val phrases = rememberTestPhraseLibrary()
        VoiceLinkContent(state, session, phrases, onSend, onHistoryAction, connectionActions, onReconnect,
            sharedText = sharedText, shareVersion = shareVersion)
    }

    @Composable
    private fun TestSpeakPage(state: ClientState, draft: TextFieldValue, onDraftChange: (TextFieldValue) -> Unit,
        onSend: () -> Unit, source: SpeakInputSource, onSourceChange: (SpeakInputSource) -> Unit,
        sendMode: SpeakSendMode, onConnect: () -> Unit,
        cloudConfig: seeyuer.sayin.asr.CloudConfiguration? = null, onConfigureCloud: () -> Unit = {}) {
        val context = LocalContext.current
        val session = remember { SpeakSession(context, draft, onDraftChange).also { it.selectSource(source); it.changeSendMode(sendMode) } }
        currentTestSession = session
        SideEffect { session.updateReceiver(state); session.onSendReady = { onSend() } }
        DisposableEffect(session) { onDispose { session.close() } }
        SpeakPage(state, session, rememberTestPhraseLibrary(), onSend, onConnect, cloudConfig, onConfigureCloud)
    }

    private fun setTestContent(modelRoot: File? = null, content: @Composable () -> Unit) {
        compose.setContent {
            testFocusManager = LocalFocusManager.current
            // UI preferences belong to an isolated test namespace, never the user settings.
            val isolated = remember {
                object : android.content.ContextWrapper(compose.activity) {
                    override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("ui-test-$name", mode)
                    override fun getNoBackupFilesDir() = modelRoot ?: super.getNoBackupFilesDir()
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
        paused = false, target = "scope", active = true, autoTextSupported = true,
        foregroundApplication = ForegroundApplication("Test editor"))
    private fun item(state: String, id: String) = OutboxItem(id, "host", "device", "scope", null, 0,
        state, null, null, 1794480000000L)
}
