package seeyuer.sayin.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import seeyuer.sayin.designsystem.VoiceLinkTheme
import seeyuer.sayin.ui.settings.PolishSettingsPage

class PolishUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsExposeServiceAndAutomaticControls() {
        val session = SpeakSession(compose.activity)
        try {
            compose.setContent {
                VoiceLinkTheme {
                    PolishSettingsPage(session, Modifier.fillMaxSize(), rememberScrollState())
                }
            }
            compose.onNodeWithTag("polish-base-url").assertExists()
            compose.onNodeWithTag("polish-key").assertExists()
            compose.onNodeWithTag("polish-save").assertExists()
            compose.onNodeWithTag("polish-test").assertExists()
            compose.onNodeWithTag("polish-auto").assertExists()
            compose.onNodeWithTag("polish-app-rules").assertExists()
        } finally {
            session.close()
        }
    }

    @Test fun missingConfigurationOffersTheSettingsEntryFromHomeSheet() {
        val session = SpeakSession(compose.activity)
        try {
            session.polish.clear()
            compose.waitUntil(5_000) { !session.polish.ready }
            compose.setContent {
                VoiceLinkTheme {
                    PolishSheet(session, show = true, onDismiss = {}, onConfigure = {})
                }
            }
            compose.onNodeWithTag("polish-open-settings").assertExists()
        } finally {
            session.close()
        }
    }
}
