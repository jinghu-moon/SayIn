package seeyuer.sayin.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Assert.*
import kotlinx.coroutines.*
import seeyuer.sayin.data.VoiceLinkRepository
import seeyuer.sayin.data.HostDiscovery
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientIssue
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.PairedTarget
import seeyuer.sayin.designsystem.VoiceLinkTheme
import top.yukonga.miuix.kmp.basic.Scaffold

/** Production composables, semantic/bounds assertions; no screenshots or real transport/input. */
@RunWith(AndroidJUnit4::class)
class ConnectionPageInstrumentedTest {
    @get:Rule(order = 0) val foregroundHost = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -W -n ${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/androidx.activity.ComponentActivity"
            ).use { FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private fun ready() = ClientState(connection = Connection.ONLINE, paired = true, address = "192.168.1.20", port = 7788,
        paused = false, active = true, target = "scope", revision = "1")

    @Test fun cardsExposeIndependentConnectAndPairingEditor() {
        val desktop = PairedTarget("8cd97d2d-9805-4022-bef6-e3d07fe106bd", "pair-a", "192.168.1.20", 7788, "DESKTOP-01", "Desk")
        val laptop = PairedTarget("89944111-9904-4c1d-bcfe-d89681d5fddb", "pair-b", "192.168.1.21", 7788, "LAPTOP-02")
        var state by mutableStateOf(ready().copy(selectedHostId = desktop.hostId, pairedTargets = listOf(desktop, laptop)))
        var connectionId: String? = null
        var renamed: Pair<String, String>? = null
        var selected: String? = null
        compose.setContent { VoiceLinkTheme { Scaffold {
            MobileConnectionPage(state,
                onSelect = { selected = it },
                onConnect = { connectionId = it },
                onRename = { id, alias -> renamed = id to alias },
                onForget = {},
                onAcknowledge = { state = state.copy(pairingCompletedHostId = null) },
                onRetryPairing = {}, onScan = {}, onPair = { _, _, _ -> }, onCancel = {},
                onUpdateAddress = { _, _, _ -> }, onReplace = {}, maintenance = {},
            )
        } } }
        compose.onNodeWithText("DESKTOP-01", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.connection_current_target), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("computer-connect-${laptop.hostId}").performClick()
        assertEquals(laptop.hostId, connectionId)
        assertNull(selected)
        compose.runOnIdle { state = state.copy(pairingCompletedHostId = laptop.hostId) }
        compose.onNodeWithText(laptop.hostId).assertIsDisplayed()
        compose.onNodeWithTag("computer-alias").performTextInput("Work")
        compose.onNodeWithText(text(R.string.connection_make_target)).performClick()
        compose.onNodeWithTag("save-computer").performScrollTo().performClick()
        assertEquals(laptop.hostId to "Work", renamed)
        assertEquals(laptop.hostId, selected)
    }

    @Test fun firstConnectionShowsThreeStepsAndOnlyExplicitPairClickSubmits() {
        var requests = 0
        compose.setContent { VoiceLinkTheme { Column {
            val state = ClientState(connection = Connection.OFFLINE)
            ConnectionDetails(state, {}, {
                ConnectionActions(state, {}, { _, _, _ -> requests++ }, {}, { _, _ -> }, {})
            })
        } } }
        compose.onNodeWithText(text(R.string.connect_step_open)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connect_step_approve)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.scan_qr)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.use_pair_code)).performClick()
        compose.onNodeWithTag("computer-address").performTextInput("192.168.1.20:7788")
        compose.onNodeWithTag("pairing-code").performTextInput("12345678")
        assertEquals(0, requests)
        compose.onNodeWithText(text(R.string.pair_computer)).assertIsEnabled().performClick()
        assertEquals(1, requests)
    }

    @Test fun approvalHasCancelAndNeverPromisesReadiness() {
        var cancels = 0
        val state = ClientState(connection = Connection.APPROVAL)
        compose.setContent { VoiceLinkTheme {
            ConnectionDetails(state, {}, {
                ConnectionActions(state, {}, { _, _, _ -> }, { cancels++ }, { _, _ -> }, {})
            })
        } }
        compose.onNodeWithText(text(R.string.waiting_approval)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connect_ready)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.scan_qr)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.cancel_pairing)).performClick()
        assertEquals(1, cancels)
    }

    @Test fun desktopStateDrivesSameCardAndCannotBeResumedFromPhone() {
        var state by mutableStateOf(ready())
        var reconnects = 0
        compose.setContent { VoiceLinkTheme { ConnectionDetails(state, { reconnects++ }, {}) } }
        val cases = listOf(
            ready() to R.string.connect_ready,
            ready().copy(paused = true, pauseReason = "paused") to R.string.receiver_paused,
            ready().copy(paused = true, pauseReason = "outcome_review_required") to R.string.connection_review_label,
            ready().copy(recovering = true) to R.string.mobile_recovering,
            ready().copy(active = false) to R.string.not_active_device,
            ready().copy(target = null) to R.string.connection_unavailable_label,
            ready().copy(storageHealthy = false) to R.string.connection_storage_label)
        for ((value, label) in cases) {
            compose.runOnIdle { state = value }
            compose.onNodeWithTag("connection-receive-status").assertTextEquals(text(label))
            compose.onNodeWithTag("connection-retry").assertDoesNotExist()
        }
        assertEquals(0, reconnects)
    }

    @Test fun automaticReconnectOnlyOffersRetryDuringBackoff() {
        var state by mutableStateOf(ready().copy(connection = Connection.CONNECTING))
        var reconnects = 0
        compose.setContent { VoiceLinkTheme { ConnectionDetails(state, { reconnects++ }, {}) } }
        compose.onNodeWithTag("connection-retry").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(connection = Connection.OFFLINE, discovering = true) }
        compose.onNodeWithTag("connection-retry").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(discovering = false) }
        compose.onNodeWithTag("connection-retry").performClick()
        assertEquals(1, reconnects)
        compose.runOnIdle { state = state.copy(issue = ClientIssue.LOCAL_NETWORK_PERMISSION) }
        compose.onNodeWithTag("connection-retry").assertDoesNotExist()
    }

    @Test fun addressUpdateAndForgetRequireExplicitActions() {
        val state = ready().copy(connection = Connection.OFFLINE)
        var updated: Pair<String, String>? = null
        var forgotten = 0
        compose.setContent { VoiceLinkTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ConnectionDetails(state, {}, {
                ConnectionActions(state, {}, { _, _, _ -> error("Must not pair") }, {},
                    { address, port -> updated = address to port }, { forgotten++ })
            })
        } } }
        compose.onNodeWithText(text(R.string.connection_update_address)).performClick()
        compose.onNodeWithTag("computer-address").performTextReplacement("192.168.1.21:8899")
        assertNull(updated)
        compose.onNodeWithText(text(R.string.connection_save_address)).performScrollTo().performClick()
        assertEquals("192.168.1.21" to "8899", updated)
        compose.onNodeWithText(text(R.string.unpair_computer)).performScrollTo().performClick()
        assertEquals(0, forgotten)
        compose.onNodeWithTag("unpair-cancel").performClick()
        assertEquals(0, forgotten)
        compose.onNodeWithText(text(R.string.unpair_computer)).performScrollTo().performClick()
        compose.onNodeWithTag("unpair-confirm").performClick()
        assertEquals(1, forgotten)
    }

    @Test fun permissionRecoveryOffersRequestOrSettingsWithoutPairing() {
        var permanent by mutableStateOf(false)
        var requests = 0
        var settings = 0
        compose.setContent { VoiceLinkTheme { PermissionRecovery(R.string.network_permission_denied, R.string.connect_allow_network,
            permanent, { requests++ }, { settings++ }) } }
        compose.onNodeWithText(text(R.string.connect_allow_network)).performClick()
        assertEquals(1, requests)
        compose.runOnIdle { permanent = true }
        compose.onNodeWithText(text(R.string.connect_open_settings)).performClick()
        assertEquals(1, settings)
        compose.onNodeWithText(text(R.string.scan_qr)).assertDoesNotExist()
    }

    @Test fun connectionTabHasNoDuplicateEntryAndSharesSheetWithoutLosingDraft() {
        compose.setContent {
            val isolated = remember { object : ContextWrapper(compose.activity) {
                override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("connection-refactor-$name", mode)
            } }
            CompositionLocalProvider(LocalContext provides isolated) {
                val session = remember { SpeakSession(isolated, TextFieldValue("Keep this draft"), {}).also { it.selectSource(SpeakInputSource.Keyboard) } }
                DisposableEffect(session) { onDispose { session.close() } }
                VoiceLinkTheme { VoiceLinkContent(ready(), session, rememberTestPhraseLibrary(), {}, { _, _ -> }, {}) }
            }
        }
        compose.onNodeWithTag("nav-Connection").performClick()
        compose.onNodeWithTag("connection-badge").assertDoesNotExist()
        compose.onNodeWithTag("speak-connect-action").assertDoesNotExist()
        compose.onNodeWithTag("connection-sheet").assertDoesNotExist()
        compose.onNodeWithTag("nav-Speak").performClick()
        compose.onNodeWithTag("draft").assertTextContains("Keep this draft")
        compose.onNodeWithTag("speak-connect-action").performClick()
        compose.onNodeWithTag("connection-sheet").assertIsDisplayed()
        compose.onNodeWithTag("connection-receive-status").assertTextEquals(text(R.string.connect_ready))
        compose.onNodeWithTag("close-connection").performClick()
        compose.onNodeWithTag("draft").assertTextContains("Keep this draft")
    }

    @Test fun permissionGrantClearsStaleIssueForUnpairedHostWithoutPairing() = runBlocking {
        val target = context
        val namespace = "connection-permission-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getDatabasePath(name: String) = target.getDatabasePath(namespace + name)
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(namespace + name, mode)
        }
        val permitted = java.util.concurrent.atomic.AtomicBoolean(false)
        val repo = VoiceLinkRepository(isolated, HostDiscovery { _, _ -> error("Unpaired discovery is forbidden") }, { permitted.get() })
        suspend fun waitFor(check: (ClientState) -> Boolean) = withTimeout(5000) {
            while (!check(repo.state.value)) delay(20)
        }
        try {
            waitFor { it.connection == Connection.OFFLINE }
            repo.foreground(true)
            waitFor { it.issue == ClientIssue.LOCAL_NETWORK_PERMISSION }
            permitted.set(true)
            repo.foreground(true)
            waitFor { it.issue == null }
            assertFalse(repo.state.value.paired)
            assertEquals(Connection.OFFLINE, repo.state.value.connection)
        } finally { repo.close() }
    }

    @Test fun narrowEnglishLargeFontKeepsStatusAndActionsWithinCard() {
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList.forLanguageTags("en")) }
            val english = remember { context.createConfigurationContext(configuration) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, 1.5f)) {
                VoiceLinkTheme { Box(Modifier.width(320.dp).fillMaxHeight()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        val state = ready().copy(connection = Connection.OFFLINE)
                        Panel { ConnectionDetails(state, {}, {
                            ConnectionActions(state, {}, { _, _, _ -> }, {}, { _, _ -> }, {})
                        }) }
                    }
                } }
            }
        }
        val card = compose.onNodeWithTag("connection-details").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("connection-computer-name", "connection-receive-status", "connection-retry")) {
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag left", bounds.left >= card.left - 1)
            assertTrue("$tag right", bounds.right <= card.right + 1)
            assertTrue("$tag height", bounds.height > 0)
        }
    }
}
