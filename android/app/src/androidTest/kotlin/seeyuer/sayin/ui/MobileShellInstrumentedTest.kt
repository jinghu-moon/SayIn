package seeyuer.sayin.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.view.WindowManager
import org.junit.Before
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.sayin.R
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.OutboxItem
import seeyuer.sayin.designsystem.VoiceLinkTheme
import top.yukonga.miuix.kmp.basic.Text
import java.io.File

@RunWith(AndroidJUnit4::class)
class MobileShellInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun keepTestActivityVisible() {
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun tabsPreserveDraftAndNeverTriggerTransportActions() {
        var sends = 0
        var actions = 0
        compose.setContent {
            var draft by remember { mutableStateOf(TextFieldValue("Draft 原文")) }
            VoiceLinkTheme {
                VoiceLinkContent(ClientState(connection = Connection.OFFLINE), draft, { draft = it },
                    { sends++ }, { _, _ -> actions++ }, { Text("Pairing slot") })
            }
        }
        compose.onNodeWithTag("draft").performTextInput(" + edit")
        for (tab in listOf("History", "Connection", "Settings", "Speak")) {
            compose.onNodeWithTag("nav-$tab").performClick()
            compose.onNodeWithTag("page-$tab").assertIsDisplayed()
        }
        compose.onNodeWithTag("draft").assertTextContains("Draft 原文", substring = true)
        compose.onNodeWithText(context.getString(R.string.send_to_computer)).performScrollTo().assertIsNotEnabled()
        assertEquals(0, sends)
        assertEquals(0, actions)
    }

    @Test fun sendRequiresExplicitClickAndReadyReceiver() {
        var sends = 0
        compose.setContent {
            VoiceLinkTheme {
                VoiceLinkContent(ready(), TextFieldValue("confirm me"), {}, { sends++ }, { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("nav-History").performClick()
        compose.onNodeWithTag("nav-Speak").performClick()
        assertEquals(0, sends)
        compose.onNodeWithText(context.getString(R.string.send_to_computer)).performScrollTo().performClick()
        assertEquals(1, sends)
    }

    @Test fun uncertainReceiptHasNoRetryAndReviewKeepsRequestIdentity() {
        val callbacks = mutableListOf<Pair<String, String>>()
        val unknown = item("review", "review-id")
        val uncertain = item("uncertain", "uncertain-id")
        compose.setContent {
            VoiceLinkTheme {
                VoiceLinkContent(ready().copy(history = listOf(unknown, uncertain)), TextFieldValue(), {}, {},
                    { id, action -> callbacks += id to action }, {})
            }
        }
        compose.onNodeWithTag("nav-History").performClick()
        compose.onAllNodesWithText(context.getString(R.string.retry_same_request)).assertCountEquals(1)
        compose.onNodeWithText(context.getString(R.string.retry_same_request)).performScrollTo().performClick()
        assertEquals(listOf("review-id" to "retry"), callbacks)
        compose.onNodeWithText(context.getString(R.string.uncertain_warning)).performScrollTo().assertIsDisplayed()
    }

    @Test fun productionPagesRenderInBothLanguagesAndThemesWithLargeText() {
        var locale by mutableStateOf("zh-Hans")
        var dark by mutableStateOf(false)
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList.forLanguageTags(locale)) }
            val localized = context.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, fontScale)) {
                VoiceLinkTheme(dark) {
                    VoiceLinkContent(ready().copy(history = listOf(item("injected", "12345678"))),
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
        }
    }

    private fun ready() = ClientState(connection = Connection.ONLINE, paired = true, address = "192.168.1.23:7788",
        paused = false, target = "scope", active = true)
    private fun item(state: String, id: String) = OutboxItem(id, "host", "device", "scope", null, 0,
        state, null, null, 1794480000000L)
}
