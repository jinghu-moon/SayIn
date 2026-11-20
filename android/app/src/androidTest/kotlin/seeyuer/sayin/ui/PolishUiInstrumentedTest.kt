package seeyuer.sayin.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.ui.settings.PolishSettingsPage
import seeyuer.sayin.ui.settings.PolishServicesPage
import top.yukonga.miuix.kmp.basic.Scaffold

class PolishUiInstrumentedTest {
    @get:Rule(order = 0) val foregroundHost = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val host = "${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
            instrumentation.uiAutomation.executeShellCommand("am start -W -n $host").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsExposeServiceAndAutomaticControls() {
        val session = SpeakSession(compose.activity)
        try {
            compose.setContent {
                VoiceLinkTheme {
                    PolishSettingsPage(session, Modifier.fillMaxSize(), rememberScrollState(), {}, {})
                }
            }
            compose.onNodeWithTag("polish-services").assertExists()
            compose.onNodeWithTag("polish-usage").assertExists()
            compose.onNodeWithTag("polish-auto").assertExists()
            compose.onNodeWithTag("polish-app-rules").assertExists()
        } finally {
            session.close()
        }
    }

    @Test fun missingConfigurationOffersTheSettingsEntryFromHomeSheet() {
        val session = SpeakSession(compose.activity)
        try {
            check(compose.activity.packageName == "seeyuer.sayin.imagetest")
            compose.waitUntil(5_000) { !session.polish.saving }
            session.polish.configurations.map { it.id }.forEach { id ->
                compose.runOnIdle { session.polish.deleteConfig(id) }
                compose.waitUntil(5_000) { !session.polish.saving }
            }
            val show = mutableStateOf(false)
            compose.setContent {
                VoiceLinkTheme {
                    Scaffold { PolishSheet(session, show = show.value, onDismiss = {}, onConfigure = {}) }
                }
            }
            compose.runOnIdle { show.value = true }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("polish-open-settings").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("polish-open-settings").assertExists()
        } finally {
            session.close()
        }
    }

    @Test fun consecutiveAddsKeepBothServicesAndRestoreAfterReload() {
        check(compose.activity.packageName == "seeyuer.sayin.imagetest")
        val session = SpeakSession(compose.activity)
        try {
            compose.setContent {
                VoiceLinkTheme { PolishServicesPage(session, Modifier.fillMaxSize(), rememberScrollState()) }
            }
            compose.waitUntil(5_000) { !session.polish.saving }
            val before = session.polish.configurations.size
            listOf("Audit A" to "first-key", "Audit B" to "second-key").forEachIndexed { index, (name, key) ->
                compose.onNodeWithTag("polish-add").performScrollTo().performClick()
                compose.onNodeWithTag("polish-config-name").performScrollTo().performTextReplacement(name)
                compose.onNodeWithTag("polish-key").performScrollTo().performTextReplacement(key)
                compose.onNodeWithTag("polish-save").performScrollTo().performClick()
                compose.waitUntil(5_000) { session.polish.configurations.size == before + index + 1 && !session.polish.saving }
            }
            val loaded = seeyuer.sayin.polish.PolishConfigStore(compose.activity).load() as seeyuer.sayin.polish.PolishConfigLoad.Ready
            assertEquals(listOf("Audit A", "Audit B"), loaded.set.configurations.takeLast(2).map { it.name })
            assertEquals(listOf("first-key", "second-key"), loaded.set.configurations.takeLast(2).map { it.key })
        } finally {
            session.close()
        }
    }
}
